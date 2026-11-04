"""Closed original provider distribution verification; no module execution/authority."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import platform
import re
import stat
import struct
import marshal
import zipfile

HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


m = load('tool_verify_manifest', 'log-build-manifest.py')
c = load('tool_verify_compiler', 'log-tool-compile.py')
COMPONENT = 'runtime-tools'
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_SOURCE_TOTAL = 32 * 1024 * 1024
CHECKPOINTS = ('LAUNCH', 'OPERATOR', 'DIRECTORY', 'RUNTIME', 'CAPACITY', 'SOURCE_BINDING',
    'PRIVATE_CONTROL', 'SCANNER', 'INSPECTOR', 'DM_BOUNDARY', 'FINAL_VERIFY', 'RESULT')
DM_PHASES = ('PREPARED', 'OLD_BASELINE', 'BEGIN', 'VERIFYING', 'ABORT', 'CRASH', 'RECOVERY',
    'OLD_FACTS_AFTER_RECOVERY', 'SECOND_COMPLETE', 'OLD_FACTS_AFTER_COMPLETE',
    'FINAL_SNAPSHOT', 'FINISH', 'BROWSER_EXIT')
CLASSES = ('dkp_provider_jobs.AcceptanceFailure', 'builtins.ValueError', 'builtins.OSError',
    'builtins.RuntimeError', 'builtins.KeyboardInterrupt', 'builtins.SystemExit')


def require(value):
    if not value:
        raise ValueError('LOG_TOOL_DISTRIBUTION_INVALID')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path, maximum, *, owner=None):
    path = Path(path)
    require(path.is_absolute() and path.resolve(strict=True) == path)
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_size <= maximum
        and (owner is None or before.st_uid == owner))
    identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mode, s.st_mtime_ns, s.st_ctime_ns)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        require(identity(os.fstat(stream.fileno())) == identity(before))
        data = stream.read(maximum + 1)
        require(identity(os.fstat(stream.fileno())) == identity(before))
    require(identity(path.lstat()) == identity(before) and len(data) == before.st_size)
    return data


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def record(data, maximum):
    require(type(data) is bytes and 0 < len(data) <= maximum)
    depth, quoted, escaped = 0, False, False
    for byte in data:
        if quoted:
            if escaped:escaped = False
            elif byte == 92:escaped = True
            elif byte == 34:quoted = False
        elif byte == 34:quoted = True
        elif byte in (91, 123):
            depth += 1
            require(depth <= 16)
        elif byte in (93, 125):depth -= 1
    def invalid(_):raise ValueError('LOG_TOOL_DISTRIBUTION_INVALID')
    value = json.loads(data.decode('utf-8', errors='strict'), object_pairs_hook=unique,
        parse_constant=invalid, parse_float=invalid)
    require(type(value) is dict and m.canonical(value) == data)
    return value


def inputs(prepared):
    require(type(prepared) is dict and prepared.keys() == m.PREPARED
        and type(prepared['schemaVersion']) is int and prepared['schemaVersion'] == 1
        and prepared['component'] == COMPONENT and type(prepared['dirty']) is bool
        and prepared['sourceFingerprintAlgorithm'] == m.ALGORITHM)
    m.identifier(prepared['buildId']);m.text(prepared['gitSha'], '[0-9a-f]{40}', 40)
    m.timestamp(prepared['preparedAt'])
    rows = prepared['inputs']
    require(type(rows) is list and 0 < len(rows) <= 4096)
    sources, total = {}, 0
    for row in rows:
        require(type(row) is dict and row.keys() == {'path', 'sha256', 'bytes', 'executable'})
        path = m.path_name(row['path'])
        m.text(row['sha256'], '[0-9a-f]{64}', 64)
        require(len(path.encode('utf-8')) <= 256 and not m.generated(path) and path not in sources
            and type(row['bytes']) is int and 0 <= row['bytes'] <= c.MAX_SOURCE
            and type(row['executable']) is bool)
        total += row['bytes'];require(total <= MAX_SOURCE_TOTAL)
        sources[path] = row
    require(list(sources) == sorted(sources, key=lambda s:s.encode('utf-8'))
        and prepared['sourceFingerprint'] == m.digest(rows))
    return sources


def vocabulary(compiled):
    return dict(events=sorted(m.EVENTS),
        phases=['PROVIDER_JOBS_' + phase for phase in CHECKPOINTS]
            + ['PROVIDER_JOBS_DM_FAILURE'] + ['PROVIDER_JOBS_DM_' + phase for phase in DM_PHASES],
        errorCodes=['PROVIDER_JOBS_STAGE_FAILED'] + ['PROVIDER_JOBS_DM_' + phase + '_FAILED' for phase in DM_PHASES],
        dependencies=[], exceptionClasses=list(CLASSES),
        frames=[dict(symbol=f['symbol'], source=f['source']) for row in compiled['modules'] for f in row['frames']])


def compiled_record(compiled, members, sources):
    require(platform.python_version() == '3.14.10')
    require(type(compiled) is dict and compiled.keys() == {'schemaVersion', 'python', 'magic', 'modules'}
        and type(compiled['schemaVersion']) is int and compiled['schemaVersion'] == 1
        and compiled['python'] == '3.14.10' and compiled['magic'] == importlib.util.MAGIC_NUMBER.hex()
        and type(compiled['modules']) is list and len(compiled['modules']) == 2)
    total_bytes, total_frames = 0, 0
    for row, (name, source, filename) in zip(compiled['modules'], c.MODULES):
        require(type(row) is dict and row.keys() == {'name', 'source', 'sourceSha256', 'compiledSha256', 'compiledBytes', 'frames'}
            and row['name'] == name and row['source'] == source)
        data, original = members['compiled/' + filename], sources[source]
        require(type(row['compiledBytes']) is int and 16 < len(data) == row['compiledBytes'] <= c.MAX_COMPILED
            and sha(data) == row['compiledSha256'] and sha(original) == row['sourceSha256']
            and data[:4] == importlib.util.MAGIC_NUMBER and data[4:8] == b'\x03\x00\x00\x00'
            and data[8:16] == importlib.util.source_hash(original))
        require(type(row['frames']) is list and len(row['frames']) <= c.MAX_FRAMES)
        for frame in row['frames']:
            require(type(frame) is dict and frame.keys() == {'module', 'locator', 'qualname', 'firstLine', 'symbol', 'source'}
                and all(type(frame[key]) is str for key in ('module', 'qualname', 'symbol', 'source'))
                and type(frame['firstLine']) is int and frame['firstLine'] >= 0
                and type(frame['locator']) is list and len(frame['locator']) <= 32
                and all(type(index) is int and index >= 0 for index in frame['locator']))
        stream = io.BytesIO(data[16:]);code = marshal.load(stream)
        require(stream.tell() == len(data) - 16 and row['frames'] == c.code_catalog(code, name, source))
        total_bytes += len(data);total_frames += len(row['frames'])
    require(total_bytes <= 4 * 1024 * 1024 and total_frames <= c.MAX_FRAMES)


def archive_members(data):
    require(type(data) is bytes and 22 <= len(data) <= MAX_ARCHIVE)
    signature, disk, start_disk, count_disk, count, size, offset, comment = struct.unpack('<4s4H2LH', data[-22:])
    require(signature == b'PK\x05\x06' and disk == start_disk == comment == 0
        and count_disk == count and 0 < count <= 4101 and offset + size == len(data) - 22
        and size <= count * (46 + 300))
    result, cursor = {}, 0
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(not archive.comment and len(archive.infolist()) == count)
        for entry in archive.infolist():
            name = entry.filename
            require(name not in result and entry.header_offset == cursor and len(name.encode('utf-8')) <= 300
                and entry.compress_type == zipfile.ZIP_STORED and not entry.extra and not entry.comment
                and entry.flag_bits in (0, 0x800) and entry.create_system == 3
                and entry.date_time == (1980, 1, 1, 0, 0, 0)
                and entry.compress_size == entry.file_size and entry.file_size <= MAX_SOURCE_TOTAL
                and (entry.external_attr >> 16) in (stat.S_IFREG | 0o444, stat.S_IFREG | 0o555)
                and entry.external_attr & 0xffff == 0 and not entry.is_dir())
            header = struct.unpack('<4s5H3L2H', data[cursor:cursor + 30])
            require(header == (b'PK\x03\x04', 20, entry.flag_bits, 0, 0, 33, entry.CRC,
                entry.file_size, entry.file_size, len(name.encode('utf-8')), 0)
                and data[cursor + 30:cursor + 30 + header[-2]] == name.encode('utf-8'))
            result[name] = (archive.read(entry), bool((entry.external_attr >> 16) & 0o111))
            cursor += 30 + header[-2] + entry.file_size
        require(cursor == offset and list(result) == sorted(result, key=lambda s:s.encode('utf-8')))
    return result


def toolchain(image):
    require(type(image) is dict and image.keys() == {'imagePin', 'imageId'})
    m.text(image['imageId'], 'sha256:[0-9a-f]{64}', 71)
    require(type(image['imagePin']) is str and re.fullmatch(r'python:3\.14\.10-trixie@sha256:[0-9a-f]{64}', image['imagePin']))
    return [dict(name=name, version='3.14.10', vendorBuildProvenance='UNKNOWN', imageDigest=digest)
        for name, digest in (('python', image['imagePin'].split('@')[1]), ('python-compiler-image', image['imageId']))]


def verify(artifact, finalized, receipt, *, expected_build_id, expected_manifest_sha256,
           expected_artifact_sha256, expected_compiler_image):
    """Expected identities come from trusted original evidence, never the archive."""
    m.identifier(expected_build_id)
    m.text(expected_manifest_sha256, '[0-9a-f]{64}', 64)
    m.text(expected_artifact_sha256, '[0-9a-f]{64}', 64)
    expected_chain = toolchain(expected_compiler_image)
    raw = read(artifact, MAX_ARCHIVE, owner=os.getuid())
    require(sha(raw) == expected_artifact_sha256)
    entries = archive_members(raw)
    members = {name:value[0] for name, value in entries.items()}
    prepared = record(members['prepared.json'], MAX_SOURCE_TOTAL)
    source_rows = inputs(prepared)
    compiled = record(members['compiler-record.json'], c.MAX_RECORD)
    manifest_bytes = members['manifest.json']
    manifest = record(manifest_bytes, m.MAX_MANIFEST)
    names = {'manifest.json', 'prepared.json', 'compiler-record.json'}
    names.update('compiled/' + filename for _, _, filename in c.MODULES)
    names.update('source/' + name for name in source_rows)
    require(set(members) == names)
    sources = {}
    for name, row in source_rows.items():
        data, executable = entries['source/' + name]
        require(len(data) == row['bytes'] and sha(data) == row['sha256'] and executable == row['executable'])
        sources[name] = data
    require(all(not entries[name][1] for name in names if not name.startswith('source/')))
    compiled_record(compiled, members, sources)
    require(type(finalized) is dict and finalized.keys() == {'schemaVersion', 'preparationSha256', 'manifestSha256', 'manifest', 'toolchainRecord', 'dependencyRecord'}
        and type(finalized['schemaVersion']) is int and finalized['schemaVersion'] == 1
        and m.canonical(finalized['manifest']) == manifest_bytes and finalized['preparationSha256'] == m.digest(prepared)
        and finalized['manifestSha256'] == expected_manifest_sha256 == sha(manifest_bytes)
        and finalized['toolchainRecord'] == expected_chain and finalized['dependencyRecord'] == [])
    require(manifest.keys() == {'schemaVersion', 'buildId', 'component', 'gitSha', 'sourceFingerprint', 'dirty',
        'sourceFingerprintAlgorithm', 'compiledAt', 'toolchainFingerprint', 'dependencyFingerprint', 'vocabularySha256', 'vocabulary'}
        and type(manifest['schemaVersion']) is int and manifest['schemaVersion'] == 1
        and manifest['buildId'] == prepared['buildId'] == expected_build_id
        and all(manifest[key] == prepared[key] for key in ('component', 'gitSha', 'sourceFingerprint', 'dirty', 'sourceFingerprintAlgorithm'))
        and type(manifest['dirty']) is bool and manifest['vocabulary'] == vocabulary(compiled)
        and manifest['toolchainFingerprint'] == m.digest(expected_chain)
        and manifest['dependencyFingerprint'] == m.digest([])
        and manifest['vocabularySha256'] == m.digest(manifest['vocabulary']))
    m.vocabulary(manifest['vocabulary'])
    require(m.timestamp(manifest['compiledAt']) >= m.timestamp(prepared['preparedAt']))
    require(type(receipt) is dict and receipt.keys() == {'schemaVersion', 'buildId', 'component', 'preparationSha256', 'manifestSha256', 'artifact'}
        and type(receipt['schemaVersion']) is int and receipt['schemaVersion'] == 1
        and receipt['buildId'] == expected_build_id and receipt['component'] == COMPONENT
        and receipt['preparationSha256'] == m.digest(prepared) and receipt['manifestSha256'] == expected_manifest_sha256
        and type(receipt['artifact']) is dict and receipt['artifact'].keys() == {'path', 'sha256', 'bytes'}
        and receipt['artifact']['path'] == 'build/generated/dkp-logging/' + COMPONENT + '/' + expected_build_id + '/provider-tool.zip'
        and receipt['artifact']['sha256'] == expected_artifact_sha256
        and type(receipt['artifact']['bytes']) is int and receipt['artifact']['bytes'] == len(raw))
    return dict(result='VERIFIED_ORIGINAL_TOOL_DISTRIBUTION', buildId=expected_build_id,
        component=COMPONENT, manifestSha256=expected_manifest_sha256, artifactSha256=expected_artifact_sha256,
        sourceFileCount=len(sources), compiledModuleCount=2, frameCount=sum(len(row['frames']) for row in compiled['modules']),
        sharedStoreDelivery='NOT_STARTED')
