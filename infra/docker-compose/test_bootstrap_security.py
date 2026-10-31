import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('bootstrap_security', Path(__file__).with_name('bootstrap-security.py'))
bootstrap = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bootstrap)


class SecurityBootstrapTest(unittest.TestCase):
    def test_real_material_is_private_trusted_and_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'security'
            self.assertTrue(bootstrap.generate(target, runtime_uid=1001, runtime_gid=1001))
            before = {p.name: p.read_bytes() for p in target.iterdir()}
            self.assertFalse(bootstrap.generate(target, runtime_uid=1001, runtime_gid=1001))
            self.assertEqual(before, {p.name: p.read_bytes() for p in target.iterdir()})
            self.assertEqual(0o700, target.stat().st_mode & 0o777)
            for path in target.iterdir():
                self.assertEqual(0o600, path.stat().st_mode & 0o777)
            for name, hostname in [('portal', 'localhost'), ('idp', 'idp.localhost')]:
                result = subprocess.run(['openssl', 'verify', '-CAfile', str(target / 'ca.crt'),
                                         '-verify_hostname', hostname, str(target / f'{name}.crt')],
                                        capture_output=True)
                self.assertEqual(0, result.returncode)
            realm = json.loads((target / 'dkp-realm.json').read_text())
            self.assertEqual(300, realm['accessTokenLifespan'])
            client = realm['clients'][0]
            self.assertEqual(['https://localhost:18443/oauth/callback/keycloak'], client['redirectUris'])
            self.assertFalse(client['directAccessGrantsEnabled'])
            self.assertEqual('S256', client['attributes']['pkce.code.challenge.method'])
            self.assertEqual('false', client['attributes']['use.refresh.tokens'])
            profile = json.loads(realm['components']['org.keycloak.userprofile.UserProfileProvider'][0]['config']['kc.user.profile.config'][0])
            tenant = next(a for a in profile['attributes'] if a['name'] == 'dkp_tenants')
            self.assertEqual(['admin'], tenant['permissions']['edit'])
            self.assertNotIn('unmanagedAttributePolicy', profile)  # absent means disabled in Keycloak's enum contract
            self.assertNotIn('@@', (target / 'dkp-realm.json').read_text())
            self.assertNotIn(client['secret'], bootstrap.TEMPLATE.read_text())

    def test_incomplete_existing_directory_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'security'
            target.mkdir()
            marker = target / 'keep.txt'
            marker.write_text('preserve')
            with self.assertRaisesRegex(ValueError, 'Incomplete'):
                bootstrap.generate(target)
            self.assertEqual('preserve', marker.read_text())

    def test_symlink_and_root_runtime_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'link'
            target.symlink_to(Path(directory), target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'symlink'):
                bootstrap.generate(target)
            with self.assertRaisesRegex(ValueError, 'non-root'):
                bootstrap.generate(Path(directory) / 'new', runtime_uid=0)

    def test_tool_failure_leaves_no_partial_material_and_restores_umask(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'security'
            prior = os.umask(0o027)
            try:
                with patch.object(bootstrap, 'run', side_effect=RuntimeError('tool failure')):
                    with self.assertRaisesRegex(RuntimeError, 'tool failure'):
                        bootstrap.generate(target, runtime_uid=1001, runtime_gid=1001, keytool='keytool')
                self.assertFalse(target.exists())
                self.assertEqual([], list(Path(directory).iterdir()))
                actual = os.umask(0o027)
                self.assertEqual(0o027, actual)
            finally:
                os.umask(prior)


if __name__ == '__main__':
    unittest.main()
