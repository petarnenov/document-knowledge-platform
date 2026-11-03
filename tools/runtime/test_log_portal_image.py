"""Original-seal/archive tests use real Git/process fixtures; real image proof is separate."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import unittest
from unittest.mock import patch
import test_log_portal_build as fixture

spec = importlib.util.spec_from_file_location('log_portal_image', Path(__file__).with_name('log-portal-image.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


class PortalImageTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixture.PortalBuildTest(); self.fixture.setUp(); self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        production = Path(__file__).resolve().parents[2]/'infra/docker-compose'
        target = self.root/'infra/docker-compose'; target.mkdir(parents=True)
        for name in ('dev-logging-portal-runtime.Dockerfile', 'dev-logging-nginx.conf'):
            (target/name).write_bytes((production/name).read_bytes())
        for args in (['add', '.'], ['-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false',
                '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'runtime recipe fixture']):
            subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True)
        self.build = self.fixture.build(); self.receipt = Path(self.build['receipt'])
        self.output = self.root.parent/'image-context'

    def reseal(self):
        directory = self.receipt.parent
        receipt = m.m.seal(self.root, json.loads((directory/'prepared.json').read_text()),
            json.loads((directory/'finalized.json').read_text()), Path(self.build['artifact']))
        self.receipt.write_bytes(m.m.canonical(receipt))

    def rewrite(self, entries):
        with tarfile.open(self.build['artifact'], 'w') as archive:
            for name, data, kind in entries:
                member = tarfile.TarInfo(name); member.type = kind; member.size = len(data) if kind == tarfile.REGTYPE else 0
                member.linkname = '../outside'
                archive.addfile(member, io.BytesIO(data) if kind == tarfile.REGTYPE else None)
        self.reseal()

    def test_exact_original_assets_public_inventory_and_fixed_recipe_without_compiler(self):
        record = m.prepare(self.root, self.receipt, self.output)
        self.assertEqual('PREPARED', record['result']); self.assertEqual('NOT_BUILT', record['runtimeImage'])
        self.assertEqual('NOT_STARTED', record['sharedStoreDelivery'])
        self.assertEqual(self.build['buildId'], record['buildId'])
        self.assertEqual(Path(self.build['manifest']).read_bytes(), (self.output/'public/dkp-logging-manifest.json').read_bytes())
        self.assertIn('index.html', record['files']); self.assertIn('logs.html', record['files'])
        self.assertNotIn('prepared.json', record['files']); self.assertNotIn('receipt.json', record['files'])
        recipe = (self.output/'Dockerfile').read_text(); self.assertIn('COPY public/', recipe); self.assertNotIn('RUN ', recipe)
        self.assertIn('!public/**', (self.output/'.dockerignore').read_text())
        self.assertEqual(0, self.output.stat().st_mode & 0o077)
        self.assertEqual(0, (self.output/'image-context.json').stat().st_mode & 0o077)
        with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, self.output)

    def test_no_output_for_source_manifest_receipt_or_artifact_drift(self):
        artifact = Path(self.build['artifact']); original = artifact.read_bytes(); artifact.write_bytes(original+b'drift')
        with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, self.output)
        artifact.write_bytes(original)
        manifest = Path(self.build['manifest']); raw = manifest.read_bytes(); manifest.write_bytes(b'{}')
        with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, self.output)
        manifest.write_bytes(raw)
        (self.root/'source.txt').write_text('changed')
        with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, self.output)
        self.assertFalse(self.output.exists())

    def test_prepared_context_verification_survives_later_source_revision(self):
        record = m.prepare(self.root, self.receipt, self.output)
        (self.root/'source.txt').write_text('later workspace revision')
        self.assertEqual(record, m.verify_prepared(self.output))

    def test_prepared_rejects_changed_asset_recipe_inventory_or_extra_file(self):
        record = m.prepare(self.root, self.receipt, self.output)
        for relative, replacement in [('public/index.html', b'changed'), ('Dockerfile', b'FROM unknown'),
                ('nginx.conf', b'changed'), ('.dockerignore', b'!image-context.json')]:
            with self.subTest(relative=relative):
                path = self.output/relative; original = path.read_bytes(); path.write_bytes(replacement)
                with self.assertRaises(ValueError): m.verify_prepared(self.output)
                path.write_bytes(original)
        extra = self.output/'public/private.json'; extra.write_text('private')
        with self.assertRaises(ValueError): m.verify_prepared(self.output)
        extra.unlink()
        extra = self.output/'public/unlisted'; extra.mkdir()
        with self.assertRaises(ValueError): m.verify_prepared(self.output)
        extra.rmdir()
        path = self.output/'image-context.json'
        for changed in [dict(record, schemaVersion=True), dict(record, buildId='00000000-0000-0000-0000-000000000000'),
                dict(record, manifestSha256='0'*64), dict(record, artifact=dict(record['artifact'], bytes=True)),
                dict(record, unexpected='field'), dict(record, files={}), dict(record, runtimeImage='BUILT')]:
            with self.subTest(changed=list(changed)):
                path.write_bytes(m.m.canonical(changed))
                with self.assertRaises(ValueError): m.verify_prepared(self.output)
        path.write_bytes(m.m.canonical(record))
        self.assertEqual(record, m.verify_prepared(self.output))

    def test_prepared_rejects_symlinks_permissions_and_manifest_substitution(self):
        record = m.prepare(self.root, self.receipt, self.output)
        for relative, mode in [('', 0o755), ('public', 0o777), ('public/index.html', 0o666), ('image-context.json', 0o644)]:
            path = self.output/relative; original_mode = path.stat().st_mode & 0o777
            path.chmod(mode)
            with self.assertRaises(ValueError): m.verify_prepared(self.output)
            path.chmod(original_mode)
        asset = self.output/'public/index.html'; original = asset.read_bytes()
        other = self.output.parent/'external.html'; other.write_bytes(original); other.chmod(0o644)
        asset.unlink(); asset.symlink_to(other)
        with self.assertRaises(ValueError): m.verify_prepared(self.output)
        asset.unlink(); asset.write_bytes(original); asset.chmod(0o644)
        # Even a rewritten inventory cannot make HTML with a substituted original manifest valid.
        raw = (self.output/'public/dkp-logging-manifest.json').read_bytes()
        asset.write_bytes(original.replace(raw, b'{}'))
        record['files']['index.html'] = {'sha256': m.hashlib.sha256(asset.read_bytes()).hexdigest(), 'bytes': asset.stat().st_size}
        (self.output/'image-context.json').write_bytes(m.m.canonical(record))
        with self.assertRaises(ValueError): m.verify_prepared(self.output)

    def test_valid_seal_does_not_allow_unsafe_special_duplicate_or_mismatched_archive(self):
        cases = [[('../escape', b'x', tarfile.REGTYPE)], [('/absolute', b'x', tarfile.REGTYPE)],
            [('index.html', b'x', tarfile.SYMTYPE)], [('index.html', b'x', tarfile.LNKTYPE)],
            [('fifo', b'', tarfile.FIFOTYPE)], [('same', b'x', tarfile.REGTYPE), ('same', b'y', tarfile.REGTYPE)],
            [('dkp-logging-manifest.json', b'wrong', tarfile.REGTYPE)]]
        for entries in cases:
            with self.subTest(entries=entries):
                self.rewrite(entries)
                with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, self.output)
                self.assertFalse(self.output.exists())

    def test_html_must_embed_exact_original_in_both_pages(self):
        raw = Path(self.build['manifest']).read_bytes()
        for name in ('index.html', 'logs.html'):
            with self.subTest(name=name):
                html = b'<script id="dkp-log-build-manifest">'+raw+b'</script>'
                self.rewrite([('dkp-logging-manifest.json', raw, tarfile.REGTYPE),
                    ('index.html', b'wrong' if name == 'index.html' else html, tarfile.REGTYPE),
                    ('logs.html', b'wrong' if name == 'logs.html' else html, tarfile.REGTYPE)])
                with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, self.output)

    def test_source_change_during_copy_cannot_leave_success_receipt(self):
        real = m.verified; calls = 0
        def changing(root, receipt):
            nonlocal calls
            calls += 1
            if calls == 2: (self.root/'source.txt').write_text('changed during copy')
            return real(root, receipt)
        with patch.object(m, 'verified', side_effect=changing):
            with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, self.output)
        self.assertFalse((self.output/'image-context.json').exists())

    def test_conflicting_file_ancestor_rejects_before_output_creation(self):
        raw = Path(self.build['manifest']).read_bytes()
        html = b'<script id="dkp-log-build-manifest">'+raw+b'</script>'
        required = [('dkp-logging-manifest.json', raw, tarfile.REGTYPE),
                    ('index.html', html, tarfile.REGTYPE), ('logs.html', html, tarfile.REGTYPE)]
        for number, entries in enumerate([[('assets', b'x', tarfile.REGTYPE), ('assets/main.js', b'y', tarfile.REGTYPE)],
                        [('assets/main.js', b'y', tarfile.REGTYPE), ('assets', b'x', tarfile.REGTYPE)]]):
            with self.subTest(entries=entries):
                self.rewrite(required+entries)
                target = self.root.parent/('conflict-'+str(number))
                with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, target)
                self.assertFalse(target.exists())

    def test_declared_size_and_entry_count_bounds(self):
        self.rewrite([(str(n), b'', tarfile.REGTYPE) for n in range(513)])
        with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, self.output)
        member = tarfile.TarInfo('oversized'); member.size = m.MAX_BYTES + 1
        Path(self.build['artifact']).write_bytes(member.tobuf()+b'\0'*1024); self.reseal()
        with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, self.output)
        self.assertFalse(self.output.exists())

    def test_private_canonical_receipt_and_output_paths(self):
        foreign = self.root.parent/'receipt.json'; foreign.write_bytes(self.receipt.read_bytes()); foreign.chmod(0o600)
        with self.assertRaises(ValueError): m.verified(self.root, foreign)
        self.receipt.chmod(0o644)
        with self.assertRaises(ValueError): m.verified(self.root, self.receipt)
        self.receipt.chmod(0o600)
        with self.assertRaises(ValueError): m.prepare(self.root, self.receipt, self.root/'output')
        alias = self.root.parent/'alias'; alias.symlink_to(self.receipt)
        with self.assertRaises(ValueError): m.verified(self.root, alias)
        raw = self.receipt.read_bytes(); self.receipt.write_bytes(b'{"component":"admin-portal","component":"other"}')
        with self.assertRaises(ValueError): m.verified(self.root, self.receipt)
        self.receipt.write_bytes(raw)

    def test_cli_fixed_failure_and_success_output(self):
        args = ['log-portal-image.py', '--source', str(self.root), '--receipt', str(self.receipt), '--output', str(self.output)]
        for fail in (False, True):
            stream = io.StringIO()
            with patch.object(sys, 'argv', args), contextlib.redirect_stdout(stream): status = m.main()
            result = json.loads(stream.getvalue()); self.assertEqual(int(fail), status)
            if fail: self.assertEqual({'result': 'FAIL', 'code': 'LOG_PORTAL_IMAGE_INVALID'}, result)
            else: self.assertEqual('PREPARED', result['result'])


if __name__ == '__main__': unittest.main()
