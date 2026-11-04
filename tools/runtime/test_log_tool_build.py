"""Actual Git/compiler/archive tests; Docker ownership is a controlled unit port."""
import contextlib
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location('original_tool_build_test', HERE / 'log-tool-build.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
v, c = b.v, b.v.c


class ToolBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / 'source';self.root.mkdir()
        self.addCleanup(self.thaw)
        (self.root / '.gitignore').write_text('**/build/\n')
        for name in ('provider-jobs.py', 'tool-diagnostics.py', 'log-tool-build.py', 'log-tool-verify.py',
                'log-tool-compile.py', 'log-build-manifest.py'):
            relative = 'tools/runtime/' + name
            target = self.root / relative;target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        target = self.root / 'tools/ci/python-runtime-pins.json';target.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / 'tools/ci/python-runtime-pins.json', target)
        (self.root / 'public').mkdir()
        (self.root / 'public/док.txt').write_text('public fixture')
        (self.root / 'public/empty').touch()
        (self.root / 'public/executable').write_text('#!/bin/sh\nexit 0\n')
        (self.root / 'public/executable').chmod(0o755)
        for command in (['init', '-q'], ['add', '.'], ['-c', 'core.hooksPath=/dev/null',
                '-c', 'commit.gpgsign=false', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                'commit', '-qm', 'fixture']):
            subprocess.run(['git', '-C', str(self.root), *command], check=True, capture_output=True)
        self.creates, self.removed = [], []
        self.running, self.exit_code, self.tamper, self.wrong_owner = False, 0, None, False
        self.real_run = subprocess.run

    def thaw(self):
        for path in self.base.rglob('*'):
            if not path.is_symlink():path.chmod(0o700 if path.is_dir() else 0o600)

    def docker(self, *args, **kwargs):
        if args[:2] == ('context', 'inspect'):return b'"unix:///fixture/docker.sock"'
        if args[:2] == ('image', 'inspect'):
            return json.dumps([dict(Id='sha256:' + 'a' * 64, Os='linux', Architecture='amd64')]).encode()
        if args[0] == 'create':
            self.creates.append(args)
            self.assertIn('--read-only', args);self.assertIn('--cap-drop', args)
            self.assertIn('none', args);self.assertNotIn('/var/run/docker.sock', ' '.join(args))
            return ('b' * 64).encode()
        if args[:2] == ('container', 'inspect'):
            operation = next(x.split('=', 1)[1] for x in self.creates[-1] if x.startswith('dkp.logging.operation-id='))
            return json.dumps([{'Id':'b' * 64, 'Image':'sha256:' + 'a' * 64,
                'Config':{'Labels':{'dkp.issue':'foreign' if self.wrong_owner else '457',
                    'dkp.logging.operation':'provider-tool-build', 'dkp.logging.operation-id':operation}},
                'State':{'Running':self.running, 'ExitCode':self.exit_code}}]).encode()
        if args[0] == 'rm':self.removed.append(args[1]);return b''
        raise AssertionError('Unexpected Docker control operation')

    def start(self, args, **kwargs):
        if args[:2] != ['docker', 'start']:return self.real_run(args, **kwargs)
        if self.running:raise subprocess.TimeoutExpired(args, 60)
        mounts = [x for x in self.creates[-1] if x.startswith('type=bind,src=')]
        def mounted(destination):
            return Path(next(x.split(',src=', 1)[1].split(',dst=', 1)[0] for x in mounts if ',dst=' + destination in x))
        source, output = mounted('/source'), mounted('/output')
        if not self.exit_code:
            c.compile_distribution(source, output)
            if self.tamper == 'source':(self.root / 'public/empty').write_text('changed')
            elif self.tamper == 'bytecode':(output / 'provider-jobs.pyc').write_bytes(b'wrong')
            elif self.tamper == 'extra':(output / 'foreign').touch()
            elif self.tamper in ('record', 'locator'):
                path = output / 'compiler-record.json';record = json.loads(path.read_bytes())
                if self.tamper == 'record':record['unknown'] = True
                else:record['modules'][0]['frames'][0]['firstLine'] = False
                path.write_bytes(b.m.canonical(record))
        return SimpleNamespace(returncode=self.exit_code)

    @contextlib.contextmanager
    def controlled(self):
        with patch.object(b, 'docker', side_effect=self.docker), patch.object(b.subprocess, 'run', side_effect=self.start):
            yield

    def build(self):
        with self.controlled():return b.build(self.root)

    def material(self):
        result = self.build()
        finalized = json.loads(Path(result['finalized']).read_bytes())
        receipt = json.loads(Path(result['receipt']).read_bytes())
        expected = dict(expected_build_id=result['buildId'], expected_manifest_sha256=result['manifestSha256'],
            expected_artifact_sha256=result['artifactSha256'], expected_compiler_image=result['compilerImage'])
        return result, finalized, receipt, expected

    def test_two_real_original_builds_preserve_full_public_layout_and_original_identities(self):
        first, second = self.build(), self.build()
        self.assertNotEqual(first['buildId'], second['buildId'])
        manifests = [json.loads(Path(x['manifest']).read_bytes()) for x in (first, second)]
        self.assertEqual(manifests[0]['sourceFingerprint'], manifests[1]['sourceFingerprint'])
        self.assertEqual(manifests[0]['gitSha'], manifests[1]['gitSha'])
        self.assertFalse(manifests[0]['dirty'])
        for result in (first, second):
            self.assertEqual(result['buildId'], result['verified']['buildId'])
            self.assertEqual(result['sharedStoreDelivery'], 'NOT_STARTED')
            with zipfile.ZipFile(result['artifact']) as archive:
                prepared = json.loads(archive.read('prepared.json'))
                self.assertEqual(len(prepared['inputs']) + 5, len(archive.infolist()))
                self.assertEqual(Path(result['manifest']).read_bytes(), archive.read('manifest.json'))
                self.assertEqual(b'', archive.read('source/public/empty'))
                self.assertEqual(b'public fixture', archive.read('source/public/док.txt'))
                self.assertTrue(archive.getinfo('source/public/executable').external_attr >> 16 & 0o111)
                self.assertFalse(any('.git/' in name or 'local-data/' in name for name in archive.namelist()))
            operation = json.loads(Path(result['manifest']).with_name('compiler-operation.json').read_bytes())
            self.assertEqual(('COMPILED', 'REMOVED'), (operation['result'], operation['cleanup']))
        (self.root / 'public/empty').write_text('dirty public source')
        dirty = self.build()
        self.assertTrue(json.loads(Path(dirty['manifest']).read_bytes())['dirty'])
        self.assertEqual(len(self.removed), 3)

    def test_compiler_failure_unknown_live_and_foreign_identity_never_seal_or_remove_foreign(self):
        for running, code, foreign in ((False, 7, False), (True, 0, False), (False, 0, True)):
            self.running, self.exit_code, self.wrong_owner = running, code, foreign
            with self.subTest(running=running, foreign=foreign), self.assertRaises((ValueError, subprocess.TimeoutExpired)):
                self.build()
        self.assertEqual(self.removed, ['b' * 64])
        self.assertFalse(list((self.root / 'build').rglob('receipt.json')))

    def test_changed_source_bad_compiler_record_bytecode_and_unknown_output_are_refused(self):
        for tamper in ('bytecode', 'record', 'locator', 'extra', 'source'):
            self.tamper = tamper
            with self.subTest(tamper=tamper), self.assertRaises(ValueError):self.build()
        self.assertFalse(list((self.root / 'build').rglob('receipt.json')))

    def test_expected_trusted_identity_is_not_derived_from_archive(self):
        result, finalized, receipt, expected = self.material()
        for key, value in (('expected_build_id', str(uuid.uuid4())), ('expected_manifest_sha256', '1' * 64),
                ('expected_artifact_sha256', '2' * 64),
                ('expected_compiler_image', dict(imagePin=expected['expected_compiler_image']['imagePin'], imageId='sha256:' + 'c' * 64))):
            with self.subTest(key=key), self.assertRaises(ValueError):
                v.verify(Path(result['artifact']), finalized, receipt, **{**expected, key:value})
        wrong = copy.deepcopy(receipt);wrong['artifact']['bytes'] = True
        with self.assertRaises(ValueError):v.verify(Path(result['artifact']), finalized, wrong, **expected)
        wrong = copy.deepcopy(finalized);wrong['dependencyRecord'] = [{'unknown':True}]
        with self.assertRaises(ValueError):v.verify(Path(result['artifact']), wrong, receipt, **expected)

    def test_archive_source_mode_bytecode_catalog_manifest_and_member_tampering(self):
        result, finalized, receipt, expected = self.material()
        original = v.archive_members(Path(result['artifact']).read_bytes())
        mutations = ['source', 'mode', 'bytecode', 'catalog', 'manifest', 'unknown', 'missing', 'duplicate-json', 'prepared-path']
        for index, mutation in enumerate(mutations):
            members = {name:data for name, (data, _) in original.items()}
            executable = {name for name, (_, flag) in original.items() if flag}
            if mutation == 'source':members['source/public/empty'] = b'changed'
            elif mutation == 'mode':executable.clear()
            elif mutation == 'bytecode':members['compiled/provider-jobs.pyc'] = b'wrong'
            elif mutation == 'catalog':
                record = json.loads(members['compiler-record.json']);record['modules'][0]['frames'][0]['firstLine'] = False
                members['compiler-record.json'] = b.m.canonical(record)
            elif mutation == 'manifest':members['manifest.json'] = b'{}'
            elif mutation == 'unknown':members['../unknown'] = b'foreign'
            elif mutation == 'missing':del members['compiled/provider-jobs.pyc']
            elif mutation == 'duplicate-json':members['prepared.json'] = b'{"schemaVersion":1,"schemaVersion":1}'
            else:
                prepared = json.loads(members['prepared.json']);prepared['inputs'][0]['path'] = '../foreign'
                members['prepared.json'] = b.m.canonical(prepared)
            artifact = self.base / ('tamper-' + str(index) + '.zip');b.package(artifact, members, executable)
            altered = copy.deepcopy(receipt);data = artifact.read_bytes()
            altered['artifact'].update(sha256=v.sha(data), bytes=len(data))
            with self.subTest(mutation=mutation), self.assertRaises((ValueError, KeyError)):
                v.verify(artifact, finalized, altered, **{**expected, 'expected_artifact_sha256':v.sha(data)})

    def test_closed_zip_refuses_prefix_trailer_comments_compression_links_and_duplicates(self):
        result = self.build();raw = Path(result['artifact']).read_bytes()
        for bad in (b'prefix' + raw, raw + b'trailer', raw[:-1], raw[:14] + bytes([raw[14] ^ 1]) + raw[15:]):
            with self.assertRaises((ValueError, zipfile.BadZipFile)):v.archive_members(bad)
        for variant in ('comment', 'compressed', 'link', 'duplicate', 'directory'):
            path = self.base / (variant + '.zip')
            with zipfile.ZipFile(path, 'w') as archive:
                entry = zipfile.ZipInfo('test', (1980, 1, 1, 0, 0, 0));entry.create_system = 3
                entry.external_attr = (stat.S_IFLNK | 0o444 if variant == 'link' else stat.S_IFREG | 0o444) << 16
                if variant == 'compressed':entry.compress_type = zipfile.ZIP_DEFLATED
                if variant == 'directory':entry.filename = 'test/'
                archive.writestr(entry, b'public')
                if variant == 'duplicate':archive.writestr(entry, b'public')
                if variant == 'comment':archive.comment = b'unknown'
            with self.subTest(variant=variant), self.assertRaises(ValueError):v.archive_members(path.read_bytes())

    def test_json_bounds_original_input_shape_and_unpinned_compiler(self):
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":0.5}', b'[]', b'{ "a":1}',
                b'{"a":' * 17 + b'0' + b'}' * 17):
            with self.assertRaises(ValueError):v.record(raw, 1024)
        self.assertEqual(v.record(b.m.canonical({'quoted':'escaped \\" { [ ] }'}), 1024)['quoted'], 'escaped \\" { [ ] }')
        prepared = b.m.prepare(self.root, b.COMPONENT)
        for change in ('duplicate', 'boolean', 'path', 'large', 'count', 'fingerprint'):
            bad = copy.deepcopy(prepared)
            if change == 'duplicate':bad['inputs'].append(bad['inputs'][0])
            elif change == 'boolean':bad['inputs'][0]['bytes'] = True
            elif change == 'path':bad['inputs'][0]['path'] = 'a' * 257
            elif change == 'large':bad['inputs'][0]['bytes'] = c.MAX_SOURCE + 1
            elif change == 'count':bad['inputs'] = [bad['inputs'][0]] * 4097
            else:bad['sourceFingerprint'] = '0' * 64
            with self.subTest(change=change), self.assertRaises(ValueError):v.inputs(bad)
        with patch.object(v.platform, 'python_version', return_value='0.0.0'):
            with self.assertRaises(ValueError):v.compiled_record({}, {}, {})
        with self.assertRaises(ValueError):v.toolchain({'imagePin':'python:latest', 'imageId':'sha256:' + 'a' * 64})

    def test_file_ownership_symlink_and_size_guards(self):
        path = self.base / 'file';path.write_bytes(b'public')
        self.assertEqual(v.read(path, 6, owner=os.getuid()), b'public')
        alias = self.base / 'alias';alias.symlink_to(path)
        for target, size, owner in ((alias, 6, os.getuid()), (path, 1, os.getuid()), (path, 6, os.getuid() + 1)):
            with self.assertRaises(ValueError):v.read(target, size, owner=owner)
        with patch.dict(os.environ, {'DOCKER_HOST':'synthetic-secret-canary'}):
            with self.assertRaises(ValueError):self.build()
        with patch.object(b.os, 'getuid', return_value=0):
            with self.assertRaises(ValueError):b.build(self.root)

    def test_cli_failure_is_safe_and_builder_does_not_claim_delivery(self):
        with patch('sys.argv', ['builder', '--source', str(self.root)]):
            with patch.object(b, 'build', side_effect=ValueError('synthetic-secret-canary')):
                with patch('builtins.print') as printed:self.assertEqual(b.main(), 1)
                self.assertNotIn('synthetic-secret-canary', str(printed.call_args))
            with patch.object(b, 'build', return_value={'sharedStoreDelivery':'NOT_STARTED'}):
                with patch('builtins.print'):self.assertEqual(b.main(), 0)


if __name__ == '__main__':unittest.main()
