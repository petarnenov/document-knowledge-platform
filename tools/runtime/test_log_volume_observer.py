"""Observer unit boundaries; real volume/TLS/supervisor proof belongs to isolated acceptance."""
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import ssl
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec=importlib.util.spec_from_file_location('volume_observer',Path(__file__).with_name('log-volume-observer.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class ObserverTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.directory=Path(self.temp.name).resolve();self.directory.chmod(0o700)
        for name in ['observer','operator','gateway-pki']:(self.directory/name).mkdir(mode=0o700)
        self.config={'schemaVersion':1,'ownerId':'a'*32,'directory':str(self.directory),'project':'dkp-dev-logging-acceptance-'+'b'*12,
            'pgContainerId':'c'*64,'pgImageId':'sha256:'+'d'*64,'volumeIdentity':'e'*64,
            'dockerBinary':str(Path(sys.executable).resolve()),'dockerContext':'desktop-linux','port':18443}
        self.path=self.directory/'observer/config.json'
        self.write(self.path,json.dumps(self.config).encode())
        self.write(self.directory/'operator/observer-token',b's'*64)
        self.write(self.directory/'gateway-pki/ca.crt',b'trusted-fixture-ca')
        self.labels={'dkp.owner':'shared-dev-logging','dkp.issue':'457','dkp.logging.owner-id':self.config['ownerId']}
        self.volume={'Name':self.config['project']+'-postgres-data','CreatedAt':'fixture','Mountpoint':'/fixture',
            'Driver':'local','Options':None,'Scope':'local','Labels':self.labels}
        self.config['volumeIdentity']=hashlib.sha256(json.dumps(self.volume,sort_keys=True).encode()).hexdigest()
        self.pg={'Id':self.config['pgContainerId'],'Image':self.config['pgImageId'],'State':{'Running':True},
            'Config':{'Labels':self.labels},'Mounts':[{'Type':'volume','Name':self.volume['Name'],'Destination':'/var/lib/postgresql','RW':True}]}

    def write(self,path,value):
        path.write_bytes(value);path.chmod(0o600)

    def responses(self):
        return [json.dumps([{'Endpoints':{'docker':{'Host':'unix:///fixture.sock'}}}]).encode(),
                json.dumps([self.pg]).encode(),json.dumps([self.volume]).encode(),b'1B-blocks Avail\n10737418240 5368709120\n']

    def observation(self):
        return {'volumeIdentity':self.config['volumeIdentity'],'freeBytes':5368709120,'totalBytes':10737418240}

    def test_private_configuration_is_closed_canonical_owned_and_does_not_load_secret_into_config(self):
        selected=m.configuration(self.path)
        self.assertEqual('desktop-linux',selected['dockerContext']);self.assertNotIn('s'*64,json.dumps(selected))
        for key,value in [('schemaVersion',True),('ownerId','bad'),('project','other'),('pgContainerId','bad'),
                ('pgImageId','unpinned'),('volumeIdentity','bad'),('dockerContext','tcp://remote'),('port',True),('port',0),
                ('port',65536),('dockerBinary','/missing'),('directory',str(self.directory/'missing')),('extra','bad')]:
            altered={**selected,key:value};self.write(self.path,json.dumps(altered).encode())
            with self.assertRaises(Exception):m.configuration(self.path)
        self.write(self.path,b'{"schemaVersion":1,"schemaVersion":1}')
        with self.assertRaises(m.ObserverFailure):m.configuration(self.path)
        self.write(self.path,b'[]')
        with self.assertRaises(m.ObserverFailure):m.configuration(self.path)
        self.write(self.path,json.dumps({**selected,'dockerContext':4}).encode())
        with self.assertRaises(m.ObserverFailure):m.configuration(self.path)
        self.write(self.path,json.dumps(selected).encode());self.path.chmod(0o644)
        with self.assertRaises(m.ObserverFailure):m.configuration(self.path)
        self.path.chmod(0o600)
        alias=self.directory/'alias';alias.symlink_to(self.path)
        with self.assertRaises(m.ObserverFailure):m.configuration(alias)
        self.write(self.directory/'operator/observer-token',b'invalid token')
        with self.assertRaises(m.ObserverFailure):m.configuration(self.path)

    def test_exact_physical_measurement_checks_local_context_container_and_volume_before_fixed_df(self):
        with patch.object(m,'docker',side_effect=self.responses()) as commands:
            self.assertEqual(self.observation(),m.measure(self.config))
        deadlines=[call.args[1] for call in commands.call_args_list]
        self.assertEqual(1,len(set(deadlines)))
        self.assertEqual(('exec',self.config['pgContainerId'],'df','-B1','--output=size,avail','/var/lib/postgresql'),commands.call_args.args[2:])
        for target,key,value in [('pg','Id','f'*64),('pg','Image','other'),('pg','Mounts',[]),('pg','State',{'Running':False}),
                                 ('volume','Mountpoint','changed'),('volume','Name','other')]:
            original=getattr(self,target);altered=copy.deepcopy(original);altered[key]=value;setattr(self,target,altered)
            with patch.object(m,'docker',side_effect=self.responses()),self.assertRaises(m.ObserverFailure):m.measure(self.config)
            setattr(self,target,original)
        self.pg['Config']['Labels']={}
        with patch.object(m,'docker',side_effect=self.responses()),self.assertRaises(m.ObserverFailure):m.measure(self.config)

    def test_measurement_rejects_remote_context_bad_inspect_and_malformed_or_unsafe_disk_values(self):
        for index,value in [(0,b'[]'),(0,b'[{"Endpoints":{"docker":{"Host":"tcp://remote"}}}]'),
                            (1,b'[]'),(2,b'[]'),(3,b''),(3,b'header\n-1 3\n'),(3,b'header\n10 11\n'),
                            (3,b'header\n9007199254740992 1\n'),(3,b'header\n0 0\n'),(3,b'header\nNaN 1\n')]:
            responses=self.responses();responses[index]=value
            with patch.object(m,'docker',side_effect=responses),self.assertRaises(m.ObserverFailure):m.measure(self.config)
        with patch.object(m,'docker',side_effect=[b'not-json']),self.assertRaises(ValueError):m.measure(self.config)

    def test_subprocess_budget_is_remaining_total_and_secret_or_docker_overrides_are_not_forwarded(self):
        with patch.object(m.time,'monotonic',side_effect=[10,10.5]),patch.object(m.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=b'ok')) as run:
            self.assertEqual(b'ok',m.docker(self.config,12,'version'))
        self.assertEqual(2,run.call_args.kwargs['timeout'])
        self.assertEqual({'HOME','PATH'},set(run.call_args.kwargs['env']))
        self.assertEqual([self.config['dockerBinary'],'--context','desktop-linux','version'],run.call_args.args[0])
        with patch.object(m.time,'monotonic',return_value=12),patch.object(m.subprocess,'run') as run,self.assertRaises(m.ObserverFailure):m.docker(self.config,12,'version')
        run.assert_not_called()
        for failure in [OSError('private'),subprocess.TimeoutExpired('private',1)]:
            with patch.object(m.subprocess,'run',side_effect=failure),self.assertRaisesRegex(m.ObserverFailure,'^MEASUREMENT_UNAVAILABLE$'):m.docker(self.config,m.time.monotonic()+2,'version')
        for result in [SimpleNamespace(returncode=1,stdout=b'secret'),SimpleNamespace(returncode=0,stdout=b'x'*131073)]:
            with patch.object(m.subprocess,'run',return_value=result),self.assertRaises(m.ObserverFailure):m.docker(self.config,m.time.monotonic()+2,'version')

    def test_forward_uses_strict_ca_fixed_https_bearer_and_only_measurement_fields(self):
        connection=Mock();connection.getresponse.return_value=SimpleNamespace(status=204,read=lambda limit:b'')
        with patch.object(m.ssl,'create_default_context',return_value='strict-ca') as tls,patch.object(m.http.client,'HTTPSConnection',return_value=connection) as https:
            m.forward(self.config,self.observation())
        tls.assert_called_once_with(cafile=str(self.directory/'gateway-pki/ca.crt'))
        https.assert_called_once_with('localhost',18443,context='strict-ca',timeout=1)
        call=connection.request.call_args
        self.assertEqual(('POST','/api/v1/dev-logs/storage-observation'),call.args)
        self.assertEqual(self.observation(),json.loads(call.kwargs['body']))
        self.assertEqual('Bearer '+'s'*64,call.kwargs['headers']['Authorization']);connection.close.assert_called_once()
        for status,body in [(401,b''),(503,b''),(204,b'unexpected')]:
            connection.reset_mock();connection.getresponse.return_value=SimpleNamespace(status=status,read=lambda limit:body)
            with patch.object(m.ssl,'create_default_context'),patch.object(m.http.client,'HTTPSConnection',return_value=connection),self.assertRaises(m.ObserverFailure):m.forward(self.config,self.observation())
            connection.close.assert_called_once()
        self.assertEqual((0.0,0.0),signal.getitimer(signal.ITIMER_REAL))

    def test_whole_http_timer_expires_once_and_restores_handler_after_success_and_failure(self):
        before=signal.getsignal(signal.SIGALRM)
        with m.http_budget():
            handler=signal.getsignal(signal.SIGALRM)
            with self.assertRaisesRegex(m.ObserverFailure,'^HTTP_UNAVAILABLE$'):handler(signal.SIGALRM,None)
        self.assertEqual(before,signal.getsignal(signal.SIGALRM))
        with patch.object(m.signal,'getitimer',return_value=(1.0,0.0)),self.assertRaises(m.ObserverFailure):
            with m.http_budget():self.fail('Existing process timer cannot be overwritten')

    def test_failed_measurement_never_forwards_or_keeps_stale_healthy_values_and_recovery_is_visible(self):
        with patch.object(m,'measure',return_value=self.observation()),patch.object(m,'forward'):
            first=m.cycle(self.config,1)
        self.assertEqual('ACCEPTED',first['result'])
        with patch.object(m,'measure',side_effect=RuntimeError('secret')),patch.object(m,'forward') as forward:
            failed=m.cycle(self.config,2)
        forward.assert_not_called();self.assertEqual('FAILED',failed['result']);self.assertNotIn('freeBytes',failed)
        with patch.object(m,'measure',return_value=self.observation()),patch.object(m,'forward',side_effect=ssl.SSLError('secret')):
            failed=m.cycle(self.config,3)
        self.assertEqual('HTTP_UNAVAILABLE',failed['code']);self.assertEqual('FAILED',failed['result'])
        with patch.object(m,'measure',return_value=self.observation()),patch.object(m,'forward'):
            recovered=m.cycle(self.config,4)
        self.assertEqual('ACCEPTED',recovered['result'])
        saved=m.private(self.directory/'observer/health.json',4096).decode()
        self.assertEqual(4,json.loads(saved)['sequence']);self.assertNotIn('secret',saved);self.assertNotIn('s'*64,saved)
        with patch.object(m,'measure',return_value={**self.observation(),'freeBytes':0}),patch.object(m,'forward'):
            degraded=m.cycle(self.config,5)
        self.assertEqual('LOW_SPACE',degraded['code']);self.assertEqual('DEGRADED',degraded['result'])

    def test_health_rejects_unsafe_replacement_and_scheduler_never_catches_up_or_overlaps(self):
        target=self.directory/'observer/health.json';target.symlink_to(self.path)
        with self.assertRaises(m.ObserverFailure):m.health(self.config,{'sequence':1})
        target.unlink();(self.directory/'observer').chmod(0o755)
        with self.assertRaises(m.ObserverFailure):m.health(self.config,{'sequence':1})
        (self.directory/'observer').chmod(0o700)
        with patch.object(m,'cycle') as cycle,patch.object(m.time,'monotonic',side_effect=[10,13,20,20.5]),patch.object(m.time,'sleep',side_effect=[None,KeyboardInterrupt]) as sleep:
            with self.assertRaises(KeyboardInterrupt):m.run(self.config)
        self.assertEqual([0,1.5],[call.args[0] for call in sleep.call_args_list])
        self.assertEqual([1,2],[call.args[1] for call in cycle.call_args_list])
        for args in [[],['--other','private'],['--config','/missing']]:
            with patch.object(m.sys,'stderr',new_callable=io.StringIO) as error:
                self.assertEqual(1,m.main(args));self.assertEqual('OBSERVER_UNAVAILABLE\n',error.getvalue())
        with patch.object(m,'configuration',return_value=self.config),patch.object(m,'run',side_effect=OSError('secret')),patch.object(m.sys,'stderr',new_callable=io.StringIO):
            self.assertEqual(1,m.main(['--config',str(self.path)]))


if __name__=='__main__':unittest.main()
