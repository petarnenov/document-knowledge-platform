"""Actual provider main/phase hooks preserve ordered business work and failures."""
from contextlib import contextmanager, ExitStack
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import test_provider_jobs as fixtures

m = fixtures.m
PHASES = ('PREPARED', 'OLD_BASELINE', 'BEGIN', 'VERIFYING', 'ABORT', 'CRASH', 'RECOVERY',
    'OLD_FACTS_AFTER_RECOVERY', 'SECOND_COMPLETE', 'OLD_FACTS_AFTER_COMPLETE',
    'FINAL_SNAPSHOT', 'FINISH', 'BROWSER_EXIT')


class Recording:
    def __init__(self):
        self.events, self.active, self.note = [], None, None

    def boundary_start(self):return 10

    @contextmanager
    def operation(self, phase, **kwargs):
        self.active = phase
        self.events.append(('STARTED', phase, kwargs['error_code']))
        try:
            yield
        except BaseException as error:
            self.events.append(('FAILED', phase, error))
            raise
        else:
            self.events.append(('SUCCEEDED', phase))
        finally:
            self.active = None

    def failure_note(self, phase, error, **kwargs):
        self.note = phase, error, kwargs


class ProviderLoggingTests(unittest.TestCase):
    setUp = fixtures.JobsTests.setUp

    @contextmanager
    def business(self, work, diagnostics, failed_phase=None):
        error = ValueError('private source failure canary')
        failed = False
        def touch(result=None):
            nonlocal failed
            if not failed and diagnostics.active == 'PROVIDER_JOBS_DM_' + str(failed_phase):
                failed = True
                raise error
            return result
        with ExitStack() as stack:
            for name in ('private', 'health', 'owned'):
                stack.enter_context(patch.object(m.runtime, name, side_effect=lambda *_:touch()))
            stack.enter_context(patch.object(m, 'bound'))
            stack.enter_context(patch.object(m, 'provider', side_effect=lambda *_:touch(self.target)))
            stack.enter_context(patch.object(m.runtime, 'docker', return_value=[{}]))
            command = stack.enter_context(patch.object(m.runtime, 'command', side_effect=lambda *_:touch()))
            stack.enter_context(patch.object(m, 'wait', side_effect=lambda check, _: (touch(), check())[1]))
            stack.enter_context(patch.object(m, 'marker', return_value=self.value))
            stack.enter_context(patch.object(m, 'browser_marker', return_value=True))
            stack.enter_context(patch.object(m, 'no_forbidden_facts', side_effect=lambda *_:touch('e' * 64)))
            stack.enter_context(patch.object(m.d, 'sql', side_effect=lambda _, sql:touch('VERIFYING' if sql.startswith('SELECT state') else 'a' * 64)))
            markers = stack.enter_context(patch.object(m, 'write_marker', side_effect=lambda *_:touch()))
            stack.enter_context(patch.object(m, 'browser_instance', return_value=(self.target, 'image')))
            stack.enter_context(patch.object(m, 'retain_browser_diagnostic'))
            stack.enter_context(patch.object(m, 'retain_dm_diagnostic'))
            browser = Mock();browser.poll.return_value = None;browser.wait.side_effect = lambda **_:touch(0)
            stack.enter_context(patch.object(m.subprocess, 'Popen', return_value=browser))
            yield command, markers, browser, error

    def test_every_real_dm_phase_is_entered_in_original_order_and_success_is_paired(self):
        diagnostics = Recording()
        with tempfile.TemporaryDirectory() as tmp, self.business(Path(tmp), diagnostics) as (command, markers, browser, _):
            result = m.dm_boundary(self.state, Path(tmp), diagnostics)
            self.assertEqual('PASS', result['result'])
            self.assertEqual(['begin', 'abort-now', 'restored', 'finish'], [c.args[1] for c in markers.call_args_list])
            self.assertEqual([['docker','kill','--signal','KILL',self.target], ['docker','start',self.target],
                ['docker','rm','--force',self.target]], [c.args[0] for c in command.call_args_list])
            browser.terminate.assert_called_once()
        expected = []
        for phase in PHASES:
            name = 'PROVIDER_JOBS_DM_' + phase
            expected += [('STARTED', name, name + '_FAILED'), ('SUCCEEDED', name)]
        self.assertEqual(expected, diagnostics.events)
        self.assertIsNone(diagnostics.note)

    def test_each_phase_observes_original_failure_before_terminal_note_and_preserves_cleanup(self):
        for index, phase in enumerate(PHASES):
            diagnostics = Recording()
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as tmp, self.business(Path(tmp), diagnostics, phase) as (_, _, browser, error):
                with self.assertRaises(ValueError) as caught:m.dm_boundary(self.state, Path(tmp), diagnostics)
                self.assertIs(error, caught.exception)
                self.assertEqual(('FAILED', 'PROVIDER_JOBS_DM_' + phase, error), diagnostics.events[-1])
                self.assertEqual(2 * (index + 1), len(diagnostics.events))
                self.assertEqual(('PROVIDER_JOBS_DM_FAILURE', error,
                    {'boundary_started_at':10, 'error_code':'PROVIDER_JOBS_DM_' + phase + '_FAILED'}), diagnostics.note)
                browser.terminate.assert_called_once()

    def test_main_explicit_context_captures_original_failure_before_public_mapping(self):
        diagnostics = Recording()
        failure = OSError('private source failure canary')
        args = ['--directory', 'fresh', '--runtime-directory', 'owned']
        with patch.object(m, 'run', side_effect=failure) as run, patch.object(m.sys, 'stdout', new_callable=io.StringIO) as output, patch.object(m.sys, 'stderr', new_callable=io.StringIO) as errors:
            with self.assertRaises(SystemExit):m.main(diagnostics, args)
            run.assert_called_once_with(Path('fresh'), Path('owned'), diagnostics)
            self.assertIs(failure, diagnostics.events[1][2])
            self.assertEqual('FAILED', diagnostics.events[1][0])
            self.assertEqual('LAUNCH', json.loads(errors.getvalue())['stage'])
            self.assertNotIn('canary', output.getvalue() + errors.getvalue())
        with patch.object(m, 'run', return_value={'result':'PASS'}) as run, patch.object(m.sys, 'stdout', new_callable=io.StringIO) as output:
            m.main(diagnostics, args)
            run.assert_called_once_with(Path('fresh'), Path('owned'), diagnostics)
            self.assertEqual({'result':'PASS'}, json.loads(output.getvalue()))

    def test_optional_broken_phase_scope_cannot_skip_or_suppress_work(self):
        error = KeyboardInterrupt('private interrupt')
        for broken in ('operation', '__enter__', '__exit__', 'suppression'):
            scope = Mock();scope.__enter__ = Mock();scope.__exit__ = Mock(return_value=True)
            diagnostics = Mock();diagnostics.operation.return_value = scope
            if broken == 'operation':diagnostics.operation.side_effect = RuntimeError('sink')
            elif broken == '__enter__':scope.__enter__.side_effect = RuntimeError('sink')
            elif broken == '__exit__':scope.__exit__.side_effect = RuntimeError('sink')
            work = []
            with self.subTest(broken=broken), self.assertRaises(KeyboardInterrupt) as caught:
                with m.diagnostic_scope('PREPARED', diagnostics, dm=True):
                    work.append('entered');raise error
            self.assertEqual(['entered'], work);self.assertIs(error, caught.exception)


if __name__ == '__main__':
    unittest.main()
