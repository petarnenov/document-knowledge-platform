"""Private launcher/ownership tests with explicit synthetic Java, process and HTTP fixtures."""
import contextlib
import copy
import importlib.util
import io
import http.server
import json
import os
from pathlib import Path
import socket
import sys
import threading
import urllib.error
import unittest
import uuid
from unittest.mock import Mock,patch
import test_dev_logging as fixture

spec=importlib.util.spec_from_file_location('native_bff',Path(__file__).with_name('log-native-bff.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class NativeBffTest(unittest.TestCase):
    def setUp(self):
        self.fixture=fixture.LoggingRuntimeTests();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.shared=self.fixture.directory.resolve(strict=True);state=self.fixture.ready();state['directory']=str(self.shared)
        fixture.m.save(self.shared,state)
        self.parent=self.shared.parent/'native';self.parent.mkdir(mode=0o700)
        self.directory=self.parent/'process';self.settings=self.parent/'settings.json'
        self.installation=self.parent/'installation';self.installation.mkdir(mode=0o700)
        self.installed={'component':'browser-bff','buildId':str(uuid.uuid4()),'manifestSha256':'a'*64}
        self.private(self.installation/'install.json',self.installed)
        self.java_home=self.parent/'jdk';(self.java_home/'bin').mkdir(parents=True)
        self.java=self.java_home/'bin/java'
        self.java.write_text('#!/bin/sh\nprintf \'openjdk version "25.0.5"\\nTemurin-25.0.5+8\\n\' >&2\n')
        self.java.chmod(0o700)
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));self.port=sock.getsockname()[1]
        self.producer=self.parent/'producer.json';self.browser=self.parent/'browser.json';self.portal=self.parent/'portal.json'
        self.private(self.portal,{'fixture':'explicit synthetic portal manifest; codec tested separately'})
        self.producer_value={'schemaVersion':1,'manifestSha256':'a'*64,'runId':str(uuid.uuid4()),'instanceId':str(uuid.uuid4()),
            'endpoint':'https://localhost:18087/api/v1/dev-logs','producerToken':'p'*43,'caFile':str(self.shared/'gateway-pki/ca.crt'),
            'spoolDirectory':str(self.directory/'producer-spool')}
        self.browser_value={'schemaVersion':1,'endpoint':self.producer_value['endpoint'],'parentProducerId':str(uuid.uuid4()),
            'delegatorToken':'d'*48,'caFile':self.producer_value['caFile'],
            'spoolDirectory':str(self.directory/'browser-spool'),
            'portalManifests':[{'file':str(self.portal),'sha256':m.digest(self.portal.read_bytes())}]}
        self.private(self.producer,self.producer_value);self.private(self.browser,self.browser_value)
        self.private(self.shared/'gateway-pki/runtime.env','OIDC_CLIENT_SECRET='+'c'*48+'\nDKP_TRUSTSTORE_PASSWORD='+'t'*48+'\nUNRELATED_SECRET=must-not-inherit\n')
        self.private(self.shared/'gateway-pki/truststore.p12','synthetic unit truststore')
        self.value={'schemaVersion':1,'installation':str(self.installation),'javaHome':str(self.java_home),'port':self.port,
            'sharedDirectory':str(self.shared),'producerConfig':str(self.producer),'browserConfig':str(self.browser)}
        self.private(self.settings,self.value)
        self.app=Mock(pid=45678);self.app.poll.return_value=None
        self.real_healthy=m.healthy;self.real_spawn=m.spawn
        original_access=os.access;original_run=m.subprocess.run
        def fixture_access(path,mode,*args,**kwargs):
            return True if Path(path)==self.java and mode==os.X_OK else original_access(path,mode,*args,**kwargs)
        def fixture_java(args,**kwargs):
            # The pinned runner deliberately mounts /tmp noexec. This Java is synthetic,
            # not a compiler proof; never weaken that mount or execute the fixture there.
            if args==[str(self.java),'-version']:
                return m.subprocess.CompletedProcess(args,0,stdout=b'',stderr=b'openjdk version "25.0.5"\nTemurin-25.0.5+8\n')
            return original_run(args,**kwargs)
        self.start_time='Tue Nov 3 09:00:00 2026';self.listener='127.0.0.1:'+str(self.port)
        for replacement in (patch.object(m.i,'verify_installed',return_value=self.installed),patch.object(m.r,'verify_live'),
                patch.object(m.os,'access',side_effect=fixture_access),patch.object(m.subprocess,'run',side_effect=fixture_java),
                patch.object(m,'spawn',return_value=self.app),patch.object(m.r,'command',side_effect=self.command),
                patch.object(m,'healthy')):
            replacement.start();self.addCleanup(replacement.stop)
        self.argv=m.inputs(self.settings,self.directory)['command']

    def private(self,path,value):
        fixture.m.write_private(path,json.dumps(value) if isinstance(value,dict) else value,replace=path.exists())

    def command(self,args,**kwargs):
        if args[0]=='ps':return (str(os.getuid())+' '+self.start_time+' '+' '.join(self.argv)+'\n').encode()
        if args[0]=='lsof':return ('p45678\nn'+self.listener+'\n').encode()
        raise AssertionError('Unexpected fixture command')

    def test_private_exact_launch_and_repeated_start_never_duplicates_or_exports_secrets(self):
        result=m.start(self.settings,self.directory)
        self.assertEqual('PASS',result['result']);self.assertEqual(self.port,result['port'])
        record=json.loads((self.directory/'state.json').read_text());self.assertEqual('READY',record['stage'])
        self.assertEqual(result,m.start(self.settings,self.directory));self.assertEqual(1,m.spawn.call_count)
        args,kwargs=m.spawn.call_args
        self.assertEqual(self.argv,args[0]);self.assertTrue(kwargs['start_new_session']);self.assertTrue(kwargs['close_fds'])
        self.assertEqual('true',kwargs['env']['OIDC_ENABLED']);self.assertNotIn('UNRELATED_SECRET',kwargs['env'])
        self.assertNotIn('JAVA_TOOL_OPTIONS',kwargs['env']);self.assertNotIn('HTTP_PROXY',kwargs['env'])
        self.assertIn('-Dmicronaut.server.host=127.0.0.1',(self.directory/'java.options').read_text())
        self.assertNotIn('c'*48,json.dumps(record));self.assertNotIn('t'*48,json.dumps(record))
        for path in self.directory.iterdir():self.assertEqual(0,path.stat().st_mode & 0o077)

    def test_stale_pid_identity_command_listener_and_file_drift_are_rejected(self):
        m.start(self.settings,self.directory);original_time=self.start_time
        self.start_time='Wed Nov 4 09:00:00 2026'
        with self.assertRaises(ValueError):m.status(self.settings,self.directory)
        self.start_time=original_time;original_argv=self.argv;self.argv=['foreign process']
        with self.assertRaises(ValueError):m.status(self.settings,self.directory)
        self.argv=original_argv;self.listener='*:8080'
        with self.assertRaises(ValueError):m.status(self.settings,self.directory)
        self.listener='127.0.0.1:'+str(self.port)
        (self.directory/'java.options').write_text('changed options')
        with self.assertRaises(ValueError):m.status(self.settings,self.directory)
        self.assertEqual(1,m.spawn.call_count)

    def test_browser_spool_is_explicit_private_first_use_and_missing_storage_is_not_recreated(self):
        m.start(self.settings,self.directory);spool=self.directory/'browser-spool'
        self.assertEqual(0o700,spool.stat().st_mode & 0o7777)
        spool.rmdir()
        with self.assertRaises(ValueError):m.start(self.settings,self.directory)
        self.assertFalse(spool.exists());self.assertEqual(1,m.spawn.call_count)
        foreign=self.parent/'foreign-spool';foreign.mkdir(mode=0o700);spool.symlink_to(foreign,target_is_directory=True)
        with self.assertRaises(ValueError):m.status(self.settings,self.directory)
        self.assertTrue(spool.is_symlink());spool.unlink();spool.mkdir(mode=0o755)
        with self.assertRaises(ValueError):m.status(self.settings,self.directory)
        self.assertEqual(1,m.spawn.call_count)

    def test_exited_or_unready_process_retains_owned_start_evidence_without_killing_anything(self):
        self.app.poll.return_value=1
        with self.assertRaises(ValueError):m.start(self.settings,self.directory)
        record=json.loads((self.directory/'state.json').read_text());self.assertEqual(45678,record['pid'])
        self.assertEqual('STARTING',record['stage']);self.app.terminate.assert_not_called();self.app.kill.assert_not_called()
        with self.assertRaises(ValueError):
            with patch.object(m,'listening',side_effect=ValueError('not ready')),patch.object(m.time,'monotonic',side_effect=[0,30]):
                fresh=self.parent/'second';self.producer_value['spoolDirectory']=str(fresh/'producer-spool');self.private(self.producer,self.producer_value)
                self.browser_value['spoolDirectory']=str(fresh/'browser-spool');self.private(self.browser,self.browser_value)
                self.argv=m.inputs(self.settings,fresh)['command'];self.app.poll.return_value=None;m.start(self.settings,fresh)
        self.assertEqual('STARTING',json.loads((self.parent/'second/state.json').read_text())['stage'])

    def test_bad_configuration_roles_endpoints_ports_and_manifest_bindings_fail_before_process(self):
        original=copy.deepcopy(self.value)
        for case in ({'port':True},{'port':18081},{'port':18087},{'port':80},{'extra':'unknown'}):
            self.private(self.settings,{**original,**case})
            with self.assertRaises(ValueError):m.start(self.settings,self.directory)
        self.private(self.settings,original)
        for path,original_config,cases in ((self.producer,self.producer_value,[{'endpoint':'http://foreign.invalid'},
                {'caFile':'/foreign'},{'manifestSha256':'b'*64},{'spoolDirectory':'/foreign'},{'producerToken':'short'}]),
                (self.browser,self.browser_value,[{'delegatorToken':'p'*43},{'portalManifests':[]},{'spoolDirectory':'/foreign'},
                {'portalManifests':[{'file':str(self.portal),'sha256':'wrong'}]}])):
            for case in cases:
                self.private(path,{**original_config,**case})
                with self.assertRaises(ValueError):m.start(self.settings,self.directory)
            self.private(path,original_config)
        m.spawn.assert_not_called();self.assertFalse(self.directory.exists())

    def test_wrong_java_version_or_component_and_nonprivate_paths_refuse(self):
        self.installed['component']='knowledge-search'
        with self.assertRaises(ValueError):m.inputs(self.settings,self.directory)
        self.installed['component']='browser-bff'
        with patch.object(m.subprocess,'run',return_value=Mock(returncode=0,stderr=b'java 17')):
            with self.assertRaises(ValueError):m.inputs(self.settings,self.directory)
        self.settings.chmod(0o644)
        with self.assertRaises(ValueError):m.inputs(self.settings,self.directory)
        self.settings.chmod(0o600);self.parent.chmod(0o755)
        with self.assertRaises(ValueError):m.start(self.settings,self.directory)
        self.parent.chmod(0o700)
        alias=self.parent/'alias.json';alias.symlink_to(self.settings)
        with self.assertRaises(ValueError):m.inputs(alias,self.directory)

    def test_owned_listener_and_ps_parser_reject_other_uid_extra_listeners_and_invalid_pid(self):
        with self.assertRaises(ValueError):m.process_identity(0)
        with patch.object(m.r,'command',return_value=b'0 Tue Nov 3 09:00:00 2026 java'):
            with self.assertRaises(ValueError):m.process_identity(45678)
        with patch.object(m.r,'command',return_value=b'p45678\nn127.0.0.1:1234\nn*:8080\n'):
            with self.assertRaises(ValueError):m.listening(45678,1234)

    def test_cli_fixed_failure_does_not_expose_private_config_or_stop_a_process(self):
        argv=['log-native-bff.py','start','--settings',str(self.settings),'--directory',str(self.directory)]
        with patch.object(sys,'argv',argv),patch.object(m.r,'local_daemon'),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0,m.main())
        self.assertEqual('PASS',json.loads(output.getvalue())['result'])
        argv[1]='status'
        with patch.object(sys,'argv',argv),patch.object(m.r,'local_daemon'),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0,m.main())
        self.private(self.settings,{'secret':'must-not-escape'})
        with patch.object(sys,'argv',argv),patch.object(m.r,'local_daemon'),contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1,m.main())
        self.assertEqual({'result':'FAIL','code':'LOG_NATIVE_BFF_UNAVAILABLE','processCleanup':'NOT_STOPPED'},json.loads(output.getvalue()))

    def test_real_owned_health_http_uses_fixed_path_no_proxy_and_never_follows_redirects(self):
        requests=[]
        class Handler(http.server.BaseHTTPRequestHandler):
            status=200
            def log_message(self,*args):pass
            def do_GET(self):
                requests.append(self.path);self.send_response(self.status)
                if self.status==302:self.send_header('Location','/unexpected-redirect')
                self.end_headers()
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,kwargs={'poll_interval':.05},daemon=True);thread.start()
        try:
            with patch.dict(os.environ,{'HTTP_PROXY':'http://127.0.0.1:1','NO_PROXY':''}):self.real_healthy(server.server_port)
            Handler.status=302
            with self.assertRaises(urllib.error.HTTPError):self.real_healthy(server.server_port)
            Handler.status=204
            with self.assertRaises(ValueError):self.real_healthy(server.server_port)
            self.assertEqual(['/health/liveness']*3,requests)
        finally:server.shutdown();thread.join(timeout=2);server.server_close()
        app=self.real_spawn(['/bin/sh','-c','exit 0'],stdin=m.subprocess.DEVNULL,stdout=m.subprocess.DEVNULL,
            stderr=m.subprocess.DEVNULL,start_new_session=True,close_fds=True)
        self.assertEqual(0,app.wait(timeout=3))


if __name__ == '__main__':unittest.main()
