"""Real Git, Python compiler and sourceless ZIP tests; Docker control is a unit port."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


b = load('python_build_test', 'log-python-build.py')
c = load('python_compiler_test', 'log-python-compile.py')


class PythonBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve() / 'source'
        self.root.mkdir()
        (self.root / '.gitignore').write_text('**/build/\n')
        for name in b.MODULES:
            source = ROOT / 'sdks/python/dkp_logging' / (name + '.py')
            target = self.root / source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        for name in ('tools/runtime/log-python-compile.py', 'tools/ci/python-runtime-pins.json'):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        for command in (['init', '-q'], ['add', '.'], ['-c', 'core.hooksPath=/dev/null',
                '-c', 'commit.gpgsign=false', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                'commit', '-qm', 'fixture']):
            subprocess.run(['git', '-C', str(self.root), *command], check=True, capture_output=True)
        self.creates, self.removed, self.running, self.code, self.tamper = [], [], False, 0, None
        self.real_run = subprocess.run

    def docker(self, *args, **kwargs):
        if args[:2] == ('context', 'inspect'):
            return b'"unix:///fixture/docker.sock"'
        if args[:2] == ('image', 'inspect'):
            return json.dumps([{'Id':'sha256:' + 'a' * 64, 'Os':'linux', 'Architecture':'amd64'}]).encode()
        if args[0] == 'create':
            self.creates.append(args)
            self.assertIn('none', args)
            self.assertIn('--read-only', args)
            self.assertIn('--cap-drop', args)
            self.assertNotIn('/var/run/docker.sock', ' '.join(args))
            return ('b' * 64).encode()
        if args[:2] == ('container', 'inspect'):
            operation = next(value.split('=', 1)[1] for value in self.creates[-1] if value.startswith('dkp.logging.operation-id='))
            return json.dumps([{'Id':'b' * 64, 'Image':'sha256:' + 'a' * 64,
                'Config':{'Labels':{'dkp.issue':'457', 'dkp.logging.operation':'python-build', 'dkp.logging.operation-id':operation}},
                'State':{'Running':self.running, 'ExitCode':self.code}}]).encode()
        if args[0] == 'rm':
            self.removed.append(args[1])
            return b''
        raise AssertionError('Unexpected Docker control call')

    def compiler_start(self, args, **kwargs):
        if args[:2] != ['docker', 'start']:
            return self.real_run(args, **kwargs)
        mounts = [value for value in self.creates[-1] if value.startswith('type=bind,src=')]
        source = Path(next(value.split(',src=', 1)[1].split(',dst=', 1)[0] for value in mounts if ',dst=/source,' in value))
        output = Path(next(value.split(',src=', 1)[1].split(',dst=', 1)[0] for value in mounts if ',dst=/output' in value))
        if self.running:
            raise subprocess.TimeoutExpired(args, 60)
        if not self.code:
            c.compile_package(source, output)
            if self.tamper == 'source':
                (self.root / 'sdks/python/dkp_logging/wire.py').write_text('changed input')
            elif self.tamper == 'record':
                record = json.loads((output / 'compiler-record.json').read_text())
                record['modules'][0]['compiledBytes'] = True
                (output / 'compiler-record.json').write_text(json.dumps(record))
            elif self.tamper == 'bytecode':
                (output / 'dkp_logging/wire.pyc').write_bytes(b'wrong')
        return SimpleNamespace(returncode=self.code)

    @contextlib.contextmanager
    def controlled(self):
        with patch.object(b, 'docker', side_effect=self.docker), patch.object(b.subprocess, 'run', side_effect=self.compiler_start):
            yield

    def test_real_compiler_original_manifest_seal_and_sourceless_import_two_builds(self):
        with self.controlled():
            first, second = b.build(self.root), b.build(self.root)
        self.assertNotEqual(first['buildId'], second['buildId'])
        manifests = [json.loads(Path(value['manifest']).read_text()) for value in (first, second)]
        self.assertEqual(manifests[0]['sourceFingerprint'], manifests[1]['sourceFingerprint'])
        self.assertEqual('NOT_STARTED', first['sharedStoreDelivery'])
        for value in (first, second):
            manifest = Path(value['manifest']).read_bytes()
            with zipfile.ZipFile(value['artifact']) as archive:
                self.assertEqual(6, len(archive.infolist()))
                self.assertEqual(manifest, archive.read(b.MANIFEST))
                self.assertTrue(all(name.endswith('.pyc') or name == b.MANIFEST for name in archive.namelist()))
            # A fresh isolated process cannot accidentally reuse checkout imports.
            code = ('import sys;sys.path.insert(0,sys.argv[1]);'
                'from dkp_logging.producer import LogProducer;from dkp_logging.spool import FileLogSpool;'
                'assert LogProducer.__module__=="dkp_logging.producer";'
                'import dkp_logging.wire as w;assert w.__file__.startswith(sys.argv[1]+"/");'
                'assert "sdks/python/dkp_logging/wire.py"==w.Wire.prepare.__code__.co_filename')
            subprocess.run([sys.executable, '-I', '-c', code, value['artifact']], check=True, timeout=10, capture_output=True)
            observations = json.loads(Path(value['observations']).read_text())
            self.assertEqual(5, observations['filesCompiled'])
            self.assertEqual(value['buildId'], observations['buildId'])
            self.assertGreaterEqual(b.m.timestamp(observations['finishedAt']), b.m.timestamp(observations['startedAt']))
            self.assertNotEqual(observations['runId'], value['buildId'])
            receipt = json.loads(Path(value['receipt']).read_text())
            self.assertEqual(value['manifestSha256'], receipt['manifestSha256'])
            self.assertEqual('REMOVED', json.loads(Path(value['manifest']).with_name('compiler-operation.json').read_text())['cleanup'])
        self.assertEqual(['b' * 64] * 2, self.removed)

    def test_compiler_failure_and_unknown_live_process_do_not_seal_or_remove_live_owner(self):
        for running, code in ((False, 2), (True, 0)):
            self.running, self.code = running, code
            with self.subTest(running=running), self.controlled(), self.assertRaises((ValueError, subprocess.TimeoutExpired)):
                b.build(self.root)
        self.assertEqual(['b' * 64], self.removed)
        self.assertEqual([], list((self.root / 'build').rglob('receipt.json')))

    def test_source_and_compiler_output_tampering_refused(self):
        for tamper in ('record', 'bytecode', 'source'):
            self.tamper = tamper
            with self.subTest(tamper=tamper), self.controlled(), self.assertRaises(ValueError):
                b.build(self.root)
        self.assertEqual([], list((self.root / 'build').rglob('receipt.json')))

    def test_record_extra_entries_duplicate_fields_and_wrong_magic_refused(self):
        source, output = self.root.parent / 'input', self.root.parent / 'output'
        source.mkdir(); output.mkdir()
        expected = {}
        for name in b.MODULES:
            data = (self.root / 'sdks/python/dkp_logging' / (name + '.py')).read_bytes()
            (source / (name + '.py')).write_bytes(data)
            expected[name] = b.hashlib.sha256(data).hexdigest()
        c.compile_package(source, output)
        directory = output.parent
        output.rename(directory / 'compiler-output')
        output = directory / 'compiler-output'
        record_path = output / 'compiler-record.json'
        good = record_path.read_bytes()
        b.verify_compiled(directory, expected)
        for raw in [b'{"schemaVersion":1,"schemaVersion":1}', b'{}',
                good.replace(b'"python":"3.14.10"', b'"python":"3.11.2"'),
                good.replace(b'"magic":"', b'"magic":"invalid')]:
            record_path.write_bytes(raw)
            with self.assertRaises(ValueError):
                b.verify_compiled(directory, expected)
        record_path.write_bytes(good)
        (output / 'foreign').write_text('preserve')
        with self.assertRaises(ValueError):
            b.verify_compiled(directory, expected)

    def test_compiler_guards_version_layout_source_change_and_cli(self):
        source, output = self.root.parent / 'compile-source', self.root.parent / 'compile-output'
        source.mkdir(); output.mkdir()
        with patch.object(c.platform, 'python_version', return_value='3.11.2'), self.assertRaises(ValueError):
            c.compile_package(source, output)
        with self.assertRaises(ValueError):
            c.compile_package(source, output)
        for name in b.MODULES:
            (source / (name + '.py')).write_text('value=1\n')
        arguments = ['compiler', '--source', str(source), '--output', str(output)]
        with patch.object(sys, 'argv', arguments), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, c.main())
            self.assertEqual(1, c.main())
        (self.root.parent / 'alias').symlink_to(source)
        with self.assertRaises(ValueError):
            c.compile_package(self.root.parent / 'alias', output)

    def test_build_cli_fixed_error_and_actual_docker_wrapper(self):
        with patch.object(b, 'build', return_value={'result':'BUILT'}), patch.object(sys, 'argv', ['build', '--source', str(self.root)]), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, b.main())
        with patch.object(b, 'build', side_effect=ValueError('secret')), patch.object(sys, 'argv', ['build', '--source', str(self.root)]), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, b.main())
        self.assertNotIn('secret', output.getvalue())
        with patch.object(b.subprocess, 'check_output', return_value=b'fixed') as call:
            self.assertEqual(b'fixed', b.docker('version'))
            self.assertEqual(['docker', 'version'], call.call_args.args[0])
        with self.assertRaises(ValueError):
            b.unique([('id', 1), ('id', 2)])

    def test_root_caller_cannot_launch_privileged_compiler_even_on_local_daemon(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(b.os, 'getuid', return_value=0), \
                patch.object(b, 'docker', side_effect=AssertionError('No provider access before nonroot guard')) as provider:
            with self.assertRaises(ValueError):
                b.execute_compiler(self.root, self.root.parent / 'unused', {})
            provider.assert_not_called()


if __name__ == '__main__':
    unittest.main()
