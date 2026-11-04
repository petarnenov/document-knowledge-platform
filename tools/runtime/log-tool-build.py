"""Original provider source distribution build; no install, registration or delivery."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import uuid
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('tool_build_verify', HERE / 'log-tool-verify.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
m = v.m
COMPONENT = v.COMPONENT


def require(value):
    if not value:
        raise ValueError('LOG_TOOL_BUILD_INVALID')


def docker(*args, timeout=30):
    return subprocess.check_output(['docker', *args], stderr=subprocess.DEVNULL, timeout=timeout)


def read(path, limit):
    return v.read(path, limit)


def unique(pairs):
    return v.unique(pairs)


def write(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
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
            and labels.get('dkp.issue') == '457' and labels.get('dkp.logging.operation') == 'provider-tool-build'
            and labels.get('dkp.logging.operation-id') == operation)
        return value

    args = ['create', '--platform', 'linux/amd64', '--name', 'dkp-provider-tool-build-' + operation,
        '--label', 'dkp.issue=457', '--label', 'dkp.logging.operation=provider-tool-build',
        '--label', 'dkp.logging.operation-id=' + operation, '--network', 'none', '--read-only',
        '--user', str(os.getuid()) + ':' + str(os.getgid()), '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges:true', '--memory', '256m', '--cpus', '1', '--pids-limit', '32',
        '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,size=32m',
        '--mount', 'type=bind,src=' + str(directory / 'compiler-input') + ',dst=/source,readonly',
        '--mount', 'type=bind,src=' + str(directory / 'compiler-input/tools/runtime/log-tool-compile.py') + ',dst=/compiler.py,readonly',
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



def snapshot(root, directory, prepared):
    inventory = v.inputs(prepared)
    source = directory / 'compiler-input'
    source.mkdir(mode=0o700)
    files = {}
    for name, row in inventory.items():
        original = root / name
        data = v.read(original, v.c.MAX_SOURCE, owner=root.stat().st_uid)
        require(v.sha(data) == row['sha256'] and len(data) == row['bytes']
            and bool(original.stat().st_mode & 0o111) == row['executable'])
        target = source / name
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        write(target, data)
        target.chmod(0o555 if row['executable'] else 0o444)
        files[name] = data
    # Bind the actual trusted builder/verifier/compiler/manifest implementation,
    # not unrelated files from a caller's different source checkout.
    for filename in ('log-tool-build.py', 'log-tool-verify.py', 'log-tool-compile.py', 'log-build-manifest.py'):
        require(files['tools/runtime/' + filename] == v.read(HERE / filename, v.c.MAX_SOURCE))
    for path in sorted([source] + [p for p in source.rglob('*') if p.is_dir()], key=lambda p:len(p.parts), reverse=True):
        path.chmod(0o555)
    m.unchanged(root, prepared)
    return files


def compiled(directory, sources):
    output = directory / 'compiler-output'
    require(output.resolve(strict=True) == output and output.is_dir()
        and {p.name for p in output.iterdir()} == {'compiler-record.json'} | {name for _, _, name in v.c.MODULES})
    record = v.record(v.read(output / 'compiler-record.json', v.c.MAX_RECORD, owner=os.getuid()), v.c.MAX_RECORD)
    files = {'compiled/' + name:v.read(output / name, v.c.MAX_COMPILED, owner=os.getuid()) for _, _, name in v.c.MODULES}
    v.compiled_record(record, files, sources)
    return record, files


def package(path, members, executable):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_STORED, allowZip64=False) as archive:
            for name in sorted(members, key=lambda s:s.encode('utf-8')):
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.create_system = 3
                entry.external_attr = (stat.S_IFREG | (0o555 if name in executable else 0o444)) << 16
                archive.writestr(entry, members[name])
    require(path.stat().st_size <= v.MAX_ARCHIVE)


def build(root):
    root = Path(root)
    require(root.is_absolute() and root.resolve(strict=True) == root and os.getuid() > 0)
    prepared = m.prepare(root, COMPONENT)
    directory = m.write_output(root, COMPONENT, prepared['buildId'], 'prepared.json', prepared).parent
    require(directory.stat().st_uid == os.getuid() and not directory.stat().st_mode & 0o077)
    sources = snapshot(root, directory, prepared)
    operation = execute_compiler(root, directory, prepared)
    require(operation['result'] == 'COMPILED' and operation['stage'] == 'COMPLETE' and operation['cleanup'] == 'REMOVED')
    record, files = compiled(directory, sources)
    image = {key:operation[key] for key in ('imagePin', 'imageId')}
    finalized = m.finalize(root, prepared, v.vocabulary(record), v.toolchain(image), [])
    finalized_path = m.write_output(root, COMPONENT, prepared['buildId'], 'finalized.json', finalized)
    manifest_path = m.write_output(root, COMPONENT, prepared['buildId'], 'manifest.json', finalized['manifest'])
    members = {'source/' + name:data for name, data in sources.items()}
    members.update(files)
    members.update({'manifest.json':m.canonical(finalized['manifest']), 'prepared.json':m.canonical(prepared),
        'compiler-record.json':m.canonical(record)})
    executable = {'source/' + row['path'] for row in prepared['inputs'] if row['executable']}
    artifact = directory / 'provider-tool.zip'
    package(artifact, members, executable)
    receipt = m.seal(root, prepared, finalized, artifact)
    verified = v.verify(artifact, finalized, receipt, expected_build_id=prepared['buildId'],
        expected_manifest_sha256=finalized['manifestSha256'], expected_artifact_sha256=receipt['artifact']['sha256'],
        expected_compiler_image=image)
    receipt_path = m.write_output(root, COMPONENT, prepared['buildId'], 'receipt.json', receipt)
    return dict(result='BUILT', component=COMPONENT, buildId=prepared['buildId'],
        manifest=str(manifest_path), manifestSha256=finalized['manifestSha256'], finalized=str(finalized_path),
        artifact=str(artifact), artifactSha256=receipt['artifact']['sha256'], receipt=str(receipt_path),
        compilerImage=image, verified=verified, sharedStoreDelivery='NOT_STARTED')


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = build(args.source)
    except Exception:
        print('{"result":"FAIL","code":"LOG_TOOL_BUILD_INVALID"}')
        return 1
    print(json.dumps(result))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
