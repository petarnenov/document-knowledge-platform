"""Normal lifecycle invariants; mocks isolate Docker, real Docker acceptance is separate."""
import copy
import datetime as dt
import hashlib
import importlib.util
import json
import os
import io
import sys
from types import SimpleNamespace
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

spec=importlib.util.spec_from_file_location('dev_logging',Path(__file__).with_name('dev-logging.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class LoggingRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.directory=Path(self.temp.name)/'shared'
        self.original_free_ports=m.free_ports
        self.original_select_subnets=m.select_subnets;self.original_check_subnets=m.check_subnets
        subnet_patch=patch.object(m,'select_subnets',return_value={'data':'10.241.0.0/28','edge':'10.241.0.16/28'});subnet_patch.start();self.addCleanup(subnet_patch.stop)
        check_patch=patch.object(m,'check_subnets');check_patch.start();self.addCleanup(check_patch.stop)
        def pki(directory,*ports):
            folder=directory/'gateway-pki';folder.mkdir(mode=0o700)
            for name in ('portal.crt','portal.key','idp.crt','idp.key','ca.crt'):m.write_private(folder/name,'synthetic-unit-fixture')
        self.pki_patch=patch.object(m,'create_pki',side_effect=pki);self.pki_patch.start();self.addCleanup(self.pki_patch.stop)
        self.ports_patch=patch.object(m,'free_ports');self.ports_patch.start();self.addCleanup(self.ports_patch.stop)
        with patch.object(m,'check_resources'):
            self.state=m.initialize(self.directory,'dkp-dev-logging',18087)

    def ready(self):
        self.state.update(stage='READY',image='query:local',imageId='sha256:'+'a'*64,sourceFingerprint='b'*64,
                          configSha256=m.config_digest(self.directory),schemaSha256='c'*64,volumes={},
                          gatewayImage='gateway:local',gatewayImageId='sha256:'+'d'*64,gatewaySourceFingerprint='b'*64,
                          gatewayConfigSha256=m.gateway_digest(self.directory))
        return self.state

    def item(self,service):
        mounts=([{'Type':'volume','Name':'dkp-dev-logging-postgres-data','Destination':'/var/lib/postgresql','RW':True},
                 {'Type':'volume','Name':'dkp-dev-logging-postgres-secrets','Destination':'/run/secrets','RW':False}]
                if service=='postgres' else [{'Type':'bind','Source':str(self.directory/'query'),'Destination':'/run/dkp/log-query','RW':False}])
        if service=='gateway':
            mounts=[{'Type':'bind','Source':str(self.directory/('gateway' if name.endswith('.conf') else 'gateway-pki')/name),'Destination':'/run/dkp/gateway/'+name,'RW':False} for name in ('server.conf','log-proxy.conf','portal.crt','portal.key','idp.crt','idp.key','ca.crt')]
        ports={'8443/tcp':[{'HostIp':'127.0.0.1','HostPort':'18087'}],'8444/tcp':[{'HostIp':'127.0.0.1','HostPort':'18088'}]} if service=='gateway' else {}
        networks={'postgres':['data'],'query':['data','edge'],'gateway':['edge']}[service]
        return {'Id':service,'Image':self.state.get('gatewayImageId' if service=='gateway' else 'imageId'),'Config':{'Image':m.PG if service=='postgres' else self.state.get('image'),
                'User':'999:999' if service=='postgres' else str(os.getuid())+':'+str(os.getgid()),
                'Labels':{**m.labels(self.state),'com.docker.compose.service':service}},'State':{'Running':True},'Mounts':mounts,
                'NetworkSettings':{'Networks':{'dkp-dev-logging-'+name:{} for name in networks},'Ports':ports},'HostConfig':{'ReadonlyRootfs':True,'CapDrop':['ALL'],'Memory':768*1024*1024 if service!='query' else 3*1024*1024*1024,'NanoCpus':2_000_000_000,'PidsLimit':256 if service=='query' else 128,'SecurityOpt':['no-new-privileges:true'],
                'RestartPolicy':{'Name':'unless-stopped'},'PortBindings':ports}}

    def test_generated_credentials_are_separate_private_and_repeated_initialize_preserves_them(self):
        token=m.private(self.directory/'operator/read-token').decode()
        readers=json.loads(m.private(self.directory/'query/readers.json'))
        self.assertEqual(hashlib.sha256(token.encode()).hexdigest(),readers['readers'][0]['tokenSha256'])
        self.assertNotIn(token,(self.directory/'query/readers.json').read_text())
        self.assertGreater(dt.datetime.fromisoformat(readers['readers'][0]['expiresAt'].replace('Z','+00:00')),dt.datetime.now(dt.timezone.utc))
        before={str(p):p.read_bytes() for p in self.directory.rglob('*') if p.is_file()}
        with patch.object(m,'check_resources'):
            self.assertEqual(self.state,m.initialize(self.directory,'dkp-dev-logging',18087))
        self.assertEqual(before,{str(p):p.read_bytes() for p in self.directory.rglob('*') if p.is_file()})
        env=m.environment({**self.state,'image':'query:local','gatewayImage':'gateway:local'},self.directory)
        self.assertNotIn(token,env);self.assertNotIn(m.private(self.directory/'database/admin-password').decode(),env)
        self.assertEqual(0,(self.directory/'query/password').stat().st_mode&0o077)

    def test_legacy_compose_environment_has_private_ephemeral_viewer_defaults_without_mutation(self):
        self.ready()
        legacy=m.environment(self.state,self.directory)
        legacy=''.join(line+'\n' for line in legacy.splitlines() if not line.startswith(('DKP_LOG_VIEWER_IMAGE=','DKP_LOG_VIEWER_TOKEN=')))
        m.write_private(self.directory/'compose.env',legacy)
        overlays=[]
        def inspect_command(*args,**kwargs):
            self.assertEqual(2,args.count('--env-file'))
            index=[i for i,value in enumerate(args) if value=='--env-file'][-1]
            path=Path(args[index+1]);overlays.append(path)
            payload=m.private(path).decode()
            self.assertIn('DKP_LOG_VIEWER_TOKEN="'+str(self.directory/'operator/read-token')+'"',payload)
            self.assertIn('DKP_LOG_VIEWER_IMAGE="'+self.state.get('viewerImage','dkp-disabled-viewer:unselected')+'"',payload)
            self.assertNotIn(m.private(self.directory/'operator/read-token').decode(),payload)
            self.assertEqual(legacy,(self.directory/'compose.env').read_text())
            return b'fixture'
        with patch.object(m,'docker',side_effect=inspect_command):
            self.assertEqual(b'fixture',m.compose(self.state,self.directory,'ps','--all','--quiet','postgres'))
        self.assertTrue(overlays);self.assertFalse(overlays[0].exists())
        self.assertEqual(legacy,(self.directory/'compose.env').read_text())
        self.state.update(viewerEnabled=True,viewerImage='owned-viewer:current')
        with patch.object(m,'docker',side_effect=inspect_command):m.compose(self.state,self.directory,'ps','--all')
        self.assertFalse(overlays[-1].exists())
        def failed_command(*args,**kwargs):
            inspect_command(*args,**kwargs)
            raise m.RuntimeFailure('LOG_RUNTIME_COMMAND_FAILED')
        with patch.object(m,'docker',side_effect=failed_command),self.assertRaises(m.RuntimeFailure):m.compose(self.state,self.directory,'ps','--all')
        self.assertFalse(overlays[-1].exists())
        self.assertEqual(legacy,(self.directory/'compose.env').read_text())


    def test_private_read_and_atomic_write_reject_unsafe_permissions_symlinks_and_oversize(self):
        p=self.directory/'query/password'
        with self.assertRaises(FileExistsError):m.write_private(p,'overwrite')
        original=p.read_bytes();m.write_private(p,original,replace=True)
        with self.assertRaises(m.RuntimeFailure):m.private(p,1)
        p.chmod(0o640)
        with self.assertRaises(m.RuntimeFailure):m.private(p)
        p.chmod(0o600);link=self.directory/'link';link.symlink_to(p)
        with self.assertRaises(m.RuntimeFailure):m.private(link)
        with self.assertRaises(m.RuntimeFailure):m.private(self.directory/'query')

    def test_unknown_existing_directory_or_wrong_project_or_state_rejected(self):
        with self.assertRaises(m.RuntimeFailure):m.initialize(self.directory.parent,'dkp-dev-logging',18087)
        with self.assertRaises(m.RuntimeFailure):m.initialize(self.directory,'other',18087)
        for field,value in [('ownerId','bad'),('schemaVersion',2),('port',1),('uid',0),('directory','/other')]:
            state={**self.state,field:value}
            with self.subTest(field=field),self.assertRaises(m.RuntimeFailure):
                m.validate_state(state,self.directory,'dkp-dev-logging',18087)
        with patch.object(m,'check_resources'):
            other=m.initialize(self.directory.parent/'acceptance','dkp-dev-logging-acceptance-'+'a'*12,18088)
            self.assertNotEqual(self.state['ownerId'],other['ownerId'])

    def test_unknown_owner_and_wrong_or_writable_mount_rejected(self):
        with self.assertRaises(m.RuntimeFailure):m.owned({'Labels':{}},self.state)
        for service in ('postgres','query','gateway'):
            item=self.item(service);m.check_mounts(item,self.state,service)
            for mutation in ('source','mode','extra'):
                changed=copy.deepcopy(item)
                if mutation=='source':
                    changed['Mounts'][0]['Name' if service=='postgres' else 'Source']='other'
                elif mutation=='mode':changed['Mounts'][0]['RW']=not changed['Mounts'][0]['RW']
                else:changed['Mounts'].append({'Type':'bind','Source':'/private','Destination':'/extra','RW':False})
                with self.subTest(service=service,mutation=mutation),self.assertRaises(m.RuntimeFailure):m.check_mounts(changed,self.state,service)

    def test_ready_storage_missing_replaced_or_unowned_fails_before_mutation(self):
        self.ready()
        with patch.object(m,'exists',return_value=False),patch.object(m,'docker') as docker:
            with self.assertRaisesRegex(m.RuntimeFailure,'VOLUME_MISSING'):m.check_resources(self.state)
            docker.assert_not_called()
        volume={'Name':'dkp-dev-logging-postgres-data','CreatedAt':'one','Labels':m.labels(self.state)}
        self.state['volumes']={'dkp-dev-logging-postgres-data':m.volume_identity(volume)}
        with patch.object(m,'exists',return_value=True),patch.object(m,'inspect',return_value={**volume,'CreatedAt':'two'}):
            with self.assertRaisesRegex(m.RuntimeFailure,'VOLUME_MISMATCH'):m.check_resources(self.state)
        with patch.object(m,'exists',return_value=True),patch.object(m,'inspect',return_value={'Labels':{}}):
            with self.assertRaisesRegex(m.RuntimeFailure,'OWNER_MISMATCH'):m.check_resources(self.state)

    def test_exact_owned_network_and_service_inventory(self):
        volume={'Name':'owned','CreatedAt':'one','Labels':m.labels(self.state)}
        network={'Labels':m.labels(self.state),'Internal':True}
        def inspect(kind,name):return self.item('query') if kind=='container' else {**network,'IPAM':{'Config':[{'Subnet':self.state['subnets']['data' if name.endswith('-data') else 'edge']}]},'Internal':network['Internal'] if name.endswith('-data') else False} if kind=='network' else volume
        with patch.object(m,'exists',return_value=True),patch.object(m,'inspect',side_effect=inspect),patch.object(m,'docker',return_value=b'query\n'):
            m.check_resources(self.state)
            network['Internal']=False
            with self.assertRaisesRegex(m.RuntimeFailure,'NETWORK_MISMATCH'):m.check_resources(self.state)

    def test_command_suppresses_docker_secret_output_and_timeout(self):
        with patch.object(m.subprocess,'run',return_value=Mock(returncode=0,stdout=b'ok')):self.assertEqual(b'ok',m.docker('info'))
        with patch.object(m.subprocess,'run',return_value=Mock(returncode=1,stdout=b'private',stderr=b'private')):
            with self.assertRaisesRegex(m.RuntimeFailure,'^LOG_RUNTIME_COMMAND_FAILED$'):m.command(['docker','bad'])
        for failure in (OSError('private'),subprocess.TimeoutExpired('private',1)):
            with patch.object(m.subprocess,'run',side_effect=failure),self.assertRaisesRegex(m.RuntimeFailure,'^LOG_RUNTIME_COMMAND_UNAVAILABLE$'):m.command(['docker','bad'])
        with patch.object(m,'command',return_value=b'[{"Id":"x"}]'):self.assertEqual('x',m.inspect('container','x')['Id'])
        for kind,line,name in [('container','{"Names":"x"}','x'),('volume','{"Name":"x"}','x'),('network','{"Name":"x"}','x'),('image','{"Repository":"x","Tag":"local"}','x:local')]:
            with patch.object(m,'docker',return_value=line.encode()):self.assertTrue(m.exists(kind,name))

    def test_schema_provision_is_transactional_and_status_does_not_create_missing_schema(self):
        with patch.object(m,'sql',return_value='f') as sql:
            with self.assertRaisesRegex(m.RuntimeFailure,'SCHEMA_MISMATCH'):m.assert_schema(self.state,self.directory)
            self.assertEqual(1,sql.call_count);self.assertNotIn(b'CREATE',sql.call_args.args[2])
        self.state['stage']='DB_READY'
        with patch.object(m,'sql',side_effect=['f','0','','c'*64]),patch.object(m,'assert_schema'):
            m.provision(self.state,self.directory)
        self.assertEqual('c'*64,self.state['schemaSha256'])
        with patch.object(m,'assert_schema') as check,patch.object(m,'sql') as sql:
            m.provision(self.state,self.directory);check.assert_called_once();sql.assert_not_called()
        migration=hashlib.sha256((m.SQL/'logging-migration/V1__owned_dev_logging.sql').read_bytes()).hexdigest()
        for responses,code in [(['t','wrong'],'SCHEMA_MISMATCH'),(['t','1:'+migration,'f'],'UNKNOWN_SCHEMA'),(['t','1:'+migration,'t','d'*64],'SCHEMA_DRIFT')]:
            with patch.object(m,'sql',side_effect=responses),self.assertRaisesRegex(m.RuntimeFailure,code):m.assert_schema(self.state,self.directory)
        with patch.object(m,'sql',side_effect=['t','1:'+migration,'t','c'*64]):m.assert_schema(self.state,self.directory)

    def test_disk_requires_expected_volume_and_both_free_space_predicates(self):
        item=self.item('postgres');volume={'Labels':m.labels(self.state)}
        with patch.object(m,'container',return_value=item),patch.object(m,'inspect',return_value=volume):
            for size,free in [(100*1024**3,3*1024**3),(3*1024**3,1024**3)]:
                with patch.object(m,'docker',return_value=f'Size Avail\n{size} {free}\n'.encode()),self.assertRaisesRegex(m.RuntimeFailure,'DISK_UNAVAILABLE'):m.disk(self.state,self.directory)
            with patch.object(m,'docker',return_value=b'Size Avail\n10737418240 5368709120\n'):m.disk(self.state,self.directory)

    def test_status_and_repeated_start_leave_live_service_and_secrets_untouched(self):
        self.ready();image={'Config':{'Labels':{**m.labels(self.state),'dkp.logging.source':self.state['sourceFingerprint']}}}
        with patch.object(m,'check_resources'),patch.object(m,'inspect',return_value=image),patch.object(m,'container',side_effect=lambda s,d,k:self.item(k)),patch.object(m,'assert_schema'),patch.object(m,'probe'):
            self.assertEqual('PASS',m.verify_live(self.state,self.directory)['result'])
        with patch.object(m,'check_resources'),patch.object(m,'verify_live',return_value={'result':'PASS'}),patch.object(m,'build_image') as build,patch.object(m,'compose') as compose:
            self.assertEqual('PASS',m.start(self.state,self.directory)['result']);build.assert_not_called();compose.assert_not_called()

    def test_start_then_controlled_update_preserves_owned_database_and_uses_no_deps_for_query(self):
        def live(*args):return {'result':'PASS'}
        def built(state,directory,component='query'):
            return {'gatewayImage':'gateway:local','gatewayImageId':'sha256:y','gatewaySourceFingerprint':'a'*64} if component=='gateway' else {'image':'query:local','imageId':'sha256:x','sourceFingerprint':'a'*64}
        with patch.object(m,'probe'),patch.object(m,'check_resources'),patch.object(m,'build_image',side_effect=built),patch.object(m,'install_secrets') as secrets,patch.object(m,'compose') as compose,patch.object(m,'inspect',return_value={'Labels':m.labels(self.state)}),patch.object(m,'disk'),patch.object(m,'provision'),patch.object(m,'verify_live',side_effect=live):
            m.start(self.state,self.directory)
            secrets.assert_called_once();self.assertEqual('READY',self.state['stage'])
            self.assertIn('--no-deps',compose.call_args.args)
            self.assertIn('--no-recreate',compose.call_args.args)
            with patch.object(m,'assert_schema'):
                m.start(self.state,self.directory,update=True)
            self.assertEqual(1,secrets.call_count);self.assertIn('--force-recreate',compose.call_args.args)
            self.assertIn('--no-deps',compose.call_args.args)

    def test_explicit_renewal_preserves_token_and_reader_identity(self):
        self.ready();token=m.private(self.directory/'operator/read-token');before=json.loads(m.private(self.directory/'query/readers.json'))
        with patch.object(m,'check_resources'),patch.object(m,'assert_schema'),patch.object(m,'start',return_value={'result':'PASS'}) as start:
            m.renew(self.state,self.directory);self.assertTrue(start.call_args.kwargs['update'])
        self.assertEqual(token,m.private(self.directory/'operator/read-token'))
        after=json.loads(m.private(self.directory/'query/readers.json'))
        self.assertEqual(before['readers'][0]['id'],after['readers'][0]['id'])
        self.assertEqual(before['readers'][0]['tokenSha256'],after['readers'][0]['tokenSha256'])

    def test_renewal_rejects_schema_drift_before_replacing_grants(self):
        original=m.private(self.directory/'query/readers.json')
        with patch.object(m,'check_resources'),patch.object(m,'assert_schema',side_effect=m.RuntimeFailure('LOG_RUNTIME_SCHEMA_DRIFT')),patch.object(m,'start') as start:
            with self.assertRaisesRegex(m.RuntimeFailure,'SCHEMA_DRIFT'):m.renew(self.state,self.directory)
            start.assert_not_called()
        self.assertEqual(original,m.private(self.directory/'query/readers.json'))

    def test_secret_mount_helper_never_receives_secret_in_argv_and_removes_only_owned_helper(self):
        item={'Labels':m.labels(self.state),'Config':{'Labels':m.labels(self.state)}}
        with patch.object(m,'exists',return_value=True),patch.object(m,'inspect',return_value=item),patch.object(m,'docker',return_value=b'helper') as docker:
            m.install_secrets(self.state,self.directory)
        calls=[call.args for call in docker.call_args_list]
        command_text=str(calls)
        self.assertNotIn(m.private(self.directory/'database/admin-password').decode(),command_text)
        self.assertTrue(any(args[:2]==('cp',str(self.directory/'database/admin-password')) for args in calls))
        self.assertTrue(any(args[:3]==('rm','--force','--volumes') for args in calls))
        self.assertFalse(any(args[:2]==('volume','rm') for args in calls))
        with patch.object(m,'container',return_value={'Id':'db'}),patch.object(m,'docker',return_value=b't\n') as docker:
            self.assertEqual('t',m.sql(self.state,self.directory,b'SELECT 1;'))
            self.assertEqual(b'SELECT 1;',docker.call_args.kwargs['data'])
            self.assertNotIn('SELECT 1;',docker.call_args.args)
        with patch.object(m,'compose',return_value=b'query'),patch.object(m,'inspect',return_value=item):
            self.assertEqual(item,m.container(self.state,self.directory,'query'))
        with patch.object(m,'compose',return_value=b''),self.assertRaisesRegex(m.RuntimeFailure,'CONTAINER_MISSING'):
            m.container(self.state,self.directory,'query')

    def test_image_build_binds_public_snapshot_and_rejects_foreign_image_before_overwrite(self):
        fake=SimpleNamespace(snapshot=Mock(return_value={'fingerprint':'a'*64}))
        image={'Id':'sha256:x','Os':'linux','Architecture':'amd64','Config':{'Labels':m.labels(self.state)}}
        with patch.dict(sys.modules,{'verify':fake}),patch.object(m,'exists',return_value=False),patch.object(m,'inspect',return_value=image),patch.object(m,'docker') as docker:
            result=m.build_image(self.state,self.directory)
            self.assertEqual('a'*64,result['sourceFingerprint']);self.assertEqual('sha256:x',result['imageId'])
            self.assertIn('dkp.logging.source='+'a'*64,docker.call_args.args)
        with patch.dict(sys.modules,{'verify':fake}),patch.object(m,'exists',return_value=True),patch.object(m,'inspect',return_value={'Labels':{}}),patch.object(m,'docker') as docker:
            with self.assertRaisesRegex(m.RuntimeFailure,'OWNER_MISMATCH'):m.build_image(self.state,self.directory)
            docker.assert_not_called()

    def test_local_daemon_and_nonblocking_operator_lock(self):
        with patch.dict(os.environ,{},clear=True),patch.object(m,'docker',return_value=b'"unix:///safe.sock"'):m.local_daemon()
        with patch.dict(os.environ,{'DOCKER_HOST':'private'},clear=True),self.assertRaisesRegex(m.RuntimeFailure,'LOCAL_DAEMON_REQUIRED'):m.local_daemon()
        with patch.dict(os.environ,{},clear=True),patch.object(m,'docker',return_value=b'"tcp://remote"'),self.assertRaisesRegex(m.RuntimeFailure,'LOCAL_DAEMON_REQUIRED'):m.local_daemon()
        with m.operator_lock(self.directory):
            with self.assertRaisesRegex(m.RuntimeFailure,'OPERATOR_BUSY'):
                with m.operator_lock(self.directory):self.fail('second mutating operator must not enter')
        with m.operator_lock(self.directory):pass

    def test_probe_uses_only_private_read_token_and_finite_iso_utc_window(self):
        response=Mock();response.__enter__=Mock(return_value=response);response.__exit__=Mock(return_value=False)
        response.status=200;response.read.return_value=b'{"events":[]}'
        with patch.object(m.ssl,'create_default_context',return_value=Mock()),patch.object(m.urllib.request,'urlopen',return_value=response) as fetch:
            m.probe(self.state,self.directory)
            request=fetch.call_args.args[0]
            self.assertIn('Z&to=',request.full_url);self.assertEqual(5,fetch.call_args.kwargs['timeout'])
        with patch.object(m.ssl,'create_default_context',return_value=Mock()),patch.object(m.urllib.request,'urlopen',side_effect=OSError('private')):
            with self.assertRaisesRegex(m.RuntimeFailure,'^LOG_RUNTIME_QUERY_UNAVAILABLE$'):m.probe(self.state,self.directory)

    def test_cli_action_dispatch_and_safe_failure_output(self):
        for action,target in [('start','start'),('update','start'),('status','verify_live'),('renew-readers','renew')]:
            with patch.object(sys,'argv',['dev-logging.py',action,'--directory',str(self.directory)]+(['--target','both'] if action=='update' else [])),patch.object(m,'local_daemon'),patch.object(m,'initialize',return_value=self.state),patch.object(m,target,return_value={'result':'PASS'}) as handler,patch('sys.stdout',new_callable=io.StringIO) as output:
                self.assertEqual(0,m.main());self.assertEqual('PASS',json.loads(output.getvalue())['result'])
                handler.assert_called_once()
        for failure,code in [(ValueError('private-password'),'LOG_RUNTIME_UNAVAILABLE'),(m.RuntimeFailure('LOG_RUNTIME_OPERATOR_BUSY'),'LOG_RUNTIME_OPERATOR_BUSY')]:
            with patch.object(sys,'argv',['dev-logging.py','status','--directory',str(self.directory)]),patch.object(m,'local_daemon',side_effect=failure),patch('sys.stdout',new_callable=io.StringIO) as output:
                self.assertEqual(1,m.main());self.assertEqual(code,json.loads(output.getvalue())['code']);self.assertNotIn('private-password',output.getvalue())

    def test_free_port_guard_and_pki_use_existing_origin_contract(self):
        import socket
        with socket.socket() as first,socket.socket() as second:
            first.bind(('127.0.0.1',0));second.bind(('127.0.0.1',0))
            ports=(first.getsockname()[1],second.getsockname()[1])
            with self.assertRaisesRegex(m.RuntimeFailure,'PORT_UNAVAILABLE'):self.original_free_ports(*ports)
        self.original_free_ports(*ports)
        with self.assertRaises(ValueError):self.original_free_ports(18443,18443)
        bootstrap=m.security_bootstrap();self.assertEqual('https://localhost:18443',bootstrap.origins(18443,18444)[0])

    def test_gateway_only_update_never_replaces_query_or_database(self):
        self.ready()
        image={'gatewayImage':'gateway:new','gatewayImageId':'sha256:new','gatewaySourceFingerprint':'f'*64}
        before=self.state['imageId']
        config=self.directory/'gateway/server.conf';old=config.read_text()
        original_read=Path.read_text
        def reviewed_template(path,*args,**kwargs):
            value=original_read(path,*args,**kwargs)
            return value+'\n# reviewed route correction\n' if path.name=='dev-logging-gateway-server.template.conf' else value
        with patch.object(Path,'read_text',reviewed_template),patch.object(m,'check_resources'),patch.object(m,'assert_schema'),patch.object(m,'build_image',return_value=image) as build,patch.object(m,'compose') as compose,patch.object(m,'disk'),patch.object(m,'provision'),patch.object(m,'probe'),patch.object(m,'verify_live',return_value={'result':'PASS'}):
            m.start(self.state,self.directory,update=True,target='gateway')
            self.assertEqual(before,self.state['imageId']);self.assertEqual('gateway',build.call_args.kwargs['component'])
            replacements=[c.args[-1] for c in compose.call_args_list if '--force-recreate' in c.args]
            self.assertEqual(['gateway'],replacements)
        self.assertEqual('sha256:new',self.state['gatewayImageId'])
        self.assertNotEqual(old,config.read_text());self.assertIn('# reviewed route correction',config.read_text())
        self.assertEqual(m.gateway_digest(self.directory),self.state['gatewayConfigSha256'])

    def test_subnets_exclude_docker_and_vpn_ranges_and_fail_exhaustion(self):
        with patch.object(m,'occupied_ranges',return_value=[m.ipaddress.IPv4Network('10.241.0.0/17')]):
            selected=self.original_select_subnets()
            self.assertEqual(2,len(set(selected.values())))
            self.assertTrue(all(m.ipaddress.IPv4Network(value).subnet_of(m.ipaddress.IPv4Network('10.241.128.0/17')) for value in selected.values()))
        with patch.object(m,'occupied_ranges',return_value=[m.ipaddress.IPv4Network('10.0.0.0/8')]):
            with self.assertRaisesRegex(m.RuntimeFailure,'POOL_EXHAUSTED'):self.original_select_subnets()
            with self.assertRaisesRegex(m.RuntimeFailure,'COLLISION'):self.original_check_subnets(self.state)
        with patch.object(m,'occupied_ranges',return_value=[]):self.original_check_subnets(self.state)

    def test_host_routes_parse_darwin_abbreviations_linux_interfaces_and_reject_ambiguity(self):
        text=b'Routing tables\nInternet:\nDestination Gateway Flags Netif Expire\ndefault 1.1.1.1 UG en0\n10.8/24 link1 UC utun5\n192.168.0 link1 UC en0\n127.0.0.1 link1 UH lo0\n'
        with patch.object(m.sys,'platform','darwin'),patch.object(m,'command',return_value=text):
            self.assertEqual(['10.8.0.0/24','192.168.0.0/24','127.0.0.1/32'],[str(value) for value in m.host_ranges()])
        for text in [b'garbage',b'Destination Gateway Flags Netif\nbad',b'Destination Gateway Flags Netif\ninvalid link UC en0']:
            with patch.object(m.sys,'platform','darwin'),patch.object(m,'command',return_value=text),self.assertRaisesRegex(m.RuntimeFailure,'ROUTE_INVENTORY'):m.host_ranges()
        routes=[{'dst':'default'},{'dst':'10.8.0.0/24','dev':'tun0'},{'dst':'10.241.0.0/28','dev':'br-owned'}]
        interfaces=[{'ifname':'tun0','addr_info':[{'family':'inet','local':'10.8.0.2','prefixlen':24}]},{'ifname':'br-owned','addr_info':[{'family':'inet','local':'10.241.0.1','prefixlen':28}]}]
        with patch.object(m.sys,'platform','linux'),patch.object(m,'command',side_effect=[json.dumps(routes).encode(),json.dumps(interfaces).encode()]):
            self.assertEqual(['10.8.0.0/24']*2,[str(value) for value in m.host_ranges(['br-owned'])])
        for platform in ['linux','unsupported']:
            with patch.object(m.sys,'platform',platform),patch.object(m,'command',side_effect=m.RuntimeFailure('unavailable')),self.assertRaisesRegex(m.RuntimeFailure,'ROUTE_INVENTORY'):m.host_ranges()

    def test_network_inventory_ignores_only_exact_owned_bridge(self):
        foreign={'Name':'foreign','Id':'a'*64,'IPAM':{'Config':[{'Subnet':'10.242.0.0/24'},{'Subnet':'fc00::/64'}]}}
        owned={'Name':self.state['project']+'-data','Id':'b'*64,'Labels':m.labels(self.state),'Options':{},'IPAM':{'Config':[{'Subnet':self.state['subnets']['data']}]}}
        with patch.object(m,'docker',return_value=b'a b'),patch.object(m,'inspect',side_effect=[foreign,owned]),patch.object(m,'host_ranges',return_value=[]) as host:
            self.assertEqual(['10.242.0.0/24'],[str(value) for value in m.occupied_ranges(self.state)])
            host.assert_called_once_with(['br-'+('b'*12)])
        owned['Labels']={}
        with patch.object(m,'docker',return_value=b'b'),patch.object(m,'inspect',return_value=owned),self.assertRaisesRegex(m.RuntimeFailure,'OWNER_MISMATCH'):m.occupied_ranges(self.state)

    def test_acceptance_diagnostic_failure_cannot_skip_owned_cleanup_and_both_streams_are_private(self):
        spec=importlib.util.spec_from_file_location('acceptance_diagnostics',Path(__file__).with_name('accept_dev_logging.py'))
        acceptance=importlib.util.module_from_spec(spec);spec.loader.exec_module(acceptance)
        cls=acceptance.SharedLoggingAcceptance;cls.project='dkp-dev-logging-acceptance-'+'a'*12
        cls.state=self.state;cls.directory=self.directory
        with patch.object(cls,'capture_private',side_effect=OSError('private diagnostic failure')),patch.object(cls,'remove_owned') as cleanup:
            with self.assertRaisesRegex(acceptance.m.RuntimeFailure,'^LOG_ACCEPTANCE_DIAGNOSTIC_FAILED$'):cls.cleanup_owned()
            cleanup.assert_called_once()
        item={'Id':'owned-query','Labels':m.labels(self.state)}
        with patch.object(acceptance.m.subprocess,'run',return_value=Mock(returncode=0,stdout=b'normal-output',stderr=b'fatal-detail')):
            cls.capture_container(item,'combined')
        path=self.directory/'combined.private.log'
        self.assertEqual(b'normal-outputfatal-detail',path.read_bytes());self.assertEqual(0,path.stat().st_mode&0o077)

    def test_acceptance_query_uses_requested_build_filter_and_retains_legacy_default(self):
        spec=importlib.util.spec_from_file_location('acceptance_query',Path(__file__).with_name('accept_dev_logging.py'))
        acceptance=importlib.util.module_from_spec(spec);spec.loader.exec_module(acceptance)
        instance=acceptance.SharedLoggingAcceptance();instance.directory=self.directory;instance.port=18087
        build_id='22222222-2222-4222-8222-222222222222'
        with patch.object(acceptance.ssl,'create_default_context'),patch.object(acceptance.urllib.request,'urlopen',side_effect=lambda *args,**kwargs:io.BytesIO(b'{"events":[]}')) as request:
            self.assertEqual({'events':[]},instance.query(build_id=build_id))
            supplied=acceptance.urllib.parse.parse_qs(acceptance.urllib.parse.urlsplit(request.call_args.args[0].full_url).query)
            self.assertEqual([build_id],supplied['buildId'])
            self.assertIn('Authorization',request.call_args.args[0].headers)
            instance.query()
            legacy=acceptance.urllib.parse.parse_qs(acceptance.urllib.parse.urlsplit(request.call_args.args[0].full_url).query)
            self.assertEqual(['11111111-1111-4111-8111-111111111111'],legacy['buildId'])

    def test_explicit_viewer_enable_builds_only_viewer_gateway_and_preserves_query_data_and_token(self):
        self.ready();before=self.state['imageId'];token=m.private(self.directory/'operator/read-token')
        def build(state,directory,component):
            prefix='viewer' if component=='viewer' else 'gateway'
            return {prefix+'Image':prefix+':new',prefix+'ImageId':'sha256:'+prefix,prefix+'SourceFingerprint':'f'*64}
        with patch.object(m,'check_resources'),patch.object(m,'assert_schema'),patch.object(m,'build_image',side_effect=build) as images,patch.object(m,'compose') as compose,patch.object(m,'disk'),patch.object(m,'provision'),patch.object(m,'probe') as probe,patch.object(m,'verify_live',return_value={'result':'PASS'}):
            m.start(self.state,self.directory,update=True,target='viewer')
            self.assertEqual(['viewer','gateway'],[call.kwargs['component'] for call in images.call_args_list])
            self.assertEqual(['viewer','gateway'],[call.args[-1] for call in compose.call_args_list if '--force-recreate' in call.args])
            self.assertEqual(before,self.state['imageId']);self.assertTrue(self.state['viewerEnabled'])
            self.assertEqual(m.viewer_digest(self.directory,self.state),self.state['viewerConfigSha256'])
            self.assertEqual(token,m.private(self.directory/'operator/read-token'))
            self.assertTrue(any(call.kwargs.get('viewer') for call in probe.call_args_list))
        with patch.object(m,'check_resources'):
            self.state['stage']='NEW'
            with self.assertRaisesRegex(m.RuntimeFailure,'VIEWER_REQUIRES_READY'):m.start(self.state,self.directory,update=True,target='viewer')

    def test_gateway_templates_publish_viewer_routes_only_after_explicit_enable(self):
        config=self.directory/'gateway/server.conf'
        self.assertNotIn('alias /usr/share/nginx/html/logs.html',config.read_text())
        self.assertIn('location = /dev/logs { return 404; }',config.read_text())
        m.gateway_config(self.directory,18087,18088,viewer=True)
        self.assertIn('alias /usr/share/nginx/html/logs.html',config.read_text())
        self.assertIn('http://viewer:18088',config.read_text())
        self.assertNotIn('__VIEWER_ROUTES__',config.read_text())

    def test_native_bff_uses_only_fixed_operator_port_and_preserves_routes(self):
        config=self.directory/'gateway/server.conf'
        m.gateway_config(self.directory,18087,18088,viewer=True,writer=True)
        original=config.read_text()
        m.gateway_config(self.directory,18087,18088,viewer=True,writer=True,native_bff_port=58480)
        selected=config.read_text()
        self.assertEqual(original.replace('set $bff http://browser-bff:8080;',
            'set $bff http://host.docker.internal:58480;'),selected)
        for invalid in [True,False,'58480','http://example.invalid',0,1023,65536,18087,18088,
                        18081,18082,18083,19081,19082,19083]:
            with self.subTest(port=invalid),self.assertRaisesRegex(m.RuntimeFailure,'NATIVE_BFF_PORT_INVALID'):
                m.gateway_config(self.directory,18087,18088,native_bff_port=invalid)
            self.assertEqual(selected,config.read_text())
        # A changed upstream template cannot silently omit the selected native binding.
        read_text=Path.read_text
        def drift(path,*args,**kwargs):
            value=read_text(path,*args,**kwargs)
            return value.replace('http://browser-bff:8080','http://unknown:8080') if path.name=='dev-logging-gateway-server.template.conf' else value
        with patch.object(Path,'read_text',drift),self.assertRaisesRegex(m.RuntimeFailure,'NATIVE_BFF_TEMPLATE_INVALID'):
            m.gateway_config(self.directory,18087,18088,native_bff_port=58480)
        self.assertEqual(selected,config.read_text())

    def test_writer_routes_require_explicit_selection_and_precede_general_bff_proxy(self):
        config=self.directory/'gateway/server.conf'
        original=config.read_text()
        self.assertIn('location ^~ /api/v1/dev-logs/ { proxy_pass $logs;',original)
        self.assertNotIn('location = /api/v1/dev-logs/registrations',original)
        m.gateway_config(self.directory,18087,18088,viewer=True,writer=True)
        selected=config.read_text()
        self.assertNotIn('__LOG_ROUTES__',selected)
        self.assertNotIn('location ^~ /api/v1/dev-logs/',selected)
        for path,limit in [('context',1024),('beacon',32768),('beacon/receipts',32768)]:
            route=selected.split('location = /api/v1/dev-logs/'+path+' {',1)[1].split('\n}',1)[0]
            self.assertIn('if ($request_method != POST) { return 405; }',route)
            self.assertIn('client_max_body_size '+str(limit)+';',route)
            self.assertIn('proxy_pass $bff;',route);self.assertNotIn('proxy_pass $logs;',route)
            self.assertIn('proxy_set_header X-Forwarded-Host localhost:18087;',route)
            self.assertIn('proxy_set_header X-Forwarded-Proto https;',route)
            self.assertIn('proxy_set_header Forwarded "";',route)
            self.assertIn('proxy_read_timeout 6s;',route)
            self.assertIn('proxy_request_buffering off;',route)
            self.assertIn('add_header Cache-Control no-store always;',route)
            self.assertNotIn('proxy_set_header Origin',route)
            self.assertNotIn('proxy_set_header Cookie',route)
            self.assertNotIn('proxy_set_header X-CSRF',route)
            self.assertLess(selected.index('location = /api/v1/dev-logs/'+path+' {'),selected.index('location ~ "^/api/v1/dev-logs/"'))
        for path,method in [('events','GET'),('registrations','POST'),('delegations','POST'),
                            ('batches','POST'),('receipts','POST'),('storage-observation','POST')]:
            route=selected.split('location = /api/v1/dev-logs/'+path+' {',1)[1].split('\n}',1)[0]
            self.assertIn('if ($request_method != '+method+') { return 405; }',route)
            self.assertIn('proxy_pass $logs; include /run/dkp/gateway/log-proxy.conf;',route)
        delete=selected.index('location ~ "^/api/v1/dev-logs/registrations/')
        deny=selected.index('location ~ "^/api/v1/dev-logs/"')
        bff=selected.index('location ~ "^/(api|oauth|login|logout)(/|$)"')
        self.assertLess(delete,deny);self.assertLess(deny,bff)
        self.assertIn('if ($request_method != DELETE) { return 405; }',selected[delete:deny])
        for location in ['location = /api/v1/dev-logs {', 'location ~ "^/api/v1/dev-logs/" {']:
            block=selected.split(location,1)[1].split('}',1)[0]
            for header in ['Cache-Control no-store', 'X-Content-Type-Options nosniff', 'Referrer-Policy no-referrer']:
                self.assertIn('add_header '+header+' always;',block)
        self.assertIn('alias /usr/share/nginx/html/logs.html',selected)
        self.assertIn('proxy_ssl_verify on;',selected)
        self.assertNotIn('Access-Control-Allow-Origin',selected)
        m.gateway_config(self.directory,18087,18088)
        self.assertEqual(original,config.read_text())

    def observer_install_fixture(self):
        self.directory=self.directory.resolve();self.state['directory']=str(self.directory)
        self.state['project']='dkp-dev-logging-acceptance-'+'a'*12
        self.state['writerEnabled']=True
        self.state['volumes']={self.state['project']+'-postgres-data':'b'*64}
        self.launchagents=self.directory/'home/Library/LaunchAgents';self.launchagents.mkdir(parents=True)
        patches=[patch.object(m.sys,'platform','darwin'),patch.object(m.Path,'home',return_value=self.directory/'home'),
            patch.object(m,'check_resources'),patch.object(m,'container',return_value={'Id':'c'*64,'Image':'sha256:'+'d'*64}),
            patch.object(m,'docker',return_value=b'desktop-linux\n'),patch.object(m.shutil,'which',return_value=sys.executable),
            patch.object(m,'command',side_effect=lambda args,**kwargs:b'3.14.0\n' if args[0]!='/bin/launchctl' else b''),
            patch.object(m,'observer_job',return_value=None)]
        for p in patches:p.start();self.addCleanup(p.stop)
        return Path(sys.executable).resolve()

    def test_observer_install_seals_stable_owned_files_and_starts_only_dedicated_job(self):
        python=self.observer_install_fixture()
        result=m.install_observer(self.state,self.directory,python)
        self.assertEqual('OBSERVER_JOB_INSTALLED',result['stage']);self.assertEqual('PENDING',result['physicalObservation'])
        record=self.state['observer'];self.assertEqual('3.14.0',record['pythonVersion'])
        plist=m.plistlib.loads(m.private(Path(record['plist'])))
        self.assertEqual(record['arguments'],plist['ProgramArguments']);self.assertTrue(plist['KeepAlive']);self.assertTrue(plist['RunAtLoad'])
        self.assertEqual(5,plist['ThrottleInterval']);self.assertEqual('/dev/null',plist['StandardErrorPath'])
        config=json.loads(m.private(self.directory/'observer/config.json'))
        self.assertEqual('c'*64,config['pgContainerId']);self.assertNotIn('token',config)
        self.assertNotIn('observer-token',json.dumps(plist))
        m.command.assert_called_with(['/bin/launchctl','bootstrap','gui/'+str(self.state['uid']),record['plist']],timeout=5)
        self.assertEqual(record,m.observer_files(self.state,self.directory))
        # An interrupted bootstrap resumes the sealed exact job, without rewriting files or secrets.
        before={str(path):path.read_bytes() for path in (self.directory/'observer').iterdir()}
        m.install_observer(self.state,self.directory,None)
        self.assertEqual(before,{str(path):path.read_bytes() for path in (self.directory/'observer').iterdir()})
        with patch.object(m,'observer_job',return_value=123),patch.object(m,'observer_status',return_value={'result':'PASS'}) as status:
            m.command.reset_mock();self.assertEqual({'result':'PASS'},m.install_observer(self.state,self.directory,None))
            status.assert_called_once();m.command.assert_not_called()
        (self.directory/'observer/observer.py').write_bytes(b'changed')
        with self.assertRaisesRegex(m.RuntimeFailure,'CONFIG_MISMATCH'):m.observer_files(self.state,self.directory)

    def test_observer_status_requires_owned_running_job_fresh_success_not_just_disk_counters(self):
        python=self.observer_install_fixture();m.install_observer(self.state,self.directory,python)
        fresh={'schemaVersion':1,'ownerId':self.state['ownerId'],'sequence':1,'at':dt.datetime.now(dt.timezone.utc).isoformat(),
               'result':'ACCEPTED','code':'OBSERVATION_ACCEPTED','freeBytes':5368709120,'totalBytes':10737418240}
        path=self.directory/'observer/health.json'
        def publish(value):m.write_private(path,json.dumps(value),replace=path.exists())
        publish(fresh)
        with patch.object(m,'observer_job',return_value=123):
            self.assertTrue(m.observer_status(self.state,self.directory)['apiObservationAccepted'])
            publish({**fresh,'result':'FAILED','code':'HTTP_UNAVAILABLE'})
            self.assertEqual('FAIL',m.observer_status(self.state,self.directory)['result'])
            publish({**fresh,'result':'DEGRADED','code':'LOW_SPACE'})
            self.assertEqual('FAIL',m.observer_status(self.state,self.directory)['result'])
            for value in [{**fresh,'at':(dt.datetime.now(dt.timezone.utc)-dt.timedelta(seconds=6)).isoformat()},
                          {**fresh,'sequence':True},{**fresh,'ownerId':'foreign'},{**fresh,'result':'unknown'}]:
                publish(value)
                with self.assertRaises(m.RuntimeFailure):m.observer_status(self.state,self.directory)
        for pid in [None,0]:
            with patch.object(m,'observer_job',return_value=pid),self.assertRaisesRegex(m.RuntimeFailure,'NOT_RUNNING'):m.observer_status(self.state,self.directory)

    def test_native_job_probe_rejects_foreign_arguments_and_bounds_launchctl(self):
        python=self.observer_install_fixture();m.install_observer(self.state,self.directory,python)
        record=self.state['observer']
        # Use the real probe while keeping every native command mocked.
        # patch.object above retains the original in the module file; reload a separate namespace.
        spec=importlib.util.spec_from_file_location('observer_probe_fixture',Path(m.__file__))
        other=importlib.util.module_from_spec(spec);spec.loader.exec_module(other)
        text='path = '+record['plist']+'\nprogram = '+record['python']+'\narguments = {\n'+'\n'.join(record['arguments'])+'\n}\npid = 123\n'
        with patch.object(other.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=text.encode())) as run:
            self.assertEqual(123,other.observer_job(record));self.assertEqual(3,run.call_args.kwargs['timeout'])
        for output in [text.replace('pid = 123',''),text.replace('--config','--wrong')]:
            with patch.object(other.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=output.encode())):
                if '--wrong' in output:
                    with self.assertRaisesRegex(other.RuntimeFailure,'JOB_MISMATCH'):other.observer_job(record)
                else:self.assertEqual(0,other.observer_job(record))
        with patch.object(other.subprocess,'run',return_value=SimpleNamespace(returncode=113,stdout=b'')):self.assertIsNone(other.observer_job(record))
        for failure in [OSError('private'),subprocess.TimeoutExpired('private',3)]:
            with patch.object(other.subprocess,'run',side_effect=failure),self.assertRaisesRegex(other.RuntimeFailure,'SUPERVISOR_UNAVAILABLE'):other.observer_job(record)

    def test_observer_install_fails_closed_without_native_supervisor_writer_or_owned_plan(self):
        with patch.object(m.sys,'platform','linux'),self.assertRaisesRegex(m.RuntimeFailure,'NATIVE_SUPERVISOR'):m.install_observer(self.state,self.directory,None)
        with patch.object(m.sys,'platform','darwin'),self.assertRaisesRegex(m.RuntimeFailure,'REQUIRES_WRITER'):m.install_observer(self.state,self.directory,None)
        python=self.observer_install_fixture()
        with self.assertRaisesRegex(m.RuntimeFailure,'PYTHON_REQUIRED'):m.install_observer(self.state,self.directory,None)
        with patch.object(m,'observer_job',return_value=0),self.assertRaisesRegex(m.RuntimeFailure,'UNKNOWN_JOB'):m.install_observer(self.state,self.directory,python)
        m.install_observer(self.state,self.directory,python)
        self.state['observer']['label']='foreign'
        with self.assertRaisesRegex(m.RuntimeFailure,'OWNER_MISMATCH'):m.observer_files(self.state,self.directory)

    def test_observer_plist_parent_must_remain_owned_and_not_group_or_other_writable(self):
        python=self.observer_install_fixture()
        self.launchagents.chmod(0o777)
        with self.assertRaisesRegex(m.RuntimeFailure,'PLIST_DIRECTORY'):m.install_observer(self.state,self.directory,python)
        self.assertNotIn('observer',self.state)
        self.launchagents.chmod(0o755);m.install_observer(self.state,self.directory,python)
        self.launchagents.chmod(0o775)
        with self.assertRaisesRegex(m.RuntimeFailure,'PLIST_DIRECTORY'):m.observer_status(self.state,self.directory)

    def writer_fixture(self):
        self.ready();self.state['volumes']={'dkp-dev-logging-postgres-data':'a'*64}
        self.model={'version':1,'digest':self.state['schemaSha256'],'summary':{'manifests':1,'producers':0,'scopes':0,'events':2,
            'capacityReservedBytes':4096,'scopeReservedBytes':0,'eventReservedBytes':4096},'commitObservationFailure':False}
        self.trace=[]
        def sql(state,directory,payload):
            if payload.startswith(b'BEGIN;'):
                if payload.endswith(b'ROLLBACK;\n'):
                    self.trace.append('preview');return 'f'*64
                self.assertEqual('f'*64,state['writerActivation']['expectedSchemaSha256'])
                self.assertEqual('PREPARED',state['writerActivation']['stage'])
                self.trace.append('commit');self.model.update(version=2,digest='f'*64)
                if self.model['commitObservationFailure']:raise m.RuntimeFailure('LOG_RUNTIME_COMMAND_UNAVAILABLE')
                return ''
            if payload==(m.SQL/'logging-provision/schema-version.sql').read_bytes():return m.writer_versions() if self.model['version']==2 else m.writer_versions().splitlines()[0]
            if payload==(m.SQL/'logging-provision/schema-digest.sql').read_bytes():return self.model['digest']
            if payload==(m.SQL/'logging-provision/writer-summary.sql').read_bytes():return json.dumps(self.model['summary'])
            if b"rolname='dkp_log_writer'" in payload:return '0' if self.model['version']==1 else '1'
            if payload in [(m.SQL/'logging-provision/schema-state.sql').read_bytes(),(m.SQL/'logging-provision/schema-shape.sql').read_bytes()]:return 't'
            self.fail('Unexpected unit SQL request')
        def build(state,directory,component):
            self.trace.append('build-'+component)
            return ({'image':'query:new','imageId':'sha256:'+'e'*64,'sourceFingerprint':'e'*64} if component=='query'
                    else {'gatewayImage':'gateway:new','gatewayImageId':'sha256:'+'f'*64,'gatewaySourceFingerprint':'e'*64})
        patches=[patch.object(m,'check_resources'),patch.object(m,'disk'),patch.object(m,'verify_live',return_value={'result':'PASS'}),
            patch.object(m,'sql',side_effect=sql),patch.object(m,'container',side_effect=lambda state,directory,name:{'Id':name,'Image':name+'-image'}),
            patch.object(m,'build_image',side_effect=build),patch.object(m,'inspect',side_effect=lambda kind,name:{'Id':name,'Config':{'Labels':m.labels(self.state)}}),
            patch.object(m,'start',return_value={'result':'PASS'})]
        for p in patches:p.start();self.addCleanup(p.stop)

    def test_writer_preview_then_guarded_commit_preserves_old_files_and_uses_prebuilt_images(self):
        self.writer_fixture()
        before={name:m.private(self.directory/name) for name in ['query/password','query/cursor-key','query/readers.json','operator/read-token','database/admin-password']}
        self.assertEqual({'result':'PASS'},m.enable_writer(self.state,self.directory))
        self.assertEqual(['build-query','build-gateway','preview','commit'],self.trace)
        self.assertTrue(self.state['writerEnabled']);self.assertEqual('ACTIVE',self.state['writerActivation']['stage'])
        self.assertEqual(before,{name:m.private(self.directory/name) for name in before})
        self.assertIn('prepared_images',m.start.call_args.kwargs);self.assertEqual('both',m.start.call_args.kwargs['target'])
        self.assertEqual('f'*64,self.state['schemaSha256'])
        files=m.writer_bindings(self.directory);self.assertEqual(files,self.state['writerActivation']['files'])
        registration=json.loads(m.private(self.directory/'query/registration-grants.json'))
        observer=json.loads(m.private(self.directory/'query/observer-grant.json'))
        self.assertEqual([],registration['delegators']);self.assertNotEqual(observer['id'],registration['registrars'][0]['id'])
        for grant,name in [(observer,'observer-token'),(registration['registrars'][0],'registrar-token')]:
            token=m.private(self.directory/'operator'/name)
            self.assertEqual(hashlib.sha256(token).hexdigest(),grant['tokenSha256'])
            self.assertNotIn(token.decode(),json.dumps(grant))
            lifetime=dt.datetime.fromisoformat(grant['expiresAt'].replace('Z','+00:00'))-dt.datetime.now(dt.timezone.utc)
            self.assertGreater(lifetime,dt.timedelta(days=29));self.assertLessEqual(lifetime,dt.timedelta(days=30))
        m.sql.reset_mock();m.start.reset_mock();m.enable_writer(self.state,self.directory)
        m.sql.assert_not_called();m.start.assert_not_called()

    def test_writer_unknown_commit_outcome_resumes_exact_v2_without_repeating_create_role(self):
        self.writer_fixture();self.model['commitObservationFailure']=True
        with self.assertRaisesRegex(m.RuntimeFailure,'COMMAND_UNAVAILABLE'):m.enable_writer(self.state,self.directory)
        self.assertEqual('PREPARED',self.state['writerActivation']['stage']);self.assertFalse(self.state.get('writerEnabled',False))
        files=m.writer_bindings(self.directory)
        self.model['commitObservationFailure']=False
        m.enable_writer(self.state,self.directory)
        self.assertEqual(1,self.trace.count('preview'));self.assertEqual(1,self.trace.count('commit'))
        self.assertEqual(files,m.writer_bindings(self.directory));self.assertEqual('ACTIVE',self.state['writerActivation']['stage'])

    def test_writer_bindings_preserve_pki_files_and_ignore_authority_directory_without_following_symlinks(self):
        self.writer_fixture()
        authority=self.directory/'gateway-pki/authority';authority.mkdir(mode=0o550)
        self.assertEqual({'result':'PASS'},m.enable_writer(self.state,self.directory))
        files=m.writer_bindings(self.directory)
        self.assertNotIn('gateway-pki/authority',files)
        self.assertEqual(hashlib.sha256(m.private(self.directory/'gateway-pki/ca.crt')).hexdigest(),files['gateway-pki/ca.crt'])
        alias=self.directory/'gateway-pki/authority-alias';alias.symlink_to(authority,target_is_directory=True)
        with self.assertRaises(m.RuntimeFailure):m.writer_bindings(self.directory)
        alias.unlink();alias.symlink_to(self.directory/'gateway-pki/ca.crt')
        with self.assertRaises(m.RuntimeFailure):m.writer_bindings(self.directory)

    def test_writer_postcommit_start_failure_keeps_migration_and_rejects_drift_on_resume(self):
        self.writer_fixture();m.start.side_effect=m.RuntimeFailure('LOG_RUNTIME_COMMAND_FAILED')
        with self.assertRaises(m.RuntimeFailure):m.enable_writer(self.state,self.directory)
        self.assertEqual('MIGRATED',self.state['writerActivation']['stage']);self.assertTrue(self.state['writerEnabled'])
        self.model['digest']='b'*64
        with self.assertRaisesRegex(m.RuntimeFailure,'UNKNOWN_WRITER_STATE'):m.enable_writer(self.state,self.directory)
        self.model['digest']='f'*64;m.start.side_effect=None
        m.enable_writer(self.state,self.directory);self.assertEqual(1,self.trace.count('commit'))

    def test_writer_changed_inputs_and_preservation_witness_fail_closed_before_retry(self):
        self.writer_fixture();self.model['commitObservationFailure']=True
        with self.assertRaises(m.RuntimeFailure):m.enable_writer(self.state,self.directory)
        self.model['commitObservationFailure']=False
        original=m.private(self.directory/'query/writer-password')
        m.write_private(self.directory/'query/writer-password','z'*64,replace=True)
        with self.assertRaisesRegex(m.RuntimeFailure,'ACTIVATION_INPUT_CHANGED'):m.enable_writer(self.state,self.directory)
        m.write_private(self.directory/'query/writer-password',original,replace=True)
        self.model['summary']['events']+=1
        with self.assertRaisesRegex(m.RuntimeFailure,'DATA_CHANGED'):m.enable_writer(self.state,self.directory)
        self.assertEqual(1,self.trace.count('commit'))
        with self.assertRaisesRegex(m.RuntimeFailure,'UNKNOWN_WRITER_FILES'):m.create_writer_files(self.directory)
        with self.assertRaises(m.RuntimeFailure):m.metadata_guard("x';COMMIT;")

    def test_writer_sql_preview_rolls_back_and_real_commit_checks_expected_metadata_before_commit(self):
        m.create_writer_files(self.directory)
        preview=m.writer_transition_sql(self.directory,'a'*64)
        commit=m.writer_transition_sql(self.directory,'a'*64,'b'*64)
        self.assertTrue(preview.endswith(b'ROLLBACK;\n'));self.assertNotIn(b'COMMIT;',preview)
        self.assertTrue(commit.endswith(b'COMMIT;\n'));self.assertIn(b'IS DISTINCT FROM',commit)
        self.assertIn((m.SQL/'logging-migration/V2__scoped_producer_credentials.sql').read_bytes(),preview)
        self.assertIn(b"rolname='dkp_log_writer'",preview);self.assertIn(b'LOCK TABLE dev_logs.schema_migrations',preview)
        self.assertLess(commit.index(b'CREATE ROLE dkp_log_writer'),commit.rindex(b"'"+b'b'*64+b"'"))
        self.assertNotEqual(m.config_digest(self.directory),m.config_digest(self.directory,True))
        self.ready();self.state.update(writerEnabled=True,volumes={'dkp-dev-logging-postgres-data':'c'*64})
        env=m.environment(self.state,self.directory)
        self.assertIn('DKP_LOG_WRITER_ENABLED="true"',env);self.assertIn('DKP_LOG_VOLUME_IDENTITY="'+'c'*64+'"',env)
        self.assertNotIn(m.private(self.directory/'query/writer-password').decode(),env)

    def test_writer_grant_renewal_preserves_token_ids_and_does_not_extend_producer_scopes(self):
        self.writer_fixture();m.enable_writer(self.state,self.directory)
        before=json.loads(m.private(self.directory/'query/registration-grants.json'));token=m.private(self.directory/'operator/registrar-token')
        m.sql.reset_mock();m.start.reset_mock();m.renew_writer_grants(self.state,self.directory)
        after=json.loads(m.private(self.directory/'query/registration-grants.json'))
        self.assertEqual(before['registrars'][0]['id'],after['registrars'][0]['id'])
        self.assertEqual(token,m.private(self.directory/'operator/registrar-token'))
        self.assertEqual(before['registrars'][0]['tokenSha256'],after['registrars'][0]['tokenSha256'])
        m.sql.assert_not_called();self.assertEqual('query',m.start.call_args.kwargs['target'])
        m.write_private(self.directory/'operator/observer-token','wrong-token',replace=True)
        with self.assertRaisesRegex(m.RuntimeFailure,'GRANTS_INVALID'):m.renew_writer_grants(self.state,self.directory)

    def manifest_operation_fixture(self):
        self.ready();self.state['writerEnabled']=True
        self.manifest=self.directory/'reviewed.json';m.write_private(self.manifest,'{}')
        self.manifest=self.manifest.resolve();self.manifest_sha=hashlib.sha256(b'{}').hexdigest()
        self.operation_labels={};self.operation_commands=[];self.operation_running=False;self.operation_start_failure=False
        def docker(*args,**kwargs):
            self.operation_commands.append(args)
            if args[0]=='create':
                self.operation_labels.update(dict(args[index+1].split('=',1) for index,value in enumerate(args) if value=='--label'))
                return ('e'*64).encode()
            if args[0]=='start':
                if self.operation_start_failure:raise m.RuntimeFailure('LOG_RUNTIME_COMMAND_UNAVAILABLE')
                return json.dumps({'result':'INSTALLED','manifestSha256':self.manifest_sha,'buildId':'11111111-1111-4111-8111-111111111111'}).encode()
            return b''
        def inspect(kind,name):
            return {'Id':name,'Image':self.state['imageId'],'Config':{'Labels':self.operation_labels if kind=='container' else m.labels(self.state)},
                    'State':{'Running':self.operation_running,'ExitCode':0}}
        for p in [patch.object(m,'verify_live'),patch.object(m,'docker',side_effect=docker),patch.object(m,'inspect',side_effect=inspect)]:
            p.start();self.addCleanup(p.stop)

    def test_private_manifest_operation_has_only_fixed_private_mounts_and_terminal_owned_cleanup(self):
        self.manifest_operation_fixture()
        result=m.install_manifest(self.state,self.directory,self.manifest,self.manifest_sha)
        self.assertEqual('INSTALLED',result['result'])
        create=self.operation_commands[0];joined=' '.join(create)
        self.assertEqual(2,create.count('--mount'));self.assertIn('dkp-dev-logging-data',create)
        self.assertIn('--read-only',create);self.assertNotIn('--publish',create);self.assertNotIn('docker.sock',joined)
        self.assertNotIn(m.private(self.directory/'database/admin-password').decode(),joined)
        self.assertIn('/run/dkp/owner-password',create);self.assertEqual(('rm','e'*64),self.operation_commands[-1])
        evidence=list((self.directory/'operator').glob('manifest-operation-*.json'));self.assertEqual(1,len(evidence))
        record=json.loads(m.private(evidence[0]));self.assertEqual('REMOVED',record['cleanup']);self.assertEqual('INSTALLED',record['result'])

    def test_unknown_running_manifest_operation_is_retained_without_force_stop_and_input_must_match(self):
        self.manifest_operation_fixture()
        with self.assertRaisesRegex(m.RuntimeFailure,'INPUT_INVALID'):m.install_manifest(self.state,self.directory,self.manifest,'f'*64)
        self.assertEqual([],self.operation_commands)
        self.operation_running=True;self.operation_start_failure=True
        with self.assertRaisesRegex(m.RuntimeFailure,'COMMAND_UNAVAILABLE'):m.install_manifest(self.state,self.directory,self.manifest,self.manifest_sha)
        self.assertFalse(any(call[0]=='rm' for call in self.operation_commands))
        evidence=list((self.directory/'operator').glob('manifest-operation-*.json'))
        record=json.loads(m.private(evidence[0]));self.assertEqual('RETAINED',record['cleanup']);self.assertEqual('STILL_RUNNING',record['stage'])


if __name__=='__main__':unittest.main()
