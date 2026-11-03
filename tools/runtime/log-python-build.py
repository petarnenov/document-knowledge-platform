"""Original Python SDK compilation, manifest embedding and sealed build observations."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('python_build_manifest', HERE / 'log-build-manifest.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
COMPONENT = 'python-build'
MODULES = ('__init__', 'https', 'producer', 'spool', 'wire')
MANIFEST = 'dkp_logging/manifest.json'


def require(value):
    if not value:
        raise ValueError('LOG_PYTHON_BUILD_INVALID')


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def docker(*args, timeout=30):
    return subprocess.check_output(['docker', *args], stderr=subprocess.DEVNULL, timeout=timeout)


def read(path, limit):
    require(not path.is_symlink() and path.is_file() and path.stat().st_size <= limit)
    data = path.read_bytes()
    require(len(data) <= limit)
    return data


def write(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as target:
        target.write(data)


def execute_compiler(root, directory, prepared):
    require(not os.environ.get('DOCKER_HOST') and not os.environ.get('DOCKER_CONTEXT'))
    require(os.getuid() > 0)
    endpoint = json.loads(docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}'))
    require(type(endpoint) is str and endpoint.startswith('unix:///'))
    pins = json.loads(read(root / 'tools/ci/python-runtime-pins.json', 16384), object_pairs_hook=unique)
    require(pins['python'] == '3.14.10' and pins['platform'] == 'linux/amd64'
        and re.fullmatch(r'python:3\.14\.10-trixie@sha256:[0-9a-f]{64}', pins['pythonImage']))
    image = json.loads(docker('image', 'inspect', pins['pythonImage']))[0]
    require(image['Os'] == 'linux' and image['Architecture'] == 'amd64' and re.fullmatch(r'sha256:[0-9a-f]{64}', image['Id']))
    output = directory / 'compiler-output'
    output.mkdir(mode=0o700)
    operation = uuid.uuid4().hex
    record = {'schemaVersion':1, 'result':'UNKNOWN', 'stage':'CREATE', 'containerId':None,
        'imageId':image['Id'], 'imagePin':pins['pythonImage'], 'operation':operation, 'cleanup':'NOT_CREATED'}
    journal = directory / 'compiler-operation.json'

    def save():
        temporary = directory / 'compiler-operation.next'
        write(temporary, m.canonical(record))
        temporary.replace(journal)

    def owned():
        value = json.loads(docker('container', 'inspect', record['containerId']))[0]
        labels = value['Config']['Labels']
        require(value['Id'] == record['containerId'] and value['Image'] == record['imageId']
            and labels.get('dkp.issue') == '457' and labels.get('dkp.logging.operation') == 'python-build'
            and labels.get('dkp.logging.operation-id') == operation)
        return value

    args = ['create', '--platform', 'linux/amd64', '--name', 'dkp-python-build-' + operation,
        '--label', 'dkp.issue=457', '--label', 'dkp.logging.operation=python-build',
        '--label', 'dkp.logging.operation-id=' + operation, '--network', 'none', '--read-only',
        '--user', str(os.getuid()) + ':' + str(os.getgid()), '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges:true', '--memory', '256m', '--cpus', '1', '--pids-limit', '32',
        '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,size=32m',
        '--mount', 'type=bind,src=' + str(directory / 'compiler-input') + ',dst=/source,readonly',
        '--mount', 'type=bind,src=' + str(directory / 'compiler.py') + ',dst=/compiler.py,readonly',
        '--mount', 'type=bind,src=' + str(output) + ',dst=/output', image['Id'],
        'python', '-I', '/compiler.py', '--source', '/source', '--output', '/output']
    require(all(not any(char in str(path) for char in (',', '\n', '\r')) for path in (directory, root)))
    save()
    try:
        identifier = docker(*args).decode().strip()
        require(re.fullmatch(r'[0-9a-f]{64}', identifier))
        record.update(containerId=identifier, stage='COMPILE', cleanup='RETAINED')
        save()
        owned()
        descriptor = os.open(directory / 'compile.private.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as log:
            result = subprocess.run(['docker', 'start', '--attach', identifier], stdout=log,
                stderr=subprocess.STDOUT, timeout=60)
        value = owned()
        require(result.returncode == 0 and not value['State']['Running'] and value['State']['ExitCode'] == 0)
        record.update(result='COMPILED', stage='COMPLETE')
        return record
    finally:
        if record['containerId'] is not None:
            value = owned()
            if not value['State']['Running']:
                docker('rm', record['containerId'])
                record['cleanup'] = 'REMOVED'
        save()


def verify_compiled(directory, expected):
    output = directory / 'compiler-output'
    record = json.loads(read(output / 'compiler-record.json', 16384), object_pairs_hook=unique)
    require(type(record) is dict and record.keys() == {'schemaVersion', 'python', 'magic', 'modules'}
        and type(record['schemaVersion']) is int and record['schemaVersion'] == 1 and record['python'] == '3.14.10'
        and type(record['magic']) is str and re.fullmatch(r'[0-9a-f]{8}', record['magic']))
    require(type(record['modules']) is list and len(record['modules']) == 5)
    require({path.name for path in output.iterdir()} == {'compiler-record.json', 'dkp_logging'})
    package = output / 'dkp_logging'
    require(not package.is_symlink() and package.is_dir()
        and {path.name for path in package.iterdir()} == {name + '.pyc' for name in MODULES})
    files = {}
    for name, row in zip(MODULES, record['modules']):
        require(type(row) is dict and row.keys() == {'name', 'sourceSha256', 'compiledSha256', 'compiledBytes'}
            and row['name'] == name and row['sourceSha256'] == expected[name])
        data = read(package / (name + '.pyc'), 262144)
        require(type(row['compiledBytes']) is int and 16 < len(data) == row['compiledBytes']
            and hashlib.sha256(data).hexdigest() == row['compiledSha256']
            and data[:4].hex() == record['magic'] and data[4:8] == b'\x03\x00\x00\x00')
        files['dkp_logging/' + name + '.pyc'] = data
    return record, files


def build(root):
    root = Path(root)
    require(root.is_absolute() and root.resolve(strict=True) == root)
    prepared = m.prepare(root, COMPONENT)
    directory = m.write_output(root, COMPONENT, prepared['buildId'], 'prepared.json', prepared).parent
    execution = {key:str(uuid.uuid4()) for key in ('runId', 'instanceId', 'correlationId', 'startedEventId', 'finishedEventId')}
    source = directory / 'compiler-input'
    source.mkdir(mode=0o700)
    expected = {}
    inventory = {row['path']:row for row in prepared['inputs']}
    for name in MODULES:
        relative = 'sdks/python/dkp_logging/' + name + '.py'
        data = read(root / relative, 65536)
        expected[name] = hashlib.sha256(data).hexdigest()
        require(inventory[relative]['sha256'] == expected[name])
        write(source / (name + '.py'), data)
    helper = 'tools/runtime/log-python-compile.py'
    compiler = read(root / helper, 65536)
    require(hashlib.sha256(compiler).hexdigest() == inventory[helper]['sha256'])
    write(directory / 'compiler.py', compiler)
    started, monotonic = m.now(), time.monotonic()
    operation = execute_compiler(root, directory, prepared)
    finished = m.now()
    duration = max(0, int((time.monotonic() - monotonic) * 1000))
    record, files = verify_compiled(directory, expected)
    toolchain = [{'name':'python', 'version':record['python'], 'vendorBuildProvenance':'UNKNOWN',
        'imageDigest':operation['imagePin'].split('@', 1)[1]}]
    vocabulary = {'events':sorted(m.EVENTS), 'phases':['BUILD'], 'errorCodes':[],
        'dependencies':[], 'exceptionClasses':[], 'frames':[]}
    finalized = m.finalize(root, prepared, vocabulary, toolchain, [])
    m.write_output(root, COMPONENT, prepared['buildId'], 'finalized.json', finalized)
    manifest = m.write_output(root, COMPONENT, prepared['buildId'], 'manifest.json', finalized['manifest'])
    artifact = directory / 'python-logging.zip'
    with zipfile.ZipFile(artifact, 'x', compression=zipfile.ZIP_STORED) as archive:
        for name, data in files.items():
            archive.writestr(name, data)
        archive.writestr(MANIFEST, m.canonical(finalized['manifest']))
    artifact.chmod(0o600)
    require(artifact.stat().st_size <= 2 * 1024 * 1024)
    expected_entries = {**files, MANIFEST:m.canonical(finalized['manifest'])}
    with zipfile.ZipFile(artifact) as archive:
        require(len(archive.infolist()) == len(expected_entries)
            and set(archive.namelist()) == set(expected_entries))
        for entry in archive.infolist():
            require(entry.compress_type == zipfile.ZIP_STORED and entry.file_size == len(expected_entries[entry.filename])
                and archive.read(entry) == expected_entries[entry.filename])
    receipt = m.seal(root, prepared, finalized, artifact)
    receipt_path = m.write_output(root, COMPONENT, prepared['buildId'], 'receipt.json', receipt)
    observations = {'schemaVersion':1, 'component':COMPONENT, 'buildId':prepared['buildId'], **execution,
        'startedAt':started, 'finishedAt':finished, 'durationMs':duration, 'filesCompiled':len(files)}
    observations_path = directory / 'observations.json'
    write(observations_path, m.canonical(observations))
    return {'result':'BUILT', 'component':COMPONENT, 'buildId':prepared['buildId'],
        'manifest':str(manifest), 'manifestSha256':hashlib.sha256(manifest.read_bytes()).hexdigest(),
        'artifact':str(artifact), 'receipt':str(receipt_path), 'observations':str(observations_path),
        'observationsSha256':hashlib.sha256(observations_path.read_bytes()).hexdigest(), 'sharedStoreDelivery':'NOT_STARTED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = build(args.source)
    except Exception:
        print('{"result":"FAIL","code":"LOG_PYTHON_BUILD_INVALID"}')
        return 1
    print(json.dumps(result))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
