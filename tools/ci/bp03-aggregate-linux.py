#!/usr/bin/env python3
"""Shared #222 isolated Linux BFF controller; no application stack or Docker socket mount."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import re
import tempfile
import uuid
import xml.etree.ElementTree as ET
import verify as common

PIN = 'eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507'
PROFILE = 'bp03-aggregate-linux'
COMMAND = ['bash', 'tools/ci/bp03-aggregate-check.sh']
LAYERS = ('bootstrap', 'adapters-out', 'application')
FLOORS = (
    ('bootstrap', 'IngressAdmission'), ('bootstrap', 'PinnedIngressSettlement'),
    ('bootstrap', 'ingress'), ('bootstrap', 'liveReadback'),
    ('bootstrap', 'metadataBody'), ('adapters-out', 'transferLength'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def runner_binding(source):
    for name in ('bp03-aggregate-linux.py', 'verify.py'):
        if digest(Path(__file__).with_name(name)) != digest(source / 'tools' / 'ci' / name):
            raise ValueError('Executed controller must match verified source')
    dockerfile = source / 'tools/ci/bp03-aggregate.Dockerfile'
    if dockerfile.read_text().splitlines()[0] != 'FROM ' + PIN:
        raise ValueError('Declared Java pin must match executed Dockerfile')


def owned(identifier, run_id, image):
    item = common.inspect('container', identifier)
    if (item['Id'] != identifier or item['Image'] != image
            or item['Config']['Labels'].get('dkp.check.run') != run_id
            or item['Config']['Labels'].get('dkp.issue') != '222'):
        raise ValueError('Exact owned verification container required')
    return item


def results(source, evidence):
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    for layer in LAYERS:
        files = list((evidence / 'reports' / layer / 'test-results' / 'test').glob('TEST-*.xml'))
        if not files:
            raise ValueError('Missing affected-layer test results')
        for path in files:
            suite = ET.parse(path).getroot()
            if int(suite.attrib['tests']) != len(suite.findall('testcase')) or not suite.findall('testcase'):
                raise ValueError('Complete nonempty JUnit suite required')
            for name in counts:
                counts[name] += int(suite.attrib[name])
            if suite.findall('.//skipped') or suite.findall('.//failure') or suite.findall('.//error'):
                raise ValueError('Failed or skipped affected test')
    if any(counts[name] for name in ('failures', 'errors', 'skipped')):
        raise ValueError('Complete passing affected tests required')
    coverage = {}
    for layer, name in FLOORS:
        report = name + 'CoverageReport'
        root = ET.parse(evidence / 'reports' / layer / 'reports' / 'jacoco' / report / (report + '.xml')).getroot()
        counters = {row.attrib['type']: row.attrib for row in root.findall('counter')}
        coverage[name] = {}
        for kind in ('LINE', 'BRANCH'):
            row = counters[kind]
            covered, missed = int(row['covered']), int(row['missed'])
            if covered < 0 or missed < 0 or covered + missed <= 0 or covered / (covered + missed) < .75:
                raise ValueError('Affected source coverage floor failed')
            coverage[name][kind] = dict(covered=covered, missed=missed)
    entrypoint = source / 'services/browser-bff/bootstrap/src/test/java/org/documentknowledge/browserbff/bootstrap/IngressLinuxAcceptance.java'
    expected = re.findall(r'run\("([^"\n]+)"', entrypoint.read_text())
    cases, terminals = [], []
    for line in (evidence / 'check.private.log').read_text().splitlines():
        if line.startswith('{"case":'):
            row = json.loads(line)
            if row.get('result') != 'PASS':
                raise ValueError('Linux ingress case failed')
            cases.append(row['case'])
        elif line.startswith('{"profile":"dkp338-linux-ingress-component"'):
            terminals.append(json.loads(line))
    if (len(expected) < 32 or len(set(expected)) != len(expected) or cases != expected
            or terminals != [dict(profile='dkp338-linux-ingress-component', passed=len(expected), failed=0, skipped=0)]):
        raise ValueError('Exact complete Linux ingress profile required')
    return dict(counts=counts, coverage=coverage, linuxCases=cases)


def run(source, evidence):
    source, evidence = Path(source), Path(evidence)
    if not evidence.is_absolute() or evidence.exists() or evidence.parent.resolve(strict=True) != evidence.parent:
        raise ValueError('Fresh canonical private evidence directory required')
    evidence.mkdir(mode=0o700)
    identifier, image, run_id = None, None, uuid.uuid4().hex
    record = dict(schemaVersion=1, profile=PROFILE, result='FAIL', stage='PREFLIGHT', cleanup='NOT_CREATED',
                  pin=PIN, command=COMMAND, source=None, imageId=None, results=None,
                  operatorPython=platform.python_version(), runId=run_id)
    try:
        common.local_daemon()
        runner_binding(source)
        with tempfile.TemporaryDirectory(prefix='dkp222-public-') as temporary:
            snapshot = Path(temporary)
            record['source'] = common.snapshot(source, snapshot)
            record['stage'] = 'BUILD'
            tag = 'dkp222-aggregate-' + run_id
            if common.logged(['docker', 'build', '--platform', 'linux/amd64', '--tag', tag,
                              '--file', str(snapshot / 'tools/ci/bp03-aggregate.Dockerfile'), str(snapshot)],
                             evidence / 'build.private.log', 900):
                raise ValueError('Pinned BFF image build failed')
            metadata = common.inspect('image', tag)
            image = metadata['Id']
            if not re.fullmatch('sha256:[a-f0-9]{64}', image) or metadata['Os'] != 'linux' or metadata['Architecture'] != 'amd64':
                raise ValueError('Exact pinned Linux image required')
            record.update(imageId=image, stage='CHECK')
            # A failed/timed-out create may have created a resource without returning its ID.
            # Retain UNKNOWN unless an exact identity can be inspected and cleaned safely.
            record['cleanup'] = 'UNKNOWN'
            identifier = common.command(['docker', 'create', '--platform', 'linux/amd64', '--label', 'dkp.issue=222',
                '--label', 'dkp.check.run=' + run_id, '--network', 'none', '--user', '10001:0',
                '--memory', '2g', '--cpus', '2', '--pids-limit', '256', '--cap-drop', 'ALL',
                '--security-opt', 'no-new-privileges:true', image, *COMMAND]).decode().strip()
            if not re.fullmatch('[a-f0-9]{64}', identifier):
                identifier = None
                raise ValueError('Exact created container ID required')
            record['cleanup'] = 'RETAINED'
            owned(identifier, run_id, image)
            status = common.logged(['docker', 'start', '--attach', identifier], evidence / 'check.private.log', 900)
            state = owned(identifier, run_id, image)['State']
            for layer in LAYERS:
                destination = evidence / 'reports' / layer
                destination.mkdir(parents=True)
                for what in ('test-results', 'reports') if layer != 'application' else ('test-results',):
                    common.command(['docker', 'cp', identifier + ':/workspace/services/browser-bff/' + layer + '/build/' + what,
                                    str(destination)])
            if status or state['Running'] or state['ExitCode']:
                raise ValueError('Affected BFF check failed')
            if 'openjdk version "25.0.5"' not in (evidence / 'check.private.log').read_text():
                raise ValueError('Pinned executed Java version required')
            record['results'] = results(source, evidence)
            if common.snapshot(source) != record['source']:
                raise ValueError('Source changed during check')
            record.update(result='PASS', stage='COMPLETE')
    finally:
        if identifier is not None:
            try:
                owned(identifier, run_id, image)
                common.command(['docker', 'rm', '--force', identifier])
                record['cleanup'] = 'REMOVED'
            except Exception:
                record.update(result='FAIL', cleanup='UNKNOWN')
        record['logs'] = {p.name: digest(p) for p in evidence.glob('*.private.log')}
        (evidence / 'receipt.json').write_text(json.dumps(record, indent=2) + '\n')
        for path in evidence.rglob('*'):
            if path.is_file():
                path.chmod(0o600)
    return record


def validate(source, evidence):
    source, evidence = Path(source), Path(evidence)
    runner_binding(source)
    record = json.loads((evidence / 'receipt.json').read_text())
    if (record['schemaVersion'] != 1 or record['profile'] != PROFILE or record['result'] != 'PASS'
            or record['stage'] != 'COMPLETE' or record['cleanup'] != 'REMOVED' or record['pin'] != PIN
            or record['command'] != COMMAND or record['source'] != common.snapshot(source)):
        raise ValueError('Current passing exact-source receipt required')
    if not re.fullmatch('sha256:[a-f0-9]{64}', record['imageId']) or record['results'] != results(source, evidence):
        raise ValueError('Executed image/results changed')
    if record['logs'] != {p.name: digest(p) for p in evidence.glob('*.private.log')}:
        raise ValueError('Executed logs changed')
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('action', choices=('run', 'validate'))
    parser.add_argument('--source', required=True)
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    try:
        record = run(args.source, args.evidence) if args.action == 'run' else validate(args.source, args.evidence)
        if record['result'] != 'PASS' or record['cleanup'] != 'REMOVED':
            raise ValueError('Check and owned cleanup must pass')
        print(json.dumps(dict(profile=PROFILE, result='PASS', sourceFingerprint=record['source']['fingerprint'])))
    except (Exception, KeyboardInterrupt):
        print(json.dumps(dict(profile=PROFILE, result='FAIL')))
        raise SystemExit(1)


if __name__ == '__main__':
    main()
