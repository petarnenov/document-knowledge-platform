"""Install a verified original Java distribution privately; never start a process."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat

spec = importlib.util.spec_from_file_location('original_java', Path(__file__).with_name('log-java-build.py'))
j = importlib.util.module_from_spec(spec); spec.loader.exec_module(j)
m = j.m
MAX_FILE = 64 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024


def require(value):
    if not value: raise ValueError('LOG_JAVA_INSTALL_INVALID')


def read(path, maximum, private=False):
    require(path.is_absolute() and path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
        and not info.st_mode & (0o077 if private else 0o022) and 0 < info.st_size <= maximum)
    with path.open('rb') as stream: raw = stream.read(maximum + 1)
    require(len(raw) <= maximum)
    return raw


def document(path, maximum):
    return json.loads(read(path, maximum, True), object_pairs_hook=j.unique)


def distribution(directory, component):
    require(directory.is_absolute() and directory.resolve(strict=True) == directory and directory.is_dir())
    files = {}; total = 0
    for path in sorted(directory.rglob('*')):
        require(not path.is_symlink() and path.resolve(strict=True) == path)
        if path.is_dir(): continue
        name = m.path_name(path.relative_to(directory).as_posix())
        require(name in ('bin/'+component, 'bin/'+component+'.bat')
            or (len(Path(name).parts) == 2 and name.startswith('lib/') and name.endswith('.jar')))
        data = read(path, MAX_FILE); total += len(data)
        require(len(files) < 512 and total <= MAX_TOTAL)
        files[name] = data
    require('bin/'+component in files and 'bin/'+component+'.bat' in files)
    return files


def original(root, receipt_file):
    require(root.is_absolute() and root.resolve(strict=True) == root)
    receipt = document(receipt_file, m.MAX_MANIFEST)
    require(type(receipt) is dict and receipt.get('component') in j.COMPONENTS)
    component = receipt['component']; build = m.identifier(receipt.get('buildId'))
    directory = root/'build/generated/dkp-logging'/component/build
    require(receipt_file == directory/'receipt.json')
    prepared = document(directory/'prepared.json', 32 * 1024 * 1024)
    finalized = document(directory/'finalized.json', 2 * m.MAX_MANIFEST)
    require(type(receipt.get('artifact')) is dict)
    artifact = root/m.path_name(receipt['artifact'].get('path'))
    require(artifact.parent == root/'services'/component/'bootstrap/build/libs' and artifact.suffix == '.jar')
    require(receipt == m.seal(root, prepared, finalized, artifact))
    raw = read(directory/'manifest.json', m.MAX_MANIFEST, True)
    require(raw == m.canonical(finalized['manifest']))
    files = distribution(root/'services'/component/'bootstrap/build/install'/component, component)
    captured = document(directory/'distribution.json', 2 * m.MAX_MANIFEST)
    require(type(captured) is dict and type(captured.get('schemaVersion')) is int)
    require(captured == {'schemaVersion':1, 'component':component, 'buildId':build,
        'manifestSha256':receipt['manifestSha256'],
        'files':{name:{'sha256':hashlib.sha256(data).hexdigest(), 'bytes':len(data)} for name,data in files.items()}})
    require(files.get('lib/'+artifact.name) == read(artifact, MAX_FILE))
    j.verify_jar(artifact, finalized['manifest'])
    require(receipt == m.seal(root, prepared, finalized, artifact))
    return receipt, raw, files


def install(root, receipt_file, output):
    root = Path(root); receipt_file = Path(receipt_file); output = Path(output)
    receipt, raw, files = original(root, receipt_file)
    require(output.is_absolute() and output.parent.resolve(strict=True) == output.parent
        and output.parent.stat().st_uid == os.getuid() and not output.parent.stat().st_mode & 0o077
        and not output.is_relative_to(root) and not output.exists() and not output.is_symlink())
    output.mkdir(mode=0o700)
    inventory = {}
    for name, data in files.items():
        target = output/'distribution'/name; target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        mode = 0o700 if name == 'bin/'+receipt['component'] else 0o600
        target.write_bytes(data); target.chmod(mode)
        inventory[name] = {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'mode': mode}
    (output/'manifest.json').write_bytes(raw); (output/'manifest.json').chmod(0o600)
    # Recheck both original inputs and the complete copied distribution before success.
    require(original(root, receipt_file) == (receipt, raw, files))
    require(distribution(output/'distribution', receipt['component']) == files)
    record = {'schemaVersion': 1, 'result': 'INSTALLED', 'component': receipt['component'],
        'buildId': receipt['buildId'], 'manifestSha256': receipt['manifestSha256'],
        'artifact': receipt['artifact'], 'files': inventory, 'process': 'NOT_STARTED', 'sharedStoreDelivery': 'NOT_STARTED'}
    (output/'install.json').write_bytes(m.canonical(record)); (output/'install.json').chmod(0o600)
    return record


def verify_installed(output):
    """Recheck a private installed artifact independently of later workspace/HEAD changes."""
    output = Path(output)
    require(output.is_absolute() and output.resolve(strict=True) == output and output.is_dir()
        and output.stat().st_uid == os.getuid() and not output.stat().st_mode & 0o077)
    record = document(output/'install.json', 2*m.MAX_MANIFEST)
    require(type(record) is dict and record.keys() == {'schemaVersion','result','component','buildId','manifestSha256',
        'artifact','files','process','sharedStoreDelivery'} and type(record['schemaVersion']) is int
        and record['schemaVersion'] == 1 and record['result'] == 'INSTALLED' and record['component'] in j.COMPONENTS
        and record['process'] == 'NOT_STARTED' and record['sharedStoreDelivery'] == 'NOT_STARTED')
    m.identifier(record['buildId'])
    raw = read(output/'manifest.json',m.MAX_MANIFEST,True)
    require(hashlib.sha256(raw).hexdigest() == record['manifestSha256'])
    manifest = json.loads(raw,object_pairs_hook=j.unique)
    require(manifest['component'] == record['component'] and manifest['buildId'] == record['buildId'])
    files = distribution(output/'distribution',record['component'])
    actual = {}
    for name,data in files.items():
        mode = 0o700 if name == 'bin/'+record['component'] else 0o600
        require(stat.S_IMODE((output/'distribution'/name).stat().st_mode) == mode)
        actual[name] = {'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'mode':mode}
    require(actual == record['files'])
    artifact = record['artifact']
    require(type(artifact) is dict and artifact.keys() == {'path','sha256','bytes'})
    name = m.path_name(artifact['path']); parts = Path(name).parts
    require(len(parts) == 6 and parts[:5] == ('services',record['component'],'bootstrap','build','libs') and parts[-1].endswith('.jar'))
    require(actual.get('lib/'+parts[-1],{}).get('sha256') == artifact['sha256']
        and actual['lib/'+parts[-1]]['bytes'] == artifact['bytes'])
    j.verify_jar(output/'distribution/lib'/parts[-1],manifest)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--receipt', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        result = install(args.source, args.receipt, args.output)
        print(json.dumps({key: result[key] for key in ('result', 'component', 'buildId', 'manifestSha256', 'process', 'sharedStoreDelivery')})); return 0
    except Exception:
        print(json.dumps({'result': 'FAIL', 'code': 'LOG_JAVA_INSTALL_INVALID'})); return 1


if __name__ == '__main__': raise SystemExit(main())
