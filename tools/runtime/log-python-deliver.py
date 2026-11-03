"""Producer-only delivery of original bounded Python build observations."""
import argparse
import datetime
import hashlib
import importlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import stat
import sys
import time
import zipfile

MODULES = ('__init__', 'https', 'producer', 'spool', 'wire')
MANIFEST = 'dkp_logging/manifest.json'
CONFIG = frozenset(('schemaVersion', 'manifestSha256', 'runId', 'instanceId', 'endpoint',
    'connectAddress', 'producerToken', 'caFile', 'spoolDirectory'))
OBSERVATIONS = frozenset(('schemaVersion', 'component', 'buildId', 'runId', 'instanceId',
    'correlationId', 'startedEventId', 'finishedEventId', 'startedAt', 'finishedAt', 'durationMs', 'filesCompiled'))
MANIFEST_FIELDS = frozenset(('schemaVersion', 'buildId', 'component', 'gitSha', 'sourceFingerprint',
    'compiledAt', 'toolchainFingerprint', 'dependencyFingerprint', 'vocabularySha256', 'vocabulary',
    'sourceFingerprintAlgorithm', 'dirty'))


def require(value):
    if not value:
        raise ValueError('LOG_PYTHON_DELIVERY_INVALID')


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def private(path, limit):
    path = Path(path)
    require(path.is_absolute() and path.resolve(strict=True) == path)
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and stat.S_IMODE(before.st_mode) == 0o600
        and before.st_uid == os.getuid() and before.st_size <= limit)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as source:
        data = source.read(limit + 1)
        after = os.fstat(source.fileno())
    require(len(data) <= limit and (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        == (after.st_dev, after.st_ino, len(data), after.st_mtime_ns))
    return data


def digest(value):
    require(type(value) is str and re.fullmatch(r'[0-9a-f]{64}', value))
    return value


def load_artifact(artifact, artifact_sha256, manifest_sha256):
    raw = private(artifact, 2 * 1024 * 1024)
    require(hashlib.sha256(raw).hexdigest() == digest(artifact_sha256))
    expected = {MANIFEST} | {'dkp_logging/' + name + '.pyc' for name in MODULES}
    with zipfile.ZipFile(artifact) as archive:
        entries = archive.infolist()
        require(len(entries) == len(expected) and {entry.filename for entry in entries} == expected)
        for entry in entries:
            require(entry.compress_type == zipfile.ZIP_STORED and not entry.flag_bits & 1)
            if entry.filename == MANIFEST:
                require(0 < entry.file_size <= 1024 * 1024)
            else:
                require(16 < entry.file_size <= 262144)
                code = archive.read(entry)
                require(code[:4] == importlib.util.MAGIC_NUMBER and code[4:8] == b'\x03\x00\x00\x00')
        manifest = archive.read(MANIFEST)
    require(hashlib.sha256(manifest).hexdigest() == digest(manifest_sha256))
    value = json.loads(manifest, object_pairs_hook=unique)
    require(type(value) is dict and value.keys() == MANIFEST_FIELDS
        and type(value['schemaVersion']) is int and value['schemaVersion'] == 1
        and value['component'] == 'python-build' and value['sourceFingerprintAlgorithm'] == 'dkp-public-inputs-v1')
    return value


def deliver(artifact, artifact_sha256, manifest_sha256, observations, observations_sha256, configuration, wait_seconds=10):
    require(platform.python_version() == '3.14.10')
    require(type(wait_seconds) in (int, float) and 0 <= wait_seconds <= 15)
    artifact = Path(artifact)
    manifest = load_artifact(artifact, artifact_sha256, manifest_sha256)
    raw = private(observations, 8192)
    require(hashlib.sha256(raw).hexdigest() == digest(observations_sha256))
    observed = json.loads(raw, object_pairs_hook=unique)
    config = json.loads(private(configuration, 8192), object_pairs_hook=unique)
    require(type(observed) is dict and observed.keys() == OBSERVATIONS
        and type(observed['schemaVersion']) is int and observed['schemaVersion'] == 1)
    require(type(config) is dict and config.keys() == CONFIG
        and type(config['schemaVersion']) is int and config['schemaVersion'] == 1)
    require(config['manifestSha256'] == manifest_sha256 and observed['buildId'] == manifest['buildId']
        and observed['component'] == manifest['component'] and observed['runId'] == config['runId']
        and observed['instanceId'] == config['instanceId'])
    require(type(observed['filesCompiled']) is int and observed['filesCompiled'] == 5
        and type(observed['durationMs']) is int and 0 <= observed['durationMs'] <= 9007199254740991)
    require(type(config['producerToken']) is str and re.fullmatch(r'[A-Za-z0-9_-]{32,512}', config['producerToken']))
    ca = Path(config['caFile'])
    require(ca.is_absolute() and ca.resolve(strict=True) == ca and ca.is_file()
        and not ca.stat().st_mode & 0o022 and ca.stat().st_size <= 65536)
    # Refuse existing SDK modules rather than use a checkout or another installation.
    require(not any(name == 'dkp_logging' or name.startswith('dkp_logging.') for name in sys.modules))
    sys.path.insert(0, str(artifact))
    wire = importlib.import_module('dkp_logging.wire')
    producer_module = importlib.import_module('dkp_logging.producer')
    https = importlib.import_module('dkp_logging.https')
    spool_module = importlib.import_module('dkp_logging.spool')
    require(all(module.__file__.startswith(str(artifact) + '/') for module in (wire, producer_module, https, spool_module)))
    identity = {key:manifest[key] for key in ('buildId', 'component', 'gitSha', 'sourceFingerprint')}
    identity.update(runId=config['runId'], instanceId=config['instanceId'])
    vocabulary = {key:manifest['vocabulary'][key] for key in ('events', 'phases')}
    codec = wire.Wire(identity, vocabulary)
    require(observed['startedEventId'] != observed['finishedEventId'])
    events = []
    for prefix, event_name, outcome, duration, cleanup in (
            ('started', 'build.started', 'STARTED', 0, False),
            ('finished', 'build.succeeded', 'SUCCEEDED', observed['durationMs'], True)):
        event = dict(eventId=observed[prefix + 'EventId'], timestamp=observed[prefix + 'At'],
            severity='DEBUG', eventName=event_name, correlationId=observed['correlationId'],
            phase='BUILD', outcome=outcome, durationMs=duration, attempt=1,
            count=observed['filesCompiled'], cleanupSettled=cleanup)
        codec.prepare(event)
        events.append(event)
    require(datetime.datetime.fromisoformat(observed['finishedAt'].replace('Z', '+00:00'))
        >= datetime.datetime.fromisoformat(observed['startedAt'].replace('Z', '+00:00')))

    def transport():
        return https.HttpsTransport(config['endpoint'], config['producerToken'], str(ca), config['connectAddress'])

    outbound = transport()
    try:
        spool = spool_module.FileLogSpool(Path(config['spoolDirectory']), identity, outbound.credential_fingerprint())
        producer = producer_module.LogProducer(identity, vocabulary, outbound, spool)
    except Exception:
        outbound.close()
        raise
    try:
        admissions = [producer.emit(event) for event in events]
        deadline = time.monotonic() + wait_seconds
        while producer.status()['pending'] and time.monotonic() < deadline:
            time.sleep(min(.05, max(0, deadline - time.monotonic())))
    finally:
        producer.close()
        stopped = producer.await_closed(5)
    status = producer.status()
    ids = [event['eventId'] for event in events]
    states = dict.fromkeys(ids, 'UNKNOWN')
    # Final confirmation names the exact original IDs, never aggregate counters.
    if stopped:
        lookup = transport()
        try:
            code, response = lookup.post('receipts', wire.encode({'eventIds':ids}))
            if code == 200:
                states = wire.receipt_states(response, ids)
        except Exception:
            pass  # Report UNKNOWN; no raw exception text or recursive logging.
        finally:
            lookup.close()
    return {'result':'COMMITTED' if all(state == 'COMMITTED' for state in states.values()) else 'PENDING',
        'buildId':identity['buildId'], 'runId':identity['runId'], 'eventStates':states,
        'admissions':admissions, 'status':status, 'workerStopped':stopped}


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--artifact-sha256', required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--observations', type=Path, required=True)
    parser.add_argument('--observations-sha256', required=True)
    parser.add_argument('--configuration', type=Path, required=True)
    parser.add_argument('--wait-seconds', type=float, default=10)
    args = parser.parse_args()
    try:
        result = deliver(args.artifact, args.artifact_sha256, args.manifest_sha256,
            args.observations, args.observations_sha256, args.configuration, args.wait_seconds)
    except Exception:
        print('{"result":"FAIL","code":"LOG_PYTHON_DELIVERY_INVALID"}')
        return 1
    print(json.dumps(result, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
