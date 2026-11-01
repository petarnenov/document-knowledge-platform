#!/usr/bin/env python3
"""External BP04 authority barriers. No production hooks or cross-database transaction.

Only finite, owner-attested fixture sessions may execute the fixed SQL below. Raw
rows, cookie values, catalog bytes and process diagnostics never become receipts.
Runtime receipts distinguish these three combinations from the remaining full BP04 gates.
"""
import argparse
import contextlib
import http.client
import http.cookiejar
from html.parser import HTMLParser
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import stat
import socket
import ssl
import subprocess
import threading
import time
import uuid
from urllib.parse import urljoin, urlencode, urlsplit
from urllib.request import Request

KB_TABLES = ('knowledge_collections', 'collection_acl_rules', 'audience_policies',
             'audience_rules', 'collection_memberships', 'membership_history',
             'policy_revisions', 'document_membership_scopes', 'tenant_policy_fences',
             'policy_snapshots', 'command_outcomes', 'audit_facts', 'outbox_events')
DM_TABLES = ('documents', 'retention', 'acl_rules', 'legal_holds', 'versions',
             'command_outcomes', 'audit_facts')
ACTIONS = {'dm': 'document.share-to-collection', 'kb': 'knowledge.collection.archive'}
LOCK_MS = 250


def require_uuid(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ValueError('Noncanonical fixture identity')
    return value


def snapshot_sql(owner):
    """Hash every row in every explicitly owned table; only one digest leaves PG."""
    if owner not in ('kb', 'dm'):
        raise ValueError('Unknown logical database owner')
    tables = KB_TABLES if owner == 'kb' else DM_TABLES
    parts = ["SELECT '" + name + "' AS name, COALESCE(jsonb_agg(to_jsonb(t) "
             "ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text AS data FROM "
             + owner + '.' + name + ' t' for name in tables]
    return ("SELECT encode(sha256(convert_to(string_agg(name || ':' || data, E'\\n' ORDER BY name), 'UTF8')), 'hex') "
            "FROM (" + ' UNION ALL '.join(parts) + ') owned;')


def lock_sql(owner, document=None):
    if owner == 'dm':
        return ("SELECT 1 FROM dm.documents WHERE tenant_id='tenant-a' AND document_id='"
                + require_uuid(document) + "'::uuid FOR UPDATE;")
    if owner == 'kb' and document is None:
        return "SELECT 1 FROM kb.tenant_policy_fences WHERE tenant_id='tenant-a' FOR UPDATE;"
    raise ValueError('Unknown fixture barrier')


def waiter_sql(pid):
    if type(pid) is not int or not 1 <= pid <= 2147483647:
        raise ValueError('Invalid owning PostgreSQL PID')
    # Independent observer: exact blocker PID, actual runtime role, exact DB;
    # never return query text, document/tenant values or connection attributes.
    return ("SELECT count(*) FROM pg_stat_activity a WHERE a.datname=current_database() "
            "AND a.usename IN ('dkp_dm_runtime','dkp_kb_runtime') AND "
            + str(pid) + '=ANY(pg_blocking_pids(a.pid));')


class BackendUnsettled(ValueError):
    """Local process exit is not evidence of owning PostgreSQL settlement."""


class CatalogObserverUnsettled(ValueError):
    """The Docker exec completion was not observed; never claim remote settlement."""


class CatalogObserver:
    """One read-only guest read, prepared before any request/SQL barrier.

    Normal docker exec completion acknowledges the remote exit status. On an
    observation timeout we neither kill the client nor infer remote settlement.
    The guest itself has a fixed lifetime, including its stdin wait.
    """
    PATHS = {'dm': '/run/dkp/authority/grants.json',
             'kb': '/run/dkp/kb-authority/grants.json'}

    def __init__(self, container, owner, popen=subprocess.Popen, receipt=None):
        if not re.fullmatch('[a-f0-9]{64}', container) or owner not in self.PATHS:
            raise ValueError('Invalid owned catalog observer selector')
        self.receipt = receipt or (lambda value: None)
        self.closed = False
        self.settled = False
        # No dynamic shell selector, background process, file write or privilege
        # change. Only the expected digest crosses stdin; no catalog bytes leave.
        script = ('printf "READY\\n"; IFS= read -r expected || exit 21; '
                  'case "$expected" in *[!a-f0-9]*|"") exit 22;; esac; '
                  '[ ${#expected} -eq 64 ] || exit 22; '
                  'actual=$(sha256sum ' + self.PATHS[owner] + ' 2>/dev/null) || exit 23; '
                  '[ "${actual%% *}" = "$expected" ] || exit 24; printf "ACK\\n"')
        self.process = popen(['docker', 'exec', '-i', container, 'timeout', '-s', 'TERM',
                              '-k', '1', '15', 'sh', '-ec', script],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.lines = queue.Queue(maxsize=4)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        self.report('RUNNING')
        try:
            self.expect('READY', time.monotonic() + 10)
        except Exception:
            self.close()
            raise

    def report(self, phase):
        self.receipt({'version': 1, 'phase': phase, 'guestLifetimeSeconds': 15,
                      'guestKillGraceSeconds': 1})

    def _read(self):
        try:
            while True:
                line = self.process.stdout.readline(257)
                if not line:
                    break
                self.lines.put_nowait(line.rstrip('\r\n') if len(line) <= 256 else None)
                if len(line) > 256:
                    break
        except (OSError, queue.Full):
            pass
        finally:
            with contextlib.suppress(queue.Full):
                self.lines.put_nowait(None)

    def expect(self, expected, deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError('Original catalog observer budget exhausted')
        try:
            value = self.lines.get(timeout=remaining)
        except queue.Empty:
            raise ValueError('Bounded catalog acknowledgement not observed') from None
        if value != expected:
            raise ValueError('Invalid catalog observer acknowledgement')

    def acknowledge(self, digest, deadline):
        if self.closed or not re.fullmatch('[a-f0-9]{64}', digest):
            raise ValueError('Invalid catalog acknowledgement request')
        if not 0 < deadline - time.monotonic() <= LOCK_MS / 1000:
            raise ValueError('Catalog acknowledgement exceeds original lock budget')
        self.process.stdin.write(digest + '\n')
        self.process.stdin.flush()
        self.expect('ACK', deadline)
        # The exit status comes from Docker's remote exec, not a locally killed
        # client. Both exact bytes AND normal remote completion precede release.
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError('Original catalog observer budget exhausted')
        try:
            code = self.process.wait(timeout=remaining)
        except subprocess.TimeoutExpired:
            raise ValueError('Catalog exec completion exceeded original lock budget') from None
        if code != 0:
            raise ValueError('Catalog observer failed')
        self.settled = True
        self.report('ACKNOWLEDGED_SETTLED')

    def close(self):
        if self.closed:
            if not self.settled:
                raise CatalogObserverUnsettled('Catalog observer settlement UNKNOWN')
            return
        self.closed = True
        with contextlib.suppress(OSError):
            self.process.stdin.close()
        try:
            # Outside the SQL barrier: allow the fixed guest lifetime to expire.
            # Do not terminate/retry/adopt any process on missing observation.
            code = self.process.wait(timeout=17)
        except (subprocess.TimeoutExpired, OSError):
            self.settled = False
            self.report('UNKNOWN_SETTLEMENT')
            raise CatalogObserverUnsettled('Catalog observer settlement UNKNOWN') from None
        self.reader.join(timeout=1)
        self.process.stdout.close()
        if code != 0:
            # CLI transport/daemon errors overlap remote command exit codes.
            # Even a known local exit cannot establish guest termination.
            self.settled = False
            self.report('UNKNOWN_SETTLEMENT')
            raise CatalogObserverUnsettled('Catalog observer settlement UNKNOWN')
        self.settled = True
        self.report('SETTLED')

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


class PgSession:
    """Persistent bounded psql process, scoped to one already-attested container."""
    def __init__(self, container, owner, popen=subprocess.Popen, observer_run=None, receipt=None):
        if not re.fullmatch('[a-f0-9]{64}', container) or owner not in ('dm', 'kb'):
            raise ValueError('Invalid owned PostgreSQL selector')
        self.container = container
        self.database = 'dkp_dm' if owner == 'dm' else 'knowledge_base'
        self.user = 'dkp_local_db_admin' if owner == 'dm' else 'dkp_kb_local_admin'
        self.nonce = 'dkp367_' + uuid.uuid4().hex
        self.pid = None
        self.observer_run = observer_run or subprocess.run
        self.receipt = receipt or (lambda value: None)
        self.closed = False
        self.settled = False
        self.report('LAUNCHING')
        self.process = popen(self.command(), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.lines = queue.Queue(maxsize=32)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        try:
            self.pid = finite_pid(self.scalar('SELECT pg_backend_pid();'))
            self.report('RUNNING')
        except Exception:
            self.close()
            raise

    def command(self, observer=False):
        application = self.nonce + ('_observer' if observer else '')
        # Fixed fixture-only bounds. No runtime role, source timer or production
        # pool property is changed. Credentials remain inside owning container env.
        return ['docker', 'exec', '-i', self.container, 'sh', '-ec',
                'export PGPASSWORD="$POSTGRES_PASSWORD" '
                "PGOPTIONS='-c statement_timeout=1000ms -c lock_timeout=250ms "
                "-c idle_in_transaction_session_timeout=15000ms -c idle_session_timeout=15000ms' PGAPPNAME=" + application
                + '; exec psql -X -qAt --set=ON_ERROR_STOP=1 --host=127.0.0.1 '
                '--username="$POSTGRES_USER" --dbname=' + self.database]

    def report(self, phase):
        self.receipt({'version': 1, 'phase': phase, 'container': self.container,
                      'database': self.database, 'role': self.user,
                      'application': self.nonce, 'backendPid': self.pid,
                      'statementMs': 1000, 'lockMs': 250, 'idleTransactionMs': 15000, 'idleSessionMs': 15000})

    def _read(self):
        try:
            for line in self.process.stdout:
                if len(line) > 256:
                    self.lines.put_nowait(None)
                    return
                self.lines.put_nowait(line.rstrip('\r\n'))
        except (OSError, queue.Full):
            pass
        finally:
            with contextlib.suppress(queue.Full):
                self.lines.put_nowait(None)

    def scalar(self, sql, timeout=1):
        if self.closed or not 0 < timeout <= 10:
            raise ValueError('Closed or unbounded fixture SQL')
        marker = 'END_' + uuid.uuid4().hex
        try:
            self.process.stdin.write(sql + '\n\\echo ' + marker + '\n')
            self.process.stdin.flush()
            deadline = time.monotonic() + timeout
            values = []
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise queue.Empty
                line = self.lines.get(timeout=remaining)
                if line is None:
                    raise ValueError('Owned PostgreSQL session ended')
                if line == marker:
                    if len(values) != 1:
                        raise ValueError('Unexpected fixture SQL cardinality')
                    return values[0]
                values.append(line)
                if len(values) > 1:
                    raise ValueError('Unexpected fixture SQL cardinality')
        except (OSError, queue.Empty):
            raise ValueError('Bounded fixture SQL failed') from None

    def observe_settlement(self):
        # Never terminate a backend. The exact recorded nonce/database/admin-role
        # and, once captured, PID identify ONLY our own session, even after PID reuse.
        predicate = ("application_name='" + self.nonce + "' AND datname='" + self.database
                     + "' AND usename='" + self.user + "'")
        if self.pid is not None:
            predicate += ' AND pid=' + str(self.pid)
        sql = 'SELECT count(*) FROM pg_stat_activity WHERE ' + predicate + ';\n'
        deadline = time.monotonic() + 2
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise BackendUnsettled('Owning PostgreSQL backend settlement UNKNOWN')
            try:
                value = self.observer_run(self.command(observer=True), input=sql, text=True,
                                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                          timeout=min(1, remaining))
            except (OSError, subprocess.TimeoutExpired):
                raise BackendUnsettled('Owning PostgreSQL backend settlement UNKNOWN') from None
            if value.returncode or value.stdout not in ('0\n', '1\n'):
                raise BackendUnsettled('Owning PostgreSQL backend settlement UNKNOWN')
            if value.stdout == '0\n':
                return
            time.sleep(.005)

    def close(self):
        if self.closed:
            if not self.settled:
                raise BackendUnsettled('Owning PostgreSQL backend settlement remains UNKNOWN')
            return
        self.closed = True  # LOCAL transport state only, never remote settlement.
        try:
            with contextlib.suppress(OSError):
                self.process.stdin.close()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
            self.reader.join(timeout=2)
            self.process.stdout.close()
            if self.reader.is_alive():
                raise BackendUnsettled('Fixture SQL reader did not settle')
            self.observe_settlement()
            self.settled = True
            self.report('SETTLED')
        except Exception:
            self.report('UNKNOWN_BACKEND_SETTLEMENT')
            raise

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def private_bytes(path):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
            or info.st_mode & 0o077 or not 1 <= info.st_size <= 1048576):
        raise ValueError('Unsafe owned authority fixture')
    return path.read_bytes(), stat.S_IMODE(info.st_mode)


def replace_owned(path, data, mode):
    """Replace this exact trusted-directory entry; never chmod or adopt a foreign file."""
    parent = path.parent.stat()
    if path.parent.is_symlink() or parent.st_uid != os.getuid() or parent.st_mode & 0o022:
        raise ValueError('Unsafe authority fixture parent')
    original, _ = private_bytes(path)
    parent_mode = stat.S_IMODE(parent.st_mode)
    temporary = path.parent / ('.authority-barrier-' + uuid.uuid4().hex)
    try:
        path.parent.chmod(parent_mode | 0o200)
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        # Do not overwrite a concurrently replaced/rebound authority entry.
        if private_bytes(path)[0] != original:
            raise ValueError('Concurrent authority fixture replacement')
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        try:
            temporary.unlink(missing_ok=True)
        finally:
            path.parent.chmod(parent_mode)


@contextlib.contextmanager
def revoked_catalog(path, owner, issuer, subject):
    if owner not in ACTIONS or issuer != 'https://idp.localhost:20444/realms/dkp':
        raise ValueError('Invalid authority fixture scope')
    require_uuid(subject)
    original, mode = private_bytes(path)
    data = json.loads(original)
    matching = [entry for entry in data['entries'] if entry['issuer'] == issuer
                and entry['subject'] == subject and entry['tenant'] == 'tenant-a']
    if len(matching) != 1 or ACTIONS[owner] not in matching[0]['actions']:
        raise ValueError('Missing explicit fixture grant')
    matching[0]['actions'].remove(ACTIONS[owner])
    replacement = (json.dumps(data, sort_keys=True) + '\n').encode()
    replace_owned(path, replacement, mode)
    try:
        yield hashlib.sha256(replacement).hexdigest()
    finally:
        # Changed bytes are a fixture conflict, never permission to overwrite them.
        if private_bytes(path)[0] != replacement:
            raise ValueError('Authority changed during fixture; restoration requires reconciliation')
        replace_owned(path, original, mode)
        if private_bytes(path)[0] != original:
            raise ValueError('Authority restoration failed')

# Authentication uses the production BFF's normal authorization-code/PKCE flow.
# No direct grant, signing key, access token or forged browser/session is used.
ORIGIN = 'https://localhost:20443'
ISSUER = 'https://idp.localhost:20444/realms/dkp'


def closed_url(value):
    url = urlsplit(value)
    if (url.scheme != 'https' or url.username is not None or url.password is not None
            or url.fragment or (url.hostname, url.port) not in
            {('localhost', 20443), ('idp.localhost', 20444)}
            or any(ord(character) < 33 or ord(character) > 126 for character in value)):
        raise ValueError('Untrusted authentication/acceptance destination')
    return url


class LoginForm(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.action = None
        self.fields = {}
        self.active = False

    def handle_starttag(self, tag, attributes):
        values = dict(attributes)
        if tag == 'form' and values.get('id') == 'kc-form-login':
            if self.action is not None or values.get('method', '').lower() != 'post':
                raise ValueError('Ambiguous real identity form')
            self.action = values.get('action')
            self.active = True
        if self.active and tag == 'input' and values.get('type') == 'hidden':
            name, value = values.get('name'), values.get('value', '')
            if not name or name in self.fields or len(name) > 128 or len(value) > 4096 or len(self.fields) >= 32:
                raise ValueError('Unbounded identity form')
            self.fields[name] = value

    def handle_endtag(self, tag):
        if tag == 'form':
            self.active = False


class BrowserIdentity:
    """Strict-CA HTTPS with explicit loopback resolution, preserving TLS hostname."""
    def __init__(self, ca, connector=None):
        self.context = ssl.create_default_context(cafile=str(ca))
        self.cookies = http.cookiejar.CookieJar()
        self.connector = connector or self._connection
        self.csrf = None
        self.actor = None

    def _connection(self, url, remaining):
        connection = http.client.HTTPSConnection(url.hostname, url.port, timeout=remaining, context=self.context)
        raw = socket.create_connection(('127.0.0.1', url.port), timeout=remaining)
        try:
            connection.sock = self.context.wrap_socket(raw, server_hostname=url.hostname)
        except BaseException:
            raw.close()
            raise
        return connection

    def call(self, method, destination, body=None, headers=None, *, limit=2097152, seconds=12):
        url = closed_url(destination)
        if method not in ('GET', 'POST', 'PATCH', 'PUT') or not 1 <= limit <= 2097152 or not 0 < seconds <= 12:
            raise ValueError('Invalid bounded acceptance request')
        data = body
        outgoing = {'Accept': 'application/json', 'Connection': 'close', **(headers or {})}
        request = Request(destination, data=data, method=method, headers=outgoing)
        self.cookies.add_cookie_header(request)
        deadline = time.monotonic() + seconds
        connection = self.connector(url, seconds)
        try:
            connection.request(method, url.path + ('?' + url.query if url.query else ''), body=data,
                               headers=dict(request.header_items()))
            transport = connection.sock
            response = connection.getresponse()
            self.cookies.extract_cookies(response, request)
            if response.getheader('Content-Encoding'):
                raise ValueError('Encoded acceptance response rejected')
            payload = bytearray()
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ValueError('Acceptance whole response deadline expired')
                transport.settimeout(remaining)
                chunk = response.read1(min(16384, limit + 1 - len(payload)))
                if not chunk:
                    break
                payload.extend(chunk)
                if len(payload) > limit:
                    raise ValueError('Acceptance response bound exceeded')
            return response.status, response.headers, bytes(payload)
        finally:
            connection.close()

    def login(self, username, password, expected_subject):
        require_uuid(expected_subject)
        url = ORIGIN + '/oauth/login/keycloak'
        method, data, headers = 'GET', None, {'Accept': 'text/html'}
        submitted = False
        for _ in range(16):
            status, received, body = self.call(method, url, data, headers, limit=262144)
            if status in (302, 303):
                target = received.get('Location')
                if not target:
                    raise ValueError('Missing real identity redirect')
                url = urljoin(url, target)
                closed_url(url)
                method, data, headers = 'GET', None, {'Accept': 'text/html'}
                continue
            if status != 200:
                raise ValueError('Real identity flow rejected')
            if submitted:
                break
            form = LoginForm()
            form.feed(body.decode('utf-8', errors='strict'))
            action = urljoin(url, form.action or '')
            parsed = closed_url(action)
            if (not form.action or parsed.hostname != 'idp.localhost'
                    or parsed.path != '/realms/dkp/login-actions/authenticate'):
                raise ValueError('Missing closed real identity login form')
            if 'username' in form.fields or 'password' in form.fields:
                raise ValueError('Unexpected credential field binding')
            form.fields.update(username=username, password=password)
            data = urlencode(form.fields).encode('ascii')
            url, method = action, 'POST'
            headers = {'Accept': 'text/html', 'Content-Type': 'application/x-www-form-urlencoded'}
            submitted = True
        else:
            raise ValueError('Real identity redirect bound exceeded')
        session = self.json('GET', '/api/v1/session', expected=200)
        if (session.get('issuer') != ISSUER or session.get('subject') != expected_subject
                or not isinstance(session.get('csrfToken'), str) or not 16 <= len(session['csrfToken']) <= 512):
            raise ValueError('Actual authenticated actor mismatch')
        self.csrf, self.actor = session['csrfToken'], expected_subject
        self.json('POST', '/api/v1/session/tenant', {'tenantId': 'tenant-a'}, expected=200)
        selected = self.json('GET', '/api/v1/session', expected=200)
        if selected.get('selectedTenant') != 'tenant-a' or selected.get('subject') != self.actor:
            raise ValueError('Actual trusted tenant selection failed')
        self.csrf = selected['csrfToken']

    def json(self, method, path, body=None, *, key=None, expected=None):
        uuid_pattern = r'[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}'
        permitted = ((method == 'GET' and path == '/api/v1/session')
                     or (method == 'POST' and path in ('/api/v1/session/tenant', '/api/v1/dm/documents', '/api/v1/kb/collections'))
                     or (method == 'POST' and re.fullmatch('/api/v1/kb/collections/' + uuid_pattern + '/memberships', path))
                     or (method == 'PATCH' and re.fullmatch('/api/v1/kb/collections/' + uuid_pattern + '/status', path)))
        if not permitted:
            raise ValueError('Untrusted acceptance API route')
        headers = {'Accept': 'application/json'}
        data = None
        if method != 'GET':
            if self.csrf is None:
                raise ValueError('Missing actual authenticated mutation capability')
            headers.update({'Origin': ORIGIN, 'X-CSRF-TOKEN': self.csrf, 'Content-Type': 'application/json'})
            if key is not None:
                require_uuid(key)
                headers['Idempotency-Key'] = key
            data = json.dumps(body, separators=(',', ':')).encode()
            if len(data) > 16384:
                raise ValueError('Acceptance metadata exceeds existing wire bound')
        status, received, payload = self.call(method, ORIGIN + path, data, headers)
        if expected is not None and status != expected:
            raise ValueError('Actual acceptance API status mismatch')
        if (received.get('Content-Type', '').split(';')[0].strip().lower() != 'application/json'
                or received.get_all('Cache-Control') != ['no-store']):
            raise ValueError('Actual acceptance API headers mismatch')
        decoded = json.loads(payload)
        if not isinstance(decoded, dict):
            raise ValueError('Actual acceptance API shape mismatch')
        return decoded if expected is not None else (status, decoded)

ROOT = Path(__file__).resolve().parents[2]
CASES = ('dm-deny-during-assessment', 'kb-deny-before-commit', 'dm-post-check-race')


def runtime_module():
    spec = importlib.util.spec_from_file_location('kb_owned_runtime', ROOT / 'tools/runtime/kb-runtime.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def finite_pid(value):
    if not re.fullmatch('[1-9][0-9]{0,9}', value) or int(value) > 2147483647:
        raise ValueError('Invalid owning PostgreSQL PID receipt')
    return int(value)


class RuntimeFixture:
    def __init__(self, directory):
        self.runtime = runtime_module()
        self.directory, self.state = self.runtime.verify(directory)
        resources = self.runtime.inventory(self.directory, self.state)
        self.services = {}
        for identifier, value in resources['container'].items():
            service = value['Config']['Labels']['com.docker.compose.service']
            if service in self.services:
                raise ValueError('Duplicate owned fixture service')
            self.services[service] = identifier
        if not {'dm-postgres', 'kb-postgres', 'document-management', 'knowledge-base', 'browser-bff', 'dm-sharing'} <= self.services.keys():
            raise ValueError('Incomplete actual cross-service fixture')
        self.evidence = self.directory / ('authority-acceptance-' + uuid.uuid4().hex)
        self.evidence.mkdir(mode=0o700)
        self.helper_hash = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        self.runtime.write(self.evidence / 'run.json', {'version': 1, 'phase': 'STARTING',
                           'runtimeSource': self.state['source'], 'runtimeCommit': self.state['commit'],
                           'helperSha256': self.helper_hash, 'scope': 'BP04-authority-A-B-C-subset'})
        self.security = self.directory / 'security'
        values = self.runtime.security().env_read(self.security / 'identity/runtime.env')
        catalog = json.loads(private_bytes(self.security / 'identity/authority/grants.json')[0])
        actors = {entry['subject'] for entry in catalog['entries'] if entry['issuer'] == ISSUER}
        if len(actors) != 1:
            raise ValueError('Ambiguous synthetic actual actor')
        self.subject = require_uuid(actors.pop())
        self.identity = BrowserIdentity(self.security / 'identity/ca.crt')
        self.identity.login(values['DKP_TEST_USERNAME'], values['DKP_TEST_PASSWORD'], self.subject)

    def record(self, case, value):
        if case not in CASES:
            raise ValueError('Unknown private evidence selector')
        self.runtime.write(self.evidence / (case + '.json'), value)

    def backend_receipt(self, value):
        bound = {**value, 'runtimeSource': self.state['source'], 'runtimeCommit': self.state['commit'],
                 'helperSha256': self.helper_hash}
        self.runtime.write(self.evidence / ('backend-' + value['application'] + '.json'), bound)

    def failed(self, case, phase):
        path = self.evidence / (case + '.json')
        value = json.loads(private_bytes(path)[0]) if path.exists() else {'version': 1, 'case': case}
        value['phase'] = phase
        self.record(case, value)

    def pg(self, owner):
        return PgSession(self.services['kb-postgres' if owner == 'kb' else 'dm-postgres'], owner,
                         receipt=self.backend_receipt)

    def catalog_observer(self, owner, case, stage='revocation'):
        if owner not in ACTIONS or case not in CASES or stage not in ('revocation', 'restoration'):
            raise ValueError('Invalid catalog observer evidence selector')
        service = 'document-management' if owner == 'dm' else 'knowledge-base'
        def receipt(value):
            self.runtime.write(self.evidence / (case + '-catalog-' + stage + '.json'),
                               {**value, 'owner': owner, 'stage': stage, 'container': self.services[service],
                                'runtimeSource': self.state['source'], 'runtimeCommit': self.state['commit'],
                                'helperSha256': self.helper_hash})
        return CatalogObserver(self.services[service], owner, receipt=receipt)

    def snapshot(self, session, owner):
        value = session.scalar(snapshot_sql(owner), timeout=10)
        if not re.fullmatch('[a-f0-9]{64}', value):
            raise ValueError('Invalid whole owned-state digest')
        return value

    def catalog(self, owner):
        relative = 'identity/authority/grants.json' if owner == 'dm' else 'kb-authority/grants.json'
        return self.security / relative

    def setup(self):
        draft = self.identity.json('POST', '/api/v1/dm/documents', {
            'metadata': {'title': 'Synthetic cross-service authority barrier', 'description': 'Public metadata-only fixture.'},
            'classification': 'development-restricted', 'classificationPolicyRevision': 1},
            key=str(uuid.uuid4()), expected=201)
        document = require_uuid(draft.get('documentId'))
        collection = self.identity.json('POST', '/api/v1/kb/collections', {
            'title': 'Synthetic authority barrier', 'description': 'Public metadata-only fixture.'},
            key=str(uuid.uuid4()), expected=201)
        identifier = require_uuid(collection.get('collectionId'))
        membership = self.identity.json('POST', '/api/v1/kb/collections/' + identifier + '/memberships', {
            'expectedRevision': collection['revision'], 'documentId': document,
            'expectedDocumentRevision': draft['revision'], 'sharingScope': 'SELECTED_TENANT',
            'reason': 'Synthetic external barrier preparation'}, key=str(uuid.uuid4()), expected=201)
        return document, identifier, membership['revision']


def wait_blocked(observer, pid):
    waiter_sql(pid)  # Validate the closed owning-PID selector before any observer dispatch.
    deadline = time.monotonic() + LOCK_MS / 1000
    while time.monotonic() < deadline:
        # A single JSON scalar, containing count/time only, leaves PostgreSQL.
        sql = ("SELECT json_build_object('count',count(*),'waitMs',"
               "COALESCE(max(EXTRACT(EPOCH FROM clock_timestamp()-a.query_start)*1000),0))::text "
               "FROM pg_stat_activity a WHERE a.datname=current_database() "
               "AND a.usename IN ('dkp_dm_runtime','dkp_kb_runtime') AND "
               + str(pid) + '=ANY(pg_blocking_pids(a.pid));')
        value = json.loads(observer.scalar(sql, timeout=max(.001, deadline - time.monotonic())))
        if (set(value) != {'count', 'waitMs'} or type(value['count']) is not int
                or not isinstance(value['waitMs'], (float, int)) or isinstance(value['waitMs'], bool)
                or not 0 <= value['waitMs'] <= LOCK_MS):
            raise ValueError('Invalid actual lock-wait observation')
        if value['count'] == 1:
            return time.monotonic(), value['waitMs']
        if value['count'] != 0:
            raise ValueError('Nonexclusive actual barrier waiter')
        time.sleep(.002)
    raise ValueError('Actual runtime request never reached owned lock barrier')


def require_receipt(value, collection):
    if (not isinstance(value, dict) or set(value) != {'collectionId', 'revision'}
            or value['collectionId'] != collection or type(value['revision']) is not int
            or not 0 <= value['revision'] <= 9007199254740991):
        raise ValueError('Invalid minimal original command receipt')


def _one_case(fixture, case):
    if case not in CASES:
        raise ValueError('Unknown cross-service authority case')
    document, collection, revision = fixture.setup()
    body = {'expectedRevision': revision, 'status': 'ACTIVE', 'reason': 'Synthetic external authority barrier'}
    path, key = '/api/v1/kb/collections/' + collection + '/status', str(uuid.uuid4())
    owner = 'dm' if case == 'dm-deny-during-assessment' else 'kb'
    revoke = 'kb' if case == 'kb-deny-before-commit' else 'dm'
    original_catalog_digest = hashlib.sha256(private_bytes(fixture.catalog(revoke))[0]).hexdigest()
    result = queue.Queue(maxsize=1)
    def request():
        try:
            result.put(fixture.identity.json('PATCH', path, body, key=key))
        except Exception:
            result.put(None)
    with fixture.catalog_observer(revoke, case) as catalog_observer, fixture.pg('kb') as kb, fixture.pg('dm') as dm, fixture.pg(owner) as blocker, fixture.pg(owner) as observer:
        before, dm_before = fixture.snapshot(kb, 'kb'), fixture.snapshot(dm, 'dm')
        pid = finite_pid(blocker.scalar('BEGIN; SELECT pg_backend_pid();'))
        if pid != blocker.pid:
            raise ValueError('Owning PostgreSQL backend identity changed')
        if blocker.scalar(lock_sql(owner, document if owner == 'dm' else None)) != '1':
            raise ValueError('Missing exact owned barrier row')
        private = {'version': 1, 'case': case, 'phase': 'IN_PROGRESS',
                   'runtimeSource': fixture.state['source'], 'runtimeCommit': fixture.state['commit'],
                   'actor': {'issuer': ISSUER, 'subject': fixture.subject}, 'tenant': 'tenant-a',
                   'method': 'PATCH', 'path': path, 'body': body, 'idempotencyKey': key,
                   'wholeKbStateBefore': before, 'wholeDmStateBefore': dm_before}
        fixture.record(case, private)
        thread = threading.Thread(target=request, daemon=True)
        thread.start()
        try:
            observed, already_waiting_ms = wait_blocked(observer, pid)
            with revoked_catalog(fixture.catalog(revoke), revoke, ISSUER, fixture.subject) as digest:
                catalog_observer.acknowledge(digest, observed + (LOCK_MS - already_waiting_ms) / 1000)
                if (time.monotonic() - observed) * 1000 + already_waiting_ms >= LOCK_MS:
                    raise ValueError('Original owning lock budget exhausted before release')
                if blocker.scalar('COMMIT; SELECT 1;', timeout=.25) != '1':
                    raise ValueError('Owning barrier release not acknowledged')
                measured_ms = (time.monotonic() - observed) * 1000 + already_waiting_ms
                if measured_ms >= LOCK_MS:
                    raise ValueError('Actual owning lock interval exceeded approved limit')
                thread.join(timeout=12)
                if thread.is_alive():
                    raise ValueError('Actual command did not settle within outer request bound')
                response = result.get_nowait()
                if response is None:
                    raise ValueError('Actual cross-service request failed before typed response')
                status, payload = response
                after = fixture.snapshot(kb, 'kb')
                if fixture.snapshot(dm, 'dm') != dm_before:
                    raise ValueError('Metadata assessment unexpectedly mutated DM facts')
                if case == 'dm-post-check-race':
                    if status != 200:
                        raise ValueError('Documented post-DM-check race outcome not observed')
                    require_receipt(payload, collection)
                    if after == before:
                        raise ValueError('Known acknowledged command has no durable change')
                    denied = fixture.identity.json('PATCH', path, body, key=key)
                    if denied != (404, {'code': 'NOT_FOUND'}) or fixture.snapshot(kb, 'kb') != after:
                        raise ValueError('Original-key current-authority replay not denied atomically')
                elif status != 404 or payload != {'code': 'NOT_FOUND'} or after != before:
                    raise ValueError('Current authority denial did not withhold all KB facts')
            # The SQL barrier is already released. Confirm delivery of restored
            # bytes before replay or the next case; never reuse a stale ACK.
            with fixture.catalog_observer(revoke, case, 'restoration') as restored:
                restored.acknowledge(original_catalog_digest, time.monotonic() + LOCK_MS / 1000)
            if case == 'dm-post-check-race':
                replay = fixture.identity.json('PATCH', path, body, key=key, expected=200)
                if replay != payload or fixture.snapshot(kb, 'kb') != after:
                    raise ValueError('Original-key reconciliation duplicated/changed known receipt')
            private.update(phase='PASS', wholeKbStateAfter=after, receipt=payload)
            fixture.record(case, private)
            return {'case': case, 'result': 'PASS', 'lockWaitAndReleaseMs': round(measured_ms, 3),
                    'wholeKbStateBefore': before, 'wholeKbStateAfter': after,
                    'dmStateUnchanged': True, 'knownCommit': case == 'dm-post-check-race'}
        except Exception:
            private['phase'] = 'FAILED_RECONCILIATION_REQUIRED'
            fixture.record(case, private)
            raise
        finally:
            # Closing this exact session rolls back/releases our lock; no provider
            # stop/restart, table cleanup, fact deletion or false rollback claim.
            blocker.close()
            thread.join(timeout=12)
            if thread.is_alive():
                private['phase'] = 'UNKNOWN_REQUEST_SETTLEMENT'
                fixture.record(case, private)
                raise ValueError('Owned acceptance request failed to settle')


def one_case(fixture, case):
    try:
        return _one_case(fixture, case)
    except BackendUnsettled:
        fixture.failed(case, 'UNKNOWN_BACKEND_SETTLEMENT')
        raise
    except CatalogObserverUnsettled:
        fixture.failed(case, 'UNKNOWN_CATALOG_OBSERVER_SETTLEMENT')
        raise


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-directory', type=Path, required=True)
    parser.add_argument('--case', choices=CASES, action='append')
    args = parser.parse_args(arguments)
    selected = args.case or list(CASES)
    if len(set(selected)) != len(selected):
        raise ValueError('Duplicate acceptance case')
    source_hash = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    fixture = RuntimeFixture(args.runtime_directory)
    receipts = [one_case(fixture, case) for case in selected]
    fixture.runtime.verify(fixture.directory)
    if hashlib.sha256(Path(__file__).read_bytes()).hexdigest() != source_hash:
        raise ValueError('Acceptance helper changed during execution')
    print(json.dumps({'schemaVersion': 1, 'profile': 'BP04-authority-A-B-C', 'result': 'PASS',
                      'runtimeCommit': fixture.state['commit'], 'runtimeSource': fixture.state['source'],
                      'helperSha256': source_hash, 'cases': receipts,
                      'scope': 'Actual DM/KB/catalog/PG via genuine OIDC BFF; external owning lock barriers.',
                      'remaining': ['known-commit response withholding at protected serialization',
                                    'true UNKNOWN COMMIT network loss/restart',
                                    'full100-member policy/audience/activity matrix']}, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('BP04 authority acceptance failed; retain private fixture for reconciliation.') from None
