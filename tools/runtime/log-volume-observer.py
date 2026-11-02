#!/usr/bin/env python3
"""Standalone owned physical-volume observer. No checkout imports or logging recursion."""
from contextlib import contextmanager
import datetime as dt
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import signal
import ssl
import stat
import subprocess
import sys
import tempfile
import time


class ObserverFailure(Exception):
    """Only fixed safe codes leave a cycle; never propagate Docker/TLS/token details."""


def require(value, code='OBSERVER_INVALID'):
    if not value:
        raise ObserverFailure(code)


def private(path, maximum):
    require(path.is_absolute() and path.resolve(strict=True) == path)
    with path.open('rb') as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid()
                and not before.st_mode & 0o077 and 0 < before.st_size <= maximum)
        value = stream.read(maximum + 1)
        after = os.fstat(stream.fileno())
    require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_mode)
            == (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_mode)
            and len(value) == before.st_size)
    return value


def configuration(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result)
            result[key] = value
        return result
    config = json.loads(private(path, 8192).decode('utf-8'), object_pairs_hook=unique)
    require(isinstance(config, dict) and set(config) == {
        'schemaVersion', 'ownerId', 'directory', 'project', 'pgContainerId', 'pgImageId',
        'volumeIdentity', 'dockerBinary', 'dockerContext', 'port'})
    require(type(config['schemaVersion']) is int and config['schemaVersion'] == 1)
    require(all(isinstance(config[key], str) for key in set(config) - {'schemaVersion', 'port'}))
    require(re.fullmatch('[0-9a-f]{32}', config['ownerId']))
    require(re.fullmatch('dkp-dev-logging(?:-acceptance-[0-9a-f]{12})?', config['project']))
    require(re.fullmatch('[0-9a-f]{64}', config['pgContainerId']))
    require(re.fullmatch('sha256:[0-9a-f]{64}', config['pgImageId']))
    require(re.fullmatch('[0-9a-f]{64}', config['volumeIdentity']))
    require(re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', config['dockerContext']))
    require(type(config['port']) is int and 1 <= config['port'] <= 65535)
    directory = Path(config['directory'])
    require(directory.is_absolute() and directory.resolve(strict=True) == directory)
    info = directory.stat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and not info.st_mode & 0o077)
    require(path == directory / 'observer/config.json')
    binary = Path(config['dockerBinary'])
    require(binary.is_absolute() and binary.resolve(strict=True) == binary and binary.is_file()
            and os.access(binary, os.X_OK) and not binary.stat().st_mode & 0o022)
    token = private(directory / 'operator/observer-token', 512).decode('ascii')
    require(re.fullmatch('[A-Za-z0-9_-]{43,512}', token))
    private(directory / 'gateway-pki/ca.crt', 32768)
    return config


def docker(config, deadline, *args):
    remaining = deadline - time.monotonic()
    require(remaining > 0, 'MEASUREMENT_UNAVAILABLE')
    try:
        result = subprocess.run([config['dockerBinary'], '--context', config['dockerContext'], *args],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=remaining,
            env={'HOME': str(Path.home()), 'PATH': '/usr/bin:/bin:/usr/sbin:/sbin'})
    except (OSError, subprocess.TimeoutExpired):
        raise ObserverFailure('MEASUREMENT_UNAVAILABLE') from None
    require(result.returncode == 0 and len(result.stdout) <= 131072 and time.monotonic() <= deadline,
            'MEASUREMENT_UNAVAILABLE')
    return result.stdout


def owned(item, config):
    labels = item.get('Config', {}).get('Labels', item.get('Labels', {})) or {}
    require(all(labels.get(key) == value for key, value in {
        'dkp.owner': 'shared-dev-logging', 'dkp.issue': '457',
        'dkp.logging.owner-id': config['ownerId']}.items()), 'MEASUREMENT_UNAVAILABLE')


def measure(config):
    deadline = time.monotonic() + 2
    endpoint = json.loads(docker(config, deadline, 'context', 'inspect', config['dockerContext']))
    require(isinstance(endpoint, list) and len(endpoint) == 1
            and endpoint[0]['Endpoints']['docker']['Host'].startswith('unix:///'), 'MEASUREMENT_UNAVAILABLE')
    items = json.loads(docker(config, deadline, 'container', 'inspect', config['pgContainerId']))
    require(isinstance(items, list) and len(items) == 1, 'MEASUREMENT_UNAVAILABLE')
    pg = items[0]
    owned(pg, config)
    require(pg['Id'] == config['pgContainerId'] and pg['Image'] == config['pgImageId']
            and pg['State']['Running'], 'MEASUREMENT_UNAVAILABLE')
    volume_name = config['project'] + '-postgres-data'
    require(any(mount.get('Type') == 'volume' and mount.get('Name') == volume_name
                and mount.get('Destination') == '/var/lib/postgresql' and mount.get('RW') is True
                for mount in pg['Mounts']), 'MEASUREMENT_UNAVAILABLE')
    volumes = json.loads(docker(config, deadline, 'volume', 'inspect', volume_name))
    require(isinstance(volumes, list) and len(volumes) == 1, 'MEASUREMENT_UNAVAILABLE')
    volume = volumes[0]
    owned(volume, config)
    identity = hashlib.sha256(json.dumps({key: volume.get(key) for key in
        ('Name', 'CreatedAt', 'Mountpoint', 'Driver', 'Options', 'Scope', 'Labels')}, sort_keys=True).encode()).hexdigest()
    require(volume['Name'] == volume_name and identity == config['volumeIdentity'], 'MEASUREMENT_UNAVAILABLE')
    lines = docker(config, deadline, 'exec', config['pgContainerId'], 'df', '-B1', '--output=size,avail',
                   '/var/lib/postgresql').decode('ascii').splitlines()
    require(len(lines) == 2 and re.fullmatch(r'\s*[0-9]+\s+[0-9]+\s*', lines[1]), 'MEASUREMENT_UNAVAILABLE')
    total, free = map(int, lines[1].split())
    require(0 <= free <= total <= 9007199254740991 and total > 0, 'MEASUREMENT_UNAVAILABLE')
    return {'volumeIdentity': config['volumeIdentity'], 'freeBytes': free, 'totalBytes': total}


@contextmanager
def http_budget():
    # This standalone main-thread process owns SIGALRM. The timer covers the whole TLS/HTTP exchange.
    require(signal.getitimer(signal.ITIMER_REAL) == (0.0, 0.0), 'HTTP_UNAVAILABLE')
    def expired(*_):
        raise ObserverFailure('HTTP_UNAVAILABLE')
    previous = signal.signal(signal.SIGALRM, expired)
    try:
        signal.setitimer(signal.ITIMER_REAL, 1)
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def forward(config, observation):
    directory = Path(config['directory'])
    with http_budget():
        token = private(directory / 'operator/observer-token', 512).decode('ascii')
        require(re.fullmatch('[A-Za-z0-9_-]{43,512}', token), 'HTTP_UNAVAILABLE')
        context = ssl.create_default_context(cafile=str(directory / 'gateway-pki/ca.crt'))
        connection = http.client.HTTPSConnection('localhost', config['port'], context=context, timeout=1)
        try:
            connection.request('POST', '/api/v1/dev-logs/storage-observation',
                body=json.dumps(observation, separators=(',', ':')).encode(),
                headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
            response = connection.getresponse()
            require(response.status == 204 and len(response.read(1025)) == 0, 'HTTP_UNAVAILABLE')
        finally:
            connection.close()


def health(config, record):
    folder = Path(config['directory']) / 'observer'
    info = folder.lstat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and not info.st_mode & 0o077)
    target = folder / 'health.json'
    if target.exists() or target.is_symlink():
        private(target, 4096)
    with tempfile.NamedTemporaryFile(dir=folder, delete=False) as stream:
        temporary = Path(stream.name)
        os.chmod(temporary, 0o600)
        stream.write((json.dumps(record, sort_keys=True) + '\n').encode())
    try:
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def cycle(config, sequence):
    record = {'schemaVersion': 1, 'ownerId': config['ownerId'], 'sequence': sequence,
              'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'result': 'FAILED', 'code': 'MEASUREMENT_UNAVAILABLE'}
    try:
        observation = measure(config)
        record.update(observation)
        record['code'] = 'HTTP_UNAVAILABLE'
        forward(config, observation)
        enough = observation['freeBytes'] >= 2 * 1024 ** 3 and observation['freeBytes'] * 10 >= observation['totalBytes']
        record.update(result='ACCEPTED' if enough else 'DEGRADED', code='OBSERVATION_ACCEPTED' if enough else 'LOW_SPACE')
    except Exception:
        pass  # Never retain raw exceptions, token, Docker output, SQL or uploaded content.
    health(config, record)
    return record


def run(config):
    sequence = 0
    while True:
        started = time.monotonic()
        sequence += 1
        cycle(config, sequence)
        time.sleep(max(0, started + 2 - time.monotonic()))


def main(args):
    try:
        require(len(args) == 2 and args[0] == '--config')
        run(configuration(Path(args[1])))
    except Exception:
        print('OBSERVER_UNAVAILABLE', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
