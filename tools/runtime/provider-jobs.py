#!/usr/bin/env python3
"""Issue305 EXCLUSIVE owned provider acceptance. No default/adoption/foreign cleanup.

--directory <fresh ignored child> --runtime-directory <ready dedicated137run>
Requires two free admission slots. Source/config+fixed dispatch prove executing1;
client SUBMITTED/CANCELED markers never assert remote engine settlement. Root must
reserve the runtime before invoking. All outputs are finite; private files retain
synthetic IDs/hashes only. Standard definitions and provider security stay intact.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
def module(name):
    spec = importlib.util.spec_from_file_location('jobs_' + name, Path(__file__).with_name(name + '.py'))
    value = importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

d = module('upload-durability');runtime = d.runtime
NODE_IMAGE = 'node:24.22.1-bookworm-slim@sha256:be8204d4bd327363357f532187b752ff62759c31bedf98e9e18fc08c9cbd605c'
CLAM_IMAGE = 'clamav/clamav:1.5.4@sha256:bda39705e291105049c3936e2803ac90b955172af8030f94f05dce1252df6c6d'
# Reviewed primary source: https://github.com/Cisco-Talos/clamav/tree/clamav-1.5.4/clamd
# terminal INSTREAM -> dispatch_command -> shared thrpool; mutex caps thr_alive.
# This is enforcement composition, not remotely observed scan entry/exit.
DISPATCH_PROOF = {'tag': 'clamav-1.5.4', 'sha256': {'server-th.c': '708e7e2ca90e9aaae853caea8dddae16a3fd12d5a85e2f1f73ae10f62ed37aef', 'session.c': 'cab9cc2b7fe9c623f6112875ba0171542be8edb71dee3682f3f55ef527ef71c7', 'thrmgr.c': '27575374e4676b65f73f2559b8c2a1af784b8f9bcafaf04de9ffe2cd78b3a13e', 'scanner.c': '03f6ff00e5cdf42e3f758c9717637f2f7c913cbc03b0b674a6a71c3a40332ccb'}}
SOURCES = ('tools/runtime/provider-jobs.py', 'tools/runtime/provider-job-probe.mjs',
           'tools/runtime/identity-e2e.mjs', 'apps/admin-portal/e2e-real/upload-job-boundary.spec.ts',
           'tools/runtime/upload-durability.py', 'apps/admin-portal/e2e-real/upload-job-fixture.ts',
           'tools/runtime/provider-job-control-acceptance.py')
UUID = re.compile('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}')


def closed(value, keys):
    if not isinstance(value, dict) or set(value) != set(keys):raise ValueError('Closed acceptance record required')
    return value


def fixture(value):
    closed(value, ('tenant', 'sizeBytes', 'sha256', 'first', 'second'))
    if value['tenant'] not in ('tenant-a', 'tenant-b') or type(value['sizeBytes']) is not int or not 1 <= value['sizeBytes'] <= 100000000 or not isinstance(value['sha256'], str) or not re.fullmatch('[a-f0-9]{64}', value['sha256']):raise ValueError('Bounded synthetic fixture required')
    for name in ('first', 'second'):
        artifact = closed(value[name], ('documentId', 'uploadId', 'completeKey'))
        if any(not isinstance(v, str) or not UUID.fullmatch(v) for v in artifact.values()):raise ValueError('Canonical synthetic identity required')
    if value['first']['documentId'] == value['second']['documentId'] or value['first']['uploadId'] == value['second']['uploadId']:raise ValueError('Different real admissions required')
    return value


def bound(state):
    current = runtime.load(Path(state['directory']))
    if current != state:raise ValueError('Recorded runtime changed')
    runtime.helper('applications').attest(runtime.application_runtime(), state)


def provider(state, service):
    bound(state)
    return d.container(state, service)


def attest_scanner(state):
    target = provider(state, 'clamav');item = runtime.docker('inspect', target)[0]
    host, config = item['HostConfig'], item['Config']
    image = runtime.docker('image', 'inspect', CLAM_IMAGE)[0]['Id']
    mounts = {v['Destination']: v for v in item['Mounts']}
    if (item['Image'] != image or config['Image'] != CLAM_IMAGE or config['User'] != '100:101'
            or config['Entrypoint'] != ['clamd'] or config['Cmd'] != ['--config-file=/run/dkp/clamd.conf']
            or not host['ReadonlyRootfs'] or host.get('Privileged') or host.get('CapAdd') or host.get('CapDrop') != ['ALL']
            or host.get('SecurityOpt') not in (['no-new-privileges:true'], ['no-new-privileges'])
            or host['Memory'] != 4294967296 or host['NanoCpus'] != 2000000000 or host['PidsLimit'] != 64
            or host['Tmpfs'] != {'/tmp': 'uid=100,gid=101,mode=0700,size=1g'}
            or set(mounts) != {'/var/lib/clamav', '/run/dkp/clamd.conf'}
            or any(v['RW'] for v in mounts.values())
            or mounts['/var/lib/clamav']['Source'] != state['definitions']
            or mounts['/run/dkp/clamd.conf']['Source'] != str(ROOT / 'infra/docker-compose/clamd.conf')):
        raise ValueError('Exact one pinned isolated scanner required')
    runtime.verify_generation(state)
    expected = state['sources'].get('infra/docker-compose/clamd.conf')
    result = runtime.command(['docker', 'exec', '--user', '100:101', target, 'sha256sum', '/run/dkp/clamd.conf'])
    if len(result) > 256 or result.decode().strip() != expected + '  /run/dkp/clamd.conf':raise ValueError('Live read-only scanner config differs')
    directives = dict(line.split(None, 1) for line in (ROOT / 'infra/docker-compose/clamd.conf').read_text().splitlines() if line and not line.startswith('#'))
    if directives.get('MaxThreads') != '1' or directives.get('MaxQueue') != '2':raise ValueError('Reviewed finite scanner bound required')
    return target


# The browser runs as pwuser UID1001. No host authority/control bind is mounted.
# Exact-owner Docker exec bridges bounded finite records in its private tmpfs.
CONTROL_SCRIPT = r"""
const fs=require('node:fs');
const directory='/tmp/dkp-job-boundary';
const operation=process.argv[1],name=process.argv[2];
const names=operation==='read'?['prepared','submitted','aborted','completed']:operation==='write'?['begin','abort-now','restored','finish']:[];
if(process.getuid()!==1001||!names.includes(name))throw Error('closed bridge');
let info;try{info=fs.lstatSync(directory);}catch(error){if(operation==='read'&&error.code==='ENOENT'){process.stdout.write('null');process.exit(0);}throw error;}
if(!info.isDirectory()||info.isSymbolicLink()||info.uid!==1001||(info.mode&511)!==448)throw Error('private directory');
const file=directory+'/'+name+'.json';
if(operation==='write'){
 const fd=fs.openSync(file,fs.constants.O_CREAT|fs.constants.O_EXCL|fs.constants.O_WRONLY|fs.constants.O_NOFOLLOW,384);
 try{fs.writeFileSync(fd,JSON.stringify({schemaVersion:1,checkpoint:name}));fs.fsyncSync(fd);}finally{fs.closeSync(fd);}
 process.stdout.write('{"written":true}');
}else{
 let stat;try{stat=fs.lstatSync(file);}catch(error){if(error.code==='ENOENT'){process.stdout.write('null');process.exit(0);}throw error;}
 if(!stat.isFile()||stat.isSymbolicLink()||stat.uid!==1001||(stat.mode&511)!==384||stat.size>8192)throw Error('private record');
 process.stdout.write(JSON.stringify(JSON.parse(fs.readFileSync(file,'utf8'))));
}
"""


def exchange(work, operation, name):
    choices = ('begin','abort-now','restored','finish') if operation=='write' else ('prepared','submitted','aborted','completed') if operation=='read' else ()
    if name not in choices:raise ValueError('Closed private bridge required')
    current=browser_instance(work)
    if current is None:
        if operation=='read':return None
        raise ValueError('Exact owned browser required before control write')
    output=runtime.command(['docker','exec','--user','1001:1001',current[0],'node','-e',CONTROL_SCRIPT,operation,name],timeout=5)
    if len(output)>8192:raise ValueError('Bounded private bridge required')
    value=json.loads(output)
    if operation=='write' and value!={'written':True}:raise ValueError('Private control write failed')
    return value


def write_marker(work, name):
    return exchange(work,'write',name)




def wait(operation, seconds=10):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = operation()
        if value is not None:return value
        time.sleep(.05)
    raise ValueError('Observed checkpoint deadline exceeded')


def marker(work, name):
    return exchange(work,'read',name)


def line(process, seconds=130):
    end = time.monotonic() + seconds;value = b''
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        while b'\n' not in value:
            remaining = end - time.monotonic()
            if remaining <= 0 or not selector.select(remaining):raise ValueError('Probe output deadline exceeded')
            part = process.stdout.read1(8193 - len(value))
            if not part:raise ValueError('Complete probe output required')
            value += part
            if len(value) > 8192:raise ValueError('Bounded probe output required')
    if value.count(b'\n') != 1 or not value.endswith(b'\n'):raise ValueError('One closed probe line required')
    return json.loads(value)


def probe(state, work, phase):
    if phase not in ('scanner', 'inspector'):raise ValueError('Closed provider probe required')
    attest_scanner(state)
    target = provider(state, 'structure-inspector')
    runtime.attest_worker(state, runtime.docker('inspect', target)[0])
    network = runtime.helper('storage').owned_network(state['project'] + '_upload-data', 'https://rustfs:9000')
    image_info = runtime.docker('image', 'inspect', NODE_IMAGE)[0]
    image = image_info['Id']
    run_id = uuid.uuid4().hex
    args = ['docker', 'create', '--interactive', '--label', 'dkp.issue=305', '--label', 'dkp.jobs.run=' + run_id,
            '--user', str(os.getuid()) + ':' + str(os.getgid()), '--network', network,
            '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
            '--memory', '128m', '--cpus', '.25', '--pids-limit', '32', '--mount',
            'type=bind,src=' + str(ROOT / 'tools/runtime/provider-job-probe.mjs') + ',dst=/run/dkp/probe.mjs,readonly']
    if phase == 'inspector':
        for source, destination in [('ca.crt', 'ca.crt'), ('service.key', 'service.key')]:
            args += ['--mount', 'type=bind,src=' + str(Path(state['directory']) / 'inspector/app' / source) + ',dst=/run/dkp/' + destination + ',readonly']
    args += ['--entrypoint', 'node', NODE_IMAGE, '/run/dkp/probe.mjs', phase]
    identifier = runtime.command(args).decode().strip()
    if not re.fullmatch('[a-f0-9]{64}', identifier):raise ValueError('Owned probe container identity required')
    def own():
        return attest_probe(runtime.docker('inspect', identifier)[0], identifier, image, run_id, network, phase, state, image_info['Config']['Env'])
    process = None
    try:
        own();bound(state)
        log = work / (phase + '.private.log');runtime.private(log, '')
        with log.open('wb') as stream:
            process = subprocess.Popen(['docker', 'start', '--attach', '--interactive', identifier], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stream)
            if phase == 'inspector':
                process.stdin.write((runtime.PROFILE + '\n').encode());process.stdin.flush()
                for checkpoint, count in [('PARTIAL_BODY_SUBMITTED', 1), ('PARTIAL_CLIENT_CANCELED', 0)]:
                    value = line(process)
                    if value != {'schemaVersion': 1, 'phase': 'inspector', 'checkpoint': checkpoint}:raise ValueError('Exact provider gate required')
                    wait(lambda: observed_spool(state, count))
                    process.stdin.write(b'CONTINUE\n');process.stdin.flush()
            value = line(process)
            probe_result(value, phase)
            process.stdin.close()
            if process.wait(timeout=10) or own()['State']['ExitCode'] != 0:raise ValueError('Probe did not complete')
            attest_scanner(state)
            return value
    finally:
        if process is not None:stop_process(process)
        own();bound(state);runtime.command(['docker', 'rm', '--force', identifier])


def attest_probe(item, identifier, image, run_id, network, phase, state, expected_env=()):
    config, host = item['Config'], item['HostConfig']
    expected_mounts = {'/run/dkp/probe.mjs': str(ROOT / 'tools/runtime/provider-job-probe.mjs')}
    if phase == 'inspector':
        expected_mounts.update({ '/run/dkp/' + name: str(Path(state['directory']) / 'inspector/app' / name) for name in ('ca.crt', 'service.key') })
    mounts = item['Mounts']
    if (item['Id'] != identifier or item['Image'] != image or config['Image'] != NODE_IMAGE
            or config['Labels'].get('dkp.issue') != '305' or config['Labels'].get('dkp.jobs.run') != run_id
            or config['User'] != str(os.getuid()) + ':' + str(os.getgid()) or config['Entrypoint'] != ['node']
            or config['Cmd'] != ['/run/dkp/probe.mjs', phase] or config.get('Env', []) != list(expected_env) or host['NetworkMode'] != network
            or host.get('PidMode') not in ('', None) or host.get('IpcMode') not in ('private', '', None) or host.get('UTSMode') not in ('', None) or host.get('Devices') or host.get('DeviceRequests') or host.get('Binds')
            or not host['ReadonlyRootfs'] or host.get('Privileged') or host.get('CapAdd') or host['CapDrop'] != ['ALL']
            or host.get('SecurityOpt') not in (['no-new-privileges:true'], ['no-new-privileges'])
            or host['Memory'] != 134217728 or host['NanoCpus'] != 250000000 or host['PidsLimit'] != 32
            or host.get('Tmpfs') or len(mounts) != len(expected_mounts)
            or {v['Destination']: v['Source'] for v in mounts} != expected_mounts
            or any(v['RW'] or v['Type'] != 'bind' for v in mounts)):
        raise ValueError('Exact isolated owned probe required')
    return item


def probe_result(value, phase):
    expected = {'schemaVersion': 1, 'phase': phase, 'result': 'PASS', 'remoteCancellationAcknowledged': False}
    if phase == 'scanner':
        expected.update(submitted=6, canceled=1, clean=2, eicar=3, executionObservation='CONFIG_AND_PINNED_DISPATCH_PROOF')
        closed(value, (*expected, 'before', 'after'))
        for name in ('before', 'after'):
            pool = closed(value[name], ('maxThreads', 'observedQueue'))
            if type(pool['maxThreads']) is not int or pool['maxThreads'] != 1 or type(pool['observedQueue']) is not int or not 0 <= pool['observedQueue'] <= 2:raise ValueError('Exact finite pool evidence required')
    else:
        expected.update(busyStatus=503, recoveryStatus=200, admittedObservation='PRIVATE_SPOOL')
        closed(value, expected)
    if any(type(value[k]) is not type(v) or value[k] != v for k, v in expected.items()):raise ValueError('Real closed provider result required')
    return value


def observed_spool(state, expected):
    value = spool_count(state)
    return value if value == expected else None


def spool_count(state):
    target = provider(state, 'structure-inspector')
    result = runtime.command(['docker', 'exec', '--user', '10001:10001', target, 'sh', '-c', "find /tmp -mindepth 1 -maxdepth 1 -type d -name 'inspection-*' | wc -l"])
    if result.strip() not in (b'0', b'1'):raise ValueError('Private inspection admission bound violated')
    return int(result.strip())


def no_forbidden_facts(state, value):
    # All literals have passed the closed canonical fixture validator. Never print rows.
    fixture(value);a = value['first'];tenant = value['tenant']
    where = "tenant_id='" + tenant + "' AND upload_id='" + a['uploadId'] + "'"
    query = "SELECT (SELECT count(*) FROM dm.upload_slots WHERE " + where + " AND document_id='" + a['documentId'] + "' AND state IN ('VERIFYING','QUARANTINED') AND quota_held AND NOT quarantine_cleanup_confirmed AND transient_reservation=2*declared_size AND declared_size=" + str(value['sizeBytes']) + " AND actual_size=declared_size AND body_sha256='" + value['sha256'] + "' AND quarantine_version_id IS NOT NULL AND possible_original_key IS NULL AND possible_original_version_id IS NULL)=1 AND NOT EXISTS(SELECT 1 FROM dm.upload_receipts WHERE " + where + ") AND NOT EXISTS(SELECT 1 FROM dm.upload_verification_facts WHERE " + where + ") AND NOT EXISTS(SELECT 1 FROM dm.versions WHERE tenant_id='" + tenant + "' AND document_id='" + a['documentId'] + "') AND NOT EXISTS(SELECT 1 FROM dm.upload_receipt_consumptions WHERE tenant_id='" + tenant + "' AND document_id='" + a['documentId'] + "') AND NOT EXISTS(SELECT 1 FROM dm.upload_deletion_fences WHERE " + where + ");"
    if d.sql(state, query) != 't' or d.sql(state, d.BALANCES) != 't':raise ValueError('Canceled/restarted work created forbidden facts or lost full charge')
    accounting = "SELECT (SELECT count(*) FROM dm.upload_slots)=2 AND (SELECT count(*) FROM dm.upload_resource_accounts)=1 AND EXISTS(SELECT 1 FROM dm.upload_resource_accounts a CROSS JOIN (SELECT count(*) FILTER(WHERE quota_held) AS admitted,coalesce(sum(transient_reservation),0) AS transient,coalesce(sum(CASE WHEN NOT EXISTS(SELECT 1 FROM dm.upload_receipts r WHERE r.tenant_id=s.tenant_id AND r.upload_id=s.upload_id) THEN declared_size ELSE 0 END),0) AS promised,coalesce(sum(CASE WHEN EXISTS(SELECT 1 FROM dm.upload_receipts r WHERE r.tenant_id=s.tenant_id AND r.upload_id=s.upload_id) THEN declared_size ELSE 0 END),0) AS retained FROM dm.upload_slots s WHERE tenant_id='" + tenant + "') t WHERE a.tenant_id='" + tenant + "' AND a.admitted_count=t.admitted AND a.reserved_transient_bytes=t.transient AND a.promised_original_bytes=t.promised AND a.retained_original_bytes=t.retained);"
    if d.sql(state, accounting) != 't':raise ValueError('Exact per-slot tenant charge accounting required')
    query = "SELECT encode(sha256(convert_to(jsonb_build_array(tenant_id,upload_id,document_id,actor_issuer,actor_subject,created_at,expires_at,expected_document_revision,declared_format,declared_size,body_idempotency_key,body_sha256,actual_size,quarantine_bucket,quarantine_key,quarantine_version_id)::text,'UTF8')),'hex') FROM dm.upload_slots WHERE " + where + ";"
    digest = d.sql(state, query)
    if not re.fullmatch('[a-f0-9]{64}', digest):raise ValueError('Exact immutable upload evidence required')
    return digest


def browser_marker(work, name):
    value = marker(work, name)
    if value is None:return None
    if value != {'schemaVersion': 1, 'checkpoint': {'submitted': 'COMPLETE_SUBMITTED', 'aborted': 'BROWSER_ABORTED', 'completed': 'SECOND_VERIFIED'}[name]}:raise ValueError('Exact browser evidence required')
    return value


def free_admissions(state):
    if d.sql(state, "SELECT (SELECT count(*) FROM dm.upload_global_resource_account)=1 AND (SELECT admitted_count=0 AND reserved_transient_bytes=0 AND promised_original_bytes=0 AND retained_original_bytes=0 FROM dm.upload_global_resource_account) AND NOT EXISTS(SELECT 1 FROM dm.upload_slots);") != 't':raise ValueError('Dedicated fresh uncharged runtime required')


def browser_instance(work, expected=None):
    control_digest = hashlib.sha256(str(work / 'control').encode()).hexdigest()
    name = 'dkp-jobs-browser-' + control_digest[:16]
    identifier = runtime.command(['docker', 'ps', '-aq', '--filter', 'name=^/' + name + '$']).decode().strip()
    if not identifier:return None
    if not re.fullmatch('[a-f0-9]{64}', identifier):raise ValueError('One exact owned browser required')
    item = runtime.docker('inspect', identifier)[0]
    labels = item['Config']['Labels']
    if item['Id'] != identifier or item['Name'] != '/' + name or labels.get('dkp.issue') != '305' or labels.get('dkp.jobs.control') != control_digest or item['Config']['Image'] != 'dkp-identity-e2e:local' or (expected is not None and (identifier, item['Image']) != expected):raise ValueError('Foreign browser cleanup refused')
    return identifier, item['Image']


def stop_process(process):
    if process.poll() is None:
        process.terminate()
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)


def dm_boundary(state, work):
    bound(state)
    runtime.private(work / 'input.json', '{"schemaVersion":1}\n')
    log = work / 'browser.private.log';runtime.private(log, '')
    args = ['node', 'tools/runtime/identity-e2e.mjs', '--upload-job-input-file', str(work / 'input.json'), '--security-directory', str(Path(state['directory']) / 'identity'), '--runtime-env-file', str(Path(state['directory']) / 'runtime.env')]
    with log.open('wb') as output:
        browser = subprocess.Popen(args, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, env={k:v for k,v in os.environ.items() if k in ('PATH', 'HOME', 'JAVA_HOME')})
        target = None;stopped = False;browser_identity = None
        try:
            value = fixture(wait(lambda: marker(work, 'prepared'), 180))
            browser_identity = browser_instance(work)
            if browser_identity is None:raise ValueError('Actual owned browser required')
            baseline = d.sql(state, d.SNAPSHOT)
            original_evidence = no_forbidden_facts(state, value)
            write_marker(work, 'begin');wait(lambda: browser_marker(work, 'submitted'), 10)
            def verifying():
                status = d.sql(state, "SELECT state FROM dm.upload_slots WHERE tenant_id='" + value['tenant'] + "' AND upload_id='" + value['first']['uploadId'] + "';")
                if status == 'VERIFYING':return True
                if status != 'QUARANTINED':raise ValueError('Old verifying boundary not exercised')
            wait(verifying, 10)
            write_marker(work, 'abort-now');wait(lambda: browser_marker(work, 'aborted'), 10)
            target = provider(state, 'document-management')
            # Explicit crash, not a graceful drain mistaken for an in-flight restart.
            bound(state);runtime.command(['docker', 'kill', '--signal', 'KILL', target]);stopped = True
            bound(state);runtime.owned(runtime.docker('inspect', target)[0], state)
            runtime.command(['docker', 'start', target]);stopped = False
            runtime.health(state)
            if provider(state, 'document-management') != target:raise ValueError('DM restart changed exact instance')
            if no_forbidden_facts(state, value) != original_evidence:raise ValueError('Restart changed original evidence')
            write_marker(work, 'restored');wait(lambda: browser_marker(work, 'completed'), 180)
            if no_forbidden_facts(state, value) != original_evidence:raise ValueError('New work changed old evidence')
            after = d.sql(state, d.SNAPSHOT)
            if not re.fullmatch('[a-f0-9]{64}', baseline) or not re.fullmatch('[a-f0-9]{64}', after):raise ValueError('Full18table digest required')
            runtime.private(work / 'dm-facts.json', json.dumps({'before18TableSha256': baseline, 'after18TableSha256': after, 'noForbiddenFacts': True, 'accountingBalances': True}))
            write_marker(work, 'finish')
            if browser.wait(timeout=30):raise ValueError('Genuine guarded browser boundary failed')
            return {'phase': 'dm', 'result': 'PASS', 'boundary': 'OBSERVED_VERIFYING_THEN_ABORT_AND_EXACT_PROCESS_CRASH', 'remoteCancellationAcknowledged': False}
        finally:
            try:
                if target is not None and stopped:
                    bound(state);runtime.owned(runtime.docker('inspect', target)[0], state);runtime.command(['docker', 'start', target]);runtime.health(state)
            finally:
                try:
                    current = browser_instance(work, browser_identity)
                    if current is not None:
                        bound(state);runtime.command(['docker', 'rm', '--force', current[0]])
                finally:stop_process(browser)



def run(directory, runtime_directory):
    if os.getuid() == 0:raise ValueError('Nonroot trusted operator required')
    runtime.local_daemon();work = runtime.confined(directory)
    if work.exists():raise ValueError('Fresh acceptance directory required')
    state = runtime.load(runtime_directory)
    bound(state)
    if runtime.verify(runtime_directory) != state:raise ValueError('Original runtime changed')
    free_admissions(state)
    work.mkdir(mode=0o700);(work / 'control').mkdir(mode=0o700)
    hashes = {p: runtime.digest(ROOT / p) for p in SOURCES}
    runtime.private(work / 'sources.json', json.dumps(hashes))
    control_proof = module('provider-job-control-acceptance').run()
    runtime.private(work / 'control-proof.json', json.dumps(control_proof))
    results = []
    for phase in ('scanner', 'inspector'):
        if hashes != {p: runtime.digest(ROOT / p) for p in SOURCES}:raise ValueError('Acceptance source changed')
        results.append(probe(state, work, phase))
    if hashes != {p: runtime.digest(ROOT / p) for p in SOURCES}:raise ValueError('Acceptance source changed')
    results.append(dm_boundary(state, work))
    if hashes != {p: runtime.digest(ROOT / p) for p in SOURCES}:raise ValueError('Acceptance source changed')
    bound(state)
    if runtime.verify(runtime_directory) != state:raise ValueError('Original runtime changed')
    runtime.private(work / 'result.json', json.dumps({'schemaVersion': 1, 'result': 'PASS', 'phases': results, 'directEngineEntryExitObserved': False, 'dispatchProof': DISPATCH_PROOF}))
    return {'schemaVersion': 1, 'result': 'PASS', 'required': 3, 'passed': 3, 'skipped': 0, 'directEngineEntryExitObserved': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__);parser.add_argument('--directory', required=True, type=Path);parser.add_argument('--runtime-directory', required=True, type=Path)
    args = parser.parse_args()
    try:print(json.dumps(run(args.directory, args.runtime_directory)))
    except Exception:raise SystemExit('Owned provider job acceptance failed closed; private evidence retained') from None


if __name__ == '__main__':main()
