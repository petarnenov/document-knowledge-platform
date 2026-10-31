import importlib.util
import base64
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
            before = {str(p.relative_to(target)): p.read_bytes() for p in target.rglob('*') if p.is_file()}
            self.assertFalse(bootstrap.generate(target, runtime_uid=1001, runtime_gid=1001))
            self.assertEqual(before, {str(p.relative_to(target)): p.read_bytes() for p in target.rglob('*') if p.is_file()})
            self.assertEqual(0o700, target.stat().st_mode & 0o777)
            for path in target.iterdir():
                if path.is_file():
                    self.assertEqual(0o600, path.stat().st_mode & 0o777)
            authority = target / 'authority'
            self.assertEqual(0o550, authority.stat().st_mode & 0o777)
            for path in authority.iterdir():
                self.assertEqual(0o400, path.stat().st_mode & 0o777)
            self.assertEqual(32, len(base64.b64decode((authority / 'cursor.key').read_text(), validate=False)))
            for name, hostname in [('portal', 'localhost'), ('idp', 'idp.localhost')]:
                result = subprocess.run(['openssl', 'verify', '-CAfile', str(target / 'ca.crt'),
                                         '-verify_hostname', hostname, str(target / f'{name}.crt')],
                                        capture_output=True)
                self.assertEqual(0, result.returncode)
            realm = json.loads((target / 'dkp-realm.json').read_text())
            self.assertEqual(300, realm['accessTokenLifespan'])
            catalog = json.loads((authority / 'grants.json').read_text())
            self.assertEqual(2, len(catalog['entries']))
            # BP03 explicitly adds this privilege to fresh synthetic development catalogs.
            # Read/admin/creator privileges never imply content access or delegation.
            self.assertIn('document.content.review', bootstrap.ACTIONS)
            self.assertEqual(10, len(set(bootstrap.ACTIONS)))
            self.assertNotIn('*', bootstrap.ACTIONS)
            for entry in catalog['entries']:
                self.assertEqual(realm['users'][0]['id'], entry['subject'])
                self.assertEqual('https://idp.localhost:18444/realms/dkp', entry['issuer'])
                self.assertEqual(set(bootstrap.ACTIONS), set(entry['actions']))
                self.assertEqual(set(bootstrap.ACTIONS), set(entry['delegationCeiling']))
            env = dict(line.split('=', 1) for line in (target / 'runtime.env').read_text().splitlines())
            for key in ['DKP_DB_ADMIN_PASSWORD', 'DKP_DM_MIGRATOR_PASSWORD', 'DKP_DM_RUNTIME_PASSWORD']:
                self.assertGreater(len(env[key]), 32)
            self.assertEqual(3, len({env[key] for key in ['DKP_DB_ADMIN_PASSWORD', 'DKP_DM_MIGRATOR_PASSWORD', 'DKP_DM_RUNTIME_PASSWORD']}))
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

    def test_bp03_origins_are_bound_and_reuse_never_rewrites_mismatched_material(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'identity'
            self.assertTrue(bootstrap.generate(target, runtime_uid=1001, runtime_gid=1001,
                                               portal_port=19443, idp_port=19444))
            realm = json.loads((target / 'dkp-realm.json').read_text())
            client = realm['clients'][0]
            self.assertEqual(['https://localhost:19443/oauth/callback/keycloak'], client['redirectUris'])
            self.assertEqual(['https://localhost:19443'], client['webOrigins'])
            catalog = json.loads((target / 'authority/grants.json').read_text())
            for entry in catalog['entries']:
                self.assertEqual('https://idp.localhost:19444/realms/dkp', entry['issuer'])
                self.assertEqual(10, len(entry['actions']))
                self.assertEqual(set(bootstrap.ACTIONS), set(entry['actions']))
                self.assertEqual(set(bootstrap.ACTIONS), set(entry['delegationCeiling']))
            before = {str(p.relative_to(target)): p.read_bytes() for p in target.rglob('*') if p.is_file()}
            self.assertFalse(bootstrap.generate(target, portal_port=19443, idp_port=19444))
            self.assertEqual(before, {str(p.relative_to(target)): p.read_bytes() for p in target.rglob('*') if p.is_file()})
            for ports in ({}, {'portal_port': 19445, 'idp_port': 19444}, {'portal_port': 19443, 'idp_port': 19445}):
                with self.assertRaisesRegex(ValueError, 'different identity origins'):
                    bootstrap.generate(target, **ports)
                self.assertEqual(before, {str(p.relative_to(target)): p.read_bytes() for p in target.rglob('*') if p.is_file()})
            env = dict(line.split('=', 1) for line in (target / 'runtime.env').read_text().splitlines())
            self.assertEqual('19443', env['DKP_PORTAL_PORT']);self.assertEqual('19444', env['DKP_IDP_PORT'])
            # Individually detect tampered redirect, issuer and recorded ports without
            # regenerating credentials or silently repairing existing material.
            for relative in ('dkp-realm.json', 'authority/grants.json', 'runtime.env'):
                path = target / relative;saved = path.read_bytes();mode = path.stat().st_mode & 0o777
                changed = saved.replace(b':19443', b':19445') if relative == 'dkp-realm.json' else (
                    saved.replace(b':19444', b':19445') if relative.endswith('grants.json') else
                    saved.replace(b'DKP_IDP_PORT=19444', b'DKP_IDP_PORT=19445'))
                path.chmod(0o600);path.write_bytes(changed);path.chmod(mode)
                with self.assertRaisesRegex(ValueError, 'different identity origins'):
                    bootstrap.generate(target, portal_port=19443, idp_port=19444)
                self.assertEqual(changed, path.read_bytes())
                path.chmod(0o600);path.write_bytes(saved);path.chmod(mode)
            options = (target / 'java-security.options').read_text()
            self.assertIn('-Djavax.net.ssl.trustStore=/run/dkp/truststore.p12\n', options)
            (target / 'java-security.options').write_text(options + '-Dunsafe.option=true\n')
            with self.assertRaisesRegex(ValueError, 'trust options'):
                bootstrap.generate(target, portal_port=19443, idp_port=19444)

    def test_default_legacy_material_preserves_existing_grants_and_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'identity'
            bootstrap.generate(target, runtime_uid=1001, runtime_gid=1001)
            # Older BP02 runtime.env had no port keys and nine explicit grants.
            env = target / 'runtime.env'
            env.write_text(''.join(line + '\n' for line in env.read_text().splitlines()
                                   if not line.startswith(('DKP_PORTAL_PORT=', 'DKP_IDP_PORT='))))
            path = target / 'authority/grants.json';catalog = json.loads(path.read_text())
            for entry in catalog['entries']:
                for field in ('actions', 'delegationCeiling'):
                    entry[field].remove('document.content.review')
            path.chmod(0o600);path.write_text(json.dumps(catalog));path.chmod(0o400)
            before = {str(p.relative_to(target)): p.read_bytes() for p in target.rglob('*') if p.is_file()}
            self.assertFalse(bootstrap.generate(target))
            self.assertEqual(before, {str(p.relative_to(target)): p.read_bytes() for p in target.rglob('*') if p.is_file()})
            self.assertNotIn('document.content.review', json.loads(path.read_text())['entries'][0]['actions'])

    def test_identity_ports_reject_unsafe_noncanonical_and_colliding_inputs_without_files(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'identity'
            for portal, idp in ((443, 19444), (19443, 65536), (19443, 19443), (19081, 19444),
                                (19443, 18083), (True, 19444), ('19443', 19444), (19443, '19444'),
                                ('https://evil.test', 19444), ('19443;echo unsafe', 19444)):
                with self.subTest(portal=portal, idp=idp), self.assertRaisesRegex(ValueError, 'identity ports'):
                    bootstrap.generate(target, portal_port=portal, idp_port=idp)
                self.assertFalse(target.exists())
            self.assertEqual(('https://localhost:1024', 'https://idp.localhost:65535/realms/dkp'), bootstrap.origins(1024, 65535))

    def test_existing_authority_permissions_cannot_be_weakened(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'security'
            bootstrap.generate(target, runtime_uid=1001, runtime_gid=1001)
            authority = target / 'authority'
            authority.chmod(0o750)
            with self.assertRaisesRegex(ValueError, 'Authority directory'):
                bootstrap.generate(target)
            authority.chmod(0o550)
            (authority / 'cursor.key').chmod(0o600)
            with self.assertRaisesRegex(ValueError, 'Authority files'):
                bootstrap.generate(target)
            (authority / 'cursor.key').chmod(0o400)
            (target / 'runtime.env').chmod(0o644)
            with self.assertRaisesRegex(ValueError, 'Security files'):
                bootstrap.generate(target)
            (target / 'runtime.env').chmod(0o600)
            target.chmod(0o755)
            with self.assertRaisesRegex(ValueError, 'owner-only'):
                bootstrap.generate(target)

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
