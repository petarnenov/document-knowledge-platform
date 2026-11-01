import copy
import contextlib
import io
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

HERE=Path(__file__).resolve().parent
def load(name,file):
    spec=importlib.util.spec_from_file_location(name,HERE/file);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
v=load('verify427','verify.py');inside=load('inside427','python_runtime_inside.py')

class CheckTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve();self.source=self.root/'source';self.source.mkdir();self.evidence=self.root/'evidence';self.evidence.mkdir()
        subprocess.run(['git','init','-q',str(self.source)],check=True)
        subprocess.run(['git','-C',str(self.source),'config','user.email','public@example.invalid'],check=True)
        subprocess.run(['git','-C',str(self.source),'config','user.name','Synthetic'],check=True)
        (self.source/'public.txt').write_text('public');subprocess.run(['git','-C',str(self.source),'add','.'],check=True);subprocess.run(['git','-C',str(self.source),'commit','-qm','public'],check=True)

    def helper_report(self):
        return {'files':{'/workspace/tools/runtime/'+name:{'summary':{'covered_lines':3,'num_statements':4,'covered_branches':3,'num_branches':4}} for name in inside.HELPERS+inside.RUNNER_HELPERS}}

    def receipt(self):
        check={'schemaVersion':1,'result':'PASS','stage':'COMPLETE','python':'3.14.10','coverage':'7.17.0','coverageCore':'ctrace','runnerHelpers':inside.selected_floors(self.helper_report(),inside.RUNNER_HELPERS),'java':'openjdk version "25.0.5"','commands':inside.COMMANDS,'helpers':inside.floors(self.helper_report(),self.source)}
        log=v.MARKER+json.dumps(check).encode()+b'\n';(self.evidence/'check.private.log').write_bytes(log)
        return {'schemaVersion':1,'profile':'python-runtime','result':'PASS','stage':'COMPLETE','cleanup':'REMOVED','source':v.snapshot(self.source),'runner':v.runner_binding(),'pins':json.loads((HERE/'python-runtime-pins.json').read_text()),'platform':'linux/amd64','imageId':'sha256:'+'a'*64,'check':check,'logSha256':v.sha(log)}

    def test_local_daemon_rejects_remote_context_and_environment_overrides(self):
        with patch.dict(os.environ,{},clear=True):
            for endpoint in ('tcp://remote:2375','ssh://remote',None):
                with patch.object(v,'command',return_value=json.dumps(endpoint).encode()),self.assertRaises(ValueError):v.local_daemon()
            with patch.object(v,'command',return_value=b'"unix:///var/run/docker.sock"'):v.local_daemon()
            for key in ('DOCKER_HOST','DOCKER_CONTEXT'):
                with patch.dict(os.environ,{key:'remote'}),patch.object(v,'command') as cmd,self.assertRaises(ValueError):v.local_daemon()
                cmd.assert_not_called()

    def test_unstaged_executable_bit_invalidates_evidence_and_sealed_copy(self):
        original=v.snapshot(self.source);(self.source/'public.txt').chmod(0o755)
        changed=v.snapshot(self.source,self.root/'executable')
        self.assertEqual(original['commit'],changed['commit']);self.assertEqual(original['indexSha256'],changed['indexSha256'])
        self.assertNotEqual(original['fingerprint'],changed['fingerprint']);self.assertEqual(0o555,(self.root/'executable/public.txt').stat().st_mode&0o777)
        (self.root/'executable/public.txt').chmod(0o600);(self.root/'executable').chmod(0o700)

    def test_locality_failure_retains_finite_failed_receipt_before_any_source_or_provider_access(self):
        target=self.root/'preflight'
        with patch.object(v,'local_daemon',side_effect=ValueError('fixed')),patch.object(v,'snapshot') as snapshot,self.assertRaises(ValueError):v.run(self.source,target)
        snapshot.assert_not_called();record=json.loads((target/'receipt.json').read_text());self.assertEqual(('FAIL','PREFLIGHT','NOT_CREATED'),(record['result'],record['stage'],record['cleanup']))

    def test_snapshot_binds_worktree_index_and_commit_without_git_or_private_copy(self):
        dest=self.root/'snapshot';original=v.snapshot(self.source,dest);self.assertEqual('public',(dest/'public.txt').read_text());self.assertFalse((dest/'.git').exists());self.assertEqual(0o444,(dest/'public.txt').stat().st_mode&0o777)
        (self.source/'public.txt').write_text('changed');self.assertNotEqual(original,v.snapshot(self.source));subprocess.run(['git','-C',str(self.source),'add','.'],check=True);self.assertNotEqual(original['indexSha256'],v.snapshot(self.source)['indexSha256'])
        (dest/'public.txt').chmod(0o600);dest.chmod(0o700)

    def test_snapshot_rejects_parent_alias_and_tracked_symlink(self):
        alias=self.root/'alias';alias.symlink_to(self.source,target_is_directory=True)
        with self.assertRaises(ValueError):v.snapshot(alias)
        (self.source/'outside').symlink_to(self.root/'foreign');(self.root/'foreign').write_text('foreign');subprocess.run(['git','-C',str(self.source),'add','.'],check=True)
        with self.assertRaises(ValueError):v.snapshot(self.source)

    def test_present_feature_helper_is_mandatory_not_an_optional_floor(self):
        report=self.helper_report();self.assertEqual(set(inside.HELPERS),set(inside.floors(report,self.source)))
        parent=self.source/'tools/runtime';parent.mkdir(parents=True)
        for name in inside.FEATURE_HELPERS:
            (parent/name).write_text('public')
            with self.assertRaises(ValueError):inside.floors(report,self.source)
            report['files']['/workspace/tools/runtime/'+name]={'summary':{'covered_lines':3,'num_statements':4,'covered_branches':3,'num_branches':4}}
        self.assertEqual(10,len(inside.floors(report,self.source)))
        for key,value in [('num_statements',0),('num_branches',0),('covered_lines',2),('covered_branches',2)]:
            bad=copy.deepcopy(report);bad['files']['/workspace/tools/runtime/clam-definitions.py']['summary'][key]=value
            with self.assertRaises(ValueError):inside.floors(bad,self.source)

    def test_evidence_rejects_source_log_command_environment_and_cleanup_drift(self):
        record=self.receipt();path=self.evidence/'receipt.json';path.write_text(json.dumps(record));self.assertEqual(record,v.validate(self.source,self.evidence))
        for key,bad in [('result','FAIL'),('cleanup','UNKNOWN'),('platform','linux/arm64'),('imageId','tag')]:
            changed={**record,key:bad};path.write_text(json.dumps(changed))
            with self.assertRaises(ValueError):v.validate(self.source,self.evidence)
        path.write_text(json.dumps(record));(self.evidence/'check.private.log').write_bytes(b'changed')
        with self.assertRaises(ValueError):v.validate(self.source,self.evidence)
        record=self.receipt();record['check']['commands']=record['check']['commands'][:-1];path.write_text(json.dumps(record))
        with self.assertRaises(ValueError):v.validate(self.source,self.evidence)
        record=self.receipt();path.write_text(json.dumps(record));(self.source/'public.txt').write_text('late')
        with self.assertRaises(ValueError):v.validate(self.source,self.evidence)

    def test_owned_cleanup_never_guesses_foreign_container(self):
        item={'Id':'a'*64,'Image':'sha256:'+'b'*64,'Config':{'Labels':{'dkp.check.run':'c','dkp.issue':'427'}}}
        with patch.object(v,'inspect',return_value=item):self.assertEqual(item,v.owned('a'*64,'c','sha256:'+'b'*64))
        for identifier,run,image in [('wrong','c',item['Image']),(item['Id'],'foreign',item['Image']),(item['Id'],'c','foreign')]:
            with patch.object(v,'inspect',return_value=item),self.assertRaises(ValueError):v.owned(identifier,run,image)

    def test_fresh_evidence_and_build_failure_cannot_launch_check(self):
        with self.assertRaises(ValueError):v.run(self.source,self.evidence)
        with patch.object(v,'local_daemon'),patch.object(v,'logged',return_value=1),patch.object(v,'owned') as owned,self.assertRaises(ValueError):v.run(self.source,self.root/'failed')
        owned.assert_not_called();r=json.loads((self.root/'failed/receipt.json').read_text());self.assertEqual('FAIL',r['result']);self.assertEqual('NOT_CREATED',r['cleanup'])

    def test_actual_command_profile_success_and_timeout_always_remove_only_owned_id(self):
        image='sha256:'+'b'*64;identifier='a'*64;original=v.command
        child=self.receipt()['check'];removed=[]
        for timeout in (False,True):
            target=self.root/('timeout' if timeout else 'success')
            def execute(args,**kwargs):
                if args[:2]==['docker','create']:
                    self.assertIn('none',args);self.assertIn('--read-only',args);self.assertIn('10001:10001',args);self.assertNotIn('/var/run/docker.sock',' '.join(args));return identifier.encode()
                if args[:2]==['docker','rm']:removed.append(args);return b''
                return original(args,**kwargs)
            def logged(args,path,seconds):
                self.assertEqual(600,seconds)
                if args[:2]==['docker','build']:(target/'image.id').write_text(image);return 0
                if timeout:raise subprocess.TimeoutExpired('fixed check',seconds)
                path.write_bytes(v.MARKER+json.dumps(child).encode()+b'\n');return 0
            with patch.object(v,'local_daemon'),patch.object(v,'command',side_effect=execute),patch.object(v,'logged',side_effect=logged),patch.object(v,'inspect',return_value={'Os':'linux','Architecture':'amd64'}),patch.object(v,'owned',return_value={'State':{'Running':False,'ExitCode':0}}):
                if timeout:
                    with self.assertRaises(subprocess.TimeoutExpired):v.run(self.source,target)
                else:self.assertEqual('PASS',v.run(self.source,target)['result'])
            record=json.loads((target/'receipt.json').read_text());self.assertEqual('REMOVED',record['cleanup']);self.assertEqual('FAIL' if timeout else 'PASS',record['result'])
        self.assertEqual([['docker','rm','--force',identifier]]*2,removed)

    def test_container_entrypoint_requires_real_versions_commands_and_floors(self):
        for failing in (False,True):
            capture=io.StringIO()
            effect=subprocess.CalledProcessError(1,'fixed') if failing else None
            with patch.dict(os.environ,{'COVERAGE_CORE':'ctrace'}),patch.object(inside.subprocess,'check_output',return_value=b'container warning\nopenjdk version "25.0.5"\n'),patch.object(inside.subprocess,'run',side_effect=effect),patch.object(inside,'helper_names',return_value=inside.HELPERS),patch.object(inside.Path,'read_text',return_value=json.dumps(self.helper_report())),patch.object(inside.platform,'python_version',return_value='3.14.10'),contextlib.redirect_stdout(capture):
                self.assertEqual(1 if failing else 0,inside.main())
            record=json.loads(capture.getvalue().split('\t',1)[1]);self.assertEqual('FAIL' if failing else 'PASS',record['result']);self.assertEqual(1 if failing else len(inside.COMMANDS),len(record['commands']))
        with patch.object(inside.subprocess,'check_output',return_value=b'openjdk version "25.0.5"'),patch.object(inside.platform,'python_version',return_value='wrong'),patch.object(inside.subprocess,'run') as run,contextlib.redirect_stdout(io.StringIO()):self.assertEqual(1,inside.main())
        run.assert_not_called()

if __name__=='__main__':unittest.main()
