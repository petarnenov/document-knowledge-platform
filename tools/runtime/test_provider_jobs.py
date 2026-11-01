"""Owned mechanism/negative tests. Real provider acceptance is a separate gate."""
from contextlib import ExitStack
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location('provider_jobs', Path(__file__).with_name('provider-jobs.py'))
m = importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class JobsTests(unittest.TestCase):
    def setUp(self):
        self.state = {'directory': '/private/owned', 'project': 'dkp-bp03-'+'a'*12, 'definitions': '/private/generation', 'sources': {'infra/docker-compose/clamd.conf': 'f'*64}}
        self.value = {'tenant': 'tenant-a', 'sizeBytes': 1000, 'sha256': 'f'*64, 'first': {'documentId': 'a'*8+'-aaaa-aaaa-aaaa-'+'a'*12, 'uploadId': 'b'*8+'-bbbb-bbbb-bbbb-'+'b'*12, 'completeKey': 'c'*8+'-cccc-cccc-cccc-'+'c'*12}, 'second': {'documentId': 'd'*8+'-dddd-dddd-dddd-'+'d'*12, 'uploadId': 'e'*8+'-eeee-eeee-eeee-'+'e'*12, 'completeKey': 'f'*8+'-ffff-ffff-ffff-'+'f'*12}}
        self.target = 'b'*64

    def test_closed_fixture_and_source_boundaries(self):
        self.assertEqual(self.value, m.fixture(self.value))
        for bad in [[], {}, {**self.value, 'extra': 1}, {**self.value, 'tenant': 'unsafe'}, {**self.value, 'sizeBytes': True}, {**self.value, 'sizeBytes': 100000001}, {**self.value, 'sha256': 'x'}, {**self.value, 'first': self.value['second']}, {**self.value, 'first': {**self.value['first'], 'uploadId': "'SQL"}}]:
            with self.assertRaises((ValueError, TypeError)):m.fixture(bad)
        with patch.object(m.runtime, 'load', return_value=self.state),patch.object(m.runtime,'helper',return_value=SimpleNamespace(attest=lambda *args:None)):m.bound(self.state)
        with patch.object(m.runtime, 'load', return_value={} ), self.assertRaises(ValueError):m.bound(self.state)
        with patch.object(m, 'bound'), patch.object(m.d, 'container', return_value=self.target):self.assertEqual(self.target, m.provider(self.state, 'clamav'))

    def test_missing_or_stale_application_attestation_refuses_before_health_sql_or_any_probe(self):
        with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
            stack.enter_context(patch.object(m.os,'getuid',return_value=1000));stack.enter_context(patch.object(m.runtime,'local_daemon'));stack.enter_context(patch.object(m.runtime,'confined',return_value=Path(tmp)/'fresh'));stack.enter_context(patch.object(m.runtime,'load',return_value=self.state))
            stack.enter_context(patch.object(m.runtime,'helper',return_value=SimpleNamespace(attest=Mock(side_effect=ValueError('stale application image')))))
            verify=stack.enter_context(patch.object(m.runtime,'verify'));probe=stack.enter_context(patch.object(m,'probe'));sql=stack.enter_context(patch.object(m.d,'sql'))
            with self.assertRaises(ValueError):m.run('fresh','runtime')
            verify.assert_not_called();probe.assert_not_called();sql.assert_not_called()

    def scanner(self):
        return {'Image': 'image', 'HostConfig': {'ReadonlyRootfs': True, 'Privileged': False, 'CapAdd': None, 'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'], 'Memory': 4294967296, 'NanoCpus': 2000000000, 'PidsLimit': 64, 'Tmpfs': {'/tmp': 'uid=100,gid=101,mode=0700,size=1g'}}, 'Config': {'Image': m.CLAM_IMAGE, 'User': '100:101', 'Entrypoint': ['clamd'], 'Cmd': ['--config-file=/run/dkp/clamd.conf']}, 'Mounts': [{'Destination': '/var/lib/clamav', 'Source': self.state['definitions'], 'RW': False}, {'Destination': '/run/dkp/clamd.conf', 'Source': str(m.ROOT/'infra/docker-compose/clamd.conf'), 'RW': False}]}

    def test_exact_live_scanner_profile_and_config_hash_before_probe(self):
        item = self.scanner()
        with patch.object(m, 'provider', return_value=self.target), patch.object(m.runtime, 'verify_generation'), patch.object(m.runtime, 'docker', side_effect=lambda *a: [item] if a[0]=='inspect' else [{'Id': 'image'}]), patch.object(m.runtime, 'command', return_value=('f'*64+'  /run/dkp/clamd.conf\n').encode()) as command:
            self.assertEqual(self.target, m.attest_scanner(self.state))
            for field, bad in [('ReadonlyRootfs', False), ('Privileged', True), ('Memory', 1), ('NanoCpus', 1), ('PidsLimit', 1), ('Tmpfs', {}), ('CapDrop', []), ('CapAdd', ['SYS_PTRACE']), ('SecurityOpt', [])]:
                old = item['HostConfig'][field];item['HostConfig'][field] = bad
                with self.assertRaises(ValueError):m.attest_scanner(self.state)
                item['HostConfig'][field] = old
            for field,bad in [('Image', 'other'), ('User', '0'), ('Entrypoint', ['sh']), ('Cmd', ['--debug'])]:
                old=item['Config'][field];item['Config'][field]=bad
                with self.assertRaises(ValueError):m.attest_scanner(self.state)
                item['Config'][field]=old
            item['Mounts'][0]['RW']=True
            with self.assertRaises(ValueError):m.attest_scanner(self.state)
            item['Mounts'][0]['RW']=False
            command.return_value=b'wrong'
            with self.assertRaises(ValueError):m.attest_scanner(self.state)

    def probe_item(self, phase='scanner'):
        mounts=[{'Destination': '/run/dkp/probe.mjs', 'Source': str(m.ROOT/'tools/runtime/provider-job-probe.mjs'), 'RW': False, 'Type': 'bind'}]
        if phase=='inspector':mounts += [{'Destination': '/run/dkp/'+name, 'Source': '/private/owned/inspector/app/'+name, 'RW': False, 'Type': 'bind'} for name in ('ca.crt','service.key')]
        return {'Id': self.target, 'Image': 'image', 'Config': {'Image': m.NODE_IMAGE, 'Labels': {'dkp.issue': '305', 'dkp.jobs.run': 'run'}, 'User': str(os.getuid())+':'+str(os.getgid()), 'Entrypoint': ['node'], 'Cmd': ['/run/dkp/probe.mjs', phase]}, 'HostConfig': {'NetworkMode': 'owned', 'ReadonlyRootfs': True, 'Privileged': False, 'CapAdd': None, 'CapDrop': ['ALL'], 'SecurityOpt': ['no-new-privileges:true'], 'Memory':134217728, 'NanoCpus':250000000, 'PidsLimit':32, 'Tmpfs':{}}, 'Mounts': mounts}

    def test_operator_probe_never_adopts_or_privileges_a_foreign_helper(self):
        for phase in ('scanner','inspector'):
            item=self.probe_item(phase)
            self.assertEqual(item, m.attest_probe(item,self.target,'image','run','owned',phase,self.state))
            for group,field,bad in [('Config','User','0'),('Config','Cmd',['sh']),('Config','Entrypoint',['sh']),('HostConfig','NetworkMode','host'),('HostConfig','ReadonlyRootfs',False),('HostConfig','Memory',1),('HostConfig','NanoCpus',1),('HostConfig','PidsLimit',1),('HostConfig','CapAdd',['NET_ADMIN']),('HostConfig','SecurityOpt',[])]:
                changed=copy.deepcopy(item);changed[group][field]=bad
                with self.assertRaises(ValueError):m.attest_probe(changed,self.target,'image','run','owned',phase,self.state)
            item['Mounts'][0]['RW']=True
            with self.assertRaises(ValueError):m.attest_probe(item,self.target,'image','run','owned',phase,self.state)
        with self.assertRaises(ValueError):m.probe(self.state,Path('/private'),'foreign')

    def result(self, phase):
        if phase=='scanner':return {'schemaVersion':1,'phase':phase,'result':'PASS','remoteCancellationAcknowledged':False,'submitted':6,'canceled':1,'clean':2,'eicar':3,'before':{'maxThreads':1,'observedQueue':0},'after':{'maxThreads':1,'observedQueue':0},'executionObservation':'CONFIG_AND_PINNED_DISPATCH_PROOF'}
        return {'schemaVersion':1,'phase':phase,'result':'PASS','remoteCancellationAcknowledged':False,'busyStatus':503,'recoveryStatus':200,'admittedObservation':'PRIVATE_SPOOL'}

    def test_closed_results_never_claim_direct_execution_or_remote_settlement(self):
        for phase in ('scanner','inspector'):
            good=self.result(phase);self.assertEqual(good,m.probe_result(good,phase))
            for key,value in [('remoteCancellationAcknowledged',True),('schemaVersion',True),('result','SKIP'),('extra',1)]:
                with self.assertRaises(ValueError):m.probe_result({**good,key:value},phase)
        for pool in [{'maxThreads':2,'observedQueue':0},{'maxThreads':True,'observedQueue':0},{'maxThreads':1,'observedQueue':3},{'maxThreads':1,'observedQueue':False}]:
            with self.assertRaises(ValueError):m.probe_result({**self.result('scanner'),'after':pool},'scanner')

    def test_private_checkpoints_deadlines_and_output_bounds(self):
        work=Path('/private/jobs')
        with patch.object(m,'browser_instance',return_value=None):
            self.assertIsNone(m.marker(work,'prepared'))
            with self.assertRaises(ValueError):m.write_marker(work,'begin')
        with patch.object(m,'browser_instance',return_value=(self.target,'image')),patch.object(m.runtime,'command',return_value=b'{"written":true}') as command:
            m.write_marker(work,'begin');self.assertIn('1001:1001',command.call_args.args[0]);self.assertEqual(5,command.call_args.kwargs['timeout'])
            command.return_value=b'{"schemaVersion":1,"checkpoint":"COMPLETE_SUBMITTED"}'
            self.assertIsNotNone(m.browser_marker(work,'submitted'))
            command.return_value=b'{}'
            with self.assertRaises(ValueError):m.browser_marker(work,'submitted')
            with self.assertRaises(ValueError):m.write_marker(work,'begin')
            command.return_value=b'x'*8193
            with self.assertRaises(ValueError):m.marker(work,'prepared')
        for name in ('x','../../foreign'):
            with self.assertRaises(ValueError):m.write_marker(work,name)
            with self.assertRaises(ValueError):m.marker(work,name)
        self.assertEqual(0,m.wait(lambda:0))
        with self.assertRaises(ValueError):m.wait(lambda:None,0)
        with patch.object(m, 'spool_count', return_value=1):self.assertEqual(1,m.observed_spool(self.state,1));self.assertIsNone(m.observed_spool(self.state,0))
        with patch.object(m,'provider',return_value=self.target),patch.object(m.runtime,'command',return_value=b'0\n'):self.assertEqual(0,m.spool_count(self.state))
        with patch.object(m,'provider'),patch.object(m.runtime,'command',return_value=b'2'),self.assertRaises(ValueError):m.spool_count(self.state)
        with patch.object(m.selectors,'DefaultSelector') as selector:
            selector.return_value.__enter__.return_value.select.return_value=True
            self.assertEqual({'phase':'scanner'},m.line(SimpleNamespace(stdout=io.BytesIO(b'{"phase":"scanner"}\n'))))
        with patch.object(m.selectors,'DefaultSelector'),self.assertRaises(ValueError):m.line(SimpleNamespace(stdout=None),0)
        for raw in (b'',b'x'*8193):
            with patch.object(m.selectors,'DefaultSelector') as selector:
                selector.return_value.__enter__.return_value.select.return_value=True
                with self.assertRaises((ValueError,json.JSONDecodeError)):m.line(SimpleNamespace(stdout=io.BytesIO(raw)))

    def test_sql_requires_full_old_evidence_charge_and_no_receipt_version_or_fence(self):
        with patch.object(m.d,'sql',side_effect=['t','t','t','f'*64]) as sql:
            self.assertEqual('f'*64,m.no_forbidden_facts(self.state,self.value))
            query=sql.call_args_list[0].args[1]
            for required in ('transient_reservation=2*declared_size','quota_held','NOT quarantine_cleanup_confirmed','possible_original_key IS NULL','upload_verification_facts','upload_receipts','upload_receipt_consumptions','upload_deletion_fences','dm.versions'):self.assertIn(required,query)
            self.assertIn('expires_at',sql.call_args_list[3].args[1])
        for values in (['f'],['t','f'],['t','t','f'],['t','t','t','bad']):
            with patch.object(m.d,'sql',side_effect=values),self.assertRaises(ValueError):m.no_forbidden_facts(self.state,self.value)
        with patch.object(m.d,'sql',return_value='t'):m.free_admissions(self.state)
        with patch.object(m.d,'sql',return_value='f'),self.assertRaises(ValueError):m.free_admissions(self.state)

    def test_full_run_only_after_fresh_profile_each_phase_and_no_skips(self):
        with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
            work=Path(tmp)/'fresh'
            for name in ('local_daemon','private'):stack.enter_context(patch.object(m.runtime,name))
            stack.enter_context(patch.object(m.os,'getuid',return_value=1000));stack.enter_context(patch.object(m.runtime,'confined',return_value=work));stack.enter_context(patch.object(m.runtime,'load',return_value=self.state));stack.enter_context(patch.object(m,'bound'));stack.enter_context(patch.object(m.runtime,'verify',return_value=self.state));stack.enter_context(patch.object(m.runtime,'digest',return_value='f'*64));stack.enter_context(patch.object(m,'free_admissions'))
            probe=stack.enter_context(patch.object(m,'probe',side_effect=[self.result('scanner'),self.result('inspector')]))
            stack.enter_context(patch.object(m,'dm_boundary',return_value={'result':'PASS'}));stack.enter_context(patch.object(m,'module',return_value=SimpleNamespace(run=lambda:{'result':'PASS'})))
            result=m.run(work,'runtime');self.assertEqual((3,0),(result['passed'],result['skipped']));self.assertEqual(2,probe.call_count)
            with self.assertRaises(ValueError):m.run(work,'runtime')
        with patch.object(m.os,'getuid',return_value=0),self.assertRaises(ValueError):m.run('x','x')

    def test_probe_gate_and_cleanup_lifecycle_never_relaxes_real_results(self):
        for phase in ('scanner','inspector'):
            with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
                work=Path(tmp)
                stack.enter_context(patch.object(m,'attest_scanner'));stack.enter_context(patch.object(m,'provider',return_value=self.target));stack.enter_context(patch.object(m.runtime,'attest_worker'));stack.enter_context(patch.object(m.runtime,'helper',return_value=SimpleNamespace(owned_network=lambda *args:'owned')));stack.enter_context(patch.object(m.runtime,'docker',return_value=[{'Id':'image','Config':{'Env':[]},'State':{'ExitCode':0}}]));command=stack.enter_context(patch.object(m.runtime,'command',return_value=self.target.encode()));own=stack.enter_context(patch.object(m,'attest_probe',return_value={'State':{'ExitCode':0}}));stack.enter_context(patch.object(m,'bound'));stack.enter_context(patch.object(m.runtime,'private'));stack.enter_context(patch.object(m,'observed_spool',return_value=0))
                process=Mock();process.stdin=io.BytesIO();process.wait.return_value=0;process.poll.return_value=None
                stack.enter_context(patch.object(m.subprocess,'Popen',return_value=process))
                lines=[]
                if phase=='inspector':lines=[{'schemaVersion':1,'phase':phase,'checkpoint':name} for name in ('PARTIAL_BODY_SUBMITTED','PARTIAL_CLIENT_CANCELED')]
                reader=stack.enter_context(patch.object(m,'line',side_effect=lines+[self.result(phase)]))
                self.assertEqual(self.result(phase),m.probe(self.state,work,phase));self.assertEqual(['docker','rm','--force',self.target],command.call_args.args[0]);process.terminate.assert_called_once();self.assertGreaterEqual(own.call_count,3)
                reader.side_effect=[{'schemaVersion':1,'phase':phase,'checkpoint':'FORGED'}] if phase=='inspector' else [{**self.result(phase),'remoteCancellationAcknowledged':True}]
                process.stdin=io.BytesIO()
                with self.assertRaises(ValueError):m.probe(self.state,work,phase)
                self.assertEqual(['docker','rm','--force',self.target],command.call_args.args[0])

    def test_exact_browser_cleanup_and_bounded_process_termination(self):
        import hashlib
        import subprocess
        work=Path('/private/jobs');digest=hashlib.sha256(str(work/'control').encode()).hexdigest()
        item={'Id':self.target,'Image':'image','Name':'/dkp-jobs-browser-'+digest[:16],'Config':{'Image':'dkp-identity-e2e:local','Labels':{'dkp.issue':'305','dkp.jobs.control':digest}}}
        with patch.object(m.runtime,'command',return_value=self.target.encode()) as command,patch.object(m.runtime,'docker',return_value=[item]):
            self.assertEqual((self.target,'image'),m.browser_instance(work))
            self.assertEqual((self.target,'image'),m.browser_instance(work,(self.target,'image')))
            with self.assertRaises(ValueError):m.browser_instance(work,('foreign','image'))
            item['Config']['Labels']['dkp.jobs.control']='foreign'
            with self.assertRaises(ValueError):m.browser_instance(work)
            command.return_value=b'';self.assertIsNone(m.browser_instance(work))
            command.return_value=b'not-id'
            with self.assertRaises(ValueError):m.browser_instance(work)
        process=Mock();process.poll.return_value=None;process.wait.side_effect=[subprocess.TimeoutExpired('synthetic',5),0]
        m.stop_process(process);process.terminate.assert_called_once();process.kill.assert_called_once()
        process=Mock();process.poll.return_value=0;m.stop_process(process);process.terminate.assert_not_called()

    def test_browser_lookup_requests_untruncated_identity_for_exact_validation(self):
        import hashlib
        work=Path('/private/jobs');digest=hashlib.sha256(str(work/'control').encode()).hexdigest()
        item={'Id':self.target,'Image':'image','Name':'/dkp-jobs-browser-'+digest[:16],'Config':{'Image':'dkp-identity-e2e:local','Labels':{'dkp.issue':'305','dkp.jobs.control':digest}}}
        def cli(args):
            self.assertEqual(['docker','ps','-aq'],args[:3])
            self.assertIn('name=^/dkp-jobs-browser-'+digest[:16]+'$',args)
            # Docker's actual quiet-list output is short unless explicitly disabled.
            return (self.target if '--no-trunc' in args else self.target[:12]).encode()+b'\n'
        with patch.object(m.runtime,'command',side_effect=cli),patch.object(m.runtime,'docker',return_value=[item]) as inspect:
            self.assertEqual((self.target,'image'),m.browser_instance(work))
            inspect.assert_called_once_with('inspect',self.target)

    def test_browser_lookup_rejects_malformed_and_foreign_cleanup_identity(self):
        import hashlib
        work=Path('/private/jobs');digest=hashlib.sha256(str(work/'control').encode()).hexdigest()
        item={'Id':self.target,'Image':'image','Name':'/dkp-jobs-browser-'+digest[:16],'Config':{'Image':'dkp-identity-e2e:local','Labels':{'dkp.issue':'305','dkp.jobs.control':digest}}}
        with patch.object(m.runtime,'command') as command,patch.object(m.runtime,'docker',return_value=[item]) as inspect:
            for raw in (self.target[:12],self.target.upper(),'g'*64,self.target+'\n'+'c'*64):
                command.return_value=raw.encode();inspect.reset_mock()
                with self.subTest(rawLength=len(raw)),self.assertRaises(ValueError):m.browser_instance(work)
                inspect.assert_not_called()
            command.return_value=self.target.encode()
            for field,bad in [('Id','c'*64),('Name','/foreign'),('Image','changed-image')]:
                changed=copy.deepcopy(item);changed[field]=bad;inspect.return_value=[changed]
                with self.subTest(field=field),self.assertRaises(ValueError):m.browser_instance(work,(self.target,'image'))
            for field,bad in [('Image','foreign:local'),('Labels',{'dkp.issue':'other','dkp.jobs.control':digest}),('Labels',{'dkp.issue':'305','dkp.jobs.control':'foreign'})]:
                changed=copy.deepcopy(item);changed['Config'][field]=bad;inspect.return_value=[changed]
                with self.subTest(config=field),self.assertRaises(ValueError):m.browser_instance(work)
            self.assertTrue(all(c.args[0][:3]==['docker','ps','-aq'] for c in command.call_args_list))

    def test_boundary_failure_stops_browser_process_but_never_removes_foreign_container(self):
        import hashlib
        with tempfile.TemporaryDirectory() as tmp,ExitStack() as stack:
            work=Path(tmp);digest=hashlib.sha256(str(work/'control').encode()).hexdigest()
            foreign={'Id':self.target,'Image':'image','Name':'/dkp-jobs-browser-'+digest[:16],'Config':{'Image':'dkp-identity-e2e:local','Labels':{'dkp.issue':'305','dkp.jobs.control':'foreign'}}}
            for name in ('private',):stack.enter_context(patch.object(m.runtime,name))
            stack.enter_context(patch.object(m,'bound'));stack.enter_context(patch.object(m,'wait',side_effect=ValueError('Synthetic boundary failure')))
            command=stack.enter_context(patch.object(m.runtime,'command',return_value=self.target.encode()))
            stack.enter_context(patch.object(m.runtime,'docker',return_value=[foreign]))
            browser=Mock();browser.poll.return_value=None
            stack.enter_context(patch.object(m.subprocess,'Popen',return_value=browser))
            with self.assertRaisesRegex(ValueError,'Foreign browser cleanup refused'):m.dm_boundary(self.state,work)
            browser.terminate.assert_called_once()
            self.assertEqual(1,command.call_count)
            self.assertEqual(['docker','ps','-aq','--no-trunc'],command.call_args.args[0][:4])

    def test_dm_real_restart_is_exact_crash_and_restore_even_when_boundary_fails(self):
        with tempfile.TemporaryDirectory() as tmp, ExitStack() as stack:
            work=Path(tmp);(work/'control').mkdir()
            for name in ('private','health','owned'):stack.enter_context(patch.object(m.runtime,name))
            stack.enter_context(patch.object(m,'bound'));stack.enter_context(patch.object(m,'provider',return_value=self.target));stack.enter_context(patch.object(m.runtime,'docker',return_value=[{}]));command=stack.enter_context(patch.object(m.runtime,'command'))
            stack.enter_context(patch.object(m,'marker',return_value=self.value));stack.enter_context(patch.object(m,'browser_marker',return_value=True));stack.enter_context(patch.object(m,'no_forbidden_facts',return_value='e'*64));stack.enter_context(patch.object(m.d,'sql',side_effect=['a'*64,'VERIFYING','b'*64]));markers=stack.enter_context(patch.object(m,'write_marker'))
            stack.enter_context(patch.object(m,'browser_instance',return_value=(self.target,'image')))
            browser=Mock();browser.poll.return_value=None;browser.wait.return_value=0
            stack.enter_context(patch.object(m.subprocess,'Popen',return_value=browser))
            self.assertEqual('PASS',m.dm_boundary(self.state,work)['result']);self.assertEqual(['begin','abort-now','restored','finish'],[c.args[1] for c in markers.call_args_list])
            self.assertEqual([['docker','kill','--signal','KILL',self.target],['docker','start',self.target],['docker','rm','--force',self.target]],[c.args[0] for c in command.call_args_list]);browser.terminate.assert_called_once()
            command.reset_mock();command.side_effect=[None,ValueError('synthetic start failure'),None,None]
            m.d.sql.side_effect=['a'*64,'VERIFYING']
            with self.assertRaises(ValueError):m.dm_boundary(self.state,work)
            self.assertIn(['docker','start',self.target],[c.args[0] for c in command.call_args_list])


if __name__=='__main__':unittest.main()
