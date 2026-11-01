import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('kb_authority_acceptance', Path(__file__).with_name('kb-authority-acceptance.py'))
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
ACTOR = '00000000-0000-4000-8000-000000000001'


class AuthorityPrimitivesTest(unittest.TestCase):
    def test_complete_snapshot_is_explicit_ordered_hash_only(self):
        for owner, tables in [('kb', M.KB_TABLES), ('dm', M.DM_TABLES)]:
            sql = M.snapshot_sql(owner)
            self.assertEqual(len(tables), sql.count('FROM ' + owner + '.'))
            self.assertIn('SELECT encode(sha256(convert_to(string_agg', sql)
            self.assertIn('ORDER BY to_jsonb(t)::text', sql)
            for table in tables:
                self.assertIn('FROM ' + owner + '.' + table + ' t', sql)
        with self.assertRaises(ValueError):
            M.snapshot_sql('public')

    def test_barrier_identity_and_waiter_observation_closed(self):
        self.assertIn('FOR UPDATE', M.lock_sql('dm', ACTOR))
        self.assertIn('tenant_policy_fences', M.lock_sql('kb'))
        for owner, identity in [('foreign', None), ('kb', ACTOR), ('dm', None), ('dm', ACTOR.upper().replace('4', 'A'))]:
            with self.assertRaises((ValueError, TypeError)):
                M.lock_sql(owner, identity)
        for value in [0, -1, 2147483648, True, '1;DROP TABLE kb.audit_facts']:
            with self.assertRaises(ValueError):
                M.waiter_sql(value)
        sql = M.waiter_sql(123)
        self.assertIn('123=ANY(pg_blocking_pids(a.pid))', sql)
        self.assertNotIn('a.query', sql)
        self.assertIn('current_database()', sql)

    def fixture(self, root):
        path = root / 'grants.json'
        value = {'schemaVersion': 1, 'entries': [
            {'issuer': 'https://idp.localhost:20444/realms/dkp', 'subject': ACTOR,
             'tenant': tenant, 'actions': list(M.ACTIONS.values()), 'delegationCeiling': []}
            for tenant in ['tenant-a', 'tenant-b']]}
        path.write_text(json.dumps(value))
        path.chmod(0o400)
        return path, path.read_bytes()

    def test_atomic_catalog_change_targets_only_actor_tenant_action_and_restores_even_failure(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            path, original = self.fixture(root)
            root.chmod(0o500)
            try:
                with self.assertRaisesRegex(RuntimeError, 'deliberate'):
                    with M.revoked_catalog(path, 'dm', 'https://idp.localhost:20444/realms/dkp', ACTOR) as digest:
                        self.assertEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest())
                        value = json.loads(path.read_bytes())
                        self.assertNotIn(M.ACTIONS['dm'], value['entries'][0]['actions'])
                        self.assertIn(M.ACTIONS['kb'], value['entries'][0]['actions'])
                        self.assertIn(M.ACTIONS['dm'], value['entries'][1]['actions'])
                        self.assertEqual(0o400, path.stat().st_mode & 0o777)
                        self.assertEqual(0o500, root.stat().st_mode & 0o777)
                        raise RuntimeError('deliberate')
                self.assertEqual(original, path.read_bytes())
                self.assertEqual([], list(root.glob('.authority-barrier-*')))
            finally:
                root.chmod(0o700)

    def test_catalog_rejects_invalid_scope_and_never_overwrites_concurrent_authority(self):
        with tempfile.TemporaryDirectory() as name:
            path, original = self.fixture(Path(name))
            for owner, issuer, actor in [('other', 'https://idp.localhost:20444/realms/dkp', ACTOR), ('dm', 'https://foreign.example', ACTOR), ('dm', 'https://idp.localhost:20444/realms/dkp', 'bad')]:
                with self.assertRaises(ValueError):
                    with M.revoked_catalog(path, owner, issuer, actor):
                        self.fail('must not enter')
                self.assertEqual(original, path.read_bytes())
            with self.assertRaisesRegex(ValueError, 'restoration requires reconciliation'):
                with M.revoked_catalog(path, 'kb', 'https://idp.localhost:20444/realms/dkp', ACTOR):
                    path.chmod(0o600)
                    path.write_text('explicit conflicting owner change')
                    path.chmod(0o400)
            self.assertEqual(b'explicit conflicting owner change', path.read_bytes())

    def test_unsafe_catalog_parent_and_file_fail_without_replacement(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            path, original = self.fixture(root)
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                M.private_bytes(path)
            path.chmod(0o400)
            root.chmod(0o720)
            with self.assertRaises(ValueError):
                M.replace_owned(path, b'changed', 0o400)
            root.chmod(0o700)
            link = root / 'symlink'
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                M.private_bytes(link)
            self.assertEqual(original, path.read_bytes())

    def test_failed_replacement_restores_directory_mode_and_removes_temporary(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            path, original = self.fixture(root)
            root.chmod(0o500)
            try:
                with patch.object(M.os, 'replace', side_effect=OSError('unit fixture only')):
                    with self.assertRaises(OSError):
                        M.replace_owned(path, b'changed', 0o400)
                self.assertEqual(original, path.read_bytes())
                self.assertEqual(0o500, root.stat().st_mode & 0o777)
                self.assertEqual([], list(root.glob('.authority-barrier-*')))
            finally:
                root.chmod(0o700)


class PersistentProtocolTest(unittest.TestCase):
    def child(self, response='1'):
        # Actual finite local subprocess/pipe. Explicit synthetic psql protocol,
        # never represented as PostgreSQL or cross-service acceptance.
        code = "import sys\nfor s in sys.stdin:\n if s.startswith('\\\\echo '): print(s[6:].strip(),flush=True)\n elif s.strip()=='SELECT pg_backend_pid();': print(123,flush=True)\n else: print(" + repr(response) + ",flush=True)"
        def launch(arguments, **options):
            self.assertEqual('docker', arguments[0])
            self.assertIn('exec psql -X -qAt', arguments[-1])
            self.assertNotIn('secret', ' '.join(arguments))
            return subprocess.Popen([sys.executable, '-u', '-c', code], **options)
        return launch

    @staticmethod
    def absent(*arguments, **options):
        return subprocess.CompletedProcess(arguments, 0, stdout='0\n', stderr='')

    def test_live_pipe_success_and_settled_owned_cleanup(self):
        with M.PgSession('a' * 64, 'kb', self.child(), observer_run=self.absent) as session:
            self.assertEqual('1', session.scalar('SELECT 1;'))
            self.assertEqual('1', session.scalar('SELECT 1;'))
        self.assertFalse(session.reader.is_alive())
        self.assertIsNotNone(session.process.poll())
        session.close()
        with self.assertRaises(ValueError):
            session.scalar('SELECT 1;')

    def test_invalid_selector_timeout_and_abnormal_eof_are_finite(self):
        for container, owner in [('wrong', 'kb'), ('a' * 64, 'foreign')]:
            with self.assertRaises(ValueError):
                M.PgSession(container, owner, self.child())
        def exited(arguments, **options):
            return subprocess.Popen([sys.executable, '-u', '-c', 'pass'], **options)
        with self.assertRaises(ValueError):
            M.PgSession('a' * 64, 'dm', exited, observer_run=self.absent)
        with M.PgSession('a' * 64, 'dm', self.child(''), observer_run=self.absent) as session:
            with self.assertRaises(ValueError):
                session.scalar('SELECT 1;', timeout=0)
        with M.PgSession('a' * 64, 'dm', self.child('x' * 257), observer_run=self.absent) as session:
            with self.assertRaises(ValueError):
                session.scalar('SELECT 1;')

class HttpBoundaryTest(unittest.TestCase):
    def identity(self):
        from email.message import Message
        class Response:
            status = 200
            headers = Message()
            def __init__(self, payload):
                self.payload = payload
            def getheader(self, key):
                return self.headers.get(key)
            def info(self):
                return self.headers
            def read1(self, count):
                part, self.payload = self.payload[:count], self.payload[count:]
                return part
        class Connection:
            def __init__(self):
                self.sock = self
                self.closed = False
                self.response = Response(b'{"safe":true}')
            def settimeout(self, remaining):
                if remaining <= 0:
                    raise AssertionError('timeout reset')
            def request(self, method, path, body, headers):
                self.sent = method, path, body, headers
            def getresponse(self):
                return self.response
            def close(self):
                self.closed = True
        connection = Connection()
        with patch.object(M.ssl, 'create_default_context', return_value=object()):
            identity = M.BrowserIdentity(Path('explicit-fixture-ca'), lambda *_: connection)
        return identity, connection

    def test_closed_https_destinations_and_normal_form_parser(self):
        for url in ['http://localhost:20443/', 'https://foreign.example:20443/', 'https://localhost:20444/',
                    'https://user@localhost:20443/', 'https://localhost:20443/#x', 'https://localhost:20443/\n']:
            with self.assertRaises(ValueError):
                M.closed_url(url)
        form = M.LoginForm()
        form.feed('<form id="kc-form-login" method="post" action="/realms/dkp/login-actions/authenticate"><input type="hidden" name="token" value="a&amp;b"></form>')
        self.assertEqual({'token': 'a&b'}, form.fields)
        for html in ['<form id="kc-form-login" method="get">', '<form id="kc-form-login" method="post"><input type="hidden" name="x"><input type="hidden" name="x">']:
            with self.assertRaises(ValueError):
                M.LoginForm().feed(html)

    def test_bounded_response_and_always_close_without_private_exception_output(self):
        identity, connection = self.identity()
        status, _, body = identity.call('GET', M.ORIGIN + '/api/v1/session')
        self.assertEqual((200, b'{"safe":true}'), (status, body))
        self.assertTrue(connection.closed)
        for method, limit, seconds in [('DELETE', 1, 1), ('GET', 0, 1), ('GET', 1, 13)]:
            with self.assertRaises(ValueError):
                identity.call(method, M.ORIGIN, limit=limit, seconds=seconds)
        identity, connection = self.identity()
        connection.response.headers['Content-Encoding'] = 'gzip'
        with self.assertRaises(ValueError):
            identity.call('GET', M.ORIGIN)
        self.assertTrue(connection.closed)
        del connection.response.headers['Content-Encoding']
        identity, connection = self.identity()
        with self.assertRaises(ValueError):
            identity.call('GET', M.ORIGIN, limit=1)
        self.assertTrue(connection.closed)

    def test_api_mutation_retains_origin_csrf_exactkey_and_metadata_bound(self):
        identity, _ = self.identity()
        with self.assertRaises(ValueError):
            identity.json('POST', '/api/v1/kb/collections', {})
        identity.csrf = 'actual-unit-session-csrf-value'
        from email.message import Message
        headers = Message()
        headers['Content-Type'], headers['Cache-Control'] = 'application/json', 'no-store'
        with patch.object(identity, 'call', return_value=(201, headers, b'{"collectionId":"' + ACTOR.encode() + b'","revision":0}')) as call:
            value = identity.json('POST', '/api/v1/kb/collections', {'title': 'a'}, key=ACTOR, expected=201)
            self.assertEqual(ACTOR, value['collectionId'])
            sent = call.call_args.args
            self.assertEqual(M.ORIGIN, sent[3]['Origin'])
            self.assertEqual(identity.csrf, sent[3]['X-CSRF-TOKEN'])
            self.assertEqual(ACTOR, sent[3]['Idempotency-Key'])
        for path in ['/arbitrary', '/api/v1/kb/collections?tenant=other', '/api/v1/kb/collections#x']:
            with self.assertRaises(ValueError):
                identity.json('GET', path)
        with self.assertRaises(ValueError):
            identity.json('POST', '/api/v1/kb/collections', {'title': 'x' * 16384})
        for response in [(503, headers, b'{}'), (200, headers, b'[]')]:
            with patch.object(identity, 'call', return_value=response):
                with self.assertRaises(ValueError):
                    identity.json('GET', '/api/v1/session', expected=200)

    def test_real_flow_protocol_uses_bff_code_redirects_and_original_actor_selection(self):
        identity, _ = self.identity()
        form = b'<form id="kc-form-login" method="post" action="https://idp.localhost:20444/realms/dkp/login-actions/authenticate"><input type="hidden" name="execution" value="closed-unit-execution"></form>'
        responses = [(302, {'Location': M.ISSUER + '/protocol/openid-connect/auth'}, b''),
                     (200, {}, form), (302, {'Location': M.ORIGIN + '/oauth/callback/keycloak?code=synthetic-code'}, b''),
                     (302, {'Location': M.ORIGIN + '/'}, b''), (200, {}, b'normal portal')]
        session = {'issuer': M.ISSUER, 'subject': ACTOR, 'csrfToken': 'a' * 32, 'selectedTenant': 'tenant-a'}
        with patch.object(identity, 'call', side_effect=responses) as calls, patch.object(identity, 'json', side_effect=[session, {}, session]) as api:
            identity.login('synthetic-user', 'unit-credential', ACTOR)
            self.assertEqual('a' * 32, identity.csrf)
            self.assertEqual('POST', calls.call_args_list[2].args[0])
            self.assertIn(b'username=synthetic-user', calls.call_args_list[2].args[2])
            self.assertEqual({'tenantId': 'tenant-a'}, api.call_args_list[1].args[2])
        for response in [(302, {}, b''), (503, {}, b''), (200, {}, b'<form id="kc-form-login" method="post" action="https://foreign.example/">')]:
            with patch.object(identity, 'call', return_value=response):
                with self.assertRaises(ValueError):
                    identity.login('user', 'password', ACTOR)


class BarrierCoordinatorTest(unittest.TestCase):
    def test_waiter_pid_cardinality_and_phase_guard(self):
        from unittest.mock import Mock
        for value in ['0', '-1', '2147483648', 'pid']:
            with self.assertRaises(ValueError):
                M.finite_pid(value)
        self.assertEqual(123, M.finite_pid('123'))
        observer = Mock()
        observer.scalar.side_effect = ['{"count":0,"waitMs":0}', '{"count":1,"waitMs":3.5}']
        _, age = M.wait_blocked(observer, 123)
        self.assertEqual(3.5, age)
        for value in ['{"count":2,"waitMs":1}', '{"count":1,"waitMs":251}', '{"count":1}', '{"count":true,"waitMs":1}']:
            observer.scalar.side_effect = None
            observer.scalar.return_value = value
            with self.assertRaises(ValueError):
                M.wait_blocked(observer, 123)
        for payload in [{}, {'collectionId': ACTOR, 'revision': True}, {'collectionId': ACTOR, 'revision': -1}]:
            with self.assertRaises(ValueError):
                M.require_receipt(payload, ACTOR)
        M.require_receipt({'collectionId': ACTOR, 'revision': 0}, ACTOR)

    def fixture(self, root, case, *, failure=False):
        from unittest.mock import Mock, MagicMock
        path, _ = AuthorityPrimitivesTest().fixture(root)
        class Session:
            pid = 123
            def scalar(self, sql, timeout=1):
                if 'pg_backend_pid' in sql:
                    return '123'
                return '1'
            def close(self):
                pass
            def __enter__(self):
                return self
            def __exit__(self, *_):
                self.close()
        fixture = Mock()
        fixture.catalog_observer.return_value = MagicMock()
        fixture.setup.return_value = (ACTOR, ACTOR, 1)
        fixture.pg.side_effect = lambda *_: Session()
        fixture.catalog.return_value = path
        fixture.subject = ACTOR
        fixture.state = {'source': 'a' * 64, 'commit': 'b' * 40}
        fixture.snapshot.side_effect = ['before', 'dm-before', 'after' if case == 'dm-post-check-race' else 'before', 'dm-before']
        if case == 'dm-post-check-race':
            fixture.snapshot.side_effect = ['before', 'dm-before', 'after', 'dm-before', 'after', 'after']
            fixture.identity.json.side_effect = [(200, {'collectionId': ACTOR, 'revision': 2}),
                                                 (404, {'code': 'NOT_FOUND'}), {'collectionId': ACTOR, 'revision': 2}]
        else:
            fixture.identity.json.return_value = (503 if failure else 404, {'code': 'UNAVAILABLE' if failure else 'NOT_FOUND'})
        return fixture

    def test_all_three_cases_distinguish_precommit_denial_and_documented_postcheck_commit(self):
        for case in M.CASES:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as name:
                fixture = self.fixture(Path(name), case)
                with patch.object(M, 'wait_blocked', return_value=(M.time.monotonic(), 0)):
                    receipt = M.one_case(fixture, case)
                self.assertEqual('PASS', receipt['result'])
                self.assertEqual(case == 'dm-post-check-race', receipt['knownCommit'])

    def test_wrong_typed_failure_is_not_broadened_and_catalog_restores(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            fixture = self.fixture(root, M.CASES[0], failure=True)
            original = (root / 'grants.json').read_bytes()
            with patch.object(M, 'wait_blocked', return_value=(M.time.monotonic(), 0)):
                with self.assertRaises(ValueError):
                    M.one_case(fixture, M.CASES[0])
            self.assertEqual(original, (root / 'grants.json').read_bytes())
        with self.assertRaises(ValueError):
            M.one_case(None, 'unapproved')

class RuntimeBindingTest(unittest.TestCase):
    def runtime(self, root):
        from unittest.mock import Mock
        runtime = Mock()
        runtime.verify.return_value = (root, {'commit': 'a' * 40, 'source': 'b' * 64})
        # Inventory comes from versioned Compose services, not a copied fixture list.
        compose = Path(__file__).resolve().parents[2] / 'infra/docker-compose'
        services = []
        for filename in ('compose.yaml', 'compose-kb.yaml'):
            text = (compose / filename).read_text().split('\nservices:\n', 1)[1]
            section = re.split(r'\n[^ #\s][^\n]*:', text, maxsplit=1)[0]
            for service in re.findall(r'^  ([a-z][a-z0-9-]*):', section, re.MULTILINE):
                if service not in services:
                    services.append(service)
        runtime.inventory.return_value = {'container': {format(i + 1, '064x'): {'Config': {'Labels': {'com.docker.compose.service': name}}} for i, name in enumerate(services)}}
        runtime.security.return_value.env_read.return_value = {'DKP_TEST_USERNAME': 'synthetic-user', 'DKP_TEST_PASSWORD': 'unit-credential'}
        authority = root / 'security/identity/authority'
        authority.mkdir(parents=True)
        AuthorityPrimitivesTest().fixture(authority)
        return runtime

    def test_exact_runtime_services_originalactor_and_actual_guarded_fixture_inputs(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            runtime = self.runtime(root)
            with patch.object(M, 'runtime_module', return_value=runtime), patch.object(M, 'BrowserIdentity') as browser:
                fixture = M.RuntimeFixture(root)
                initial = runtime.write.call_args.args[1]
                self.assertEqual('STARTING', initial['phase'])
                self.assertNotIn('password', json.dumps(initial))
                runtime.write.reset_mock()
                browser.return_value.login.assert_called_once_with('synthetic-user', 'unit-credential', ACTOR)
                fixture.identity.json.side_effect = [{'documentId': ACTOR, 'revision': 0}, {'collectionId': ACTOR, 'revision': 0}, {'membershipId': ACTOR, 'revision': 1}]
                self.assertEqual((ACTOR, ACTOR, 1), fixture.setup())
                request = fixture.identity.json.call_args_list[2]
                self.assertEqual('SELECTED_TENANT', request.args[2]['sharingScope'])
                from unittest.mock import Mock
                session = Mock()
                session.scalar.return_value = 'a' * 64
                self.assertEqual('a' * 64, fixture.snapshot(session, 'kb'))
                session.scalar.return_value = 'private raw row must never pass'
                with self.assertRaises(ValueError):
                    fixture.snapshot(session, 'kb')
                self.assertEqual(root / 'security/kb-authority/grants.json', fixture.catalog('kb'))
                fixture.record(M.CASES[0], {'phase': 'IN_PROGRESS'})
                runtime.write.assert_called_once()
                with self.assertRaises(ValueError):
                    fixture.record('unknown', {})
                with patch.object(M, 'PgSession') as pg:
                    fixture.pg('kb')
                    self.assertEqual(fixture.services['kb-postgres'], pg.call_args.args[0])
                    fixture.pg('dm')
                    self.assertEqual(fixture.services['dm-postgres'], pg.call_args.args[0])
                    self.assertEqual('dm', pg.call_args.args[1])
                with patch.object(M, 'CatalogObserver') as observer:
                    for owner, service in [('dm', 'document-management'), ('kb', 'knowledge-base')]:
                        fixture.catalog_observer(owner, M.CASES[0])
                        self.assertEqual((fixture.services[service], owner), observer.call_args.args)
                        observer.call_args.kwargs['receipt']({'phase': 'SETTLED'})
                        record = runtime.write.call_args.args[1]
                        self.assertEqual(fixture.services[service], record['container'])
                        self.assertEqual(fixture.helper_hash, record['helperSha256'])
                    for owner, case, stage in [('proxy', M.CASES[0], 'revocation'), ('dm', '../foreign', 'revocation'), ('dm', M.CASES[0], 'foreign')]:
                        with self.assertRaises(ValueError):
                            fixture.catalog_observer(owner, case, stage)
            self.assertTrue(fixture.evidence.is_dir())
            self.assertEqual(0o700, fixture.evidence.stat().st_mode & 0o777)

    def test_foreign_missing_or_duplicate_service_and_actor_fail_before_login(self):
        for variant in ('duplicate', 'missing', 'obsolete-alias', 'actor'):
            with self.subTest(variant=variant), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                runtime = self.runtime(root)
                if variant == 'duplicate':
                    runtime.inventory.return_value['container']['f' * 64] = {'Config': {'Labels': {'com.docker.compose.service': 'dm-postgres'}}}
                elif variant == 'missing':
                    missing = next(key for key, value in runtime.inventory.return_value['container'].items() if value['Config']['Labels']['com.docker.compose.service'] == 'dm-postgres')
                    del runtime.inventory.return_value['container'][missing]
                elif variant == 'obsolete-alias':
                    # A legacy/invented alias must not substitute the actual owning service.
                    value = next(value for value in runtime.inventory.return_value['container'].values() if value['Config']['Labels']['com.docker.compose.service'] == 'dm-postgres')
                    value['Config']['Labels']['com.docker.compose.service'] = 'postgres'
                else:
                    path = root / 'security/identity/authority/grants.json'
                    value = json.loads(path.read_text())
                    value['entries'][1]['subject'] = '00000000-0000-4000-8000-000000000002'
                    path.chmod(0o600)
                    path.write_text(json.dumps(value))
                    path.chmod(0o400)
                with patch.object(M, 'runtime_module', return_value=runtime), patch.object(M, 'BrowserIdentity') as browser:
                    with self.assertRaises(ValueError):
                        M.RuntimeFixture(root)
                    browser.assert_not_called()

    def test_cli_closedselection_publicreceipt_sourcebinding_and_no_providers_in_unit(self):
        from unittest.mock import Mock
        fixture = Mock()
        fixture.state = {'commit': 'a' * 40, 'source': 'b' * 64}
        with patch.object(M, 'RuntimeFixture', return_value=fixture) as factory, patch.object(M, 'one_case', return_value={'result': 'PASS'}) as case, patch('builtins.print') as output:
            M.main(['--runtime-directory', '/explicit-unit-fixture', '--case', M.CASES[0]])
            self.assertEqual(1, case.call_count)
            report = json.loads(output.call_args.args[0])
            self.assertEqual('BP04-authority-A-B-C', report['profile'])
            self.assertIn('true UNKNOWN COMMIT network loss/restart', report['remaining'])
            self.assertNotIn('password', json.dumps(report))
            factory.assert_called_once()
        with patch.object(M, 'RuntimeFixture') as factory:
            with self.assertRaises(ValueError):
                M.main(['--runtime-directory', '/explicit-unit-fixture', '--case', M.CASES[0], '--case', M.CASES[0]])
            factory.assert_not_called()

    def test_owned_transport_failure_closes_socket_and_never_disables_verification(self):
        from unittest.mock import Mock
        identity, _ = HttpBoundaryTest().identity()
        identity.context = Mock()
        identity.context.wrap_socket.side_effect = M.ssl.SSLError('synthetic verification rejection')
        raw = Mock()
        with patch.object(M.socket, 'create_connection', return_value=raw) as connect:
            with self.assertRaises(M.ssl.SSLError):
                identity._connection(M.closed_url(M.ORIGIN), 1)
            self.assertEqual(('127.0.0.1', 20443), connect.call_args.args[0])
            self.assertEqual('localhost', identity.context.wrap_socket.call_args.kwargs['server_hostname'])
            raw.close.assert_called_once()


class BackendSettlementTest(unittest.TestCase):
    def test_process_exit_without_actual_backend_absence_cannot_claim_settlement(self):
        # Actual local child exit, explicit synthetic SQL observer still reports
        # the owning backend present. Not real PostgreSQL evidence.
        with patch.object(M.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, stdout='1\n', stderr='')):
            session = M.PgSession('a' * 64, 'kb', PersistentProtocolTest().child())
            with self.assertRaises(ValueError):
                session.close()

class BackendFailureMatrixTest(unittest.TestCase):
    def test_nonce_pid_database_role_and_fixed_fixture_limits_are_bound_before_settlement(self):
        observed = []
        records = []
        def observer(arguments, **options):
            observed.append((arguments, options))
            return subprocess.CompletedProcess(arguments, 0, stdout='1\n' if len(observed) == 1 else '0\n')
        session = M.PgSession('a' * 64, 'dm', PersistentProtocolTest().child(), observer_run=observer, receipt=records.append)
        self.assertEqual(123, session.pid)
        session.close()
        self.assertTrue(session.settled)
        self.assertEqual(['LAUNCHING', 'RUNNING', 'SETTLED'], [value['phase'] for value in records])
        for arguments, options in observed:
            command = arguments[-1]
            self.assertIn('statement_timeout=1000ms', command)
            self.assertIn('lock_timeout=250ms', command)
            self.assertIn('idle_in_transaction_session_timeout=15000ms', command)
            self.assertIn('idle_session_timeout=15000ms', command)
            self.assertIn('PGAPPNAME=' + session.nonce + '_observer', command)
            self.assertIn('--dbname=dkp_dm', command)
            sql = options['input']
            self.assertIn("application_name='" + session.nonce + "'", sql)
            self.assertIn("datname='dkp_dm'", sql)
            self.assertIn("usename='dkp_local_db_admin'", sql)
            self.assertIn('pid=123', sql)
            self.assertNotIn('terminate_backend', sql)
            self.assertLessEqual(options['timeout'], 1)
        self.assertIsNone(records[0]['backendPid'])
        self.assertEqual(123, records[1]['backendPid'])

    def test_observer_timeout_malformed_and_failed_query_preserve_unknown_not_process_exit(self):
        from unittest.mock import Mock
        outcomes = [subprocess.TimeoutExpired('synthetic-own-observer', 1),
                    subprocess.CompletedProcess([], 0, stdout='raw-private-row', stderr=''),
                    subprocess.CompletedProcess([], 1, stdout='', stderr='')]
        for outcome in outcomes:
            with self.subTest(outcome=type(outcome).__name__):
                records = []
                observer = Mock(side_effect=outcome) if isinstance(outcome, Exception) else Mock(return_value=outcome)
                session = M.PgSession('b' * 64, 'kb', PersistentProtocolTest().child(), observer_run=observer, receipt=records.append)
                with self.assertRaises(M.BackendUnsettled):
                    session.close()
                self.assertIsNotNone(session.process.poll())
                self.assertFalse(session.settled)
                self.assertEqual('UNKNOWN_BACKEND_SETTLEMENT', records[-1]['phase'])
                count = observer.call_count
                with self.assertRaises(M.BackendUnsettled):
                    session.close()
                self.assertEqual(count, observer.call_count, 'No hidden close retry may fabricate settlement')

    def test_unknown_context_exit_promotes_completed_command_checks_to_private_unknown(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = BarrierCoordinatorTest().fixture(Path(name), M.CASES[0])
            original = fixture.pg.side_effect
            visits = []
            def session(owner):
                value = original(owner)
                visits.append(value)
                if len(visits) == 1:
                    # This initial KB snapshot session exits after the business
                    # assertions and explicit blocker close have completed.
                    class UnknownAfterChecks(type(value)):
                        def __exit__(self, *_):
                            raise M.BackendUnsettled('synthetic backend still present')
                    value = UnknownAfterChecks()
                return value
            fixture.pg.side_effect = session
            with patch.object(M, 'wait_blocked', return_value=(M.time.monotonic(), 0)):
                with self.assertRaises(M.BackendUnsettled):
                    M.one_case(fixture, M.CASES[0])
            fixture.failed.assert_called_once_with(M.CASES[0], 'UNKNOWN_BACKEND_SETTLEMENT')

    def test_private_unknown_record_preserves_original_canonical_request_for_reconciliation(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as name:
            fixture = object.__new__(M.RuntimeFixture)
            fixture.evidence = Path(name)
            fixture.state = {'source': 'a' * 64, 'commit': 'b' * 40}
            fixture.helper_hash = 'c' * 64
            fixture.runtime = Mock()
            def write(path, value):
                path.write_text(json.dumps(value))
                path.chmod(0o600)
            fixture.runtime.write.side_effect = write
            original = {'version': 1, 'case': M.CASES[0], 'phase': 'PASS', 'idempotencyKey': ACTOR,
                        'body': {'expectedRevision': 1, 'status': 'ACTIVE', 'reason': 'synthetic'},
                        'method': 'PATCH', 'path': '/api/v1/kb/collections/' + ACTOR + '/status'}
            fixture.record(M.CASES[0], original)
            fixture.failed(M.CASES[0], 'UNKNOWN_BACKEND_SETTLEMENT')
            restored = json.loads((fixture.evidence / (M.CASES[0] + '.json')).read_text())
            self.assertEqual(original['body'], restored['body'])
            self.assertEqual(ACTOR, restored['idempotencyKey'])
            self.assertEqual('UNKNOWN_BACKEND_SETTLEMENT', restored['phase'])
            fixture.backend_receipt({'application': 'dkp367_' + 'a' * 32, 'phase': 'UNKNOWN_BACKEND_SETTLEMENT'})
            self.assertTrue((fixture.evidence / ('backend-dkp367_' + 'a' * 32 + '.json')).exists())
            fixture.failed(M.CASES[1], 'UNKNOWN_BACKEND_SETTLEMENT')
            self.assertEqual('UNKNOWN_BACKEND_SETTLEMENT', json.loads((fixture.evidence / (M.CASES[1] + '.json')).read_text())['phase'])


if __name__ == '__main__':
    unittest.main()

class CatalogObserverTest(unittest.TestCase):
    def child(self, response='ACK', exit_code=0, ready='READY'):
        def launch(arguments, **options):
            self.assertEqual(['docker', 'exec', '-i', 'a' * 64, 'timeout', '-s', 'TERM', '-k', '1', '15', 'sh', '-ec'], arguments[:-1])
            self.assertIn('sha256sum /run/dkp/authority/grants.json', arguments[-1])
            self.assertNotIn('chmod', arguments[-1])
            code = ('import sys\nprint(' + repr(ready) + ',flush=True)\n'
                    's=sys.stdin.readline()\n'
                    'if s: print(' + repr(response) + ',flush=True)\n'
                    'sys.exit(' + str(exit_code) + ')')
            return subprocess.Popen([sys.executable, '-u', '-c', code], **options)
        return launch

    def test_ack_requires_matching_protocol_and_remote_normal_exit(self):
        records = []
        with M.CatalogObserver('a' * 64, 'dm', self.child(), records.append) as observer:
            observer.acknowledge('b' * 64, M.time.monotonic() + .2)
            self.assertTrue(observer.settled)
        self.assertEqual(['RUNNING', 'ACKNOWLEDGED_SETTLED', 'SETTLED'], [r['phase'] for r in records])
        self.assertFalse(observer.reader.is_alive())
        observer.close()
        with self.assertRaises(ValueError):
            observer.acknowledge('b' * 64, M.time.monotonic() + .2)

    def test_nonzero_mismatch_eof_and_oversized_output_cannot_release_barrier(self):
        for response, code in [('ACK', 24), ('MISMATCH', 0), ('', 23), ('x' * 257, 0)]:
            with self.subTest(response=response[:12], code=code):
                observer = M.CatalogObserver('a' * 64, 'dm', self.child(response, code))
                try:
                    with self.assertRaises(ValueError):
                        observer.acknowledge('b' * 64, M.time.monotonic() + .2)
                finally:
                    if code:
                        with self.assertRaises(M.CatalogObserverUnsettled):
                            observer.close()
                    else:
                        observer.close()

    def test_invalid_selector_protocol_digest_and_budget_fail_closed(self):
        for container, owner in [('foreign', 'dm'), ('a' * 64, 'proxy')]:
            with self.assertRaises(ValueError):
                M.CatalogObserver(container, owner, self.child())
        with self.assertRaises(ValueError):
            M.CatalogObserver('a' * 64, 'dm', self.child(ready='PRIVATE VALUE'))
        with M.CatalogObserver('a' * 64, 'dm', self.child()) as observer:
            for digest, deadline in [('not-a-digest', M.time.monotonic() + .2),
                                     ('b' * 64, M.time.monotonic() - 1),
                                     ('b' * 64, M.time.monotonic() + 1)]:
                with self.assertRaises(ValueError):
                    observer.acknowledge(digest, deadline)
            with self.assertRaises(ValueError):
                observer.expect('ACK', M.time.monotonic() - 1)
            with self.assertRaises(ValueError):
                observer.expect('ACK', M.time.monotonic() + .001)

    def test_unknown_exec_settlement_is_sticky_without_client_kill_or_retry(self):
        from unittest.mock import Mock
        with M.CatalogObserver('a' * 64, 'dm', self.child()) as observer:
            # Preserve the actual child and restore its wait only for test cleanup;
            # production never repairs UNKNOWN by calling close again.
            original = observer.process.wait
            observer.process.wait = Mock(side_effect=subprocess.TimeoutExpired('synthetic', 17))
            try:
                with self.assertRaises(M.CatalogObserverUnsettled):
                    observer.close()
                self.assertFalse(observer.settled)
                with self.assertRaises(M.CatalogObserverUnsettled):
                    observer.close()
                self.assertEqual(1, observer.process.wait.call_count)
            finally:
                observer.process.wait = original
                original(timeout=2)
                observer.reader.join(timeout=1)
                observer.process.stdout.close()
                # The context exit must also retain UNKNOWN; use explicit catch.
                observer.settled = True

    def test_ack_timeout_does_not_reset_lock_budget(self):
        with M.CatalogObserver('a' * 64, 'dm', self.child()) as observer:
            with patch.object(observer, 'expect', side_effect=ValueError('bounded')):
                with self.assertRaises(ValueError):
                    observer.acknowledge('b' * 64, M.time.monotonic() + .001)
        with M.CatalogObserver('a' * 64, 'dm', self.child()) as observer:
            original = observer.process.wait
            with patch.object(observer.process, 'wait', side_effect=subprocess.TimeoutExpired('synthetic', .1)):
                with self.assertRaises(ValueError):
                    observer.acknowledge('b' * 64, M.time.monotonic() + .2)
            original(timeout=2)

    def test_original_barrier_order_and_remaining_budget(self):
        for case in M.CASES:
            with tempfile.TemporaryDirectory() as name:
                fixture = BarrierCoordinatorTest().fixture(Path(name), case)
                observer = fixture.catalog_observer.return_value.__enter__.return_value
                observed = M.time.monotonic()
                def ack(digest, deadline):
                    self.assertEqual(hashlib.sha256(fixture.catalog.return_value.read_bytes()).hexdigest(), digest)
                    if len(observer.acknowledge.call_args_list) == 1:
                        self.assertAlmostEqual(observed + .240, deadline)
                observer.acknowledge.side_effect = ack
                with patch.object(M, 'wait_blocked', return_value=(observed, 10)):
                    M.one_case(fixture, case)
                owner = 'kb' if case == 'kb-deny-before-commit' else 'dm'
                self.assertEqual([(owner, case), (owner, case, 'restoration')], [call.args for call in fixture.catalog_observer.call_args_list])
                self.assertEqual(2, observer.acknowledge.call_count)

    def test_unknown_observer_overrides_successful_case(self):
        with tempfile.TemporaryDirectory() as name:
            fixture = BarrierCoordinatorTest().fixture(Path(name), M.CASES[0])
            fixture.catalog_observer.return_value.__exit__.side_effect = M.CatalogObserverUnsettled('unit unknown')
            with patch.object(M, 'wait_blocked', return_value=(M.time.monotonic(), 0)):
                with self.assertRaises(M.CatalogObserverUnsettled):
                    M.one_case(fixture, M.CASES[0])
            fixture.failed.assert_called_once_with(M.CASES[0], 'UNKNOWN_CATALOG_OBSERVER_SETTLEMENT')

    def test_real_posix_digest_protocol_rejects_wrong_bytes_and_missing_file(self):
        # Actual local sh/sha256sum, explicit synthetic file; not Docker evidence.
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / 'grants.json'
            path.write_bytes(b'public synthetic catalog')
            def launch(arguments, **options):
                script = arguments[-1].replace('/run/dkp/authority/grants.json', str(path))
                return subprocess.Popen(['sh', '-ec', script], **options)
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            with M.CatalogObserver('a' * 64, 'dm', launch) as observer:
                observer.acknowledge(digest, M.time.monotonic() + .2)
            for missing in (False, True):
                observer = M.CatalogObserver('a' * 64, 'dm', launch)
                try:
                    if missing:
                        path.unlink()
                    with self.assertRaises(ValueError):
                        observer.acknowledge('b' * 64, M.time.monotonic() + .2)
                    observer.process.wait(timeout=1)
                    self.assertEqual(24, observer.process.returncode)
                finally:
                    with self.assertRaises(M.CatalogObserverUnsettled):
                        observer.close()

    def test_ack_failure_never_commits_and_preserves_original_reconciliation(self):
        for case in M.CASES:
            with tempfile.TemporaryDirectory() as name:
                fixture = BarrierCoordinatorTest().fixture(Path(name), case)
                original = fixture.catalog.return_value.read_bytes()
                sessions = []
                factory = fixture.pg.side_effect
                def pg(owner):
                    from unittest.mock import Mock
                    session = factory(owner)
                    session.scalar = Mock(wraps=session.scalar)
                    sessions.append(session)
                    return session
                fixture.pg.side_effect = pg
                fixture.catalog_observer.return_value.__enter__.return_value.acknowledge.side_effect = ValueError('unit no ACK')
                with patch.object(M, 'wait_blocked', return_value=(M.time.monotonic(), 0)):
                    with self.assertRaises(ValueError):
                        M.one_case(fixture, case)
                self.assertFalse(any('COMMIT' in call.args[0] for session in sessions for call in session.scalar.call_args_list))
                self.assertEqual(original, fixture.catalog.return_value.read_bytes())
                self.assertEqual('FAILED_RECONCILIATION_REQUIRED', fixture.record.call_args.args[1]['phase'])
                self.assertIn('idempotencyKey', fixture.record.call_args.args[1])

    def test_nonzero_docker_client_exit_is_unknown_and_cannot_be_repaired_by_close(self):
        for code in (1, 125):
            records = []
            observer = M.CatalogObserver('a' * 64, 'dm', self.child(exit_code=code), records.append)
            with self.assertRaises(M.CatalogObserverUnsettled):
                observer.close()
            self.assertFalse(observer.settled)
            self.assertEqual(code, observer.process.returncode)
            self.assertEqual('UNKNOWN_SETTLEMENT', records[-1]['phase'])
            for close in (observer.close, lambda: observer.__exit__(None, None, None)):
                with self.assertRaises(M.CatalogObserverUnsettled):
                    close()
            self.assertNotIn('SETTLED', [r['phase'] for r in records])
            self.assertFalse(observer.reader.is_alive())

    def test_bounded_guest_observation_tolerates_initial_missing_lookup_without_business_retry(self):
        # Deterministic actual local shell: the first three guest-lookup stand-ins
        # fail, subsequent reads see the exact file. No timers or business retry.
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / 'grants.json'
            count = Path(name) / 'count'
            path.write_bytes(b'public synthetic catalog')
            count.write_text('0')
            def launch(arguments, **options):
                prefix = ('sha256sum() { n=$(cat ' + str(count) + '); n=$((n+1)); '
                          'printf "%s" "$n" > ' + str(count) + '; '
                          '[ "$n" -gt 3 ] || return 1; command sha256sum "$@"; }; ')
                script = prefix + arguments[-1].replace('/run/dkp/authority/grants.json', str(path))
                return subprocess.Popen(['sh', '-ec', script], **options)
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            with M.CatalogObserver('a' * 64, 'dm', launch) as observer:
                observer.acknowledge(digest, M.time.monotonic() + .2)
            self.assertEqual('4', count.read_text())
            count.write_text('0')
            observer = M.CatalogObserver('a' * 64, 'dm', launch)
            try:
                with self.assertRaises(ValueError):
                    observer.acknowledge('b' * 64, M.time.monotonic() + .2)
            finally:
                with self.assertRaises(M.CatalogObserverUnsettled):
                    observer.close()
            self.assertEqual('32', count.read_text())
