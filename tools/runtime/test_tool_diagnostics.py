import importlib.util
import itertools
from contextlib import ExitStack
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock
import uuid

ROOT = Path(__file__).resolve().parents[2]
# Load the pure codec under a fixture-only name. Preloading dkp_logging would
# invalidate original compiled-artifact tests elsewhere in unittest discovery.
wire_spec = importlib.util.spec_from_file_location('tool_port_wire_fixture', ROOT / 'sdks/python/dkp_logging/wire.py')
wire = importlib.util.module_from_spec(wire_spec)
sys.modules[wire_spec.name] = wire
wire_spec.loader.exec_module(wire)
Wire, EVENTS = wire.Wire, wire.EVENTS


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


port = load('tool-diagnostics')
jobs = load('provider-jobs')
IDENTITY = dict(buildId=str(uuid.UUID(int=1)), runId=str(uuid.UUID(int=2)), instanceId=str(uuid.UUID(int=3)),
    component='runtime-tools', gitSha='a' * 40, sourceFingerprint='b' * 64)


def nested_failure():
    secret_local = 'synthetic-secret-canary'
    try:
        raise OSError(secret_local)
    except OSError as cause:
        raise ValueError('/private/synthetic-secret-canary') from cause


class DiagnosticsTests(unittest.TestCase):
    def make(self, **options):
        phases = ['TEST', 'PROVIDER_JOBS_DM_FAILURE'] + ['PROVIDER_JOBS_' + stage.value for stage in jobs.AcceptanceStage]
        errors = ['TEST_FAILED', 'PROVIDER_JOBS_STAGE_FAILED'] + ['PROVIDER_JOBS_DM_' + phase + '_FAILED' for phase in jobs.DM_DIAGNOSTIC_PHASES]
        classes = {cls: 'builtins.' + cls.__name__ for cls in (ValueError, OSError, RuntimeError, KeyboardInterrupt, SystemExit)}
        frames = {nested_failure.__code__: dict(symbol='tests.nested_failure', source='tools/runtime/test_tool_diagnostics.py')}
        vocabulary = dict(events=sorted(EVENTS), phases=phases, errorCodes=errors,
            exceptionClasses=list(classes.values()), dependencies=[], frames=list(frames.values()))
        events = []
        def emit(event):
            events.append(event)
            return 'ACCEPTED'
        ids = itertools.count(100)
        parameters = dict(clock=lambda: 10., now=lambda: '2026-11-04T00:00:00Z', identifier=lambda: str(uuid.UUID(int=next(ids))))
        parameters.update(options)
        diagnostic = port.Diagnostics(Wire(IDENTITY, vocabulary), vocabulary, parameters.pop('emit', emit),
            str(uuid.UUID(int=4)), classes, frames, **parameters)
        return diagnostic, events

    def test_success_facts_and_exact_elapsed_without_cleanup_assumption(self):
        ticks = iter((10., 10.125))
        diagnostic, events = self.make(clock=lambda: next(ticks))
        with diagnostic.operation('TEST', family='build', count=2) as scope:
            self.assertTrue(scope.record(status=201, bytes=32, queueDepth=1, cancelRequested=False, remainingDeadlineMs=50))
        self.assertEqual([e['eventName'] for e in events], ['build.started', 'build.succeeded'])
        self.assertEqual(events[-1]['durationMs'], 125)
        self.assertFalse(events[-1]['cleanupSettled'])
        self.assertEqual(events[-1]['diagnostics']['attributes']['bytes'], 32)
        self.assertEqual(events[-1]['correlationId'], str(uuid.UUID(int=4)))
        self.assertNotEqual(events[0]['eventId'], events[1]['eventId'])
        self.assertEqual(diagnostic.status(), dict(admitted=2, rejected=0, emitFailures=0))

    def test_actual_cause_and_exact_owned_frames_without_private_content(self):
        diagnostic, events = self.make()
        original = None
        try:
            with diagnostic.operation('TEST', error_code='TEST_FAILED'):
                try:
                    nested_failure()
                except ValueError as error:
                    original = error
                    raise
        except ValueError as observed:
            self.assertIs(observed, original)
        failed = events[-1]
        failure = failed['diagnostics']['failure']
        self.assertEqual(failure['exceptionClass'], 'builtins.ValueError')
        self.assertEqual(failure['causeClasses'], ['builtins.OSError'])
        self.assertTrue(failure['stackFrames'])
        self.assertTrue(all(frame['symbol'] == 'tests.nested_failure' for frame in failure['stackFrames']))
        self.assertNotIn('synthetic-secret-canary', repr(events))
        self.assertNotIn('/private/', repr(events))
        self.assertNotIn(original, vars(diagnostic).values())

    def test_unknown_concrete_subclass_is_not_replaced_by_registered_parent(self):
        class Unknown(ValueError):pass
        diagnostic, events = self.make()
        with self.assertRaises(Unknown):
            with diagnostic.operation('TEST'):
                raise Unknown('synthetic-secret-canary')
        self.assertNotIn('exceptionClass', events[-1]['diagnostics']['failure'])
        self.assertGreater(events[-1]['diagnostics']['counts']['unregisteredSymbols'], 0)

    def test_suppressed_context_is_omitted_and_unsuppressed_context_retained(self):
        for suppress in (True, False):
            with self.subTest(suppress=suppress):
                diagnostic, events = self.make()
                with self.assertRaises(ValueError):
                    with diagnostic.operation('TEST'):
                        try:raise OSError('synthetic-secret-canary')
                        except OSError:
                            if suppress:raise ValueError() from None
                            raise ValueError()
                self.assertEqual(events[-1]['diagnostics']['failure']['causeClasses'], [] if suppress else ['builtins.OSError'])

    def test_cause_cycle_and_long_chain_are_bounded(self):
        for cycle in (True, False):
            diagnostic, events = self.make()
            error = ValueError()
            current = error
            for _ in range(8):
                following = ValueError()
                current.__cause__ = following
                current = following
            if cycle:error.__cause__ = error
            diagnostic.failure_note('TEST', error, boundary_started_at=9., error_code='TEST_FAILED')
            self.assertLessEqual(len(events[0]['diagnostics']['failure']['causeClasses']), 4)
            self.assertGreater(events[0]['diagnostics']['counts']['truncatedFields'], 0)

    def test_traceback_traversal_is_bounded(self):
        diagnostic, events = self.make()
        def recurse(n):
            if n:recurse(n-1)
            else:raise ValueError()
        with self.assertRaises(ValueError):
            with diagnostic.operation('TEST'):recurse(40)
        self.assertGreater(events[-1]['diagnostics']['counts']['truncatedFields'], 0)

    def test_cancellation_and_system_exit_keep_original_exit(self):
        for error, name, outcome in ((KeyboardInterrupt(), 'operation.cancelled', 'CANCELLED'),
                (SystemExit(0), 'dependency.succeeded', 'SUCCEEDED'),
                (SystemExit(None), 'dependency.succeeded', 'SUCCEEDED'),
                (SystemExit(0.0), 'dependency.failed', 'FAILED'),
                (SystemExit('0'), 'dependency.failed', 'FAILED'),
                (SystemExit(2), 'dependency.failed', 'FAILED')):
            diagnostic, events = self.make()
            try:
                with diagnostic.operation('TEST', family='dependency'):raise error
            except BaseException as observed:self.assertIs(observed, error)
            self.assertEqual((events[-1]['eventName'], events[-1]['outcome']), (name, outcome))

    def test_note_has_no_invented_start_and_measures_whole_boundary(self):
        ticks = iter((1., 4.5))
        diagnostic, events = self.make(clock=lambda: next(ticks))
        marker = diagnostic.boundary_start()
        diagnostic.failure_note('PROVIDER_JOBS_DM_FAILURE', ValueError(), boundary_started_at=marker,
            error_code='PROVIDER_JOBS_DM_VERIFYING_FAILED')
        self.assertEqual(len(events), 1)
        self.assertEqual((events[0]['eventName'], events[0]['outcome'], events[0]['durationMs']), ('operation.failed', 'FAILED', 3500))
        self.assertEqual(events[0]['diagnostics']['errorCode'], 'PROVIDER_JOBS_DM_VERIFYING_FAILED')

    def test_bad_markers_and_note_identity_reject_without_emission(self):
        diagnostic, events = self.make()
        for marker in (None, float('nan'), float('inf'), -1, 11, True):
            self.assertEqual(diagnostic.failure_note('TEST', ValueError(), boundary_started_at=marker,
                error_code='TEST_FAILED'), 'REJECTED')
        self.assertEqual(diagnostic.failure_note('UNKNOWN', ValueError(), boundary_started_at=1, error_code='TEST_FAILED'), 'REJECTED')
        self.assertEqual(diagnostic.failure_note('TEST', 'not-an-exception', boundary_started_at=1, error_code='TEST_FAILED'), 'REJECTED')
        self.assertEqual(events, [])

    def test_invalid_optional_scope_and_facts_preserve_work(self):
        diagnostic, events = self.make()
        for args in (([], {}), ('UNKNOWN', {}), ('TEST', {'family':'arbitrary'}), ('TEST', {'attempt':-1}), ('TEST', {'error_code':'UNKNOWN'})):
            with diagnostic.operation(args[0], **args[1]):pass
        self.assertFalse(events)
        with diagnostic.operation('TEST') as scope:
            for facts in (dict(message='synthetic-secret-canary'), dict(status=99), dict(count=True), dict(bytes=-1), dict(cleanupSettled=1)):
                self.assertFalse(scope.record(**facts))
            self.assertTrue(scope.record(cleanupSettled=True, count=3, cancelRequested=True))
        self.assertTrue(events[-1]['cleanupSettled'])
        self.assertEqual(events[-1]['count'], 3)

    def test_rejected_scope_does_not_retain_raw_rejected_arguments(self):
        diagnostic, events = self.make()
        scope = diagnostic.operation(['synthetic-secret-canary'], count={'private':'synthetic-secret-canary'})
        self.assertNotIn('synthetic-secret-canary', repr(vars(scope)))
        with scope:pass
        self.assertEqual(events, [])

    def test_logging_callback_codec_clock_and_timestamp_failure_do_not_replace_business_failure(self):
        def broken(*args):raise RuntimeError('synthetic-secret-canary')
        for options in (dict(emit=broken), dict(now=broken), dict(identifier=broken), dict(clock=broken), dict(clock=lambda: float('nan'))):
            diagnostic, events = self.make(**options)
            error = ValueError('original')
            try:
                with diagnostic.operation('TEST'):raise error
            except ValueError as observed:self.assertIs(observed, error)
            self.assertGreater(diagnostic.status()['emitFailures'], 0)
        diagnostic, events = self.make()
        with patch.object(diagnostic._codec, 'prepare', side_effect=broken):
            with diagnostic.operation('TEST'):pass
        self.assertFalse(events)

    def test_rejected_closed_and_duplicate_are_admission_states(self):
        for state in ('REJECTED', 'FULL', 'CLOSED', 'DUPLICATE'):
            diagnostic, events = self.make(emit=lambda event: state)
            with diagnostic.operation('TEST'):pass
            status = diagnostic.status()
            self.assertEqual(status['admitted'] if state == 'DUPLICATE' else status['rejected'], 2)
            self.assertNotIn('committed', status)

    def test_port_performs_no_io(self):
        diagnostic, events = self.make()
        with patch('builtins.open', side_effect=AssertionError('I/O')), patch('socket.socket', side_effect=AssertionError('I/O')), patch('subprocess.run', side_effect=AssertionError('I/O')):
            with diagnostic.operation('TEST'):pass
        self.assertEqual(len(events), 2)

    def test_codec_fixture_does_not_preload_the_runtime_sdk_package(self):
        self.assertEqual(Wire.__module__, 'tool_port_wire_fixture')

    def test_checkpoint_captures_original_error_before_safe_mapping(self):
        diagnostic, events = self.make()
        with self.assertRaises(jobs.AcceptanceFailure) as public:
            with jobs.checkpoint(jobs.AcceptanceStage.DM_BOUNDARY, diagnostic):nested_failure()
        self.assertEqual(jobs.failure_receipt(public.exception)['cause'], 'UNDETERMINED')
        self.assertTrue(public.exception.__suppress_context__)
        self.assertEqual(events[-1]['diagnostics']['failure']['exceptionClass'], 'builtins.ValueError')
        self.assertEqual(events[-1]['diagnostics']['failure']['causeClasses'], ['builtins.OSError'])
        self.assertEqual(events[-1]['phase'], 'PROVIDER_JOBS_DM_BOUNDARY')

    def test_missing_or_broken_optional_hook_does_not_replace_original_behavior(self):
        class Broken:
            def operation(self, *args, **kwargs):raise RuntimeError('synthetic-secret-canary')
        for diagnostics in (None, Broken()):
            with jobs.checkpoint(jobs.AcceptanceStage.OPERATOR, diagnostics):pass
            with self.assertRaises(jobs.AcceptanceFailure) as caught:
                with jobs.checkpoint(jobs.AcceptanceStage.OPERATOR, diagnostics):raise ValueError()
            self.assertEqual(caught.exception.stage, jobs.AcceptanceStage.OPERATOR)

    def test_scope_entry_exit_failures_and_suppression_never_control_owned_work(self):
        for mode in ('entry-fails', 'exit-fails', 'suppresses'):
            for original in (None, ValueError('original'), SystemExit(2), KeyboardInterrupt()):
                with self.subTest(mode=mode, error=type(original).__name__):
                    class Scope:
                        def __enter__(self):
                            if mode == 'entry-fails':raise RuntimeError('diagnostic failure')
                        def __exit__(self, *args):
                            if mode == 'exit-fails':raise RuntimeError('diagnostic failure')
                            return mode == 'suppresses'
                    hook = Mock();hook.operation.return_value = Scope()
                    work, caught = [], None
                    try:
                        with jobs.checkpoint(jobs.AcceptanceStage.OPERATOR, hook):
                            work.append('ran')
                            if original is not None:raise original
                    except BaseException as error:caught = error
                    self.assertEqual(work, ['ran'])
                    if isinstance(original, ValueError):
                        self.assertIsInstance(caught, jobs.AcceptanceFailure)
                        self.assertEqual(caught.stage, jobs.AcceptanceStage.OPERATOR)
                    else:
                        self.assertIs(caught, original)

    def test_dm_failure_note_precedes_mapping_and_preserves_owned_cleanup(self):
        ticks = iter((1., 3.5))
        diagnostic, events = self.make(clock=lambda: next(ticks))
        original = OSError('synthetic-secret-canary')
        with tempfile.TemporaryDirectory() as temporary, ExitStack() as stack:
            work = Path(temporary)
            stack.enter_context(patch.object(jobs, 'bound'))
            stack.enter_context(patch.object(jobs.runtime, 'private'))
            stack.enter_context(patch.object(jobs, 'wait', side_effect=original))
            stack.enter_context(patch.object(jobs, 'browser_instance', return_value=('f' * 64, 'image')))
            stack.enter_context(patch.object(jobs, 'retain_browser_diagnostic'))
            browser = Mock();browser.poll.return_value = None
            stack.enter_context(patch.object(jobs.subprocess, 'Popen', return_value=browser))
            command = stack.enter_context(patch.object(jobs.runtime, 'command'))
            def mapped(*args):
                self.assertEqual(len(events), 1)
                self.assertIs(args[2], original)
            stack.enter_context(patch.object(jobs, 'retain_dm_diagnostic', side_effect=mapped))
            try:
                jobs.dm_boundary({'directory':'/private/fixture'}, work, diagnostic)
            except OSError as error:self.assertIs(error, original)
            else:self.fail('Original error must propagate')
            self.assertEqual(events[0]['eventName'], 'operation.failed')
            self.assertEqual(events[0]['durationMs'], 2500)
            self.assertEqual(events[0]['phase'], 'PROVIDER_JOBS_DM_FAILURE')
            self.assertEqual(events[0]['diagnostics']['errorCode'], 'PROVIDER_JOBS_DM_PREPARED_FAILED')
            self.assertNotIn('synthetic-secret-canary', repr(events))
            command.assert_called_once_with(['docker', 'rm', '--force', 'f' * 64])
            browser.terminate.assert_called_once()

    def test_run_passes_explicit_context_before_operator_refusal(self):
        diagnostic, events = self.make()
        with patch.object(jobs.os, 'getuid', return_value=0), self.assertRaises(jobs.AcceptanceFailure):
            jobs.run('unused', 'unused', diagnostic)
        self.assertEqual([e['phase'] for e in events], ['PROVIDER_JOBS_OPERATOR'] * 2)

    def test_invalid_original_catalog_configuration_is_refused(self):
        vocabulary = dict(phases=['TEST'], errorCodes=[], exceptionClasses=[], frames=[])
        with self.assertRaises(ValueError):
            port.Diagnostics(Mock(), vocabulary, lambda event: 'ACCEPTED', str(uuid.UUID(int=4)), {ValueError:'unknown'}, {})
        with self.assertRaises(ValueError):
            port.Diagnostics(Mock(), vocabulary, lambda event: 'ACCEPTED', str(uuid.UUID(int=4)), {}, {nested_failure.__code__:{'symbol':'unknown','source':'private'}})
        with self.assertRaises(ValueError):
            port.Diagnostics(Mock(), vocabulary, None, str(uuid.UUID(int=4)), {}, {})
        for correlation in (None, 'invalid', str(uuid.UUID(int=0))):
            with self.assertRaises(ValueError):
                port.Diagnostics(Mock(), vocabulary, lambda event:'ACCEPTED', correlation, {}, {})


if __name__ == '__main__':unittest.main()
