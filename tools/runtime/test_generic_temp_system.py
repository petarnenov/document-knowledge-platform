"""Closed launcher/unit tests; genuine public SYSTEM execution is a separate CI command."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('generic_system',Path(__file__).with_name('generic-temp-system.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class SystemWorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        p=patch.object(m,'ROOT',self.root);p.start();self.addCleanup(p.stop)
        self.identifier='b'*64;self.image_id='sha256:'+'a'*64;self.run_id='c'*32;self.name='dkp360-generic-'+self.run_id[:12]
        self.bundle=self.root/'public';self.bundle.mkdir();self.classpath=['/opt/proof/0']
    def item(self):
        return {'Id':self.identifier,'Name':'/'+self.name,'Image':self.image_id,'Config':{'Image':m.IMAGE,'Env':[m.ENVIRONMENT,'PATH=/jdk/bin'],'Labels':{'dkp.issue':'360','dkp360.run':self.run_id,'dkp360.mode':'TRANSFER'},'User':'10001:10001','Entrypoint':['java'],'WorkingDir':'/fixture','Cmd':['-Xmx128m','-XX:ActiveProcessorCount=2','-cp',':'.join(self.classpath),m.CLASS,'TRANSFER']},'HostConfig':{'NetworkMode':'none','ReadonlyRootfs':True,'Privileged':False,'CapAdd':None,'CapDrop':['ALL'],'SecurityOpt':['no-new-privileges:true'],'Memory':805306368,'NanoCpus':1000000000,'PidsLimit':64,'Tmpfs':{'/fixture':'uid=10001,gid=10001,mode=0700,size=384m'}},'Mounts':[{'Type':'bind','Source':str(self.bundle),'Destination':'/opt/proof','RW':False}],'State':{'Running':False,'ExitCode':0}}
    def own(self):return m.owned(self.identifier,self.name,self.run_id,self.image_id,self.bundle,self.classpath,['PATH=/jdk/bin'],'TRANSFER')
    def test_exact_ownership_and_every_resource_boundary(self):
        good=self.item()
        with patch.object(m,'inspect',return_value=good):self.assertEqual(good,self.own())
        for group,field,bad in [(None,'Id','a'*64),(None,'Name','/foreign'),(None,'Image','foreign'),('Config','Image','tag-only'),('Config','Env',['JAVA_TOOL_OPTIONS=-agentlib:jdwp']),('Config','Labels',{}),('Config','User','0'),('Config','Entrypoint',['sh']),('Config','Cmd',[]),('Config','WorkingDir','/'),('HostConfig','NetworkMode','host'),('HostConfig','ReadonlyRootfs',False),('HostConfig','Privileged',True),('HostConfig','CapAdd',['SYS_PTRACE']),('HostConfig','CapDrop',[]),('HostConfig','SecurityOpt',[]),('HostConfig','Memory',1),('HostConfig','NanoCpus',1),('HostConfig','PidsLimit',1),('HostConfig','Tmpfs',{}),('HostConfig','PidMode','host'),('HostConfig','IpcMode','host'),('HostConfig','UTSMode','host'),('HostConfig','Devices',[{}]),('HostConfig','DeviceRequests',[{}]),(None,'Mounts',[])]:
            value=copy.deepcopy(good);(value if group is None else value[group])[field]=bad
            with self.subTest(field=field),patch.object(m,'inspect',return_value=value),self.assertRaises(ValueError):self.own()
        for field,bad in [('RW',True),('Type','volume'),('Source','/foreign'),('Destination','/etc')]:
            value=copy.deepcopy(good);value['Mounts'][0][field]=bad
            with patch.object(m,'inspect',return_value=value),self.assertRaises(ValueError):self.own()
    def test_actual_docker_explicit_env_precedes_image_defaults_without_duplicates(self):
        value=self.item()
        with patch.object(m,'inspect',return_value=value):self.own()
        for env in [['PATH=/jdk/bin',m.ENVIRONMENT],[m.ENVIRONMENT,m.ENVIRONMENT,'PATH=/jdk/bin'],['PATH=/jdk/bin']]:
            value=self.item();value['Config']['Env']=env
            with patch.object(m,'inspect',return_value=value),self.assertRaises(ValueError):self.own()

    def test_inspection_and_commands_are_bounded_and_fail_closed(self):
        with patch.object(m,'command',return_value=b'[]'),self.assertRaises(ValueError):m.inspect(self.identifier)
        with patch.object(m,'command',return_value=b'[{}]'):self.assertEqual({},m.inspect(self.identifier))
        with patch.object(m.subprocess,'run',return_value=subprocess.CompletedProcess([],0,b'ok',b'')) as call:
            self.assertEqual(b'ok',m.command(['docker','inspect']));self.assertEqual(120,call.call_args.kwargs['timeout'])
        for value in [subprocess.CompletedProcess([],1,b'private',b'private'),subprocess.CompletedProcess([],0,b'x'*4000001,b'')]:
            with patch.object(m.subprocess,'run',return_value=value),self.assertRaises(ValueError):m.command(['docker'])
        for value in [OSError('private'),subprocess.TimeoutExpired('private',90)]:
            with patch.object(m.subprocess,'run',side_effect=value),self.assertRaisesRegex(ValueError,'unavailable or timed out'):m.command(['docker'])
    def sample(self,mode='TRANSFER'):
        return {'requirement':'GTEMP-09','mode':mode,'elapsedMs':m.MODES[mode]+1,'retainedBytesBeforeRecovery':1,'realFilesystem':True,'realTcp':True,'realProviders':False,'result':'PASS'}
    def test_closed_receipt_requires_real_elapsed_retention_and_exact_mode(self):
        for mode in m.MODES:
            value=self.sample(mode);self.assertEqual(value,m.receipt(json.dumps(value,separators=(',',':')),mode))
        for field,bad in [('mode','IDLE'),('elapsedMs',299000-1),('elapsedMs',330000),('elapsedMs',True),('retainedBytesBeforeRecovery',0),('retainedBytesBeforeRecovery',100000001),('realTcp',False),('realFilesystem',False),('realProviders',True),('result','SKIP'),('requirement','other')]:
            value=self.sample();value[field]=bad
            with self.subTest(field=field,bad=bad),self.assertRaises(ValueError):m.receipt(json.dumps(value,separators=(',',':')),'TRANSFER')
        valid=json.dumps(self.sample(),separators=(',',':'))
        for bad in [valid+'\n'+valid,valid.replace('"elapsedMs":300001','"elapsedMs":1,"elapsedMs":300001'),valid.replace('"requirement"','"extra":1,"requirement"')]:
            with self.assertRaises(ValueError):m.receipt(bad,'TRANSFER')

    def test_every_mode_rejects_even_one_millisecond_before_public_budget(self):
        for mode in m.MODES:
            with self.subTest(mode=mode):
                value=self.sample(mode);value['elapsedMs']=m.MODES[mode]-1
                with self.assertRaises(ValueError):m.receipt(json.dumps(value,separators=(',',':')),mode)

    def test_each_mode_runs_distinct_owned_container_and_binds_source_and_classes(self):
        for mode in m.MODES:
            work=self.root/mode;work.mkdir()
            calls=[]
            def execute(args,timeout=120):
                calls.append((args,timeout))
                if args[:2]==['docker','create']:return self.identifier.encode()
                if args[:2]==['docker','start']:return json.dumps(self.sample(mode),separators=(',',':')).encode()
                if args[:2]==['docker','rm']:return b''
                raise AssertionError(args)
            with patch.object(m,'command',side_effect=execute),patch.object(m,'owned',return_value=self.item()),patch.object(m,'sources',return_value={'source':'hash'}):
                m.run_proof(work,self.bundle,self.classpath,{'source':'hash'},{},self.image_id,['PATH=/jdk/bin'],mode)
            result=json.loads((work/(mode+'-result.json')).read_text());self.assertEqual(mode,result['mode'])
            self.assertEqual(0o600,(work/(mode+'-result.json')).stat().st_mode&0o777)
            self.assertEqual(m.TIMEOUTS[mode],calls[1][1]);self.assertEqual(['docker','rm','--force',self.identifier],calls[-1][0])
            self.assertEqual([m.CLASS,mode],calls[0][0][-2:]);self.assertIn(m.ENVIRONMENT,calls[0][0])
    def test_failed_owned_execution_keeps_no_pass_and_never_removes_foreign_container(self):
        for scenario in ['timeout','receipt','running','source','classes','foreign','shortid']:
            work=self.root/scenario;work.mkdir();calls=[]
            def execute(args,timeout=120):
                calls.append(args)
                if args[:2]==['docker','create']:return b'short' if scenario=='shortid' else self.identifier.encode()
                if args[:2]==['docker','start']:
                    if scenario=='timeout':raise ValueError('timeout')
                    return b'{}' if scenario=='receipt' else json.dumps(self.sample(),separators=(',',':')).encode()
                return b''
            item=self.item();item['State']['Running']=scenario=='running'
            if scenario=='classes':(self.bundle/'new.class').write_bytes(b'changed')
            with patch.object(m,'command',side_effect=execute),patch.object(m,'owned',side_effect=ValueError('foreign') if scenario=='foreign' else None,return_value=item),patch.object(m,'sources',return_value={'changed':'hash'} if scenario=='source' else {'source':'hash'}),self.assertRaises(ValueError):
                m.run_proof(work,self.bundle,self.classpath,{'source':'hash'},{},self.image_id,['PATH=/jdk/bin'],'TRANSFER')
            self.assertEqual(0 if scenario in ('foreign','shortid') else 1,len([c for c in calls if c[:2]==['docker','rm']]))
            self.assertFalse((work/'TRANSFER-result.json').exists())
            if scenario=='classes':(self.bundle/'new.class').unlink()
    def test_run_compiles_once_and_requires_all_three_receipts_before_overall_pass(self):
        from contextlib import ExitStack
        for bad_image,failed in [(False,False),(True,False),(False,True)]:
            with self.subTest(bad_image=bad_image,failed=failed),ExitStack() as stack:
                stack.enter_context(patch.object(m,'compile_bundle',return_value=(self.bundle,self.classpath,{},{})))
                stack.enter_context(patch.object(m,'command',return_value=json.dumps([{'Id':'tag' if bad_image else self.image_id,'Config':{'Env':['PATH=/jdk/bin']}}]).encode()))
                proof=stack.enter_context(patch.object(m,'run_proof',side_effect=ValueError('failed') if failed else None))
                if bad_image or failed:
                    with self.assertRaises(ValueError):m.run()
                else:
                    self.assertEqual(m.RESULT,m.run());self.assertEqual(set(m.MODES),{call.args[-1] for call in proof.call_args_list})
        self.assertFalse((self.root/'local-data').exists())

    def seed_sources(self):
        for name in [*m.FIXTURES,'tools/runtime/generic-temp-system.py','gradle.properties','build.gradle','settings.gradle','gradle/libs.versions.toml','gradle/wrapper/gradle-wrapper.properties','gradle/wrapper/gradle-wrapper.jar','gradlew']:
            p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'public')
        for project in m.PROJECTS:
            p=self.root/project/'src/main/java';p.mkdir(parents=True);(p/'Example.java').write_text('class Example {}')
            for name in ['build.gradle','gradle.lockfile']:(self.root/project/name).write_text('public:library:1=runtimeClasspath\n')
    def test_sources_closed_and_compile_bundles_only_fresh_public_classes_and_locked_jars(self):
        self.seed_sources();before=m.sources();self.assertTrue(set(m.FIXTURES)<=set(before))
        cache_temp=tempfile.TemporaryDirectory();self.addCleanup(cache_temp.cleanup);cache=Path(cache_temp.name).resolve();jar=cache/'caches/modules-2/files-2.1/public/library/1/hash/library-1.jar';jar.parent.mkdir(parents=True);jar.write_bytes(b'public-jar')
        work=self.root/'work';work.mkdir();jhome=self.root/'jdk';jhome.mkdir()
        def execute(args,timeout=120):
            if ':services:document-management:adapters-out:verificationSystemClasspath' in args:
                (work/'classpath.json').write_text(json.dumps({'cache':str(cache),'jars':[str(jar),str(self.root/'ignored.jar')]}));
                for project in m.PROJECTS:
                    p=self.root/project/'build/classes/java/main';p.mkdir(parents=True);(p/'Example.class').write_bytes(b'\xca\xfe\xba\xbepublic-class')
                return b'compile complete'
            if args[-1]=='-version':return b'javac 25.0.5\n'
            if '-d' in args:(Path(args[args.index('-d')+1])/'Main.class').write_bytes(b'\xca\xfe\xba\xbepublic-main');return b''
            raise AssertionError(args)
        with patch.dict(os.environ,{'JAVA_HOME':str(jhome)}),patch.object(m,'command',side_effect=execute):
            bundle,cp,source,classes=m.compile_bundle(work)
        self.assertEqual(before,source);self.assertEqual(7,len(cp));self.assertEqual(7,len(classes));self.assertTrue(all(p.stat().st_mode&0o777==0o644 for p in bundle.rglob('*') if p.is_file()))
        link=self.root/'link';link.symlink_to(jar)
        with self.assertRaises(ValueError):m.digest(link)
        with self.assertRaises(FileExistsError):m.private(work/'sources.json','never overwrite')
    def test_launcher_build_properties_and_entire_source_ancestry_are_bound(self):
        self.seed_sources()
        before=m.sources()
        for name in ('gradle.properties','tools/runtime/generic-temp-system.py',*m.FIXTURES):
            path=self.root/name;original=path.read_bytes();path.write_bytes(b'changed')
            self.assertNotEqual(before,m.sources());path.write_bytes(original)
        tree=self.root/m.PROJECTS[0]/'src/main/java'
        external=self.root/'outside';external.mkdir();(external/'Secret.java').write_text('private')
        (tree/'linked').symlink_to(external,target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'Canonical'):m.sources()
        with self.assertRaisesRegex(ValueError,'Canonical'):m.digest(tree/'linked/Secret.java')
        (tree/'linked').unlink()
        directory=self.root/'gradle';directory.rename(self.root/'original-gradle')
        directory.symlink_to(self.root/'original-gradle',target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'Canonical'):m.sources()

    def test_compile_rejects_stale_nonclass_fake_class_cache_escape_and_changed_inputs(self):
        self.seed_sources()
        (self.root/'jdk').mkdir()
        cache_temp=tempfile.TemporaryDirectory();self.addCleanup(cache_temp.cleanup)
        cache=Path(cache_temp.name).resolve()
        jar=cache/'caches/modules-2/files-2.1/public/library/1/hash/library-1.jar'
        jar.parent.mkdir(parents=True);jar.write_bytes(b'public-jar')
        for scenario in ('nonclass','fakeclass','directorylink','jarparentlink','cachelink','launcher','properties','compiler','unlocked','schema'):
            with self.subTest(scenario=scenario):
                work=self.root/('work-'+scenario);work.mkdir()
                contaminant=None;link=None
                for project in m.PROJECTS:
                    output=self.root/project/'build/classes/java/main';output.mkdir(parents=True,exist_ok=True)
                    (output/'Example.class').write_bytes(b'\xca\xfe\xba\xbepublic')
                output=self.root/m.PROJECTS[0]/'build/classes/java/main'
                if scenario in ('nonclass','fakeclass'):
                    contaminant=output/('authority.pem' if scenario=='nonclass' else 'Fake.class')
                    contaminant.write_bytes(b'private non-class input')
                if scenario=='directorylink':
                    link=output/'outside';link.symlink_to(jar.parent,target_is_directory=True)
                exported_jar=jar;exported_cache=cache
                if scenario=='unlocked':
                    exported_jar=jar.parents[2]/'2/hash/library-2.jar';exported_jar.parent.mkdir(parents=True,exist_ok=True);exported_jar.write_bytes(b'unlocked')
                if scenario=='jarparentlink':
                    link=jar.parent.parent/'alias';link.symlink_to(jar.parent,target_is_directory=True);exported_jar=link/jar.name
                if scenario=='cachelink':
                    link=self.root/'cache-alias';link.symlink_to(cache,target_is_directory=True);exported_cache=link
                def execute(args,timeout=120):
                    if ':services:document-management:adapters-out:verificationSystemClasspath' in args:
                        (work/'classpath.json').write_text(json.dumps({'cache':str(exported_cache),'jars':[str(exported_jar)],**({'extra':True} if scenario=='schema' else {})}));return b'compiled'
                    if args[-1]=='-version':return b'javac 24' if scenario=='compiler' else b'javac 25.0.5'
                    if '-d' in args:
                        (Path(args[args.index('-d')+1])/'Main.class').write_bytes(b'\xca\xfe\xba\xbemain')
                        if scenario in ('launcher','properties'):
                            changed=self.root/('tools/runtime/generic-temp-system.py' if scenario=='launcher' else 'gradle.properties')
                            changed.write_bytes(b'changed during compile')
                        return b''
                    raise AssertionError(args)
                with patch.dict(os.environ,{'JAVA_HOME':str(self.root/'jdk')}),patch.object(m,'command',side_effect=execute),self.assertRaises(ValueError):
                    m.compile_bundle(work)
                self.assertFalse((work/'classes.json').exists())
                if contaminant:contaminant.unlink()
                if link:link.unlink()
                for name in ('gradle.properties','tools/runtime/generic-temp-system.py'):(self.root/name).write_bytes(b'public')

    def test_build_symlink_never_writes_external_or_default_security_directory(self):
        external=self.root/'external';external.mkdir();(self.root/'build').symlink_to(external,target_is_directory=True)
        with self.assertRaises(ValueError):m.run()
        self.assertEqual([],list(external.iterdir()));self.assertFalse((self.root/'local-data').exists())


if __name__=='__main__':unittest.main()
