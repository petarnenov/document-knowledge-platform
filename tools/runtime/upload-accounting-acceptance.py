#!/usr/bin/env python3
"""Execute the actual durability balance SQL against synthetic CTEs in real PG.

Requires --directory <ready BP03 record>. Never writes business tables. Missing
providers fail; there are no mocked or skipped required acceptance cases.
"""
import argparse
import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('upload_durability', Path(__file__).with_name('upload-durability.py'))
durability = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(durability)
COLUMNS = 'admitted_count,reserved_transient_bytes,promised_original_bytes,retained_original_bytes'


def query(global_rows, tenant_rows):
    def relation(rows):
        if not rows:
            return 'SELECT 0,0,0,0 WHERE false'
        return 'VALUES ' + ','.join('(' + ','.join(str(value) for value in row) + ')' for row in rows)
    sql = durability.BALANCES.replace('dm.upload_global_resource_account', 'global_fixture').replace('dm.upload_resource_accounts', 'tenant_fixture')
    return ('WITH global_fixture(' + COLUMNS + ') AS (' + relation(global_rows) + '),'
            'tenant_fixture(' + COLUMNS + ') AS (' + relation(tenant_rows) + ') ' + sql)


def run(directory):
    durability.runtime.local_daemon()
    state = durability.runtime.load(directory)
    total = (3, 300, 500, 700)
    tenants = [(1, 100, 200, 300), (2, 200, 300, 400)]
    cases = [('balanced', [total], tenants, 't'), ('empty-tenants', [(0, 0, 0, 0)], [], 't'),
             ('missing-global', [], tenants, 'f'), ('duplicate-global', [total, total], tenants, 'f')]
    for index, name in enumerate(COLUMNS.split(',')):
        wrong = list(total)
        wrong[index] += 1
        cases.append(('mismatch-' + name, [wrong], tenants, 'f'))
    for name, globals_, tenant_, expected in cases:
        if durability.sql(state, query(globals_, tenant_)) != expected:
            raise ValueError('Actual PostgreSQL accounting regression failed: ' + name)
    return len(cases)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True)
    args = parser.parse_args(argv)
    try:
        count = run(args.directory)
    except Exception:
        print('Real PostgreSQL accounting acceptance failed; private diagnostics suppressed.')
        return 1
    print('Real PostgreSQL accounting acceptance PASS: ' + str(count) + ' cases, zero skips; no business writes.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
