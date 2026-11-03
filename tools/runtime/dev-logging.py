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
import shutil
import plistlib
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
    require(type(state.get('writerEnabled', False)) is bool)


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


def logging_routes(writer=False):
    if not writer:
        return ('location = /api/v1/dev-logs { proxy_pass $logs; include /run/dkp/gateway/log-proxy.conf; }\n'
                'location ^~ /api/v1/dev-logs/ { proxy_pass $logs; include /run/dkp/gateway/log-proxy.conf; }')
    deny = ('{ add_header Cache-Control no-store always; '
            'add_header X-Content-Type-Options nosniff always; '
            'add_header Referrer-Policy no-referrer always; return 404; }')
    routes = ['location = /api/v1/dev-logs ' + deny]
    # Browser context/Beacon receipts retain the BFF session/Origin/CSRF boundary.
    # Never send browser envelopes or session cookies to the producer ingestion API.
    for path, limit in [('context', 1024), ('beacon', 32768), ('beacon/receipts', 32768)]:
        routes.append('location = /api/v1/dev-logs/' + path + ' {\n'
                      '    if ($request_method != POST) { return 405; }\n'
                      '    client_max_body_size ' + str(limit) + ';\n'
                      '    proxy_pass $bff;\n'
                      '    proxy_set_header Host localhost:__PORTAL_PORT__;\n'
                      '    proxy_set_header X-Forwarded-Proto https;\n'
                      '    proxy_set_header X-Forwarded-Host localhost:__PORTAL_PORT__;\n'
                      '    proxy_set_header X-Forwarded-Port __PORTAL_PORT__;\n'
                      '    proxy_set_header Forwarded "";\n'
                      '    proxy_http_version 1.1;\n'
                      '    proxy_connect_timeout 1s;\n'
                      '    proxy_send_timeout 5s;\n'
                      '    proxy_read_timeout 6s;\n'
                      '    proxy_buffering off;\n'
                      '    proxy_request_buffering off;\n'
                      '    proxy_intercept_errors off;\n'
                      '    proxy_hide_header Cache-Control;\n'
                      '    proxy_hide_header X-Content-Type-Options;\n'
                      '    add_header Cache-Control no-store always;\n'
                      '    add_header X-Content-Type-Options nosniff always;\n'
                      '    add_header Referrer-Policy no-referrer always;\n}')
    for path, method in [('events', 'GET'), ('registrations', 'POST'), ('delegations', 'POST'),
                         ('batches', 'POST'), ('receipts', 'POST'), ('storage-observation', 'POST')]:
        routes.append('location = /api/v1/dev-logs/' + path + ' {\n'
                      '    if ($request_method != ' + method + ') { return 405; }\n'
                      '    proxy_pass $logs; include /run/dkp/gateway/log-proxy.conf;\n}')
    # Regex order matters: canonical DELETE before the closed catch-all, both before BFF regexes.
    routes.append('location ~ "^/api/v1/dev-logs/registrations/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$" {\n'
                  '    if ($request_method != DELETE) { return 405; }\n'
                  '    proxy_pass $logs; include /run/dkp/gateway/log-proxy.conf;\n}')
    routes.append('location ~ "^/api/v1/dev-logs/" ' + deny)
    return '\n'.join(routes)


def gateway_config(directory, port, idp_port, viewer=False, writer=False, native_bff_port=None):
    if native_bff_port is not None:
        require(type(native_bff_port) is int and 1024 <= native_bff_port <= 65535
            and native_bff_port not in {port, idp_port, 18081, 18082, 18083, 19081, 19082, 19083},
            'LOG_RUNTIME_NATIVE_BFF_PORT_INVALID')
    (directory / 'gateway').mkdir(mode=0o700, exist_ok=True)
    for name, template in [('server.conf','dev-logging-gateway-server.template.conf'),('log-proxy.conf','dev-logging-log-proxy.template.conf')]:
        routes = COMPOSE.with_name('dev-logging-viewer-routes.template.conf').read_text() if viewer else 'location = /dev/logs { return 404; }\nlocation ^~ /dev/logs/ { return 404; }\nlocation = /logs.html { return 404; }'
        value = COMPOSE.with_name(template).read_text().replace('__VIEWER_ROUTES__',routes).replace('__LOG_ROUTES__',logging_routes(writer)).replace('__PORTAL_PORT__', str(port)).replace('__IDP_PORT__', str(idp_port))
        if native_bff_port is not None and name == 'server.conf':
            upstream = 'set $bff http://browser-bff:8080;'
            require(value.count(upstream) == 1, 'LOG_RUNTIME_NATIVE_BFF_TEMPLATE_INVALID')
            value = value.replace(upstream, 'set $bff http://host.docker.internal:'+str(native_bff_port)+';')
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


def config_digest(directory, writer=False):
    digest = hashlib.sha256()
    names = ('password', 'cursor-key', 'readers.json')
    if writer:
        digest.update(b'writer-enabled=true\0')
        names += ('writer-password', 'registration-grants.json', 'observer-grant.json')
    for name in names:
        digest.update(name.encode() + b'\0' + private(directory / 'query' / name))
    return digest.hexdigest()


def portal_adapter():
    spec = importlib.util.spec_from_file_location('runtime_portal_image', ROOT/'tools/runtime/log-portal-image.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def portal_selection(state, directory):
    selected = state.get('portalRuntime')
    if selected is None: return None
    require(type(selected) is dict and selected.keys() == {'context', 'contextSha256', 'buildId', 'manifestSha256',
        'artifactSha256', 'nativeBffPort'}, 'LOG_RUNTIME_PORTAL_BINDING_INVALID')
    adapter = portal_adapter()
    build_id = adapter.m.identifier(selected['buildId'])
    context = directory/'operator/portal-builds'/build_id
    require(selected['context'] == str(context), 'LOG_RUNTIME_PORTAL_BINDING_INVALID')
    record = adapter.verify_prepared(context)
    require(hashlib.sha256(private(context/'image-context.json')).hexdigest() == selected['contextSha256']
        and record['buildId'] == selected['buildId'] and record['manifestSha256'] == selected['manifestSha256']
        and record['artifact']['sha256'] == selected['artifactSha256'], 'LOG_RUNTIME_PORTAL_BINDING_INVALID')
    port = selected['nativeBffPort']
    require(type(port) is int and 1024 <= port <= 65535 and port not in
        {state['port'], state['idpPort'], 18081, 18082, 18083, 19081, 19082, 19083}, 'LOG_RUNTIME_NATIVE_BFF_PORT_INVALID')
    return selected


def build_image(state, directory, component='query'):
    sys.path.insert(0, str(ROOT / 'tools/ci'))
    import verify
    with tempfile.TemporaryDirectory(prefix='dkp-log-public-source-') as temporary:
        source = verify.snapshot(ROOT, Path(temporary))
        selected = portal_selection(state, directory) if component == 'gateway' else None
        image = state['project'] + '-' + component + ':' + source['fingerprint'][:16]
        if selected: image += '-' + selected['buildId']
        context = str(selected['context']) if selected else temporary
        args = ['build', '--platform', 'linux/amd64', '--tag', image,
                '--file', str(Path(context)/'Dockerfile') if selected else str(COMPOSE.with_name('dev-logging-'+component+'.Dockerfile' if component != 'query' else 'dev-logging.Dockerfile'))]
        extra = {'dkp.logging.portal-build': selected['buildId'], 'dkp.logging.portal-manifest': selected['manifestSha256'],
                 'dkp.logging.portal-artifact': selected['artifactSha256']} if selected else {}
        for key, value in {**labels(state), 'dkp.logging.source': source['fingerprint'], **extra}.items():
            args.extend(['--label', key + '=' + value])
        if exists('image', image):
            owned(inspect('image', image), state)
        docker(*args, context, timeout=900)
        if selected: require(portal_selection(state, directory) == selected, 'LOG_RUNTIME_PORTAL_BINDING_INVALID')
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
              'DKP_LOG_QUERY_CONFIG': str(directory / 'query'),
              'DKP_LOG_WRITER_ENABLED': 'true' if state.get('writerEnabled') else 'false',
              'DKP_LOG_VOLUME_IDENTITY': state['volumes'][state['project'] + '-postgres-data'] if state.get('writerEnabled') else 'unconfigured'}
    require(all('\n' not in value and '\r' not in value and '$' not in value and '"' not in value for value in values.values()))
    return ''.join(key + '="' + value + '"\n' for key, value in values.items())


def compose(state, directory, *args, timeout=120):
    # Compose interpolates disabled profiles even for read-only ps. Older shared
    # runtimes lack viewer keys; derive only those keys without mutating their env.
    values = {'DKP_LOG_VIEWER_IMAGE': state.get('viewerImage','dkp-disabled-viewer:unselected'),
              'DKP_LOG_VIEWER_TOKEN': str(directory / 'operator/read-token'),
              'DKP_LOG_WRITER_ENABLED': 'true' if state.get('writerEnabled') else 'false',
              'DKP_LOG_VOLUME_IDENTITY': state['volumes'][state['project'] + '-postgres-data'] if state.get('writerEnabled') else 'unconfigured'}
    require(all(not any(character in value for character in ('\n','\r','$','"')) for value in values.values()))
    with tempfile.TemporaryDirectory(prefix='dkp-log-compose-viewer-') as temporary:
        overlay = Path(temporary) / 'viewer.env'
        write_private(overlay, ''.join(key + '="' + value + '"\n' for key,value in values.items()))
        return docker('compose', '--project-name', state['project'], '--env-file', str(directory / 'compose.env'),
                      '--env-file', str(overlay), '--file', str(COMPOSE), *args, timeout=timeout)


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
    return docker('exec', '--interactive', item['Id'], 'psql', '-X', '--quiet', '--no-password', '--tuples-only', '--no-align',
                  '--set', 'ON_ERROR_STOP=1', '--username', 'dkp_log_admin', '--dbname', 'dkp_dev_logs',
                  data=payload, timeout=10).decode().strip()


def assert_schema(state, directory):
    digest = hashlib.sha256((SQL / 'logging-migration/V1__owned_dev_logging.sql').read_bytes()).hexdigest()
    require(sql(state, directory, (SQL / 'logging-provision/schema-state.sql').read_bytes()) == 't', 'LOG_RUNTIME_SCHEMA_MISMATCH')
    versions = '1:' + digest
    if state.get('writerEnabled'):
        versions += '\n2:' + hashlib.sha256((SQL / 'logging-migration/V2__scoped_producer_credentials.sql').read_bytes()).hexdigest()
    require(sql(state, directory, (SQL / 'logging-provision/schema-version.sql').read_bytes()) == versions, 'LOG_RUNTIME_SCHEMA_MISMATCH')
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
    require(config_digest(directory, state.get('writerEnabled',False)) == state['configSha256'], 'LOG_RUNTIME_CONFIG_UPDATE_REQUIRED')
    gateway_image = inspect('image', state['gatewayImageId'])
    owned(gateway_image, state)
    require(gateway_image['Config']['Labels'].get('dkp.logging.source') == state['gatewaySourceFingerprint'])
    selected = portal_selection(state, directory)
    if selected:
        require(all(gateway_image['Config']['Labels'].get(key) == value for key, value in {
            'dkp.logging.portal-build': selected['buildId'], 'dkp.logging.portal-manifest': selected['manifestSha256'],
            'dkp.logging.portal-artifact': selected['artifactSha256']}.items()), 'LOG_RUNTIME_PORTAL_IMAGE_MISMATCH')
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
        if service == 'query':
            actual = dict(value.split('=',1) for value in item['Config'].get('Env',[]) if '=' in value)
            require(actual.get('DKP_LOGGING_WRITER_ENABLED','false') == ('true' if state.get('writerEnabled') else 'false'),
                    'LOG_RUNTIME_WRITER_PROFILE_MISMATCH')
            if state.get('writerEnabled'):
                expected = {'DKP_LOGGING_WRITER_PASSWORD_FILE':'/run/dkp/log-query/writer-password',
                    'DKP_LOGGING_REGISTRATION_GRANTS_FILE':'/run/dkp/log-query/registration-grants.json',
                    'DKP_LOGGING_OBSERVER_GRANT_FILE':'/run/dkp/log-query/observer-grant.json',
                    'DKP_LOGGING_VOLUME_IDENTITY':state['volumes'][state['project']+'-postgres-data']}
                require(all(actual.get(key) == value for key,value in expected.items()), 'LOG_RUNTIME_WRITER_PROFILE_MISMATCH')
    assert_schema(state, directory)  # Status is read-only even for an incomplete initial state.
    probe(state, directory)
    if state.get('viewerEnabled'):
        probe(state,directory,viewer=True)
    return {'viewer': 'ENABLED' if state.get('viewerEnabled') else 'NOT_ENABLED', 'result': 'PASS', 'stage': 'SHARED_WRITER_RUNTIME' if state.get('writerEnabled') else 'SHARED_READ_RUNTIME', 'url': 'https://localhost:' + str(state['port']),
            'project': state['project'], 'sourceFingerprint': state['sourceFingerprint'], 'imageId': state['imageId'],
            'gatewayImageId': state['gatewayImageId'], 'gatewaySourceFingerprint': state['gatewaySourceFingerprint'],
            'ingestion': 'HTTP_ENABLED_OBSERVATION_REQUIRED' if state.get('writerEnabled') else 'NOT_IMPLEMENTED',
            'eventPersistence': 'NOT_ASSERTED', 'full457': 'OPEN'}


def start(state, directory, update=False, target='both', prepared_images=None):
    check_resources(state)
    require(target in ('query','gateway','both','viewer'))
    if state['stage'] == 'READY' and not update:
        return verify_live(state, directory)
    initial = state['stage'] != 'READY'
    require(target != 'viewer' or not initial, 'LOG_RUNTIME_VIEWER_REQUIRES_READY_READ_RUNTIME')
    if not initial:
        assert_schema(state, directory)
        if target in ('gateway','viewer'):
            require(config_digest(directory, state.get('writerEnabled',False)) == state['configSha256'], 'LOG_RUNTIME_QUERY_UPDATE_REQUIRED')
        if target == 'query':
            require(gateway_digest(directory) == state['gatewayConfigSha256'], 'LOG_RUNTIME_GATEWAY_UPDATE_REQUIRED')
    if state.get('viewerEnabled') and target != 'viewer':
        require(viewer_digest(directory,state) == state['viewerConfigSha256'], 'LOG_RUNTIME_VIEWER_UPDATE_REQUIRED')
    components = ('viewer','gateway') if target == 'viewer' else ('query','gateway') if initial or target == 'both' else (target,)
    if target == 'viewer':
        state['viewerEnabled'] = True
    if prepared_images is not None:
        require(update and target == 'both' and set(prepared_images) == {'query','gateway'})
    for component in components:
        state.update(build_image(state, directory, component=component) if prepared_images is None else prepared_images[component])
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
        selected = portal_selection(state, directory)
        gateway_config(directory, state['port'], state['idpPort'], viewer=state.get('viewerEnabled',False), writer=state.get('writerEnabled',False),
            native_bff_port=selected['nativeBffPort'] if selected else None)
    for component in components:
        compose(state, directory, 'up', '--detach', '--wait', '--no-deps', '--force-recreate' if update else '--no-recreate', component)
    if 'query' in components:
        state['configSha256'] = config_digest(directory, state.get('writerEnabled',False))
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


def writer_scripts():
    names = ('logging-migration/V1__owned_dev_logging.sql', 'logging-migration/V2__scoped_producer_credentials.sql',
             'logging-provision/write-role.sql', 'logging-provision/schema-digest.sql', 'logging-provision/writer-summary.sql')
    return {name: hashlib.sha256((SQL / name).read_bytes()).hexdigest() for name in names}


def writer_versions():
    return '\n'.join(str(version) + ':' + hashlib.sha256((SQL / name).read_bytes()).hexdigest()
                     for version,name in [(1,'logging-migration/V1__owned_dev_logging.sql'),
                                          (2,'logging-migration/V2__scoped_producer_credentials.sql')])


def metadata_guard(digest):
    require(re.fullmatch('[0-9a-f]{64}', digest))
    query = (SQL / 'logging-provision/schema-digest.sql').read_text().strip().removesuffix(';')
    return "DO $dkp$ BEGIN IF (" + query + ") IS DISTINCT FROM '" + digest + "' THEN RAISE EXCEPTION 'LOG_RUNTIME_SCHEMA_DRIFT'; END IF; END $dkp$;\n"


def writer_transition_sql(directory, before, expected=None):
    password = private(directory / 'query/writer-password', 512).decode('ascii')
    require(re.fullmatch('[A-Za-z0-9_-]{32,512}', password))
    migration = (SQL / 'logging-migration/V2__scoped_producer_credentials.sql').read_text()
    role = (SQL / 'logging-provision/write-role.sql').read_text().replace('__WRITER_PASSWORD__', password)
    versions = writer_versions().splitlines()
    guard = ("DO $dkp$ BEGIN IF (SELECT string_agg(version::text||':'||migration_sha,E'\\n' ORDER BY version) "
             "FROM dev_logs.schema_migrations) IS DISTINCT FROM '" + versions[0]
             + "' OR EXISTS(SELECT FROM pg_roles WHERE rolname='dkp_log_writer') THEN RAISE EXCEPTION 'LOG_RUNTIME_UNKNOWN_WRITER_STATE'; END IF; END $dkp$;\n")
    payload = "BEGIN;\nSET LOCAL lock_timeout='2s';\nSET LOCAL statement_timeout='5s';\nLOCK TABLE dev_logs.schema_migrations IN EXCLUSIVE MODE;\n"
    payload += metadata_guard(before) + guard + migration + role
    payload += "\nINSERT INTO dev_logs.schema_migrations VALUES (2,'" + versions[1].split(':',1)[1] + "');\n"
    if expected is None:
        payload += (SQL / 'logging-provision/schema-digest.sql').read_text() + '\nROLLBACK;\n'
    else:
        payload += metadata_guard(expected) + 'COMMIT;\n'
    return payload.encode()


def writer_bindings(directory):
    names = ['query/password','query/cursor-key','query/readers.json','operator/read-token','database/admin-password',
             'query/writer-password','query/registration-grants.json','query/observer-grant.json',
             'operator/registrar-token','operator/observer-token']
    names += ['gateway-pki/' + path.name for path in sorted((directory / 'gateway-pki').iterdir())
              if not path.is_dir() or path.is_symlink()]
    return {name: hashlib.sha256(private(directory / name)).hexdigest() for name in names}


def create_writer_files(directory):
    paths = ['query/writer-password','query/registration-grants.json','query/observer-grant.json',
             'operator/registrar-token','operator/observer-token']
    require(all(not (directory / name).exists() and not (directory / name).is_symlink() for name in paths),
            'LOG_RUNTIME_UNKNOWN_WRITER_FILES')
    expires = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=30)).isoformat().replace('+00:00','Z')
    registrar, observer = secrets.token_urlsafe(48), secrets.token_urlsafe(48)
    registration = {'schemaVersion':1,'registrars':[{'id':str(uuid.uuid4()),'expiresAt':expires,
                    'tokenSha256':hashlib.sha256(registrar.encode()).hexdigest()}],'delegators':[]}
    observation = {'schemaVersion':1,'id':str(uuid.uuid4()),'expiresAt':expires,
                   'tokenSha256':hashlib.sha256(observer.encode()).hexdigest()}
    for name,value in [('query/writer-password',secrets.token_urlsafe(48)),('query/registration-grants.json',json.dumps(registration)),
                       ('query/observer-grant.json',json.dumps(observation)),('operator/registrar-token',registrar),
                       ('operator/observer-token',observer)]:
        write_private(directory / name,value)


def writer_summary(state, directory):
    value = json.loads(sql(state,directory,(SQL / 'logging-provision/writer-summary.sql').read_bytes()))
    require(set(value) == {'manifests','producers','scopes','events','capacityReservedBytes','scopeReservedBytes','eventReservedBytes'}
            and all(type(number) is int and number >= 0 for number in value.values()), 'LOG_RUNTIME_PRESERVATION_INVALID')
    return value


def enable_writer(state, directory):
    require(state['stage'] == 'READY', 'LOG_RUNTIME_WRITER_REQUIRES_READY')
    if state.get('writerEnabled') and state.get('writerActivation',{}).get('stage') == 'ACTIVE':
        return verify_live(state,directory)
    check_resources(state)
    disk(state,directory)
    if 'writerActivation' not in state:
        require(not state.get('writerEnabled'), 'LOG_RUNTIME_UNKNOWN_WRITER_STATE')
        verify_live(state,directory)
        require(sql(state,directory,b"SELECT count(*) FROM pg_roles WHERE rolname='dkp_log_writer';") == '0',
                'LOG_RUNTIME_UNKNOWN_WRITER_STATE')
        services = ('postgres','query','gateway','viewer') if state.get('viewerEnabled') else ('postgres','query','gateway')
        live = {name:container(state,directory,name) for name in services}
        before = {'containers':{name:item['Id'] for name,item in live.items()},
                  'images':{name:item['Image'] for name,item in live.items()},
                  'configuration':{key:state.get(key) for key in ('configSha256','gatewayConfigSha256','viewerConfigSha256')},
                  'volumes':dict(state['volumes']),'schemaSha256':state['schemaSha256'],
                  'versions':sql(state,directory,(SQL / 'logging-provision/schema-version.sql').read_bytes()),
                  'summary':writer_summary(state,directory)}
        create_writer_files(directory)
        state['writerActivation'] = {'stage':'PREPARING','before':before,'scripts':writer_scripts(),
                                     'files':writer_bindings(directory),'images':{}}
        save(directory,state)  # Generated credentials and before-state are bound before preview/commit.
    transition = state['writerActivation']
    require(transition['stage'] in ('PREPARING','PREPARED','MIGRATED'), 'LOG_RUNTIME_UNKNOWN_WRITER_STATE')
    require(transition['scripts'] == writer_scripts() and transition['files'] == writer_bindings(directory),
            'LOG_RUNTIME_ACTIVATION_INPUT_CHANGED')
    before = transition['before']
    require(state['volumes'] == before['volumes'] and container(state,directory,'postgres')['Id'] == before['containers']['postgres'],
            'LOG_RUNTIME_ACTIVATION_OWNER_CHANGED')
    if state.get('viewerEnabled'):
        require(container(state,directory,'viewer')['Id'] == before['containers']['viewer'], 'LOG_RUNTIME_VIEWER_CHANGED')
    require(writer_summary(state,directory) == before['summary'], 'LOG_RUNTIME_DATA_CHANGED')
    if transition['stage'] == 'PREPARING':
        assert_schema(state,directory)
        for component in ('query','gateway'):
            if component not in transition['images']:
                transition['images'][component] = build_image(state,directory,component=component)
                save(directory,state)
        expected = sql(state,directory,writer_transition_sql(directory,before['schemaSha256']))
        require(re.fullmatch('[0-9a-f]{64}',expected), 'LOG_RUNTIME_PREVIEW_INVALID')
        assert_schema(state,directory)  # Preview must have rolled back every schema/role/grant change.
        require(sql(state,directory,b"SELECT count(*) FROM pg_roles WHERE rolname='dkp_log_writer';") == '0',
                'LOG_RUNTIME_PREVIEW_INVALID')
        transition.update(expectedSchemaSha256=expected,stage='PREPARED')
        save(directory,state)
    versions = sql(state,directory,(SQL / 'logging-provision/schema-version.sql').read_bytes())
    current = sql(state,directory,(SQL / 'logging-provision/schema-digest.sql').read_bytes())
    if versions == before['versions'] and current == before['schemaSha256']:
        sql(state,directory,writer_transition_sql(directory,before['schemaSha256'],transition['expectedSchemaSha256']))
    else:
        require(versions == writer_versions() and current == transition['expectedSchemaSha256'],
                'LOG_RUNTIME_UNKNOWN_WRITER_STATE')
    state['schemaSha256'] = transition['expectedSchemaSha256']
    state['writerEnabled'] = True
    transition['stage'] = 'MIGRATED'
    save(directory,state)  # A later application failure retains committed V2 and exact recovery identities.
    assert_schema(state,directory)
    require(writer_summary(state,directory) == before['summary'], 'LOG_RUNTIME_DATA_CHANGED')
    for component, image in transition['images'].items():
        key = 'imageId' if component == 'query' else 'gatewayImageId'
        item = inspect('image',image[key]);owned(item,state)
        require(item['Id'] == image[key], 'LOG_RUNTIME_IMAGE_MISMATCH')
    # Startup runs the existing exact reader/writer SQL verifiers before readiness can pass.
    result = start(state,directory,update=True,target='both',prepared_images=transition['images'])
    require(container(state,directory,'postgres')['Id'] == before['containers']['postgres'], 'LOG_RUNTIME_ACTIVATION_OWNER_CHANGED')
    if state.get('viewerEnabled'):
        require(container(state,directory,'viewer')['Id'] == before['containers']['viewer'], 'LOG_RUNTIME_VIEWER_CHANGED')
    transition['stage'] = 'ACTIVE'
    save(directory,state)
    return result


def renew_writer_grants(state, directory):
    require(state.get('writerEnabled') is True, 'LOG_RUNTIME_WRITER_NOT_ENABLED')
    verify_live(state,directory)
    registration = json.loads(private(directory / 'query/registration-grants.json'))
    observer = json.loads(private(directory / 'query/observer-grant.json'))
    require(registration['schemaVersion'] == 1 and len(registration['registrars']) == 1 and registration['delegators'] == []
            and observer['schemaVersion'] == 1, 'LOG_RUNTIME_GRANTS_INVALID')
    registrar = registration['registrars'][0]
    for grant,name in [(registrar,'registrar-token'),(observer,'observer-token')]:
        require(grant['tokenSha256'] == hashlib.sha256(private(directory / 'operator' / name,512)).hexdigest(),
                'LOG_RUNTIME_GRANTS_INVALID')
    expiry = (dt.datetime.now(dt.timezone.utc)+dt.timedelta(days=30)).isoformat().replace('+00:00','Z')
    registrar['expiresAt'] = expiry;observer['expiresAt'] = expiry
    write_private(directory / 'query/registration-grants.json',json.dumps(registration),replace=True)
    write_private(directory / 'query/observer-grant.json',json.dumps(observer),replace=True)
    result = start(state,directory,update=True,target='query')
    if 'writerActivation' in state:
        state['writerActivation']['files'] = writer_bindings(directory)
        save(directory,state)
    return result


def unique_object(pairs):
    result = {}
    for key,value in pairs:
        require(key not in result, 'LOG_BROWSER_GRANT_INVALID'); result[key] = value
    return result


def install_browser_grant(state, directory, grant_file):
    """Finite operator-only browser bridge grant; controlled query-only replacement."""
    require(state.get('writerEnabled') is True, 'LOG_RUNTIME_WRITER_NOT_ENABLED')
    require(grant_file is not None and grant_file.is_absolute()
            and grant_file.resolve(strict=True) == grant_file, 'LOG_BROWSER_GRANT_INVALID')
    raw = private(grant_file, 32768); grant_sha = hashlib.sha256(raw).hexdigest()
    value = json.loads(raw, object_pairs_hook=unique_object)
    require(type(value) is dict and value.keys() == {'id','expiresAt','tokenSha256','parents','bridgeManifests','permissions'},
            'LOG_BROWSER_GRANT_INVALID')
    def identifier(value):
        require(isinstance(value,str) and str(uuid.UUID(value)) == value and int(uuid.UUID(value)) != 0,
                'LOG_BROWSER_GRANT_INVALID')
    identifier(value['id'])
    require(isinstance(value['tokenSha256'],str) and re.fullmatch('[0-9a-f]{64}',value['tokenSha256']), 'LOG_BROWSER_GRANT_INVALID')
    expiry = value['expiresAt']
    require(isinstance(expiry,str) and re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z',expiry),
            'LOG_BROWSER_GRANT_INVALID')
    remaining = dt.datetime.fromisoformat(expiry.replace('Z','+00:00')) - dt.datetime.now(dt.timezone.utc)
    require(dt.timedelta() < remaining <= dt.timedelta(hours=48), 'LOG_BROWSER_GRANT_INVALID')
    require(type(value['parents']) is list and len(value['parents']) == 1, 'LOG_BROWSER_GRANT_INVALID')
    identifier(value['parents'][0])
    bridges = value['bridgeManifests']
    require(type(bridges) is list and 1 <= len(bridges) <= 64
            and all(isinstance(item,str) and re.fullmatch('[0-9a-f]{64}',item) for item in bridges)
            and len(set(bridges)) == len(bridges), 'LOG_BROWSER_GRANT_INVALID')
    issuer = security_bootstrap().origins(state['port'],state['idpPort'])[1]
    catalog = json.loads(private(directory/'gateway-pki/authority/grants.json'), object_pairs_hook=unique_object)
    require(type(catalog) is dict and catalog.get('schemaVersion') == 1 and type(catalog.get('entries')) is list
            and 1 <= len(catalog['entries']) <= 4096, 'LOG_BROWSER_GRANT_INVALID')
    permissions = [{'kind':'AUTHENTICATED_UNSELECTED','issuer':issuer}]
    for row in catalog['entries']:
        require(type(row) is dict and row.get('issuer') == issuer and isinstance(row.get('tenant'),str), 'LOG_BROWSER_GRANT_INVALID')
        permissions.append({'kind':'TENANT','issuer':issuer,'tenantId':row['tenant']})
    allowed = {json.dumps(row,sort_keys=True) for row in permissions}
    requested = value['permissions']
    require(type(requested) is list and 1 <= len(requested) <= 65
            and all(type(row) is dict and json.dumps(row,sort_keys=True) in allowed for row in requested)
            and len({json.dumps(row,sort_keys=True) for row in requested}) == len(requested), 'LOG_BROWSER_GRANT_INVALID')
    pending = state.get('browserGrantUpdate')
    if pending is None: verify_live(state,directory)
    registration = json.loads(private(directory/'query/registration-grants.json'), object_pairs_hook=unique_object)
    require(type(registration) is dict and registration.keys() == {'schemaVersion','registrars','delegators'}
            and type(registration['schemaVersion']) is int and registration['schemaVersion'] == 1
            and type(registration['delegators']) is list and len(registration['delegators']) < 4096, 'LOG_BROWSER_GRANT_INVALID')
    existing = [row for row in registration['delegators'] if row.get('id') == value['id']]
    if existing:
        require(existing == [value], 'LOG_BROWSER_GRANT_CONFLICT')
        if pending is None: return {'result':'UNCHANGED','stage':'BROWSER_DELEGATOR_INSTALLED','grantId':value['id'],'full457':'OPEN'}
    else:
        others = registration['registrars'] + registration['delegators']
        others += json.loads(private(directory/'query/readers.json'))['readers']
        others += [json.loads(private(directory/'query/observer-grant.json'))]
        require(all(row['id'] != value['id'] and row['tokenSha256'] != value['tokenSha256'] for row in others),
                'LOG_BROWSER_GRANT_CONFLICT')
        registration['delegators'].append(value)
    updated = json.dumps(registration,sort_keys=True,separators=(',',':')).encode()
    require(len(updated) <= 1048576, 'LOG_BROWSER_GRANT_INVALID')
    if pending is None:
        before = writer_bindings(directory); after = {**before,'query/registration-grants.json':hashlib.sha256(updated).hexdigest()}
        preserved = {service:{'id':container(state,directory,service)['Id'],'image':container(state,directory,service)['Image']}
                     for service in (('postgres','gateway','viewer') if state.get('viewerEnabled') else ('postgres','gateway'))}
        pending = {'grantSha256':grant_sha,'before':before,'after':after,'gatewaySha256':gateway_digest(directory),
                   'volumes':state['volumes'].copy(),'preserved':preserved,
                   'queryBeforeImage':state['imageId'],'queryBeforeSource':state['sourceFingerprint']}
        state['browserGrantUpdate'] = pending; save(directory,state)
    require(pending['grantSha256'] == grant_sha and pending['after']['query/registration-grants.json'] == hashlib.sha256(updated).hexdigest()
            and writer_bindings(directory) in (pending['before'],pending['after'])
            and gateway_digest(directory) == pending['gatewaySha256'] and state['volumes'] == pending['volumes'], 'LOG_BROWSER_GRANT_UPDATE_DRIFT')
    check_resources(state); assert_schema(state,directory)
    query = container(state,directory,'query')
    require(query['Image'] in (pending['queryBeforeImage'],state['imageId']), 'LOG_BROWSER_GRANT_UPDATE_DRIFT')
    image = inspect('image',query['Image']); owned(image,state)
    expected_source = pending['queryBeforeSource'] if query['Image'] == pending['queryBeforeImage'] else state['sourceFingerprint']
    require(image['Config']['Labels'].get('dkp.logging.source') == expected_source, 'LOG_BROWSER_GRANT_UPDATE_DRIFT')
    for service,identity in pending['preserved'].items():
        item = container(state,directory,service)
        require({'id':item['Id'],'image':item['Image']} == identity, 'LOG_BROWSER_GRANT_UPDATE_DRIFT')
    if writer_bindings(directory) != pending['after']:
        write_private(directory/'query/registration-grants.json',updated,replace=True)
    # An interrupted update retains the exact private plan; repeat only that same transition.
    start(state,directory,update=True,target='query')
    require(writer_bindings(directory) == pending['after'], 'LOG_BROWSER_GRANT_UPDATE_DRIFT')
    for service,identity in pending['preserved'].items():
        item = container(state,directory,service)
        require({'id':item['Id'],'image':item['Image']} == identity, 'LOG_BROWSER_GRANT_UPDATE_DRIFT')
    if 'writerActivation' in state: state['writerActivation']['files'] = writer_bindings(directory)
    del state['browserGrantUpdate']; save(directory,state)
    return {'result':'INSTALLED','stage':'BROWSER_DELEGATOR_INSTALLED','grantId':value['id'],'full457':'OPEN'}


def install_manifest(state, directory, manifest, expected):
    require(state.get('writerEnabled') is True, 'LOG_RUNTIME_WRITER_NOT_ENABLED')
    require(manifest is not None and expected is not None and re.fullmatch('[0-9a-f]{64}',expected), 'LOG_MANIFEST_INPUT_INVALID')
    require(manifest.is_absolute() and manifest.resolve(strict=True) == manifest
            and not any(char in str(manifest) for char in (',','\n','\r')), 'LOG_MANIFEST_INPUT_INVALID')
    require(hashlib.sha256(private(manifest)).hexdigest() == expected, 'LOG_MANIFEST_INPUT_INVALID')
    verify_live(state,directory)
    image = inspect('image',state['imageId']);owned(image,state)
    require(image['Id'] == state['imageId'], 'LOG_RUNTIME_IMAGE_MISMATCH')
    operation = uuid.uuid4().hex
    args = ['create','--platform','linux/amd64','--name',state['project']+'-manifest-'+operation,
            '--user',str(state['uid'])+':'+str(state['gid']),'--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges:true','--memory','512m','--cpus','1','--pids-limit','64',
            '--network',state['project']+'-data','--tmpfs','/tmp',
            '--env','JAVA_OPTS=-XX:ActiveProcessorCount=2 -Xmx256m',
            '--mount','type=bind,src='+str(manifest)+',dst=/run/dkp/manifest.json,readonly',
            '--mount','type=bind,src='+str(directory/'database/admin-password')+',dst=/run/dkp/owner-password,readonly']
    for key,value in {**labels(state),'dkp.logging.operation':'manifest-install','dkp.logging.operation-id':operation}.items():
        args.extend(['--label',key+'='+value])
    args += [state['imageId'],'install-manifest','--manifest','/run/dkp/manifest.json','--sha256',expected,
             '--jdbc-url','jdbc:postgresql://postgres:5432/dkp_dev_logs','--password-file','/run/dkp/owner-password']
    identity = docker(*args).decode().strip()
    require(re.fullmatch('[0-9a-f]{64}',identity), 'LOG_MANIFEST_OPERATION_INVALID')
    record = {'schemaVersion':1,'operationId':operation,'containerId':identity,'imageId':state['imageId'],
              'manifestSha256':expected,'stage':'CREATED','result':'UNKNOWN','cleanup':'RETAINED'}
    evidence = directory/'operator'/('manifest-operation-'+operation+'.json')
    def operation_container():
        item = inspect('container',identity);owned(item,state)
        actual = item['Config']['Labels']
        require(item['Id'] == identity and item['Image'] == state['imageId']
                and actual.get('dkp.logging.operation') == 'manifest-install'
                and actual.get('dkp.logging.operation-id') == operation, 'LOG_MANIFEST_OPERATION_OWNER_MISMATCH')
        return item
    try:
        operation_container()
        write_private(evidence,json.dumps(record))
        output = docker('start','--attach',identity,timeout=10)
        item = operation_container()
        require(not item['State']['Running'] and item['State']['ExitCode'] == 0, 'LOG_MANIFEST_OPERATION_INCOMPLETE')
        require(len(output) <= 4096, 'LOG_MANIFEST_OPERATION_RESULT_INVALID')
        value = json.loads(output)
        require(set(value) == {'result','manifestSha256','buildId'} and value['result'] == 'INSTALLED'
                and value['manifestSha256'] == expected and str(uuid.UUID(value['buildId'])) == value['buildId'],
                'LOG_MANIFEST_OPERATION_RESULT_INVALID')
        record.update(stage='COMPLETE',result='INSTALLED',buildId=value['buildId'])
        return value
    finally:
        item = operation_container()
        if not item['State']['Running']:
            docker('rm',identity)  # Terminal exact owned one-off only. Never stop a running/unknown outcome.
            record['cleanup'] = 'REMOVED'
        else:
            record['stage'] = 'STILL_RUNNING'
        write_private(evidence,json.dumps(record),replace=evidence.exists())


def local_daemon():
    require(not os.environ.get('DOCKER_HOST') and not os.environ.get('DOCKER_CONTEXT'), 'LOG_RUNTIME_LOCAL_DAEMON_REQUIRED')
    endpoint = json.loads(docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}'))
    require(isinstance(endpoint, str) and endpoint.startswith('unix:///'), 'LOG_RUNTIME_LOCAL_DAEMON_REQUIRED')


def observer_job(record):
    try:
        result = subprocess.run(['/bin/launchctl', 'print', record['service']],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=3)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeFailure('LOG_OBSERVER_SUPERVISOR_UNAVAILABLE') from None
    if result.returncode == 113:
        return None
    require(result.returncode == 0 and len(result.stdout) <= 65536, 'LOG_OBSERVER_SUPERVISOR_UNAVAILABLE')
    text = result.stdout.decode('utf-8')
    require(re.search(r'(?m)^\s*path = ' + re.escape(record['plist']) + r'$', text)
            and re.search(r'(?m)^\s*program = ' + re.escape(record['python']) + r'$', text), 'LOG_OBSERVER_JOB_MISMATCH')
    arguments = re.search(r'(?ms)^\s*arguments = \{\n(.*?)^\s*\}', text)
    require(arguments and [line.strip() for line in arguments.group(1).splitlines() if line.strip()]
            == record['arguments'], 'LOG_OBSERVER_JOB_MISMATCH')
    pid = re.search(r'(?m)^\s*pid = ([1-9][0-9]*)$', text)
    return int(pid.group(1)) if pid else 0


def observer_files(state, directory):
    require(sys.platform == 'darwin' and 'observer' in state, 'LOG_OBSERVER_NOT_INSTALLED')
    record = state['observer']
    label = 'org.documentknowledge.devlogging.' + state['ownerId'] + '.observer'
    expected = Path.home() / 'Library/LaunchAgents' / (label + '.plist')
    require(expected.parent.resolve(strict=True) == expected.parent
            and expected.parent.stat().st_uid == os.getuid() and not expected.parent.stat().st_mode & 0o022,
            'LOG_OBSERVER_PLIST_DIRECTORY')
    require(record['label'] == label and record['service'] == 'gui/' + str(state['uid']) + '/' + label
            and record['plist'] == str(expected), 'LOG_OBSERVER_OWNER_MISMATCH')
    for path, digest in [(directory / 'observer/observer.py', record['scriptSha256']),
                         (directory / 'observer/config.json', record['configSha256']),
                         (expected, record['plistSha256'])]:
        require(hashlib.sha256(private(path)).hexdigest() == digest, 'LOG_OBSERVER_CONFIG_MISMATCH')
    return record


def observer_status(state, directory):
    record = observer_files(state, directory)
    pid = observer_job(record)
    require(pid is not None and pid > 0, 'LOG_OBSERVER_NOT_RUNNING')
    value = json.loads(private(directory / 'observer/health.json', 4096))
    require(value['schemaVersion'] == 1 and value['ownerId'] == state['ownerId']
            and type(value['sequence']) is int and value['sequence'] > 0, 'LOG_OBSERVER_HEALTH_INVALID')
    age = (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(value['at'])).total_seconds()
    require(0 <= age <= 5, 'LOG_OBSERVER_HEALTH_STALE')
    accepted = value['result'] == 'ACCEPTED' and value['code'] == 'OBSERVATION_ACCEPTED'
    require(value['result'] in ('ACCEPTED', 'FAILED', 'DEGRADED')
            and value['code'] in ('OBSERVATION_ACCEPTED', 'HTTP_UNAVAILABLE', 'MEASUREMENT_UNAVAILABLE', 'LOW_SPACE'),
            'LOG_OBSERVER_HEALTH_INVALID')
    return {'result': 'PASS' if accepted else 'FAIL', 'stage': 'SHARED_VOLUME_OBSERVER', 'pid': pid,
            'sequence': value['sequence'], 'code': value['code'], 'apiObservationAccepted': accepted,
            'eventPersistence': 'NOT_ASSERTED', 'full457': 'OPEN'}


def install_observer(state, directory, interpreter):
    require(sys.platform == 'darwin', 'LOG_OBSERVER_NATIVE_SUPERVISOR_REQUIRED')
    require(state.get('writerEnabled') is True, 'LOG_OBSERVER_REQUIRES_WRITER')
    check_resources(state)
    if 'observer' in state:
        observer_files(state, directory)
        if observer_job(state['observer']) is not None:
            return observer_status(state, directory)  # No implicit restart/replacement/rotation.
        record = state['observer']
    else:
        require(interpreter is not None, 'LOG_OBSERVER_PYTHON_REQUIRED')
        python = Path(interpreter)
        require(python.is_absolute() and python.resolve(strict=True) == python and python.is_file()
                and os.access(python, os.X_OK) and not python.stat().st_mode & 0o022)
        if state['project'] == 'dkp-dev-logging':
            require(not any(python.is_relative_to(path) or directory.is_relative_to(path)
                            for path in (ROOT, Path('/tmp'), Path('/private/tmp'), Path('/private/var/folders'))),
                    'LOG_OBSERVER_REQUIRES_STABLE_PATHS')
        version = command([str(python), '-I', '-c', 'import platform; print(platform.python_version())'], timeout=3).decode().strip()
        require(re.fullmatch(r'3\.14\.[0-9]+', version), 'LOG_OBSERVER_PYTHON_VERSION')
        binary = Path(shutil.which('docker')).resolve(strict=True)
        context = docker('context', 'show', timeout=3).decode().strip()
        require(re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', context))
        pg = container(state, directory, 'postgres')
        label = 'org.documentknowledge.devlogging.' + state['ownerId'] + '.observer'
        plist = Path.home() / 'Library/LaunchAgents' / (label + '.plist')
        require(plist.parent.resolve(strict=True) == plist.parent and plist.parent.stat().st_uid == os.getuid()
                and not plist.parent.stat().st_mode & 0o022, 'LOG_OBSERVER_PLIST_DIRECTORY')
        require(not plist.exists() and not plist.is_symlink(), 'LOG_OBSERVER_UNKNOWN_JOB')
        record = {'label': label, 'service': 'gui/' + str(state['uid']) + '/' + label, 'plist': str(plist),
                  'python': str(python), 'pythonVersion': version,
                  'arguments': [str(python), '-I', str(directory / 'observer/observer.py'), '--config', str(directory / 'observer/config.json')]}
        require(observer_job(record) is None, 'LOG_OBSERVER_UNKNOWN_JOB')
        config = {'schemaVersion': 1, 'ownerId': state['ownerId'], 'directory': str(directory), 'project': state['project'],
                  'pgContainerId': pg['Id'], 'pgImageId': pg['Image'],
                  'volumeIdentity': state['volumes'][state['project'] + '-postgres-data'],
                  'dockerBinary': str(binary), 'dockerContext': context, 'port': state['port']}
        script = (ROOT / 'tools/runtime/log-volume-observer.py').read_bytes()
        config_bytes = (json.dumps(config, sort_keys=True) + '\n').encode()
        plist_bytes = plistlib.dumps({'Label': label, 'ProgramArguments': record['arguments'], 'RunAtLoad': True,
            'KeepAlive': True, 'ThrottleInterval': 5, 'ProcessType': 'Background',
            'WorkingDirectory': str(directory / 'observer'), 'StandardOutPath': '/dev/null', 'StandardErrorPath': '/dev/null',
            'EnvironmentVariables': {'HOME': str(Path.home()), 'PATH': '/usr/bin:/bin:/usr/sbin:/sbin'}})
        folder = directory / 'observer'
        require(not folder.exists() and not folder.is_symlink(), 'LOG_OBSERVER_UNKNOWN_FILES')
        folder.mkdir(mode=0o700)
        for path, content in [(folder / 'observer.py', script), (folder / 'config.json', config_bytes), (plist, plist_bytes)]:
            write_private(path, content)
        record.update(scriptSha256=hashlib.sha256(script).hexdigest(), configSha256=hashlib.sha256(config_bytes).hexdigest(),
                      plistSha256=hashlib.sha256(plist_bytes).hexdigest(), stage='PREPARED')
        state['observer'] = record
        save(directory, state)  # Durable ownership before creating any native job.
    command(['/bin/launchctl', 'bootstrap', 'gui/' + str(state['uid']), record['plist']], timeout=5)
    state['observer']['stage'] = 'INSTALLED'
    save(directory, state)
    return {'result': 'PASS', 'stage': 'OBSERVER_JOB_INSTALLED', 'physicalObservation': 'PENDING', 'full457': 'OPEN'}


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
    parser.add_argument('action', choices=('start', 'status', 'update', 'renew-readers', 'enable-writer', 'renew-writer-grants', 'install-browser-grant', 'install-manifest', 'observer-install', 'observer-status'))
    parser.add_argument('--directory', type=Path, default=ROOT / 'local-data/security/dev-logging/shared')
    parser.add_argument('--project', default='dkp-dev-logging')
    parser.add_argument('--port', type=int, default=18443)
    parser.add_argument('--idp-port', type=int, default=18444)
    parser.add_argument('--target', choices=('query','gateway','both','viewer'))
    parser.add_argument('--observer-python', type=Path)
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--sha256')
    parser.add_argument('--grant', type=Path)
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
            if 'browserGrantUpdate' in state:
                require(args.action in ('status','observer-status','install-browser-grant'), 'LOG_BROWSER_GRANT_UPDATE_PENDING')
            if args.action == 'status':
                result = verify_live(state, directory)
            elif args.action == 'renew-readers':
                result = renew(state, directory)
            elif args.action == 'enable-writer':
                result = enable_writer(state,directory)
            elif args.action == 'renew-writer-grants':
                result = renew_writer_grants(state,directory)
            elif args.action == 'install-manifest':
                result = install_manifest(state,directory,args.manifest,args.sha256)
            elif args.action == 'install-browser-grant':
                result = install_browser_grant(state,directory,args.grant)
            elif args.action == 'observer-install':
                result = install_observer(state, directory, args.observer_python)
            elif args.action == 'observer-status':
                result = observer_status(state, directory)
            else:
                result = start(state, directory, update=args.action == 'update', target=args.target or 'both')
        print(json.dumps(result, sort_keys=True))
        return 0
    except Exception as failure:
        print(json.dumps({'result': 'FAIL', 'code': str(failure) if isinstance(failure, RuntimeFailure) else 'LOG_RUNTIME_UNAVAILABLE'}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
