"""Install and reverify a fixed original distribution; never execute provider code."""
import argparse
import json
import os
from pathlib import Path
import stat

import importlib.util

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('tool_install_verify', HERE / 'log-tool-verify.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
PRIVATE = 'source/local-data/security/bp03-upload'
PRIVATE_DIRS = frozenset(('source/local-data', 'source/local-data/security', PRIVATE))
FIELDS = frozenset(('schemaVersion', 'buildId', 'manifestSha256', 'artifactSha256', 'compilerImage', 'files'))
MAX_RECORD = 4 * 1024 * 1024


def canonical(path, *, exists=True):
    path = Path(path)
    v.require(path.is_absolute() and not any(ord(c) < 32 or ord(c) == 127 or c == ',' for c in str(path))
        and path.resolve(strict=exists) == path)
    return path


def directory(path, mode):
    canonical(path)
    info = path.lstat()
    v.require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
        and stat.S_IMODE(info.st_mode) == mode)


def write(path, data, executable=False):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as output:
        output.write(data)
        os.fchmod(output.fileno(), 0o555 if executable else 0o444)


def expected(record):
    return dict(expected_build_id=record['buildId'], expected_manifest_sha256=record['manifestSha256'],
        expected_artifact_sha256=record['artifactSha256'], expected_compiler_image=record['compilerImage'])


def original(artifact, finalized, receipt, identities):
    finalized_raw = v.read(canonical(finalized), 2 * 1024 * 1024, owner=os.getuid())
    receipt_raw = v.read(canonical(receipt), 1024 * 1024, owner=os.getuid())
    v.verify(canonical(artifact), v.record(finalized_raw, 2 * 1024 * 1024),
        v.record(receipt_raw, 1024 * 1024), **identities)
    archive_raw = v.read(canonical(artifact), v.MAX_ARCHIVE, owner=os.getuid())
    v.require(v.sha(archive_raw) == identities['expected_artifact_sha256'])
    members = v.archive_members(archive_raw)
    members.update({'provider-tool.zip': (archive_raw, False),
        'finalized.json': (finalized_raw, False), 'receipt.json': (receipt_raw, False)})
    return members


def rows(members):
    v.require(0 < len(members) <= 4104 and sum(len(data) for data, _ in members.values()) <= 128 * 1024 * 1024)
    return [dict(path=name, sha256=v.sha(data), bytes=len(data), executable=executable)
        for name, (data, executable) in sorted(members.items(), key=lambda row: row[0].encode('utf-8'))]


def directories(names):
    result = set(PRIVATE_DIRS)
    for name in names:
        relative = Path(name).parent
        while relative != Path('.'):
            result.add(relative.as_posix())
            relative = relative.parent
    return result


def install(artifact, finalized, receipt, target, **identities):
    """Fresh exclusive layout. Failed own installations are retained, never adopted."""
    target = canonical(target, exists=False)
    directory(target.parent, 0o700)
    v.require(not target.exists() and not target.is_symlink())
    members = original(artifact, finalized, receipt, identities)
    inventory = rows(members)
    record = dict(schemaVersion=1, buildId=identities['expected_build_id'],
        manifestSha256=identities['expected_manifest_sha256'],
        artifactSha256=identities['expected_artifact_sha256'],
        compilerImage=identities['expected_compiler_image'], files=inventory)
    raw = v.m.canonical(record)
    v.require(len(raw) <= MAX_RECORD)
    target.mkdir(mode=0o700)
    dirs = directories(members)
    for name in sorted(dirs, key=lambda name: (len(Path(name).parts), name)):
        (target / name).mkdir(mode=0o700)
    for name, (data, executable) in members.items():
        write(target / name, data, executable)
    write(target / 'installation.json', raw)
    for name in sorted(dirs, key=lambda name: len(Path(name).parts), reverse=True):
        (target / name).chmod(0o700 if name in PRIVATE_DIRS else 0o555)
    digest = v.sha(raw)
    verify_installation(target, digest)
    return dict(result='INSTALLED_ORIGINAL_TOOL', installationSha256=digest,
        buildId=record['buildId'], sharedStoreDelivery='NOT_STARTED')


def verify_installation(target, installation_sha256):
    target = canonical(target)
    directory(target, 0o700)
    v.m.text(installation_sha256, '[0-9a-f]{64}', 64)
    raw = v.read(target / 'installation.json', MAX_RECORD, owner=os.getuid())
    v.require(v.sha(raw) == installation_sha256)
    record = v.record(raw, MAX_RECORD)
    v.require(record.keys() == FIELDS and type(record['schemaVersion']) is int
        and record['schemaVersion'] == 1 and type(record['files']) is list
        and 0 < len(record['files']) <= 4104)
    names, total = [], 0
    for row in record['files']:
        v.require(type(row) is dict and row.keys() == {'path', 'sha256', 'bytes', 'executable'})
        name = v.m.path_name(row['path'])
        v.require(len(name.encode('utf-8')) <= 300 and type(row['bytes']) is int
            and 0 <= row['bytes'] <= v.MAX_ARCHIVE and type(row['executable']) is bool)
        v.m.text(row['sha256'], '[0-9a-f]{64}', 64)
        total += row['bytes'];names.append(name)
    v.require(total <= 128 * 1024 * 1024 and len(set(names)) == len(names)
        and names == sorted(names, key=lambda name: name.encode('utf-8')))
    dirs = directories(names)
    # Never traverse private runtime contents; only its declared root is inspected.
    observed_files, observed_dirs = set(), set()
    def visit(parent):
        for child in parent.iterdir():
            name = child.relative_to(target).as_posix()
            info = child.lstat()
            v.require(info.st_uid == os.getuid())
            if stat.S_ISDIR(info.st_mode):
                v.require(name in dirs)
                directory(child, 0o700 if name in PRIVATE_DIRS else 0o555)
                observed_dirs.add(name)
                if name != PRIVATE:visit(child)
            else:
                v.require(stat.S_ISREG(info.st_mode) and name in set(names) | {'installation.json'})
                observed_files.add(name)
    visit(target)
    v.require(observed_dirs == dirs and observed_files == set(names) | {'installation.json'}
        and stat.S_IMODE((target / 'installation.json').stat().st_mode) == 0o444)
    for row in record['files']:
        path = target / row['path']
        v.require(stat.S_IMODE(path.lstat().st_mode) == (0o555 if row['executable'] else 0o444))
        data = v.read(path, row['bytes'], owner=os.getuid())
        v.require(len(data) == row['bytes'] and v.sha(data) == row['sha256'])
    members = original(target / 'provider-tool.zip', target / 'finalized.json', target / 'receipt.json', expected(record))
    v.require(rows(members) == record['files'])
    return dict(record=record, manifest=v.record(members['manifest.json'][0], v.m.MAX_MANIFEST),
        compiler=v.record(members['compiler-record.json'][0], v.c.MAX_RECORD))


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    for name in ('artifact', 'finalized', 'receipt', 'installation-directory'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('build-id', 'manifest-sha256', 'artifact-sha256', 'compiler-image-pin', 'compiler-image-id'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    try:
        result = install(args.artifact, args.finalized, args.receipt, args.installation_directory,
            expected_build_id=args.build_id, expected_manifest_sha256=args.manifest_sha256,
            expected_artifact_sha256=args.artifact_sha256,
            expected_compiler_image=dict(imagePin=args.compiler_image_pin, imageId=args.compiler_image_id))
    except Exception:
        print('{"result":"FAIL","code":"LOG_TOOL_INSTALL_INVALID"}')
        return 1
    print(json.dumps(result, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
