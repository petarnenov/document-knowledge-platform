"""Real original compiler/archive installation and immutable layout regressions."""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import test_log_tool_build as fixtures

spec = importlib.util.spec_from_file_location('original_tool_install_test', fixtures.HERE / 'log-tool-install.py')
i = importlib.util.module_from_spec(spec)
spec.loader.exec_module(i)


class ToolInstallTests(unittest.TestCase):
    setUp = fixtures.ToolBuildTests.setUp
    thaw = fixtures.ToolBuildTests.thaw
    docker = fixtures.ToolBuildTests.docker
    start = fixtures.ToolBuildTests.start
    controlled = fixtures.ToolBuildTests.controlled
    build = fixtures.ToolBuildTests.build
    material = fixtures.ToolBuildTests.material

    def prepared(self):
        self.base.chmod(0o700)
        result, _, _, expected = self.material()
        target = self.base / 'installation'
        return result, expected, target

    def installed(self):
        result, expected, target = self.prepared()
        receipt = i.install(Path(result['artifact']), Path(result['finalized']), Path(result['receipt']), target, **expected)
        return result, expected, target, receipt

    def test_original_layout_and_identity_survive_install_and_private_runtime_changes(self):
        original, expected, target, installed = self.installed()
        actual = i.verify_installation(target, installed['installationSha256'])
        self.assertEqual(original['buildId'], actual['manifest']['buildId'])
        self.assertEqual('NOT_STARTED', installed['sharedStoreDelivery'])
        self.assertEqual(Path(original['artifact']).read_bytes(), (target / 'provider-tool.zip').read_bytes())
        self.assertEqual(Path(original['finalized']).read_bytes(), (target / 'finalized.json').read_bytes())
        self.assertEqual(b'public fixture', (target / 'source/public/док.txt').read_bytes())
        self.assertEqual(0o555, (target / 'source/public/executable').stat().st_mode & 0o777)
        self.assertEqual(0o444, (target / 'compiled/provider-jobs.pyc').stat().st_mode & 0o777)
        self.assertEqual(0o555, (target / 'source/tools/runtime').stat().st_mode & 0o777)
        private = target / i.PRIVATE
        (private / 'owned').mkdir(mode=0o700)
        (private / 'owned/control').write_text('private synthetic control')
        (private / 'owned/link').symlink_to(self.base / 'absent')
        # No read, adoption or inventory assertion about the private leaf contents.
        self.assertEqual(actual, i.verify_installation(target, installed['installationSha256']))
        with self.assertRaises(ValueError):i.install(Path(original['artifact']), Path(original['finalized']), Path(original['receipt']), target, **expected)

    def test_invalid_original_never_creates_installation(self):
        result, expected, target = self.prepared()
        for key in ('expected_build_id', 'expected_manifest_sha256', 'expected_artifact_sha256'):
            changed = {**expected, key: 'not-an-original-identity'}
            with self.subTest(key=key), self.assertRaises(ValueError):
                i.install(Path(result['artifact']), Path(result['finalized']), Path(result['receipt']), target, **changed)
            self.assertFalse(target.exists())
        bad = self.base / 'bad.json';bad.write_bytes(b'{"schemaVersion":1,"schemaVersion":1}')
        with self.assertRaises(ValueError):i.install(Path(result['artifact']), bad, Path(result['receipt']), target, **expected)
        self.assertFalse(target.exists())

    def test_canonical_parent_and_exclusive_target_refuse_foreign_paths(self):
        result, expected, target = self.prepared()
        call = lambda path: i.install(Path(result['artifact']), Path(result['finalized']), Path(result['receipt']), path, **expected)
        for path in (Path('relative'), self.base / 'bad,comma', self.base / 'bad\ncontrol'):
            with self.assertRaises(ValueError):call(path)
        self.base.chmod(0o755)
        with self.assertRaises(ValueError):call(target)
        self.base.chmod(0o700)
        alias = self.base / 'alias';alias.symlink_to(self.base, target_is_directory=True)
        with self.assertRaises(ValueError):call(alias / 'installation')
        target.symlink_to(self.base / 'missing')
        with self.assertRaises(ValueError):call(target)

    def test_immutable_tamper_wrong_digest_and_unknown_inventory_fail_before_execution(self):
        _, _, target, installed = self.installed()
        digest = installed['installationSha256']
        with self.assertRaises(ValueError):i.verify_installation(target, 'f' * 64)
        for name, mode in (('source/public/empty', 0o600), ('source/tools', 0o700), ('installation.json', 0o600)):
            path = target / name;old = path.stat().st_mode & 0o777;path.chmod(mode)
            with self.subTest(name=name), self.assertRaises(ValueError):i.verify_installation(target, digest)
            path.chmod(old)
        path = target / 'source/public/empty';path.chmod(0o600);path.write_bytes(b'changed');path.chmod(0o444)
        with self.assertRaises(ValueError):i.verify_installation(target, digest)
        path.chmod(0o600);path.write_bytes(b'');path.chmod(0o444)
        for name, kind in (('unknown', 'file'), ('foreign', 'directory'), ('alias', 'link')):
            path = target / name
            if kind == 'file':path.write_text('foreign')
            elif kind == 'directory':path.mkdir()
            else:path.symlink_to(target / 'manifest.json')
            with self.subTest(kind=kind), self.assertRaises(ValueError):i.verify_installation(target, digest)
            if kind == 'directory':path.rmdir()
            else:path.unlink()
        path = target / 'source/local-data/unknown';path.write_text('not allowed sibling')
        with self.assertRaises(ValueError):i.verify_installation(target, digest)
        path.unlink()
        self.assertEqual(installed['buildId'], i.verify_installation(target, digest)['record']['buildId'])

    def test_declared_inventory_is_closed_bounded_and_matches_original(self):
        _, _, target, installed = self.installed()
        path = target / 'installation.json';original = json.loads(path.read_bytes())
        def changed(value):
            path.chmod(0o600);raw = i.v.m.canonical(value);path.write_bytes(raw);path.chmod(0o444)
            return i.v.sha(raw)
        cases = []
        for key, value in (('schemaVersion', True), ('extra', 1), ('files', [])):
            cases.append({**original, key:value})
        for key, value in (('bytes', True), ('bytes', 129 * 1024 * 1024), ('executable', 1),
                ('path', '../escaped'), ('path', 'source/' + 'x' * 301), ('sha256', 'wrong'), ('extra', 1)):
            bad = copy.deepcopy(original);bad['files'][0][key] = value;cases.append(bad)
        cases.append({**original, 'files':list(reversed(original['files']))})
        cases.append({**original, 'files':[original['files'][0]] * 4105})
        cases.append({**original, 'files':original['files'] + [original['files'][-1]]})
        cases.append({**original, 'files':original['files'][1:]})
        bad = copy.deepcopy(original);bad['files'][0]['sha256'] = 'f' * 64;cases.append(bad)
        for number, bad in enumerate(cases):
            digest = changed(bad)
            with self.subTest(case=number), self.assertRaises(ValueError):i.verify_installation(target, digest)
        digest = changed(original)
        self.assertEqual(installed['installationSha256'], digest)
        i.verify_installation(target, digest)

    def test_private_leaf_and_file_ownership_are_not_adopted(self):
        _, _, target, installed = self.installed()
        with patch.object(i.os, 'getuid', return_value=os.getuid() + 1), self.assertRaises(ValueError):
            i.verify_installation(target, installed['installationSha256'])
        leaf = target / i.PRIVATE;leaf.chmod(0o755)
        with self.assertRaises(ValueError):i.verify_installation(target, installed['installationSha256'])

    def test_cli_success_and_failure_print_only_safe_original_facts(self):
        result, expected, target = self.prepared()
        args = ['log-tool-install.py', '--artifact', result['artifact'], '--finalized', result['finalized'],
            '--receipt', result['receipt'], '--installation-directory', str(target), '--build-id', result['buildId'],
            '--manifest-sha256', result['manifestSha256'], '--artifact-sha256', result['artifactSha256'],
            '--compiler-image-pin', result['compilerImage']['imagePin'], '--compiler-image-id', result['compilerImage']['imageId']]
        with patch('sys.argv', args), patch('sys.stdout', new_callable=io.StringIO) as output:
            self.assertEqual(0, i.main());self.assertEqual(result['buildId'], json.loads(output.getvalue())['buildId'])
        with patch('sys.argv', args), patch('sys.stdout', new_callable=io.StringIO) as output:
            self.assertEqual(1, i.main());self.assertEqual({'result':'FAIL','code':'LOG_TOOL_INSTALL_INVALID'}, json.loads(output.getvalue()))
            self.assertNotIn(str(self.base), output.getvalue())


if __name__ == '__main__':
    unittest.main()
