"""Owned sequencing/semantic safety; actual HTTPS/provider execution stays required."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

s=importlib.util.spec_from_file_location('upload_faults',Path(__file__).with_name('upload-faults.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)


def artifact(index=1, verified=True, consumed=True):
    uid=lambda n:f'00000000-0000-4000-8000-{n:012d}'
    return dict(documentId=uid(index),uploadId=uid(index+10),receiptId=uid(index+20) if verified else None,versionId=uid(index+30) if consumed else None,completeKey=uid(index+40),appendKey=uid(index+50))

def fixture():return dict(tenant='tenant-a',sha256='a'*64,sizeBytes=346,consumed=artifact(),unconsumed=artifact(2,consumed=False),candidate=None)


class FaultTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.work=self.root/'work';self.state=dict(directory=str(self.root/'runtime'),project='dkp-bp03-'+'a'*12,runId='a'*32)
        self.fixture=fixture()
    def p(self,obj,name,*args,**kwargs):
        patcher=patch.object(obj,name,*args,**kwargs);value=patcher.start();self.addCleanup(patcher.stop);return value

    def test_closed_fixture_rejects_urls_unknown_fields_noncanonical_ids_and_unverified_anchors(self):
        self.assertEqual(self.fixture,m.valid_fixture(self.fixture))
        for key,value in [('tenant','foreign'),('sha256','http://evil'),('sizeBytes',True),('sizeBytes',0),('sizeBytes',100000001),('extra','x')]:
            bad=copy.deepcopy(self.fixture);bad[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):m.valid_fixture(bad)
        for key,value in [('documentId','../unsafe'),('receiptId',None),('versionId',None),('extra','x')]:
            bad=copy.deepcopy(self.fixture);bad['consumed'][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):m.valid_fixture(bad)
        bad=copy.deepcopy(self.fixture);bad['candidate']=artifact(3,False,False);m.valid_fixture(bad)
        bad['candidate']['versionId']='wrong'
        with self.assertRaises(ValueError):m.valid_fixture(bad)

    def output(self,phase,value=None):return (m.MARKER+json.dumps(dict(phase=phase,result='PASS',fixture=value or self.fixture))+'\n').encode()
    def test_browser_result_exactly_once_and_identity_continuity(self):
        self.assertEqual(self.fixture,m.result(self.output('seed'),'seed',None))
        self.assertEqual(self.fixture,m.result(self.output('verify'),'verify',self.fixture))
        for output in (b'',self.output('verify')*2,self.output('seed'),b'DKP_UPLOAD_FAULT_RESULT {"phase":"verify","result":"SKIP"}'):
            with self.assertRaises(ValueError):m.result(output,'verify',self.fixture)
        changed=copy.deepcopy(self.fixture);changed['consumed']['documentId']=artifact(4)['documentId']
        with self.assertRaisesRegex(ValueError,'identity'):m.result(self.output('verify',changed),'verify',self.fixture)
        candidate=copy.deepcopy(self.fixture);candidate['candidate']=artifact(3,False,False)
        self.assertEqual(candidate,m.result(self.output('candidate',candidate),'candidate',self.fixture))
        recovered=copy.deepcopy(candidate);recovered['candidate']=artifact(3)
        m.result(self.output('recover',recovered),'recover',candidate)
        changed=copy.deepcopy(recovered);changed['candidate']['uploadId']=artifact(5)['uploadId']
        with self.assertRaisesRegex(ValueError,'candidate'):m.result(self.output('recover',changed),'recover',candidate)
        consumed=copy.deepcopy(self.fixture);consumed['unconsumed']['versionId']=artifact(2)['versionId']
        m.result(self.output('consume',consumed),'consume',self.fixture)

    def test_real_browser_command_has_only_private_input_paths_and_captures_logs(self):
        self.work.mkdir();self.p(m,'ROOT',self.root)
        def execute(args,**kwargs):
            kwargs['stdout'].write(self.output('seed'));self.assertEqual('node',args[0]);self.assertIn('--upload-fault-input-file',args)
            self.assertEqual(900,kwargs['timeout']);self.assertNotIn('DKP_IDENTITY_PASSWORD',kwargs['env'])
            return SimpleNamespace(returncode=0)
        self.p(m.subprocess,'run',side_effect=execute)
        self.assertEqual(self.fixture,m.browser(self.work,self.state,'seed',None))
        for path in self.work.iterdir():self.assertEqual(0o600,path.stat().st_mode&0o777)
        for error in (OSError('private'),subprocess.TimeoutExpired('private',900)):
            m.subprocess.run.side_effect=error
            with self.assertRaisesRegex(ValueError,'unavailable'):m.browser(self.work,self.state,'seed',None)
        m.subprocess.run.side_effect=lambda *a,**k:SimpleNamespace(returncode=1)
        with self.assertRaisesRegex(ValueError,'phase failed'):m.browser(self.work,self.state,'seed',None)
        with self.assertRaisesRegex(ValueError,'Finite'):m.browser(self.work,self.state,'arbitrary',None)

    def test_all_tables_digest_including_correct_anchor_join_targets(self):
        sql=self.p(m.d,'sql',return_value='b'*64)
        self.assertEqual('b'*64,m.digest(self.state));self.assertEqual(18,sql.call_args.args[1].count('FROM dm.'))
        m.anchor(self.state,self.fixture);query=sql.call_args.args[1]
        self.assertIn('upload_id IN',query);self.assertIn('attempt_id IN (SELECT attempt_id FROM dm.upload_deletion_fences',query)
        self.assertNotIn('FROM dm.upload_command_outcomes t WHERE document_id',query)
        sql.return_value='private-payload'
        with self.assertRaisesRegex(ValueError,'digest'):m.digest(self.state)

    def test_anchor_binds_both_exact_receipt_states_to_current_real_inspector_and_source_hash(self):
        self.state['workerImage']='sha256:'+'c'*64
        sql=self.p(m.d,'sql',return_value='t');m.anchors_valid(self.state,self.fixture)
        query=sql.call_args.args[1]
        self.assertIn("s.state='CONSUMED'",query);self.assertIn("s.state='VERIFIED'",query)
        self.assertIn("e.source_sha256=r.sha256",query);self.assertIn("e.profile_digest='"+m.d.runtime.PROFILE+"'",query)
        self.assertIn(self.state['workerImage'],query)
        sql.return_value='f'
        with self.assertRaisesRegex(ValueError,'binding differs'):m.anchors_valid(self.state,self.fixture)

    def test_recovery_preserves_all_other_documents_tenants_and_global_facts(self):
        value=self.candidate();sql=self.p(m.d,'sql',return_value='b'*64)
        m.recovery_guard(self.state,value)
        query=sql.call_args.args[1]
        self.assertIn("NOT (tenant_id='tenant-a' AND document_id=",query)
        self.assertIn("WHERE tenant_id<>'tenant-a'",query)
        self.assertNotIn('FROM dm.upload_global_resource_account t',query)
        self.assertIn('FROM dm.upload_resource_accounts t',query)
        self.assertIn('attempt_id IN (SELECT attempt_id',query)

    def test_accounting_counts_metadata_strict_and_global_balance_guard(self):
        sql=self.p(m.d,'sql',side_effect=['t','[1,692,346,346]'])
        self.assertEqual([1,692,346,346],m.accounting(self.state))
        for result in ('f','invalid'):
            sql.side_effect=[result]
            with self.assertRaises(ValueError):m.accounting(self.state)
        for value in ('null','[]','[true,1,2,3]','[-1,1,2,3]'):
            sql.side_effect=['t',value]
            with self.assertRaisesRegex(ValueError,'metadata'):m.accounting(self.state)
        sql.side_effect=['[1,2,3,4]'];self.assertEqual([1,2,3,4],m.counts(self.state))
        sql.side_effect=['[]']
        with self.assertRaises(ValueError):m.counts(self.state)

    def candidate(self):
        value=copy.deepcopy(self.fixture);value['candidate']=artifact(3,False,False);return value
    def test_candidate_no_receipt_version_evidence_or_document_revision_can_appear_on_failure(self):
        value=self.candidate();facts=['QUARANTINED',2,0,None,True,692]
        sql=self.p(m.d,'sql',side_effect=[json.dumps(facts),'t'])
        self.assertEqual(facts,m.candidate_facts(self.state,value))
        for results in ([json.dumps(facts),'f'],['[]']):
            sql.side_effect=results
            with self.assertRaises(ValueError):m.candidate_facts(self.state,value)
        sql.side_effect=None;sql.return_value='c'*64
        m.failure_guard(self.state,value);query=sql.call_args.args[1]
        self.assertEqual(18,query.count('FROM dm.'));self.assertIn("-'state_revision'-'attempt_count'-'failure_code'",query)
        self.assertIn("operation='upload.verify.failed'",query)
        sql.return_value='[1,1]';self.assertEqual(1,m.audit_failures(self.state,value))
        sql.return_value='private'
        with self.assertRaises(ValueError):m.audit_failures(self.state,value)

    def test_failure_audit_total_and_exact_typed_binding_reject_wrong_outcome_or_identity_rows(self):
        value=self.candidate();sql=self.p(m.d,'sql',return_value='[0,0]')
        self.assertEqual(0,m.audit_failures(self.state,value))
        sql.return_value='[1,1]';self.assertEqual(1,m.audit_failures(self.state,value))
        query=sql.call_args.args[1]
        for binding in ("a.document_id=s.document_id", "a.actor_issuer=s.actor_issuer", "a.actor_subject=s.actor_subject", "a.state_revision=s.state_revision", "s.attempt_count=1", "s.state='QUARANTINED'", "a.outcome_code='DEPENDENCY_UNAVAILABLE'",value['candidate']['documentId']):
            self.assertIn(binding,query)
        # The TOTAL WHERE clause must not remove an unexpected outcome before count.
        where=query.rsplit(' WHERE ',1)[1]
        self.assertNotIn('outcome_code',where);self.assertIn(value['candidate']['uploadId'],where)
        for counts in ([2,1],[1,0],[0,1],[2,0]):
            sql.return_value=json.dumps(counts)
            with self.subTest(counts=counts),self.assertRaisesRegex(ValueError,'typed candidate'):m.audit_failures(self.state,value)
        for invalid in (None,[],[1],[-1,-1],[True,True],[1,1,1],[1.0,1.0]):
            sql.return_value=json.dumps(invalid)
            with self.subTest(invalid=invalid),self.assertRaisesRegex(ValueError,'counts'):m.audit_failures(self.state,value)

    def test_only_exact_expected_failed_attempt_delta_allowed(self):
        before=['QUARANTINED',2,0,None,True,692]
        m.check_failure(before,before.copy(),False)
        expected=['QUARANTINED',4,1,'DEPENDENCY_UNAVAILABLE',True,692]
        m.check_failure(before,expected,True)
        for index,value in [(0,'VERIFIED'),(1,5),(2,2),(3,None),(4,False),(5,0)]:
            wrong=expected.copy();wrong[index]=value
            with self.assertRaisesRegex(ValueError,'delta'):m.check_failure(before,wrong,True)
        with self.assertRaises(ValueError):m.check_failure(before,expected,False)

    def test_recovery_exact_accounting_one_of_each_immutable_fact_and_bound_consumption(self):
        value=self.candidate();value['candidate']=artifact(3)
        accounts=self.p(m,'accounting',return_value=[1,692,346,692]);counts=self.p(m,'counts',return_value=[2,3,3,2]);sql=self.p(m.d,'sql',return_value='t')
        m.check_recovered(self.state,value,[1,692,346,346],[1,2,2,1],True)
        self.assertIn('s.attempt_count=2',sql.call_args.args[1]);self.assertIn('artifact_reference=r.receipt_id::text',sql.call_args.args[1])
        accounts.return_value=[0,0,0,0]
        with self.assertRaisesRegex(ValueError,'resource'):m.check_recovered(self.state,value,[1,692,346,346],[1,2,2,1],True)
        accounts.return_value=[1,692,346,692];counts.return_value=[3,3,3,2]
        with self.assertRaisesRegex(ValueError,'exactly one'):m.check_recovered(self.state,value,[1,692,346,346],[1,2,2,1],True)
        counts.return_value=[2,3,3,2];sql.return_value='f'
        with self.assertRaisesRegex(ValueError,'mismatch'):m.check_recovered(self.state,value,[1,692,346,346],[1,2,2,1],False)

    def test_quiet_preserves_full_equality_after_real_settlement(self):
        cleanup=self.p(m.d,'await_cleanup');snapshot=self.p(m.d,'snapshot',return_value='d'*64);self.p(m.time,'sleep')
        self.assertEqual('d'*64,m.quiet(self.state));cleanup.assert_called_once_with(self.state)
        snapshot.side_effect=['a'*64,'b'*64]
        with self.assertRaisesRegex(ValueError,'quiescent'):m.quiet(self.state)

    def test_fault_always_restores_same_owned_id_and_requires_real_health(self):
        self.work.mkdir()
        self.p(m.d,'container',return_value='id');action=self.p(m.d,'action');probe=self.p(m.d,'wait_probe')
        self.p(m.d.runtime,'wait_container');self.p(m.d.runtime,'wait_inspector');browser=self.p(m,'browser',return_value=self.fixture)
        for service in m.d.SERVICES:
            action.reset_mock();m.fault(self.work,self.state,self.fixture,service,'readonly-outage')
            self.assertEqual(['stop','start'],[call.args[2] for call in action.call_args_list])
            self.assertEqual('id',action.call_args.args[1])
        browser.side_effect=ValueError('private-browser')
        with self.assertRaises(ValueError):m.fault(self.work,self.state,self.fixture,'pg','candidate-outage')
        self.assertEqual('start',action.call_args.args[2]);self.assertEqual(('liveness',200),probe.call_args.args)
        action.side_effect=[None,ValueError('private-restoration')]
        with self.assertRaises(ValueError):m.fault(self.work,self.state,self.fixture,'pg','candidate-outage')
        proofs=[json.loads(p.read_text()) for p in self.work.glob('fault-*.json')]
        self.assertTrue(any(x['verdict']=='RESTORATION_UNPROVEN' and x['restoreCommandSucceeded'] is False for x in proofs))

    def arrange_run(self):
        self.p(m,'ROOT',self.root)
        for source in m.SOURCES:
            target=self.root/source;target.parent.mkdir(parents=True,exist_ok=True);target.write_text('synthetic')
        self.p(m.d.runtime,'local_daemon');self.p(m.d.runtime,'load',return_value=self.state);self.p(m.d.runtime,'verify_generation')
        self.p(m.d.runtime,'confined',side_effect=Path);self.p(m.d,'container',return_value='owned-id');self.p(m.d,'wait_probe');self.p(m,'quiet',return_value='a'*64);self.p(m.d,'snapshot',return_value='a'*64)
        self.p(m,'anchor',return_value='b'*64);self.p(m,'anchors_valid');self.p(m,'recovery_guard',return_value='e'*64);self.p(m,'digest',return_value='f'*64);self.p(m.d,'sql',return_value='692')
        self.p(m,'accounting',side_effect=lambda state:[0,0,0,692] if self.lastphase=='consume' else ([2,1384,692,346] if self.lastphase in ('candidate','candidate-outage') else [1,692,346,346]));self.p(m,'counts',side_effect=lambda state:[2,2,2,2] if self.lastphase=='consume' else [1,2,2,1])
        self.p(m,'candidate_facts',return_value=['QUARANTINED',2,0,None,True,692]);self.p(m,'failure_guard',return_value='c'*64)
        self.p(m,'audit_failures',side_effect=lambda state,f:0 if self.lastphase=='candidate' else (0 if self.lastservice in ('pg','dm') else 1))
        self.p(m,'check_failure');self.p(m,'check_recovered')
        self.lastphase='seed';self.lastservice=None
        def phase(work,state,phase,fixture,service=None):
            self.lastphase=phase;self.lastservice=service
            value=copy.deepcopy(fixture or self.fixture)
            if phase=='candidate':value['candidate']=artifact(3,False,False)
            if phase=='recover':value['candidate']=artifact(3)
            if phase=='consume':value['unconsumed']['versionId']=artifact(2)['versionId']
            return value
        self.p(m,'browser',side_effect=phase)
        self.p(m,'fault',side_effect=lambda work,state,f,service,phase_:phase(work,state,phase_,f,service))
    def test_full_sequencing_all_five_both_states_and_fail_closed_evidence(self):
        self.arrange_run();record=m.run(self.work,'runtime')
        self.assertEqual('PASS',record['verdict']);self.assertEqual(5,len(record['results']));self.assertTrue(all(x['retry']=='PASS' for x in record['results']))
        self.assertEqual(10,m.fault.call_count);self.assertEqual('consume',m.browser.call_args.args[2]);self.assertEqual(0o600,(self.work/'state.json').stat().st_mode&0o777)
        with self.assertRaisesRegex(ValueError,'Fresh'):m.run(self.work,'runtime')
    def test_run_never_passes_changed_snapshot_or_failed_candidate_semantics(self):
        self.arrange_run();m.d.snapshot.return_value='changed'
        with self.assertRaisesRegex(ValueError,'pg-readonly'):m.run(self.work,'runtime')
        self.assertEqual('FAIL',json.loads((self.work/'state.json').read_text())['verdict'])
        m.d.snapshot.return_value='a'*64;m.failure_guard.side_effect=['a'*64,'b'*64]
        with self.assertRaisesRegex(ValueError,'pg-candidate'):m.run(self.root/'second','runtime')

    def test_extra_wrong_outcome_failure_row_cannot_pass_full_orchestrated_guard(self):
        actual_audits=m.audit_failures
        self.arrange_run()
        def sql(state,query):
            if 'FROM dm.upload_audit_facts a JOIN dm.upload_slots' in query:
                return '[2,1]' if self.lastphase=='candidate-outage' and self.lastservice=='rustfs' else '[0,0]'
            return '692'
        m.d.sql.side_effect=sql
        with patch.object(m,'audit_failures',actual_audits):
            with self.assertRaisesRegex(ValueError,'rustfs-candidate'):m.run(self.work,'runtime')
        saved=json.loads((self.work/'state.json').read_text())
        self.assertEqual('FAIL',saved['verdict']);self.assertEqual('rustfs-candidate',saved['phase'])
        self.assertNotEqual('consume',m.browser.call_args.args[2])

    def test_cli_no_raw_error_payload_and_no_false_success(self):
        run=self.p(m,'run',side_effect=ValueError('private-key'));output=self.p(__import__('builtins'),'print')
        self.assertEqual(1,m.main(['--directory','own','--runtime-directory','ready']));self.assertNotIn('private-key',output.call_args.args[0])
        run.side_effect=None;self.assertEqual(0,m.main(['--directory','own','--runtime-directory','ready']))


if __name__=='__main__':unittest.main()
