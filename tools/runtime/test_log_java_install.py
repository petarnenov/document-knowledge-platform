"""Real Git/JAR/copy fixtures; simulated compiler is not actual native build proof."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import test_log_java_build as fixture

spec = importlib.util.spec_from_file_location('java_install', Path(__file__).with_name('log-java-install.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


class JavaInstallTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture.JavaBuildTest(); self.fixture.setUp(); self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.built = self.fixture.build(); self.receipt = Path(self.built['receipt'])
        self.dist = self.root/'services/knowledge-search/bootstrap/build/install/knowledge-search'
        self.parent = self.root.parent/'private'; self.parent.mkdir(mode=0o700)
        self.output = self.parent/'installed'

    def install(self): return m.install(self.root, self.receipt, self.output)

    def test_worker_original_distribution_install_and_service_path_substitution_rejection(self):
        self.fixture.component='structure-inspector'
        self.fixture.settings['record']['component']='structure-inspector'
        self.built=self.fixture.build();self.receipt=Path(self.built['receipt'])
        self.dist=self.root/'workers/structure-inspector/build/install/structure-inspector'
        result=self.install()
        self.assertEqual('structure-inspector',result['component'])
        self.assertEqual('workers/structure-inspector/build/libs/bootstrap.jar',result['artifact']['path'])
        self.assertEqual(result,m.verify_installed(self.output))
        record=self.output/'install.json'
        for invalid in ('services/structure-inspector/bootstrap/build/libs/bootstrap.jar','workers/foreign/build/libs/bootstrap.jar'):
            changed=json.loads(json.dumps(result));changed['artifact']['path']=invalid
            record.write_bytes(m.m.canonical(changed))
            with self.assertRaises(ValueError):m.verify_installed(self.output)
        record.write_bytes(m.m.canonical(result))
        self.assertEqual(result,m.verify_installed(self.output))

    def test_exact_distribution_private_manifest_inventory_and_no_process_start(self):
        result = self.install()
        self.assertEqual('INSTALLED', result['result']); self.assertEqual(self.built['buildId'], result['buildId'])
        self.assertEqual('NOT_STARTED', result['process']); self.assertEqual('NOT_STARTED', result['sharedStoreDelivery'])
        self.assertEqual(Path(self.built['manifest']).read_bytes(), (self.output/'manifest.json').read_bytes())
        for name, entry in result['files'].items():
            installed = self.output/'distribution'/name
            self.assertEqual((self.dist/name).read_bytes(), installed.read_bytes())
            self.assertEqual(entry['mode'], installed.stat().st_mode & 0o777)
            self.assertEqual(len(installed.read_bytes()), entry['bytes'])
        self.assertEqual(result, json.loads((self.output/'install.json').read_text()))
        self.assertEqual(result,m.verify_installed(self.output))
        self.assertEqual(0, (self.output/'install.json').stat().st_mode & 0o077)
        with self.assertRaises(ValueError): self.install()

    def test_installed_copy_recheck_survives_workspace_changes_and_rejects_content_or_mode_drift(self):
        installed=self.install()
        (self.root/'source.txt').write_text('later development revision')
        self.assertEqual(installed,m.verify_installed(self.output))
        jar=self.output/'distribution/lib/fixture-library.jar';raw=jar.read_bytes();jar.write_bytes(b'tampered')
        with self.assertRaises(ValueError):m.verify_installed(self.output)
        jar.write_bytes(raw);jar.chmod(0o644)
        with self.assertRaises(ValueError):m.verify_installed(self.output)
        jar.chmod(0o600);(self.output/'manifest.json').write_bytes(b'wrong manifest')
        with self.assertRaises(ValueError):m.verify_installed(self.output)

    def test_source_receipt_artifact_manifest_and_distribution_mismatch_refuse_output(self):
        for path in (self.root/'source.txt', self.receipt, Path(self.built['artifact']), Path(self.built['manifest']), self.dist/'lib/bootstrap.jar'):
            with self.subTest(path=path.name):
                original = path.read_bytes(); path.write_bytes(original+b'changed')
                try:
                    with self.assertRaises(Exception): self.install()
                    self.assertFalse(self.output.exists())
                finally: path.write_bytes(original)

    def test_dependency_change_after_original_build_is_rejected_before_copy(self):
        (self.dist/'lib/fixture-library.jar').write_bytes(b'dependency changed after compilation')
        with self.assertRaises(ValueError): self.install()
        self.assertFalse(self.output.exists())

    def test_original_distribution_record_is_private_exact_and_duplicate_aware(self):
        path = self.receipt.with_name('distribution.json'); raw = path.read_bytes()
        for value in (b'{"schemaVersion":1,"schemaVersion":1}', b'[]',
                json.dumps({**json.loads(raw), 'schemaVersion':True}).encode(),
                json.dumps({**json.loads(raw), 'extra':'unsupported'}).encode()):
            path.write_bytes(value)
            with self.assertRaises(ValueError): self.install()
            self.assertFalse(self.output.exists())
        path.write_bytes(raw); path.chmod(0o644)
        with self.assertRaises(ValueError): self.install()

    def test_links_special_files_unexpected_paths_writable_files_and_missing_launcher_reject(self):
        unexpected = self.dist/'extra.txt'; unexpected.write_text('not part of distribution')
        with self.assertRaises(ValueError): self.install()
        unexpected.unlink()
        linked = self.dist/'lib/linked.jar'; linked.symlink_to(self.dist/'lib/bootstrap.jar')
        with self.assertRaises(ValueError): self.install()
        linked.unlink()
        fifo = self.dist/'lib/pipe.jar'; os.mkfifo(fifo)
        with self.assertRaises(ValueError): self.install()
        fifo.unlink()
        jar = self.dist/'lib/fixture-library.jar'; jar.chmod(0o666)
        with self.assertRaises(ValueError): self.install()
        jar.chmod(0o600)
        launcher = self.dist/'bin/knowledge-search.bat'; launcher.unlink()
        with self.assertRaises(ValueError): self.install()
        self.assertFalse(self.output.exists())

    def test_private_canonical_receipt_and_private_external_fresh_destination_required(self):
        self.receipt.chmod(0o644)
        with self.assertRaises(ValueError): self.install()
        self.receipt.chmod(0o600)
        alias = self.parent/'receipt.json'; alias.symlink_to(self.receipt)
        with self.assertRaises(ValueError): m.install(self.root, alias, self.output)
        for destination in (Path('relative'), self.root/'inside', self.parent/'missing/child'):
            with self.subTest(destination=str(destination)):
                with self.assertRaises(Exception): m.install(self.root, self.receipt, destination)
        self.parent.chmod(0o755)
        with self.assertRaises(ValueError): self.install()
        self.parent.chmod(0o700)
        self.output.symlink_to(self.root.parent/'absent')
        with self.assertRaises(ValueError): self.install()

    def test_source_changes_while_copying_never_leave_success_receipt(self):
        original = m.original; calls = 0
        def changed(*args):
            nonlocal calls
            calls += 1
            if calls == 2: (self.root/'source.txt').write_text('changed during copy')
            return original(*args)
        with patch.object(m, 'original', side_effect=changed):
            with self.assertRaises(ValueError): self.install()
        self.assertTrue(self.output.is_dir()); self.assertFalse((self.output/'install.json').exists())

    def test_changed_copy_is_detected_before_success(self):
        original = m.distribution
        def changed(directory, component):
            if directory.is_relative_to(self.parent): (directory/'lib/fixture-library.jar').write_bytes(b'copy drift')
            return original(directory, component)
        with patch.object(m, 'distribution', side_effect=changed):
            with self.assertRaises(ValueError): self.install()
        self.assertFalse((self.output/'install.json').exists())

    def test_file_and_total_bounds_and_entry_count_are_enforced_before_output(self):
        with patch.object(m, 'MAX_FILE', 1):
            with self.assertRaises(ValueError): self.install()
        with patch.object(m, 'MAX_TOTAL', 1):
            with self.assertRaises(ValueError): self.install()
        for number in range(513): (self.dist/'lib'/('extra'+str(number)+'.jar')).write_bytes(b'x')
        with self.assertRaises(ValueError): self.install()
        self.assertFalse(self.output.exists())

    def test_cli_success_and_fixed_failure_do_not_expose_private_details(self):
        args = ['log-java-install.py', '--source', str(self.root), '--receipt', str(self.receipt), '--output', str(self.output)]
        for fail in (False, True):
            stream = io.StringIO()
            with patch.object(sys, 'argv', args), contextlib.redirect_stdout(stream): result = m.main()
            self.assertEqual(int(fail), result)
            value = json.loads(stream.getvalue())
            if fail: self.assertEqual({'result':'FAIL', 'code':'LOG_JAVA_INSTALL_INVALID'}, value)
            else: self.assertEqual('INSTALLED', value['result'])


if __name__ == '__main__': unittest.main()
