"""Safety/order tests, not a substitute for actual deployed fault acceptance."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location('upload_durability', Path(__file__).with_name('upload-durability.py'))
m = importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class DurabilityTests(unittest.TestCase):
    def setUp(self):
        self.state = {'directory': '/private/owned', 'project': 'dkp-bp03-'+'a'*12, 'runId': 'a'*32}
        self.target = 'b'*64
        self.item = {'Id': self.target, 'State': {'Status': 'running'}, 'Config': {'Labels': {
            'com.docker.compose.project': self.state['project'], 'com.docker.compose.service': 'document-management'}},
            'NetworkSettings': {'Ports': {'8080/tcp': [{'HostIp':'127.0.0.1','HostPort':'19081'}]}}}

    def test_exact_container_service_project_port_and_running_guards(self):
        with patch.object(m.runtime,'compose',return_value=self.target.encode()), patch.object(m.runtime,'docker',return_value=[self.item]), patch.object(m.runtime,'owned'):
            self.assertEqual(self.target,m.container(self.state,'document-management'))
            for field,value in [('com.docker.compose.service','foreign'),('com.docker.compose.project','foreign')]:
                original=self.item['Config']['Labels'][field];self.item['Config']['Labels'][field]=value
                with self.assertRaises(ValueError):m.container(self.state,'document-management')
                self.item['Config']['Labels'][field]=original
            self.item['NetworkSettings']['Ports']['8080/tcp'][0]['HostIp']='0.0.0.0'
            with self.assertRaises(ValueError):m.container(self.state,'document-management')
            self.item['State']['Status']='exited'
            with self.assertRaises(ValueError):m.container(self.state,'document-management')
        with patch.object(m.runtime,'compose',return_value=b''):
            with self.assertRaises(ValueError):m.container(self.state,'dm-postgres')

    def test_sql_stdin_only_captured_diagnostics_and_failures(self):
        with patch.object(m,'container',return_value=self.target),patch.object(m.runtime,'environment',return_value={}),patch.object(m.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=b'ok\n')) as execute:
            self.assertEqual('ok',m.sql(self.state,'SELECT private_rows;'))
            args,kwargs=execute.call_args
            self.assertNotIn('SELECT private_rows;',args[0]);self.assertEqual(b'SELECT private_rows;',kwargs['input'])
            execute.return_value=SimpleNamespace(returncode=1,stdout=b'private',stderr=b'secret')
            with self.assertRaisesRegex(ValueError,'query failed'):m.sql(self.state,'x')
            for error in (OSError('secret'),subprocess.TimeoutExpired('secret',15),UnicodeError('secret')):
                execute.side_effect=error
                with self.assertRaisesRegex(ValueError,'unavailable'):m.sql(self.state,'x')

    def test_snapshot_all_eighteen_tables_inventory_durable_nonempty_and_digest(self):
        values=[','.join(sorted(m.TABLES)),'on','on','on','t','t','c'*64]
        with patch.object(m,'sql',side_effect=values):self.assertEqual('c'*64,m.snapshot(self.state))
        for index,value in [(0,'missing'),(1,'off'),(2,'off'),(3,'off'),(4,'f'),(5,'f'),(6,'private-invalid')]:
            bad=values.copy();bad[index]=value
            with patch.object(m,'sql',side_effect=bad),self.assertRaises(ValueError):m.snapshot(self.state)
        self.assertEqual(18,len(m.TABLES));self.assertEqual(18,m.SNAPSHOT.count('FROM dm.'))
        for table in m.TABLES:self.assertIn('FROM dm.'+table+' t',m.SNAPSHOT)

    def test_action_reattests_before_any_mutation(self):
        with patch.object(m.runtime,'docker',return_value=[self.item]),patch.object(m.runtime,'owned'),patch.object(m.runtime,'command') as command:
            m.action(self.state,self.target,'stop','document-management')
            self.assertEqual(['docker','stop','--time','10',self.target],command.call_args.args[0])
            m.action(self.state,self.target,'start','document-management')
            self.assertEqual(['docker','start',self.target],command.call_args.args[0])
            self.item['Id']='c'*64
            with self.assertRaises(ValueError):m.action(self.state,self.target,'stop','document-management')
            self.assertEqual(2,command.call_count)

    def arrange(self,stack):
        stack.enter_context(patch.object(m.time,'sleep'))
        for name in ('local_daemon','verify_generation','wait_container','wait_inspector','private'):
            stack.enter_context(patch.object(m.runtime,name))
        stack.enter_context(patch.object(m.runtime,'load',return_value=self.state))
        stack.enter_context(patch.object(m,'container',return_value=self.target))
        stack.enter_context(patch.object(m,'snapshot',return_value='c'*64))
        return stack.enter_context(patch.object(m,'action')),stack.enter_context(patch.object(m,'wait_probe'))

    def test_each_fault_restores_same_target_and_checks_live_down_then_ready(self):
        from contextlib import ExitStack
        for mode,service in m.SERVICES.items():
            with self.subTest(mode=mode),ExitStack() as stack:
                action,probe=self.arrange(stack)
                result=m.run('/private/owned',mode)
                self.assertEqual('PASS',result['verdict']);self.assertEqual(18,result['tableCount'])
                self.assertEqual([(self.state,self.target,'stop',service),(self.state,self.target,'start',service)], [call.args for call in action.call_args_list])
                states=[call.args for call in probe.call_args_list]
                self.assertIn(('readiness',200),states)
                self.assertEqual(mode!='dm',('readiness',503) in states)

    def test_restore_even_when_stop_or_down_probe_fails_and_never_emit_pass(self):
        from contextlib import ExitStack
        for where in ('stop','probe'):
            with self.subTest(where=where),ExitStack() as stack:
                action,probe=self.arrange(stack)
                if where=='stop':action.side_effect=[ValueError('private'),None]
                else:probe.side_effect=[None,ValueError('private')]
                with self.assertRaises(ValueError):m.run('/private/owned','pg')
                self.assertEqual('start',action.call_args.args[2]);self.assertEqual('FAIL',json.loads(m.runtime.private.call_args.args[1])['verdict'])

    def test_hash_change_never_passes_and_snapshot_only_never_mutates(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            action,probe=self.arrange(stack)
            result=m.run('/private/owned');action.assert_not_called();self.assertEqual('snapshot',result['exercise'])
            with patch.object(m,'snapshot',side_effect=['a'*64,'a'*64,'b'*64]),self.assertRaisesRegex(ValueError,'full-state-changed'):
                m.run('/private/owned','pg')

    def test_existing_evidence_blocks_mutation_before_generation_or_probe(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            action,probe=self.arrange(stack)
            with patch.object(m.Path,'exists',return_value=True),self.assertRaises(m.AcceptanceFailure):
                m.run('/private/owned','pg')
            action.assert_not_called();probe.assert_not_called()

    def test_nonquiescent_state_fails_before_mutation_and_records_only_hashes(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            action,probe=self.arrange(stack)
            with patch.object(m,'snapshot',side_effect=['a'*64,'b'*64]),self.assertRaisesRegex(m.AcceptanceFailure,'quiescence-state-changed'):
                m.run('/private/owned','pg')
            action.assert_not_called()
            proof=json.loads(m.runtime.private.call_args.args[1])
            self.assertEqual('FAIL',proof['verdict']);self.assertEqual('a'*64,proof['beforeSha256']);self.assertEqual('b'*64,proof['afterSha256'])
            self.assertIsNone(proof['restoreCommandSucceeded'])

    def test_failed_restore_records_fixed_phase_without_private_exception(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            action,probe=self.arrange(stack)
            action.side_effect=[None,RuntimeError('secret-provider-payload')]
            with self.assertRaisesRegex(m.AcceptanceFailure,'provider-restore') as caught:m.run('/private/owned','pg')
            self.assertNotIn('secret',str(caught.exception))
            proof=json.loads(m.runtime.private.call_args.args[1]);self.assertFalse(proof['restoreCommandSucceeded']);self.assertEqual('provider-restore',proof['phase'])
            self.assertNotIn('secret',json.dumps(proof))

    def test_failed_private_evidence_write_never_turns_failure_into_pass(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            self.arrange(stack)
            m.runtime.private.side_effect=OSError('private-path')
            with self.assertRaisesRegex(m.AcceptanceFailure,'checkpoint-save'):m.run('/private/owned','pg')

    def test_optional_cleanup_wait_is_bounded_truthful_read_only_predicate(self):
        with patch.object(m.time,'monotonic',side_effect=[0,0,1]),patch.object(m.time,'sleep') as sleep,patch.object(m,'sql',side_effect=['f','t']) as sql:
            m.await_cleanup(self.state)
            sleep.assert_called_once_with(1)
            for call in sql.call_args_list:
                self.assertEqual('SELECT NOT EXISTS(SELECT 1 FROM dm.upload_cleanup_work WHERE completed_at IS NULL);',call.args[1])
        with patch.object(m.time,'monotonic',side_effect=[0,121]),self.assertRaisesRegex(ValueError,'within120s'):
            m.await_cleanup(self.state)
        with patch.object(m.time,'monotonic',side_effect=[0,0]),patch.object(m,'sql',return_value='invalid-private'),self.assertRaisesRegex(ValueError,'Invalid cleanup'):
            m.await_cleanup(self.state)

    def test_cleanup_preparation_precedes_baseline_and_never_retries_fault(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            action,probe=self.arrange(stack)
            with patch.object(m,'await_cleanup') as prepare:
                m.run('/private/owned','pg',wait_cleanup=True)
                prepare.assert_called_once_with(self.state)
                self.assertEqual(2,action.call_count)
                prepare.reset_mock()
                m.run('/private/owned','pg')
                prepare.assert_not_called()
            with patch.object(m,'await_cleanup',side_effect=ValueError('held pending cleanup')),self.assertRaisesRegex(m.AcceptanceFailure,'cleanup-settlement'):
                action.reset_mock();m.run('/private/owned','pg',wait_cleanup=True)
            action.assert_not_called()
            self.assertEqual('cleanup-settlement',json.loads(m.runtime.private.call_args.args[1])['phase'])

    def test_probe_exact_status_shape_bounded_body_no_proxy(self):
        class Response(io.BytesIO):
            code=200
        with patch.object(m.urllib.request,'build_opener') as build:
            build.return_value.open.return_value=Response(b'{"status":"UP"}')
            self.assertTrue(m.probe('liveness',200))
            build.return_value.open.return_value=Response(b'{"status":"UP","detail":"private"}')
            self.assertFalse(m.probe('liveness',200))
            build.return_value.open.return_value=Response(b' '*129)
            self.assertFalse(m.probe('liveness',200))
            error=urllib.error.HTTPError('loopback',503,'Down',{},io.BytesIO(b'{"status":"DOWN"}'))
            build.return_value.open.side_effect=error
            self.assertTrue(m.probe('readiness',503))
        self.assertIsNone(m.NoRedirect().redirect_request(None,None,302,'',{},'https://foreign'))

    def test_bounded_wait_accepts_only_valid_probe_retries_safe_errors(self):
        with patch.object(m.time,'monotonic',side_effect=[0,0,1,2]),patch.object(m.time,'sleep'),patch.object(m,'probe',side_effect=[OSError('private'),False,True]):
            m.wait_probe('readiness',200,seconds=4)
        with patch.object(m.time,'monotonic',side_effect=[0,2]),self.assertRaisesRegex(ValueError,'Bounded'):
            m.wait_probe('readiness',200,seconds=1)

    def test_cli_sanitizes_failure_and_reports_only_success(self):
        with patch.object(m,'run',side_effect=RuntimeError('private-password')),patch('sys.stdout',new_callable=io.StringIO) as output:
            self.assertEqual(1,m.main(['--directory','owned']))
            self.assertNotIn('private-password',output.getvalue())
        with patch.object(m,'run',side_effect=m.AcceptanceFailure('outage-readiness')),patch('sys.stdout',new_callable=io.StringIO) as output:
            self.assertEqual(1,m.main(['--directory','owned']))
            self.assertIn('outage-readiness',output.getvalue())
        with patch.object(m,'run'),patch('sys.stdout',new_callable=io.StringIO):
            self.assertEqual(0,m.main(['--directory','owned','--exercise','scanner','--wait-cleanup']))
            m.run.assert_called_once_with('owned','scanner',wait_cleanup=True)


if __name__=='__main__':unittest.main()
