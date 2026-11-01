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

spec=importlib.util.spec_from_file_location('system_workspace',Path(__file__).with_name('verification-workspace-system.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class SystemWorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        p=patch.object(m,'ROOT',self.root);p.start();self.addCleanup(p.stop)
        self.identifier='b'*64;self.image_id='sha256:'+'a'*64;self.run_id='c'*32;self.name='dkp340-system-'+self.run_id[:12]
        self.bundle=self.root/'public';self.bundle.mkdir();self.classpath=['/opt/proof/0']
    def item(self):
        return {'Id':self.identifier,'Name':'/'+self.name,'Image':self.image_id,'Config':{'Image':m.IMAGE,'Env':['PATH=/jdk/bin'],'Labels':{'dkp.issue':'340','dkp340.run':self.run_id},'User':'10001:10001','Entrypoint':['java'],'WorkingDir':'/fixture','Cmd':['-Xmx128m','-XX:ActiveProcessorCount=2','-cp',':'.join(self.classpath),m.CLASS]},'HostConfig':{'NetworkMode':'none','ReadonlyRootfs':True,'Privileged':False,'CapAdd':None,'CapDrop':['ALL'],'SecurityOpt':['no-new-privileges:true'],'Memory':805306368,'NanoCpus':1000000000,'PidsLimit':64,'Tmpfs':{'/fixture':'uid=10001,gid=10001,mode=0700,size=384m'}},'Mounts':[{'Type':'bind','Source':str(self.bundle),'Destination':'/opt/proof','RW':False}],'State':{'Running':False,'ExitCode':0}}
    def own(self):return m.owned(self.identifier,self.name,self.run_id,self.image_id,self.bundle,self.classpath,['PATH=/jdk/bin'])
    def test_exact_ownership_and_every_resource_boundary(self):
        good=self.item()
        with patch.object(m,'inspect',return_value=good):self.assertEqual(good,self.own())
        for group,field,bad in [(None,'Id','a'*64),(None,'Name','/foreign'),(None,'Image','foreign'),('Config','Image','tag-only'),('Config','Env',['JAVA_TOOL_OPTIONS=-agentlib:jdwp']),('Config','Labels',{}),('Config','User','0'),('Config','Entrypoint',['sh']),('Config','Cmd',[]),('Config','WorkingDir','/'),('HostConfig','NetworkMode','host'),('HostConfig','ReadonlyRootfs',False),('HostConfig','Privileged',True),('HostConfig','CapAdd',['SYS_PTRACE']),('HostConfig','CapDrop',[]),('HostConfig','SecurityOpt',[]),('HostConfig','Memory',1),('HostConfig','NanoCpus',1),('HostConfig','PidsLimit',1),('HostConfig','Tmpfs',{}),('HostConfig','PidMode','host'),('HostConfig','IpcMode','host'),('HostConfig','UTSMode','host'),('HostConfig','Devices',[{}]),('HostConfig','DeviceRequests',[{}]),(None,'Mounts',[])]:
            value=copy.deepcopy(good);(value if group is None else value[group])[field]=bad
            with self.subTest(field=field),patch.object(m,'inspect',return_value=value),self.assertRaises(ValueError):self.own()
        for field,bad in [('RW',True),('Type','volume'),('Source','/foreign'),('Destination','/etc')]:
            value=copy.deepcopy(good);value['Mounts'][0][field]=bad
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
    def arrange_run(self,stack):
        compiled=stack.enter_context(patch.object(m,'compile_bundle',return_value=(self.bundle,self.classpath,{'source':'hash'},{})))
        stack.enter_context(patch.object(m,'sources',return_value={'source':'hash'}));stack.enter_context(patch.object(m.uuid,'uuid4',return_value=type('Uuid',(),{'hex':self.run_id})()))
        stack.enter_context(patch.object(m,'owned',return_value=self.item()))
        output=''.join('SYSTEM_CASE_PASS case'+str(i)+'\n' for i in range(14))+json.dumps(m.RESULT)+'\n'
        def execute(args,timeout=120):
            if args[:3]==['docker','image','inspect']:return json.dumps([{'Id':self.image_id,'Config':{'Env':['PATH=/jdk/bin']}}]).encode()
            if args[:2]==['docker','create']:return self.identifier.encode()
            if args[:2]==['docker','start']:return output.encode()
            if args[:3]==['docker','rm','--force']:return b''
            raise AssertionError(args)
        command=stack.enter_context(patch.object(m,'command',side_effect=execute))
        return command,compiled
    def test_success_records_private_bound_proof_and_only_removes_exact_container(self):
        from contextlib import ExitStack
        with ExitStack() as stack:
            command,_=self.arrange_run(stack);self.assertEqual(m.RESULT,m.run())
            self.assertEqual(['docker','rm','--force',self.identifier],command.call_args.args[0])
            work=next((self.root/'build').iterdir());self.assertEqual(0o700,work.stat().st_mode&0o777)
            self.assertEqual(0o600,(work/'result.json').stat().st_mode&0o777)
            self.assertFalse((self.root/'local-data').exists())
            args=next(c.args[0] for c in command.call_args_list if c.args[0][:2]==['docker','create']);self.assertIn('none',args);self.assertIn(m.IMAGE,args)
    def test_failed_timeout_or_result_still_cleans_owned_but_never_foreign_identity(self):
        from contextlib import ExitStack
        for failure in ('timeout','partial','running','changedSource','changedClasses','foreign'):
            with self.subTest(failure=failure),ExitStack() as stack:
                command,_=self.arrange_run(stack);original=command.side_effect
                if failure in ('timeout','partial'):
                    def execute(args,timeout=120):
                        if args[:2]==['docker','start']:
                            if failure=='timeout':raise ValueError('timed out')
                            return b'{}\n'
                        return original(args,timeout)
                    command.side_effect=execute
                if failure=='running':m.owned.return_value['State']['Running']=True
                if failure=='changedSource':m.sources.side_effect=[{'wrong':'hash'}]
                if failure=='changedClasses':(self.bundle/'new.class').write_bytes(b'public')
                if failure=='foreign':m.owned.side_effect=ValueError('foreign')
                with self.assertRaises((ValueError,StopIteration)):m.run()
                removals=[c.args[0] for c in command.call_args_list if c.args[0][:2]==['docker','rm']]
                self.assertEqual([] if failure=='foreign' else [['docker','rm','--force',self.identifier]],removals)
                if (self.bundle/'new.class').exists():(self.bundle/'new.class').unlink()
    def test_unidentified_created_container_and_wrong_image_are_never_guessed_for_cleanup(self):
        from contextlib import ExitStack
        for value in (b'not-id',json.dumps([{'Id':'tag-only'}]).encode()):
            with ExitStack() as stack:
                command,_=self.arrange_run(stack)
                command.side_effect=lambda args,timeout=120: value if (args[:2]==['docker','create'] or b'tag-only' in value) else json.dumps([{'Id':self.image_id,'Config':{'Env':['PATH=/jdk/bin']}}]).encode()
                with self.assertRaises(ValueError):m.run()
                self.assertFalse(any(c.args[0][:2]==['docker','rm'] for c in command.call_args_list))
    def seed_sources(self):
        for name in [m.MAIN,'tools/runtime/verification-workspace-system.py','gradle.properties','build.gradle','settings.gradle','gradle/libs.versions.toml','gradle/wrapper/gradle-wrapper.properties','gradle/wrapper/gradle-wrapper.jar','gradlew']:
            p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'public')
        for project in m.PROJECTS:
            p=self.root/project/'src/main/java';p.mkdir(parents=True);(p/'Example.java').write_text('class Example {}')
            for name in ['build.gradle','gradle.lockfile']:(self.root/project/name).write_text('public')
    def test_sources_closed_and_compile_bundles_only_fresh_public_classes_and_locked_jars(self):
        self.seed_sources();before=m.sources();self.assertIn(m.MAIN,before)
        cache_temp=tempfile.TemporaryDirectory();self.addCleanup(cache_temp.cleanup);cache=Path(cache_temp.name).resolve();jar=cache/'caches/modules-2/files-2.1/public/library/1/hash/library-1.jar';jar.parent.mkdir(parents=True);jar.write_bytes(b'public-jar')
        work=self.root/'work';work.mkdir();jhome=self.root/'jdk'
        def execute(args,timeout=120):
            if len(args)>3 and 'verificationSystemClasspath' in args[3]:
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
        for name in ('gradle.properties','tools/runtime/verification-workspace-system.py'):
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
        cache_temp=tempfile.TemporaryDirectory();self.addCleanup(cache_temp.cleanup)
        cache=Path(cache_temp.name).resolve()
        jar=cache/'caches/modules-2/files-2.1/public/library/1/hash/library-1.jar'
        jar.parent.mkdir(parents=True);jar.write_bytes(b'public-jar')
        for scenario in ('nonclass','fakeclass','directorylink','jarparentlink','cachelink','launcher','properties'):
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
                if scenario=='jarparentlink':
                    link=jar.parent.parent/'alias';link.symlink_to(jar.parent,target_is_directory=True);exported_jar=link/jar.name
                if scenario=='cachelink':
                    link=self.root/'cache-alias';link.symlink_to(cache,target_is_directory=True);exported_cache=link
                def execute(args,timeout=120):
                    if len(args)>3 and 'verificationSystemClasspath' in args[3]:
                        (work/'classpath.json').write_text(json.dumps({'cache':str(exported_cache),'jars':[str(exported_jar)]}));return b'compiled'
                    if args[-1]=='-version':return b'javac 25.0.5'
                    if '-d' in args:
                        (Path(args[args.index('-d')+1])/'Main.class').write_bytes(b'\xca\xfe\xba\xbemain')
                        if scenario in ('launcher','properties'):
                            changed=self.root/('tools/runtime/verification-workspace-system.py' if scenario=='launcher' else 'gradle.properties')
                            changed.write_bytes(b'changed during compile')
                        return b''
                    raise AssertionError(args)
                with patch.dict(os.environ,{'JAVA_HOME':str(self.root/'jdk')}),patch.object(m,'command',side_effect=execute),self.assertRaises(ValueError):
                    m.compile_bundle(work)
                self.assertFalse((work/'classes.json').exists())
                if contaminant:contaminant.unlink()
                if link:link.unlink()
                for name in ('gradle.properties','tools/runtime/verification-workspace-system.py'):(self.root/name).write_bytes(b'public')

    def test_build_symlink_never_writes_external_or_default_security_directory(self):
        external=self.root/'external';external.mkdir();(self.root/'build').symlink_to(external,target_is_directory=True)
        with self.assertRaises(ValueError):m.run()
        self.assertEqual([],list(external.iterdir()));self.assertFalse((self.root/'local-data').exists())


if __name__=='__main__':unittest.main()
