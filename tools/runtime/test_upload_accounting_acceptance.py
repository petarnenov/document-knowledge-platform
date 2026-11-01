"""Regression driver safety tests; actual SQL execution is a separate required gate."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('accounting', Path(__file__).with_name('upload-accounting-acceptance.py'))
m = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(m)


class AccountingTests(unittest.TestCase):
    def test_uses_actual_balance_query_only_against_synthetic_read_only_ctes(self):
        value = m.query([(3, 300, 500, 700)], [(1, 100, 200, 300), (2, 200, 300, 400)])
        self.assertIn('VALUES (3,300,500,700)', value)
        self.assertIn(m.durability.BALANCES.replace('dm.upload_global_resource_account', 'global_fixture')
                      .replace('dm.upload_resource_accounts', 'tenant_fixture'), value)
        self.assertNotIn('dm.', value)
        self.assertIn('WHERE false', m.query([], []))
        self.assertNotRegex(value, r'(?i)\b(insert|update|delete|create|drop|alter)\b')

    def test_all_eight_required_real_queries_execute_and_fail_closed_on_any_mismatch(self):
        expected = ['t', 't', 'f', 'f', 'f', 'f', 'f', 'f']
        with patch.object(m.durability.runtime, 'local_daemon'), patch.object(m.durability.runtime, 'load', return_value={'owned': True}), \
             patch.object(m.durability, 'sql', side_effect=expected) as sql:
            self.assertEqual(8, m.run('synthetic-ready-record'))
            self.assertEqual(8, sql.call_count)
            queries = [call.args[1] for call in sql.call_args_list]
            self.assertEqual(8, len(set(queries)))
        for index in range(8):
            results = expected.copy()
            results[index] = 'f' if results[index] == 't' else 't'
            with self.subTest(case=index), patch.object(m.durability.runtime, 'local_daemon'), \
                 patch.object(m.durability.runtime, 'load', return_value={}), patch.object(m.durability, 'sql', side_effect=results):
                with self.assertRaisesRegex(ValueError, 'accounting regression failed'):
                    m.run('synthetic-ready-record')

    def test_cli_never_reports_missing_or_failed_real_provider_as_success_or_leaks_exception(self):
        with patch.object(m, 'run', return_value=8), patch('builtins.print') as output:
            self.assertEqual(0, m.main(['--directory', 'synthetic']))
            self.assertIn('8 cases, zero skips', output.call_args.args[0])
        with patch.object(m, 'run', side_effect=ValueError('synthetic-private-detail')), patch('builtins.print') as output:
            self.assertEqual(1, m.main(['--directory', 'synthetic']))
            self.assertNotIn('synthetic-private-detail', output.call_args.args[0])


if __name__ == '__main__':
    unittest.main()
