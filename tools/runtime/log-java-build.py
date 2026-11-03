"""Trusted original-build adapter. No registration credentials or runtime authority."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

spec = importlib.util.spec_from_file_location('log_build_manifest', Path(__file__).with_name('log-build-manifest.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
COMPONENTS = frozenset(('document-management','knowledge-base','knowledge-search','browser-bff'))
RESOURCE = 'META-INF/dkp-logging/manifest.json'


def require(value):
    if not value: raise ValueError('LOG_JAVA_BUILD_INVALID')


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result); result[key] = value
    return result


def run(root, java_home, directory, tasks, log_name):
    command = ['/bin/sh',str(root/'gradlew'),'--no-daemon','--max-workers=2',
        '-PdkpLogBuildDirectory='+str(directory), *tasks]
    # Compiler output stays private; it is never turned into log event content.
    path = directory/log_name
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        result = subprocess.run(command, cwd=root, env={**os.environ,'JAVA_HOME':str(java_home)},
            stdout=stream, stderr=subprocess.STDOUT, timeout=900)
    require(result.returncode == 0)


def verify_jar(path, manifest):
    require(not path.is_symlink() and path.is_file() and path.stat().st_size <= 64*1024*1024)
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) <= 4096 and names.count(RESOURCE) == 1)
        require(archive.getinfo(RESOURCE).file_size <= m.MAX_MANIFEST)
        with archive.open(RESOURCE) as stream: raw = stream.read(m.MAX_MANIFEST+1)
        require(raw == m.canonical(manifest))


def build(root, component, java_home):
    root = Path(root); java_home = Path(java_home)
    require(component in COMPONENTS and root.is_absolute() and root.resolve(strict=True) == root)
    require(java_home.is_absolute() and java_home.resolve(strict=True) == java_home
        and (java_home/'bin/java').is_file() and (java_home/'bin/javac').is_file())
    prepared = m.prepare(root, component)
    prepared_path = m.write_output(root, component, prepared['buildId'], 'prepared.json', prepared)
    directory = prepared_path.parent
    prefix = ':services:'+component+':bootstrap:'
    run(root, java_home, directory, [prefix+'compileJava',prefix+'recordDevelopmentLogBuild'], 'compile.private.log')
    record_path = directory/'build-record.json'
    require(not record_path.is_symlink() and record_path.is_file() and record_path.stat().st_size <= 65536)
    record = json.loads(record_path.read_bytes(), object_pairs_hook=unique)
    require(type(record) is dict and record.keys() == {'schemaVersion','component','toolchain','dependencies'}
        and type(record['schemaVersion']) is int and record['schemaVersion'] == 1 and record['component'] == component)
    m.dependencies(record['toolchain']); m.dependencies(record['dependencies'])
    require({row['name'] for row in record['toolchain']} == {'java','gradle'}
        and len(record['toolchain']) == 2)
    java = next(row['version'] for row in record['toolchain'] if row['name'] == 'java')
    gradle = next(row['version'] for row in record['toolchain'] if row['name'] == 'gradle')
    require(re.fullmatch(r'25\.0\.5\+8(?:-LTS)?', java) is not None)
    wrapper = (root/'gradle/wrapper/gradle-wrapper.properties').read_text()
    require('distributionUrl=https\\://services.gradle.org/distributions/gradle-'+gradle+'-bin.zip' in wrapper.splitlines())
    vocabulary = {'events':sorted(m.EVENTS),'phases':['STARTUP','SHUTDOWN'],'errorCodes':[],
        'dependencies':[],'exceptionClasses':[],'frames':[]}
    finalized = m.finalize(root, prepared, vocabulary, record['toolchain'], record['dependencies'])
    m.write_output(root, component, prepared['buildId'], 'finalized.json', finalized)
    manifest_path = m.write_output(root, component, prepared['buildId'], 'manifest.json', finalized['manifest'])
    run(root, java_home, directory, [prefix+'installDist'], 'package.private.log')
    jars = list((root/'services'/component/'bootstrap/build/libs').glob('*.jar'))
    require(len(jars) == 1)
    verify_jar(jars[0], finalized['manifest'])
    receipt = m.seal(root, prepared, finalized, jars[0])
    receipt_path = m.write_output(root, component, prepared['buildId'], 'receipt.json', receipt)
    return {'result':'BUILT','component':component,'buildId':prepared['buildId'],
        'manifest':str(manifest_path),'manifestSha256':hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        'receipt':str(receipt_path),'artifact':str(jars[0]),'sharedStoreDelivery':'NOT_STARTED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--component', choices=sorted(COMPONENTS), required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    args = parser.parse_args()
    try: result = build(args.source, args.component, args.java_home)
    except Exception:
        print(json.dumps({'result':'FAIL','code':'LOG_JAVA_BUILD_INVALID'})); return 1
    print(json.dumps(result)); return 0


if __name__ == '__main__': raise SystemExit(main())
