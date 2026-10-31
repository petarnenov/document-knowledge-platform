"""Owned orchestration tests; mocks prove ordering/safety, not real provider acceptance."""
import base64
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('upload_runtime',Path(__file__).with_name('upload-runtime.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name).resolve();self.actual_root=m.ROOT
        for relative in m.SOURCES:
            path=self.root/relative;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes((m.ROOT/relative).read_bytes())
        self.patchers=[]
        self.p('ROOT',self.root)
        self.directory=self.root/'local-data/security/bp03-upload/run'
        self.events=[];self.run_id='a'*32;self.container='b'*64;self.sha='sha256:'+'c'*64
        self.identity=SimpleNamespace(generate=self.identity_generate)
        self.storage=SimpleNamespace(IMAGE='pinned-storage',prepare=self.storage_prepare,apply=self.storage_apply,
                                     validate=lambda p:self.events.append('storage-validate'),
                                     owned_network=lambda *a:'d'*64)
        self.clam=SimpleNamespace(IMAGE='pinned-clam',launch_updater=self.launch_update,
                                  read_record=lambda p:{'containerId':self.container},
                                  publish_completed=self.publish_update,scanner_root=lambda:self.root/'scanner')
        self.helpers={'identity':self.identity,'storage':self.storage,'clam':self.clam}

    def p(self,name,*args,**kwargs):
        patcher=patch.object(m,name,*args,**kwargs);result=patcher.start();self.addCleanup(patcher.stop);return result

    def identity_generate(self,path,**kwargs):
        self.events.append('identity');path.mkdir(mode=0o700)
        m.private(path/'runtime.env','DKP_LOCAL_UID=1001\nDKP_LOCAL_GID=1001\nOIDC_CLIENT_SECRET=synthetic-only\n')
        m.private(path/'ca.crt','synthetic-public-ca')

    def storage_prepare(self,path):
        self.events.append('storage-prepare')
        for part in ('server','app','bootstrap','spool'):(path/part).mkdir(parents=True,mode=0o700)
        for name in ('access.key','secret.key','ca.crt'):m.private(path/'app'/name,'synthetic')

    def storage_apply(self,*args,**kwargs):self.events.append('iam')
    def launch_update(self):self.events.append('real-launch');return self.directory/'updater-record.json'
    def publish_update(self,container,path,record):
        self.events.append('publish');path.mkdir(parents=True);return {}
    def fake_authority(self,directory):
        server,app=directory/'inspector/server',directory/'inspector/app'
        server.mkdir(parents=True);app.mkdir();m.private(app/'service.key','synthetic');m.private(app/'ca.crt','public')
        return server,app
    def fake_command(self,args,**kwargs):self.events.append(tuple(args[:3]));return b''
    def fake_compose(self,state,*args,**kwargs):
        self.events.append(('compose',*args))
        return self.container.encode() if args[:2]==('ps','-q') else b''
    def arrange_start(self):
        self.p('local_daemon')
        self.p('helper',side_effect=lambda n:self.helpers[n]);self.p('command',side_effect=self.fake_command)
        self.p('worker_authority',side_effect=self.fake_authority);self.p('populate_worker_volume',side_effect=lambda *a:self.events.append('worker-volume'))
        self.p('image',return_value=self.sha);self.p('wait_container',side_effect=lambda *a,**k:self.events.append('wait'))
        self.p('compose',side_effect=self.fake_compose);self.p('inspector_ready',side_effect=lambda *a:self.events.append('inspector-ready'))
        self.p('health',side_effect=lambda *a:self.events.append('health'))
        self.p('uuid',SimpleNamespace(uuid4=lambda:SimpleNamespace(hex=self.run_id)))

    def test_full_fresh_sequence_records_unique_owner_and_private_authority_without_secrets_argv(self):
        self.arrange_start();state=m.start(self.directory)
        self.assertEqual('ready',state['phase']);self.assertEqual('dkp-bp03-'+'a'*12,state['project'])
        self.assertLess(self.events.index('real-launch'),self.events.index('publish'))
        providers=('compose','up','-d','--no-build','rustfs','clamav','inspector-network','structure-inspector')
        self.assertLess(self.events.index(providers),self.events.index('iam'))
        self.assertLess(self.events.index('iam'),self.events.index(('compose','up','-d','--no-build')))
        self.assertEqual(state,m.load(self.directory))
        values=m.environment(self.directory)
        self.assertEqual(state['project'],values['DKP_UPLOAD_COMPOSE_PROJECT'])
        self.assertEqual(self.sha,values['DKP_UPLOAD_INSPECTOR_IMAGE_DIGEST'])
        self.assertEqual(m.PROFILE,values['DKP_UPLOAD_INSPECTOR_PROFILE_DIGEST'])
        self.assertEqual(str(self.directory/'inspector/app/service.key'),values['DKP_UPLOAD_INSPECTOR_KEY_FILE'])
        self.assertNotIn('bootstrap',values['DKP_UPLOAD_INSPECTOR_KEY_FILE'])
        for path in (self.directory/'runtime.env',self.directory/'state.json',self.directory/'owned-compose.json'):
            self.assertEqual(0o600,path.stat().st_mode&0o777)
        with self.assertRaisesRegex(ValueError,'fresh directory'):m.start(self.directory)

    def test_existing_foreign_project_and_failed_real_definitions_never_start_apps(self):
        self.arrange_start()
        with patch.object(m,'command',return_value=b'foreign'):
            with self.assertRaisesRegex(ValueError,'refusing adoption'):m.start(self.directory)
        self.assertNotIn('identity',self.events)
        other=self.directory.with_name('download-failure');self.directory=other
        self.clam.publish_completed=lambda *a:(_ for _ in ()).throw(ValueError('genuine definitions unavailable'))
        with self.assertRaisesRegex(ValueError,'genuine definitions'):m.start(other)
        self.assertFalse(any(isinstance(e,tuple) and e[:2]==('compose','up') for e in self.events))
        self.assertNotEqual('ready',m.load(other,material=False)['phase'])

    def test_record_source_and_private_files_reject_tampering_or_foreign_reuse(self):
        self.arrange_start();state=m.start(self.directory)
        env=self.directory/'runtime.env';before=env.read_text();env.write_text(before+'UNSAFE=1\n')
        with self.assertRaisesRegex(ValueError,'material changed'):m.load(self.directory)
        env.write_text(before);env.chmod(0o644)
        with self.assertRaises(ValueError):m.load(self.directory)
        env.chmod(0o600);source=self.root/m.SOURCES[0];source.write_text('changed')
        with self.assertRaisesRegex(ValueError,'source changed'):m.load(self.directory)
        state['project']='foreign';m.save(state)
        with self.assertRaisesRegex(ValueError,'closed runtime'):m.load(self.directory,material=False)
        for path in (self.root,self.root/'local-data/security/bp03-upload',self.root/'outside'):
            with self.assertRaises(ValueError):m.confined(path)
        link=self.root/'local-data/security/bp03-upload/link';link.symlink_to(self.directory,target_is_directory=True)
        with self.assertRaises(ValueError):m.confined(link/'child')

    def test_environment_rejects_duplicates_and_drops_ambient_credentials(self):
        self.directory.mkdir(parents=True);m.private(self.directory/'runtime.env','PATH=/synthetic/bin\nSAFE_VALUE=yes\n')
        with patch.dict(os.environ,{'AWS_SECRET_ACCESS_KEY':'must-not-forward','COMPOSE_FILE':'untrusted'}):
            values=m.environment(self.directory);self.assertNotIn('AWS_SECRET_ACCESS_KEY',values);self.assertNotIn('COMPOSE_FILE',values)
        (self.directory/'runtime.env').write_text('DUP=1\nDUP=2\n')
        with self.assertRaises(ValueError):m.environment(self.directory)
        (self.directory/'runtime.env').write_text('bad-name=value\n')
        with self.assertRaises(ValueError):m.environment(self.directory)

    def test_real_worker_tls_split_never_gives_apps_ca_or_server_private_keys(self):
        self.directory.mkdir(parents=True);server,app=m.worker_authority(self.directory)
        self.assertEqual({'ca.crt','service.key'},{p.name for p in app.iterdir()})
        self.assertEqual(32,len(base64.b64decode((app/'service.key').read_text())))
        self.assertEqual((server/'service.key').read_bytes(),(app/'service.key').read_bytes())
        self.assertTrue((self.directory/'inspector/bootstrap/ca.key').is_file())
        cert=self.directory/'inspector/bootstrap/server.crt'
        for host in ('structure-inspector','localhost'):
            result=subprocess.run(['openssl','verify','-CAfile',str(app/'ca.crt'),'-verify_hostname',host,str(cert)],capture_output=True)
            self.assertEqual(0,result.returncode)
        for p in (self.directory/'inspector').rglob('*'):
            self.assertEqual(0o700 if p.is_dir() else 0o600,p.stat().st_mode&0o777)

    def test_command_failure_and_timeout_suppress_private_diagnostics(self):
        for effect in (subprocess.TimeoutExpired(['secret'],1),OSError('private')):
            with patch.object(m.subprocess,'run',side_effect=effect),self.assertRaisesRegex(ValueError,'private diagnostics suppressed'):
                m.command(['synthetic'])
        with patch.object(m.subprocess,'run',return_value=subprocess.CompletedProcess([],1,b'private',b'secret')),self.assertRaises(ValueError):
            m.command(['synthetic'])

    def test_application_diagnostics_only_emit_fixed_categories(self):
        cases = (
            (b'all predefined address pools have been fully subnetted', 'applications-network-pool-exhausted'),
            (b'could not find an available, non-overlapping IPv4 address pool', 'applications-network-pool-exhausted'),
            (b'port is already allocated', 'applications-port-conflict'),
            (b'address already in use', 'applications-port-conflict'),
            (b'No such image', 'applications-image-unavailable'),
            (b'pull access denied', 'applications-image-unavailable'),
            (b'invalid mount config', 'applications-mount-invalid'),
            (b'bind source path does not exist', 'applications-mount-invalid'),
            (b'dependency failed to start', 'applications-dependency-unhealthy'),
            (b'is unhealthy', 'applications-dependency-unhealthy'),
            (b'\xff\x00unknown', 'applications-unknown'),
            (None, 'applications-unknown'), ('secret-text', 'applications-unknown'))
        for stderr, expected in cases:
            with self.subTest(category=expected):
                private = b' password=synthetic-secret /private/authority?token=synthetic-token '
                value = private + stderr + private if isinstance(stderr, bytes) else stderr
                self.assertEqual(expected, m.application_failure_category(value))
                with patch.object(m.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, private, value)):
                    with self.assertRaises(m.CommandFailure) as failure:
                        m.command(['synthetic'], application_start=True)
                    self.assertEqual(expected, failure.exception.category)
                    self.assertEqual('Owned command failed (' + expected + '); private diagnostics suppressed', str(failure.exception))

    def test_application_failure_keeps_prepared_state_and_never_health_or_ready(self):
        self.arrange_start()
        def compose(state, *args, **kwargs):
            if args == ('up', '-d', '--no-build'):
                raise m.CommandFailure('applications-network-pool-exhausted')
            return self.fake_compose(state, *args, **kwargs)
        with patch.object(m, 'compose', side_effect=compose):
            with self.assertRaisesRegex(m.StageFailure, 'applications-network-pool-exhausted'):
                m.start(self.directory)
        self.assertEqual('prepared', m.load(self.directory, material=False)['phase'])
        self.assertNotIn('health', self.events)

    def test_only_application_compose_start_enables_diagnostic_classification(self):
        self.directory.mkdir(parents=True)
        m.private(self.directory / 'runtime.env', 'SAFE_VALUE=yes\n')
        state = {'directory':str(self.directory), 'project':'dkp-bp03-' + 'a'*12}
        with patch.object(m, 'command', return_value=b'') as command:
            for args, expected in ((('up','-d','--no-build'), True),
                                   (('up','-d','--no-build','rustfs'), False), (('config','--quiet'), False)):
                m.compose(state, *args)
                self.assertEqual(expected, command.call_args.kwargs['application_start'])

    def test_wait_requires_owned_true_health_or_successful_updater_exit(self):
        state={'runId':self.run_id,'project':'dkp-bp03-'+'a'*12}
        item={'Config':{'Labels':m.labels(state)},'State':{'Status':'running','Health':{'Status':'healthy'}}}
        with patch.object(m,'docker',return_value=[item]):m.wait_container(self.container,state)
        for status in ({'Status':'exited','ExitCode':1},{'Status':'running','Health':{'Status':'unhealthy'}}):
            item['State']=status
            with patch.object(m,'docker',return_value=[item]),self.assertRaises(ValueError):m.wait_container(self.container,state)
        item['State']={'Status':'exited','ExitCode':0}
        with patch.object(m,'docker',return_value=[item]):m.wait_container(self.container,state,updater=True)
        item['State']['ExitCode']=1
        with patch.object(m,'docker',return_value=[item]),self.assertRaises(ValueError):m.wait_container(self.container,state,updater=True)
        item['State']={'Status':'running'}
        with patch.object(m,'docker',return_value=[item]),patch.object(m.time,'monotonic',side_effect=[0,0,4]),patch.object(m.time,'sleep') as sleep,self.assertRaisesRegex(ValueError,'deadline'):
            m.wait_container(self.container,state,seconds=2)
        sleep.assert_called_once_with(2)
        item['Config']['Labels']={}
        with patch.object(m,'docker',return_value=[item]),self.assertRaisesRegex(ValueError,'foreign'):m.wait_container(self.container,state)

    def test_owned_volume_initializer_has_no_network_or_host_private_bind_and_only_ownership_caps(self):
        state={'runId':self.run_id,'project':'dkp-bp03-'+'a'*12,'workerVolume':'owned','workerImage':self.sha}
        volume={'Labels':m.labels(state),'Driver':'local','Options':None}
        container={'Config':{'Labels':m.labels(state)},'State':{'ExitCode':0}}
        calls=[]
        def execute(args,**kwargs):calls.append(args);return self.container.encode() if args[:2]==['docker','create'] else b''
        with patch.object(m,'command',side_effect=execute),patch.object(m,'docker',side_effect=lambda *a:[volume if a[:2]==('volume','inspect') else container]):
            m.populate_worker_volume(state,self.root)
        create=next(c for c in calls if c[:2]==['docker','create'])
        self.assertIn('none',create);self.assertIn('CHOWN',create);self.assertIn('FOWNER',create);self.assertIn('--read-only',create)
        self.assertEqual(1,create.count('--mount'));self.assertFalse(any('type=bind' in a for a in create))
        self.assertEqual(3,len([c for c in calls if c[:2]==['docker','cp']]))
        self.assertEqual(['docker','rm','--force',self.container],calls[-1])
        volume['Driver']='nfs'
        with patch.object(m,'command',side_effect=execute),patch.object(m,'docker',return_value=[volume]),self.assertRaises(ValueError):m.populate_worker_volume(state,self.root)

    def test_tls_health_probe_is_owned_bounded_and_requires_exact_up_response(self):
        state={'runId':self.run_id,'project':'dkp-bp03-'+'a'*12,'directory':str(self.root)}
        item={'Config':{'Labels':m.labels(state)},'State':{'ExitCode':0}}
        calls=[]
        def execute(args,**kwargs):
            calls.append(args)
            if args[:2]==['docker','create']:return self.container.encode()
            return b'{"status":"UP"}' if args[:2]==['docker','start'] else b''
        self.p('helper',return_value=self.storage)
        with patch.object(m,'command',side_effect=execute),patch.object(m,'docker',return_value=[item]):m.inspector_ready(state)
        create=calls[0];self.assertIn('--cacert',create);self.assertIn('https://structure-inspector:8443/health/readiness',create)
        self.assertEqual(1,create.count('--mount'));self.assertIn('--read-only',create);self.assertEqual(['docker','rm','--force',self.container],calls[-1])
        item['State']['ExitCode']=1
        with patch.object(m,'command',side_effect=execute),patch.object(m,'docker',return_value=[item]),self.assertRaises(ValueError):m.inspector_ready(state)

    def test_health_checks_all_owned_services_and_pinned_worker(self):
        state={'runId':self.run_id,'project':'dkp-bp03-'+'a'*12,'workerImage':self.sha}
        item={'Config':{'Labels':m.labels(state)},'Image':self.sha,'State':{'Status':'running'}}
        with patch.object(m,'compose',return_value=self.container.encode()),patch.object(m,'docker',return_value=[item]),patch.object(m,'wait_container') as wait,patch.object(m,'inspector_ready') as inspector,patch.object(m,'attest_worker'):
            m.health(state);self.assertEqual(10,wait.call_count);inspector.assert_called_once()
            item['State']['Status']='exited'
            with self.assertRaises(ValueError):m.health(state)
        with patch.object(m,'compose',return_value=b''),self.assertRaises(ValueError):m.health(state)

    def test_live_worker_profile_rejects_capabilities_writable_authority_and_resource_widening(self):
        import copy
        state={'runId':self.run_id,'project':'dkp-bp03-'+'a'*12,'workerImage':self.sha,'networkImage':self.sha,'workerVolume':'owned'}
        worker={'Image':self.sha,'Config':{'User':'10001:10001'},'HostConfig':{
            'NetworkMode':'container:'+self.container,'ReadonlyRootfs':True,'Privileged':False,
            'CapAdd':None,'CapDrop':['ALL'],'SecurityOpt':['no-new-privileges:true'],
            'Memory':768*1024**2,'NanoCpus':1_000_000_000,'PidsLimit':64,
            'Tmpfs':{'/tmp':'uid=10001,gid=10001,mode=0700,size=1g'}},
            'Mounts':[{'Name':'owned','Destination':'/run/dkp/inspector','RW':False}]}
        network={'Image':self.sha,'Config':{'User':'0:0','Labels':m.labels(state)},'HostConfig':{
            'ReadonlyRootfs':True,'Privileged':False,'CapDrop':['ALL'],'CapAdd':['NET_ADMIN']},'Mounts':[]}
        volume={'Labels':m.labels(state),'Driver':'local','Options':None}
        def inspect(*args):return [volume if args[:2]==('volume','inspect') else network]
        with patch.object(m,'compose',return_value=self.container.encode()),patch.object(m,'docker',side_effect=inspect):
            m.attest_worker(state,worker)
            for field,value in [('ReadonlyRootfs',False),('Privileged',True),('CapAdd',['NET_ADMIN']),
                                ('Memory',0),('NanoCpus',0),('PidsLimit',0),('NetworkMode','host'),('Tmpfs',{})]:
                changed=copy.deepcopy(worker);changed['HostConfig'][field]=value
                with self.subTest(field=field),self.assertRaises(ValueError):m.attest_worker(state,changed)
            changed=copy.deepcopy(worker);changed['Mounts'][0]['RW']=True
            with self.assertRaises(ValueError):m.attest_worker(state,changed)
            network['HostConfig']['CapAdd']=['SYS_ADMIN']
            with self.assertRaises(ValueError):m.attest_worker(state,worker)
            network['HostConfig']['CapAdd']=['NET_ADMIN'];volume['Driver']='nfs'
            with self.assertRaises(ValueError):m.attest_worker(state,worker)

    def test_inspector_startup_wait_is_bounded_and_never_fakes_up(self):
        with patch.object(m,'inspector_ready',side_effect=[ValueError('starting'),None]) as ready,patch.object(m.time,'sleep'):
            m.wait_inspector({});self.assertEqual(2,ready.call_count)
        with patch.object(m,'inspector_ready',side_effect=ValueError('not-ready')),patch.object(m.time,'monotonic',side_effect=[0,0,61]),patch.object(m.time,'sleep'),self.assertRaisesRegex(ValueError,'deadline'):
            m.wait_inspector({})

    def test_probe_deadline_retains_only_safe_failure_class_and_static_stage(self):
        with patch.object(m,'inspector_ready',side_effect=m.ProbeFailure('curl-exit-60')),patch.object(m.time,'monotonic',side_effect=[0,0,61]),patch.object(m.time,'sleep'):
            with self.assertRaisesRegex(m.ProbeFailure,'startup-deadline-60s-last-curl-exit-60'):
                m.wait_inspector({})
        with patch.object(m,'inspector_ready',side_effect=ValueError('private response and credential')),patch.object(m.time,'monotonic',side_effect=[0,0,61]),patch.object(m.time,'sleep'):
            with self.assertRaises(m.StageFailure) as failure:
                m.stage('inspector-readiness',lambda:m.wait_inspector({}))
            self.assertIn('inspector-readiness',str(failure.exception))
            self.assertIn('last-ValueError',str(failure.exception))
            self.assertNotIn('credential',str(failure.exception));self.assertNotIn('private response',str(failure.exception))
        with patch('sys.argv',['upload-runtime','start','--directory',str(self.directory)]),patch.object(m,'start',side_effect=m.StageFailure('Owned BP03 stage inspector-readiness failed (curl-exit-7); private diagnostics suppressed')):
            with self.assertRaisesRegex(SystemExit,'inspector-readiness.*curl-exit-7'):
                m.main()

    def test_compose_uses_only_recorded_project_and_private_overrides_without_secret_arguments(self):
        self.directory.mkdir(parents=True);m.private(self.directory/'runtime.env','SECRET=not-in-argv\n')
        state={'directory':str(self.directory),'project':'dkp-bp03-'+'a'*12}
        with patch.object(m,'command',return_value=b'') as call:
            m.compose(state,'config','--quiet')
            args=call.call_args.args[0];self.assertNotIn('not-in-argv',args);self.assertIn(state['project'],args);self.assertIn(str(self.directory/'owned-compose.json'),args)

    def test_verify_rechecks_material_helpers_and_real_health(self):
        self.arrange_start();state=m.start(self.directory)
        with patch.object(self.identity,'generate') as identity,patch.object(m,'verify_generation') as generation:
            self.assertEqual(state,m.verify(self.directory));identity.assert_called_once()
        self.assertIn('storage-validate',self.events)

    def test_stop_removes_only_owned_resources_preserves_pg_and_host_data_and_is_idempotent(self):
        self.arrange_start();state=m.start(self.directory);state['updaterRecord']='';m.save(state)
        item={'Config':{'Labels':m.labels(state)}};network={'Labels':m.labels(state)}
        volume={'Labels':m.labels(state),'Driver':'local','Options':None}
        calls=[]
        def execute(args,**kwargs):
            calls.append(args)
            if args[:3]==['docker','ps','-aq'] and '--no-trunc' in args:return self.container.encode()
            if args[:3]==['docker','network','ls']:return b'owned-net'
            if args[:3]==['docker','volume','ls']:return state['workerVolume'].encode()
            return b''
        def inspect(*args):return [network if args[:2]==('network','inspect') else volume if args[:2]==('volume','inspect') else item]
        with patch.object(m,'command',side_effect=execute),patch.object(m,'docker',side_effect=inspect):
            m.stop(self.directory);m.stop(self.directory)
        self.assertEqual('stopped',m.load(self.directory,material=False)['phase'])
        self.assertTrue((self.directory/'runtime.env').exists());self.assertTrue((self.directory/'rustfs-data').exists())
        self.assertTrue(all('prune' not in c and '--volumes' not in c for c in calls))
        self.assertEqual([['docker','volume','rm',state['workerVolume']]],[c for c in calls if c[:3]==['docker','volume','rm']])

    def test_generation_reverification_binds_genuine_finished_time_and_exact_immutable_bytes(self):
        import hashlib
        finished=datetime.now(timezone.utc);generation=self.root/'scanner/generations'/('dkp137-'+self.run_id)
        generation.mkdir(parents=True);canonical=[]
        for family in ('bytecode','daily','main'):
            p=generation/(family+'.cvd');p.write_bytes(b'synthetic structural fixture');p.chmod(0o444)
            canonical.append(m.digest(p)+'  '+p.name+'\n')
        manifest={'schemaVersion':1,'updatedAt':finished.isoformat(),'databaseVersion':'123',
                  'databaseDigest':hashlib.sha256(''.join(canonical).encode()).hexdigest()}
        path=generation/'dkp-update.json';path.write_text(json.dumps(manifest));path.chmod(0o444);generation.chmod(0o555)
        clam=SimpleNamespace(read_record=lambda p:{},attest=lambda *a,**k:finished,scanner_root=lambda:self.root/'scanner',MAX_DATABASE_BYTES=1000)
        state={'updaterRecord':'private-record','runId':self.run_id,'definitions':str(generation)}
        with patch.object(m,'helper',return_value=clam):
            m.verify_generation(state)
            path.chmod(0o600);manifest['updatedAt']='2000-01-01T00:00:00Z';path.write_text(json.dumps(manifest));path.chmod(0o444)
            with self.assertRaisesRegex(ValueError,'freshness'):m.verify_generation(state)
            manifest['updatedAt']=finished.isoformat();manifest['databaseDigest']='0'*64
            path.chmod(0o600);path.write_text(json.dumps(manifest));path.chmod(0o444)
            with self.assertRaisesRegex(ValueError,'bytes changed'):m.verify_generation(state)
        generation.chmod(0o700)

    def test_stop_refuses_foreign_container_before_mutation(self):
        self.arrange_start();state=m.start(self.directory)
        with patch.object(m,'command',return_value=self.container.encode()) as command,patch.object(m,'docker',return_value=[{'Config':{'Labels':{}}}]),self.assertRaisesRegex(ValueError,'foreign'):
            m.stop(self.directory)
        self.assertEqual(1,command.call_count)

    def test_updater_cleanup_binds_recorded_container_and_volume_while_preserving_foreign_consumers(self):
        self.arrange_start();state=m.start(self.directory)
        record={'containerId':self.container,'containerName':'owned-updater','runId':'e'*32,'stagingVolume':'own-staging'}
        clam=SimpleNamespace(read_record=lambda p:record,labels=lambda r:{'dkp.issue':'97','dkp.clam.run':r})
        updater={'Id':self.container,'Name':'/owned-updater','Config':{'Labels':clam.labels(record['runId'])}}
        volume={'Labels':clam.labels(record['runId']),'Driver':'local','Options':None}
        calls=[]
        def execute(args,**kwargs):calls.append(args);return b''
        with patch.object(m,'helper',return_value=clam),patch.object(m,'command',side_effect=execute),patch.object(m,'docker',side_effect=lambda *a:[volume if a[:2]==('volume','inspect') else updater]):
            m.stop(self.directory)
        self.assertIn(['docker','rm','--force',self.container],calls)
        self.assertIn(['docker','volume','rm','own-staging'],calls)
        self.assertNotIn(['docker','volume','rm','dm-postgres-data'],calls)
        state['phase']='prepared';m.save(state);updater['Config']['Labels']={}
        with patch.object(m,'helper',return_value=clam),patch.object(m,'command',side_effect=execute),patch.object(m,'docker',return_value=[updater]),self.assertRaisesRegex(ValueError,'foreign updater'):
            m.stop(self.directory)

    def test_pins_context_and_profile_fail_closed_before_creating_runtime(self):
        with patch.dict(os.environ,{'DOCKER_HOST':'tcp://untrusted:2375'}),self.assertRaisesRegex(ValueError,'overrides'):
            m.local_daemon()
        for result in (b'"ssh://remote"',b'null'):
            with patch.dict(os.environ,{'DOCKER_HOST':'','DOCKER_CONTEXT':''}),patch.object(m,'command',return_value=result),self.assertRaisesRegex(ValueError,'Unix-socket'):
                m.local_daemon()
        with patch.dict(os.environ,{'DOCKER_HOST':'','DOCKER_CONTEXT':''}),patch.object(m,'command',return_value=b'"unix:///var/run/docker.sock"'):
            m.local_daemon()
        source=self.root/m.PROFILE_SOURCE;source.write_text('PROFILE="changed"')
        with self.assertRaisesRegex(ValueError,'profile'):m.start(self.directory)
        self.assertFalse(self.directory.exists())
        with patch.object(m,'docker',return_value=[{'Id':'unverified'}]),self.assertRaises(ValueError):m.image('tag')
        with patch.object(m,'command',return_value=b'{"verified":true}'):
            self.assertEqual({'verified':True},m.docker('inspect','owned'))

    def test_cli_suppresses_failures_and_never_prints_environment(self):
        for action in ('start','verify','stop'):
            with patch('sys.argv',['upload-runtime',action,'--directory',str(self.directory)]),patch.object(m,action,return_value={}) as operation,patch('builtins.print') as output:
                m.main();operation.assert_called_once();self.assertIn('no production readiness',output.call_args.args[0])
        with patch('sys.argv',['upload-runtime','start','--directory',str(self.directory)]),patch.object(m,'start',side_effect=ValueError('secret')), \
             self.assertRaisesRegex(SystemExit,'private diagnostics suppressed'):
            m.main()


if __name__=='__main__':unittest.main()
