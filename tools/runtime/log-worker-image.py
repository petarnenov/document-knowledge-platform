"""Package a verified ORIGINAL inspector distribution; inspect exact image bytes before runtime use."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import tempfile
import uuid


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    result = importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


i = module('worker_install', 'log-java-install.py')
r = module('worker_image_runtime', 'dev-logging.py')
ROOT = Path(__file__).resolve().parents[2]
RECIPE = ROOT/'infra/docker-compose/inspector-logging-runtime.Dockerfile'
IGNORE = '*\n!Dockerfile\n!distribution\n!distribution/**\n'
CONTEXT_FIELDS = frozenset(('schemaVersion', 'component', 'buildId', 'manifestSha256', 'installSha256', 'recipeSha256'))
IMAGE_FIELDS = CONTEXT_FIELDS | frozenset(('result', 'imageId', 'operation', 'filesSha256', 'inspection', 'probeContainerId', 'probeCleanup', 'actualFilesSha256'))


def require(value):
    if not value:raise ValueError('LOG_WORKER_IMAGE_INVALID')


def sha(raw):return hashlib.sha256(raw).hexdigest()


def write(path, raw, mode=0o600):
    with os.fdopen(os.open(path, os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW, mode), 'wb') as stream:stream.write(raw)


def context(output):
    output = Path(output)
    installed = i.verify_installed(output)
    require(installed['component'] == 'structure-inspector')
    record = i.document(output/'image-context.json', 8192)
    require(type(record) is dict and record.keys() == CONTEXT_FIELDS and type(record['schemaVersion']) is int
        and record['schemaVersion'] == 1 and record['component'] == installed['component']
        and record['buildId'] == installed['buildId'] and record['manifestSha256'] == installed['manifestSha256'])
    require(record['installSha256'] == sha(i.read(output/'install.json', 2*i.m.MAX_MANIFEST, True)))
    recipe = i.read(output/'Dockerfile', 4096, True)
    require(recipe == i.read(RECIPE,4096) and record['recipeSha256'] == sha(recipe))
    require(i.read(output/'.dockerignore', 4096, True) == IGNORE.encode())
    return record, installed


def prepare(installation, output):
    installation, output = Path(installation), Path(output)
    installed = i.verify_installed(installation)
    require(installed['component'] == 'structure-inspector')
    require(output.is_absolute() and output.parent.resolve(strict=True) == output.parent
        and output.parent.stat().st_uid == os.getuid() and not output.parent.stat().st_mode & 0o077
        and not output.is_relative_to(ROOT) and not output.is_relative_to(installation)
        and not output.exists() and not output.is_symlink())
    files = i.distribution(installation/'distribution', 'structure-inspector')
    raw_manifest = i.read(installation/'manifest.json', i.m.MAX_MANIFEST, True)
    raw_install = i.read(installation/'install.json', 2*i.m.MAX_MANIFEST, True)
    output.mkdir(mode=0o700)
    for name, raw in files.items():
        target = output/'distribution'/name;target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        write(target, raw, installed['files'][name]['mode'])
    write(output/'manifest.json', raw_manifest);write(output/'install.json', raw_install)
    recipe = i.read(RECIPE,4096);write(output/'Dockerfile', recipe);write(output/'.dockerignore', IGNORE.encode())
    record = dict(schemaVersion=1, component='structure-inspector', buildId=installed['buildId'],
        manifestSha256=installed['manifestSha256'], installSha256=sha(raw_install), recipeSha256=sha(recipe))
    write(output/'image-context.json', i.m.canonical(record))
    require(i.verify_installed(installation) == installed)
    require(context(output) == (record, installed))
    return record


def image(output):
    r.local_daemon()
    output = Path(output);expected, installed = context(output)
    record = i.document(output/'image.json', 8192)
    validate_image(record, expected, installed)
    return record


def validate_image(record, expected, installed):
    require(type(record) is dict and record.keys() == IMAGE_FIELDS and type(record['schemaVersion']) is int and all(record[key] == value for key, value in expected.items()))
    require(record['result'] == 'BUILT' and record['inspection'] == 'EXACT_DISTRIBUTION_MATCH'
        and type(record['imageId']) is str and re.fullmatch(r'sha256:[0-9a-f]{64}', record['imageId'])
        and type(record['operation']) is str and re.fullmatch(r'[0-9a-f]{32}', record['operation'])
        and record['filesSha256'] == sha(i.m.canonical(installed['files']))
        and record['actualFilesSha256'] == record['filesSha256'] and record['probeCleanup'] == 'REMOVED'
        and type(record['probeContainerId']) is str and re.fullmatch(r'[0-9a-f]{64}', record['probeContainerId']))
    actual = r.inspect('image', record['imageId'])
    require(actual['Id'] == record['imageId'] and actual['Os'] == 'linux' and actual['Architecture'] == 'amd64'
        and actual['Config']['User'] == '10001:10001' and actual['Config']['Entrypoint'] == ['/app/bin/structure-inspector'])
    labels = actual['Config']['Labels'] or {}
    require(all(labels.get(key) == value for key, value in labels_for(record).items()))
    return record


def labels_for(record):
    return {'dkp.issue':'457', 'dkp.component':'structure-inspector', 'dkp.worker.operation':record['operation'],
        'dkp.log.build-id':record['buildId'], 'dkp.log.manifest-sha256':record['manifestSha256'],
        'dkp.worker.recipe-sha256':record['recipeSha256']}


def build(output):
    r.local_daemon()
    output = Path(output);expected, installed = context(output)
    require(not (output/'image.json').exists())
    operation = uuid.uuid4().hex;tag = 'dkp457-worker-'+operation
    record = dict(expected, result='BUILT', operation=operation, filesSha256=sha(i.m.canonical(installed['files'])), inspection='EXACT_DISTRIBUTION_MATCH')
    labels = labels_for(record)
    args = ['build', '--platform', 'linux/amd64', '--tag', tag]
    for key, value in labels.items():args += ['--label', key+'='+value]
    # Build output contains only the sealed public context, never runtime credentials.
    r.docker(*args, str(output), timeout=300)
    actual = r.inspect('image', tag);record['imageId'] = actual['Id']
    require(type(record['imageId']) is str and re.fullmatch(r'sha256:[0-9a-f]{64}', record['imageId']))
    args = ['create', '--platform', 'linux/amd64', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges:true', '--entrypoint', '/bin/true']
    for key, value in labels.items():args += ['--label', key+'='+value]
    container = r.docker(*args, record['imageId']).decode().strip()
    require(re.fullmatch(r'[0-9a-f]{64}', container))
    def owned():
        item = r.inspect('container', container)
        require(item['Id'] == container and item['Image'] == record['imageId']
            and all((item['Config']['Labels'] or {}).get(key) == value for key, value in labels.items())
            and item['State']['Status'] == 'created' and not item['State']['Running'])
    try:
        owned()
        with tempfile.TemporaryDirectory(prefix='dkp-worker-image-', dir=output.parent) as temporary:
            copied = Path(temporary)/'app'
            r.docker('cp', container+':/app', str(copied))
            files = i.distribution(copied, 'structure-inspector')
            require(files == i.distribution(output/'distribution', 'structure-inspector'))
            actual_files = {name:{'sha256':sha(raw), 'bytes':len(raw), 'mode':(copied/name).stat().st_mode & 0o777} for name,raw in files.items()}
            require(actual_files == installed['files']);record['actualFilesSha256'] = sha(i.m.canonical(actual_files))
        require(context(output) == (expected, installed))
    finally:
        owned();r.docker('rm', container)
    record['probeContainerId'] = container;record['probeCleanup'] = 'REMOVED'
    validate_image(record, expected, installed)
    write(output/'image.json', i.m.canonical(record))
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('action', choices=('prepare', 'build', 'verify'))
    parser.add_argument('--installation', type=Path);parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.action == 'prepare':result = prepare(args.installation, args.directory)
        elif args.action == 'build':result = build(args.directory)
        else:result = image(args.directory)
        print(json.dumps({key:result[key] for key in ('component', 'buildId', 'manifestSha256')}));return 0
    except Exception:
        print('{"result":"FAIL","code":"LOG_WORKER_IMAGE_INVALID"}');return 1


if __name__ == '__main__':raise SystemExit(main())
