#!/usr/bin/env python3
"""Issue340: reproducible public SYSTEM Linux proof; no provider stack or authority input."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[2]
IMAGE = 'eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507'
PROJECTS = ('modules/identity-contracts', 'modules/authorization-catalog', 'services/document-management/domain', 'services/document-management/application', 'services/document-management/adapters-out')
MAIN = 'services/document-management/adapters-out/src/test/java/org/documentknowledge/documentmanagement/adapters/outbound/verificationtemp/SystemVerificationWorkspaceAcceptance.java'
CLASS = 'org.documentknowledge.documentmanagement.adapters.outbound.verificationtemp.SystemVerificationWorkspaceAcceptance'
INIT = '''allprojects { p ->
    afterEvaluate {
        if (p.path == ':services:document-management:adapters-out') {
            tasks.register('verificationSystemClasspath') {
                dependsOn tasks.named('classes')
                doLast {
                    new File(providers.gradleProperty('dkpSystemClasspath').get()).text = groovy.json.JsonOutput.toJson([
                        cache: gradle.gradleUserHomeDir.absolutePath,
                        jars: sourceSets.test.runtimeClasspath.files.collect { it.absolutePath }])
                }
            }
        }
    }
}
'''
RESULT = {'schemaVersion': 1, 'result': 'PASS', 'cases': 14, 'uid': 10001, 'platform': 'Linux', 'publicConstructorOnly': True, 'maxPayloadBytes': 200000000, 'providerAccess': False}


def command(args, timeout=120):
    try:
        result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode or len(result.stdout) > 4_000_000 or len(result.stderr) > 4_000_000:
            raise ValueError('Bounded public proof command failed')
        return result.stdout
    except (OSError, subprocess.TimeoutExpired) as failure:
        raise ValueError('Public proof command unavailable or timed out') from failure


def private(path, value):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'w') as output:
        output.write(value)


def canonical(path):
    if not path.is_absolute() or path.resolve(strict=True) != path:
        raise ValueError('Canonical public input ancestry required')
    return path


def digest(path):
    canonical(path)
    if not path.is_file():
        raise ValueError('Regular public input required')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources():
    canonical(ROOT)
    paths = {ROOT / 'tools/runtime/verification-workspace-system.py', ROOT / 'gradle.properties', ROOT / MAIN, ROOT / 'build.gradle', ROOT / 'settings.gradle', ROOT / 'gradle/libs.versions.toml', ROOT / 'gradle/wrapper/gradle-wrapper.properties', ROOT / 'gradle/wrapper/gradle-wrapper.jar', ROOT / 'gradlew'}
    for project in PROJECTS:
        tree = canonical(ROOT / project / 'src/main/java')
        for entry in tree.rglob('*'):
            canonical(entry)
            if entry.suffix == '.java':
                paths.add(entry)
        for name in ('build.gradle', 'gradle.lockfile'):
            paths.add(ROOT / project / name)
    return {str(p.relative_to(ROOT)): digest(p) for p in sorted(paths)}


def compile_bundle(work):
    before = sources()
    private(work / 'sources.json', json.dumps(before, sort_keys=True) + '\n')
    init = work / 'classpath.init.gradle';private(init, INIT)
    exported = work / 'classpath.json'
    log = command([str(ROOT / 'gradlew'), '-I', str(init), ':services:document-management:adapters-out:verificationSystemClasspath', '-PdkpSystemClasspath=' + str(exported), '--rerun-tasks'], 300)
    private(work / 'compile.log', log.decode('utf8'))
    exported.chmod(0o600)
    data = json.loads(exported.read_text())
    if set(data) != {'cache', 'jars'} or not isinstance(data['jars'], list):
        raise ValueError('Closed public classpath required')
    cache = canonical(Path(data['cache']) / 'caches/modules-2/files-2.1')
    jars = []
    for value in data['jars']:
        path = Path(value)
        if path.is_relative_to(ROOT):
            continue  # Never use potentially stale project jars or test outputs.
        if not path.is_absolute() or not path.is_relative_to(cache) or path.suffix != '.jar':
            raise ValueError('Only locked public Gradle dependency jars allowed')
        digest(path);jars.append(path)
    classes = [ROOT / p / 'build/classes/java/main' for p in PROJECTS]
    if not all(canonical(p).is_dir() for p in classes):
        raise ValueError('Fresh project classes required')
    java_home = Path(os.environ['JAVA_HOME'])
    if command([str(java_home / 'bin/javac'), '-version']).strip() != b'javac 25.0.5':
        raise ValueError('Pinned repository javac25.0.5 required')
    # javac enforces the repository's Java25 class level; the actual runtime is the immutable JDK25 image.
    compiled = work / 'main';compiled.mkdir(mode=0o700)
    command([str(java_home / 'bin/javac'), '-cp', os.pathsep.join(map(str, classes + jars)), '-d', str(compiled), str(ROOT / MAIN)])
    if sources() != before:
        raise ValueError('Source or build input changed during compilation')
    bundle = work / 'public';bundle.mkdir(mode=0o755)
    classpath = []
    for index, path in enumerate([compiled, *classes, *dict.fromkeys(jars)]):
        target = bundle / (str(index) + ('.jar' if path.is_file() else ''))
        if path.is_dir():
            canonical(path)
            for entry in path.rglob('*'):
                canonical(entry)
                if not entry.is_dir():
                    if not entry.is_file() or entry.suffix != '.class':
                        raise ValueError('Only compiled class output allowed')
                    with entry.open('rb') as output:
                        if output.read(4) != b'\xca\xfe\xba\xbe':
                            raise ValueError('Compiled class signature required')
            shutil.copytree(path, target)
        else:
            shutil.copyfile(path, target)
        classpath.append('/opt/proof/' + target.name)
    for path in bundle.rglob('*'):
        path.chmod(0o755 if path.is_dir() else 0o644)
    manifest = {str(p.relative_to(bundle)): digest(p) for p in sorted(bundle.rglob('*')) if p.is_file()}
    private(work / 'classes.json', json.dumps(manifest, sort_keys=True) + '\n')
    return bundle, classpath, before, manifest


def inspect(identifier):
    value = json.loads(command(['docker', 'inspect', identifier]))
    if not isinstance(value, list) or len(value) != 1:
        raise ValueError('One owned proof container required')
    return value[0]


def owned(identifier, name, run_id, image_id, bundle, classpath, image_env):
    value = inspect(identifier);config = value['Config'];host = value['HostConfig']
    mounts = value['Mounts']
    if (value['Id'] != identifier or value['Name'] != '/' + name or value['Image'] != image_id
            or config['Image'] != IMAGE or config['Labels'].get('dkp.issue') != '340' or config['Labels'].get('dkp340.run') != run_id
            or config.get('Env') != image_env or config['User'] != '10001:10001' or config['Entrypoint'] != ['java']
            or config['Cmd'] != ['-Xmx128m', '-XX:ActiveProcessorCount=2', '-cp', ':'.join(classpath), CLASS] or config['WorkingDir'] != '/fixture'
            or host['NetworkMode'] != 'none' or not host['ReadonlyRootfs'] or host.get('Privileged') or host.get('CapAdd') or host['CapDrop'] != ['ALL']
            or host['SecurityOpt'] != ['no-new-privileges:true'] or host['Memory'] != 805306368 or host['NanoCpus'] != 1000000000 or host['PidsLimit'] != 64
            or host['Tmpfs'] != {'/fixture': 'uid=10001,gid=10001,mode=0700,size=384m'}
            or host.get('PidMode') not in ('', None) or host.get('IpcMode') not in ('private', '', None) or host.get('UTSMode') not in ('', None)
            or host.get('Devices') or host.get('DeviceRequests') or len(mounts) != 1
            or mounts[0]['Type'] != 'bind' or mounts[0]['Source'] != str(bundle) or mounts[0]['Destination'] != '/opt/proof' or mounts[0]['RW']):
        raise ValueError('Exact isolated owned proof required')
    return value


def run():
    # Automatic temporary ownership: no caller-provided image/path/command, no security namespace.
    parent = ROOT / 'build'
    if parent.is_symlink():
        raise ValueError('Owned build directory required')
    parent.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='system-workspace-', dir=parent)).resolve();work.chmod(0o700)
    bundle, classpath, before, classes = compile_bundle(work)
    image = json.loads(command(['docker', 'image', 'inspect', IMAGE]))
    if not isinstance(image, list) or len(image) != 1 or not re.fullmatch('sha256:[a-f0-9]{64}', image[0]['Id']):
        raise ValueError('Pinned local JDK image required')
    image_id = image[0]['Id'];image_env = image[0]['Config']['Env'];run_id = uuid.uuid4().hex;name = 'dkp340-system-' + run_id[:12]
    args = ['docker', 'create', '--name', name, '--label', 'dkp.issue=340', '--label', 'dkp340.run=' + run_id, '--user', '10001:10001', '--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '768m', '--cpus', '1', '--pids-limit', '64', '--tmpfs', '/fixture:uid=10001,gid=10001,mode=0700,size=384m', '--mount', 'type=bind,src=' + str(bundle) + ',dst=/opt/proof,readonly', '--workdir', '/fixture', '--entrypoint', 'java', IMAGE, '-Xmx128m', '-XX:ActiveProcessorCount=2', '-cp', ':'.join(classpath), CLASS]
    identifier = command(args).decode().strip()
    if not re.fullmatch('[a-f0-9]{64}', identifier):
        raise ValueError('Exact created container identity required')
    try:
        owned(identifier, name, run_id, image_id, bundle, classpath, image_env)
        if sources() != before:
            raise ValueError('Source changed before execution')
        output = command(['docker', 'start', '--attach', identifier], 90).decode('utf8')
        private(work / 'runtime.log', output)
        value = owned(identifier, name, run_id, image_id, bundle, classpath, image_env)
        lines = output.splitlines()
        if value['State']['Running'] or value['State']['ExitCode'] != 0 or len(lines) != 15 or sum(line.startswith('SYSTEM_CASE_PASS ') for line in lines) != 14 or json.loads(lines[-1]) != RESULT:
            raise ValueError('All14 public SYSTEM cases required')
        if sources() != before or classes != {str(p.relative_to(bundle)): digest(p) for p in sorted(bundle.rglob('*')) if p.is_file()}:
            raise ValueError('Executed source/class binding changed')
        private(work / 'result.json', json.dumps({**RESULT, 'image': IMAGE, 'imageId': image_id, 'containerId': identifier, 'sourceDigest': hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(), 'classDigest': hashlib.sha256(json.dumps(classes, sort_keys=True).encode()).hexdigest()}) + '\n')
        return RESULT
    finally:
        owned(identifier, name, run_id, image_id, bundle, classpath, image_env)
        command(['docker', 'rm', '--force', identifier])


if __name__ == '__main__':
    argparse.ArgumentParser(description=__doc__).parse_args()
    try:
        print(json.dumps(run()))
    except Exception:
        raise SystemExit('SYSTEM Linux workspace proof failed closed; private build evidence retained') from None
