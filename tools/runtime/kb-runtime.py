#!/usr/bin/env python3
"""Owned BP04 development lifecycle; browser success is not full BP04 acceptance."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import stat
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
LABEL = 'dkp.run-id'
PORTS = (20443, 20444, 20081, 20082)
STATE_KEYS = {'version', 'project', 'owner', 'phase', 'source', 'images', 'resources', 'overlay', 'commit'}


def security():
    spec = importlib.util.spec_from_file_location('kb_material', ROOT / 'tools/runtime/kb-security.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def networking():
    spec = importlib.util.spec_from_file_location('kb_network', ROOT / 'tools/runtime/kb-network.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def network_plan(directory, state):
    overlay = directory / 'ownership.json'
    private_file(overlay)
    if hashlib.sha256(overlay.read_bytes()).hexdigest() != state['overlay']:
        raise ValueError('Owned overlay differs')
    value = json.loads(overlay.read_text())
    if set(value) != {'services', 'networks', 'volumes'} or not isinstance(value['networks'], dict):
        raise ValueError('Invalid owned addressing overlay')
    plan = {}
    for name, config in value['networks'].items():
        if (not isinstance(config, dict) or set(config) != {'labels', 'ipam'}
                or config['labels'] != {LABEL: state['owner'], 'dkp.profile': 'dkp-bp04'}):
            raise ValueError('Invalid owned addressing metadata')
        plan[name] = config['ipam']
    networking().validate_plan(plan)
    return plan


def private_file(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077 or info.st_size > 1048576:
        raise ValueError('Unsafe private record')


def write(path, value):
    temporary = path.with_suffix('.new')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        json.dump(value, stream, sort_keys=True)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


def git(*arguments):
    return subprocess.run(['git', *arguments], cwd=ROOT, capture_output=True, check=True).stdout


def source_identity():
    commit = git('rev-parse', '--verify', 'HEAD').decode().strip()
    if not re.fullmatch(r'[a-f0-9]{40}|[a-f0-9]{64}', commit):
        raise ValueError('Invalid committed source identity')
    # Check each boundary independently: opposing index/worktree changes must not cancel.
    for arguments in [('diff', '--cached', '--quiet', 'HEAD', '--'), ('diff', '--quiet', '--')]:
        dirty = subprocess.run(['git', *arguments], cwd=ROOT,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if dirty.returncode != 0:
            raise ValueError('Committed source must have no tracked changes')
    return commit


def source_paths(commit=None):
    commit = source_identity() if commit is None else commit
    names = []
    for entry in git('ls-tree', '-rz', '--full-tree', commit).split(b'\0'):
        if not entry:
            continue
        metadata, name = entry.split(b'\t', 1)
        if metadata.split()[:2] not in ([b'100644', b'blob'], [b'100755', b'blob']):
            raise ValueError('Committed public source must contain regular files only')
        names.append(name)
    return sorted(names)


def source_digest(root=None):
    digest = hashlib.sha256()
    if root is None:
        commit = source_identity()
        values = [(name, git('show', commit + ':' + os.fsdecode(name))) for name in source_paths(commit)]
    else:
        values = []
        for path in sorted(root.rglob('*')):
            if path.is_symlink():
                raise ValueError('Public snapshot contains a symlink')
            if path.is_dir():
                continue
            if not path.is_file():
                raise ValueError('Public snapshot contains a special file')
            values.append((os.fsencode(str(path.relative_to(root))), path.read_bytes()))
    # Path ordering compares components; Git orders complete relative-name bytes.
    # Use the same canonical order for committed blobs and the copied snapshot.
    for name, data in sorted(values, key=lambda value: value[0]):
        relative = Path(os.fsdecode(name))
        if relative.is_absolute() or '..' in relative.parts or relative.parts[0] in ('local-data', '.git'):
            raise ValueError('Private material cannot be a build input')
        digest.update(name + b'\0' + data + b'\0')
    return digest.hexdigest()


def snapshot(directory, commit=None):
    identity = source_identity()
    if commit is not None and commit != identity:
        raise ValueError('Committed source changed before snapshot')
    target = directory / 'public-source'
    target.mkdir(mode=0o700)
    for name in source_paths(identity):
        relative = Path(os.fsdecode(name))
        if relative.is_absolute() or '..' in relative.parts or relative.parts[0] in ('local-data', '.git'):
            raise ValueError('Unsafe public source path')
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        destination.write_bytes(git('show', identity + ':' + os.fsdecode(name)))
        mode = git('ls-tree', identity, '--', os.fsdecode(name)).split()[0]
        destination.chmod(0o500 if mode == b'100755' else 0o400)
    return target


def execute(directory, arguments, *, input_text=None, timeout=120):
    """Capture privately, never relay Docker env/config/credential-bearing errors."""
    log = directory / 'commands.log'
    fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_APPEND | os.O_NOFOLLOW, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'ab') as output:
        try:
            result = subprocess.run(arguments, cwd=ROOT, input=input_text, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        except subprocess.TimeoutExpired:
            output.write(b'Command timeout; outcome requires owned-resource reconciliation.\n')
            raise ValueError('Runtime command timed out') from None
        output.write(result.stdout.encode() + result.stderr.encode())
        if result.returncode:
            raise ValueError('Runtime command failed; private evidence retained')
        return result.stdout.strip()


def compose(directory, state):
    overlay = directory / 'ownership.json'
    if overlay.exists():
        private_file(overlay)
        if hashlib.sha256(overlay.read_bytes()).hexdigest() != state['overlay']:
            raise ValueError('Owned overlay differs')
    elif state['phase'] != 'prepared':
        raise ValueError('Missing owned overlay')
    return ['docker', 'compose', '-p', state['project'], '--env-file', str(directory / 'security/runtime.env'),
            '-f', str(directory / 'public-source/infra/docker-compose/compose.yaml'), '-f', str(directory / 'public-source/infra/docker-compose/compose-kb.yaml'),
            '-f', str(directory / 'ownership.json')]


def inspect(directory, kind, identifier):
    return json.loads(execute(directory, ['docker', kind, 'inspect', identifier]))[0]


def inventory(directory, state):
    result = {}
    for kind in ('container', 'network', 'volume'):
        arguments = ['docker', kind, 'ls'] + (['-a'] if kind == 'container' else [])
        identifiers = execute(directory, arguments + ['-q', '--filter',
                              'label=com.docker.compose.project=' + state['project']]).split()
        items = {}
        for identifier in identifiers:
            item = inspect(directory, kind, identifier)
            labels = item.get('Config', {}).get('Labels', {}) if kind == 'container' else item.get('Labels', {})
            if (labels.get(LABEL) != state['owner'] or labels.get('dkp.profile') != 'dkp-bp04'
                    or labels.get('com.docker.compose.project') != state['project']):
                raise ValueError('Foreign or unrecorded project resource')
            if kind == 'network':
                logical = labels.get('com.docker.compose.network')
                if not isinstance(logical, str) or item.get('Name') != state['project'] + '_' + logical:
                    raise ValueError('Unexpected owned network name')
                networking().attest(network_plan(directory, state), logical, item)
            identity = item.get('Id') if kind != 'volume' else item.get('Name')
            if not identity:
                raise ValueError('Missing immutable resource identity')
            items[identity] = item
        result[kind] = items
    return result


def resource_ids(inventory_value):
    return {kind: sorted(items) for kind, items in inventory_value.items()}


def load(directory):
    directory = security().safe_path(directory)
    path = directory / 'runtime-state.json'
    private_file(path)
    state = json.loads(path.read_text())
    if (set(state) != STATE_KEYS or state['version'] != 1 or not re.fullmatch(r'dkp-bp04-[a-f0-9]{12}', state['project'])
            or not re.fullmatch(r'[a-f0-9]{32}', state['owner']) or state['phase'] not in ('prepared', 'built', 'running', 'failed', 'stopped')
            or not re.fullmatch(r'[a-f0-9]{40}|[a-f0-9]{64}', state['commit'])
            or not re.fullmatch(r'[a-f0-9]{64}', state['source']) or not isinstance(state['images'], dict)
            or set(state['resources']) != {'container', 'network', 'volume'}):
        raise ValueError('Invalid owned runtime record')
    for kind, identifiers in state['resources'].items():
        pattern = r'[a-f0-9]{64}' if kind != 'volume' else re.escape(state['project']) + r'_[a-zA-Z0-9_-]+'
        if not isinstance(identifiers, list) or len(identifiers) != len(set(identifiers)) or any(not isinstance(value, str) or not re.fullmatch(pattern, value) for value in identifiers):
            raise ValueError('Invalid owned resource selector')
    if any(not re.fullmatch(r'[a-z][a-z0-9-]*', name) or not re.fullmatch(r'sha256:[a-f0-9]{64}', value) for name, value in state['images'].items()):
        raise ValueError('Invalid image selector')
    return directory, state


def preflight_ports():
    held = []
    try:
        for port in PORTS:
            stream = socket.socket()
            held.append(stream)
            stream.bind(('127.0.0.1', port))
    finally:
        for stream in held:
            stream.close()


def check_images(directory, state, resources):
    for item in resources['container'].values():
        service = item['Config']['Labels'].get('com.docker.compose.service')
        if item.get('Image') != state['images'].get(service):
            raise ValueError('Runtime image differs from recorded source build')


def healthy(directory, state):
    resources = inventory(directory, state)
    if resource_ids(resources) != state['resources']:
        raise ValueError('Owned resource set changed')
    check_images(directory, state, resources)
    services = {}
    for item in resources['container'].values():
        service = item['Config']['Labels']['com.docker.compose.service']
        if service in services:
            raise ValueError('Duplicate service container')
        services[service] = item
        status = item['State']
        if service == 'kb-migrate':
            if status.get('Status') != 'exited' or status.get('ExitCode') != 0:
                return False
        elif status.get('Status') != 'running' or status.get('Health', {}).get('Status') != 'healthy':
            return False
    return set(services) == set(state['images'])


def database(directory, state):
    arguments = compose(directory, state) + ['exec', '-T', 'kb-postgres', 'sh', '-ec',
        'export PGPASSWORD="$POSTGRES_PASSWORD"; exec psql --no-psqlrc --set=ON_ERROR_STOP=1 '
        '--host=127.0.0.1 --username="$POSTGRES_USER" --dbname=knowledge_base --tuples-only --no-align']
    sql = (ROOT / 'infra/docker-compose/kb-database-check.sql').read_text()
    if execute(directory, arguments, input_text=sql, timeout=10) != '1':
        raise ValueError('Actual post-migration database ownership/durability check failed')


def verify(directory):
    directory, state = load(directory)
    if (state['phase'] != 'running' or source_identity() != state['commit'] or source_digest() != state['source']
            or source_digest(directory / 'public-source') != state['source']):
        raise ValueError('Runtime source or lifecycle differs from record')
    security().verify(directory / 'security')
    if not healthy(directory, state):
        raise ValueError('Runtime is not healthy')
    database(directory, state)
    return directory, state


def start(directory):
    directory = security().safe_path(directory)
    if directory.exists():
        raise ValueError('Start requires a fresh directory; no adoption')
    commit = source_identity()
    digest = source_digest()
    if source_identity() != commit:
        raise ValueError('Committed source changed before runtime preparation')
    preflight_ports()
    directory.mkdir(parents=True, mode=0o700)
    state = {'version': 1, 'project': 'dkp-bp04-' + secrets.token_hex(6), 'owner': secrets.token_hex(16),
             'phase': 'prepared', 'source': digest, 'commit': commit, 'images': {}, 'overlay': '',
             'resources': {'container': [], 'network': [], 'volume': []}}
    write(directory / 'runtime-state.json', state)
    try:
        if any(inventory(directory, state).values()):
            raise ValueError('Project already exists')
        security().prepare(directory / 'security')
        snapshot(directory, state['commit'])
        # Resolve reviewed configuration privately; preserve all service/network isolation.
        # Only owned labels and explicit conflict-checked development IPAM are overlaid.
        base = compose(directory, state)[:-2]
        config = json.loads(execute(directory, base + ['config', '--format', 'json']))
        overlay = {kind: {name: {'labels': {LABEL: state['owner'], 'dkp.profile': 'dkp-bp04'}} for name in config.get(kind, {})}
                   for kind in ('services', 'networks', 'volumes')}
        addresses = networking()
        planned = addresses.allocate(config.get('networks', {}), state['project'],
                                     addresses.docker_subnets() + addresses.host_routes())
        for name, ipam in planned.items():
            overlay['networks'][name]['ipam'] = ipam
        write(directory / 'ownership.json', overlay)
        state['overlay'] = hashlib.sha256((directory / 'ownership.json').read_bytes()).hexdigest()
        write(directory / 'runtime-state.json', state)
        execute(directory, compose(directory, state) + ['config', '--quiet'])
        if source_identity() != state['commit'] or source_digest() != state['source'] or source_digest(directory / 'public-source') != state['source']:
            raise ValueError('Source changed before Docker build')
        execute(directory, compose(directory, state) + ['build'], timeout=1800)
        if source_identity() != state['commit'] or source_digest() != state['source'] or source_digest(directory / 'public-source') != state['source']:
            raise ValueError('Source changed during build')
        for name, service in config['services'].items():
            image = service.get('image', state['project'] + '-' + name)
            state['images'][name] = inspect(directory, 'image', image)['Id']
        state['phase'] = 'built'
        write(directory / 'runtime-state.json', state)
        # Builds can be long: a different project or host route may have appeared.
        addresses.assert_available(network_plan(directory, state),
                                   addresses.docker_subnets() + addresses.host_routes())
        execute(directory, compose(directory, state) + ['up', '-d', '--no-build'], timeout=180)
        state['resources'] = resource_ids(inventory(directory, state))
        write(directory / 'runtime-state.json', state)
        deadline = time.monotonic() + 180
        while not healthy(directory, state):
            if time.monotonic() >= deadline:
                raise ValueError('Bounded health wait expired')
            time.sleep(1)
        database(directory, state)
        state['phase'] = 'running'
        write(directory / 'runtime-state.json', state)
    except Exception:
        # Preserve failed resources/data. Capture ONLY resources carrying this fresh run label.
        state['phase'] = 'failed'
        state['resources'] = resource_ids(inventory(directory, state))
        write(directory / 'runtime-state.json', state)
        raise


def stop(directory):
    directory, state = load(directory)
    resources = inventory(directory, state)
    if resource_ids(resources) != state['resources']:
        raise ValueError('Unknown resource change; refuse cleanup')
    for identity in state['resources']['container']:
        execute(directory, ['docker', 'container', 'rm', '-f', identity])
    for identity in state['resources']['network']:
        execute(directory, ['docker', 'network', 'rm', identity])
    # Database volumes and private evidence deliberately remain owned and retained.
    state['resources']['container'] = []
    state['resources']['network'] = []
    state['phase'] = 'stopped'
    write(directory / 'runtime-state.json', state)


def browser_record(directory, state):
    resources = inventory(directory, state)
    containers = {item['Config']['Labels']['com.docker.compose.service']: item for item in resources['container'].values()}
    network_name = state['project'] + '_default'
    networks = [item for item in resources['network'].values() if item['Name'] == network_name]
    if len(networks) != 1:
        raise ValueError('Missing exact browser network')
    value = {'schemaVersion': 1, 'profile': 'dkp-bp04', 'project': state['project'], 'runId': state['owner'],
             'securityDirectory': str(directory / 'security/identity'), 'runtimeEnvironmentFile': str(directory / 'security/runtime.env'),
             'portalId': containers['admin-portal']['Id'], 'keycloakId': containers['keycloak']['Id'],
             'networkName': network_name, 'networkId': networks[0]['Id'],
             'portalImageId': containers['admin-portal']['Image'], 'keycloakImageId': containers['keycloak']['Image']}
    write(directory / 'browser-state.json', value)


def e2e(directory):
    directory, state = verify(directory)
    browser_record(directory, state)
    execute(directory, ['node', 'tools/runtime/identity-e2e.mjs', '--kb-runtime', '--kb-runtime-state',
                        str(directory / 'browser-state.json'), '--security-directory', str(directory / 'security/identity'),
                        '--runtime-env-file', str(directory / 'security/runtime.env')], timeout=900)
    verify(directory)
    # Deliberately no whole-BP04 acceptance/READY marker: authority/races/restart are separate gates.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('start', 'verify', 'e2e', 'stop'))
    parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        globals()[args.command](args.directory)
    except Exception:
        print('KB lifecycle failed closed; private evidence and data retained.')
        return 1
    print('Owned KB lifecycle command completed; full acceptance remains separate.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
