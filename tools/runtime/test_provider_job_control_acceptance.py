"""Safety/order units; actual different-UID execution has a separate CLI proof."""
import copy
import io
import json
import subprocess
import importlib.util
from pathlib import Path
import unittest
import warnings
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('control_acceptance',Path(__file__).with_name('provider-job-control-acceptance.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class ControlTests(unittest.TestCase):
    def setUp(self):
        self.target='b'*64;self.image='sha256:'+'c'*64;self.run='a'*32
        self.item={'Id':self.target,'Image':self.image,'Config':{'Image':m.jobs.NODE_IMAGE,'Labels':{'dkp.issue':'305','dkp.control.run':self.run},'User':'1001:1001','Entrypoint':['node'],'Cmd':['-e','setTimeout(()=>{},30000)']},'Mounts':[],'HostConfig':{'NetworkMode':'none','ReadonlyRootfs':True,'CapAdd':None,'Privileged':False,'CapDrop':['ALL'],'SecurityOpt':['no-new-privileges:true'],'Memory':67108864,'NanoCpus':250000000,'PidsLimit':16,'Tmpfs':{'/tmp/dkp-job-boundary':'uid=1001,gid=1001,mode=0700,size=16k'}}}
    def test_closed_nonroot_isolated_profile_refuses_foreign_or_privileged_fixture(self):
        with patch.object(m.r,'docker',return_value=[self.item]):self.assertEqual(self.item,m.owned(self.target,self.image,self.run))
        for group,field,bad in [('Config','User','0'),('Config','Entrypoint',['sh']),('Config','Cmd',['other']),('HostConfig','NetworkMode','host'),('HostConfig','ReadonlyRootfs',False),('HostConfig','CapAdd',['SYS_PTRACE']),('HostConfig','PidsLimit',100),('HostConfig','Tmpfs',{})]:
            item=copy.deepcopy(self.item);item[group][field]=bad
            with patch.object(m.r,'docker',return_value=[item]),self.assertRaises(ValueError):m.owned(self.target,self.image,self.run)
    def arrange(self,stack,outputs):
        # Process-global stderr can also receive unrelated delayed finalizer warnings.
        # Keep those separately observable rather than treating them as our JSON record.
        self.warnings=stack.enter_context(warnings.catch_warnings(record=True))
        warnings.simplefilter('always',ResourceWarning)
        self.diagnostics=io.StringIO();stack.enter_context(patch.object(m.sys,'stderr',self.diagnostics))
        stack.enter_context(patch.object(m.r,'local_daemon'));stack.enter_context(patch.object(m.r,'image',return_value=self.image));stack.enter_context(patch.object(m,'owned',return_value=self.item))
        return stack.enter_context(patch.object(m.r,'command',side_effect=outputs))
    def test_actual_pinned_browser_uid_must_match_before_fixture_creation(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            execute=self.arrange(stack,[b'1000'])
            with self.assertRaises(ValueError):m.run()
            self.assertEqual(1,execute.call_count)

    def test_invalid_created_identity_refuses_any_exec_or_cleanup_guess(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            execute=self.arrange(stack,[b'1001',b'not-a-container-id'])
            with self.assertRaises(ValueError):m.run()
            self.assertEqual(2,execute.call_count)

    def test_real_bridge_script_used_in_both_directions_and_only_owned_cleanup(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            execute=self.arrange(stack,[b'1001',self.target.encode(),b'',b'',b'{"schemaVersion":1}',b'{"written":true}',b'PRIVATE',b'DENIED',b''])
            result=m.run();self.assertEqual('PASS',result['result']);self.assertFalse(result['providerAccess'])
            self.assertIn(m.jobs.CONTROL_SCRIPT,execute.call_args_list[5].args[0]);self.assertIn(m.jobs.CONTROL_SCRIPT,execute.call_args_list[4].args[0])
            self.assertIn('1000:1000',execute.call_args_list[7].args[0]);self.assertEqual(['docker','rm','--force',self.target],execute.call_args.args[0])
            create=execute.call_args_list[1].args[0];self.assertIn('none',create);self.assertIn('--read-only',create);self.assertNotIn('--mount',create)
    def test_any_missing_read_write_private_mode_or_other_uid_denial_fails_but_cleans_own_fixture(self):
        from contextlib import ExitStack
        good=[b'1001',self.target.encode(),b'',b'',b'{"schemaVersion":1}',b'{"written":true}',b'PRIVATE',b'DENIED',b'']
        for index in (4,5,6,7):
            outputs=good[:index]+[b'{}']+[b'']
            with ExitStack() as stack:
                execute=self.arrange(stack,outputs)
                with self.assertRaises(ValueError):m.run()
                self.assertEqual(['docker','rm','--force',self.target],execute.call_args.args[0])

    def test_actual_failures_bind_exact_stage_boundary_and_preserve_owned_cleanup(self):
        from contextlib import ExitStack
        good=[b'1001',self.target.encode(),b'',b'',b'{"schemaVersion":1}',b'{"written":true}',b'PRIVATE',b'DENIED',b'']
        cases=[(0,m.Stage.BROWSER_UID,'NOT_CREATED'),(1,m.Stage.CREATE,'UNKNOWN'),(2,m.Stage.START,'REMOVED'),(3,m.Stage.PREPARE,'REMOVED'),(4,m.Stage.READ,'REMOVED'),(5,m.Stage.WRITE,'REMOVED'),(6,m.Stage.MODES,'REMOVED'),(7,m.Stage.DENIAL,'REMOVED'),(8,m.Stage.CLEANUP,'UNKNOWN')]
        for index,stage,cleanup in cases:
            outputs=good[:index]+[m.r.CommandFailure('command-timeout')]+([b''] if 1<index<8 else [])
            with ExitStack() as stack:
                execute=self.arrange(stack,outputs)
                with self.assertRaises(m.ControlFailure) as failed:m.run()
                self.assertEqual(stage,failed.exception.stage)
                record=json.loads(self.diagnostics.getvalue())
                self.assertEqual({'schemaVersion':1,'issue':413,'result':'FAIL','stage':stage.value,'boundary':'COMMAND','category':'TIMEOUT','controlFixtureCleanup':cleanup},record)
                if 1<index<8:self.assertEqual(['docker','rm','--force',self.target],execute.call_args.args[0])

    def test_unrelated_finalizer_warning_is_separate_from_exact_closed_diagnostic(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            execute=self.arrange(stack,[m.r.CommandFailure('command-timeout')])
            original=m.diagnostic
            def during_diagnostic(*args):
                warnings.warn('unrelated finalizer',ResourceWarning)
                return original(*args)
            stack.enter_context(patch.object(m,'diagnostic',side_effect=during_diagnostic))
            with self.assertRaises(m.ControlFailure):m.run()
            self.assertEqual(1,execute.call_count)
            self.assertEqual({'schemaVersion':1,'issue':413,'result':'FAIL','stage':'BROWSER_UID','boundary':'COMMAND','category':'TIMEOUT','controlFixtureCleanup':'NOT_CREATED'},json.loads(self.diagnostics.getvalue()))
            self.assertNotIn('unrelated finalizer',self.diagnostics.getvalue())
            self.assertEqual(1,len(self.warnings))
            self.assertIs(ResourceWarning,self.warnings[0].category)

    def test_unknown_ownership_never_removes_or_claims_removed_even_if_prior_body_failed(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            execute=self.arrange(stack,[b'1001',self.target.encode(),b'',RuntimeError('secret://host/token')])
            stack.enter_context(patch.object(m,'owned',side_effect=[self.item,self.item,ValueError('private/path')]))
            with self.assertRaises(m.ControlFailure) as failed:m.run()
            self.assertEqual(m.Stage.CLEANUP,failed.exception.stage)
            record=json.loads(self.diagnostics.getvalue())
            self.assertEqual('OWNERSHIP',record['boundary']);self.assertEqual('UNKNOWN',record['controlFixtureCleanup'])
            self.assertFalse(any(x.args[0][:2]==['docker','rm'] for x in execute.call_args_list))
            self.assertNotIn('secret',self.diagnostics.getvalue());self.assertNotIn('private/path',self.diagnostics.getvalue())

    def test_source_validation_failures_cannot_escape_diagnostics_or_become_acceptance(self):
        from contextlib import ExitStack
        good=[b'1001',self.target.encode(),b'',b'',b'{"schemaVersion":1}',b'{"written":true}',b'PRIVATE',b'DENIED',b'']
        for index,stage in [(4,'READ'),(5,'WRITE'),(6,'MODES'),(7,'DENIAL')]:
            outputs=good[:index]+[b'{"secret":"token://private-host/path"}']+[b'']
            with ExitStack() as stack:
                self.arrange(stack,outputs)
                with self.assertRaises(m.ControlFailure):m.run()
                record=json.loads(self.diagnostics.getvalue())
                self.assertEqual(stage,record['stage']);self.assertEqual('VALIDATION',record['boundary'])
                self.assertEqual('REMOVED',record['controlFixtureCleanup']);self.assertNotIn('secret',self.diagnostics.getvalue())
        with ExitStack() as stack:
            self.arrange(stack,[b'1001',self.target.encode(),b'',b'',b'not-json',b''])
            with self.assertRaises(m.ControlFailure):m.run()
            self.assertEqual('VALIDATION',json.loads(self.diagnostics.getvalue())['category'])

    def test_public_schema_and_classifier_are_closed_even_for_malicious_errors(self):
        self.check_public_schema_and_classifier()

    def test_classifier_keeps_finalizer_warning_separate_from_exact_hostile_error_records(self):
        self.check_public_schema_and_classifier(emit_warning=True)

    def check_public_schema_and_classifier(self,emit_warning=False):
        errors=[(m.r.CommandFailure('command-unavailable'),m.Category.COMMAND_UNAVAILABLE),
                (m.r.CommandFailure('raw-token=https://private/path'),m.Category.COMMAND_FAILED),
                (ValueError('private SQL password'),m.Category.VALIDATION),
                (OSError('private filesystem'),m.Category.LOCAL_IO),
                (RuntimeError('secret-token'),m.Category.UNKNOWN)]
        for error,expected in errors:
            self.assertEqual(expected,m.category(error))
            capture=io.StringIO()
            def fail(*args):
                if emit_warning:warnings.warn('synthetic delayed finalizer',ResourceWarning)
                raise error
            with warnings.catch_warnings(record=True) as observed,patch.object(m.sys,'stderr',capture),patch.object(m,'execute_fixture',side_effect=fail):
                warnings.simplefilter('always',ResourceWarning)
                with self.assertRaises(m.ControlFailure):m.run()
            record=json.loads(capture.getvalue())
            self.assertEqual({'schemaVersion','issue','result','stage','boundary','category','controlFixtureCleanup'},set(record))
            self.assertLessEqual(len(capture.getvalue()),220)
            self.assertNotIn(str(error),capture.getvalue())
            self.assertEqual(expected.value,record['category'])
            if emit_warning:
                synthetic=[warning for warning in observed if warning.category is ResourceWarning and str(warning.message)=='synthetic delayed finalizer']
                self.assertEqual(1,len(synthetic))
        for values in [('arbitrary',m.Boundary.COMMAND,m.Category.UNKNOWN),(m.Stage.READ,'arbitrary',m.Category.UNKNOWN),(m.Stage.READ,m.Boundary.COMMAND,'arbitrary')]:
            with self.assertRaises(ValueError):m.ControlFailure(*values)
        with self.assertRaises(ValueError):m.step('user-input',m.Boundary.COMMAND,lambda:None)
        with self.assertRaises(ValueError):m.step(m.Stage.READ,'user-input',lambda:None)
        with self.assertRaises(ValueError):m.diagnostic('raw-token',m.Stage.READ,m.Boundary.COMMAND,m.Category.UNKNOWN,m.Cleanup.UNKNOWN)
        with self.assertRaises(ValueError):m.diagnostic('FAIL','raw-token',m.Boundary.COMMAND,m.Category.UNKNOWN,m.Cleanup.UNKNOWN)
        with self.assertRaises(ValueError):m.diagnostic('FAIL',m.Stage.READ,'raw-token',m.Category.UNKNOWN,m.Cleanup.UNKNOWN)
        with self.assertRaises(ValueError):m.diagnostic('FAIL',m.Stage.READ,m.Boundary.COMMAND,'raw-token',m.Cleanup.UNKNOWN)
        with self.assertRaises(ValueError):m.diagnostic('FAIL',m.Stage.READ,m.Boundary.COMMAND,m.Category.UNKNOWN,'raw-token')
        with self.assertRaises(m.ControlFailure) as failed:m.step(m.Stage.READ,m.Boundary.COMMAND,lambda:(_ for _ in ()).throw(m.ControlFailure(m.Stage.PREPARE,m.Boundary.OWNERSHIP,m.Category.VALIDATION)))
        self.assertEqual(m.Stage.PREPARE,failed.exception.stage)

    def test_diagnostic_sink_failure_cannot_override_known_result_or_failed_acceptance(self):
        from contextlib import ExitStack
        class Broken:
            def write(self,text):raise RuntimeError('private diagnostics unavailable')
            def flush(self):pass
        good=[b'1001',self.target.encode(),b'',b'',b'{"schemaVersion":1}',b'{"written":true}',b'PRIVATE',b'DENIED',b'']
        with ExitStack() as stack:
            self.arrange(stack,good);stack.enter_context(patch.object(m.sys,'stderr',Broken()))
            self.assertEqual('PASS',m.run()['result'])
        with patch.object(m.sys,'stderr',Broken()),patch.object(m,'execute_fixture',side_effect=ValueError('private')):
            with self.assertRaises(m.ControlFailure):m.run()


if __name__=='__main__':unittest.main()
