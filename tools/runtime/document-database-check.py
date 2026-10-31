#!/usr/bin/env python3
"""Verify real local DM roles and optional outage/restart; never print database diagnostics."""
import argparse
import re
import subprocess
import time
from importlib.util import spec_from_file_location, module_from_spec
from pathlib import Path

SPEC = spec_from_file_location('dm_smoke', Path(__file__).with_name('smoke.py'))
smoke = module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)
COMPOSE = smoke.COMPOSE


def sql(query, role='runtime', database='dkp_dm', *, expect_failure=False):
    if role not in ('runtime', 'migrator', 'admin') or database not in ('dkp_dm', 'postgres'):
        raise ValueError('Unapproved database verification target')
    script = '''case "$1" in
runtime) export PGPASSWORD="$DKP_DM_RUNTIME_PASSWORD"; user=dkp_dm_runtime;;
migrator) export PGPASSWORD="$DKP_DM_MIGRATOR_PASSWORD"; user=dkp_dm_migrator;;
admin) export PGPASSWORD="$POSTGRES_PASSWORD"; user="$POSTGRES_USER";;
esac
exec psql --no-psqlrc --host=127.0.0.1 --username="$user" --dbname="$2" --set=ON_ERROR_STOP=1 --tuples-only --no-align
'''
    result = subprocess.run([*COMPOSE, 'exec', '-T', 'dm-postgres', 'sh', '-c', script, 'dm-role-check', role, database],
                            input=query, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if expect_failure:
        assert result.returncode != 0, 'Forbidden database capability was available'
        return ''
    assert result.returncode == 0, 'Database verification operation failed; diagnostics suppressed'
    return result.stdout.strip()


def verify_database():
    assert sql("SHOW fsync;", 'admin') == 'on', 'Durability requires fsync'
    assert sql("SHOW synchronous_commit;", 'admin') == 'on', 'Durability requires synchronous commit'
    assert sql("SHOW full_page_writes;", 'admin') == 'on', 'Durability requires full page writes'
    assert sql("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user;") == 'f'
    assert sql("SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname=current_user;", 'migrator') == 'f'
    assert sql("SELECT has_database_privilege(current_user,current_database(),'CREATE') OR has_schema_privilege(current_user,'public','CREATE') OR has_schema_privilege(current_user,'dm','CREATE');") == 'f'
    assert sql("SELECT has_table_privilege(current_user,'dm.flyway_schema_history','SELECT');") == 'f'
    assert sql("SELECT has_table_privilege(current_user,'dm.versions','UPDATE') OR has_table_privilege(current_user,'dm.versions','DELETE') OR has_table_privilege(current_user,'dm.audit_facts','UPDATE') OR has_table_privilege(current_user,'dm.audit_facts','DELETE');") == 'f'
    assert sql("SELECT count(*) > 0 FROM dm.flyway_schema_history WHERE success;", 'admin') == 't'
    assert sql("SELECT current_user;") == 'dkp_dm_runtime'
    sql("BEGIN; UPDATE dm.documents SET revision=revision WHERE false; ROLLBACK;")
    for statement in ("CREATE TABLE dm.forbidden_probe(id integer);", "CREATE TABLE public.forbidden_probe(id integer);",
                      "UPDATE dm.versions SET producer=producer WHERE false;", "DELETE FROM dm.audit_facts WHERE false;",
                      "SELECT * FROM dm.flyway_schema_history;"):
        sql(statement, expect_failure=True)
    sql("SELECT 1;", database='postgres', expect_failure=True)


# A single statement observes one PostgreSQL MVCC snapshot of ALL owned business
# tables. Row JSON includes every column; C ordering is independent of locale.
# Table-labelled JSON arrays make table/row boundaries unambiguous, including
# empty tables. Migration history is infrastructure state and deliberately absent.
SNAPSHOT_SQL = """WITH business_state(table_name, snapshot_rows) AS (
    SELECT 'documents', coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE "C"), '[]'::jsonb) FROM dm.documents t
    UNION ALL SELECT 'retention', coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE "C"), '[]'::jsonb) FROM dm.retention t
    UNION ALL SELECT 'acl_rules', coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE "C"), '[]'::jsonb) FROM dm.acl_rules t
    UNION ALL SELECT 'legal_holds', coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE "C"), '[]'::jsonb) FROM dm.legal_holds t
    UNION ALL SELECT 'versions', coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE "C"), '[]'::jsonb) FROM dm.versions t
    UNION ALL SELECT 'command_outcomes', coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE "C"), '[]'::jsonb) FROM dm.command_outcomes t
    UNION ALL SELECT 'audit_facts', coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE "C"), '[]'::jsonb) FROM dm.audit_facts t
)
SELECT encode(sha256(convert_to(jsonb_agg(jsonb_build_array(table_name, snapshot_rows)
    ORDER BY table_name COLLATE "C")::text, 'UTF8')), 'hex') FROM business_state;"""


def snapshot():
    # Hash inside PostgreSQL: only a SHA-256 digest crosses the process boundary.
    value = sql(SNAPSHOT_SQL, 'admin')
    assert re.fullmatch(r'[0-9a-f]{64}', value), 'Invalid persistence verification digest'
    return value


def wait_readiness(status):
    for _ in range(40):
        try:
            code, body = smoke.fetch('http://127.0.0.1:18081/health/readiness')
            if code == status:
                import json
                assert json.loads(body) == {'status': 'UP' if status == 200 else 'DOWN'}
                return
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise AssertionError('DM database readiness did not reach expected state')


def compose_action(action):
    result = subprocess.run([*COMPOSE, action, 'dm-postgres'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert result.returncode == 0, 'Database lifecycle verification command failed'


def verify_outage():
    assert sql('SELECT count(*) > 0 FROM dm.documents;', 'admin') == 't', 'Run authenticated draft E2E before restart verification'
    before = snapshot()
    try:
        compose_action('stop')
        wait_readiness(503)
        code, body = smoke.fetch('http://127.0.0.1:18081/health/liveness')
        import json
        assert code == 200 and json.loads(body) == {'status': 'UP'}, 'DB outage must not kill process liveness'
    finally:
        compose_action('start')
    wait_readiness(200)
    verify_database()
    assert snapshot() == before, 'Owned business state changed across persistent database restart'


def main(outage=False):
    verify_database()
    if outage:
        verify_outage()
    print('DM PostgreSQL scoped roles, migrations and durability checks PASS' + ('; outage/readiness/restart PASS' if outage else ''))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exercise-outage', action='store_true', help='Stop/restart only the isolated local DM database after real draft E2E')
    arguments = parser.parse_args()
    try:
        main(arguments.exercise_outage)
    except Exception:
        print('DM database verification failed; raw database/environment diagnostics suppressed.')
        raise SystemExit(1)
