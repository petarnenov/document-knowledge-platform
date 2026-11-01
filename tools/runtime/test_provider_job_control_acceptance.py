"""Safety/order units; actual different-UID execution has a separate CLI proof."""
import copy
import importlib.util
from pathlib import Path
import unittest
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


if __name__=='__main__':unittest.main()
