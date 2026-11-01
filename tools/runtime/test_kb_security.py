import base64
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

PATH = Path(__file__).with_name('kb-security.py')
SPEC = importlib.util.spec_from_file_location('kb_security', PATH)
kb = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(kb)


class KnowledgeSecurityTest(unittest.TestCase):
    def setUp(self):
        parent = kb.ROOT / 'local-data/security'
        parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.temporary = tempfile.TemporaryDirectory(prefix='kb-unit-', dir=parent)
        self.root = Path(self.temporary.name)
        self.destination = self.root / 'fresh'
        self.baseline = patch.object(kb, 'baseline', side_effect=self.identity)
        self.tools = patch.object(kb, 'tool', side_effect=self.tool)
        self.baseline.start()
        self.tools.start()

    def tearDown(self):
        self.tools.stop()
        self.baseline.stop()
        self.temporary.cleanup()

    def identity(self, destination):
        destination.mkdir(mode=0o700)
        (destination / 'authority').mkdir(mode=0o700)
        template = json.loads((kb.ROOT / 'infra/docker-compose/realm-template.json').read_text())
        (destination / 'dkp-realm.json').write_text(json.dumps(template))
        actor = template['users'][0]['id']
        grants = {'schemaVersion': 1, 'entries': [
            {'issuer': 'https://idp.localhost:18444/realms/dkp', 'subject': actor,
             'tenant': tenant, 'actions': ['document.create'], 'delegationCeiling': ['document.create']}
            for tenant in ('tenant-a', 'tenant-b')]}
        (destination / 'authority/grants.json').write_text(json.dumps(grants))
        values = {name: secrets.token_urlsafe(20) for name in kb.ENV_KEYS
                  if name not in {'DKP_KB_SECURITY_DIRECTORY', 'DKP_KB_ADMIN_PASSWORD',
                                  'DKP_KB_MIGRATOR_PASSWORD', 'DKP_KB_RUNTIME_PASSWORD'}}
        values.update(DKP_LOCAL_UID=str(os.getuid()), DKP_LOCAL_GID=str(os.getgid()))
        (destination / 'runtime.env').write_text(''.join(f'{name}={value}\n' for name, value in values.items()))
        for name in ('portal.crt', 'idp.crt', 'truststore.p12', 'java-security.options'):
            (destination / name).write_text('Synthetic unit fixture only')

    def tool(self, *arguments):
        for flag in ('-out', '-keyout'):
            if flag in arguments:
                Path(arguments[arguments.index(flag) + 1]).write_text('Synthetic unit fixture only')

    def reseal(self):
        state = json.loads((self.destination / 'state.json').read_text())
        state['files'] = kb.files(self.destination)
        (self.destination / 'state.json').write_text(json.dumps(state))

    def test_fresh_material_exact_actor_tenants_relations_and_scoped_mounts(self):
        self.assertTrue(kb.prepare(self.destination))
        self.assertFalse(kb.prepare(self.destination))
        env = kb.env_read(self.destination / 'runtime.env')
        self.assertEqual(kb.ENV_KEYS, set(env))
        self.assertEqual(str(self.destination), env['DKP_KB_SECURITY_DIRECTORY'])
        self.assertEqual(3, len({env[name] for name in ('DKP_KB_ADMIN_PASSWORD', 'DKP_KB_MIGRATOR_PASSWORD', 'DKP_KB_RUNTIME_PASSWORD')}))
        grants = json.loads((self.destination / 'kb-authority/grants.json').read_text())
        self.assertEqual(['tenant-a', 'tenant-b'], [entry['tenant'] for entry in grants['entries']])
        for entry in grants['entries']:
            self.assertEqual(kb.ISSUER, entry['issuer'])
            self.assertEqual(list(kb.KB_ACTIONS), entry['actions'])
            self.assertEqual(entry['actions'], entry['delegationCeiling'])
            self.assertEqual(['synthetic-firm'], entry['firms'])
            self.assertEqual(['synthetic-resource-scope'], entry['resourceScopes'])
        realm = json.loads((self.destination / 'identity/dkp-realm.json').read_text())
        client = next(c for c in realm['clients'] if c['clientId'] == 'dkp-bff')
        self.assertEqual([kb.PORTAL + '/oauth/callback/keycloak'], client['redirectUris'])
        self.assertEqual([kb.PORTAL], client['webOrigins'])
        for entry in json.loads((self.destination / 'identity/authority/grants.json').read_text())['entries']:
            self.assertEqual(kb.ISSUER, entry['issuer'])
            self.assertIn('document.share-to-collection', entry['actions'])
            self.assertIn('document.content.review', entry['actions'])
        self.assertEqual(32, len(base64.b64decode((self.destination / 'kb-authority/cursor.key').read_text())))
        self.assertFalse((self.destination / 'kb-authority/server.key').exists())
        before = kb.files(self.destination)
        kb.verify(self.destination)
        self.assertEqual(before, kb.files(self.destination))
        for path in self.destination.rglob('*'):
            self.assertEqual(0, path.stat().st_mode & 0o077)

    def test_partial_and_changed_material_preserved_without_regeneration(self):
        self.destination.mkdir(mode=0o700)
        (self.destination / 'precious').write_text('preserve')
        with self.assertRaises(ValueError):
            kb.prepare(self.destination)
        self.assertEqual('preserve', (self.destination / 'precious').read_text())
        shutil.rmtree(self.destination)
        kb.prepare(self.destination)
        key = self.destination / 'kb-authority/cursor.key'
        key.chmod(0o600)
        key.write_text('changed')
        with self.assertRaises(ValueError):
            kb.prepare(self.destination)
        self.assertEqual('changed', key.read_text())

    def test_unsafe_paths_symlinks_extra_files_and_public_permissions_rejected(self):
        for path in (kb.ROOT, kb.ROOT / 'local-data/security', self.root / '../../outside'):
            with self.assertRaises(ValueError):
                kb.safe_path(path)
        link = self.root / 'linked'
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            kb.prepare(link / 'child')
        self.root.chmod(0o770)
        with self.assertRaises(ValueError):
            kb.prepare(self.destination)
        self.root.chmod(0o700)
        kb.prepare(self.destination)
        extra = self.destination / 'kb-authority/extra'
        extra.write_text('unexpected')
        extra.chmod(0o400)
        with self.assertRaises(ValueError):
            kb.verify(self.destination)
        extra.unlink()
        key = self.destination / 'kb-authority/cursor.key'
        key.chmod(0o644)
        with self.assertRaises(ValueError):
            kb.verify(self.destination)
        key.chmod(0o400)
        key.unlink()
        key.symlink_to(self.destination / 'runtime.env')
        with self.assertRaises(ValueError):
            kb.verify(self.destination)

    def test_closed_record_and_env_and_password_distinctness(self):
        kb.prepare(self.destination)
        state_path = self.destination / 'state.json'
        original = state_path.read_text()
        for change in ({'unknown': True}, {'schemaVersion': 2}, {'profile': 'other'}, {'uid': -1}, {'gid': -1}):
            state = json.loads(original)
            state.update(change)
            state_path.write_text(json.dumps(state))
            with self.assertRaises(ValueError):
                kb.verify(self.destination)
        state_path.write_text(original)
        env = kb.env_read(self.destination / 'runtime.env')
        env['DKP_KB_RUNTIME_PASSWORD'] = env['DKP_KB_ADMIN_PASSWORD']
        (self.destination / 'runtime.env').write_text(''.join(f'{k}={v}\n' for k, v in env.items()))
        self.reseal()
        with self.assertRaises(ValueError):
            kb.verify(self.destination)
        env['DKP_KB_RUNTIME_PASSWORD'] = 'new-distinct-fixture'
        env['EXTRA'] = 'unexpected'
        (self.destination / 'runtime.env').write_text(''.join(f'{k}={v}\n' for k, v in env.items()))
        self.reseal()
        with self.assertRaises(ValueError):
            kb.verify(self.destination)

    def test_invalid_key_and_bounded_files(self):
        kb.prepare(self.destination)
        key = self.destination / 'kb-authority/cursor.key'
        key.chmod(0o600)
        key.write_text(base64.b64encode(bytes(31)).decode())
        self.reseal()
        with self.assertRaises(ValueError):
            kb.verify(self.destination)
        key.write_bytes(bytes(1048577))
        with self.assertRaises(ValueError):
            kb.files(self.destination)
        key.unlink()
        os.mkfifo(key)
        with self.assertRaises(ValueError):
            kb.files(self.destination)

    def test_env_parser_rejects_malformed_duplicates_empty_and_oversize(self):
        path = self.root / 'env'
        for value in ('invalid', 'KEY=one\nKEY=two', '=value', 'KEY=', 'KEY=bad\x00', 'x' * 32769):
            path.write_text(value)
            with self.assertRaises(ValueError):
                kb.env_read(path)

    def test_actor_client_tenant_and_subject_mismatches_abort(self):
        for field in ('username', 'tenants', 'id', 'client', 'grant-tenant', 'grant-subject'):
            identity = self.root / field
            self.identity(identity)
            realm_path = identity / 'dkp-realm.json'
            realm = json.loads(realm_path.read_text())
            if field == 'username':
                realm['users'][0]['username'] = 'other'
            elif field == 'tenants':
                realm['users'][0]['attributes']['dkp_tenants'] = ['tenant-a']
            elif field == 'id':
                realm['users'][0]['id'] = 'invalid'
            elif field == 'client':
                realm['clients'] = []
            else:
                grants_path = identity / 'authority/grants.json'
                grants = json.loads(grants_path.read_text())
                grants['entries'][0]['tenant' if field == 'grant-tenant' else 'subject'] = 'other'
                grants_path.write_text(json.dumps(grants))
            realm_path.write_text(json.dumps(realm))
            with self.assertRaises(ValueError):
                kb.authority(identity)

    def test_tool_failure_leaves_no_published_or_partial_authority(self):
        with patch.object(kb, 'tool', side_effect=ValueError('tool unavailable')):
            with self.assertRaises(ValueError):
                kb.prepare(self.destination)
        self.assertFalse(self.destination.exists())
        self.assertEqual([], list(self.root.glob('.kb-private-*')))

    def test_real_subprocess_wrapper_is_bounded_silent_and_checks_exit(self):
        self.tools.stop()
        try:
            with patch.object(kb.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as run:
                kb.tool('safe-tool', 'public-argument')
                self.assertEqual(60, run.call_args.kwargs['timeout'])
                self.assertEqual(subprocess.DEVNULL, run.call_args.kwargs['stderr'])
            with patch.object(kb.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1)):
                with self.assertRaises(ValueError):
                    kb.tool('safe-tool')
        finally:
            self.tools.start()

    def test_cli_reports_only_finite_status(self):
        with patch('sys.argv', ['kb-security.py', 'prepare', '--directory', str(self.destination)]), patch('builtins.print') as output:
            self.assertEqual(0, kb.main())
            output.assert_called_once_with('Fresh KB private material prepared.')
        with patch('sys.argv', ['kb-security.py', 'verify', '--directory', str(self.destination)]), patch('builtins.print') as output:
            self.assertEqual(0, kb.main())
            output.assert_called_once_with('Existing KB private material verified.')
        with patch('sys.argv', ['kb-security.py', 'verify', '--directory', str(self.root / 'missing')]), patch('builtins.print') as output:
            self.assertEqual(1, kb.main())
            output.assert_called_once_with('KB private material validation failed; existing material preserved.')


if __name__ == '__main__':
    unittest.main()
