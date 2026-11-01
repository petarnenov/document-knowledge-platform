import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
spec = importlib.util.spec_from_file_location('bp03_aggregate', Path(__file__).with_name('bp03-aggregate-linux.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class AggregateLinuxTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / 'source'
        self.source.mkdir()
        self.fixture = self.root / 'fixture'
        self.fixture.mkdir()
        entrypoint = self.source / 'services/browser-bff/bootstrap/src/test/java/org/documentknowledge/browserbff/bootstrap/IngressLinuxAcceptance.java'
        entrypoint.parent.mkdir(parents=True)
        self.cases = ['case-' + str(i) for i in range(32)]
        entrypoint.write_text('\n'.join('run("' + name + '", actual);' for name in self.cases))
        self.log = 'openjdk version "25.0.5"\n' + '\n'.join(
            json.dumps(dict(case=name, result='PASS'), separators=(',', ':')) for name in self.cases)
        self.log += '\n' + json.dumps(dict(profile='dkp338-linux-ingress-component', passed=32, failed=0, skipped=0), separators=(',', ':')) + '\n'
        (self.fixture / 'check.private.log').write_text(self.log)
        for layer in m.LAYERS:
            directory = self.fixture / 'reports' / layer / 'test-results' / 'test'
            directory.mkdir(parents=True)
            suite = ET.Element('testsuite', tests='1', failures='0', errors='0', skipped='0')
            ET.SubElement(suite, 'testcase', name='actual')
            ET.ElementTree(suite).write(directory / 'TEST-example.xml')
        for layer, name in m.FLOORS:
            directory = self.fixture / 'reports' / layer / 'reports' / 'jacoco' / (name + 'CoverageReport')
            directory.mkdir(parents=True, exist_ok=True)
            (directory / (name + 'CoverageReport.xml')).write_text(
                '<report><counter type="LINE" covered="75" missed="25"/>'
                '<counter type="BRANCH" covered="80" missed="20"/></report>')

    def test_results_require_actual_profile_and_every_separate_floor(self):
        result = m.results(self.source, self.fixture)
        self.assertEqual(3, result['counts']['tests'])
        self.assertEqual(self.cases, result['linuxCases'])
        self.assertEqual(17, len(result['coverage']))
        path = next((self.fixture / 'reports').rglob('*CoverageReport.xml'))
        original = path.read_text()
        for bad in [original.replace('covered="75"', 'covered="74"'),
                    original.replace('covered="75" missed="25"', 'covered="0" missed="0"'),
                    original.replace('covered="75"', 'covered="-1"')]:
            path.write_text(bad)
            with self.assertRaises(ValueError):
                m.results(self.source, self.fixture)

    def test_missing_failed_skipped_and_zero_tests_reject(self):
        path = self.fixture / 'reports/bootstrap/test-results/test/TEST-example.xml'
        original = path.read_bytes()
        for key in ('failures', 'errors', 'skipped'):
            root = ET.fromstring(original)
            root.set(key, '1')
            ET.ElementTree(root).write(path)
            with self.assertRaises(ValueError):
                m.results(self.source, self.fixture)
        root = ET.fromstring(original)
        root.set('tests', '2')
        ET.ElementTree(root).write(path)
        with self.assertRaises(ValueError):
            m.results(self.source, self.fixture)
        for tag in ('failure', 'error', 'skipped'):
            root = ET.fromstring(original)
            ET.SubElement(root.find('testcase'), tag)
            ET.ElementTree(root).write(path)
            with self.assertRaises(ValueError):
                m.results(self.source, self.fixture)
        path.unlink()
        with self.assertRaises(ValueError):
            m.results(self.source, self.fixture)
        shutil.copytree(self.fixture, self.root / 'empty')
        for path in (self.root / 'empty').rglob('TEST-*.xml'):
            path.write_text('<testsuite tests="0" failures="0" errors="0" skipped="0"/>')
        with self.assertRaises(ValueError):
            m.results(self.source, self.root / 'empty')

    def test_partial_reordered_duplicate_or_failed_linux_cases_reject(self):
        path = self.fixture / 'check.private.log'
        lines = self.log.splitlines()
        for bad in [lines[:-1], lines + [lines[-1]], lines[:1] + lines[2:],
                    [lines[0], lines[2], lines[1], *lines[3:]],
                    [line.replace('"result":"PASS"', '"result":"FAIL"') for line in lines]]:
            path.write_text('\n'.join(bad))
            with self.assertRaises(ValueError):
                m.results(self.source, self.fixture)

    def test_owner_guard_rejects_other_task_image_or_container(self):
        item = dict(Id='a' * 64, Image='sha256:' + 'b' * 64,
                    Config=dict(Labels={'dkp.issue': '222', 'dkp.check.run': 'run'}))
        with mock.patch.object(m.common, 'inspect', return_value=item):
            self.assertEqual(item, m.owned('a' * 64, 'run', 'sha256:' + 'b' * 64))
            for args in [('foreign', 'run', item['Image']), (item['Id'], 'foreign', item['Image']),
                         (item['Id'], 'run', 'foreign')]:
                with self.assertRaises(ValueError):
                    m.owned(*args)
            item['Config']['Labels']['dkp.issue'] = 'other'
            with self.assertRaises(ValueError):
                m.owned('a' * 64, 'run', 'sha256:' + 'b' * 64)

    def fake_run(self, failure=None):
        evidence = self.root / 'evidence'
        calls = []
        binding = dict(commit='source', fingerprint='fingerprint')
        image = dict(Id='sha256:' + 'b' * 64, Os='linux', Architecture='amd64')
        def command(args):
            calls.append(args)
            if args[:2] == ['docker', 'create']:
                if failure == 'create':
                    raise TimeoutError('PRIVATE_SENTINEL')
                return (('invalid' if failure == 'id' else 'a' * 64)).encode()
            if args[:2] == ['docker', 'rm'] and failure == 'cleanup':
                raise ValueError('PRIVATE_SENTINEL')
            if args[:2] == ['docker', 'cp']:
                parts = args[2].split('/')
                layer, what = parts[-3], parts[-1]
                shutil.copytree(self.fixture / 'reports' / layer / what, Path(args[3]) / what)
            return b''
        def logged(args, path, timeout):
            path.write_text(self.log if failure != 'java' else self.log.replace('25.0.5', '25.0.4'))
            return int(failure == ('build' if args[1] == 'build' else 'check'))
        state = dict(State=dict(Running=False, ExitCode=0))
        with mock.patch.object(m.common, 'local_daemon'), mock.patch.object(m.common, 'snapshot', return_value=binding), \
             mock.patch.object(m, 'runner_binding'), \
             mock.patch.object(m.common, 'command', side_effect=command), \
             mock.patch.object(m.common, 'logged', side_effect=logged), \
             mock.patch.object(m.common, 'inspect', return_value=image), mock.patch.object(m, 'owned', return_value=state):
            if failure in ('build', 'check', 'java', 'id', 'create'):
                with self.assertRaises((ValueError, TimeoutError)):
                    m.run(self.source, evidence)
                result = json.loads((evidence / 'receipt.json').read_text())
            else:
                result = m.run(self.source, evidence)
                if not failure:
                    self.assertEqual('PASS', m.validate(self.source, evidence)['result'])
        return result, calls, evidence, binding

    def test_shared_run_exact_isolation_validation_and_owned_cleanup(self):
        result, calls, evidence, binding = self.fake_run()
        self.assertEqual('PASS', result['result'])
        self.assertEqual('REMOVED', result['cleanup'])
        create = next(call for call in calls if call[:2] == ['docker', 'create'])
        self.assertEqual('none', create[create.index('--network') + 1])
        self.assertEqual('256', create[create.index('--pids-limit') + 1])
        self.assertEqual('2g', create[create.index('--memory') + 1])
        self.assertNotIn('--mount', create)
        self.assertNotIn('--privileged', create)
        self.assertEqual(m.COMMAND, create[-2:])
        self.assertEqual(['docker', 'rm', '--force', 'a' * 64], calls[-1])
        with mock.patch.object(m.common, 'snapshot', return_value=binding):
            directory = self.source / 'tools/ci'
            directory.mkdir(parents=True)
            for name in ('bp03-aggregate-linux.py', 'verify.py', 'bp03-aggregate.Dockerfile'):
                shutil.copyfile(Path(m.__file__).with_name(name), directory / name)
            (evidence / 'check.private.log').write_text('changed')
            with self.assertRaises(ValueError):
                m.validate(self.source, evidence)

    def test_executed_controller_cannot_verify_different_runner_source(self):
        directory = self.source / 'tools/ci'
        directory.mkdir(parents=True)
        for name in ('bp03-aggregate-linux.py', 'verify.py', 'bp03-aggregate.Dockerfile'):
            shutil.copyfile(Path(m.__file__).with_name(name), directory / name)
        m.runner_binding(self.source)
        (directory / 'bp03-aggregate.Dockerfile').write_text('FROM unselected\n')
        with self.assertRaises(ValueError):
            m.runner_binding(self.source)
        shutil.copyfile(Path(m.__file__).with_name('bp03-aggregate.Dockerfile'), directory / 'bp03-aggregate.Dockerfile')
        (directory / 'verify.py').write_text('different')
        with self.assertRaises(ValueError):
            m.runner_binding(self.source)

    def test_build_check_version_identity_and_cleanup_failure_cannot_pass(self):
        for failure in ('build', 'check', 'java', 'id', 'create', 'cleanup'):
            with self.subTest(failure=failure):
                result, calls, evidence, _ = self.fake_run(failure)
                self.assertEqual('FAIL', result['result'])
                expected = 'UNKNOWN' if failure in ('cleanup', 'id', 'create') else 'NOT_CREATED' if failure == 'build' else 'REMOVED'
                self.assertEqual(expected, result['cleanup'])
                self.assertTrue(result['runId'])
                shutil.rmtree(evidence)

    def test_existing_evidence_injected_arguments_and_cli_fail_closed(self):
        with self.assertRaises(ValueError):
            m.run(self.source, self.root)
        with mock.patch.object(sys, 'argv', ['profile', 'run', '--source', str(self.source),
                               '--evidence', str(self.root), '--skip-tests']), self.assertRaises(SystemExit):
            m.main()
        with mock.patch.object(sys, 'argv', ['profile', 'run', '--source', str(self.source), '--evidence', str(self.root)]), \
             mock.patch.object(m, 'run', side_effect=ValueError('PRIVATE_SENTINEL')), mock.patch('sys.stdout', new_callable=io.StringIO) as output, \
             self.assertRaises(SystemExit):
            m.main()
        self.assertNotIn('PRIVATE_SENTINEL', output.getvalue())
        self.assertEqual(dict(profile=m.PROFILE, result='FAIL'), json.loads(output.getvalue()))

    def test_workflow_uses_shared_runner_and_leaves_full_foundation_unchanged(self):
        root = Path(__file__).parents[2]
        workflow = (root / '.github/workflows/bp03-aggregate.yml').read_text()
        for action in ('run', 'validate'):
            self.assertIn('python3 tools/ci/bp03-aggregate-linux.py ' + action, workflow)
        self.assertIn('branches-ignore: [dev, main]', workflow)
        self.assertIn('contents: read', workflow)
        self.assertNotIn('pull_request_target', workflow)
        self.assertIn('  pull_request:', (root / '.github/workflows/foundation.yml').read_text())


if __name__ == '__main__':
    unittest.main()
