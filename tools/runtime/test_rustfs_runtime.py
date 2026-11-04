"""Authority and native-IAM contract tests; no simulated provider acceptance claim."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('rustfs_runtime', Path(__file__).with_name('rustfs-runtime.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class AuthorityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name).resolve()
        with patch.object(module, 'authority_root', return_value=cls.root):
            cls.base = module.prepare(cls.root / 'base')

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.directory = self.root / self.id().split('.')[-1]
        shutil.copytree(self.base, self.directory)
        self.addCleanup(shutil.rmtree, self.directory)
        self.addCleanup(patch.stopall)
        patch.object(module, 'authority_root', return_value=self.root).start()

    def test_real_tls_preparation_reuses_exact_private_identity(self):
        before = {n: (self.directory / n).read_bytes() for n in module.FILES}
        self.assertEqual(self.directory, module.prepare(self.directory))
        self.assertEqual(before, {n: (self.directory / n).read_bytes() for n in module.FILES})
        self.assertEqual(0o700, self.directory.stat().st_mode & 0o777)
        self.assertTrue(all((self.directory / n).stat().st_mode & 0o777 == 0o600 for n in module.FILES))
        self.assertEqual({'ca.crt', 'access.key', 'secret.key', 'access.key.role'}, {p.name for p in (self.directory / 'app').iterdir()})
        self.assertEqual(['tenant-a', 'tenant-b'], json.loads((self.directory / 'bootstrap/authority.json').read_text())['tenants'])

    def test_partial_permissive_symlink_and_tampered_material_refused_without_rotation(self):
        path = self.directory / 'app/access.key'
        original = path.read_bytes()
        for mutate, restore in [
            (lambda: path.unlink(), lambda: path.write_bytes(original)),
            (lambda: path.chmod(0o644), lambda: path.chmod(0o600)),
            (lambda: path.write_bytes(b'wrong'), lambda: path.write_bytes(original)),
        ]:
            mutate()
            with self.assertRaises(ValueError):
                module.prepare(self.directory)
            restore();path.chmod(0o600)
        path.unlink();path.symlink_to(self.base / 'app/access.key')
        with self.assertRaises(ValueError):
            module.validate(self.directory)
        path.unlink();path.write_bytes(original);path.chmod(0o600)
        (self.directory / 'server').chmod(0o755)
        with self.assertRaises(ValueError):
            module.validate(self.directory)

    def test_confined_paths_and_symlink_ancestor_reject(self):
        for path in (self.root, self.root.parent / 'outside', self.root / 'base/../../outside'):
            with self.assertRaises(ValueError):
                module.confined(path)
        link = self.directory / 'link';link.symlink_to(self.base, target_is_directory=True)
        with self.assertRaises(ValueError):
            module.confined(link / 'app')
        (self.directory / 'app/root-secret').write_text('must never be app-mounted')
        with self.assertRaises(ValueError):
            module.validate(self.directory)

    def test_closed_manifest_and_private_identity_guards(self):
        manifest = self.directory / 'bootstrap/authority.json'
        initial = json.loads(manifest.read_text())
        for key, value in [('schemaVersion', 2), ('image', 'unreviewed'), ('tenants', ['other']), ('files', {}), ('extra', True)]:
            data = dict(initial);data[key] = value;manifest.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                module.validate(self.directory)
        manifest.write_text(json.dumps(initial))
        for name, value in [('spool/.spool-authority', 'wrong\n'), ('app/access.key', 'not-hex\n'), ('bootstrap/root-curl.conf', 'user="wrong"\n')]:
            original = (self.directory / name).read_bytes();(self.directory / name).write_text(value)
            data = dict(initial);data['files'] = dict(initial['files']);data['files'][name] = hashlib.sha256(value.encode()).hexdigest();manifest.write_text(json.dumps(data))
            with self.assertRaises(ValueError):
                module.validate(self.directory)
            (self.directory / name).write_bytes(original)
        manifest.write_text(json.dumps(initial))
        original_run = module.run
        with patch.object(module, 'run', side_effect=lambda *a: b'wrong-key' if a[1] == 'pkey' else original_run(*a)):
            with self.assertRaises(ValueError):
                module.validate(self.directory)

    def test_failed_openssl_preparation_leaves_no_partial_authority(self):
        target = self.directory / 'failed'
        with patch.object(module, 'run', side_effect=ValueError('command failed')):
            with self.assertRaises(ValueError):
                module.prepare(target)
        self.assertFalse(target.exists());self.assertEqual([], list(self.directory.glob('.rustfs-authority-*')))

    def test_policy_has_only_required_bucket_actions_and_two_hashed_prefixes(self):
        policy = module.policy();statements = policy['Statement']
        actions = {a for s in statements for a in s['Action']}
        self.assertEqual({'s3:GetBucketVersioning', 's3:ListBucketVersions', 's3:GetObject', 's3:GetObjectVersion', 's3:PutObject'}, actions)
        prefixes = [hashlib.sha256(t.encode()).hexdigest() + '/*' for t in ('tenant-a', 'tenant-b')]
        self.assertEqual(prefixes, statements[1]['Condition']['StringLike']['s3:prefix'])
        self.assertEqual({f'arn:aws:s3:::{b}/{p}' for b in module.BUCKETS for p in prefixes}, set(statements[2]['Resource']))
        self.assertTrue(all(s['Effect'] == 'Allow' for s in statements))

    def test_normal_and_cleanup_policies_are_distinct_and_least_privilege(self):
        normal = {a for s in module.policy()['Statement'] for a in s['Action']}
        cleanup = {a for s in module.policy(cleanup=True)['Statement'] for a in s['Action']}
        self.assertNotIn('s3:DeleteObjectVersion', normal)
        self.assertEqual({'s3:GetBucketVersioning', 's3:ListBucketVersions', 's3:GetObjectVersion', 's3:DeleteObjectVersion'}, cleanup)
        for denied in ('s3:PutObject', 's3:DeleteObject', 's3:CreateBucket', 's3:*', 'admin:*'):
            self.assertNotIn(denied, cleanup)
        self.assertNotEqual((self.directory / 'app/access.key').read_bytes(), (self.directory / 'cleanup/access.key').read_bytes())
        self.assertNotEqual((self.directory / 'app/secret.key').read_bytes(), (self.directory / 'cleanup/secret.key').read_bytes())
        self.assertTrue((self.directory / 'app/access.key.role').read_text().startswith('dkp-storage-normal-v1\n'))
        self.assertTrue((self.directory / 'cleanup/access.key.role').read_text().startswith('dkp-storage-cleanup-v1\n'))

    def test_swapped_aliased_and_missing_cleanup_credentials_refuse_even_with_updated_manifest(self):
        manifest = self.directory / 'bootstrap/authority.json'
        for name, value in [('cleanup/access.key', (self.directory / 'app/access.key').read_bytes()), ('cleanup/secret.key', (self.directory / 'app/secret.key').read_bytes()), ('cleanup/access.key.role', (self.directory / 'app/access.key.role').read_bytes())]:
            path = self.directory / name; original = path.read_bytes(); data = json.loads(manifest.read_text())
            path.write_bytes(value);data['files'][name] = hashlib.sha256(value).hexdigest();manifest.write_text(json.dumps(data))
            with self.assertRaises(ValueError):module.validate(self.directory)
            path.write_bytes(original);data['files'][name] = hashlib.sha256(original).hexdigest();manifest.write_text(json.dumps(data))
        (self.directory / 'cleanup/access.key').unlink()
        with self.assertRaises(ValueError):module.validate(self.directory)

    def test_https_origin_validation(self):
        self.assertEqual('https://localhost:19900', module.endpoint('https://localhost:19900/'))
        for value in ('http://localhost', 'https://user:secret@localhost', 'https://localhost/a', 'https://localhost?a', 'https://localhost#fragment', 'https:///'):
            with self.assertRaises(ValueError):
                module.endpoint(value)

    def test_private_curl_configuration_has_no_secret_argv_and_no_redirects(self):
        access = (self.directory / 'server/root-access').read_text().strip()
        secret = (self.directory / 'server/root-secret').read_text().strip()
        def command(*args):
            self.assertEqual(('curl', '--disable'), args[:2])
            self.assertNotIn('--location', args)
            self.assertFalse(any(access in a or secret in a for a in args))
            config = Path(args[args.index('--config') + 1])
            self.assertEqual(0o600, config.stat().st_mode & 0o777)
            self.assertIn(access + ':' + secret, config.read_text())
            self.assertIn('https://localhost:19900/private', config.read_text())
            response = Path(args[args.index('--output') + 1]);response.write_bytes(b'{}')
            return b'200'
        with patch.object(module, 'run', side_effect=command):
            self.assertEqual((200, b'{}'), module.request(self.directory, 'https://localhost:19900', 'PUT', '/private', '{"synthetic":true}'))
        self.assertEqual([], list((self.directory / 'bootstrap').glob('.apply-*')))
        with patch.object(module, 'run', return_value=b'403'):
            with self.assertRaises(ValueError):
                module.request(self.directory, 'https://localhost', 'GET', '/')

    def replies(self, *, existing=False, bad=None):
        calls, objects = [], {}
        def respond(directory, origin, method, path, body=None, allowed=(200, 204), **kwargs):
            authority = kwargs.get('authority', 'root')
            calls.append((method, path, body, authority))
            if bad == path:
                return 200, b'<Invalid/>'
            if authority == 'anonymous' or (authority == 'cleanup' and (method == 'PUT' or '/admin/' in path or method == 'DELETE' and '?versionId=' not in path or hashlib.sha256(b'outside-tenant').hexdigest() in path)) or (authority == 'app' and (
                    'outside' in path or hashlib.sha256(b'outside-tenant').hexdigest() in path
                    or '/admin/' in path or path == '/dkp-bootstrap-denied'
                    or method == 'DELETE')):
                return 403, b'<Error><Code>AccessDenied</Code></Error>'
            if method == 'DELETE' and '?versionId=' in path:
                objects.pop(path.split('?')[0], None)
                return 204, b''
            if method == 'GET' and '?versionId=' in path and path.split('?')[0] not in objects:
                return 404, b'<Error><Code>NoSuchVersion</Code></Error>'
            if path.endswith('?policy'):
                return 404, b'<Error><Code>NoSuchBucketPolicy</Code></Error>'
            if path.endswith('?versioning') and method == 'GET':
                return 200, b'<VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Status>Enabled</Status></VersioningConfiguration>'
            if path in ('/dkp-quarantine', '/dkp-originals') and existing:
                return 409, b'<Error><Code>BucketAlreadyOwnedByYou</Code></Error>'
            if '?versions&prefix=' in path:
                entries = ''.join('<Version><Key>' + key.split('/', 2)[2] + '</Key><VersionId>fixture-version</VersionId></Version>'
                                  for key in objects if key.startswith(path.split('?')[0] + '/'))
                return 200, ('<ListVersionsResult>' + entries + '</ListVersionsResult>').encode()
            if path.count('/') == 4 and '/admin/' not in path:
                if method == 'PUT':
                    objects[path] = body.encode()
                if method in ('GET', 'PUT'):
                    kwargs.get('response_headers', {})['x-amz-version-id'] = 'fixture-version'
                if method == 'GET':
                    return 200, objects[path.split('?')[0]]
            return 200, b''
        return calls, respond

    def test_native_iam_requires_unsigned_existing_object_denials_before_app_creation(self):
        for existing in (False, True):
            calls, respond = self.replies(existing=existing)
            with patch.object(module, 'request', side_effect=respond):
                module.apply(self.directory, 'https://localhost:19900')
            admin = [c for c in calls if c[3] == 'root' and '/admin/' in c[1]]
            self.assertEqual(6, len(admin))
            self.assertEqual(module.policy(), json.loads(admin[0][2]))
            first_admin = calls.index(admin[0])
            before = calls[:first_admin]
            self.assertEqual(10, len([c for c in before if c[3] == 'anonymous']))
            self.assertFalse(any('?acl' in c[1] for c in calls))
            for bucket in module.BUCKETS:
                self.assertTrue(any(c[1].startswith('/' + bucket + '/') and '?versionId=' in c[1]
                                    and c[0] == 'GET' and c[3] == 'anonymous' for c in before))
            deletions = [c for c in calls if c[0] == 'DELETE' and '?versionId=fixture-version' in c[1]]
            self.assertEqual(14, len(deletions))
            self.assertEqual(4, len([c for c in deletions if c[3] == 'app']))
            self.assertEqual(4, len([c for c in deletions if c[3] == 'cleanup']))
            self.assertEqual(6, len([c for c in deletions if c[3] == 'root']))

    def test_public_policy_disabled_versioning_or_ownership_refuses_iam(self):
        for path in ('/dkp-quarantine?policy', '/dkp-quarantine?versioning'):
            calls, respond = self.replies(bad=path)
            with patch.object(module, 'request', side_effect=respond), self.assertRaises(ValueError):
                module.apply(self.directory, 'https://localhost:19900')
            self.assertFalse(any('/admin/' in c[1] for c in calls))
        calls, normal = self.replies()
        def other_owner(*a, **kw):
            if a[3] == '/dkp-quarantine':
                return 409, b'<Error><Code>BucketAlreadyExists</Code></Error>'
            return normal(*a, **kw)
        with patch.object(module, 'request', side_effect=other_owner), self.assertRaises(ValueError):
            module.apply(self.directory, 'https://localhost:19900')

    def test_anonymous_denial_requires_exact_status_and_s3_code(self):
        for status, response in [(200, b'<Error><Code>AccessDenied</Code></Error>'),
                                 (403, b'<Error><Code>Other</Code></Error>'), (403, b'<Invalid/>'),
                                 (302, b''), (404, b'<Error><Code>NoSuchKey</Code></Error>')]:
            with self.subTest(status=status, response=response), \
                    self.assertRaises((ValueError, module.XML.ParseError)):
                module.require_denied(lambda *a, **kw: (status, response), self.directory,
                                      'https://localhost', 'GET', '/bucket/key', authority='anonymous')

    def test_failed_unsigned_existing_read_cleans_exact_fixture_and_prevents_iam(self):
        calls, normal = self.replies()
        def public(*a, **kw):
            if kw.get('authority') == 'anonymous' and a[3].count('/') == 4:
                return 200, b'public'
            return normal(*a, **kw)
        with patch.object(module, 'request', side_effect=public), self.assertRaises(ValueError):
            module.apply(self.directory, 'https://localhost')
        self.assertFalse(any('/admin/' in c[1] for c in calls))
        self.assertEqual(1, len([c for c in calls if c[0] == 'DELETE' and '?versionId=fixture-version' in c[1]]))

    def test_unsigned_transport_has_no_signing_credentials_cookies_or_auth_headers(self):
        def execute(*args):
            self.assertNotIn('--aws-sigv4', args)
            self.assertNotIn('--user', args)
            config = Path(args[args.index('--config') + 1]).read_text()
            self.assertNotIn('user =', config)
            self.assertNotIn('Authorization', config)
            self.assertNotIn('cookie', config.lower())
            self.assertNotIn('X-Amz-', config)
            Path(args[args.index('--output') + 1]).write_bytes(b'<Error><Code>AccessDenied</Code></Error>')
            return b'403'
        with patch.object(module, 'run', side_effect=execute):
            self.assertEqual(403, module.request(self.directory, 'https://localhost', 'GET', '/bucket/key',
                                                allowed=(403,), authority='anonymous')[0])
        with patch.object(module, 'run') as command, self.assertRaises(ValueError):
            module.request(self.directory, 'https://localhost', 'GET', '/', authority='other')
        command.assert_not_called()

    def test_fixture_rejects_missing_version_wrong_readback_and_missing_listing(self):
        for failure in ('version', 'content', 'listing'):
            calls, normal = self.replies()
            def failed(*a, **kw):
                result = normal(*a, **kw)
                if failure == 'version' and a[2] == 'PUT' and 'response_headers' in kw:
                    kw['response_headers'].clear()
                if failure == 'content' and a[2] == 'GET' and a[3].count('/') == 4 and kw.get('authority') != 'anonymous':
                    return 200, b'changed'
                if failure == 'listing' and '?versions&prefix=' in a[3] and kw.get('authority') == 'app':
                    return 200, b'<ListVersionsResult/>'
                return result
            with self.subTest(failure=failure), patch.object(module, 'request', side_effect=failed), self.assertRaises(ValueError):
                module.apply(self.directory, 'https://localhost')

    def test_command_failure_does_not_expose_private_diagnostics(self):
        with patch.object(module.subprocess, 'run', return_value=subprocess.CompletedProcess(['openssl'], 1, b'private', b'secret')):
            with self.assertRaisesRegex(ValueError, 'diagnostics suppressed'):
                module.run('openssl', 'version')

    def test_internal_network_requires_owned_attached_rustfs_and_exact_origin(self):
        network_id = 'a' * 64
        network = {'Id': network_id, 'Internal': True,
                   'Labels': {'com.docker.compose.project': 'dkp-bp03', 'com.docker.compose.network': 'upload-data'},
                   'Containers': {'b' * 64: {}}}
        def inspected(*args):
            if args[1:3] == ('network', 'inspect'):
                return json.dumps([network]).encode()
            return (json.dumps({'com.docker.compose.project': network['Labels']['com.docker.compose.project'], 'com.docker.compose.service': 'rustfs'})
                    + '\n' + json.dumps({'owned': {'NetworkID': network_id}})).encode()
        with patch.object(module, 'run', side_effect=inspected):
            self.assertEqual(network_id, module.owned_network('dkp-bp03_upload-data', 'https://rustfs:9000'))
            network['Labels']['com.docker.compose.project'] = 'dkp-bp03-' + 'c' * 12
            self.assertEqual(network_id, module.owned_network('dkp-bp03-' + 'c' * 12 + '_upload-data', 'https://rustfs:9000'))
            for foreign in ('foreign', 'dkp-bp03-unsafe', 'dkp-bp03-' + 'c' * 13):
                network['Labels']['com.docker.compose.project'] = foreign
                with self.assertRaises(ValueError):
                    module.owned_network('owned', 'https://rustfs:9000')
            network['Labels']['com.docker.compose.project'] = 'dkp-bp03'
            for field, value in [('Internal', False), ('Id', 'malformed'), ('Labels', {}), ('Containers', {})]:
                original = network[field];network[field] = value
                with self.assertRaises(ValueError):
                    module.owned_network('dkp-bp03_upload-data', 'https://rustfs:9000')
                network[field] = original
        for name, origin in [('owned', 'https://localhost:19900'), ('bad;network', 'https://rustfs:9000')]:
            with patch.object(module, 'run') as command, self.assertRaises(ValueError):
                module.owned_network(name, origin)
            command.assert_not_called()
        with patch.object(module, 'run', side_effect=[json.dumps([network]).encode(),
                (json.dumps({'com.docker.compose.project': 'another', 'com.docker.compose.service': 'rustfs'})
                 + '\n' + json.dumps({'owned': {'NetworkID': network_id}})).encode()]):
            with self.assertRaises(ValueError):
                module.owned_network('owned', 'https://rustfs:9000')

    def test_private_runner_has_only_request_mount_and_public_ca_no_secret_argv(self):
        secret = (self.directory / 'server/root-secret').read_text().strip()
        calls = []
        def execute(args, **kwargs):
            calls.append(args)
            if args[:3] == ['docker', 'rm', '--force']:
                return subprocess.CompletedProcess(args, 0)
            self.assertEqual(['docker', 'run', '--rm'], args[:3])
            self.assertEqual('a' * 64, args[args.index('--network') + 1])
            self.assertIn('--read-only', args);self.assertIn('no-new-privileges:true', args)
            self.assertEqual('ALL', args[args.index('--cap-drop') + 1])
            self.assertEqual('64m', args[args.index('--memory') + 1])
            self.assertEqual('0.5', args[args.index('--cpus') + 1])
            self.assertEqual('16', args[args.index('--pids-limit') + 1])
            self.assertEqual('curl', args[args.index('--entrypoint') + 1])
            self.assertIn(module.IMAGE, args)
            self.assertFalse(any(secret in a or 'https://' in a for a in args))
            self.assertNotIn('--env', args);self.assertNotIn('--publish', args)
            mounts = [args[i + 1] for i, a in enumerate(args) if a == '--mount']
            self.assertEqual(2, len(mounts))
            config = Path(args[args.index('--config') + 1]);root = config.parent
            self.assertIn(f'type=bind,src={root},dst={root}', mounts)
            ca = self.directory / 'app/ca.crt'
            self.assertIn(f'type=bind,src={ca},dst={ca},readonly', mounts)
            self.assertIn(secret, config.read_text())
            self.assertIn('https://rustfs:9000/private', config.read_text())
            Path(args[args.index('--cidfile') + 1]).write_text('c' * 64)
            Path(args[args.index('--output') + 1]).write_bytes(b'{}')
            return subprocess.CompletedProcess(args, 0, b'200', b'')
        with patch.object(module, 'owned_network', return_value='a' * 64) as network, \
                patch.object(module.subprocess, 'run', side_effect=execute):
            self.assertEqual((200, b'{}'), module.request(self.directory, 'https://rustfs:9000', 'PUT', '/private', '{}', docker_network='owned'))
        network.assert_called_once_with('owned', 'https://rustfs:9000')
        self.assertEqual(['docker', 'rm', '--force', 'c' * 64], calls[-1])
        self.assertEqual([], list((self.directory / 'bootstrap').glob('.apply-*')))

    def test_private_runner_failure_and_timeout_clean_only_created_container(self):
        for failure in ('status', 'timeout', 'os-error'):
            calls = []
            def execute(args, **kwargs):
                calls.append(args)
                if args[:3] == ['docker', 'rm', '--force']:
                    return subprocess.CompletedProcess(args, 0)
                Path(args[args.index('--cidfile') + 1]).write_text('d' * 64)
                if failure == 'timeout':
                    raise subprocess.TimeoutExpired(args, 35)
                if failure == 'os-error':
                    raise OSError('private diagnostics')
                return subprocess.CompletedProcess(args, 1, b'', b'private response')
            with patch.object(module, 'owned_network', return_value='a' * 64), \
                    patch.object(module.subprocess, 'run', side_effect=execute):
                with self.assertRaisesRegex(ValueError, 'diagnostics suppressed'):
                    module.request(self.directory, 'https://rustfs:9000', 'GET', '/', docker_network='owned')
            self.assertEqual(['docker', 'rm', '--force', 'd' * 64], calls[-1])
            self.assertEqual([], list((self.directory / 'bootstrap').glob('.apply-*')))
        with patch.object(module, 'owned_network', side_effect=ValueError('unowned')), \
                patch.object(module.subprocess, 'run') as execute:
            with self.assertRaises(ValueError):
                module.request(self.directory, 'https://rustfs:9000', 'GET', '/', docker_network='unowned')
            execute.assert_not_called()
        self.assertEqual([], list((self.directory / 'bootstrap').glob('.apply-*')))

    def test_rejected_request_retains_safe_status_role_and_operation_with_original_cleanup(self):
        canary = 'private-credential-and-body-canary'
        def rejected(*args):
            self.assertIn('--aws-sigv4', args)
            self.assertIn('--max-time', args)
            self.assertEqual('15', args[args.index('--max-time') + 1])
            self.assertNotIn('--insecure', args)
            Path(args[args.index('--output') + 1]).write_text(canary)
            return b'403'
        with patch.object(module, 'run', side_effect=rejected), self.assertRaises(module.BootstrapFailure) as caught:
            module.request(self.directory, 'https://localhost', 'PUT',
                '/rustfs/admin/v3/add-user?accessKey=' + canary, canary, authority='root')
        failure = caught.exception
        self.assertEqual('REQUEST_HTTP', failure.kind)
        self.assertEqual(403, failure.status)
        self.assertEqual('iam-REQUEST_HTTP-PUT-root-IAM_USER-http403', failure.category)
        self.assertNotIn(canary, str(failure));self.assertNotIn('https:', str(failure))
        self.assertEqual([], list((self.directory / 'bootstrap').glob('.apply-*')))

    def test_malformed_status_and_original_response_header_bounds_have_safe_categories(self):
        for raw, body, headers, expected in ((b'private-canary', b'', b'', 'REQUEST_STATUS_INVALID'),
                (b'099', b'', b'', 'REQUEST_STATUS_INVALID'), (b'600', b'', b'', 'REQUEST_STATUS_INVALID'),
                (b'200', b'a' * 65537, b'', 'RESPONSE_BOUND'),
                (b'200', b'', b'a' * 16385, 'HEADERS_BOUND'),
                (b'200', b'', b'x-amz-version-id: first\nx-amz-version-id: second\n', 'FIXTURE_VERSION')):
            def execute(*args):
                Path(args[args.index('--output') + 1]).write_bytes(body)
                Path(args[args.index('--dump-header') + 1]).write_bytes(headers)
                return raw
            with self.subTest(expected=expected, raw=raw), patch.object(module, 'run', side_effect=execute), self.assertRaises(module.BootstrapFailure) as caught:
                module.request(self.directory, 'https://localhost', 'GET', '/dkp-quarantine?versioning', response_headers={})
            self.assertEqual(expected, caught.exception.kind)
            self.assertNotIn('private-canary', str(caught.exception))
            self.assertEqual([], list((self.directory / 'bootstrap').glob('.apply-*')))

    def test_native_process_failure_keeps_exit_without_private_stderr_and_same_owned_cleanup(self):
        def execute(args, **kwargs):
            if args[:3] == ['docker', 'rm', '--force']:return subprocess.CompletedProcess(args, 0)
            Path(args[args.index('--cidfile') + 1]).write_text('e' * 64)
            return subprocess.CompletedProcess(args, 60, b'credential-canary', b'private-path-and-key-canary')
        with patch.object(module, 'owned_network', return_value='a' * 64), patch.object(module.subprocess, 'run', side_effect=execute) as process, self.assertRaises(module.BootstrapFailure) as caught:
            module.request(self.directory, 'https://rustfs:9000', 'GET', '/dkp-originals?versioning', authority='app', docker_network='owned')
        self.assertEqual('iam-TRANSPORT_PROCESS-GET-app-BUCKET_VERSIONING-exit60', caught.exception.category)
        self.assertNotIn('canary', str(caught.exception))
        self.assertEqual(['docker', 'rm', '--force', 'e' * 64], process.call_args_list[-1].args[0])
        self.assertEqual([], list((self.directory / 'bootstrap').glob('.apply-*')))


    def test_cli_requires_endpoint_and_suppresses_private_failures(self):
        for args in (['apply'], ['prepare', '--endpoint', 'https://localhost']):
            with patch('sys.argv', ['rustfs-runtime', *args]), self.assertRaises(SystemExit):
                module.main()
        for action in ('prepare', 'apply'):
            args = [action] + (['--endpoint', 'https://localhost'] if action == 'apply' else [])
            with patch('sys.argv', ['rustfs-runtime', '--directory', str(self.directory), *args]), patch.object(module, action) as operation, contextlib.redirect_stdout(io.StringIO()) as output:
                module.main()
            operation.assert_called_once();self.assertIn('no production readiness', output.getvalue())


class ClosedDiagnosticTests(unittest.TestCase):
    def test_closed_context_and_numeric_bounds_preserve_value_error_compatibility(self):
        for kind in module.CATEGORIES:
            for code in (-255, 0, 255):
                value = module.BootstrapFailure(kind, method='HEAD', authority='cleanup', operation='OBJECT_VERSION', status=599, exit_code=code)
                self.assertIsInstance(value, ValueError)
                self.assertTrue(value.category.isascii());self.assertLessEqual(len(value.category), 256)
                self.assertIn('http599', value.category);self.assertIn('exit' + str(code), value.category)
        self.assertEqual('iam-FIXTURE_ABSENCE', module.BootstrapFailure('FIXTURE_ABSENCE').category)
        for context in ({'kind':'private-canary'}, {'kind':[]}, {'method':'GET private-canary'},
                {'method':[]}, {'authority':'credential-canary'}, {'authority':{}}, {'operation':'/private/path'},
                {'operation':[]}, {'status':True}, {'status':99}, {'status':600}, {'status':'403'},
                {'exit_code':False}, {'exit_code':-256}, {'exit_code':256}, {'exit_code':1.0}):
            arguments = {'kind':'REQUEST_HTTP', **context}
            with self.subTest(context=context), self.assertRaises(ValueError) as caught:module.BootstrapFailure(**arguments)
            self.assertNotIn('canary', str(caught.exception));self.assertNotIn('/private/path', str(caught.exception))
        with self.assertRaises(TypeError):module.BootstrapFailure('REQUEST_HTTP', response_body='secret')

    def test_operation_labels_discard_dynamic_credentials_queries_and_keys(self):
        canary = 'private-credential-canary'
        cases = [(None,'UNKNOWN'),([], 'UNKNOWN'),('/unknown/' + canary, 'UNKNOWN'),
            ('/rustfs/admin/v3/add-canned-policy?name=' + canary, 'IAM_POLICY'),
            ('/rustfs/admin/v3/add-user?accessKey=' + canary, 'IAM_USER'),
            ('/rustfs/admin/v3/set-user-or-group-policy?userOrGroup=' + canary, 'IAM_BINDING'),
            ('/rustfs/admin/v3/info','ADMIN_INFO'),('/dkp-quarantine','BUCKET'),
            ('/dkp-originals?policy','BUCKET_POLICY'),('/dkp-quarantine?versioning','BUCKET_VERSIONING'),
            ('/dkp-originals?versions&prefix=' + canary,'VERSION_LIST'),
            ('/dkp-quarantine/tenant/' + canary,'OBJECT'),
            ('/dkp-originals/tenant/' + canary + '?versionId=' + canary,'OBJECT_VERSION'),
            ('/dkp-originals?private=' + canary,'UNKNOWN')]
        for path, expected in cases:
            with self.subTest(path=path):self.assertEqual(expected,module.operation_kind(path))
            self.assertNotIn(canary,module.operation_kind(path))

    def test_real_upload_stage_propagates_only_original_closed_failure_context(self):
        spec = importlib.util.spec_from_file_location('iam_safe_stage', Path(__file__).with_name('upload-runtime.py'))
        upload = importlib.util.module_from_spec(spec);spec.loader.exec_module(upload)
        original = module.BootstrapFailure('REQUEST_HTTP', method='PUT', authority='root', operation='IAM_POLICY', status=503)
        def fail():raise original
        with self.assertRaises(upload.StageFailure) as caught:upload.stage('native-iam',fail)
        self.assertEqual('Owned BP03 stage native-iam failed (iam-REQUEST_HTTP-PUT-root-IAM_POLICY-http503); private diagnostics suppressed', str(caught.exception))
        self.assertIsNone(caught.exception.__cause__)


if __name__ == '__main__':
    unittest.main()
