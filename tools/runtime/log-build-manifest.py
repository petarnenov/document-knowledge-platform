"""Trusted build adapter for original v1 logging manifests, not runtime authority.

Call prepare before compilation, finalize before embedding, and seal after
packaging. Actual compiler/runner records are supplied by the build adapter;
this module does not attest a compiler invocation, install manifests or log.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import uuid

ALGORITHM = 'dkp-public-inputs-v1'
MAX_MANIFEST = 1024 * 1024
OUTPUT = PurePosixPath('build/generated/dkp-logging')
EVENTS = frozenset(('process.started','process.stopped','operation.started','operation.succeeded',
    'operation.failed','operation.cancelled','dependency.started','dependency.succeeded','dependency.failed',
    'build.started','build.succeeded','build.failed','ui.route.changed','ui.identity.changed',
    'producer.delivery.degraded','producer.events.lost'))
PREPARED = frozenset(('schemaVersion','buildId','component','gitSha','sourceFingerprint','dirty',
    'preparedAt','sourceFingerprintAlgorithm','inputs'))


def require(condition):
    if not condition: raise ValueError('LOG_BUILD_MANIFEST_INVALID')


def canonical(value):
    # Closed schemas below use integer/boolean values and ASCII property names.
    # Reject floats, arbitrary keys and invalid Unicode rather than approximate JCS.
    def check(item, depth=0):
        require(depth <= 16)
        if type(item) is dict:
            require(all(type(key) is str and key.isascii() for key in item))
            for value in item.values(): check(value, depth+1)
        elif type(item) is list:
            for value in item: check(value, depth+1)
        elif type(item) is str: item.encode('utf-8', errors='strict')
        else: require(item is None or type(item) is bool or (type(item) is int and abs(item) <= 9007199254740991))
    check(value)
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def text(value, pattern, maximum):
    require(type(value) is str and 0 < len(value) <= maximum and re.fullmatch(pattern, value) is not None)
    return value


def identifier(value):
    text(value, r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', 36)
    require(uuid.UUID(value).int != 0)
    return value


def path_name(raw):
    value = raw.decode('utf-8', errors='strict') if type(raw) is bytes else raw
    require(type(value) is str and value and len(value.encode('utf-8')) <= 4096)
    parts = value.split('/')
    require(all(part not in ('', '.', '..') for part in parts))
    require('\\' not in value and not any(ord(char) < 32 or ord(char) == 127 for char in value))
    require(not any(part in ('.git', 'node_modules', 'local-data') for part in parts))
    return value


def generated(value):
    parts = PurePosixPath(value).parts
    return (parts[0] in ('build', 'dist') or
        (len(parts) >= 2 and parts[0] == 'architecture-tests' and parts[1] == 'build') or
        (len(parts) >= 3 and parts[0] in ('apps', 'modules', 'workers', 'generated-clients') and parts[2] in ('build', 'dist')) or
        (len(parts) >= 4 and parts[0] == 'services' and parts[3] in ('build', 'dist')))


def git(root, *args):
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True, timeout=30)
    require(result.returncode == 0)
    return result.stdout


def regular(root, relative):
    path = root
    for part in PurePosixPath(relative).parts:
        path = path / part
        require(not path.is_symlink())
    require(path.is_file())
    return path


def file_hash(path):
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode))
    sha = hashlib.sha256()
    blob = hashlib.sha1(usedforsecurity=False)
    blob.update(b'blob ' + str(before.st_size).encode('ascii') + b'\0')
    size = 0
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        opened = os.fstat(stream.fileno())
        require((before.st_dev, before.st_ino) == (opened.st_dev, opened.st_ino))
        while chunk := stream.read(65536):
            sha.update(chunk); blob.update(chunk); size += len(chunk)
        after = os.fstat(stream.fileno())
    end = path.lstat()
    identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mode, s.st_mtime_ns, s.st_ctime_ns)
    require(identity(before) == identity(after) == identity(end) and size == before.st_size)
    return sha.hexdigest(), blob.hexdigest(), size, bool(before.st_mode & 0o111)


def inputs(root):
    root = Path(root)
    require(root.is_absolute() and root.resolve(strict=True) == root)
    require(git(root, 'rev-parse', '--show-toplevel').decode().strip() == str(root))
    head = text(git(root, 'rev-parse', 'HEAD').decode().strip(), '[0-9a-f]{40}', 40)
    for value in git(root, 'ls-files', '--others', '--exclude-standard', '-z').split(b'\0'):
        if value: require(generated(path_name(value)))
    indexed = {}
    for value in git(root, 'ls-files', '--stage', '-z').split(b'\0'):
        if not value: continue
        metadata, raw = value.split(b'\t', 1)
        mode, blob, stage = metadata.decode('ascii').split(' ')
        name = path_name(raw)
        require(stage == '0' and mode in ('100644', '100755') and name not in indexed and not generated(name))
        indexed[name] = (mode, text(blob, '[0-9a-f]{40}', 40))
    require(indexed)
    committed = {}
    for value in git(root, 'ls-tree', '--full-tree', '-r', '-z', head).split(b'\0'):
        if not value: continue
        metadata, raw = value.split(b'\t', 1)
        mode, kind, blob = metadata.decode('ascii').split(' ')
        require(kind == 'blob')
        committed[raw.decode('utf-8', errors='strict')] = (mode, blob)
    rows = []; working = {}
    for name in sorted(indexed, key=lambda value: value.encode('utf-8')):
        sha, blob, size, executable = file_hash(regular(root, name))
        rows.append({'path':name, 'sha256':sha, 'bytes':size, 'executable':executable})
        working[name] = ('100755' if executable else '100644', blob)
    require(git(root, 'rev-parse', 'HEAD').decode().strip() == head)
    require(git(root, 'ls-files', '--stage', '-z') == b''.join(
        (mode+' '+blob+' 0\t').encode('ascii')+name.encode('utf-8')+b'\0'
        for name, (mode, blob) in indexed.items()))
    return {'gitSha':head, 'dirty':indexed != committed or working != committed,
        'sourceFingerprint':digest(rows), 'inputs':rows}


def timestamp(value):
    text(value, r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z', 32)
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')


def prepare(root, component):
    component = text(component, '[a-z][a-z0-9.-]*', 96)
    build_id = str(uuid.uuid4())  # Mint BEFORE enumerating inputs or starting compilation.
    prepared_at = now()
    return {'schemaVersion':1, 'buildId':build_id, 'component':component, 'preparedAt':prepared_at,
        'sourceFingerprintAlgorithm':ALGORITHM, **inputs(root)}


def unchanged(root, prepared):
    require(type(prepared) is dict and prepared.keys() == PREPARED and type(prepared['schemaVersion']) is int and prepared['schemaVersion'] == 1)
    identifier(prepared['buildId']); text(prepared['component'], '[a-z][a-z0-9.-]*', 96)
    require(prepared['sourceFingerprintAlgorithm'] == ALGORITHM and type(prepared['dirty']) is bool)
    timestamp(prepared['preparedAt'])
    current = inputs(root)
    require(all(prepared[key] == value for key, value in current.items()))


def catalog(values, minimum, maximum, pattern, length):
    require(type(values) is list and minimum <= len(values) <= maximum)
    for value in values: text(value, pattern, length)
    require(len(set(values)) == len(values))
    return values


def dependencies(values):
    require(type(values) is list and len(values) <= 256)
    names = set()
    for value in values:
        require(type(value) is dict and {'name','version','vendorBuildProvenance'} <= value.keys()
            and value.keys() <= {'name','version','vendorBuildProvenance','imageDigest'})
        name = text(value['name'], '[A-Za-z][A-Za-z0-9_.-]*', 128)
        require(name not in names); names.add(name)
        text(value['version'], '[A-Za-z0-9][A-Za-z0-9_.+:~-]*', 128)
        require(value['vendorBuildProvenance'] in ('VERIFIED','UNKNOWN'))
        if 'imageDigest' in value: text(value['imageDigest'], 'sha256:[0-9a-f]{64}', 71)
    return values


def vocabulary(value):
    require(type(value) is dict and value.keys() == {'events','phases','errorCodes','dependencies','exceptionClasses','frames'})
    require(EVENTS <= set(catalog(value['events'], 16, 1024, '[a-z][a-z0-9_.-]*', 128)))
    catalog(value['phases'], 0, 1024, '[A-Za-z][A-Za-z0-9_.-]*', 128)
    catalog(value['errorCodes'], 0, 1024, '[A-Z0-9_]+', 96)
    catalog(value['exceptionClasses'], 0, 8192, '[A-Za-z0-9_.$]+', 256)
    dependencies(value['dependencies'])
    frames = value['frames']; require(type(frames) is list and len(frames) <= 32768)
    seen = set()
    for frame in frames:
        require(type(frame) is dict and frame.keys() == {'symbol','source'})
        text(frame['symbol'], '[A-Za-z0-9_.$<>-]+', 256)
        source = text(frame['source'], '[A-Za-z0-9_./$-]+', 256)
        require(not source.startswith('/') and '..' not in source.split('/'))
        pair = (frame['symbol'], source); require(pair not in seen); seen.add(pair)
    return value


def finalize(root, prepared, reviewed_vocabulary, toolchain_record, dependency_record, compiled_at=None):
    unchanged(root, prepared)
    vocabulary(reviewed_vocabulary)
    dependencies(toolchain_record); require(toolchain_record)
    dependencies(dependency_record)
    require(all(value in dependency_record for value in reviewed_vocabulary['dependencies']))
    compiled_at = now() if compiled_at is None else compiled_at
    require(timestamp(compiled_at) >= timestamp(prepared['preparedAt']))
    manifest = {key:prepared[key] for key in ('buildId','component','gitSha','sourceFingerprint','dirty','sourceFingerprintAlgorithm')}
    manifest.update(schemaVersion=1, compiledAt=compiled_at, toolchainFingerprint=digest(toolchain_record),
        dependencyFingerprint=digest(dependency_record), vocabularySha256=digest(reviewed_vocabulary), vocabulary=reviewed_vocabulary)
    raw = canonical(manifest); require(len(raw) <= MAX_MANIFEST)
    # Return an independent copy: later caller mutation must not alter sealed bytes.
    return {'schemaVersion':1, 'preparationSha256':digest(prepared), 'manifestSha256':hashlib.sha256(raw).hexdigest(),
        'manifest':json.loads(raw), 'toolchainRecord':json.loads(canonical(toolchain_record)),
        'dependencyRecord':json.loads(canonical(dependency_record))}


def seal(root, prepared, finalized, artifact):
    unchanged(root, prepared)
    require(type(finalized) is dict and finalized.keys() == {'schemaVersion','preparationSha256','manifestSha256','manifest','toolchainRecord','dependencyRecord'})
    require(type(finalized['schemaVersion']) is int and finalized['schemaVersion'] == 1 and finalized['preparationSha256'] == digest(prepared))
    manifest = finalized['manifest']
    require(type(manifest) is dict and manifest.keys() == {'schemaVersion','buildId','component','gitSha','sourceFingerprint',
        'compiledAt','toolchainFingerprint','dependencyFingerprint','vocabularySha256','vocabulary','sourceFingerprintAlgorithm','dirty'})
    require(type(manifest['schemaVersion']) is int and manifest['schemaVersion'] == 1)
    require(timestamp(manifest['compiledAt']) >= timestamp(prepared['preparedAt']))
    dependencies(finalized['toolchainRecord']); require(finalized['toolchainRecord'])
    dependencies(finalized['dependencyRecord'])
    require(digest(finalized['toolchainRecord']) == manifest['toolchainFingerprint']
        and digest(finalized['dependencyRecord']) == manifest['dependencyFingerprint'])
    require(all(value in finalized['dependencyRecord'] for value in vocabulary(manifest['vocabulary'])['dependencies']))
    raw = canonical(manifest)
    require(0 < len(raw) <= MAX_MANIFEST and hashlib.sha256(raw).hexdigest() == finalized['manifestSha256'])
    require(all(manifest[key] == prepared[key] for key in ('buildId','component','gitSha','sourceFingerprint','dirty','sourceFingerprintAlgorithm')))
    vocabulary(manifest['vocabulary']); require(digest(manifest['vocabulary']) == manifest['vocabularySha256'])
    artifact = Path(artifact); root = Path(root)
    require(artifact.is_absolute() and artifact.is_relative_to(root))
    relative = path_name(artifact.relative_to(root).as_posix()); require(generated(relative))
    sha, _, size, _ = file_hash(regular(root, relative))
    unchanged(root, prepared)
    return {'schemaVersion':1, 'buildId':prepared['buildId'], 'component':prepared['component'], 'preparationSha256':digest(prepared),
        'manifestSha256':finalized['manifestSha256'], 'artifact':{'path':relative,'sha256':sha,'bytes':size}}


def write_output(root, component, build_id, name, value):
    """Only explicit ignored build outputs; never edits compilation inputs."""
    root = Path(root); require(root.resolve(strict=True) == root)
    text(component, '[a-z][a-z0-9.-]*', 96)
    require(name in ('prepared.json','manifest.json','finalized.json','receipt.json','distribution.json'))
    identifier(build_id)
    require(type(value) is dict)
    identity = value.get('manifest', {}) if name == 'finalized.json' else value
    require(type(identity) is dict and identity.get('buildId') == build_id and identity.get('component') == component)
    relative = (OUTPUT / component / build_id / name).as_posix()
    require(git(root, 'ls-files', '-z', '--', str(OUTPUT)) == b'')
    result = subprocess.run(['git','-C',str(root),'check-ignore','--quiet','--no-index','--',relative], capture_output=True, timeout=30)
    require(result.returncode == 0)
    directory = root
    for part in PurePosixPath(relative).parts[:-1]:
        directory /= part
        require(not directory.is_symlink())
        directory.mkdir(mode=0o700, exist_ok=True)
        require(directory.is_dir())
    path = directory / name
    require(not path.is_symlink())
    raw = canonical(value)
    require(len(raw) <= (32*1024*1024 if name == 'prepared.json' else 2*MAX_MANIFEST if name == 'finalized.json' else MAX_MANIFEST))
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream: stream.write(raw)
    return path
