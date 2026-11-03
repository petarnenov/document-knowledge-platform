"""Real original compiled SDK/private spool/TLS tests; Docker ownership is a unit seam, not adoption."""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import unittest
import uuid
from unittest.mock import patch

import test_log_python_deliver as tls
import test_log_worker_records as record_tests

f = tls.load('worker_forward_test_subject', 'log-worker-forward.py')


class WorkerForwardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        record_tests.WorkerRecordsTest.setUpClass()
        cls.addClassCleanup(record_tests.WorkerRecordsTest.doClassCleanups)

    def setUp(self):
        self.tls = tls.PythonDeliveryTests();self.tls.setUp();self.addCleanup(self.tls.doCleanups)
        self.record = record_tests.WorkerRecordsTest();self.record.setUp()
        self.identity, self.vocabulary = self.record.identity, self.record.vocabulary
        self.state = self.tls.directory/'forward';self.state.mkdir(mode=0o700)
        self.context = self.tls.directory/'worker';self.context.mkdir(mode=0o700)
        manifest = {**self.identity, 'vocabulary':self.vocabulary}
        path = self.context/'manifest.json';path.write_text(json.dumps(manifest));path.chmod(0o600)
        self.proof = dict(buildId=self.identity['buildId'], manifestSha256='c'*64, component='structure-inspector',
            imageId='sha256:'+'1'*64, operation='2'*32, recipeSha256='3'*64)
        self.config = dict(schemaVersion=1, imageDirectory=str(self.context), containerId='4'*64,
            containerStartedAt='2026-11-03T22:00:00.123456789Z', runId=self.identity['runId'], instanceId=self.identity['instanceId'],
            sdkArtifact=str(self.tls.built['artifact']), sdkArtifactSha256=self.tls.arguments()[1], sdkManifestSha256=self.tls.built['manifestSha256'],
            **{k:self.tls.config[k] for k in ('endpoint','connectAddress','producerToken','caFile')})
        self.path = self.tls.directory/'forward.private.json';self.save()
        self.actual = dict(Id=self.config['containerId'], Image=self.proof['imageId'], RestartCount=0,
            State=dict(Running=True, StartedAt=self.config['containerStartedAt']),
            Config=dict(Image=self.proof['imageId'], Labels=dict(f.image.labels_for(self.proof),
                **{'dkp.log.run-id':self.identity['runId'],'dkp.log.instance-id':self.identity['instanceId']}),
                Env=['DKP_INSPECTOR_LOG_MANIFEST_SHA256='+'c'*64,'DKP_INSPECTOR_LOG_RUN_ID='+self.identity['runId'],
                    'DKP_INSPECTOR_LOG_INSTANCE_ID='+self.identity['instanceId']],
                User='10001:10001', Entrypoint=['/app/bin/structure-inspector'], Cmd=None, Tty=False),
            HostConfig=dict(ReadonlyRootfs=True,CapDrop=['ALL'],CapAdd=None,Privileged=False,
                SecurityOpt=['no-new-privileges:true'],NetworkMode='container:'+'5'*64,RestartPolicy={'Name':'no'},
                LogConfig={'Type':'json-file','Config':{'max-size':'1m','max-file':'3'}}),Mounts=[])
        self.base_env=['JAVA_HOME=/opt/java/openjdk','PATH=/opt/java/openjdk/bin:/usr/local/bin:/usr/bin:/bin']
        self.actual['Config']['Env']+=self.base_env
        self.actual.update(Path='/app/bin/structure-inspector',Args=[])
        self.actual['Mounts']=[dict(Type='volume',Destination='/run/dkp/inspector',RW=False,Name='owned-worker-tls')]
        self.actual['HostConfig']['Tmpfs']={'/tmp':'uid=10001,gid=10001,mode=0700,size=1g'}
        self.commands=[];self.children=[]
        self.addCleanup(self.cleanup_children)

    def save(self):self.path.write_text(json.dumps(self.config));self.path.chmod(0o600)

    def cleanup_children(self):
        for child in self.children:
            if child.poll() is None:child.kill();child.wait(2)

    @contextlib.contextmanager
    def source(self, raw=None, delay=20):
        original = subprocess.Popen
        raw = self.record.line() if raw is None else raw
        def spawn(args, **kwargs):
            self.assertEqual(['docker','logs','--follow','--tail','512',self.config['containerId']],args)
            self.assertEqual(subprocess.DEVNULL,kwargs['stderr']);self.commands.append(args)
            child=original([sys.executable,'-c','import os,time;os.write(1,'+repr(raw)+');time.sleep('+str(delay)+')'],**kwargs)
            self.children.append(child);return child
        with patch.object(f.image,'image',return_value=self.proof),patch.object(f.image.r,'inspect',return_value={'Id':self.proof['imageId'],'Config':{'Env':self.base_env}}),patch.object(f.image.r,'command',return_value=json.dumps([self.actual]).encode()),patch.object(f.subprocess,'Popen',side_effect=spawn):yield

    def run_until(self, predicate, raw=None):
        stop = threading.Event();observed=[]
        def watcher():
            deadline=time.monotonic()+8
            while time.monotonic()<deadline:
                if predicate():observed.append(True);break
                time.sleep(.02)
            stop.set()
        thread=threading.Thread(target=watcher);thread.start()
        try:
            with self.source(raw):result=f.forward(self.path,self.state,stop)
        finally:stop.set();thread.join(10)
        self.assertTrue(observed,'normal delivery/recovery condition was not reached')
        return result

    def read_status(self):
        path=self.state/'status.json'
        return json.loads(path.read_text()) if path.exists() else {}

    def test_real_sourceless_sdk_tls_original_identity_exact_receipt_and_fixed_source(self):
        event=self.record.event
        result=self.run_until(lambda:event['eventId'] in self.tls.rows)
        row=self.tls.rows[event['eventId']]
        for key,value in self.identity.items():self.assertEqual(value,row[key])
        self.assertEqual(event['timestamp'],row['timestamp'])
        expected=json.loads(sys.modules['dkp_logging.wire'].Wire(self.identity,self.vocabulary).prepare(event).data)
        self.assertEqual(expected,row)
        self.assertEqual('java.io.IOException',row['context']['exceptionClass'])
        self.assertEqual(event['diagnostics']['failure']['stackFrames'],row['context']['stackFrames'])
        self.assertNotEqual(self.tls.built['buildId'],row['buildId'])
        self.assertEqual('STOP_REQUESTED',result['streamState']);self.assertTrue(result['dispatcherStopped'])
        self.assertEqual('NOT_ASSERTED',result['persistence']);self.assertFalse(result['historyComplete'])
        self.assertNotIn(self.config['producerToken'],json.dumps(result));self.assertEqual(0,(self.state/'status.json').stat().st_mode&0o077)
        # Independent named receipt check, not an enqueue/counter inference.
        https=sys.modules['dkp_logging.https'];wire=sys.modules['dkp_logging.wire']
        transport=https.HttpsTransport(self.config['endpoint'],self.config['producerToken'],self.config['caFile'],self.config['connectAddress'])
        try:
            code,body=transport.post('receipts',wire.encode({'eventIds':[event['eventId']]}))
            self.assertEqual(200,code);self.assertEqual({event['eventId']:'COMMITTED'},wire.receipt_states(body,[event['eventId']]))
        finally:transport.close()
        self.assertTrue(all(child.poll() is not None for child in self.children))

    def test_real_outage_durable_restart_preserves_payload_uuid_and_deduplicates_readback(self):
        self.tls.available=False
        result=self.run_until(lambda:self.read_status().get('producer',{}).get('spooled',0)==1)
        self.assertEqual(1,result['producer']['pending']);self.assertEqual({},self.tls.rows)
        self.assertTrue((self.state/'spool/pending.bin').exists())
        self.tls.reset_imports();self.tls.available=True
        result=self.run_until(lambda:self.record.event['eventId'] in self.tls.rows,raw=self.record.line()*2)
        self.assertEqual({self.record.event['eventId']},set(self.tls.rows))
        self.assertEqual(self.record.event['timestamp'],next(iter(self.tls.rows.values()))['timestamp'])
        self.assertEqual(2,result['reader']['accepted']);self.assertEqual(0,result['reader']['rejected'])

    def test_eof_partial_and_source_failure_are_visible_without_persistence_claim(self):
        with self.source(b'DKP-INSPECTOR-LOG/1 {',delay=0):result=f.forward(self.path,self.state)
        self.assertEqual('EOF',result['streamState']);self.assertTrue(result['reader']['incomplete'])
        self.assertEqual('NOT_ASSERTED',result['persistence']);self.assertEqual({},self.tls.rows)
        self.tls.reset_imports()
        with self.source(),patch.object(f.subprocess,'Popen',side_effect=OSError('PRIVATE_CANARY')):
            with self.assertRaisesRegex(ValueError,'LOG_WORKER_FORWARD_FAILED'):f.forward(self.path,self.state)
        self.assertEqual('FAILED',self.read_status()['streamState']);self.assertNotIn('PRIVATE_CANARY',json.dumps(self.read_status()))

    def test_container_replacement_stops_only_owned_reader_and_retains_unknown(self):
        calls=[]
        def inspect(*args,**kwargs):
            calls.append(True);actual=copy.deepcopy(self.actual)
            if len(calls)>1:actual['State']['StartedAt']='2026-11-03T22:01:00.1Z'
            return json.dumps([actual]).encode()
        with self.source(b''),patch.object(f.image.r,'command',side_effect=inspect):result=f.forward(self.path,self.state)
        self.assertEqual('BINDING_LOST',result['streamState']);self.assertTrue(result['dispatcherStopped'])
        self.assertTrue(all(child.poll() is not None for child in self.children));self.assertEqual({},self.tls.rows)

    def test_config_and_live_ownership_refusals_happen_before_spawn_or_network(self):
        original=copy.deepcopy(self.config)
        for changes in ({'schemaVersion':True},{'runId':'bad'},{'containerId':'tag'},{'containerStartedAt':'bad'},
                {'producerToken':'short'},{'registrarToken':'PRIVATE_CANARY'}):
            self.config={**original,**changes};self.save()
            with self.source(),self.assertRaises(ValueError):f.configuration(self.path)
        self.config=original;self.save()
        for change in ('image','running','restart','started','labels','env','user','entrypoint','cmd','tty','readonly','cap','network','rotation','socket'):
            actual=copy.deepcopy(self.actual)
            if change=='image':actual['Image']='sha256:'+'9'*64
            elif change=='running':actual['State']['Running']=False
            elif change=='restart':actual['RestartCount']=1
            elif change=='started':actual['State']['StartedAt']='changed'
            elif change=='labels':actual['Config']['Labels']={}
            elif change=='env':actual['Config']['Env']+=actual['Config']['Env'][:1]
            elif change=='user':actual['Config']['User']='0'
            elif change=='entrypoint':actual['Config']['Entrypoint']=['/bin/sh']
            elif change=='cmd':actual['Config']['Cmd']=['argument']
            elif change=='tty':actual['Config']['Tty']=True
            elif change=='readonly':actual['HostConfig']['ReadonlyRootfs']=False
            elif change=='cap':actual['HostConfig']['CapAdd']=['NET_ADMIN']
            elif change=='network':actual['HostConfig']['NetworkMode']='host'
            elif change=='rotation':actual['HostConfig']['LogConfig']['Config']={}
            else:actual['Mounts']=[{'Type':'bind','Destination':'/var/run/docker.sock','RW':False}]
            with self.source(),patch.object(f.image.r,'command',return_value=json.dumps([actual]).encode()),self.subTest(change=change),self.assertRaises(ValueError):f.configuration(self.path)
        self.assertEqual([],self.commands);self.assertEqual([],self.tls.requests)
        alias=self.tls.directory/'alias';alias.symlink_to(self.state)
        with self.assertRaises(ValueError):f.directory(alias)
        with patch.object(f.platform,'python_version',return_value='3.11.2'),self.assertRaises(ValueError):f.configuration(self.path)

    def test_immutable_distribution_rejects_bind_volume_and_tmpfs_overlays(self):
        with self.source():f.configuration(self.path)
        temporary=copy.deepcopy(self.actual)
        temporary['Mounts'].append(dict(Type='tmpfs',Destination='/tmp',RW=True))
        with self.source(),patch.object(f.image.r,'command',return_value=json.dumps([temporary]).encode()):f.configuration(self.path)
        for target in ('/app','/app/bin','/app/lib','/','/opt/java/openjdk','/bin','/run/dkp/inspector/extra'):
            for kind in ('bind','tmpfs'):
                actual=copy.deepcopy(self.actual)
                if kind=='bind':actual['Mounts'].append(dict(Type='bind',Destination=target,RW=False))
                else:actual['HostConfig']['Tmpfs'][target]='mode=0700'
                with self.source(),patch.object(f.image.r,'command',return_value=json.dumps([actual]).encode()),self.subTest(target=target,kind=kind),self.assertRaises(ValueError):f.configuration(self.path)
        actual=copy.deepcopy(self.actual);actual['Mounts'][0]['RW']=True
        with self.source(),patch.object(f.image.r,'command',return_value=json.dumps([actual]).encode()),self.assertRaises(ValueError):f.configuration(self.path)
        self.assertEqual([],self.commands);self.assertEqual([],self.tls.requests)

    def test_immutable_launcher_rejects_effective_command_or_environment_substitution(self):
        for name in ('JAVA_HOME','PATH','JAVA_OPTS','STRUCTURE_INSPECTOR_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','LD_PRELOAD','LD_LIBRARY_PATH','ENV','BASH_ENV','CLASSPATH'):
            actual=copy.deepcopy(self.actual)
            actual['Config']['Env']=[entry for entry in actual['Config']['Env'] if not entry.startswith(name+'=')]+[name+'=PRIVATE_CANARY']
            with self.source(),patch.object(f.image.r,'command',return_value=json.dumps([actual]).encode()),self.subTest(name=name),self.assertRaises(ValueError):f.configuration(self.path)
        for key,value in (('Path','/bin/sh'),('Args',['unselected-argument'])):
            actual=copy.deepcopy(self.actual);actual[key]=value
            with self.source(),patch.object(f.image.r,'command',return_value=json.dumps([actual]).encode()),self.subTest(key=key),self.assertRaises(ValueError):f.configuration(self.path)
        self.assertEqual([],self.commands);self.assertEqual([],self.tls.requests)

    def test_closed_cli_status_and_failure_redact_private_text(self):
        argv=['forward','--configuration',str(self.path),'--directory',str(self.state)]
        with patch.object(sys,'argv',argv),patch.object(f,'forward',return_value={'streamState':'STOP_REQUESTED','persistence':'NOT_ASSERTED'}),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0,f.main())
        self.assertEqual('STOP_REQUESTED',json.loads(output.getvalue())['streamState'])
        with patch.object(sys,'argv',argv),patch.object(f,'forward',side_effect=ValueError('PRIVATE_CANARY')),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1,f.main())
        self.assertEqual({'result':'FAIL','code':'LOG_WORKER_FORWARD_INVALID'},json.loads(output.getvalue()))


if __name__=='__main__':unittest.main()
