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
                'NetworkSettings':{'Networks':{'dkp-dev-logging-'+name:{} for name in networks},'Ports':ports},'HostConfig':{'ReadonlyRootfs':True,'CapDrop':['ALL'],
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


if __name__=='__main__':unittest.main()
