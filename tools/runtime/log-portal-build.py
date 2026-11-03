"""Original Vite compilation adapter; no runtime identity, registration or credentials."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tarfile

spec = importlib.util.spec_from_file_location('portal_build_manifest', Path(__file__).with_name('log-build-manifest.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
COMPONENT = 'admin-portal'
PHASES = ['STARTUP', 'SHUTDOWN', 'CONSOLE', 'ERROR', 'PROMISE', 'HTTP', 'QUERY', 'MUTATION', 'ROUTE', 'IDENTITY']


def require(value):
    if not value: raise ValueError('LOG_PORTAL_BUILD_INVALID')


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result); result[key] = value
    return result


def run(root, node, pnpm, directory, arguments, log_name, manifest=None):
    environment = {**os.environ, 'PATH':str(node.parent)+os.pathsep+os.environ.get('PATH', '')}
    environment.pop('DKP_LOG_BUILD_MANIFEST', None)
    if manifest is not None: environment['DKP_LOG_BUILD_MANIFEST'] = str(manifest)
    fd = os.open(directory/log_name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as output:
        status = subprocess.run([str(node), str(pnpm), '--filter', COMPONENT, *arguments], cwd=root,
            env=environment, stdout=output, stderr=subprocess.STDOUT, timeout=900)
    require(status.returncode == 0)


def package(root, directory, manifest):
    dist = root/'apps/admin-portal/dist'
    require(dist.is_dir() and not dist.is_symlink())
    files = sorted(dist.rglob('*')); require(0 < len(files) <= 512)
    raw = m.canonical(manifest)
    require((dist/'dkp-logging-manifest.json').read_bytes() == raw)
    escaped = raw.decode('utf-8').replace('<', '\\u003c')
    for name in ('index.html', 'logs.html'):
        html = (dist/name).read_text()
        require('id="dkp-log-build-manifest"' in html and escaped in html)
    output = directory/'portal.tar'; total = 0
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream, tarfile.open(fileobj=stream, mode='w') as archive:
        for path in files:
            require(not path.is_symlink())
            if path.is_dir(): continue
            require(path.is_file()); relative = path.relative_to(dist).as_posix(); m.path_name(relative)
            before = m.file_hash(path); total += before[2]; require(total <= 64*1024*1024)
            info = tarfile.TarInfo(relative); info.size = before[2]; info.mode = 0o644
            with path.open('rb') as source: archive.addfile(info, source)
            require(m.file_hash(path) == before)
    return output


def build(root, node, pnpm):
    root = Path(root); node = Path(node); pnpm = Path(pnpm)
    require(root.is_absolute() and root.resolve(strict=True) == root)
    require(all(path.is_absolute() and path.resolve(strict=True) == path and path.is_file() for path in (node, pnpm)))
    prepared = m.prepare(root, COMPONENT)
    directory = m.write_output(root, COMPONENT, prepared['buildId'], 'prepared.json', prepared).parent
    run(root, node, pnpm, directory, ['typecheck'], 'compile.private.log')
    # Executed by this same pinned Node/pnpm environment, not reconstructed from runtime HEAD.
    record_path = directory/'build-record.json'
    run(root, node, pnpm, directory, ['exec', 'node', str(root/'tools/runtime/log-portal-record.mjs'), str(record_path)], 'record.private.log')
    require(not record_path.is_symlink() and record_path.is_file() and record_path.stat().st_size <= 65536)
    record = json.loads(record_path.read_bytes(), object_pairs_hook=unique)
    require(type(record) is dict and record.keys() == {'schemaVersion', 'component', 'toolchain', 'dependencies'}
        and type(record['schemaVersion']) is int and record['schemaVersion'] == 1 and record['component'] == COMPONENT)
    m.dependencies(record['toolchain']); m.dependencies(record['dependencies'])
    actual = {row['name']:row['version'] for row in record['toolchain']}
    require(actual == {'node':'24.22.1', 'pnpm':'12.16.1', 'typescript':'6.0.3', 'vite':'8.3.5'} and len(record['toolchain']) == 4)
    vocabulary = {'events':sorted(m.EVENTS), 'phases':PHASES, 'errorCodes':[], 'dependencies':[], 'exceptionClasses':[], 'frames':[]}
    finalized = m.finalize(root, prepared, vocabulary, record['toolchain'], record['dependencies'])
    m.write_output(root, COMPONENT, prepared['buildId'], 'finalized.json', finalized)
    manifest = m.write_output(root, COMPONENT, prepared['buildId'], 'manifest.json', finalized['manifest'])
    run(root, node, pnpm, directory, ['exec', 'vite', 'build'], 'package.private.log', manifest)
    artifact = package(root, directory, finalized['manifest'])
    receipt = m.seal(root, prepared, finalized, artifact)
    receipt_path = m.write_output(root, COMPONENT, prepared['buildId'], 'receipt.json', receipt)
    return {'result':'BUILT', 'component':COMPONENT, 'buildId':prepared['buildId'], 'manifest':str(manifest),
        'manifestSha256':hashlib.sha256(manifest.read_bytes()).hexdigest(), 'receipt':str(receipt_path),
        'artifact':str(artifact), 'sharedStoreDelivery':'NOT_STARTED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--node', type=Path, required=True)
    parser.add_argument('--pnpm-script', type=Path, required=True)
    args = parser.parse_args()
    try: result = build(args.source.resolve(strict=True), args.node.resolve(strict=True), args.pnpm_script.resolve(strict=True))
    except Exception:
        print(json.dumps({'result':'FAIL', 'code':'LOG_PORTAL_BUILD_INVALID'})); return 1
    print(json.dumps(result)); return 0


if __name__ == '__main__': raise SystemExit(main())
