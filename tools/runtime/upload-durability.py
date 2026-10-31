#!/usr/bin/env python3
"""Owned BP03 provider fault/durability acceptance (issue143).

After genuine bootstrap and authenticated upload E2E, with other writers stopped:
  python3 tools/runtime/upload-durability.py --directory <recorded-run> --exercise pg
Repeat with rustfs, scanner, inspector and dm, one invocation at a time. No default
mutation: omitting --exercise only validates provenance, inventory and snapshots.
Each invocation checks private recorded project/material, local Docker ownership,
fixed BP03 ports, actual readiness/liveness and all eighteen owned business tables.
It stops ONLY the selected running container and always attempts its restoration.
Output contains no database rows/keys/environment; the exclusive private evidence
file contains hashes, exact container identity and finite verdicts only.

Requires quiescent business state: scheduler cleanup/expiry changes deliberately
fail exact equality. Does not manufacture fixtures or prove authenticated download,
expiry, ambiguous-copy/delete, or hold-race journeys. Run those separately. Never
use this command concurrently with browser tests or other lifecycle tooling.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request

SPEC = importlib.util.spec_from_file_location('owned_upload_runtime', Path(__file__).with_name('upload-runtime.py'))
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)
SERVICES = {'pg': 'dm-postgres', 'rustfs': 'rustfs', 'scanner': 'clamav',
            'inspector': 'structure-inspector', 'dm': 'document-management'}
TABLES = ('documents', 'retention', 'acl_rules', 'legal_holds', 'versions', 'command_outcomes', 'audit_facts',
          'upload_slots', 'upload_receipts', 'upload_verification_facts', 'upload_receipt_consumptions',
          'upload_command_outcomes', 'upload_resource_accounts', 'upload_global_resource_account',
          'upload_cleanup_work', 'upload_deletion_fences', 'upload_deletion_settlements', 'upload_audit_facts')
INVENTORY = "SELECT string_agg(tablename,',' ORDER BY tablename) FROM pg_tables WHERE schemaname='dm' AND tablename<>'flyway_schema_history';"
BALANCES = "SELECT (SELECT count(*) FROM dm.upload_global_resource_account)=1 AND NOT EXISTS (SELECT 1 FROM dm.upload_global_resource_account g WHERE (g.admitted_count,g.reserved_transient_bytes,g.promised_original_bytes,g.retained_original_bytes) IS DISTINCT FROM (SELECT coalesce(sum(admitted_count),0),coalesce(sum(reserved_transient_bytes),0),coalesce(sum(promised_original_bytes),0),coalesce(sum(retained_original_bytes),0) FROM dm.upload_resource_accounts));"
SNAPSHOT = "WITH facts(name,rows) AS (" + ' UNION ALL '.join(
    "SELECT '" + t + "',coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE \"C\"),'[]'::jsonb) FROM dm." + t + " t"
    for t in TABLES) + ") SELECT encode(sha256(convert_to(jsonb_agg(jsonb_build_array(name,rows) ORDER BY name COLLATE \"C\")::text,'UTF8')),'hex') FROM facts;"
SQL_SCRIPT = '''export PGPASSWORD="$POSTGRES_PASSWORD"
exec psql --no-psqlrc --host=127.0.0.1 --username="$POSTGRES_USER" --dbname=dkp_dm --set=ON_ERROR_STOP=1 --tuples-only --no-align
'''


def container(state, service):
    value = runtime.compose(state, 'ps', '-q', service).decode().strip()
    if not re.fullmatch('[0-9a-f]{64}', value):
        raise ValueError('Exactly one owned service instance required')
    item = runtime.docker('inspect', value)[0]
    runtime.owned(item, state)
    labels = item.get('Config', {}).get('Labels', {})
    if (item['Id'] != value or labels.get('com.docker.compose.project') != state['project']
            or labels.get('com.docker.compose.service') != service or item['State']['Status'] != 'running'):
        raise ValueError('Running exact owned Compose service required')
    if service == 'document-management':
        ports = item.get('NetworkSettings', {}).get('Ports', {}).get('8080/tcp')
        if ports != [{'HostIp': '127.0.0.1', 'HostPort': '19081'}]:
            raise ValueError('Exact loopback BP03 probe port required')
    return value


def sql(state, query):
    target = container(state, 'dm-postgres')
    try:
        result = subprocess.run(['docker', 'exec', '-i', target, 'sh', '-c', SQL_SCRIPT],
                                input=query.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=15, env=runtime.environment(Path(state['directory'])))
        if result.returncode:
            raise ValueError('Owned database query failed')
        return result.stdout.decode().strip()
    except (OSError, subprocess.TimeoutExpired, UnicodeError):
        raise ValueError('Owned database query unavailable') from None


def snapshot(state):
    if sql(state, INVENTORY) != ','.join(sorted(TABLES)):
        raise ValueError('Owned business table inventory changed')
    for setting in ('fsync', 'synchronous_commit', 'full_page_writes'):
        if sql(state, 'SHOW ' + setting + ';') != 'on':
            raise ValueError('Durable PostgreSQL settings required')
    # Require actual previously committed upload evidence, not an empty database.
    if sql(state, 'SELECT EXISTS(SELECT 1 FROM dm.upload_receipts) AND EXISTS(SELECT 1 FROM dm.upload_receipt_consumptions) AND EXISTS(SELECT 1 FROM dm.upload_verification_facts);') != 't':
        raise ValueError('Run authenticated successful upload and append first')
    if sql(state, BALANCES) != 't':
        raise ValueError('Global and tenant resource balances disagree')
    value = sql(state, SNAPSHOT)
    if not re.fullmatch('[0-9a-f]{64}', value):
        raise ValueError('Invalid owned state digest')
    return value


def probe(kind, expected):
    # No ambient proxy or redirects: only the attested loopback DM port.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        response = opener.open('http://127.0.0.1:19081/health/' + kind, timeout=3)
    except urllib.error.HTTPError as response_error:
        response = response_error
    with response:
        body = response.read(129)
        return response.code == expected and len(body) <= 128 and json.loads(body) == {
            'status': 'UP' if expected == 200 else 'DOWN'}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def wait_probe(kind, expected, seconds=45):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            if probe(kind, expected):
                return
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise ValueError('Bounded real probe did not reach required state')


def action(state, target, verb, service):
    # Re-attest exact identity before each mutation, including restoration. Never
    # resolve a replacement container or invoke Compose recreation/down/volume rm.
    item = runtime.docker('inspect', target)[0]
    runtime.owned(item, state)
    labels = item.get('Config', {}).get('Labels', {})
    if (item['Id'] != target or labels.get('com.docker.compose.project') != state['project']
            or labels.get('com.docker.compose.service') != service):
        raise ValueError('Refusing changed fault target')
    runtime.command(['docker', verb, '--time', '10', target] if verb == 'stop' else ['docker', 'start', target], timeout=45)


def run(directory, exercise=None):
    runtime.local_daemon()
    state = runtime.load(directory)
    destination = Path(state['directory']) / ('durability-' + (exercise or 'snapshot') + '.json')
    if destination.exists() or destination.is_symlink():
        raise ValueError('Existing acceptance evidence cannot be overwritten or re-executed')
    runtime.verify_generation(state)
    container(state, 'document-management')
    wait_probe('readiness', 200)
    before = snapshot(state)
    service = SERVICES[exercise] if exercise else None
    target = container(state, service) if service else None
    if target:
        try:
            action(state, target, 'stop', service)
            if exercise != 'dm':
                wait_probe('readiness', 503)
                wait_probe('liveness', 200, seconds=5)
        finally:
            action(state, target, 'start', service)
        runtime.wait_container(target, state, seconds=180) if exercise != 'inspector' else runtime.wait_inspector(state)
        wait_probe('readiness', 200, seconds=180)
        wait_probe('liveness', 200, seconds=5)
    after = snapshot(state)
    if after != before:
        raise ValueError('Owned state changed across fault; no durability PASS')
    result = {'schemaVersion': 1, 'project': state['project'], 'runId': state['runId'],
              'exercise': exercise or 'snapshot', 'containerId': target,
              'tableCount': len(TABLES), 'beforeSha256': before, 'afterSha256': after, 'verdict': 'PASS'}
    runtime.private(destination, json.dumps(result, separators=(',', ':')) + '\n')
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--exercise', choices=tuple(SERVICES))
    args = parser.parse_args(argv)
    try:
        run(args.directory, args.exercise)
    except Exception:
        print('BP03 durability acceptance failed; private diagnostics suppressed; check owned provider restoration.')
        return 1
    print('BP03 owned durability acceptance PASS; private hash evidence saved.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
