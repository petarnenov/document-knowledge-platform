"""Real Git and Node syntax/package operation; only Docker control is a unit port."""
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
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location('node_build_test', HERE / 'log-node-build.py')
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)


class NodeBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve() / 'source'; self.root.mkdir()
        (self.root / '.gitignore').write_text('**/build/\n')
        for name in [*('sdks/javascript/' + name for name in b.FILES),
                'tools/runtime/log-node-package.mjs', 'tools/ci/python-runtime-pins.json']:
            target = self.root / name; target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        for command in (['init', '-q'], ['add', '.'], ['-c', 'core.hooksPath=/dev/null',
                '-c', 'commit.gpgsign=false', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                'commit', '-qm', 'fixture']):
            subprocess.run(['git', '-C', str(self.root), *command], check=True, capture_output=True)
        self.creates, self.removed, self.running, self.code, self.tamper = [], [], False, 0, None
        self.real_run = subprocess.run

    def docker(self, *args, **kwargs):
        if args[:2] == ('context', 'inspect'): return b'"unix:///fixture/docker.sock"'
        if args[:2] == ('image', 'inspect'):
            return json.dumps([{'Id':'sha256:' + 'a' * 64, 'Os':'linux', 'Architecture':'amd64'}]).encode()
        if args[0] == 'create':
            self.creates.append(args)
            for flag in ('--init', '--read-only', '--cap-drop', '--pids-limit'): self.assertIn(flag, args)
            self.assertEqual('none', args[args.index('--network') + 1])
            self.assertNotIn('/var/run/docker.sock', ' '.join(args))
            return ('b' * 64).encode()
        if args[:2] == ('container', 'inspect'):
            operation = next(value.split('=', 1)[1] for value in self.creates[-1] if value.startswith('dkp.logging.operation-id='))
            return json.dumps([{'Id':'b' * 64, 'Image':'sha256:' + 'a' * 64,
                'Config':{'Labels':{'dkp.issue':'457', 'dkp.logging.operation':'node-build', 'dkp.logging.operation-id':operation}},
                'State':{'Running':self.running, 'ExitCode':self.code}}]).encode()
        if args[0] == 'rm': self.removed.append(args[1]); return b''
        raise AssertionError('Unexpected Docker control call')

    def package_start(self, args, **kwargs):
        if args[:2] != ['docker', 'start']: return self.real_run(args, **kwargs)
        mounts = [value for value in self.creates[-1] if value.startswith('type=bind,src=')]
        def mounted(destination):
            return Path(next(value.split(',src=', 1)[1].split(',dst=', 1)[0] for value in mounts if ',dst=' + destination in value))
        if self.running: raise subprocess.TimeoutExpired(args, 120)
        if self.code: return subprocess.CompletedProcess(args, self.code)
        output = mounted('/output')
        result = self.real_run(['node', str(mounted('/package.mjs')), '--source', str(mounted('/source')),
            '--output', str(output)], **kwargs)
        if self.tamper == 'record':
            record = json.loads((output / 'package-record.json').read_text()); record['files'][0]['bytes'] = True
            (output / 'package-record.json').write_text(json.dumps(record))
        elif self.tamper == 'output': (output / 'sdk/src/wire.js').write_text('wrong')
        elif self.tamper == 'source': (self.root / 'sdks/javascript/src/wire.js').write_text('changed')
        return result

    @contextlib.contextmanager
    def controlled(self):
        with patch.object(b, 'docker', side_effect=self.docker), patch.object(b.subprocess, 'run', side_effect=self.package_start): yield

    def test_original_two_builds_real_checks_inventory_manifest_and_observations(self):
        with self.controlled(): first, second = b.build(self.root), b.build(self.root)
        self.assertNotEqual(first['buildId'], second['buildId'])
        self.assertEqual(json.loads(Path(first['manifest']).read_text())['sourceFingerprint'],
            json.loads(Path(second['manifest']).read_text())['sourceFingerprint'])
        for result in (first, second):
            self.assertEqual('NOT_STARTED', result['sharedStoreDelivery'])
            directory = Path(result['artifact']).parent
            inventory = json.loads(Path(result['artifact']).read_text())
            self.assertEqual(13, len(inventory['files']))
            for row in inventory['files']:
                raw = (directory / 'package-output' / row['path']).read_bytes()
                self.assertEqual(row['sha256'], b.hashlib.sha256(raw).hexdigest())
                self.assertEqual(row['bytes'], len(raw))
            self.assertEqual(Path(result['manifest']).read_bytes(), (directory / 'package-output/sdk/manifest.json').read_bytes())
            observed = json.loads(Path(result['observations']).read_text())
            self.assertEqual(7, observed['filesChecked']); self.assertEqual(result['buildId'], observed['buildId'])
            self.assertNotEqual(result['buildId'], observed['runId'])
            self.assertEqual('REMOVED', json.loads((directory / 'package-operation.json').read_text())['cleanup'])
            self.assertEqual(result['manifestSha256'], json.loads(Path(result['receipt']).read_text())['manifestSha256'])
        self.assertEqual(['b' * 64] * 2, self.removed)

    def test_failed_or_running_operation_never_sealed_or_live_owner_removed(self):
        for running, code in ((False, 2), (True, 0)):
            self.running, self.code = running, code
            with self.subTest(running=running), self.controlled(), self.assertRaises((ValueError, subprocess.TimeoutExpired)):
                b.build(self.root)
        self.assertEqual(['b' * 64], self.removed)
        self.assertEqual([], list((self.root / 'build').rglob('receipt.json')))

    def test_changed_original_and_outputs_never_sealed(self):
        for tamper in ('record', 'output', 'source'):
            self.tamper = tamper
            with self.subTest(tamper=tamper), self.controlled(), self.assertRaises(ValueError): b.build(self.root)
        self.assertEqual([], list((self.root / 'build').rglob('receipt.json')))

    def test_closed_record_types_layout_hash_and_alias(self):
        with self.controlled(): result = b.build(self.root)
        directory = Path(result['artifact']).parent
        # Remove only the manifest added after package verification, recreating that boundary.
        (directory / 'package-output/sdk/manifest.json').unlink()
        prepared = json.loads((directory / 'prepared.json').read_text())
        expected = {name:b.hashlib.sha256((self.root / 'sdks/javascript' / name).read_bytes()).hexdigest() for name in b.FILES}
        path = directory / 'package-output/package-record.json'; good = path.read_bytes()
        b.verify_package(directory, expected, prepared)
        for raw in (b'{"schemaVersion":1,"schemaVersion":1}', b'{}',
                good.replace(b'"node":"24.22.1"', b'"node":"22.0.0"')):
            path.write_bytes(raw)
            with self.assertRaises(ValueError): b.verify_package(directory, expected, prepared)
        path.write_bytes(good)
        extra = directory / 'package-output/foreign'; extra.write_text('preserve')
        with self.assertRaises(ValueError): b.verify_package(directory, expected, prepared)
        extra.unlink()
        target = directory / 'package-output/sdk/src/wire.js'; target.unlink()
        target.symlink_to(self.root / 'sdks/javascript/src/wire.js')
        with self.assertRaises(ValueError): b.verify_package(directory, expected, prepared)

    def test_privilege_remote_and_cli_guards(self):
        for environment, uid in (({}, 0), ({'DOCKER_HOST':'tcp://remote'}, 10001), ({'DOCKER_CONTEXT':'remote'}, 10001)):
            with patch.dict(os.environ, environment, clear=True), patch.object(b.os, 'getuid', return_value=uid), \
                    patch.object(b, 'docker', side_effect=AssertionError('No provider access')) as provider:
                with self.assertRaises(ValueError): b.execute_package(self.root, self.root.parent / 'unused')
                provider.assert_not_called()
        with patch.object(b, 'build', return_value={'result':'BUILT'}), patch.object(sys, 'argv', ['build', '--source', str(self.root)]), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, b.main())
        with patch.object(b, 'build', side_effect=ValueError('secret-canary')), patch.object(sys, 'argv', ['build', '--source', str(self.root)]), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, b.main())
        self.assertNotIn('secret-canary', output.getvalue())
        with patch.object(b.subprocess, 'check_output', return_value=b'fixed') as call:
            self.assertEqual(b'fixed', b.docker('version')); self.assertEqual(['docker', 'version'], call.call_args.args[0])


if __name__ == '__main__': unittest.main()
