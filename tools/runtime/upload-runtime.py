#!/usr/bin/env python3
"""Fresh, owned BP03 development runtime. Never adopts a manually prepared stack.

The local Docker daemon/operator and public reviewed source remain trusted. Real
standard-definition download failures stop the run; no mocks, backdating or global
cleanup. Private authority stays beneath ignored local-data. Browser acceptance is
run separately against the emitted identity directory after true readiness.
"""
import argparse
import base64
from datetime import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
PROFILE = 'b2700939fc4b890650e053f3cd20b8407bbdf992d1de2082d4b8a6f29f51f5af'
SERVICES = ('document-management', 'knowledge-base', 'knowledge-search', 'browser-bff',
            'admin-portal', 'keycloak', 'dm-postgres', 'rustfs', 'clamav',
            'inspector-network', 'structure-inspector')
BUILD_SERVICES = SERVICES[:7]
STATE_FIELDS = {'schemaVersion', 'runId', 'project', 'directory', 'phase', 'workerVolume',
                'workerImage', 'networkImage', 'updaterRecord', 'definitions', 'sources', 'files'}
PROFILE_SOURCE = 'workers/structure-inspector/src/main/java/org/documentknowledge/inspector/StructureInspector.java'
SOURCES = ('infra/docker-compose/compose.yaml', 'infra/docker-compose/compose-upload.yaml',
           'infra/docker-compose/inspector.Dockerfile', 'infra/docker-compose/inspector-network.Dockerfile',
           'infra/docker-compose/inspector-network.sh', 'infra/docker-compose/nginx-upload.conf',
           'infra/docker-compose/bootstrap-security.py', 'infra/docker-compose/realm-template.json',
           'tools/runtime/rustfs-runtime.py', 'tools/runtime/clam-definitions.py', 'tools/runtime/upload-runtime.py',
           PROFILE_SOURCE, '.dockerignore', 'gradle/libs.versions.toml')
SOURCES += tuple(sorted(str(p.relative_to(ROOT)) for p in (ROOT / 'workers/structure-inspector/src').rglob('*.java')
                        if str(p.relative_to(ROOT)) not in SOURCES))


class CommandFailure(ValueError):
    def __init__(self, category):
        self.category = category
        super().__init__('Owned command failed (' + category + '); private diagnostics suppressed')


class ProbeFailure(ValueError):
    def __init__(self, category):
        self.category = category
        super().__init__('Real inspector readiness failed (' + category + ')')


class StageFailure(ValueError):
    pass


def stage(name, operation):
    # Names are static source labels. Never interpolate exception messages, command
    # arguments, private response bodies or environment values into diagnostics.
    try:
        return operation()
    except Exception as error:
        category = getattr(error, 'category', type(error).__name__)
        raise StageFailure('Owned BP03 stage ' + name + ' failed (' + category + '); private diagnostics suppressed') from None


def helper(name):
    path = ROOT / ('infra/docker-compose/bootstrap-security.py' if name == 'identity' else
                   'tools/runtime/' + {'storage': 'rustfs-runtime.py', 'clam': 'clam-definitions.py'}[name])
    spec = importlib.util.spec_from_file_location('upload_runtime_' + name, path)
    module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def application_failure_category(stderr):
    # Fixed literals only: never return any portion of private Compose diagnostics.
    if not isinstance(stderr, bytes):
        return 'applications-unknown'
    value = stderr.lower()
    for category, literals in (
            ('applications-network-pool-exhausted', (b'all predefined address pools have been fully subnetted',
                                                    b'could not find an available, non-overlapping ipv4 address pool')),
            ('applications-port-conflict', (b'port is already allocated', b'address already in use')),
            ('applications-image-unavailable', (b'no such image', b'pull access denied')),
            ('applications-mount-invalid', (b'invalid mount config', b'bind source path does not exist')),
            ('applications-dependency-unhealthy', (b'dependency failed to start', b'is unhealthy'))):
        if any(literal in value for literal in literals):
            return category
    return 'applications-unknown'


def command(args, *, timeout=60, env=None, application_start=False):
    try:
        result = subprocess.run(args, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode:
            category = application_failure_category(result.stderr) if application_start else 'process-exit-' + str(result.returncode)
            raise CommandFailure(category)
        return result.stdout
    except subprocess.TimeoutExpired:
        raise CommandFailure('command-timeout') from None
    except OSError:
        raise CommandFailure('command-unavailable') from None


def docker(*args):
    return json.loads(command(['docker', *args]))


def confined(directory):
    directory = Path(directory).absolute()
    allowed = ROOT / 'local-data/security/bp03-upload'
    if (directory != directory.resolve() or not directory.is_relative_to(allowed)
            or directory == allowed or any(p.is_symlink() for p in [directory, *directory.parents])):
        raise ValueError('Use a fresh directory beneath ignored local-data/security/bp03-upload')
    return directory


def private(path, content):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'w') as stream:
        stream.write(content);stream.flush();os.fsync(stream.fileno())


def save(state):
    path = Path(state['directory']) / 'state.json'
    temporary = path.with_suffix('.pending')
    private(temporary, json.dumps(state, separators=(',', ':')) + '\n')
    temporary.replace(path)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def load(directory, *, material=True):
    directory = confined(directory);path = directory / 'state.json'
    if (not directory.is_dir() or directory.stat().st_uid != os.getuid() or directory.stat().st_mode & 0o077
            or path.is_symlink() or not path.is_file() or path.stat().st_uid != os.getuid()
            or path.stat().st_mode & 0o077 or path.stat().st_size > 65536):
        raise ValueError('Private owned runtime record required')
    state = json.loads(path.read_text())
    if (set(state) != STATE_FIELDS or state['schemaVersion'] != 1
            or not re.fullmatch('[0-9a-f]{32}', state['runId'])
            or state['project'] != 'dkp-bp03-' + state['runId'][:12]
            or state['workerVolume'] != 'dkp137-inspector-' + state['runId']
            or state['directory'] != str(directory) or state['phase'] not in ('created', 'prepared', 'ready', 'stopped')):
        raise ValueError('Invalid closed runtime record')
    if material:
        if state['phase'] != 'ready' or not state['files']:
            raise ValueError('Runtime has not completed genuine bootstrap')
        for relative, expected in state['sources'].items():
            if relative not in SOURCES or digest(ROOT / relative) != expected:
                raise ValueError('Reviewed runtime source changed')
        if set(state['sources']) != set(SOURCES):
            raise ValueError('Incomplete source provenance')
        for relative, expected in state['files'].items():
            file = directory / relative
            if (not file.resolve().is_relative_to(directory) or file.is_symlink()
                    or not file.is_file() or file.stat().st_mode & 0o077 or digest(file) != expected):
                raise ValueError('Private runtime material changed')
    return state


def labels(state):
    return {'dkp.issue': '137', 'dkp.upload.run': state['runId']}


def owned(item, state):
    actual = item.get('Labels') if 'Labels' in item else item.get('Config', {}).get('Labels')
    actual = actual or {}
    if (any(actual.get(k) != v for k, v in labels(state).items())
            or actual.get('com.docker.compose.project', state['project']) != state['project']):
        raise ValueError('Refusing a foreign Docker resource')


def image(reference):
    value = docker('image', 'inspect', reference)[0]['Id']
    if not re.fullmatch('sha256:[0-9a-f]{64}', value):
        raise ValueError('Actual built image identity required')
    return value


def environment(directory):
    values = {}
    for line in (directory / 'runtime.env').read_text().splitlines():
        key, value = line.split('=', 1)
        if not re.fullmatch('[A-Z][A-Z0-9_]*', key) or key in values or '\x00' in value:
            raise ValueError('Invalid private environment')
        values[key] = value
    # Do not inherit ambient provider credentials, Compose overrides or Docker context.
    return {k: v for k, v in os.environ.items() if k in ('PATH', 'HOME', 'JAVA_HOME')} | values


def compose(state, *args, timeout=120):
    directory = Path(state['directory'])
    return command(['docker', 'compose', '-p', state['project'], '--env-file', str(directory / 'runtime.env'),
                    '-f', str(ROOT / 'infra/docker-compose/compose.yaml'),
                    '-f', str(ROOT / 'infra/docker-compose/compose-upload.yaml'),
                    '-f', str(directory / 'owned-compose.json'), *args],
                   timeout=timeout, env=environment(directory),
                   application_start=args == ('up', '-d', '--no-build'))


def wait_container(container, state, *, seconds=240, updater=False):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        item = docker('inspect', container)[0]
        if not updater:
            owned(item, state)
        status = item['State']
        if updater:
            if status['Status'] == 'exited':
                if status['ExitCode'] != 0:raise ValueError('Genuine FreshClam update failed')
                return
        elif status['Status'] == 'running' and status.get('Health', {}).get('Status') == 'healthy':
            return
        elif status['Status'] in ('exited', 'dead') or status.get('Health', {}).get('Status') == 'unhealthy':
            raise ValueError('Owned runtime is not healthy')
        time.sleep(2)
    raise ValueError('Owned runtime readiness deadline exceeded')


def worker_authority(directory):
    bootstrap, server, app = [directory / 'inspector' / p for p in ('bootstrap', 'server', 'app')]
    for path in (bootstrap, server, app):path.mkdir(parents=True, mode=0o700)
    private(server / 'keystore.password', secrets.token_urlsafe(36) + '\n')
    private(server / 'service.key', base64.b64encode(secrets.token_bytes(32)).decode() + '\n')
    command(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '3',
             '-subj', '/CN=DKP isolated inspector CA', '-addext', 'basicConstraints=critical,CA:TRUE',
             '-addext', 'keyUsage=critical,keyCertSign,cRLSign', '-keyout', str(bootstrap / 'ca.key'), '-out', str(app / 'ca.crt')])
    command(['openssl', 'req', '-new', '-newkey', 'rsa:3072', '-nodes', '-subj', '/CN=structure-inspector',
             '-keyout', str(bootstrap / 'server.key'), '-out', str(bootstrap / 'server.csr')])
    private(bootstrap / 'server.ext', 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:structure-inspector,DNS:localhost,IP:127.0.0.1\n')
    command(['openssl', 'x509', '-req', '-days', '3', '-sha256', '-in', str(bootstrap / 'server.csr'),
             '-CA', str(app / 'ca.crt'), '-CAkey', str(bootstrap / 'ca.key'), '-CAserial', str(bootstrap / 'ca.srl'), '-CAcreateserial',
             '-extfile', str(bootstrap / 'server.ext'), '-out', str(bootstrap / 'server.crt')])
    command(['openssl', 'pkcs12', '-export', '-name', 'inspector', '-inkey', str(bootstrap / 'server.key'),
             '-in', str(bootstrap / 'server.crt'), '-certfile', str(app / 'ca.crt'),
             '-out', str(server / 'server.p12'), '-passout', 'file:' + str(server / 'keystore.password')])
    private(app / 'service.key', (server / 'service.key').read_text())
    for path in (directory / 'inspector').rglob('*'):
        if path.is_file():path.chmod(0o600)
        elif path.is_dir():path.chmod(0o700)
    (directory / 'inspector').chmod(0o700)
    return server, app


def populate_worker_volume(state, server):
    name = state['workerVolume']
    command(['docker', 'volume', 'create', '--driver', 'local', '--label', 'dkp.issue=137',
             '--label', 'dkp.upload.run=' + state['runId'], name])
    volume = docker('volume', 'inspect', name)[0];owned(volume, state)
    if volume['Driver'] != 'local' or volume.get('Options') not in (None, {}):raise ValueError('Owned local worker volume required')
    # Docker cp handles private host files; the initializer receives no host bind,
    # network, CA private key or application/storage authority. Only ownership caps.
    script = 'chmod 400 /authority/* && chown -R 10001:10001 /authority && chmod 500 /authority'
    container = command(['docker', 'create', '--label', 'dkp.issue=137', '--label', 'dkp.upload.run=' + state['runId'],
        '--user', '0:0', '--read-only', '--network', 'none', '--cap-drop', 'ALL', '--cap-add', 'CHOWN',
        '--cap-add', 'FOWNER', '--security-opt', 'no-new-privileges:true', '--memory', '64m', '--cpus', '0.25',
        '--pids-limit', '16', '--mount', 'type=volume,src=' + name + ',dst=/authority',
        '--entrypoint', '/bin/sh', state['workerImage'], '-ec', script]).decode().strip()
    try:
        owned(docker('inspect', container)[0], state)
        for name in ('server.p12', 'keystore.password', 'service.key'):
            command(['docker', 'cp', str(server / name), container + ':/authority/' + name])
        command(['docker', 'start', '--attach', container], timeout=30)
        item=docker('inspect', container)[0];owned(item,state)
        if item['State']['ExitCode'] != 0:raise ValueError('Worker authority initialization failed')
    finally:
        command(['docker', 'rm', '--force', container])


def inspector_ready(state):
    storage = helper('storage');network = state['project'] + '_upload-data'
    network_id = storage.owned_network(network, 'https://rustfs:9000')
    ca = Path(state['directory']) / 'inspector/app/ca.crt'
    args = ['docker', 'create', '--label', 'dkp.issue=137', '--label', 'dkp.upload.run=' + state['runId'],
            '--user', f'{os.getuid()}:{os.getgid()}', '--network', network_id, '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges:true', '--memory', '64m', '--cpus', '0.25', '--pids-limit', '16',
            '--mount', 'type=bind,src=' + str(ca) + ',dst=/run/dkp/ca.crt,readonly', '--entrypoint', 'curl',
            storage.IMAGE, '--disable', '--silent', '--fail', '--max-time', '2', '--cacert', '/run/dkp/ca.crt',
            'https://structure-inspector:8443/health/readiness']
    container = command(args).decode().strip()
    try:
        owned(docker('inspect', container)[0], state)
        response = command(['docker', 'start', '--attach', container], timeout=10)
        item = docker('inspect', container)[0];owned(item, state)
        if item['State']['ExitCode'] != 0:
            raise ProbeFailure('curl-exit-' + str(item['State']['ExitCode']))
        try:
            value = json.loads(response)
        except json.JSONDecodeError:
            raise ProbeFailure('invalid-health-json') from None
        if value != {'status': 'UP'}:
            raise ProbeFailure('health-not-up')
    finally:
        command(['docker', 'rm', '--force', container])


def attest_worker(state, item):
    host, config = item['HostConfig'], item['Config']
    network_id = compose(state, 'ps', '-q', 'inspector-network').decode().strip()
    network = docker('inspect', network_id)[0];owned(network, state)
    owner = network['HostConfig']
    if (item['Image'] != state['workerImage'] or config['User'] != '10001:10001'
            or host['NetworkMode'] != 'container:' + network_id or not host['ReadonlyRootfs']
            or host.get('Privileged') or host.get('CapAdd') or host.get('CapDrop') != ['ALL']
            or host.get('SecurityOpt') not in (['no-new-privileges:true'], ['no-new-privileges'])
            or host.get('Memory') != 768 * 1024 ** 2 or host.get('NanoCpus') != 1_000_000_000
            or host.get('PidsLimit') != 64 or host.get('Tmpfs') != {'/tmp': 'uid=10001,gid=10001,mode=0700,size=1g'}
            or len(item['Mounts']) != 1 or item['Mounts'][0].get('Name') != state['workerVolume']
            or item['Mounts'][0].get('Destination') != '/run/dkp/inspector' or item['Mounts'][0].get('RW') is not False
            or network['Image'] != state['networkImage'] or network['Config']['User'] != '0:0'
            or not owner['ReadonlyRootfs'] or owner.get('Privileged') or owner.get('CapDrop') != ['ALL']
            or owner.get('CapAdd') != ['NET_ADMIN'] or network['Mounts']):
        raise ValueError('Actual inspector isolation profile differs from reviewed runtime')
    volume = docker('volume', 'inspect', state['workerVolume'])[0];owned(volume, state)
    if volume['Driver'] != 'local' or volume.get('Options') not in (None, {}):
        raise ValueError('Inspector authority is not the owned local volume')


def wait_inspector(state):
    end = time.monotonic() + 60
    last = 'not-observed'
    while time.monotonic() < end:
        try:
            inspector_ready(state)
            return
        except ValueError as failure:
            last = getattr(failure, 'category', type(failure).__name__)
            time.sleep(2)
    raise ProbeFailure('startup-deadline-60s-last-' + last)


def health(state):
    for service in SERVICES:
        container = compose(state, 'ps', '-q', service).decode().strip()
        if not re.fullmatch('[0-9a-f]{64}', container):raise ValueError('One owned service instance required')
        item = docker('inspect', container)[0];owned(item, state)
        if service == 'structure-inspector':
            if item['State']['Status'] != 'running':raise ValueError('Inspector is not running')
            attest_worker(state, item)
        else:wait_container(container, state)
    wait_inspector(state)


def local_daemon():
    if os.environ.get('DOCKER_HOST') or os.environ.get('DOCKER_CONTEXT'):
        raise ValueError('Use the trusted local default Docker context without environment overrides')
    host = json.loads(command(['docker', 'context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}']))
    if not isinstance(host, str) or not host.startswith('unix:///'):
        raise ValueError('A trusted local Unix-socket Docker daemon is required')


def start(directory):
    profile_source = (ROOT / PROFILE_SOURCE).read_text()
    match = re.search(r'PROFILE="([^"]+)"', profile_source)
    if match is None or hashlib.sha256(match[1].encode()).hexdigest() != PROFILE:
        raise ValueError('Worker policy profile does not match reviewed runtime binding')
    local_daemon()
    directory = confined(directory)
    if directory.exists():raise ValueError('Start requires a fresh directory; use verify/stop for a recorded run')
    directory.parent.mkdir(parents=True, exist_ok=True)
    directory.mkdir(mode=0o700)
    run_id = uuid.uuid4().hex
    state = {'schemaVersion': 1, 'runId': run_id, 'project': 'dkp-bp03-' + run_id[:12],
             'directory': str(directory), 'phase': 'created', 'workerVolume': 'dkp137-inspector-' + run_id,
             'workerImage': '', 'networkImage': '', 'updaterRecord': '', 'definitions': '',
             'sources': {p: digest(ROOT / p) for p in SOURCES}, 'files': {}}
    save(state)
    # A generated project is never adopted, even if an unlikely name collision occurs.
    for kind, option in (('ps', '-aq'), ('network', 'ls'), ('volume', 'ls')):
        args = ['docker', kind, option] if kind == 'ps' else ['docker', kind, option, '-q']
        if command([*args, '--filter', 'label=com.docker.compose.project=' + state['project']]).strip():
            raise ValueError('Generated project already has resources; refusing adoption')
    identity, storage, clam = helper('identity'), helper('storage'), helper('clam')
    identity.generate(directory / 'identity', portal_port=19443, idp_port=19444)
    env_path = directory / 'identity/runtime.env'
    env_path.write_text(env_path.read_text() + 'DKP_UPLOAD_COMPOSE_PROJECT=' + state['project'] + '\n')
    storage.prepare(directory / 'rustfs')
    server, app = worker_authority(directory)
    data = directory / 'rustfs-data';data.mkdir(mode=0o700)
    for reference in (storage.IMAGE, clam.IMAGE):command(['docker', 'pull', reference], timeout=300)
    for kind, filename, field in (('inspector', 'inspector.Dockerfile', 'workerImage'),
                                  ('inspector-network', 'inspector-network.Dockerfile', 'networkImage')):
        tag = 'dkp137-' + run_id + '-' + kind + ':local'
        command(['docker', 'build', '--label', 'dkp.issue=137', '--label', 'dkp.upload.run=' + run_id,
                 '-t', tag, '-f', str(ROOT / 'infra/docker-compose' / filename), str(ROOT)], timeout=1800)
        state[field] = image(tag)
    populate_worker_volume(state, server)
    updater = clam.launch_updater();state['updaterRecord'] = str(updater);save(state)
    record = clam.read_record(updater)
    wait_container(record['containerId'], state, seconds=1200, updater=True)
    generation = clam.scanner_root() / 'generations' / ('dkp137-' + run_id)
    clam.publish_completed(record['containerId'], generation, updater)
    state['definitions'] = str(generation)
    values = {'DKP_UPLOAD_SECURITY_DIRECTORY': str(directory / 'identity'),
              'DKP_UPLOAD_RUSTFS_SERVER_DIRECTORY': str(directory / 'rustfs/server'),
              'DKP_UPLOAD_RUSTFS_DATA_DIRECTORY': str(data),
              'DKP_UPLOAD_STORAGE_ENDPOINT': 'https://rustfs:9000', 'DKP_UPLOAD_STORAGE_REGION': 'us-east-1',
              'DKP_UPLOAD_STORAGE_ACCESS_KEY_FILE': str(directory / 'rustfs/app/access.key'),
              'DKP_UPLOAD_STORAGE_SECRET_KEY_FILE': str(directory / 'rustfs/app/secret.key'),
              'DKP_UPLOAD_STORAGE_CA_FILE': str(directory / 'rustfs/app/ca.crt'),
              'DKP_UPLOAD_SPOOL_DIRECTORY': str(directory / 'rustfs/spool'),
              'DKP_UPLOAD_DEFINITIONS_DIRECTORY': str(generation),
              'DKP_UPLOAD_SCANNER_HOST': 'clamav', 'DKP_UPLOAD_SCANNER_PORT': '3310',
              'DKP_UPLOAD_INSPECTOR_ENDPOINT': 'https://structure-inspector:8443/internal/v1/inspect',
              'DKP_UPLOAD_INSPECTOR_PROFILE_DIGEST': PROFILE, 'DKP_UPLOAD_INSPECTOR_IMAGE_DIGEST': state['workerImage'],
              'DKP_UPLOAD_INSPECTOR_CONTAINER_IMAGE': state['workerImage'],
              'DKP_UPLOAD_INSPECTOR_KEY_FILE': str(app / 'service.key'), 'DKP_UPLOAD_INSPECTOR_CA_FILE': str(app / 'ca.crt'),
              'DKP_UPLOAD_INSPECTOR_AUTHORITY_VOLUME': state['workerVolume']}
    private(directory / 'runtime.env', env_path.read_text() + ''.join(k + '=' + v + '\n' for k,v in values.items()))
    overrides = {'services': {s: {'labels': labels(state)} for s in SERVICES},
                 'networks': {n: {'labels': labels(state)} for n in ('default', 'dm-data', 'upload-data')},
                 'volumes': {'dm-postgres-data': {'labels': labels(state)}}}
    overrides['services']['inspector-network']['image'] = state['networkImage']
    for service in BUILD_SERVICES:overrides['services'][service]['image'] = 'dkp137-' + run_id + '-' + service + ':local'
    private(directory / 'owned-compose.json', json.dumps(overrides))
    state['phase'] = 'prepared';save(state)
    compose(state, 'config', '--quiet')
    compose(state, 'build', timeout=1800)
    stage('providers-start', lambda: compose(state, 'up', '-d', '--no-build', 'rustfs', 'clamav', 'inspector-network', 'structure-inspector'))
    for service in ('rustfs', 'clamav', 'inspector-network'):
        stage('provider-readiness-' + service, lambda: wait_container(compose(state, 'ps', '-q', service).decode().strip(), state))
    stage('inspector-readiness', lambda: wait_inspector(state))
    stage('native-iam', lambda: storage.apply(directory / 'rustfs', 'https://rustfs:9000', docker_network=state['project'] + '_upload-data'))
    stage('applications-start', lambda: compose(state, 'up', '-d', '--no-build'))
    stage('full-readiness', lambda: health(state))
    # Data/spool are mutable state, not private source authority. Hash only generated
    # static authority/config; definitions retain the publisher's immutable manifest.
    for part in ('identity', 'inspector', 'rustfs/app', 'rustfs/server', 'rustfs/bootstrap'):
        for path in (directory / part).rglob('*'):
            if path.is_file():state['files'][str(path.relative_to(directory))] = digest(path)
    for name in ('runtime.env', 'owned-compose.json'):state['files'][name] = digest(directory / name)
    state['phase'] = 'ready';save(state)
    return state


def verify_generation(state):
    clam = helper('clam');record = clam.read_record(Path(state['updaterRecord']))
    finished = clam.attest(record, completed=True)
    generation = clam.scanner_root() / 'generations' / ('dkp137-' + state['runId'])
    if state['definitions'] != str(generation) or generation.is_symlink() or generation.stat().st_mode & 0o222:
        raise ValueError('Recorded immutable definition generation required')
    manifest_path = generation / 'dkp-update.json'
    if (manifest_path.is_symlink() or not manifest_path.is_file()
            or manifest_path.stat().st_mode & 0o222 or manifest_path.stat().st_size > 4096):
        raise ValueError('Invalid recorded definition manifest')
    manifest = json.loads(manifest_path.read_text())
    if (set(manifest) != {'schemaVersion','updatedAt','databaseVersion','databaseDigest'}
            or manifest['schemaVersion'] != 1
            or datetime.fromisoformat(manifest['updatedAt'].replace('Z','+00:00')) != finished):
        raise ValueError('Definition freshness must equal genuine updater completion')
    canonical, total = [], 0
    for family in ('bytecode','daily','main'):
        present = [generation / (family + suffix) for suffix in ('.cvd','.cld')
                   if (generation / (family + suffix)).exists() or (generation / (family + suffix)).is_symlink()]
        if len(present) != 1:raise ValueError('Complete standard definitions required')
        file = present[0]
        if file.is_symlink() or not file.is_file() or file.stat().st_mode & 0o222:
            raise ValueError('Read-only regular standard definitions required')
        total += file.stat().st_size
        if total > clam.MAX_DATABASE_BYTES:raise ValueError('Standard definition byte limit')
        canonical.append(digest(file) + '  ' + file.name + '\n')
    expected = hashlib.sha256(''.join(sorted(canonical, key=lambda line: line.split('  ',1)[1])).encode()).hexdigest()
    if expected != manifest['databaseDigest']:
        raise ValueError('Published definition bytes changed')


def verify(directory):
    state = load(directory)
    identity, storage = helper('identity'), helper('storage')
    identity.generate(Path(state['directory']) / 'identity', portal_port=19443, idp_port=19444)
    storage.validate(Path(state['directory']) / 'rustfs')
    verify_generation(state)
    health(state)
    return state


def stop(directory):
    state = load(directory, material=False)
    if state['phase'] == 'stopped':return
    ids = command(['docker', 'ps', '-aq', '--no-trunc', '--filter', 'label=dkp.upload.run=' + state['runId']]).decode().splitlines()
    for resource in ids:
        owned(docker('inspect', resource)[0], state)
    for resource in ids:command(['docker', 'rm', '--force', resource])
    networks = command(['docker', 'network', 'ls', '-q', '--no-trunc', '--filter', 'label=dkp.upload.run=' + state['runId']]).decode().splitlines()
    for resource in networks:owned(docker('network', 'inspect', resource)[0], state)
    for resource in networks:command(['docker', 'network', 'rm', resource])
    if state['updaterRecord']:
        clam = helper('clam');record = clam.read_record(Path(state['updaterRecord']))
        updater = docker('inspect', record['containerId'])[0]
        if (updater['Id'] != record['containerId'] or updater['Name'] != '/' + record['containerName']
                or any(updater['Config']['Labels'].get(k) != v for k,v in clam.labels(record['runId']).items())):
            raise ValueError('Refusing a foreign updater')
        command(['docker', 'rm', '--force', record['containerId']])
        staging = docker('volume', 'inspect', record['stagingVolume'])[0]
        if staging['Labels'] != clam.labels(record['runId']) or staging['Driver'] != 'local' or staging.get('Options') not in (None, {}):
            raise ValueError('Refusing a foreign updater volume')
        if command(['docker', 'ps', '-aq', '--filter', 'volume=' + record['stagingVolume']]).strip():
            raise ValueError('Updater volume has another consumer; preserving it')
        command(['docker', 'volume', 'rm', record['stagingVolume']])
    volumes = command(['docker', 'volume', 'ls', '--format', '{{.Name}}', '--filter', 'label=dkp.upload.run=' + state['runId']]).decode().splitlines()
    if state['workerVolume'] in volumes:
        volume = docker('volume', 'inspect', state['workerVolume'])[0];owned(volume,state)
        if volume['Driver'] != 'local' or volume.get('Options') not in (None, {}):raise ValueError('Refusing a foreign worker volume')
        if command(['docker', 'ps', '-aq', '--filter', 'volume=' + state['workerVolume']]).strip():
            raise ValueError('Worker volume has another consumer; preserving it')
        command(['docker', 'volume', 'rm', state['workerVolume']])
    # Persistent PG volume, host data, definition generations and authority remain.
    # An explicit new start always uses a new run, never reuses or rotates these.
    state['phase'] = 'stopped';save(state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('start', 'verify', 'stop'))
    parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        state = globals()[args.action](args.directory)
    except StageFailure as failure:
        raise SystemExit(str(failure)) from None
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError):
        raise SystemExit('Owned BP03 runtime operation failed; private diagnostics suppressed; recorded resources preserved for inspection') from None
    print('Owned BP03 runtime ' + args.action + ' complete; no production readiness implied')


if __name__ == '__main__':
    main()
