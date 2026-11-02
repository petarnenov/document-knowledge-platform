#!/usr/bin/env python3
"""Owned persistent DKP logging runtime. No implicit stop/down/delete or secret output."""
import argparse
from contextlib import contextmanager
import fcntl
import importlib.util
import ipaddress
import socket
import ssl
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
PG = 'postgres:18.6@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4'
JDK = 'eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507'
SQL = ROOT / 'services/dev-logging/adapters-out/src/main/resources/db'
COMPOSE = ROOT / 'infra/docker-compose/compose-dev-logging.yaml'


class RuntimeFailure(Exception):
    """Fixed safe codes only; underlying Docker/SQL/config messages never escape."""


def require(condition, code='LOG_RUNTIME_INVALID'):
    if not condition:
        raise RuntimeFailure(code)


def command(args, *, data=None, timeout=60):
    try:
        result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeFailure('LOG_RUNTIME_COMMAND_UNAVAILABLE') from None
    if result.returncode:
        raise RuntimeFailure('LOG_RUNTIME_COMMAND_FAILED')
    return result.stdout


def docker(*args, **kwargs):
    return command(['docker', *args], **kwargs)


def inspect(kind, name):
    return json.loads(docker(kind, 'inspect', name))[0]


def exists(kind, name):
    fields = {'container': ['ps', '--all'], 'volume': ['volume', 'ls'], 'network': ['network', 'ls'], 'image': ['image', 'ls']}[kind]
    rows = docker(*fields, '--format', '{{json .}}').decode().splitlines()
    key = {'container': 'Names', 'volume': 'Name', 'network': 'Name', 'image': 'Repository'}[kind]
    return any((json.loads(row).get('Repository', '') + ':' + json.loads(row).get('Tag', '') if kind == 'image' else json.loads(row).get(key)) == name for row in rows)


def private(path, maximum=1024 * 1024):
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and not info.st_mode & 0o077)
    require(0 < info.st_size <= maximum)
    with path.open('rb') as source:
        value = source.read(maximum + 1)
    require(len(value) <= maximum)
    return value


def write_private(path, value, replace=False):
    payload = value.encode() if isinstance(value, str) else value
    if replace:
        private(path)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        os.chmod(temporary, 0o600)
        stream.write(payload)
    try:
        if replace:
            os.replace(temporary, path)
        else:
            os.link(temporary, path)  # Fail if an existing file/symlink appeared.
    finally:
        temporary.unlink(missing_ok=True)


def labels(state):
    return {'dkp.owner': 'shared-dev-logging', 'dkp.issue': '457', 'dkp.logging.owner-id': state['ownerId']}


def owned(item, state):
    actual = item.get('Config', {}).get('Labels', item.get('Labels', {})) or {}
    require(all(actual.get(key) == value for key, value in labels(state).items()), 'LOG_RUNTIME_OWNER_MISMATCH')


def validate_state(state, directory, project, port, idp_port=None):
    require(state['schemaVersion'] == 1 and re.fullmatch('[0-9a-f]{32}', state['ownerId']))
    require(state['directory'] == str(directory) and state['project'] == project and state['port'] == port)
    require(state['idpPort'] == (port + 1 if idp_port is None else idp_port))
    require(set(state['subnets']) == {'data','edge'})
    networks = [ipaddress.IPv4Network(value) for value in state['subnets'].values()]
    require(all(value.prefixlen == 28 and value.subnet_of(ipaddress.IPv4Network('10.241.0.0/16')) for value in networks) and not networks[0].overlaps(networks[1]))
    require(state['uid'] == os.getuid() and state['gid'] == os.getgid() and state['uid'] > 0)


def save(directory, state):
    path = directory / 'state.json'
    write_private(path, json.dumps(state, sort_keys=True) + '\n', replace=path.exists())


def volume_identity(item):
    return hashlib.sha256(json.dumps({key: item.get(key) for key in ('Name', 'CreatedAt', 'Mountpoint', 'Driver', 'Options', 'Scope', 'Labels')}, sort_keys=True).encode()).hexdigest()


def check_mounts(item, state, service):
    expected = ({'/var/lib/postgresql': ('volume', state['project'] + '-postgres-data', True),
                 '/run/secrets': ('volume', state['project'] + '-postgres-secrets', False)} if service == 'postgres'
                else {'/run/dkp/log-query': ('bind', str(Path(state['directory']) / 'query'), False)})
    if service == 'viewer':
        expected = {'/run/dkp/viewer/read-token': ('bind', str(Path(state['directory']) / 'operator/read-token'), False)}
    if service == 'gateway':
        expected = {'/run/dkp/gateway/' + name: ('bind', str(Path(state['directory']) / ('gateway' if name.endswith('.conf') else 'gateway-pki') / name), False) for name in ('server.conf','log-proxy.conf','portal.crt','portal.key','idp.crt','idp.key','ca.crt')}
    actual = {}
    for mount in item['Mounts']:
        if mount['Type'] == 'tmpfs':
            require(mount['Destination'] in ('/tmp', '/var/run/postgresql'))
            continue
        require(mount['Destination'] not in actual)
        actual[mount['Destination']] = (mount['Type'], mount.get('Name') if mount['Type'] == 'volume' else mount['Source'], mount['RW'])
    require(actual == expected, 'LOG_RUNTIME_MOUNT_MISMATCH')


def check_resources(state):
    project = state['project']
    for kind, suffixes in [('volume', ['postgres-data', 'postgres-secrets']), ('network', ['data', 'edge'])]:
        for suffix in suffixes:
            name = project + '-' + suffix
            present = exists(kind, name)
            if kind == 'volume' and state['stage'] != 'NEW':
                require(present and name in state['volumes'], 'LOG_RUNTIME_VOLUME_MISSING')
            if present:
                item = inspect(kind, name)
                owned(item, state)
                if kind == 'volume' and state['stage'] != 'NEW':
                    require(volume_identity(item) == state['volumes'][name], 'LOG_RUNTIME_VOLUME_MISMATCH')
                if kind == 'network':
                    require(item['Internal'] == (suffix == 'data') and [entry['Subnet'] for entry in item['IPAM']['Config']] == [state['subnets'][suffix]], 'LOG_RUNTIME_NETWORK_MISMATCH')
    ids = docker('ps', '--all', '--filter', 'label=com.docker.compose.project=' + project, '--format', '{{.ID}}').decode().split()
    for identity in ids:
        item = inspect('container', identity)
        owned(item, state)
        service = item['Config']['Labels'].get('com.docker.compose.service')
        require(service in (('postgres','query','gateway','viewer') if state.get('viewerEnabled') else ('postgres','query','gateway')))
        check_mounts(item, state, service)


def grant(token, reader_id):
    expiry = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=30)).isoformat().replace('+00:00', 'Z')
    return {'schemaVersion': 1, 'readers': [{'id': reader_id, 'operator': True, 'expiresAt': expiry,
                                           'tokenSha256': hashlib.sha256(token.encode()).hexdigest()}]}


def host_ranges(ignore_devices=()):
    ranges = []
    try:
        if sys.platform == 'darwin':
            text = command(['netstat','-rn','-f','inet']).decode()
            require('Destination' in text and 'Netif' in text, 'LOG_RUNTIME_ROUTE_INVENTORY')
            rows = text.split('Destination',1)[1].splitlines()[1:]
            for row in rows:
                fields = row.split()
                if not fields or fields[0] == 'default':
                    continue
                require(len(fields) >= 4, 'LOG_RUNTIME_ROUTE_INVENTORY')
                address, separator, prefix = fields[0].partition('/')
                octets = address.split('.')
                prefix = int(prefix) if separator else len(octets) * 8
                address = '.'.join(octets + ['0'] * (4-len(octets)))
                ranges.append(ipaddress.IPv4Network(address+'/'+str(prefix), strict=False))
        elif sys.platform.startswith('linux'):
            routes = json.loads(command(['ip','-j','-4','route','show','table','all']))
            interfaces = json.loads(command(['ip','-j','-4','addr','show']))
            require(isinstance(routes,list) and isinstance(interfaces,list) and interfaces, 'LOG_RUNTIME_ROUTE_INVENTORY')
            for row in routes:
                if row.get('dst','default') != 'default' and row.get('dev') not in ignore_devices:
                    ranges.append(ipaddress.IPv4Network(row['dst'],strict=False))
            for interface in interfaces:
                if interface['ifname'] not in ignore_devices:
                    for address in interface['addr_info']:
                        if address['family'] == 'inet':
                            ranges.append(ipaddress.IPv4Network(address['local']+'/'+str(address['prefixlen']),strict=False))
        else:
            raise ValueError('Unsupported route inventory')
        require(ranges, 'LOG_RUNTIME_ROUTE_INVENTORY')
        return ranges
    except (ValueError,KeyError,TypeError,UnicodeError,RuntimeFailure):
        raise RuntimeFailure('LOG_RUNTIME_ROUTE_INVENTORY') from None


def occupied_ranges(state=None):
    ids = docker('network','ls','--format','{{.ID}}').decode().split()
    ranges, ignored = [], []
    for identity in ids:
        item = inspect('network',identity)
        owned_network = state is not None and item['Name'] in {state['project']+'-data',state['project']+'-edge'}
        if owned_network:
            owned(item,state)
            ignored.append(item.get('Options',{}).get('com.docker.network.bridge.name','br-'+item['Id'][:12]))
            continue
        for entry in item['IPAM'].get('Config') or []:
            network = ipaddress.ip_network(entry['Subnet'])
            if network.version == 4:
                ranges.append(network)
    return ranges + host_ranges(ignored)


def select_subnets():
    occupied = occupied_ranges()
    available = [str(candidate) for candidate in ipaddress.IPv4Network('10.241.0.0/16').subnets(new_prefix=28)
                 if not any(candidate.overlaps(existing) for existing in occupied)]
    require(len(available) >= 2, 'LOG_RUNTIME_NETWORK_POOL_EXHAUSTED')
    return dict(zip(('data','edge'),secrets.SystemRandom().sample(available,2)))


def check_subnets(state):
    occupied = occupied_ranges(state)
    require(all(not ipaddress.IPv4Network(value).overlaps(existing) for value in state['subnets'].values() for existing in occupied),
            'LOG_RUNTIME_NETWORK_COLLISION')


def security_bootstrap():
    spec = importlib.util.spec_from_file_location('logging_local_security', ROOT / 'infra/docker-compose/bootstrap-security.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def free_ports(port, idp_port):
    security_bootstrap().origins(port, idp_port)
    listeners = []
    try:
        for value in (port, idp_port):
            listener = socket.socket()
            listeners.append(listener)
            listener.bind(('127.0.0.1', value))
    except OSError:
        raise RuntimeFailure('LOG_RUNTIME_PORT_UNAVAILABLE') from None
    finally:
        for listener in listeners:
            listener.close()


def create_pki(directory, port, idp_port):
    security_bootstrap().generate(directory / 'gateway-pki', portal_port=port, idp_port=idp_port)


def gateway_config(directory, port, idp_port, viewer=False):
    (directory / 'gateway').mkdir(mode=0o700, exist_ok=True)
    for name, template in [('server.conf','dev-logging-gateway-server.template.conf'),('log-proxy.conf','dev-logging-log-proxy.template.conf')]:
        routes = COMPOSE.with_name('dev-logging-viewer-routes.template.conf').read_text() if viewer else 'location = /dev/logs { return 404; }\nlocation ^~ /dev/logs/ { return 404; }\nlocation = /logs.html { return 404; }'
        value = COMPOSE.with_name(template).read_text().replace('__VIEWER_ROUTES__',routes).replace('__PORTAL_PORT__', str(port)).replace('__IDP_PORT__', str(idp_port))
        write_private(directory / 'gateway' / name, value, replace=(directory / 'gateway' / name).exists())


def initialize(directory, project, port, idp_port=None):
    require(project == 'dkp-dev-logging' or re.fullmatch('dkp-dev-logging-acceptance-[0-9a-f]{12}', project))
    idp_port = port + 1 if idp_port is None else idp_port
    security_bootstrap().origins(port, idp_port)
    require(os.getuid() > 0)
    require(not directory.is_symlink())
    directory = directory.absolute()
    if (directory / 'state.json').exists():
        state = json.loads(private(directory / 'state.json'))
        validate_state(state, directory, project, port, idp_port)
        check_resources(state)
        return state
    # A new owner never adopts a preexisting Docker project, volume, network or private directory.
    require(not directory.exists(), 'LOG_RUNTIME_UNKNOWN_DIRECTORY')
    state = {'schemaVersion': 1, 'ownerId': secrets.token_hex(16), 'directory': str(directory),
             'project': project, 'port': port, 'idpPort': idp_port, 'uid': os.getuid(), 'gid': os.getgid(), 'stage': 'NEW'}
    check_resources(state)
    free_ports(port, idp_port)
    state['subnets'] = select_subnets()
    directory.mkdir(parents=True, mode=0o700)
    for name in ('query', 'operator', 'database'):
        (directory / name).mkdir(mode=0o700)
    write_private(directory / 'database/admin-password', secrets.token_urlsafe(48))
    admin = private(directory / 'database/admin-password').decode()
    write_private(directory / 'database/admin.pgpass', '*:*:*:dkp_log_admin:' + admin + '\n')
    write_private(directory / 'query/password', secrets.token_urlsafe(48))
    write_private(directory / 'query/cursor-key', secrets.token_hex(32))
    token = secrets.token_urlsafe(48)
    write_private(directory / 'operator/read-token', token)
    write_private(directory / 'query/readers.json', json.dumps(grant(token, str(uuid.uuid4()))))
    create_pki(directory, port, idp_port)
    gateway_config(directory, port, idp_port)
    save(directory, state)
    return state


def config_digest(directory):
    digest = hashlib.sha256()
    for name in ('password', 'cursor-key', 'readers.json'):
        digest.update(name.encode() + b'\0' + private(directory / 'query' / name))
    return digest.hexdigest()


def build_image(state, directory, component='query'):
    sys.path.insert(0, str(ROOT / 'tools/ci'))
    import verify
    with tempfile.TemporaryDirectory(prefix='dkp-log-public-source-') as temporary:
        source = verify.snapshot(ROOT, Path(temporary))
        image = state['project'] + '-' + component + ':' + source['fingerprint'][:16]
        args = ['build', '--platform', 'linux/amd64', '--tag', image,
                '--file', str(COMPOSE.with_name('dev-logging-'+component+'.Dockerfile' if component != 'query' else 'dev-logging.Dockerfile'))]
        for key, value in {**labels(state), 'dkp.logging.source': source['fingerprint']}.items():
            args.extend(['--label', key + '=' + value])
        if exists('image', image):
            owned(inspect('image', image), state)
        docker(*args, temporary, timeout=900)
    item = inspect('image', image)
    owned(item, state)
    require(item['Os'] == 'linux' and item['Architecture'] == 'amd64')
    if component == 'viewer':
        return {'viewerImage': image, 'viewerImageId': item['Id'], 'viewerSourceFingerprint': source['fingerprint']}
    if component == 'gateway':
        return {'gatewayImage': image, 'gatewayImageId': item['Id'], 'gatewaySourceFingerprint': source['fingerprint']}
    return {'image': image, 'imageId': item['Id'], 'sourceFingerprint': source['fingerprint']}


def environment(state, directory):
    values = {'DKP_LOG_PROJECT': state['project'], 'DKP_LOG_OWNER_ID': state['ownerId'],
              'DKP_LOG_VIEWER_IMAGE': state.get('viewerImage','dkp-disabled-viewer:unselected'),
              'DKP_LOG_VIEWER_TOKEN': str(directory / 'operator/read-token'),
              'DKP_LOG_QUERY_IMAGE': state['image'], 'DKP_LOG_GATEWAY_IMAGE': state['gatewayImage'],
              'DKP_LOG_DATA_SUBNET': state['subnets']['data'], 'DKP_LOG_EDGE_SUBNET': state['subnets']['edge'],
              'DKP_LOG_PORT': str(state['port']), 'DKP_LOG_IDP_PORT': str(state['idpPort']),
              'DKP_LOG_GATEWAY_CONFIG': str(directory / 'gateway'), 'DKP_LOG_GATEWAY_PKI': str(directory / 'gateway-pki'),
              'DKP_LOG_UID': str(state['uid']), 'DKP_LOG_GID': str(state['gid']),
              'DKP_LOG_QUERY_CONFIG': str(directory / 'query')}
    require(all('\n' not in value and '\r' not in value and '$' not in value and '"' not in value for value in values.values()))
    return ''.join(key + '="' + value + '"\n' for key, value in values.items())


def compose(state, directory, *args, timeout=120):
    return docker('compose', '--project-name', state['project'], '--env-file', str(directory / 'compose.env'),
                  '--file', str(COMPOSE), *args, timeout=timeout)


def container(state, directory, service):
    identity = compose(state, directory, 'ps', '--all', '--quiet', service).decode().strip()
    require(bool(identity) and '\n' not in identity, 'LOG_RUNTIME_CONTAINER_MISSING')
    item = inspect('container', identity)
    owned(item, state)
    return item


def install_secrets(state, directory):
    name = state['project'] + '-postgres-secrets'
    if not exists('volume', name):
        args = ['volume', 'create']
        for key, value in {**labels(state), 'com.docker.compose.project': state['project'], 'com.docker.compose.volume': 'postgres-secrets'}.items():
            args.extend(['--label', key + '=' + value])
        docker(*args, name)
    owned(inspect('volume', name), state)
    helper = state['project'] + '-secret-init'
    if exists('container', helper):
        owned(inspect('container', helper), state)
        docker('rm', '--force', '--volumes', helper)
    args = ['create', '--name', helper, '--platform', 'linux/amd64', '--network', 'none', '--user', '0:0',
            '--cap-drop', 'ALL', '--cap-add', 'CHOWN', '--security-opt', 'no-new-privileges:true',
            '--memory', '128m', '--cpus', '1', '--pids-limit', '16', '--volume', name + ':/secrets', '--entrypoint', '/bin/sh']
    for key, value in labels(state).items():
        args.extend(['--label', key + '=' + value])
    identity = docker(*args, PG, '-c', 'sleep 120').decode().strip()
    try:
        docker('start', identity)
        for filename in ('admin-password', 'admin.pgpass'):
            docker('cp', str(directory / 'database' / filename), identity + ':/tmp/' + filename)
        docker('exec', identity, '/bin/sh', '-ec',
               'chown 0:0 /tmp/admin-password /tmp/admin.pgpass; rm -f /secrets/admin-password /secrets/admin.pgpass; cp /tmp/admin-password /secrets/admin-password; cp /tmp/admin.pgpass /secrets/admin.pgpass; chmod 0600 /secrets/admin-password /secrets/admin.pgpass; chown 999:999 /secrets/admin-password /secrets/admin.pgpass')
    finally:
        owned(inspect('container', identity), state)
        docker('rm', '--force', '--volumes', identity)


def sql(state, directory, payload):
    item = container(state, directory, 'postgres')
    return docker('exec', '--interactive', item['Id'], 'psql', '-X', '--no-password', '--tuples-only', '--no-align',
                  '--set', 'ON_ERROR_STOP=1', '--username', 'dkp_log_admin', '--dbname', 'dkp_dev_logs',
                  data=payload, timeout=10).decode().strip()


def assert_schema(state, directory):
    digest = hashlib.sha256((SQL / 'logging-migration/V1__owned_dev_logging.sql').read_bytes()).hexdigest()
    require(sql(state, directory, (SQL / 'logging-provision/schema-state.sql').read_bytes()) == 't', 'LOG_RUNTIME_SCHEMA_MISMATCH')
    require(sql(state, directory, (SQL / 'logging-provision/schema-version.sql').read_bytes()) == '1:' + digest, 'LOG_RUNTIME_SCHEMA_MISMATCH')
    require(sql(state, directory, (SQL / 'logging-provision/schema-shape.sql').read_bytes()) == 't', 'LOG_RUNTIME_UNKNOWN_SCHEMA')
    require(sql(state, directory, (SQL / 'logging-provision/schema-digest.sql').read_bytes()) == state['schemaSha256'], 'LOG_RUNTIME_SCHEMA_DRIFT')


def provision(state, directory):
    if 'schemaSha256' in state:
        assert_schema(state, directory)
        return
    require(state['stage'] == 'DB_READY', 'LOG_RUNTIME_SCHEMA_MISMATCH')
    migration = (SQL / 'logging-migration/V1__owned_dev_logging.sql').read_bytes()
    digest = hashlib.sha256(migration).hexdigest()
    if sql(state, directory, (SQL / 'logging-provision/schema-state.sql').read_bytes()) != 't':
        require(sql(state, directory, (SQL / 'logging-provision/empty-database.sql').read_bytes()) == '0', 'LOG_RUNTIME_UNKNOWN_SCHEMA')
        password = private(directory / 'query/password', 512).decode()
        require(re.fullmatch('[A-Za-z0-9_-]{32,512}', password))
        roles = (SQL / 'logging-provision/read-role.sql').read_text().replace('__MIGRATION_SHA__', digest).replace('__READER_PASSWORD__', password)
        sql(state, directory, b'BEGIN;\n' + migration + roles.encode() + b'\nCOMMIT;\n')
    state['schemaSha256'] = sql(state, directory, (SQL / 'logging-provision/schema-digest.sql').read_bytes())
    require(re.fullmatch('[0-9a-f]{64}', state['schemaSha256']))
    assert_schema(state, directory)
    save(directory, state)


def disk(state, directory):
    item = container(state, directory, 'postgres')
    expected = state['project'] + '-postgres-data'
    require(any(mount.get('Name') == expected and mount['Destination'] == '/var/lib/postgresql' for mount in item['Mounts']))
    owned(inspect('volume', expected), state)
    line = docker('exec', item['Id'], 'df', '-B1', '--output=size,avail', '/var/lib/postgresql', timeout=2).decode().splitlines()[-1]
    size, available = map(int, line.split())
    require(available >= 2 * 1024 ** 3 and available * 10 >= size, 'LOG_RUNTIME_DISK_UNAVAILABLE')


def gateway_digest(directory):
    digest = hashlib.sha256()
    for folder, names in [('gateway', ('server.conf','log-proxy.conf')), ('gateway-pki', ('portal.crt','portal.key','idp.crt','idp.key','ca.crt'))]:
        for name in names:
            digest.update(name.encode() + b'\0' + private(directory / folder / name))
    return digest.hexdigest()


def viewer_digest(directory,state):
    return hashlib.sha256(str(state['port']).encode()+b'\0'+private(directory/'operator/read-token',512)).hexdigest()


def probe(state, directory, viewer=False):
    now = dt.datetime.now(dt.timezone.utc)
    window = 'from=' + (now - dt.timedelta(minutes=5)).isoformat().replace('+00:00', 'Z') + '&to=' + (now + dt.timedelta(minutes=1)).isoformat().replace('+00:00', 'Z')
    token = private(directory / 'operator/read-token', 512).decode()
    request = urllib.request.Request('https://localhost:' + str(state['port']) + ('/dev/logs/api/v1/events?' if viewer else '/api/v1/dev-logs/events?') + window,
                                     headers={} if viewer else {'Authorization': 'Bearer ' + token})
    try:
        with urllib.request.urlopen(request, timeout=5, context=ssl.create_default_context(cafile=directory / 'gateway-pki/ca.crt')) as response:
            require(response.status == 200 and isinstance(json.loads(response.read(1024 * 1024)).get('events'), list))
    except (OSError, ValueError):
        raise RuntimeFailure('LOG_RUNTIME_QUERY_UNAVAILABLE') from None


def verify_live(state, directory):
    check_resources(state)
    image = inspect('image', state['imageId'])
    owned(image, state)
    require(image['Config']['Labels'].get('dkp.logging.source') == state['sourceFingerprint'])
    require(config_digest(directory) == state['configSha256'], 'LOG_RUNTIME_CONFIG_UPDATE_REQUIRED')
    gateway_image = inspect('image', state['gatewayImageId'])
    owned(gateway_image, state)
    require(gateway_image['Config']['Labels'].get('dkp.logging.source') == state['gatewaySourceFingerprint'])
    require(gateway_digest(directory) == state['gatewayConfigSha256'], 'LOG_RUNTIME_CONFIG_UPDATE_REQUIRED')
    if state.get('viewerEnabled'):
        viewer_image = inspect('image', state['viewerImageId']);owned(viewer_image,state)
        require(viewer_image['Config']['Labels'].get('dkp.logging.source') == state['viewerSourceFingerprint'])
        require(viewer_digest(directory,state) == state['viewerConfigSha256'], 'LOG_RUNTIME_VIEWER_UPDATE_REQUIRED')
    for service in (('postgres','query','gateway','viewer') if state.get('viewerEnabled') else ('postgres','query','gateway')):
        item = container(state, directory, service)
        require(item['State']['Running'] and item['HostConfig']['RestartPolicy']['Name'] == 'unless-stopped')
        require(item['HostConfig']['ReadonlyRootfs'] and 'ALL' in item['HostConfig']['CapDrop'])
        memory,cpu,pids = {'postgres':(768*1024*1024,2_000_000_000,128),'query':(3*1024*1024*1024,2_000_000_000,256),'gateway':(768*1024*1024,2_000_000_000,128),'viewer':(384*1024*1024,1_000_000_000,32)}[service]
        require((item['HostConfig']['Memory'],item['HostConfig']['NanoCpus'],item['HostConfig']['PidsLimit']) == (memory,cpu,pids))
        require('no-new-privileges:true' in item['HostConfig']['SecurityOpt'])
        require(item['Config']['User'] == ('999:999' if service == 'postgres' else str(state['uid']) + ':' + str(state['gid'])))
        expected_networks = {'postgres': {'data'}, 'query': {'data','edge'}, 'gateway': {'edge'}, 'viewer': {'edge'}}[service]
        require(set(item['NetworkSettings']['Networks']) == {state['project'] + '-' + name for name in expected_networks})
        if service == 'gateway':
            require(item['Image'] == state['gatewayImageId'], 'LOG_RUNTIME_IMAGE_MISMATCH')
            require(item['HostConfig']['PortBindings'] == {
                '8443/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(state['port'])}],
                '8444/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(state['idpPort'])}]})
            require(all(item['NetworkSettings']['Ports'].get(port) for port in ('8443/tcp','8444/tcp')), 'LOG_RUNTIME_PUBLICATION_MISSING')
        else:
            require(not item['HostConfig'].get('PortBindings'))
            require(item['Image'] == state['imageId'] if service == 'query' else item['Image'] == state['viewerImageId'] if service == 'viewer' else item['Config']['Image'] == PG)
        check_mounts(item, state, service)
    assert_schema(state, directory)  # Status is read-only even for an incomplete initial state.
    probe(state, directory)
    if state.get('viewerEnabled'):
        probe(state,directory,viewer=True)
    return {'viewer': 'ENABLED' if state.get('viewerEnabled') else 'NOT_ENABLED', 'result': 'PASS', 'stage': 'SHARED_READ_RUNTIME', 'url': 'https://localhost:' + str(state['port']),
            'project': state['project'], 'sourceFingerprint': state['sourceFingerprint'], 'imageId': state['imageId'],
            'gatewayImageId': state['gatewayImageId'], 'gatewaySourceFingerprint': state['gatewaySourceFingerprint'],
            'ingestion': 'NOT_IMPLEMENTED', 'full457': 'OPEN'}


def start(state, directory, update=False, target='both'):
    check_resources(state)
    require(target in ('query','gateway','both','viewer'))
    if state['stage'] == 'READY' and not update:
        return verify_live(state, directory)
    initial = state['stage'] != 'READY'
    require(target != 'viewer' or not initial, 'LOG_RUNTIME_VIEWER_REQUIRES_READY_READ_RUNTIME')
    if not initial:
        assert_schema(state, directory)
        if target in ('gateway','viewer'):
            require(config_digest(directory) == state['configSha256'], 'LOG_RUNTIME_QUERY_UPDATE_REQUIRED')
        if target == 'query':
            require(gateway_digest(directory) == state['gatewayConfigSha256'], 'LOG_RUNTIME_GATEWAY_UPDATE_REQUIRED')
    if state.get('viewerEnabled') and target != 'viewer':
        require(viewer_digest(directory,state) == state['viewerConfigSha256'], 'LOG_RUNTIME_VIEWER_UPDATE_REQUIRED')
    components = ('viewer','gateway') if target == 'viewer' else ('query','gateway') if initial or target == 'both' else (target,)
    if target == 'viewer':
        state['viewerEnabled'] = True
    for component in components:
        state.update(build_image(state, directory, component=component))
        save(directory, state)
    if state['stage'] == 'NEW':
        install_secrets(state, directory)
    write_private(directory / 'compose.env', environment(state, directory), replace=(directory / 'compose.env').exists())
    save(directory, state)
    check_subnets(state)
    compose(state, directory, 'up', '--detach', '--wait', '--no-recreate', 'postgres')
    if state['stage'] == 'NEW':
        state['volumes'] = {state['project'] + '-' + suffix: volume_identity(inspect('volume', state['project'] + '-' + suffix)) for suffix in ('postgres-data', 'postgres-secrets')}
        state['stage'] = 'DB_READY'
        save(directory, state)
    disk(state, directory)
    provision(state, directory)
    if update and 'gateway' in components:
        gateway_config(directory, state['port'], state['idpPort'], viewer=state.get('viewerEnabled',False))
    for component in components:
        compose(state, directory, 'up', '--detach', '--wait', '--no-deps', '--force-recreate' if update else '--no-recreate', component)
    if 'query' in components:
        state['configSha256'] = config_digest(directory)
    if 'gateway' in components:
        state['gatewayConfigSha256'] = gateway_digest(directory)
    if 'viewer' in components:
        state['viewerConfigSha256'] = viewer_digest(directory,state)
    ready_until = time.monotonic() + 60
    while True:
        try:
            probe(state, directory)
            if state.get('viewerEnabled'):
                probe(state,directory,viewer=True)
            break
        except RuntimeFailure:
            if time.monotonic() >= ready_until:
                raise
            time.sleep(.5)
    result = verify_live(state, directory)
    state['stage'] = 'READY'
    save(directory, state)
    return result


def renew(state, directory):
    check_resources(state)
    assert_schema(state, directory)
    token = private(directory / 'operator/read-token', 512).decode()
    old = json.loads(private(directory / 'query/readers.json'))
    reader_id = old['readers'][0]['id']
    require(len(old['readers']) == 1 and old['readers'][0]['tokenSha256'] == hashlib.sha256(token.encode()).hexdigest())
    write_private(directory / 'query/readers.json', json.dumps(grant(token, reader_id)), replace=True)
    return start(state, directory, update=True, target='query')


def local_daemon():
    require(not os.environ.get('DOCKER_HOST') and not os.environ.get('DOCKER_CONTEXT'), 'LOG_RUNTIME_LOCAL_DAEMON_REQUIRED')
    endpoint = json.loads(docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}'))
    require(isinstance(endpoint, str) and endpoint.startswith('unix:///'), 'LOG_RUNTIME_LOCAL_DAEMON_REQUIRED')


@contextmanager
def operator_lock(directory):
    require(not directory.is_symlink())
    directory.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory.parent / (directory.name + '.operator.lock')
    descriptor = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and not info.st_mode & 0o077)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeFailure('LOG_RUNTIME_OPERATOR_BUSY') from None
        yield
    finally:
        os.close(descriptor)


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('action', choices=('start', 'status', 'update', 'renew-readers'))
    parser.add_argument('--directory', type=Path, default=ROOT / 'local-data/security/dev-logging/shared')
    parser.add_argument('--project', default='dkp-dev-logging')
    parser.add_argument('--port', type=int, default=18443)
    parser.add_argument('--idp-port', type=int, default=18444)
    parser.add_argument('--target', choices=('query','gateway','both','viewer'))
    args = parser.parse_args()
    if args.action == 'update' and args.target is None:
        parser.error('update requires explicit --target query|gateway|both|viewer')
    try:
        local_daemon()
        with operator_lock(args.directory):
            if args.action != 'start':
                require((args.directory / 'state.json').is_file(), 'LOG_RUNTIME_NOT_INITIALIZED')
            state = initialize(args.directory, args.project, args.port, args.idp_port)
            directory = Path(state['directory'])
            if args.action == 'status':
                result = verify_live(state, directory)
            elif args.action == 'renew-readers':
                result = renew(state, directory)
            else:
                result = start(state, directory, update=args.action == 'update', target=args.target or 'both')
        print(json.dumps(result, sort_keys=True))
        return 0
    except Exception as failure:
        print(json.dumps({'result': 'FAIL', 'code': str(failure) if isinstance(failure, RuntimeFailure) else 'LOG_RUNTIME_UNAVAILABLE'}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
