"""Real Git/process/tar orchestration fixtures; these do not attest a real Vite compiler."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('log_portal_build', Path(__file__).with_name('log-portal-build.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


class PortalBuildTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()/'source'; self.root.mkdir()
        (self.root/'.gitignore').write_text('**/build/\n**/dist/\n')
        (self.root/'source.txt').write_text('original')
        self.node = Path(sys.executable).resolve()
        self.pnpm = self.root/'fixture-pnpm.py'
        self.settings = {'record': {'schemaVersion': 1, 'component': 'admin-portal',
            'toolchain': [{'name': name, 'version': version, 'vendorBuildProvenance': 'UNKNOWN'} for name, version in
                [('node', '24.22.1'), ('pnpm', '12.16.1'), ('typescript', '6.0.3'), ('vite', '8.3.5')]],
            'dependencies': [{'name': 'npm.fixture', 'version': '1.0', 'vendorBuildProvenance': 'UNKNOWN'}]}}
        self.save()
        self.pnpm.write_text('''import json,os,sys
from pathlib import Path
root=Path.cwd();cfg=json.loads((root/'fixture.json').read_text())
args=sys.argv[1:]
if args[-1]=='typecheck':
 if cfg.get('compileExit'):sys.exit(2)
 if cfg.get('mutate'):(root/'source.txt').write_text('changed')
elif 'node' in args:
 target=Path(args[-1])
 if cfg.get('recordSymlink'):target.symlink_to(root/'fixture.json')
 else:target.write_text(cfg.get('raw',json.dumps(cfg['record'])));target.chmod(0o600)
else:
 if cfg.get('packageExit'):sys.exit(3)
 raw=Path(os.environ['DKP_LOG_BUILD_MANIFEST']).read_bytes()
 dist=root/'apps/admin-portal/dist';(dist/'assets').mkdir(parents=True,exist_ok=True)
 (dist/'assets/main.js').write_text('fixture compilation only')
 (dist/'dkp-logging-manifest.json').write_bytes(b'wrong' if cfg.get('wrongManifest') else raw)
 for name in ('index.html','logs.html'):
  html='<script id="dkp-log-build-manifest" type="application/json">'+raw.decode().replace('<','\\\\u003c')+'</script>'
  (dist/name).write_text('missing' if cfg.get('wrongHtml') else html)
 if cfg.get('outputSymlink'):(dist/'assets/link').symlink_to(root/'source.txt')
 if cfg.get('mutatePackage'):(root/'source.txt').write_text('changed during packaging')
''')
        for args in (['init', '-q'], ['add', '.'], ['-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false',
                '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture']):
            subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True)

    def save(self): (self.root/'fixture.json').write_text(json.dumps(self.settings))
    def build(self): self.save(); return m.build(self.root, self.node, self.pnpm)

    def test_two_original_build_ids_same_source_and_exact_manifest_tar_seal(self):
        first = self.build(); second = self.build()
        a = json.loads(Path(first['manifest']).read_text()); b = json.loads(Path(second['manifest']).read_text())
        self.assertNotEqual(a['buildId'], b['buildId']); self.assertEqual(a['sourceFingerprint'], b['sourceFingerprint'])
        self.assertEqual(a['gitSha'], b['gitSha']); self.assertNotIn('runId', a)
        self.assertEqual('NOT_STARTED', first['sharedStoreDelivery'])
        with tarfile.open(first['artifact']) as archive:
            self.assertEqual(Path(first['manifest']).read_bytes(), archive.extractfile('dkp-logging-manifest.json').read())
            self.assertTrue(all(member.mode == 0o644 for member in archive.getmembers()))
        for path in Path(first['manifest']).parent.iterdir(): self.assertEqual(0, path.stat().st_mode & 0o077)
        self.assertEqual(first['manifestSha256'], json.loads(Path(first['receipt']).read_text())['manifestSha256'])

    def test_compiler_packager_failures_and_mutation_never_seal_success(self):
        for key in ('compileExit', 'packageExit', 'mutate', 'mutatePackage'):
            with self.subTest(key=key):
                (self.root/'source.txt').write_text('original'); self.settings[key] = True
                with self.assertRaises(ValueError): self.build()
                del self.settings[key]
        self.assertEqual([], list((self.root/'build').rglob('receipt.json')))

    def test_closed_record_duplicate_keys_pins_and_symlinks(self):
        original = json.loads(json.dumps(self.settings))
        cases = [{'raw': '{"schemaVersion":1,"schemaVersion":1}'}, {'recordSymlink': True},
            {'record': {**original['record'], 'component': 'other'}},
            {'record': {**original['record'], 'schemaVersion': True}},
            {'record': {**original['record'], 'extra': 1}},
            {'record': {**original['record'], 'toolchain': []}},
            {'record': {**original['record'], 'toolchain': [{**row, 'version': 'wrong'} for row in original['record']['toolchain']]}}]
        for case in cases:
            with self.subTest(case=case):
                self.settings = {**original, **case}
                with self.assertRaises(ValueError): self.build()

    def test_mismatched_outputs_and_symlink_not_sealed(self):
        for key in ('wrongManifest', 'wrongHtml', 'outputSymlink'):
            with self.subTest(key=key):
                self.settings[key] = True
                with self.assertRaises(ValueError): self.build()
                del self.settings[key]
        self.assertEqual([], list((self.root/'build').rglob('receipt.json')))

    def test_relative_missing_and_symlink_tools_reject(self):
        alias = self.root/'node-alias'; alias.symlink_to(self.node)
        for root, node, pnpm in ((Path('.'), self.node, self.pnpm), (self.root, alias, self.pnpm),
                (self.root, self.node, self.root/'missing')):
            with self.subTest(node=node):
                with self.assertRaises((ValueError, FileNotFoundError)): m.build(root, node, pnpm)

    def test_cli_success_and_fixed_failure(self):
        args = ['log-portal-build.py', '--source', str(self.root), '--node', str(self.node), '--pnpm-script', str(self.pnpm)]
        for fail in (False, True):
            self.settings['compileExit'] = fail; self.save(); stream = io.StringIO()
            with patch.object(sys, 'argv', args), contextlib.redirect_stdout(stream): code = m.main()
            result = json.loads(stream.getvalue()); self.assertEqual(int(fail), code)
            self.assertEqual('FAIL' if fail else 'BUILT', result['result'])
            if fail: self.assertEqual({'result': 'FAIL', 'code': 'LOG_PORTAL_BUILD_INVALID'}, result)


if __name__ == '__main__': unittest.main()
