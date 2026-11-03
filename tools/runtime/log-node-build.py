"""Original Node syntax-validation/source-distribution build; not transpilation."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

spec = importlib.util.spec_from_file_location('node_build_manifest', Path(__file__).with_name('log-build-manifest.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
COMPONENT = 'javascript-build'
MODULES = ('browser', 'node-https', 'node-spool', 'node', 'queue', 'responses', 'wire')
FILES = ('package.json', *sorted([*('src/' + name + '.js' for name in MODULES),
    *('src/' + name + '.d.ts' for name in ('browser', 'node-spool', 'node', 'types'))]))


def require(value):
    if not value: raise ValueError('LOG_NODE_BUILD_INVALID')


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result); result[key] = value
    return result


def read(path, limit=65536):
    require(path.is_absolute() and path.resolve(strict=True) == path and path.is_file() and path.stat().st_size <= limit)
    data = path.read_bytes(); require(0 < len(data) <= limit); return data


def write(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as target: target.write(value)


def docker(*args, timeout=30):
    return subprocess.check_output(['docker', *args], stderr=subprocess.DEVNULL, timeout=timeout)


def execute_package(root, directory):
    require(os.getuid() > 0 and not os.environ.get('DOCKER_HOST') and not os.environ.get('DOCKER_CONTEXT'))
    endpoint = json.loads(docker('context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}'))
    require(type(endpoint) is str and endpoint.startswith('unix:///'))
    pins = json.loads(read(root / 'tools/ci/python-runtime-pins.json'), object_pairs_hook=unique)
    require(pins['node'] == '24.22.1' and pins['platform'] == 'linux/amd64'
        and re.fullmatch(r'node:24\.22\.1-bookworm-slim@sha256:[0-9a-f]{64}', pins['nodeImage']))
    image = json.loads(docker('image', 'inspect', pins['nodeImage']))[0]
    require(image['Os'] == 'linux' and image['Architecture'] == 'amd64' and re.fullmatch(r'sha256:[0-9a-f]{64}', image['Id']))
    operation = uuid.uuid4().hex
    record = dict(schemaVersion=1, result='UNKNOWN', stage='CREATE', containerId=None, imageId=image['Id'],
        imagePin=pins['nodeImage'], operation=operation, cleanup='NOT_CREATED')
    journal = directory / 'package-operation.json'

    def save():
        pending = directory / 'package-operation.next'; write(pending, m.canonical(record)); pending.replace(journal)

    def owned():
        item = json.loads(docker('container', 'inspect', record['containerId']))[0]
        labels = item['Config']['Labels']
        require(item['Id'] == record['containerId'] and item['Image'] == record['imageId']
            and labels.get('dkp.issue') == '457' and labels.get('dkp.logging.operation') == 'node-build'
            and labels.get('dkp.logging.operation-id') == operation)
        return item

    output = directory / 'package-output'; output.mkdir(mode=0o700)
    require(all(not any(char in str(path) for char in (',', '\n', '\r')) for path in (root, directory)))
    args = ['create', '--init', '--platform', 'linux/amd64', '--name', 'dkp-node-build-' + operation,
        '--label', 'dkp.issue=457', '--label', 'dkp.logging.operation=node-build', '--label', 'dkp.logging.operation-id=' + operation,
        '--network', 'none', '--read-only', '--user', str(os.getuid()) + ':' + str(os.getgid()), '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges:true', '--memory', '256m', '--cpus', '1', '--pids-limit', '32',
        '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,size=32m',
        '--mount', 'type=bind,src=' + str(directory / 'package-input') + ',dst=/source,readonly',
        '--mount', 'type=bind,src=' + str(directory / 'package.mjs') + ',dst=/package.mjs,readonly',
        '--mount', 'type=bind,src=' + str(output) + ',dst=/output', image['Id'],
        'node', '/package.mjs', '--source', '/source', '--output', '/output']
    save()
    try:
        identifier = docker(*args).decode().strip(); require(re.fullmatch('[0-9a-f]{64}', identifier))
        record.update(containerId=identifier, stage='CHECK_AND_PACKAGE', cleanup='RETAINED'); save(); owned()
        descriptor = os.open(directory / 'package.private.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as target:
            result = subprocess.run(['docker', 'start', '--attach', identifier], stdout=target, stderr=subprocess.STDOUT, timeout=120)
        item = owned()
        require(result.returncode == 0 and not item['State']['Running'] and item['State']['ExitCode'] == 0)
        record.update(result='PACKAGED', stage='COMPLETE'); return record
    finally:
        if record['containerId'] is not None:
            item = owned()
            if not item['State']['Running']: docker('rm', record['containerId']); record['cleanup'] = 'REMOVED'
        save()


def verify_package(directory, expected, prepared):
    output = directory / 'package-output'
    record = json.loads(read(output / 'package-record.json', 16384), object_pairs_hook=unique)
    require(type(record) is dict and record.keys() == {'schemaVersion', 'node', 'v8', 'startedAt', 'finishedAt', 'durationMs', 'filesChecked', 'files'}
        and type(record['schemaVersion']) is int and record['schemaVersion'] == 1 and record['node'] == '24.22.1'
        and type(record['v8']) is str and re.fullmatch('[A-Za-z0-9_.+-]{1,96}', record['v8'])
        and type(record['filesChecked']) is int and record['filesChecked'] == 7
        and type(record['durationMs']) is int and 0 <= record['durationMs'] <= 9007199254740991)
    require(m.timestamp(record['finishedAt']) >= m.timestamp(record['startedAt']) >= m.timestamp(prepared['preparedAt']))
    require(type(record['files']) is list and len(record['files']) == len(FILES))
    require({path.name for path in output.iterdir()} == {'sdk', 'package-record.json'}
        and {path.name for path in (output / 'sdk').iterdir()} == {'src', 'package.json'}
        and {path.name for path in (output / 'sdk/src').iterdir()} == {name[4:] for name in FILES[1:]})
    files = {}
    for name, row in zip(FILES, record['files']):
        require(type(row) is dict and row.keys() == {'path', 'sourceSha256', 'distributionSha256', 'bytes'}
            and row['path'] == name and row['sourceSha256'] == expected[name])
        raw = read(output / 'sdk' / name)
        require(type(row['bytes']) is int and len(raw) == row['bytes']
            and hashlib.sha256(raw).hexdigest() == row['distributionSha256'] == expected[name])
        files['sdk/' + name] = raw
    return record, files


def build(root):
    root = Path(root); require(root.is_absolute() and root.resolve(strict=True) == root)
    prepared = m.prepare(root, COMPONENT)
    directory = m.write_output(root, COMPONENT, prepared['buildId'], 'prepared.json', prepared).parent
    execution = {key:str(uuid.uuid4()) for key in ('runId', 'instanceId', 'correlationId', 'startedEventId', 'finishedEventId')}
    source = directory / 'package-input'; source.mkdir(mode=0o700); (source / 'src').mkdir(mode=0o700)
    inputs = {row['path']:row for row in prepared['inputs']}; expected = {}
    for name in FILES:
        relative = 'sdks/javascript/' + name; data = read(root / relative)
        expected[name] = hashlib.sha256(data).hexdigest(); require(expected[name] == inputs[relative]['sha256'])
        write(source / name, data)
    helper = 'tools/runtime/log-node-package.mjs'; data = read(root / helper)
    require(hashlib.sha256(data).hexdigest() == inputs[helper]['sha256']); write(directory / 'package.mjs', data)
    operation = execute_package(root, directory)
    record, files = verify_package(directory, expected, prepared)
    vocabulary = dict(events=sorted(m.EVENTS), phases=['BUILD'], errorCodes=[], dependencies=[], exceptionClasses=[], frames=[])
    toolchain = [dict(name='node', version=record['node'], vendorBuildProvenance='UNKNOWN', imageDigest=operation['imagePin'].split('@', 1)[1]),
        dict(name='v8', version=record['v8'], vendorBuildProvenance='UNKNOWN')]
    finalized = m.finalize(root, prepared, vocabulary, toolchain, [])
    m.write_output(root, COMPONENT, prepared['buildId'], 'finalized.json', finalized)
    manifest = m.write_output(root, COMPONENT, prepared['buildId'], 'manifest.json', finalized['manifest'])
    files['sdk/manifest.json'] = m.canonical(finalized['manifest']); write(directory / 'package-output/sdk/manifest.json', files['sdk/manifest.json'])
    inventory = dict(schemaVersion=1, kind='dkp-node-sdk-source-distribution-v1', manifestSha256=finalized['manifestSha256'],
        files=[dict(path=name, sha256=hashlib.sha256(raw).hexdigest(), bytes=len(raw)) for name, raw in sorted(files.items())])
    artifact = directory / 'artifact.json'; write(artifact, m.canonical(inventory))
    for name, raw in files.items(): require(read(directory / 'package-output' / name, 1024 * 1024) == raw)
    receipt = m.seal(root, prepared, finalized, artifact)
    receipt_path = m.write_output(root, COMPONENT, prepared['buildId'], 'receipt.json', receipt)
    observations = dict(schemaVersion=1, component=COMPONENT, buildId=prepared['buildId'], **execution,
        **{key:record[key] for key in ('startedAt', 'finishedAt', 'durationMs', 'filesChecked')})
    observed = directory / 'observations.json'; write(observed, m.canonical(observations))
    return dict(result='BUILT', component=COMPONENT, buildId=prepared['buildId'], manifest=str(manifest),
        manifestSha256=finalized['manifestSha256'], artifact=str(artifact), receipt=str(receipt_path),
        observations=str(observed), observationsSha256=hashlib.sha256(observed.read_bytes()).hexdigest(), sharedStoreDelivery='NOT_STARTED')


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False); parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    try: result = build(args.source)
    except Exception: print('{"result":"FAIL","code":"LOG_NODE_BUILD_INVALID"}'); return 1
    print(json.dumps(result)); return 0


if __name__ == '__main__': raise SystemExit(main())
