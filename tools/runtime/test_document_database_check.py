import importlib.util
import re
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('database_check', Path(__file__).with_name('document-database-check.py'))
db = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(db)


class DatabaseCheckTest(unittest.TestCase):
    def test_role_commands_keep_passwords_out_of_arguments(self):
        with patch.object(db.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout='dkp_dm_runtime\n')) as run:
            self.assertEqual('dkp_dm_runtime', db.sql('SELECT current_user;'))
            args = run.call_args.args[0]
            self.assertIn('exec', args)
            self.assertIn('DKP_DM_RUNTIME_PASSWORD', args[args.index('-c') + 1])
            self.assertEqual('SELECT current_user;', run.call_args.kwargs['input'])
        for role, database in [('unapproved', 'dkp_dm'), ('runtime', 'other')]:
            with self.assertRaises(ValueError):
                db.sql('SELECT 1', role, database)
        with patch.object(db.subprocess, 'run', return_value=SimpleNamespace(returncode=1, stdout='sensitive')):
            self.assertEqual('', db.sql('forbidden', expect_failure=True))
            with self.assertRaisesRegex(AssertionError, '^Database verification operation failed; diagnostics suppressed$'):
                db.sql('allowed')
        with patch.object(db.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout='')):
            with self.assertRaises(AssertionError):
                db.sql('forbidden', expect_failure=True)

    def test_real_role_assertion_matrix_and_negative_capability(self):
        def sql(query, role='runtime', database='dkp_dm', expect_failure=False):
            if expect_failure:
                return ''
            if query.startswith('SHOW'):
                return 'on'
            if 'count(*) > 0' in query:
                return 't'
            if query == 'SELECT current_user;':
                return 'dkp_dm_runtime'
            if query.startswith('BEGIN'):
                return ''
            return 'f'
        with patch.object(db, 'sql', side_effect=sql):
            db.verify_database()
        with patch.object(db, 'sql', return_value='off'):
            with self.assertRaises(AssertionError):
                db.verify_database()

    def test_readiness_retries_and_rejects_wrong_payload(self):
        with patch.object(db.smoke, 'fetch', side_effect=[OSError(), (503, b'{"status":"DOWN"}'), (200, b'{"status":"UP"}')]), patch.object(db.time, 'sleep'):
            db.wait_readiness(200)
        with patch.object(db.smoke, 'fetch', return_value=(503, b'{"status":"DOWN","private":"forbidden"}')):
            with self.assertRaises(AssertionError):
                db.wait_readiness(503)
        with patch.object(db.smoke, 'fetch', return_value=(200, b'{"status":"UP"}')), patch.object(db.time, 'sleep'):
            with self.assertRaises(AssertionError):
                db.wait_readiness(503)

    def test_outage_always_restores_database_and_checks_persistence(self):
        with patch.object(db, 'sql', return_value='t'), patch.object(db, 'snapshot', return_value='digest'), patch.object(db, 'compose_action') as action, patch.object(db, 'wait_readiness') as ready, patch.object(db, 'verify_database'), patch.object(db.smoke, 'fetch', return_value=(200, b'{"status":"UP"}')):
            db.verify_outage()
            self.assertEqual(['stop', 'start'], [c.args[0] for c in action.call_args_list])
            self.assertEqual([503, 200], [c.args[0] for c in ready.call_args_list])
        with patch.object(db, 'sql', return_value='t'), patch.object(db, 'snapshot', return_value='digest'), patch.object(db, 'compose_action') as action, patch.object(db, 'wait_readiness', side_effect=AssertionError('expected failure')):
            with self.assertRaises(AssertionError):
                db.verify_outage()
            self.assertEqual('start', action.call_args_list[-1].args[0])
        with patch.object(db, 'sql', return_value='f'):
            with self.assertRaises(AssertionError):
                db.verify_outage()

    def test_snapshot_is_one_complete_ordered_database_side_digest(self):
        # This regression guards coverage of the entire aggregate, rather than
        # merely the root documents table. Each row is serialized before sorting.
        tables = ['documents', 'retention', 'acl_rules', 'legal_holds', 'versions',
                  'command_outcomes', 'audit_facts']
        self.assertEqual(tables, re.findall(r'FROM dm\.([a-z_]+) t', db.SNAPSHOT_SQL))
        self.assertEqual(7, db.SNAPSHOT_SQL.count('jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text COLLATE "C")'))
        self.assertEqual(7, db.SNAPSHOT_SQL.count("'[]'::jsonb"))
        for table in tables:
            self.assertIn("SELECT '" + table + "', coalesce(", db.SNAPSHOT_SQL)
        self.assertIn('jsonb_build_array(table_name, snapshot_rows)', db.SNAPSHOT_SQL)
        self.assertIn('ORDER BY table_name COLLATE "C"', db.SNAPSHOT_SQL)
        self.assertIn("SELECT encode(sha256(convert_to(", db.SNAPSHOT_SQL)
        self.assertEqual(1, db.SNAPSHOT_SQL.count(';'))
        self.assertNotIn('flyway_schema_history', db.SNAPSHOT_SQL)

    def test_changed_child_state_fails_after_database_restoration(self):
        # Compare the actual snapshot validation path with changed server digests.
        with patch.object(db, 'sql', side_effect=['t', 'a' * 64, 'b' * 64]) as query, patch.object(db, 'compose_action') as action, patch.object(db, 'wait_readiness'), patch.object(db, 'verify_database'), patch.object(db.smoke, 'fetch', return_value=(200, b'{"status":"UP"}')):
            with self.assertRaisesRegex(AssertionError, '^Owned business state changed across persistent database restart$'):
                db.verify_outage()
            self.assertEqual(['stop', 'start'], [c.args[0] for c in action.call_args_list])
            self.assertEqual([db.SNAPSHOT_SQL, db.SNAPSHOT_SQL], [c.args[0] for c in query.call_args_list[1:]])
        # A failed preflight snapshot must not stop a healthy database.
        with patch.object(db, 'sql', side_effect=['t', 'invalid']), patch.object(db, 'compose_action') as action:
            with self.assertRaises(AssertionError):
                db.verify_outage()
            action.assert_not_called()

    def test_snapshot_and_lifecycle_fail_safely(self):
        with patch.object(db, 'sql', return_value='a' * 64) as query:
            self.assertEqual('a' * 64, db.snapshot())
            query.assert_called_once_with(db.SNAPSHOT_SQL, 'admin')
        for invalid in ('', 'a' * 32, 'g' * 64, 'A' * 64, 'a' * 64 + '\nprivate', 'secret' + 'a' * 64):
            with self.subTest(invalid_length=len(invalid)), patch.object(db, 'sql', return_value=invalid):
                with self.assertRaisesRegex(AssertionError, '^Invalid persistence verification digest$'):
                    db.snapshot()
        with patch.object(db, 'sql', side_effect=AssertionError('query failed')):
            with self.assertRaisesRegex(AssertionError, '^query failed$'):
                db.snapshot()
        for code in (0, 1):
            with patch.object(db.subprocess, 'run', return_value=SimpleNamespace(returncode=code)):
                if code:
                    with self.assertRaises(AssertionError):
                        db.compose_action('stop')
                else:
                    db.compose_action('start')
        with patch.object(db, 'verify_database') as verify, patch.object(db, 'verify_outage') as outage, patch('builtins.print'):
            db.main(False)
            outage.assert_not_called()
            db.main(True)
            self.assertEqual(2, verify.call_count)
            outage.assert_called_once()


if __name__ == '__main__':
    unittest.main()
