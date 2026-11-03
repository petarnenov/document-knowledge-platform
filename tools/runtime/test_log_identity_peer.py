"""Owned persistent peer contract; real Keycloak/TLS acceptance is separate."""
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import test_dev_logging as fixture

spec=importlib.util.spec_from_file_location('identity_peer',Path(__file__).with_name('log-identity-peer.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class IdentityPeerTest(unittest.TestCase):
    def setUp(self):
        self.fixture=fixture.LoggingRuntimeTests();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.shared=self.fixture.directory.resolve();self.state=self.fixture.ready();self.state['directory']=str(self.shared);fixture.m.save(self.shared,self.state)
        self.directory=self.shared.parent/'identity';self.directory.parent.chmod(0o700)
        fixture.m.write_private(self.shared/'gateway-pki/dkp-realm.json','{"realm":"dkp","private":"synthetic"}')
        fixture.m.write_private(self.shared/'gateway-pki/runtime.env','KC_BOOTSTRAP_ADMIN_USERNAME=fixture-admin\nKC_BOOTSTRAP_ADMIN_PASSWORD='+'p'*48+'\nUNRELATED_SECRET=never-export\n')
        self.edge={'Id':'e'*64,'Internal':False,'IPAM':{'Config':[{'Subnet':self.state['subnets']['edge']}]},'Labels':fixture.m.labels(self.state)}
        self.container=None;self.image=None;self.creates=0;self.starts=0;self.discovery=m.discovery;self.collision=False
        sys.path.insert(0,str(m.r.ROOT/'tools/ci'));import verify
        self.public=verify
        for replacement in (patch.object(m.r,'verify_live'),patch.object(m.r,'docker',side_effect=self.docker),
                patch.object(m.r,'inspect',side_effect=self.inspect),patch.object(m.r,'exists',return_value=False),
                patch.object(m,'discovery'),patch.object(verify,'snapshot',return_value={'fingerprint':'f'*64})):
            replacement.start();self.addCleanup(replacement.stop)

    def inspect(self,kind,name):
        if kind=='network':return self.edge
        if kind=='image':return self.image
        if kind=='container':return self.container
        raise AssertionError((kind,name))

    def docker(self,*args,**kwargs):
        if args[0]=='ps':return b'c'*12 if self.container else b''
        if args[0]=='build':
            labels=dict(args[i+1].split('=',1) for i,x in enumerate(args) if x=='--label')
            self.image={'Id':'sha256:'+'a'*64,'Os':'linux','Architecture':'amd64','Config':{'Labels':labels,
                'Env':['PATH=/usr/bin:/bin','KC_RUN_IN_CONTAINER=true'],'Entrypoint':['/opt/keycloak/bin/kc.sh'],
                'Healthcheck':{'Test':['CMD','/bin/bash','/opt/keycloak/probe.sh'],'Interval':5000000000}}}
            return b''
        if args[0]=='create':
            self.creates+=1;labels=dict(args[i+1].split('=',1) for i,x in enumerate(args) if x=='--label')
            name=args[args.index('--name')+1];env=Path(args[args.index('--env-file')+1]).read_text().splitlines()
            mounts=[]
            for index,value in enumerate(args):
                if value!='--mount':continue
                fields=args[index+1].split(',');values=dict(row.split('=',1) for row in fields if '=' in row)
                mounts.append({'Type':values['type'],'Source':values['src'],'Destination':values['dst'],'RW':'readonly' not in fields})
            self.container={'Id':'c'*64,'Name':'/'+name,'Image':self.image['Id'],
                'Config':{'Labels':labels,'User':args[args.index('--user')+1],'Env':self.image['Config']['Env']+env,
                    'Cmd':['start','--optimized','--import-realm'],'Entrypoint':self.image['Config']['Entrypoint'],
                    'Healthcheck':self.image['Config']['Healthcheck']},
                'HostConfig':{'ReadonlyRootfs':True,'RestartPolicy':{'Name':'unless-stopped'},'CapDrop':['ALL'],
                    'SecurityOpt':['no-new-privileges:true'],'PortBindings':{},'Memory':768*1024*1024,'NanoCpus':2000000000,
                    'PidsLimit':128,'Tmpfs':{'/tmp':args[args.index('--tmpfs')+1].split(':',1)[1]}},
                'NetworkSettings':{'Networks':{self.state['project']+'-edge':{'NetworkID':'','Aliases':['keycloak']}}},
                'Mounts':mounts,'State':{'Running':False,'Status':'created','Health':{'Status':'starting'}}}
            return self.container['Id'].encode()
        if args[0]=='start':
            self.starts+=1;self.container['State']={'Running':True,'Status':'running','Health':{'Status':'healthy'}}
            self.container['NetworkSettings']['Networks'][self.state['project']+'-edge']['NetworkID']=self.edge['Id'];return b''
        raise AssertionError(args)

    def test_private_persistent_start_repeat_and_explicit_restart_keep_same_owned_container(self):
        result=m.start(self.directory,self.shared);self.assertEqual('PASS',result['result']);self.assertEqual(1,self.creates)
        self.assertEqual(result,m.start(self.directory,self.shared));self.assertEqual(1,self.starts)
        self.container['State']['Running']=False
        self.assertEqual(result,m.start(self.directory,self.shared));self.assertEqual(2,self.starts);self.assertEqual(1,self.creates)
        record=json.loads((self.directory/'state.json').read_text());self.assertEqual('READY',record['stage'])
        self.assertNotIn('p'*48,json.dumps(record));self.assertNotIn('UNRELATED_SECRET',(self.directory/'runtime.env').read_text())
        self.assertEqual((self.shared/'gateway-pki/dkp-realm.json').read_bytes(),(self.directory/'data/import/dkp-realm.json').read_bytes())
        self.assertEqual(3,len(self.container['Mounts']));self.assertFalse(any('ca.' in value['Source'] for value in self.container['Mounts']))
        self.assertEqual(0,self.directory.stat().st_mode&0o077);self.assertEqual(0,(self.directory/'runtime.env').stat().st_mode&0o077)

    def test_foreign_container_image_mount_network_environment_and_profile_cannot_be_adopted(self):
        m.start(self.directory,self.shared);original=copy.deepcopy(self.container)
        for change in [lambda c:c.update(Image='sha256:'+'b'*64),lambda c:c['Config'].update(User='0:0'),
                lambda c:c['HostConfig'].update(PortBindings={'8443/tcp':[{'HostPort':'443'}]}),
                lambda c:c['HostConfig'].update(RestartPolicy={'Name':'no'}),lambda c:c['HostConfig'].update(Tmpfs={}),
                lambda c:c['Config']['Env'].append('UNRELATED_SECRET=foreign'),
                lambda c:c['NetworkSettings']['Networks'][self.state['project']+'-edge'].update(NetworkID='d'*64),
                lambda c:c['NetworkSettings']['Networks'][self.state['project']+'-edge'].update(NetworkID=''),
                lambda c:c['Mounts'].append({'Type':'bind','Source':'/private/key','Destination':'/private/key','RW':False})]:
            self.container=copy.deepcopy(original);change(self.container)
            with self.assertRaises(m.r.RuntimeFailure):m.start(self.directory,self.shared)
            self.assertEqual(1,self.creates);self.assertEqual(1,self.starts)
        self.container=original;self.image['Config']['Labels']['dkp.identity.source']='0'*64
        with self.assertRaises(m.r.RuntimeFailure):m.status(self.directory,self.shared)

    def test_private_inputs_and_persisted_reference_drift_reject_without_replacement(self):
        m.start(self.directory,self.shared)
        (self.directory/'data/import/dkp-realm.json').write_text('changed')
        with self.assertRaises(m.r.RuntimeFailure):m.start(self.directory,self.shared)
        self.assertEqual(1,self.creates);self.assertEqual(1,self.starts)
        with self.assertRaises((m.r.RuntimeFailure,FileNotFoundError)):m.start(self.shared.parent/'missing'/'peer',self.shared)

    def test_not_ready_preserves_owned_container_and_later_start_checks_same_identity(self):
        with patch.object(m,'status',side_effect=m.r.RuntimeFailure('NOT_READY')),patch.object(m.time,'monotonic',side_effect=[0,76]):
            with self.assertRaisesRegex(m.r.RuntimeFailure,'NOT_READY'):m.start(self.directory,self.shared)
        self.assertTrue(self.container['State']['Running']);self.assertEqual(1,self.creates)
        self.assertEqual('PASS',m.start(self.directory,self.shared)['result']);self.assertEqual(1,self.starts)

    def test_alias_conflict_and_incomplete_start_are_visible_without_unknown_adoption(self):
        self.container={'Id':'b'*64,'NetworkSettings':{'Networks':{self.state['project']+'-edge':{'Aliases':['keycloak']}}}}
        with self.assertRaisesRegex(m.r.RuntimeFailure,'ALIAS_CONFLICT'):m.start(self.directory,self.shared)
        self.assertFalse(self.directory.exists());self.container=None
        def failed(*args,**kwargs):raise m.r.RuntimeFailure('BUILD_FAILED')
        with patch.object(m.r,'docker',side_effect=lambda *args,**kw: b'' if args[0]=='ps' else failed()):
            with self.assertRaises(m.r.RuntimeFailure):m.start(self.directory,self.shared)
        with self.assertRaisesRegex(m.r.RuntimeFailure,'START_INCOMPLETE'):m.start(self.directory,self.shared)
        self.assertEqual(0,self.creates)

    def test_discovery_and_cli_fixed_results(self):
        current=m.inputs(self.shared)
        class Response(io.BytesIO):status=200
        class Opener:
            def open(inner,url,**kwargs):
                self.assertEqual(current['issuer']+'/.well-known/openid-configuration',url)
                return Response(json.dumps({'issuer':current['issuer']}).encode())
        with patch.object(m.urllib.request,'build_opener',return_value=Opener()),patch.object(m.ssl,'create_default_context'):
            self.discovery(current,self.shared)
        args=['log-identity-peer.py','start','--directory',str(self.directory),'--shared-directory',str(self.shared)]
        with patch.object(sys,'argv',args),patch.object(m.r,'local_daemon'),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0,m.main());self.assertEqual('PASS',json.loads(output.getvalue())['result'])
        args[1]='status'
        with patch.object(sys,'argv',args),patch.object(m.r,'local_daemon'),contextlib.redirect_stdout(io.StringIO()):self.assertEqual(0,m.main())
        with patch.object(sys,'argv',args),patch.object(m.r,'local_daemon',side_effect=ValueError('private material')),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1,m.main());self.assertNotIn('private material',output.getvalue())


if __name__=='__main__':unittest.main()
