"""Verify an original sealed portal and prepare its fixed runtime image context."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import tarfile

spec = importlib.util.spec_from_file_location('portal_original_build', Path(__file__).with_name('log-portal-build.py'))
p = importlib.util.module_from_spec(spec); spec.loader.exec_module(p)
m = p.m
MAX_BYTES = 64 * 1024 * 1024


def require(condition):
    if not condition: raise ValueError('LOG_PORTAL_IMAGE_INVALID')


def private(path, maximum):
    require(path.is_absolute() and path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and not info.st_mode & 0o077
        and 0 < info.st_size <= maximum)
    with path.open('rb') as stream: raw = stream.read(maximum + 1)
    require(len(raw) <= maximum)
    return raw


def document(path, maximum):
    return json.loads(private(path, maximum), object_pairs_hook=p.unique)


def verified(root, receipt_file):
    root = Path(root); receipt_file = Path(receipt_file)
    require(root.is_absolute() and root.resolve(strict=True) == root)
    receipt = document(receipt_file, m.MAX_MANIFEST)
    require(type(receipt) is dict and receipt.get('component') == 'admin-portal')
    build_id = m.identifier(receipt.get('buildId'))
    directory = root/'build/generated/dkp-logging/admin-portal'/build_id
    require(receipt_file == directory/'receipt.json')
    prepared = document(directory/'prepared.json', 32 * 1024 * 1024)
    finalized = document(directory/'finalized.json', 2 * m.MAX_MANIFEST)
    require(type(receipt.get('artifact')) is dict)
    artifact = root/m.path_name(receipt['artifact'].get('path'))
    require(artifact == directory/'portal.tar')
    private(artifact, MAX_BYTES + 1024 * 1024)
    require(receipt == m.seal(root, prepared, finalized, artifact))
    raw = private(directory/'manifest.json', m.MAX_MANIFEST)
    require(raw == m.canonical(finalized['manifest']))
    files = {}; total = 0
    with tarfile.open(artifact, mode='r:') as archive:
        for member in archive:
            name = m.path_name(member.name)
            require(member.isfile() and not member.issparse() and name not in files and len(files) < 512)
            require(not any(name.startswith(old+'/') or old.startswith(name+'/') for old in files))
            require(0 <= member.size <= MAX_BYTES and total + member.size <= MAX_BYTES)
            with archive.extractfile(member) as stream: data = stream.read(member.size + 1)
            require(len(data) == member.size)
            files[name] = data; total += member.size
    require(files.get('dkp-logging-manifest.json') == raw)
    escaped = raw.decode('utf-8').replace('<', '\\u003c')
    for name in ('index.html', 'logs.html'):
        html = files.get(name, b'').decode('utf-8', errors='strict')
        require('id="dkp-log-build-manifest"' in html and escaped in html)
    require(receipt == m.seal(root, prepared, finalized, artifact))
    return receipt, files


def prepare(root, receipt_file, output):
    root = Path(root); output = Path(output)
    receipt, files = verified(root, receipt_file)
    require(output.is_absolute() and output.parent.resolve(strict=True) == output.parent
        and not output.is_relative_to(root) and not output.exists() and not output.is_symlink())
    # All archive entries are validated before creating any output; never tar.extractall.
    output.mkdir(mode=0o700)
    inventory = {}
    for name, data in files.items():
        target = output/'public'/name; target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        target.write_bytes(data); target.chmod(0o644)
        inventory[name] = {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}
    for target, source in [('Dockerfile', 'dev-logging-portal-runtime.Dockerfile'), ('nginx.conf', 'dev-logging-nginx.conf')]:
        (output/target).write_bytes((root/'infra/docker-compose'/source).read_bytes()); (output/target).chmod(0o644)
    (output/'.dockerignore').write_text('*\n!Dockerfile\n!nginx.conf\n!public\n!public/**\n')
    record = {'schemaVersion': 1, 'result': 'PREPARED', 'component': 'admin-portal', 'buildId': receipt['buildId'],
        'manifestSha256': receipt['manifestSha256'], 'artifact': receipt['artifact'], 'files': inventory,
        'recipeSha256': hashlib.sha256((output/'Dockerfile').read_bytes()).hexdigest(),
        'nginxConfigSha256': hashlib.sha256((output/'nginx.conf').read_bytes()).hexdigest(),
        'runtimeImage': 'NOT_BUILT', 'sharedStoreDelivery': 'NOT_STARTED'}
    # The original public source and archive must still match after copying recipe/assets.
    verified(root, receipt_file)
    (output/'image-context.json').write_bytes(m.canonical(record)); (output/'image-context.json').chmod(0o600)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--receipt', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        result = prepare(args.source, args.receipt, args.output)
        print(json.dumps({key: result[key] for key in ('result', 'buildId', 'manifestSha256', 'runtimeImage', 'sharedStoreDelivery')})); return 0
    except Exception:
        print(json.dumps({'result': 'FAIL', 'code': 'LOG_PORTAL_IMAGE_INVALID'})); return 1


if __name__ == '__main__': raise SystemExit(main())
