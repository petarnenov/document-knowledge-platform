"""Original installed bytecode plus real SDK/TLS; not provider/store adoption."""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
from unittest.mock import Mock, patch
import uuid

import test_log_tool_install as installations
import test_log_python_deliver as deliveries


class ToolRunTests(unittest.TestCase):
    def setUp(self):
        self.sdk_fixture = deliveries.PythonDeliveryTests('test_real_compiled_sdk_tls_exact_receipts_and_original_build_observations')
        self.sdk_fixture.setUp();self.addCleanup(self.sdk_fixture.doCleanups)
        self.tool = installations.ToolInstallTests('test_original_layout_and_identity_survive_install_and_private_runtime_changes')
        self.tool.setUp();self.addCleanup(self.tool.doCleanups)
        self.tool.base.chmod(0o700)
        self.old_cache = sys.dont_write_bytecode
        self.addCleanup(setattr, sys, 'dont_write_bytecode', self.old_cache)
        self.old_modules = {key:value for key,value in sys.modules.items()
            if key in ('dkp_tool_diagnostics', 'dkp_provider_jobs')}
        self.addCleanup(self.reset_tools)
        for source in installations.fixtures.HERE.glob('*.py'):
            if not source.name.startswith('test_'):
                (self.tool.root / 'tools/runtime' / source.name).write_bytes(source.read_bytes())
        # Execute the real original provider code. Only its external business port
        # is controlled below; this is not genuine provider acceptance.
        subprocess.run(['git', '-C', str(self.tool.root), 'add', '.'], check=True, capture_output=True)
        self.built, _, _, self.expected = self.tool.material()
        self.target = self.tool.base / 'installation'
        self.installed = installations.i.install(Path(self.built['artifact']), Path(self.built['finalized']),
            Path(self.built['receipt']), self.target, **self.expected)
        spec = importlib.util.spec_from_file_location('tool_runner_unit', installations.fixtures.HERE / 'log-tool-run.py')
        self.r = importlib.util.module_from_spec(spec);spec.loader.exec_module(self.r)
        # Unit controls exercise the checkout helper for unambiguous coverage.
        # Assert identical sealed bytes before supplying its installed metadata.
        # Actual installed forward-only invocation is a separate child process.
        for name in ('log-tool-run.py', 'log-tool-install.py', 'log-tool-verify.py',
                'log-tool-compile.py', 'log-build-manifest.py', 'log-python-deliver.py'):
            self.assertEqual((installations.fixtures.HERE / name).read_bytes(),
                (self.target / 'source/tools/runtime' / name).read_bytes())
        self.r.__file__ = str(self.target / 'source/tools/runtime/log-tool-run.py')
        self.r.HERE = self.target / 'source/tools/runtime'
        sdk = self.sdk_fixture
        self.config = dict(schemaVersion=1, installationDirectory=str(self.target),
            installationSha256=self.installed['installationSha256'], sdkArtifact=sdk.built['artifact'],
            sdkArtifactSha256=json.loads(Path(sdk.built['receipt']).read_bytes())['artifact']['sha256'],
            sdkManifestSha256=sdk.built['manifestSha256'], runId=str(uuid.uuid4()), instanceId=str(uuid.uuid4()),
            correlationId=str(uuid.uuid4()), endpoint=sdk.config['endpoint'], connectAddress='127.0.0.1',
            caFile=str(sdk.cert), producerToken='p' * 48, spoolDirectory=str(self.tool.base / 'queue'),
            statusFile=str(self.tool.base / 'status.json'), finishWaitSeconds=2)
        self.config_path = self.tool.base / 'producer.json'
        self.save()
        self.directory = self.target / self.r.i.PRIVATE / 'acceptance'
        self.runtime = self.target / self.r.i.PRIVATE / 'runtime'

    def reset_tools(self):
        for name in ('dkp_tool_diagnostics', 'dkp_provider_jobs'):sys.modules.pop(name, None)
        sys.modules.update(self.old_modules)

    def save(self):
        self.config_path.write_bytes(self.r.v.m.canonical(self.config));self.config_path.chmod(0o600)

    def execute(self):
        original = self.r.original_module
        def load(name, code, root):
            module = original(name, code, root)
            if name == 'dkp_provider_jobs':
                def business(*_):
                    if getattr(self, 'unit_failure', None) is not None:raise self.unit_failure
                    return {'result':'PASS'}
                module.run = business
            return module
        with patch.object(self.r, 'original_module', side_effect=load):
            return self.r.execute(self.config_path, self.directory, self.runtime)

    def test_installed_original_tool_identity_real_sdk_https_receipts_and_private_status(self):
        with patch('sys.stdout', new_callable=io.StringIO) as output:self.assertEqual(0, self.execute())
        self.assertEqual({'result':'PASS'}, json.loads(output.getvalue()))
        status_path = Path(self.config['statusFile']);status = json.loads(status_path.read_bytes())
        self.assertEqual('COMMITTED', status['result']);self.assertTrue(status['workerStopped'])
        self.assertEqual(0o600, status_path.stat().st_mode & 0o777)
        rows = self.sdk_fixture.rows
        self.assertEqual(6, len(rows));self.assertEqual(set(rows), set(status['eventStates']))
        self.assertTrue(all(row['buildId'] == self.built['buildId'] and row['component'] == 'runtime-tools'
            and row['runId'] == self.config['runId'] and row['instanceId'] == self.config['instanceId'] for row in rows.values()))
        self.assertNotEqual(self.built['buildId'], self.sdk_fixture.built['buildId'])
        self.assertEqual({'PROVIDER_JOBS_MODULE_LOAD', 'PROVIDER_JOBS_ENTRYPOINT', 'PROVIDER_JOBS_LAUNCH'},
            {row['context']['phase'] for row in rows.values()})
        self.assertNotIn(self.config['producerToken'], status_path.read_text())
        self.assertNotIn(str(self.tool.base), status_path.read_text())
        self.assertFalse(list((self.target / 'source').rglob('__pycache__')))
        self.r.i.verify_installation(self.target, self.config['installationSha256'])

    def test_normal_unavailable_endpoint_spools_and_forward_only_restores_same_ids_without_main(self):
        self.sdk_fixture.available = False
        with patch('sys.stdout', new_callable=io.StringIO):self.assertEqual(0, self.execute())
        pending = json.loads(Path(self.config['statusFile']).read_bytes())
        self.assertEqual('PENDING', pending['result']);self.assertTrue(pending['eventStates'])
        selected = set(pending['eventStates'])
        original_requests = len(self.sdk_fixture.requests)
        self.sdk_fixture.reset_imports();self.reset_tools()
        self.sdk_fixture.available = True
        completed = subprocess.run([sys.executable, str(self.target / 'source/tools/runtime/log-tool-run.py'),
            '--configuration', str(self.config_path), '--forward-only'], capture_output=True, timeout=30)
        self.assertEqual(0, completed.returncode, completed.stderr.decode())
        restored = json.loads(Path(self.config['statusFile']).read_bytes())
        self.assertEqual('COMMITTED', restored['result']);self.assertEqual(selected, set(restored['eventStates']))
        self.assertEqual(selected, set(self.sdk_fixture.rows))
        self.assertNotIn('dkp_provider_jobs', sys.modules)
        replayed = [json.loads(raw) for path, raw in self.sdk_fixture.requests[original_requests:] if path.endswith('/batches')]
        self.assertTrue(replayed);self.assertEqual(selected, {row['eventId'] for batch in replayed for row in batch['events']})

    def test_config_is_closed_and_rejects_invalid_id_authority_and_paths_before_mutations(self):
        original = dict(self.config)
        for key, value in (('schemaVersion', True), ('finishWaitSeconds', True), ('finishWaitSeconds', 6),
                ('runId', self.built['buildId']), ('correlationId', 'not-an-id'), ('producerToken', 'bad'),
                ('installationSha256', 'f' * 64), ('sdkArtifactSha256', 'f' * 64), ('extra', 'operator-authority'),
                ('connectAddress', 123), ('connectAddress', 'localhost'), ('endpoint', 123),
                ('spoolDirectory', 'relative'), ('statusFile', str(self.tool.base / 'bad\nfile'))):
            self.config = {**original, key:value};self.save()
            with self.subTest(key=key, value=value), self.assertRaises((ValueError, OSError)):
                self.r.configuration(self.config_path, (self.directory, self.runtime))
            self.assertFalse(Path(original['spoolDirectory']).exists());self.assertFalse(Path(original['statusFile']).exists())
        self.config = original;self.save()
        self.config_path.chmod(0o644)
        with self.assertRaises(ValueError):self.r.configuration(self.config_path)

    def test_reviewed_status_input_spool_and_business_collisions_never_overwrite_originals(self):
        original = dict(self.config)
        inputs = [self.config_path, Path(self.config['caFile']), Path(self.config['sdkArtifact'])]
        saved = {path:path.read_bytes() for path in inputs}
        for target in inputs + [self.target / 'manifest.json', Path(self.config['spoolDirectory']) / 'status', self.directory / 'status', self.runtime / 'status']:
            # Existing valid private parent, then only destination collision varies.
            if not target.parent.exists():target.parent.mkdir(mode=0o700)
            self.config = {**original, 'statusFile':str(target)};self.save()
            with self.subTest(target=target.name), self.assertRaises((ValueError, OSError)):
                self.r.configuration(self.config_path, (self.directory, self.runtime))
            self.assertEqual(saved[inputs[1]], inputs[1].read_bytes());self.assertEqual(saved[inputs[2]], inputs[2].read_bytes())
        self.config = original;self.save()
        hardlink = self.tool.base / 'status-alias';os.link(self.config_path, hardlink)
        self.config['statusFile'] = str(hardlink);self.save()
        with self.assertRaises(ValueError):self.r.configuration(self.config_path)
        hardlink.unlink()
        for target in (self.target, self.directory, self.tool.base):
            self.config = {**original, 'spoolDirectory':str(target)};self.save()
            with self.assertRaises((ValueError, OSError)):self.r.configuration(self.config_path, (self.directory, self.runtime))

    def test_business_exit_identity_survives_failed_finish_status(self):
        original = SystemExit(7)
        self.unit_failure = original
        with patch.object(self.r, 'status_file', side_effect=OSError('private status failure')):
            with self.assertRaises(SystemExit) as caught:self.execute()
        self.assertIs(original, caught.exception)
        self.assertTrue(any(row['eventName'] == 'operation.failed' for row in self.sdk_fixture.rows.values()))
        self.assertFalse(Path(self.config['statusFile']).exists())
        self.assertNotIn('private status failure', json.dumps(self.sdk_fixture.rows))

    def test_status_selection_cap_and_receipt_batches_never_claim_complete_delivery(self):
        modules = self.r.sdk(self.config)
        delivery = self.r.Delivery(self.config, json.loads(Path(self.built['manifest']).read_bytes()), modules)
        ids = [str(uuid.uuid4()) for _ in range(2049)]
        for value in ids:delivery.remember(value)
        delivery.remember(ids[0])
        self.assertEqual(2048, len(delivery.ids));self.assertEqual(1, delivery.omitted)
        result = delivery.finish()
        self.assertEqual('PENDING', result['result']);self.assertEqual(1, result['omittedSelection'])
        self.assertTrue(result['workerStopped'])
        self.assertTrue(all(value == 'UNKNOWN' for value in result['eventStates'].values()))
        requests = [json.loads(raw)['eventIds'] for path, raw in self.sdk_fixture.requests if path.endswith('/receipts')]
        self.assertTrue(requests);self.assertTrue(all(0 < len(batch) <= 32 for batch in requests))
        self.assertNotIn(ids[-1], result['eventStates'])

    def test_fixed_loader_preloaded_aliases_cli_mode_and_new_checkout_are_refused(self):
        for forward, directory, runtime in ((True,self.directory,None), (False,None,self.runtime), (False,self.directory,None)):
            with self.assertRaises(ValueError):self.r.execute(self.config_path, directory, runtime, forward_only=forward)
        sys.modules['dkp_provider_jobs'] = Mock()
        with self.assertRaises(ValueError):self.execute()
        self.reset_tools()
        sys.modules['dkp_logging'] = Mock()
        with self.assertRaises(ValueError):self.execute()
        self.sdk_fixture.reset_imports()
        with patch.object(self.r, '__file__', str(installations.fixtures.HERE / 'log-tool-run.py')), self.assertRaises(ValueError):self.execute()
        with patch.object(self.r.platform, 'python_version', return_value='3.11.0'), self.assertRaises(ValueError):self.execute()
        args = ['log-tool-run.py', '--configuration', str(self.config_path), '--forward-only']
        with patch('sys.argv', args), patch.object(self.r, 'execute', return_value=0) as execute:
            self.assertEqual(0, self.r.main());execute.assert_called_once()
        with patch('sys.argv', args), patch.object(self.r, 'execute', side_effect=ValueError('private-token')), patch('sys.stderr', new_callable=io.StringIO) as output:
            self.assertEqual(1, self.r.main());self.assertNotIn('private-token', output.getvalue())


if __name__ == '__main__':
    unittest.main()
