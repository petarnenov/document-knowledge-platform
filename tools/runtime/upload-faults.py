#!/usr/bin/env python3
"""Issue166 authenticated deployed faults. EXCLUSIVE fresh-runtime window required.

python3 tools/runtime/upload-faults.py --directory <fresh ignored child>
    --runtime-directory <ready137run>
Runs genuine OIDC/HTTPS phases with private synthetic-ID inputs, both receipt
states, five exact owned provider faults, full18-table read-only equality and
explicit failure/recovery accounting deltas. Never edits business rows or clocks.
Does not adopt/resume failed attempts. No arbitrary command/URL/profile arguments.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('fault_durability', Path(__file__).with_name('upload-durability.py'))
d = importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(d)
SOURCES = ('tools/runtime/upload-faults.py', 'tools/runtime/upload-durability.py',
           'tools/runtime/identity-e2e.mjs', 'apps/admin-portal/e2e-real/upload-faults.spec.ts',
           'apps/admin-portal/e2e-real/upload-fault-contract.ts')
PHASES = ('seed', 'verify', 'readonly-outage', 'candidate', 'candidate-outage', 'recover', 'consume')
ARTIFACT_KEYS = {'documentId', 'uploadId', 'receiptId', 'versionId', 'completeKey', 'appendKey'}
UUID = re.compile(r'[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}')
MARKER = 'DKP_UPLOAD_FAULT_RESULT '


def valid_fixture(value):
    if (not isinstance(value, dict) or set(value) != {'tenant', 'sha256', 'sizeBytes', 'consumed', 'unconsumed', 'candidate'}
            or value['tenant'] not in ('tenant-a', 'tenant-b') or not isinstance(value['sha256'],str) or not re.fullmatch('[a-f0-9]{64}', value['sha256'])
            or type(value['sizeBytes']) is not int or not 1 <= value['sizeBytes'] <= 100000000):
        raise ValueError('Closed synthetic fixture required')
    for name in ('consumed', 'unconsumed', 'candidate'):
        artifact = value[name]
        if artifact is None and name == 'candidate':continue
        if not isinstance(artifact, dict) or set(artifact) != ARTIFACT_KEYS:raise ValueError('Closed synthetic artifact required')
        for key, item in artifact.items():
            if item is None and key in ('receiptId', 'versionId'):continue
            if not isinstance(item, str) or not UUID.fullmatch(item):raise ValueError('Canonical synthetic UUID required')
        if name != 'candidate' and artifact['receiptId'] is None:raise ValueError('Verified receipt required')
        if name == 'consumed' and artifact['versionId'] is None:raise ValueError('Consumed original required')
    return value


def result(output, phase, previous):
    lines = [line.strip()[len(MARKER):] for line in output.decode('utf8').splitlines() if line.strip().startswith(MARKER)]
    if len(lines) != 1:raise ValueError('Exactly one real browser result required')
    value = json.loads(lines[0])
    if not isinstance(value, dict) or set(value) != {'phase', 'result', 'fixture'} or value['phase'] != phase or value['result'] != 'PASS':raise ValueError('Closed real browser PASS required')
    fixture = valid_fixture(value['fixture'])
    if phase in ('seed','verify','readonly-outage','candidate','candidate-outage','recover') and fixture['unconsumed']['versionId'] is not None:raise ValueError('Unconsumed fixture was consumed early')
    if phase == 'candidate' and (fixture['candidate'] is None or fixture['candidate']['receiptId'] is not None or fixture['candidate']['versionId'] is not None):raise ValueError('Fresh quarantined candidate required')
    if phase == 'recover' and (fixture['candidate'] is None or fixture['candidate']['receiptId'] is None or fixture['candidate']['versionId'] is None):raise ValueError('Consumed retry result required')
    if phase == 'consume' and fixture['unconsumed']['versionId'] is None:raise ValueError('Final consumption required')
    if previous is not None:
        expected = copy.deepcopy(previous)
        if phase == 'candidate':expected['candidate'] = fixture['candidate']
        if phase == 'recover':
            expected['candidate'] = fixture['candidate']
            for key in ('documentId', 'uploadId', 'completeKey', 'appendKey'):
                if fixture['candidate'][key] != previous['candidate'][key]:raise ValueError('Recovery changed owned candidate')
        if phase == 'consume':expected['unconsumed']['versionId'] = fixture['unconsumed']['versionId']
        if fixture != expected:raise ValueError('Browser fixture identity changed')
    return fixture


def browser(work, state, phase, fixture, service=None):
    if phase not in PHASES or service not in (None, *d.SERVICES):raise ValueError('Finite browser phase required')
    nonce = uuid.uuid4().hex
    source = work / ('input-' + nonce + '.json');log = work / ('browser-' + nonce + '.private.log')
    d.runtime.private(source, json.dumps({'schemaVersion': 1, 'phase': phase, 'service': service, 'fixture': fixture}, separators=(',', ':')) + '\n')
    args = ['node', 'tools/runtime/identity-e2e.mjs', '--upload-fault-input-file', str(source),
            '--security-directory', str(Path(state['directory']) / 'identity'),
            '--runtime-env-file', str(Path(state['directory']) / 'runtime.env')]
    d.runtime.private(log, '')
    try:
        with log.open('wb') as stream:
            completed = subprocess.run(args, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=900,
                                       env={k:v for k,v in os.environ.items() if k in ('PATH', 'HOME', 'JAVA_HOME')})
    except (OSError, subprocess.TimeoutExpired):raise ValueError('Real browser phase unavailable; private log retained') from None
    if completed.returncode or log.stat().st_size > 16 * 1024 * 1024:raise ValueError('Real browser phase failed; private log retained')
    return result(log.read_bytes(), phase, fixture)


def digest(state, tables=d.TABLES, filters=None, normalize=None):
    clauses = []
    for table in tables:
        row = (normalize or {}).get(table, 'to_jsonb(t)')
        where = (filters or {}).get(table, 'true')
        clauses.append("SELECT '" + table + "',coalesce(jsonb_agg(" + row + " ORDER BY " + '(' + row + ')' + "::text COLLATE \"C\"),'[]'::jsonb) FROM dm." + table + ' t WHERE ' + where)
    query = 'WITH facts(name,rows) AS (' + ' UNION ALL '.join(clauses) + ") SELECT encode(sha256(convert_to(jsonb_agg(jsonb_build_array(name,rows) ORDER BY name COLLATE \"C\")::text,'UTF8')),'hex') FROM facts;"
    value = d.sql(state, query)
    if not re.fullmatch('[a-f0-9]{64}', value):raise ValueError('Invalid state digest')
    return value


def anchor(state, fixture):
    ids = ','.join("'" + fixture[n]['documentId'] + "'" for n in ('consumed', 'unconsumed'))
    tables = tuple(t for t in d.TABLES if t not in ('upload_resource_accounts', 'upload_global_resource_account'))
    filters = {t:"tenant_id='" + fixture['tenant'] + "' AND document_id IN (" + ids + ')' for t in tables}
    uploads = ','.join("'" + fixture[n]['uploadId'] + "'" for n in ('consumed', 'unconsumed'))
    filters['upload_command_outcomes'] = "tenant_id='" + fixture['tenant'] + "' AND upload_id IN (" + uploads + ')'
    filters['upload_deletion_settlements'] = "tenant_id='" + fixture['tenant'] + "' AND attempt_id IN (SELECT attempt_id FROM dm.upload_deletion_fences WHERE tenant_id='" + fixture['tenant'] + "' AND document_id IN (" + ids + '))'
    return digest(state, tables, filters)


def anchors_valid(state, fixture):
    clauses=[]
    for name in ('consumed','unconsumed'):
        artifact=fixture[name]
        consumption=("EXISTS(SELECT 1 FROM dm.upload_receipt_consumptions c WHERE c.tenant_id=r.tenant_id AND c.receipt_id=r.receipt_id AND c.version_id='" + artifact['versionId'] + "')") if name=='consumed' else "NOT EXISTS(SELECT 1 FROM dm.upload_receipt_consumptions c WHERE c.tenant_id=r.tenant_id AND c.receipt_id=r.receipt_id)"
        clauses.append("EXISTS(SELECT 1 FROM dm.upload_receipts r JOIN dm.upload_verification_facts e ON e.tenant_id=r.tenant_id AND e.evidence_id=r.verification_evidence_id JOIN dm.upload_slots s ON s.tenant_id=r.tenant_id AND s.upload_id=r.upload_id WHERE r.tenant_id='" + fixture['tenant'] + "' AND r.receipt_id='" + artifact['receiptId'] + "' AND r.upload_id='" + artifact['uploadId'] + "' AND r.document_id='" + artifact['documentId'] + "' AND r.sha256='" + fixture['sha256'] + "' AND e.source_sha256=r.sha256 AND r.size_bytes=" + str(fixture['sizeBytes']) + " AND e.source_size=r.size_bytes AND e.profile_digest='" + d.runtime.PROFILE + "' AND e.inspector_image_digest='" + state['workerImage'] + "' AND s.state='" + ('CONSUMED' if name=='consumed' else 'VERIFIED') + "' AND " + consumption + ')')
    if d.sql(state,'SELECT ' + ' AND '.join(clauses) + ';')!='t':raise ValueError('Anchor receipt/evidence/consumption binding differs')


def recovery_guard(state, fixture, selected='candidate'):
    artifact = fixture[selected];tenant = fixture['tenant'];document = artifact['documentId'];upload = artifact['uploadId']
    tables = tuple(t for t in d.TABLES if t != 'upload_global_resource_account')
    filters = {t:"NOT (tenant_id='" + tenant + "' AND document_id='" + document + "')" for t in tables}
    filters['upload_command_outcomes'] = "NOT (tenant_id='" + tenant + "' AND upload_id='" + upload + "')"
    filters['upload_deletion_settlements'] = "NOT (tenant_id='" + tenant + "' AND attempt_id IN (SELECT attempt_id FROM dm.upload_deletion_fences WHERE tenant_id='" + tenant + "' AND document_id='" + document + "'))"
    filters['upload_resource_accounts'] = "tenant_id<>'" + tenant + "'"
    return digest(state, tables, filters)


def accounting(state):
    if d.sql(state, d.BALANCES) != 't':raise ValueError('Resource balances disagree')
    query = "SELECT json_build_array(admitted_count,reserved_transient_bytes,promised_original_bytes,retained_original_bytes)::text FROM dm.upload_global_resource_account;"
    value = json.loads(d.sql(state, query))
    if not isinstance(value, list) or len(value) != 4 or any(type(x) is not int or x < 0 for x in value):raise ValueError('Invalid accounting metadata')
    return value


def candidate_facts(state, fixture):
    a = fixture['candidate'];identifier = a['uploadId'];document = a['documentId'];tenant = fixture['tenant']
    # Identifiers passed here have already passed strict canonical UUID validation.
    query = "SELECT json_build_array(state,state_revision,attempt_count,failure_code,quota_held,transient_reservation)::text FROM dm.upload_slots WHERE tenant_id='" + tenant + "' AND upload_id='" + identifier + "' AND document_id='" + document + "';"
    facts = json.loads(d.sql(state, query))
    if not isinstance(facts, list) or len(facts) != 6:raise ValueError('Exact candidate slot required')
    if d.sql(state, "SELECT NOT EXISTS(SELECT 1 FROM dm.upload_receipts WHERE tenant_id='" + tenant + "' AND upload_id='" + identifier + "') AND NOT EXISTS(SELECT 1 FROM dm.upload_verification_facts WHERE tenant_id='" + tenant + "' AND upload_id='" + identifier + "') AND NOT EXISTS(SELECT 1 FROM dm.versions WHERE tenant_id='" + tenant + "' AND document_id='" + document + "') AND EXISTS(SELECT 1 FROM dm.documents WHERE tenant_id='" + tenant + "' AND document_id='" + document + "' AND revision=0 AND active_version_id IS NULL);") != 't':raise ValueError('Failed verification created forbidden facts')
    return facts


def failure_guard(state, fixture):
    identifier = fixture['candidate']['uploadId'];tenant = fixture['tenant']
    # All eighteen tables retained: only the three explicitly checked candidate
    # failure fields and scoped failure audit rows are normalized out. audit_failures
    # independently requires the TOTAL scoped count to equal the exactly bound typed
    # count, and the caller requires precisely zero/one; wrong or extra rows fail.
    filters = {'upload_audit_facts':"NOT (tenant_id='" + tenant + "' AND upload_id='" + identifier + "' AND operation='upload.verify.failed')"}
    normalized = {'upload_slots':"CASE WHEN tenant_id='" + tenant + "' AND upload_id='" + identifier + "' THEN to_jsonb(t)-'state_revision'-'attempt_count'-'failure_code' ELSE to_jsonb(t) END"}
    return digest(state, filters=filters, normalize=normalized)


def counts(state):
    value = json.loads(d.sql(state, "SELECT json_build_array((SELECT count(*) FROM dm.versions),(SELECT count(*) FROM dm.upload_receipts),(SELECT count(*) FROM dm.upload_verification_facts),(SELECT count(*) FROM dm.upload_receipt_consumptions))::text;"))
    if not isinstance(value, list) or len(value) != 4 or any(type(x) is not int or x < 0 for x in value):raise ValueError('Invalid immutable fact counts')
    return value


def audit_failures(state, fixture):
    candidate = fixture['candidate']
    # Count every scoped failure row, not only the expected outcome. A wrong-code,
    # wrong-actor/document/revision or extra row must never disappear in the guard.
    query = "SELECT json_build_array(count(*),count(*) FILTER (WHERE a.outcome_code='DEPENDENCY_UNAVAILABLE' AND a.document_id=s.document_id AND a.document_id='" + candidate['documentId'] + "' AND a.actor_issuer=s.actor_issuer AND a.actor_subject=s.actor_subject AND a.state_revision=s.state_revision AND s.state='QUARANTINED' AND s.failure_code='DEPENDENCY_UNAVAILABLE' AND s.attempt_count=1))::text FROM dm.upload_audit_facts a JOIN dm.upload_slots s ON s.tenant_id=a.tenant_id AND s.upload_id=a.upload_id WHERE a.tenant_id='" + fixture['tenant'] + "' AND a.upload_id='" + candidate['uploadId'] + "' AND a.operation='upload.verify.failed';"
    value = json.loads(d.sql(state, query))
    if not isinstance(value,list) or len(value)!=2 or any(type(x) is not int or x<0 for x in value):raise ValueError('Invalid failure audit counts')
    if value[0]!=value[1]:raise ValueError('Failure audit rows differ from exact typed candidate binding')
    return value[0]


def check_failure(before, after, mutable):
    expected = before.copy()
    if mutable:
        expected[1] += 2;expected[2] += 1;expected[3] = 'DEPENDENCY_UNAVAILABLE'
    if before != ['QUARANTINED', 2, 0, None, True, before[5]] or after != expected:
        raise ValueError('Unexpected failed verification state delta')


def check_recovered(state, fixture, before_accounts, before_counts, mutable):
    expected_accounts = before_accounts.copy();expected_accounts[3] += fixture['sizeBytes']
    if accounting(state) != expected_accounts:raise ValueError('Recovery resource delta differs')
    if counts(state) != [x + 1 for x in before_counts]:raise ValueError('Recovery must create exactly one immutable fact of each kind')
    a = fixture['candidate']
    query = "SELECT EXISTS(SELECT 1 FROM dm.upload_slots s JOIN dm.upload_receipts r USING(tenant_id,upload_id) JOIN dm.upload_receipt_consumptions c USING(tenant_id,receipt_id) JOIN dm.versions v ON v.tenant_id=c.tenant_id AND v.document_id=c.document_id AND v.version_id=c.version_id WHERE s.document_id=c.document_id AND r.document_id=c.document_id AND s.tenant_id='" + fixture['tenant'] + "' AND s.upload_id='" + a['uploadId'] + "' AND s.state='CONSUMED' AND s.attempt_count=" + str(2 if mutable else 1) + " AND r.receipt_id='" + a['receiptId'] + "' AND v.version_id='" + a['versionId'] + "' AND v.artifact_reference=r.receipt_id::text AND s.transient_reservation=0 AND NOT s.quota_held);"
    if d.sql(state, query) != 't':raise ValueError('Recovery exact receipt/version/consumption mismatch')


def quiet(state):
    d.await_cleanup(state)
    before = d.snapshot(state);time.sleep(12)
    if d.snapshot(state) != before:raise ValueError('Runtime is not quiescent')
    return before


def fault(work, state, fixture, service, phase):
    target = d.container(state, d.SERVICES[service])
    proof_path = work / ('fault-' + uuid.uuid4().hex + '.json')
    proof = {'project':state['project'],'runtimeRunId':state['runId'],'service':service,'containerId':target,'phase':phase,'restoreCommandSucceeded':None,'recoveredHealth':False,'verdict':'IN_PROGRESS'}
    d.runtime.private(proof_path, json.dumps(proof, separators=(',', ':')) + '\n')
    try:
        d.action(state, target, 'stop', d.SERVICES[service])
        if service != 'dm':d.wait_probe('readiness', 503);d.wait_probe('liveness', 200, seconds=5)
        return browser(work, state, phase, fixture, service)
    finally:
        try:
            proof['restoreCommandSucceeded']=False
            d.action(state, target, 'start', d.SERVICES[service]);proof['restoreCommandSucceeded']=True
            d.runtime.wait_inspector(state) if service == 'inspector' else d.runtime.wait_container(target, state, seconds=180)
            d.wait_probe('readiness', 200, seconds=180);d.wait_probe('liveness', 200, seconds=5)
            proof['recoveredHealth']=True
        finally:
            proof['verdict']='RESTORED' if proof['recoveredHealth'] else 'RESTORATION_UNPROVEN'
            pending=proof_path.with_suffix('.pending');d.runtime.private(pending,json.dumps(proof,separators=(',',':'))+'\n');pending.replace(proof_path)


def save(work, record):
    pending = work / 'state.pending';d.runtime.private(pending, json.dumps(record, separators=(',', ':')) + '\n');pending.replace(work / 'state.json')


def source_hashes():
    return {p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in SOURCES}


def run(directory, runtime_directory):
    d.runtime.local_daemon();state = d.runtime.load(runtime_directory);d.runtime.verify_generation(state)
    d.container(state,'document-management');d.wait_probe('readiness',200);d.wait_probe('liveness',200,seconds=5)
    work = d.runtime.confined(directory)
    if work.exists():raise ValueError('Fresh acceptance directory required; no adoption/resume')
    work.parent.mkdir(parents=True, exist_ok=True);work.mkdir(mode=0o700)
    record = {'schemaVersion':1,'runId':uuid.uuid4().hex,'runtimeDirectory':state['directory'],
              'project':state['project'],'runtimeRunId':state['runId'],
              'sourceHashes':source_hashes(),
              'fixture':None,'phase':'seed','results':[],'verdict':'IN_PROGRESS'}
    save(work,record)
    try:
        fixture = browser(work,state,'seed',None);record['fixture']=fixture;save(work,record)
        quiet(state);anchors_valid(state,fixture);original_anchor = anchor(state, fixture);record['anchorSha256']=original_anchor;save(work,record)
        for service in d.SERVICES:
            if source_hashes()!=record['sourceHashes']:raise ValueError('Reviewed acceptance source changed')
            record['phase']=service + '-readonly';save(work,record)
            before=quiet(state);record['currentBeforeSha256']=before;save(work,record)
            fixture=fault(work,state,fixture,service,'readonly-outage')
            fixture=browser(work,state,'verify',fixture)
            after=d.snapshot(state)
            if after!=before or anchor(state,fixture)!=original_anchor:raise ValueError('Read-only restart changed durable facts')
            record['results'].append({'service':service,'readOnly':'PASS','beforeFullStateSha256':before,'afterFullStateSha256':after})
            record['phase']=service + '-candidate';save(work,record)
            accounts_before=accounting(state)
            fixture=browser(work,state,'candidate',fixture);valid_fixture(fixture)
            quiet(state)
            before_facts=candidate_facts(state,fixture);before_guard=failure_guard(state,fixture);before_counts=counts(state)
            candidate_accounts=accounting(state)
            size=fixture['sizeBytes']
            if candidate_accounts!=[accounts_before[0]+1,accounts_before[1]+2*size,accounts_before[2]+size,accounts_before[3]] or before_facts[5]!=2*size:raise ValueError('Admission did not reserve exact slot and two-copy budgets')
            if audit_failures(state,fixture)!=0:raise ValueError('Fresh candidate has prior failure')
            fixture=fault(work,state,fixture,service,'candidate-outage')
            mutable=service not in ('pg','dm')
            check_failure(before_facts,candidate_facts(state,fixture),mutable)
            if failure_guard(state,fixture)!=before_guard or accounting(state)!=candidate_accounts or counts(state)!=before_counts or audit_failures(state,fixture)!=(1 if mutable else 0):raise ValueError('Failed command altered forbidden facts/accounting')
            unchanged_recovery=recovery_guard(state,fixture)
            record['phase']=service + '-recover';save(work,record)
            fixture=browser(work,state,'recover',fixture,service);quiet(state)
            check_recovered(state,fixture,accounts_before,before_counts,mutable)
            if recovery_guard(state,fixture)!=unchanged_recovery:raise ValueError('Recovery changed unrelated durable facts')
            anchors_valid(state,fixture)
            if anchor(state,fixture)!=original_anchor:raise ValueError('Recovery changed persistent receipt evidence')
            record['results'][-1].update({'retry':'PASS','accountsBeforeCandidate':accounts_before,'accountsAfterFailure':candidate_accounts,'accountsAfterRecovery':accounting(state),'immutableCountsBefore':before_counts,'immutableCountsAfter':counts(state)})
            record['fixture']=fixture;save(work,record)
        record['phase']='consume';save(work,record)
        unchanged_consume=recovery_guard(state,fixture,'unconsumed')
        immutable=digest(state,('upload_receipts','upload_verification_facts'))
        before_counts=counts(state);before_accounts=accounting(state)
        reservation=d.sql(state,"SELECT transient_reservation FROM dm.upload_slots WHERE tenant_id='" + fixture['tenant'] + "' AND upload_id='" + fixture['unconsumed']['uploadId'] + "';")
        if not reservation.isdigit():raise ValueError('Invalid final reservation')
        fixture=browser(work,state,'consume',fixture);quiet(state)
        expected=[before_accounts[0]-1,before_accounts[1]-int(reservation),before_accounts[2]-fixture['sizeBytes'],before_accounts[3]+fixture['sizeBytes']]
        if (accounting(state)!=expected or counts(state)!=[before_counts[0]+1,before_counts[1],before_counts[2],before_counts[3]+1]
                or digest(state,('upload_receipts','upload_verification_facts'))!=immutable or recovery_guard(state,fixture,'unconsumed')!=unchanged_consume):
            raise ValueError('Final one-consumption/replay changed forbidden facts or balances')
        record['fixture']=fixture;record['phase']='complete';record['verdict']='PASS';save(work,record)
        return record
    except Exception:
        record['verdict']='FAIL';save(work,record)
        raise ValueError('Authenticated upload acceptance failed at ' + record['phase'] + '; private evidence retained') from None


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--directory',required=True);parser.add_argument('--runtime-directory',required=True)
    args=parser.parse_args(argv)
    try:run(args.directory,args.runtime_directory)
    except Exception:
        print('Authenticated upload fault acceptance failed; private evidence retained; verify owned restoration.');return 1
    print('Authenticated upload faults PASS: all5providers, bothreceiptstates, exactbytes and explicitaccounting; zero skips.');return 0


if __name__=='__main__':raise SystemExit(main())
