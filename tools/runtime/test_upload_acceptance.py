"""151 orchestration safety tests. Mocked Docker/curl are not real-provider acceptance."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid

spec=importlib.util.spec_from_file_location('upload_acceptance',Path(__file__).with_name('upload-acceptance.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class AcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name).resolve();self.original=m.ROOT
        self.p('ROOT',self.root)
        for relative in m.BOUND_SOURCES:
            path=self.root/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes((self.original/relative).read_bytes())
        self.fixture=self.root/'local-data/security/fixture104';self.fixture.mkdir(parents=True,mode=0o700)
        for directory in ('server','app'):(self.fixture/directory).mkdir(mode=0o700)
        self.state={'container':'dkp104-'+'a'*12,'network':'dkp104-'+'a'*12+'-net','data':'dkp104-'+'a'*12+'-data',
                    'server':'dkp104-'+'a'*12+'-server','authority':'dkp104-'+'a'*12+'-authority','image':m.IMAGE,
                    'tenant':'rustfs-compat-tenant','document':str(uuid.uuid4()),'endpoint':'https://localhost:19900'}
        self.state['prefix']=hashlib.sha256(self.state['tenant'].encode()).hexdigest()+'/'+self.state['document']+'/'
        for name,value in {'state.json':json.dumps(self.state),'server/root-access':'a'*32+'\n','server/root-secret':'b'*64+'\n',
                           'root-curl.private':'user = "'+'a'*32+':'+'b'*64+'"\n','ca.pem':'public-ca','app/ca.pem':'public-ca',
                           'app/access-key':'c'*32,'app/secret-key':'d'*64,'app/cleanup-access-key':'e'*32,'app/cleanup-secret-key':'f'*64}.items():m.private(self.fixture/name,value)
        for prefix, role, access, secret in [('', 'normal', 'c'*32, 'd'*64), ('cleanup-', 'cleanup', 'e'*32, 'f'*64)]:
            m.private(self.fixture / ('app/' + prefix + 'access-key.role'), 'dkp-storage-' + role + '-v1\n' + hashlib.sha256(access.encode()).hexdigest() + '\n' + hashlib.sha256(secret.encode()).hexdigest() + '\n')
        self.directory=self.root/'local-data/security/acceptance151';self.image='sha256:'+'e'*64
        self.live={'directory':str(self.root/'local-data/security/bp03-upload/run'),'project':'dkp-bp03-'+'f'*12,'runId':'f'*32,
                   'workerImage':'sha256:'+'1'*64,'definitions':str(self.root/'definitions')}
        app=Path(self.live['directory'])/'inspector/app';app.mkdir(parents=True,mode=0o700)
        m.private(app/'ca.crt','public-inspector-ca');m.private(app/'service.key','synthetic-service-key')
        Path(self.live['definitions']).mkdir()
        self.runtime=SimpleNamespace(verify=lambda p:copy.deepcopy(self.live),PROFILE='2'*64,owned=lambda item,record:None)
        self.p('helper',side_effect=lambda name:self.runtime if name=='upload-runtime' else SimpleNamespace(socket_group=lambda image:'101'))
        self.container={'Name':'/'+self.state['container'],'Config':{'Labels':{'dkp.issue':'104'},'Image':m.IMAGE},
                        'Image':self.image,'State':{'Running':True},'Mounts':[
                            {'Destination':'/data','Name':self.state['data'],'RW':True},
                            {'Destination':'/run/private','Name':self.state['server'],'RW':False}],
                        'NetworkSettings':{'Networks':{self.state['network']:{'NetworkID':'n'*64,'Aliases':['rustfs-compat']}},
                                           'Ports':{'9000/tcp':[{'HostIp':'127.0.0.1','HostPort':'19900'}],'9001/tcp':None}}}
        self.network={'Id':'n'*64,'Driver':'bridge','Labels':{'dkp.issue':'104'}}
        self.p('inspect',side_effect=self.fake_inspect)

    def p(self,name,*args,**kwargs):
        patcher=patch.object(m,name,*args,**kwargs);result=patcher.start();self.addCleanup(patcher.stop);return result
    def fake_inspect(self,kind,name):
        if kind=='container':return self.container
        if kind=='image':return {'Id':self.image}
        if kind=='network':return self.network
        return {'Driver':'local','Options':{},'Labels':{'dkp.issue':'104'}}
    def record(self):
        return {'schemaVersion':1,'runId':'a'*32,'fixture':str(self.fixture),'runtime':self.live['directory'],
                'fixtureContainer':self.state['container'],'runtimeProject':self.live['project'],'runtimeRunId':self.live['runId'],
                'workerImage':self.live['workerImage'],'authorityVolume':'dkp151-'+'a'*32+'-authority','phase':'prepared',
                'files':{},'sourceHashes':{p:m.sha(self.root/p) for p in m.BOUND_SOURCES}}
    def arrange_prepared(self):
        self.directory.mkdir(mode=0o700)
        for part in ('app','app/denied','app/inspector'):(self.directory/part).mkdir(mode=0o700)
        for name in ('app/denied/access-key','app/denied/secret-key','app/denied/access-key.role','app/inspector/ca.crt','app/inspector/service.key'):m.private(self.directory/name,'synthetic')
        record=self.record();record['files']={str(p.relative_to(self.directory)):m.sha(p) for p in (self.directory/'app').rglob('*') if p.is_file()}
        m.save(self.directory,record);return record
    def replace_record(self,record):m.save(self.directory,record)

    def test_fixture_exact_provider_scope_root_tls_and_native_resource_binding(self):
        self.assertEqual(self.state,m.validate_fixture(self.fixture))
        self.assertEqual('151',m.authority_labels(self.record())['dkp.issue'])
        self.assertEqual(0o600,(self.fixture/'state.json').stat().st_mode&0o777)

    def test_fixture_state_scope_endpoints_and_actual_docker_drift_fail_closed(self):
        for key,value in [('extra','unexpected'),('network','foreign-net'),('prefix','*'),('image','latest'),('tenant','tenant-a'),('endpoint','http://localhost:19900'),('endpoint','https://user:secret@localhost:19900'),('endpoint','https://localhost:19900/path')]:
            changed=copy.deepcopy(self.state);changed[key]=value;(self.fixture/'state.json').write_text(json.dumps(changed))
            with self.assertRaises(ValueError):m.validate_fixture(self.fixture)
        (self.fixture/'state.json').write_text(json.dumps(self.state))
        for mutate in [lambda c:c['Config']['Labels'].update({'dkp.issue':'other'}),lambda c:c['State'].update({'Running':False}),
                       lambda c:c['NetworkSettings']['Networks'].update({'foreign':{}}),lambda c:c['NetworkSettings']['Ports']['9000/tcp'][0].update({'HostIp':'0.0.0.0'}),
                       lambda c:c['Mounts'][1].update({'RW':True}),lambda c:c.update({'Image':'unverified'})]:
            old=copy.deepcopy(self.container);mutate(self.container)
            with self.assertRaises(ValueError):m.validate_fixture(self.fixture)
            self.container=old
        self.network['Driver']='overlay'
        with self.assertRaises(ValueError):m.validate_fixture(self.fixture)
        self.network['Driver']='bridge'
        with patch.object(m,'inspect',side_effect=lambda kind,name:{'Driver':'local','Options':{'device':'/foreign'},'Labels':{'dkp.issue':'104'}} if kind=='volume' else self.fake_inspect(kind,name)):
            with self.assertRaises(ValueError):m.validate_fixture(self.fixture)

    def test_private_file_and_root_config_binding_rejections(self):
        for relative,value in [('root-curl.private','user = "unbound"'),('ca.pem','different-ca'),('app/access-key','nothex'),('server/root-secret','nothex')]:
            path=self.fixture/relative;before=path.read_bytes();path.write_text(value)
            with self.assertRaises(ValueError):m.validate_fixture(self.fixture)
            path.write_bytes(before)
        path=self.fixture/'state.json';path.chmod(0o644)
        with self.assertRaises(ValueError):m.validate_fixture(self.fixture)
        path.chmod(0o600)
        with self.assertRaises(ValueError):m.bounded(path,1)
        link=self.root/'local-data/security/link';link.symlink_to(self.fixture,target_is_directory=True)
        with self.assertRaises(ValueError):m.confined(link)
        with self.assertRaises(ValueError):m.confined(self.root)
        self.fixture.chmod(0o755)
        with self.assertRaises(ValueError):m.owned_directory(self.fixture)

    def test_policy_is_exact_two_bucket_read_only_prefix_not_write_list_or_admin(self):
        policy=m.readonly_policy(self.state['prefix']);self.assertEqual(3,len(policy['Statement']))
        allow={action for statement in policy['Statement'] if statement['Effect']=='Allow' for action in statement['Action']}
        self.assertEqual({'s3:GetBucketVersioning','s3:GetObject','s3:GetObjectVersion'},allow)
        self.assertEqual(['s3:DeleteObjectVersion'],policy['Statement'][2]['Action']);self.assertEqual('Deny',policy['Statement'][2]['Effect'])
        self.assertTrue(all(self.state['prefix']+'*' in resource for resource in policy['Statement'][1]['Resource']))

    def test_sigv4_all_secret_and_identifier_material_is_private_not_argv(self):
        self.arrange_prepared();calls=[]
        def curl(args,**kwargs):
            calls.append(args);config=Path(args[args.index('--config')+1]);text=config.read_text()
            self.assertIn('user = ',text);self.assertEqual(0o600,config.stat().st_mode&0o777)
            self.assertIn('--disable',args);self.assertNotIn('--location',args);self.assertIn('=https',args)
            self.assertFalse(any('a'*32 in arg or 'b'*64 in arg or 'synthetic' in arg for arg in args))
            Path(args[args.index('--output')+1]).write_bytes(b'public')
            Path(args[args.index('--dump-header')+1]).write_text('HTTP/1.1 200 OK\r\nx-amz-version-id: exact\r\n')
            return b'200'
        self.p('command',side_effect=curl)
        for authority in ('root','app','denied'):
            status,body,headers=m.request(self.directory,self.fixture,self.state,authority,'PUT','/private-selector',b'body')
            self.assertEqual((200,b'public',{'x-amz-version-id':'exact'}),(status,body,headers))
        m.request(self.directory,self.fixture,self.state,'denied','HEAD','/private-selector')
        self.assertEqual(4,len(calls));self.assertFalse(list(self.directory.glob('.request-*')))

    def test_head_configuration_is_standalone_and_accepted_by_real_credential_free_curl_parser(self):
        self.arrange_prepared();head_options=[]
        def configured(args,**kwargs):
            config=Path(args[args.index('--config')+1]).read_text().splitlines()
            options=[line for line in config if line.startswith('head')]
            self.assertEqual(['head'],options);head_options.extend(options)
            Path(args[args.index('--output')+1]).write_bytes(b'')
            Path(args[args.index('--dump-header')+1]).write_text('HTTP/1.1 200 OK\r\nx-amz-version-id: exact\r\n')
            return b'200'
        with patch.object(m,'command',side_effect=configured):
            self.assertEqual(200,m.request(self.directory,self.fixture,self.state,'denied','HEAD','/synthetic')[0])
        # --version parses stdin config and exits without a URL, credentials or network.
        for config,valid in [('\n'.join(head_options)+'\n',True),('head = true\n',False)]:
            result=subprocess.run(['curl','--disable','--config','-','--version'],input=config.encode(),capture_output=True,timeout=10)
            self.assertEqual(valid,result.returncode==0)

    def test_actual_helper_loading_and_large_private_diagnostics_are_bounded(self):
        with patch.object(m,'ROOT',self.original):
            loaded=m.helper('rustfs-compat-harness')
            self.assertTrue(callable(loaded.socket_group))
        self.directory.mkdir(mode=0o700)
        def oversized(args,**kwargs):
            kwargs['stdout'].truncate(16*1024*1024+1)
            return SimpleNamespace(returncode=0)
        with patch.object(subprocess,'run',side_effect=oversized):
            with self.assertRaisesRegex(ValueError,'byte limit'):m.capture(['synthetic'],self.directory/'oversized.private.log')
        self.assertEqual(0o600,(self.directory/'oversized.private.log').stat().st_mode&0o777)

    def test_response_bounds_duplicate_version_and_transport_failures(self):
        self.arrange_prepared()
        for headers,body,status in [('x-amz-version-id: a\nx-amz-version-id: b\n',b'',b'200'),('x: '+'a'*16384,b'',b'200'),('',b'a'*65537,b'200'),('',b'',b'notstatus')]:
            def curl(args,**kwargs):
                Path(args[args.index('--output')+1]).write_bytes(body);Path(args[args.index('--dump-header')+1]).write_text(headers);return status
            with patch.object(m,'command',side_effect=curl):
                with self.assertRaises(ValueError):m.request(self.directory,self.fixture,self.state,'root','GET','/')
        with patch.object(subprocess,'run',side_effect=subprocess.TimeoutExpired('curl',20)):
            with self.assertRaises(ValueError):m.command(['curl'])
        with patch.object(subprocess,'run',return_value=SimpleNamespace(returncode=1,stdout=b'secret',stderr=b'secret')):
            with self.assertRaisesRegex(ValueError,'diagnostics suppressed'):m.command(['curl'])
        with patch.object(subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=b'ok')):self.assertEqual(b'ok',m.command(['curl']))
        with self.assertRaises(ValueError):m.xml(b'<!DOCTYPE x><a/>')

    def matrix_request(self,work,fixture,state,authority,method,path,body=None):
        if path.endswith('?versioning'):return 200,b'<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>',{}
        if method=='PUT':return 200,b'',{'x-amz-version-id':'exact-version'}
        if method=='HEAD':return 200,b'',{}
        if method=='GET':return 200,b'Public synthetic issue151 non-mutating exact-version proof.\n',{}
        if authority=='denied':return 403,b'<Error><Code>AccessDenied</Code></Error>',{}
        self.assertIn('?versionId=exact-version',path);self.assertTrue(path.startswith('/dkp-'));return 204,b'',{}

    def test_real_matrix_protocol_requires403_and_nonmutating_exact_readback_before_own_cleanup(self):
        self.arrange_prepared();events=[]
        def send(*args,**kwargs):events.append(args[3:6]);return self.matrix_request(*args,**kwargs)
        with patch.object(m,'request',side_effect=send):m.matrix(self.directory,self.fixture,self.state)
        ledger=json.loads((self.directory/'matrix-ledger.private').read_text());self.assertEqual(2,len(ledger));self.assertTrue(all(row['deleted'] for row in ledger))
        self.assertTrue(all(row['key'].startswith(self.state['prefix']) and row['version']=='exact-version' for row in ledger))
        self.assertEqual(2,sum(e[0]=='denied' and e[1]=='DELETE' for e in events));self.assertEqual(2,sum(e[0]=='app' and e[1]=='DELETE' for e in events))

    def test_matrix_denial_readback_version_and_privacy_errors_never_cleanup_unproven_versions(self):
        self.arrange_prepared()
        for failure in ('versioning','put','head','delete','readback','cleanup'):
            def send(*args,**kwargs):
                result=self.matrix_request(*args,**kwargs);authority,method,path=args[3:6]
                if failure=='versioning' and path.endswith('?versioning'):return 403,b'',{}
                if failure=='put' and method=='PUT':return 200,b'',{}
                if failure=='head' and method=='HEAD':return 403,b'',{}
                if failure=='delete' and method=='DELETE' and authority=='denied':return 204,b'',{}
                if failure=='readback' and method=='GET' and not path.endswith('?versioning'):return 200,b'changed',{}
                if failure=='cleanup' and method=='DELETE' and authority=='app':return 403,b'',{}
                return result
            with patch.object(m,'request',side_effect=send):
                with self.assertRaises(ValueError):m.matrix(self.directory,self.fixture,self.state)

    def test_prepare_requires_fresh_ready_owned_providers_and_never_gives_root_authority_to_volume(self):
        events=[];self.p('request',side_effect=lambda *a,**k:(events.append(('native',a[3],a[5])),(200,b'',{}))[1])
        self.p('matrix',side_effect=lambda *a:events.append(('matrix',)))
        self.p('attest_volume')
        self.p('volume',side_effect=lambda directory,record:events.append(('volume',sorted(str(p.relative_to(directory/'app')) for p in (directory/'app').rglob('*') if p.is_file()))))
        record=m.prepare(self.directory,self.fixture,self.live['directory'])
        self.assertEqual('prepared',record['phase']);self.assertEqual(5,len(record['files']));self.assertLess(events.index(('matrix',)),next(i for i,e in enumerate(events) if e[0]=='volume'))
        self.assertEqual(sorted(m.AUTHORITY_FILES),events[-1][1])
        self.assertEqual(3,len([e for e in events if e[0]=='native']));self.assertTrue(all(e[1]=='root' for e in events if e[0]=='native'))
        with self.assertRaises(ValueError):m.prepare(self.directory,self.fixture,self.live['directory'])
        other=self.directory.with_name('notready');self.runtime.verify=lambda p:(_ for _ in ()).throw(ValueError('unready'))
        with self.assertRaises(ValueError):m.prepare(other,self.fixture,self.live['directory'])
        self.assertFalse(other.exists())

    def test_volume_population_is_own_labeled_network_none_and_app_only(self):
        record=self.arrange_prepared();events=[]
        self.p('command',side_effect=lambda args,**kwargs:(events.append(args),b'')[1]);self.p('inspect',return_value={'Labels':m.authority_labels(record)})
        m.volume(self.directory,record)
        create=next(e for e in events if e[:2]==['docker','create']);self.assertEqual('none',create[create.index('--network')+1]);self.assertIn('CHOWN',create)
        cp=next(e for e in events if e[:2]==['docker','cp']);self.assertEqual('-',cp[2]);self.assertNotIn('server',' '.join(cp))
        self.assertTrue(any(e[:3]==['docker','rm','--force'] for e in events))
        with patch.object(m,'command',return_value=b'existing'):
            with self.assertRaises(ValueError):m.volume(self.directory,record)
        with self.assertRaises(ValueError):m.owned({'Labels':{'dkp.issue':'104'}},record)

    def test_volume_attestation_requires_private_nonroot_exact_app_bytes_and_no_other_consumer(self):
        record=self.arrange_prepared();calls=[]
        self.p('inspect',return_value={'Driver':'local','Options':{},'Labels':m.authority_labels(record)})
        expected='\n'.join(record['files']['app/'+name]+'  /authority/'+name for name in m.AUTHORITY_FILES)+'\n'
        def run(args,**kwargs):calls.append(args);return expected.encode() if args[:2]==['docker','run'] else b''
        self.p('command',side_effect=run);m.attest_volume(record)
        args=calls[-1];self.assertIn('10001:10001',args);self.assertIn('none',args);self.assertIn('--read-only',args);self.assertIn(',dst=/authority,readonly',' '.join(args))
        self.assertIn('10001:10001:600',args[-1]);self.assertIn('sha256sum',args[-1])
        with patch.object(m,'command',return_value=b'foreign'):
            with self.assertRaisesRegex(ValueError,'another consumer'):m.attest_volume(record)
        with patch.object(m,'command',side_effect=lambda args,**k:b'changed' if args[:2]==['docker','run'] else b''):
            with self.assertRaisesRegex(ValueError,'bytes changed'):m.attest_volume(record)
        with patch.object(m,'inspect',return_value={'Driver':'local','Options':{'device':'/foreign'},'Labels':m.authority_labels(record)}):
            with self.assertRaises(ValueError):m.attest_volume(record)

    def test_load_verifies_current_source_authority_and_genuine_runtime_identity(self):
        record=self.arrange_prepared();actual=self.fake_inspect;self.p('attest_volume')
        self.p('inspect',side_effect=lambda kind,name:{'Driver':'local','Options':{},'Labels':m.authority_labels(record)} if kind=='volume' and name==record['authorityVolume'] else actual(kind,name))
        self.assertEqual(record,m.load(self.directory)[0])
        for field,value in [('phase','created'),('extra','field'),('authorityVolume','foreign'),('workerImage','changed')]:
            changed=copy.deepcopy(record);changed[field]=value;m.save(self.directory,changed)
            with self.assertRaises(ValueError):m.load(self.directory)
        m.save(self.directory,record)
        path=self.directory/'app/denied/access-key';path.write_text('changed')
        with self.assertRaises(ValueError):m.load(self.directory)
        path.write_text('synthetic');source=self.root/m.JAVA_SOURCE;source.write_text('changed')
        with self.assertRaises(ValueError):m.load(self.directory)

    def test_run_attaches_both_verified_networks_with_only_app_authority_and_finite_results(self):
        record=self.arrange_prepared();events=[];self.p('attest_image');self.p('load',return_value=(record,self.state,self.live));self.p('command',side_effect=lambda args,**kwargs:(events.append(args),b'')[1])
        def metadata(kind,name):
            if kind=='image':return {'Id':self.image}
            if kind=='network':return {'Internal':True,'Labels':{'com.docker.compose.network':'upload-data'}}
            return {'Config':{'Labels':m.authority_labels(record)},'State':{'ExitCode':0}}
        self.p('inspect',side_effect=metadata);self.p('capture',return_value=self.result_bytes())
        self.assertEqual(9,len(m.execute(self.directory,self.image)))
        create=next(e for e in events if e[:2]==['docker','create']);self.assertIn('10001:0',create);self.assertIn(m.TASK,create)
        self.assertEqual(self.state['network'],create[create.index('--network')+1]);self.assertTrue(any(e[:3]==['docker','network','connect'] and e[3]==self.live['project']+'_upload-data' for e in events))
        joined=' '.join(create);self.assertNotIn('root-curl',joined);self.assertNotIn('/server',joined);self.assertNotIn('synthetic-service-key',joined)
        self.assertIn(',dst=/acceptance,readonly',joined);self.assertIn(',dst=/run/dkp/definitions,readonly',joined);self.assertIn('DKP_144_DENIED_STORAGE_AUTHORITY=/acceptance/denied',joined)
        self.assertTrue(any(e[:3]==['docker','rm','--force'] for e in events))
        with self.assertRaises(ValueError):m.execute(self.directory,'mutable:tag')
        with patch.object(m,'capture',side_effect=ValueError('failed')):
            with self.assertRaises(ValueError):m.execute(self.directory,self.image)
        self.assertGreaterEqual(sum(e[:3]==['docker','rm','--force'] for e in events),2)

    def result_bytes(self):return ('Gradle public diagnostics\n'+'\n'.join(json.dumps(x,separators=(',',':')) for x in [{'requirement':n,'result':'PASS'} for n in m.CASES]+[{'issue':144,'required':8,'passed':8,'skipped':0}])).encode()
    def test_case_results_reject_missing_duplicate_skipped_or_reordered_acceptance(self):
        self.assertEqual(9,len(m.results(self.result_bytes().decode())))
        for value in ('',self.result_bytes().decode().replace('COPY-01','COPY-02'),self.result_bytes().decode().replace('"skipped":0','"skipped":1'),self.result_bytes().decode().replace('"passed":8','"passed":7')):
            with self.assertRaises(ValueError):m.results(value)

    def test_actual_image_source_attestation_rejects_stale_missing_and_wrong_task_bytes_without_provider_access(self):
        record=self.arrange_prepared();calls=[]
        paths=(m.JAVA_SOURCE,'services/document-management/adapters-out/build.gradle')
        expected='\n'.join(record['sourceHashes'][p]+'  /workspace/'+p for p in paths)+'\n'
        self.p('command',side_effect=lambda args,**kwargs:(calls.append(args),expected.encode())[1])
        m.attest_image(self.image,record);args=calls[-1]
        self.assertEqual('none',args[args.index('--network')+1]);self.assertIn('--read-only',args);self.assertIn('--cap-drop',args)
        self.assertEqual('/usr/bin/sha256sum',args[args.index('--entrypoint')+1]);self.assertNotIn('--mount',args);self.assertNotIn('--env',args);self.assertNotIn('/var/run/docker.sock',args)
        for output in ('',expected.replace(record['sourceHashes'][m.JAVA_SOURCE],'0'*64),expected.replace(record['sourceHashes'][paths[1]],'0'*64)):
            with patch.object(m,'command',return_value=output.encode()):
                with self.assertRaises(ValueError):m.attest_image(self.image,record)
        with patch.object(m,'command',side_effect=ValueError('missing source')):
            with self.assertRaises(ValueError):m.attest_image(self.image,record)

    def test_stale_image_fails_before_loading_any_provider_or_authority(self):
        self.arrange_prepared()
        self.p('inspect',return_value={'Id':self.image})
        self.p('attest_image',side_effect=ValueError('stale image'))
        provider=self.p('load')
        with self.assertRaisesRegex(ValueError,'stale image'):m.execute(self.directory,self.image)
        provider.assert_not_called()

    def test_closed_archive_has_root_metadata_private_modes_and_only_closed_app_files(self):
        import io,tarfile
        self.arrange_prepared()
        with tarfile.open(fileobj=io.BytesIO(m.authority_archive(self.directory))) as archive:
            members=archive.getmembers()
            self.assertEqual(['denied','inspector',*m.AUTHORITY_FILES],[x.name for x in members])
            for entry in members:
                self.assertEqual((0,0,'',''),(entry.uid,entry.gid,entry.uname,entry.gname))
                self.assertEqual(0o700 if entry.isdir() else 0o600,entry.mode)
                self.assertFalse(entry.issym() or entry.islnk())
                if entry.isfile():self.assertEqual((self.directory/'app'/entry.name).read_bytes(),archive.extractfile(entry).read())
        (self.directory/'app/denied/access-key').chmod(0o644)
        with self.assertRaises(ValueError):m.authority_archive(self.directory)

    def test_actual_archive_shell_count_guards_accept_seven_and_reject_missing_or_extra_entries(self):
        import io,shlex,tarfile
        record=self.arrange_prepared();commands=[]
        self.p('inspect',return_value={'Driver':'local','Options':{},'Labels':m.authority_labels(record)})
        expected='\n'.join(record['files']['app/'+name]+'  /authority/'+name for name in m.AUTHORITY_FILES)+'\n'
        def command(args,**kwargs):
            commands.append(args)
            return expected.encode() if args[:2]==['docker','run'] else b''
        self.p('command',side_effect=command);m.attest_volume(record)
        actual_attestation_script=commands[-1][-1]
        scripts=[m.INITIALIZE_SCRIPT,actual_attestation_script]
        with tempfile.TemporaryDirectory() as temporary:
            authority=Path(temporary)/'authority';authority.mkdir()
            with tarfile.open(fileobj=io.BytesIO(m.authority_archive(self.directory))) as archive:
                members=archive.getmembers()
                self.assertEqual([*m.AUTHORITY_DIRECTORIES,*m.AUTHORITY_FILES],[entry.name for entry in members])
                for entry in members:
                    destination=authority/entry.name
                    if entry.isdir():destination.mkdir(mode=entry.mode)
                    else:
                        self.assertTrue(entry.isfile());self.assertFalse(entry.issym() or entry.islnk())
                        destination.write_bytes(archive.extractfile(entry).read());destination.chmod(entry.mode)
            actual_entries=list(authority.rglob('*'))
            self.assertEqual(7,len(actual_entries))
            self.assertEqual(set(m.AUTHORITY_DIRECTORIES),{str(p.relative_to(authority)) for p in actual_entries if p.is_dir()})
            self.assertEqual(set(m.AUTHORITY_FILES),{str(p.relative_to(authority)) for p in actual_entries if p.is_file()})
            def execute_count(script):
                # Execute the exact production shell guard, not a Python count
                # mirror. Only substitute the isolated extraction's mount path.
                guard=script.split(' && ',1)[0].replace('/authority',shlex.quote(str(authority)))
                return subprocess.run(['/bin/sh','-c',guard],capture_output=True,timeout=5).returncode
            for script in scripts:self.assertEqual(0,execute_count(script))
            extra=authority/'unexpected';extra.write_text('synthetic')
            for script in scripts:self.assertNotEqual(0,execute_count(script))
            extra.unlink();missing=authority/'denied/access-key.role';data=missing.read_bytes();missing.unlink()
            for script in scripts:self.assertNotEqual(0,execute_count(script))
            missing.write_bytes(data)
            for script in scripts:self.assertEqual(0,execute_count(script))

    def test_private_initialization_normalizes_exact_root_owned_paths_before_leaf_first_transfer(self):
        script=m.INITIALIZE_SCRIPT
        self.assertLess(script.index('chmod 0700'),script.index('chown 10001'))
        self.assertLess(script.index('/authority/denied/access-key',script.index('chown 10001')),script.rindex('chown 10001:10001 /authority'))
        self.assertNotIn('chown -R',script);self.assertNotIn('*',script)
        self.assertIn('test ! -L /authority/inspector/service.key',script)

    def test_private_capture_retains_failure_diagnostics_without_public_output(self):
        self.directory.mkdir(mode=0o700)
        def run(args,**kwargs):kwargs['stdout'].write(self.result_bytes());return SimpleNamespace(returncode=0)
        with patch.object(subprocess,'run',side_effect=run):self.assertEqual(self.result_bytes(),m.capture(['docker'],self.directory/'ok.private.log'))
        with patch.object(subprocess,'run',return_value=SimpleNamespace(returncode=1)):
            with self.assertRaisesRegex(ValueError,'private log retained'):m.capture(['docker'],self.directory/'failed.private.log')
        with patch.object(subprocess,'run',side_effect=subprocess.TimeoutExpired('docker',1800)):
            with self.assertRaisesRegex(ValueError,'deadline'):m.capture(['docker'],self.directory/'timeout.private.log')
        self.assertEqual(0o600,(self.directory/'failed.private.log').stat().st_mode&0o777)

    def test_cli_emits_only_closed_results_or_finite_failure(self):
        with patch('sys.argv',['upload-acceptance.py','prepare','--directory',str(self.directory)]):
            with self.assertRaisesRegex(SystemExit,'failed closed'):m.main()
        with patch('sys.argv',['upload-acceptance.py','run','--directory',str(self.directory)]):
            with self.assertRaisesRegex(SystemExit,'failed closed'):m.main()
        with patch('sys.argv',['upload-acceptance.py','prepare','--directory',str(self.directory),'--fixture-directory',str(self.fixture),'--runtime-directory',self.live['directory']]),patch.object(m,'prepare'),patch('builtins.print') as output:
            m.main();self.assertEqual(1,output.call_count)
        with patch('sys.argv',['upload-acceptance.py','run','--directory',str(self.directory),'--image',self.image]),patch.object(m,'execute',return_value=[{'result':'PASS'}]),patch('builtins.print') as output:
            m.main();self.assertEqual(1,output.call_count)


if __name__=='__main__':unittest.main()
