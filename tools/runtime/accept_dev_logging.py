"""Explicit real disposable Docker acceptance; never run against the shared project."""
import tempfile
import http.client
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import socket
import ssl
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('accepted_logging',Path(__file__).with_name('dev-logging.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


UPSTREAM_FIXTURE = r"""
import http.server,json,ssl,sys
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  body=json.dumps({name:self.headers.get(name) for name in ('Host','Forwarded','X-Forwarded-Host','X-Forwarded-Proto','X-Forwarded-Port')}).encode()
  self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
server=http.server.HTTPServer(('0.0.0.0',int(sys.argv[1])),Handler)
if int(sys.argv[1])==18444:
 context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain('/fixture/idp.crt','/fixture/idp.key');server.socket=context.wrap_socket(server.socket,server_side=True)
server.serve_forever()
"""


class SharedLoggingAcceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        m.local_daemon()
        cls.directory=Path(os.environ['DKP_LOG_ACCEPTANCE_DIRECTORY']).absolute()
        cls.project='dkp-dev-logging-acceptance-'+uuid.uuid4().hex[:12]
        with socket.socket() as portal, socket.socket() as idp:
            portal.bind(('127.0.0.1',0));idp.bind(('127.0.0.1',0))
            cls.port=portal.getsockname()[1];cls.idp_port=idp.getsockname()[1]
        cls.fixture_ids=[]
        cls.state=m.initialize(cls.directory,cls.project,cls.port,cls.idp_port)
        cls.addClassCleanup(cls.cleanup_owned)
        original_run=m.subprocess.run
        def diagnostic_run(*args,**kwargs):
            result=original_run(*args,**kwargs)
            if result.returncode:
                # Private evidence only. Never print command/config/SQL output publicly.
                m.write_private(cls.directory/'failed-command.private.json',json.dumps({'exitCode':result.returncode,'stdout':(result.stdout or b'').decode(errors='replace'),'stderr':(result.stderr or b'').decode(errors='replace')}),replace=(cls.directory/'failed-command.private.json').exists())
            return result
        diagnostic=patch.object(m.subprocess,'run',side_effect=diagnostic_run)
        diagnostic.start();cls.addClassCleanup(diagnostic.stop)
        cls.result=m.start(cls.state,cls.directory)

    @classmethod
    def capture_container(cls, item, label):
        m.owned(item,cls.state)
        result=m.subprocess.run(['docker','logs','--tail','100',item['Id']],capture_output=True,timeout=15)
        m.require(result.returncode==0,'LOG_ACCEPTANCE_DIAGNOSTIC_FAILED')
        m.write_private(cls.directory/(label+'.private.log'),result.stdout+result.stderr)

    @classmethod
    def capture_private(cls):
        diagnostics=[]
        for identity in m.docker('ps','--all','--filter','label=com.docker.compose.project='+cls.project,'--format','{{.ID}}').decode().split():
            item=m.inspect('container',identity);m.owned(item,cls.state)
            service=item['Config']['Labels'].get('com.docker.compose.service')
            diagnostics.append({'service':service,'id':item['Id'],'state':item['State'],'restartCount':item['RestartCount']})
            cls.capture_container(item,service+'-terminal')
        m.write_private(cls.directory/'terminal-states.private.json',json.dumps(diagnostics))

    @classmethod
    def cleanup_owned(cls):
        m.require(cls.project.startswith('dkp-dev-logging-acceptance-'))
        failed=False
        try:
            try:cls.capture_private()
            except Exception:failed=True
        finally:
            cls.remove_owned()
        m.require(not failed,'LOG_ACCEPTANCE_DIAGNOSTIC_FAILED')

    @classmethod
    def remove_owned(cls):
        # Explicit acceptance-only teardown; this command is intentionally absent from the operator CLI.
        m.require(cls.project.startswith('dkp-dev-logging-acceptance-'))
        for identity in cls.fixture_ids:
            m.owned(m.inspect('container',identity),cls.state);m.docker('rm','--force','--volumes',identity)
        for identity in m.docker('ps','--all','--filter','label=com.docker.compose.project='+cls.project,'--format','{{.ID}}').decode().split():
            m.owned(m.inspect('container',identity),cls.state)
            m.docker('rm','--force','--volumes',identity)
        for suffix in ('postgres-data','postgres-secrets'):
            name=cls.project+'-'+suffix
            if m.exists('volume',name):
                m.owned(m.inspect('volume',name),cls.state);m.docker('volume','rm',name)
        for suffix in ('data','edge'):
            name=cls.project+'-'+suffix
            if m.exists('network',name):
                m.owned(m.inspect('network',name),cls.state);m.docker('network','rm',name)
        for name,identity in [('image','imageId'),('gatewayImage','gatewayImageId'),('viewerImage','viewerImageId'),('browserImage','browserImageId')]:
            if name in cls.state:
                item=m.inspect('image',cls.state[name]);m.owned(item,cls.state)
                m.require(item['Id']==cls.state[identity]);m.docker('image','rm',cls.state[name])
        m.write_private(cls.directory/'acceptance-cleanup.json',json.dumps({'project':cls.project,'result':'REMOVED','sharedProjectTouched':False}))

    def query(self,token=True):
        now=dt.datetime.now(dt.timezone.utc)
        query=urllib.parse.urlencode({'from':(now-dt.timedelta(minutes=5)).isoformat(),'to':(now+dt.timedelta(minutes=1)).isoformat(),'buildId':'11111111-1111-4111-8111-111111111111'})
        headers={'Authorization':'Bearer '+m.private(self.directory/'operator/read-token').decode()} if token else {}
        request=urllib.request.Request('https://localhost:'+str(self.port)+'/api/v1/dev-logs/events?'+query,headers=headers)
        with urllib.request.urlopen(request,timeout=5,context=ssl.create_default_context(cafile=self.directory/'gateway-pki/ca.crt')) as response:return json.load(response)

    def recovered(self):
        for attempt in range(40):
            try:
                result=self.query()
                if result['events']:return result
            except (OSError,ValueError):pass
            time.sleep(.5)
        self.fail('Owned query did not recover and return retained fixture')

    def upstream(self,name,port):
        pins=json.loads((m.ROOT/'tools/ci/python-runtime-pins.json').read_text())
        args=['create','--platform','linux/amd64','--network',self.project+'-edge','--network-alias',name,
              '--user',str(self.state['uid'])+':'+str(self.state['gid']),'--read-only','--tmpfs','/tmp',
              '--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','128m','--pids-limit','32']
        for key,value in m.labels(self.state).items():args+=['--label',key+'='+value]
        if port==18444:
            for leaf in ('idp.crt','idp.key'):args+=['--volume',str(self.directory/'gateway-pki'/leaf)+':/fixture/'+leaf+':ro']
        identity=m.docker(*args,pins['pythonImage'],'python','-c',UPSTREAM_FIXTURE,str(port)).decode().strip()
        self.fixture_ids.append(identity);m.docker('start',identity)

    def gateway_routes(self):
        context=ssl.create_default_context(cafile=self.directory/'gateway-pki/ca.crt')
        base='https://localhost:'+str(self.port)
        request=urllib.request.Request(base+'/api/v1/session',headers={'X-Forwarded-Host':'evil.invalid','Forwarded':'host=evil.invalid'})
        with self.assertRaises(urllib.error.HTTPError) as unavailable:urllib.request.urlopen(request,context=context,timeout=10)
        self.assertEqual(502,unavailable.exception.code)
        self.upstream('browser-bff',8080);self.upstream('keycloak',18444)
        for attempt in range(40):
            try:
                with urllib.request.urlopen(request,context=context,timeout=10) as response:headers=json.load(response)
                break
            except urllib.error.HTTPError as failure:
                if failure.code!=502 or attempt==39:raise
                time.sleep(.5)
        self.assertEqual('localhost:'+str(self.port),headers['Host'])
        self.assertEqual('localhost:'+str(self.port),headers['X-Forwarded-Host'])
        self.assertEqual('https',headers['X-Forwarded-Proto']);self.assertFalse(headers['Forwarded'])
        unknown=urllib.request.Request(base+'/',headers={'Host':'evil.invalid'})
        with self.assertRaises(urllib.error.HTTPError) as denied:urllib.request.urlopen(unknown,context=context,timeout=5)
        self.assertEqual(400,denied.exception.code)
        # Keep the existing public IdP authority while verifying its private TLS upstream.
        for attempt in range(40):
            connection=http.client.HTTPSConnection('127.0.0.1',self.idp_port,context=context,timeout=10)
            connection.request('GET','/realms/dkp',headers={'Host':'idp.localhost:'+str(self.idp_port),'X-Forwarded-Host':'evil.invalid'})
            response=connection.getresponse();body=response.read();connection.close()
            if response.status==200:break
            self.assertEqual(502,response.status);time.sleep(.5)
        self.assertEqual(200,response.status);headers=json.loads(body)
        self.assertEqual('idp.localhost:'+str(self.idp_port),headers['Host'])
        self.assertEqual('idp.localhost:'+str(self.idp_port),headers['X-Forwarded-Host'])
        # Logger prefix still goes to its authenticated real query service, never this fixture.
        with self.assertRaises(urllib.error.HTTPError) as denied:self.query(False)
        self.assertEqual(401,denied.exception.code)

    def viewer_get(self,path='/dev/logs/api/v1/events',headers=None,method='GET'):
        if path == '/dev/logs/api/v1/events':
            now=dt.datetime.now(dt.timezone.utc)
            path+='?'+urllib.parse.urlencode({'from':(now-dt.timedelta(hours=24)).isoformat(),'to':now.isoformat()})
        request=urllib.request.Request('https://localhost:'+str(self.port)+path,headers=headers or {},method=method)
        return urllib.request.urlopen(request,context=ssl.create_default_context(cafile=self.directory/'gateway-pki/ca.crt'),timeout=8)

    def browser_acceptance(self,mode='NORMAL'):
        if 'browserImage' not in self.state:
            import sys
            sys.path.insert(0,str(m.ROOT/'tools/ci'))
            import verify
            with tempfile.TemporaryDirectory(prefix='dkp-viewer-browser-public-') as temporary:
                source=verify.snapshot(m.ROOT,Path(temporary))
                image=self.project+'-browser:'+source['fingerprint'][:16]
                args=['build','--platform','linux/amd64','--tag',image,'--file',str(m.COMPOSE.with_name('identity-e2e.Dockerfile'))]
                for key,value in m.labels(self.state).items():args+=['--label',key+'='+value]
                m.docker(*args,temporary,timeout=900)
            self.state.update(browserImage=image,browserImageId=m.inspect('image',image)['Id'])
            m.save(self.directory,self.state)
        script=r"""
set -euo pipefail
umask 077
install -m 0644 /run/dkp/ca.crt /usr/local/share/ca-certificates/dkp-local.crt
update-ca-certificates >/dev/null 2>&1
export NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/dkp-local.crt
exec runuser -u pwuser -- /bin/bash <<'BROWSER'
set -euo pipefail
for nss in /home/pwuser/.local/share/pki/nssdb /home/pwuser/.pki/nssdb; do
 mkdir -p "$nss"
 certutil -N -d "sql:$nss" --empty-password
 certutil -A -d "sql:$nss" -n dkp-local -t 'C,,' -i /usr/local/share/ca-certificates/dkp-local.crt
done
cd /workspace/apps/admin-portal
exec node e2e-real/log-viewer.runtime.mjs
BROWSER
"""
        args=['create','--platform','linux/amd64','--network',self.project+'-edge','--user','0:0','--cap-drop','ALL',
              '--cap-add','SETUID','--cap-add','SETGID','--security-opt','no-new-privileges:true',
              '--memory','2g','--cpus','2','--pids-limit','256','--shm-size','512m',
              '--volume',str(self.directory/'gateway-pki/ca.crt')+':/run/dkp/ca.crt:ro',
              '--env','DKP_LOG_PORT='+str(self.port),'--env','DKP_LOG_BROWSER_MODE='+mode,'--entrypoint','/bin/bash']
        for key,value in m.labels(self.state).items():args+=['--label',key+'='+value]
        identity=m.docker(*args,self.state['browserImage'],'-c',script).decode().strip();self.fixture_ids.append(identity)
        result=m.subprocess.run(['docker','start','--attach',identity],capture_output=True,timeout=180)
        m.write_private(self.directory/('browser-'+mode.lower()+'.private.log'),result.stdout+result.stderr)
        item=m.inspect('container',identity);m.owned(item,self.state)
        self.assertEqual(0,result.returncode);self.assertEqual(0,item['State']['ExitCode'])
        if mode=='NORMAL':
            target=self.directory/'browser-artifacts';target.mkdir(mode=0o700)
            m.docker('cp',identity+':/workspace/apps/admin-portal/test-results',str(target))

    def viewer_acceptance(self):
        with self.assertRaises(urllib.error.HTTPError) as disabled:self.viewer_get('/dev/logs')
        self.assertEqual(404,disabled.exception.code)
        before={name:m.container(self.state,self.directory,name)['Id'] for name in ('query','postgres','gateway')}
        token=m.private(self.directory/'operator/read-token');volumes=dict(self.state['volumes'])
        pki={name:m.private(self.directory/'gateway-pki'/name) for name in ('portal.crt','portal.key','idp.crt','idp.key','ca.crt')}
        m.start(self.state,self.directory,update=True,target='viewer')
        self.assertEqual(before['query'],m.container(self.state,self.directory,'query')['Id'])
        self.assertEqual(before['postgres'],m.container(self.state,self.directory,'postgres')['Id'])
        self.assertNotEqual(before['gateway'],m.container(self.state,self.directory,'gateway')['Id'])
        self.assertEqual(volumes,self.state['volumes']);self.assertEqual(token,m.private(self.directory/'operator/read-token'))
        self.assertEqual(pki,{name:m.private(self.directory/'gateway-pki'/name) for name in pki})
        # Normal updates must retain the enabled viewer and replace only selected services.
        update_targets={'query':{'query'},'gateway':{'gateway'},'both':{'query','gateway'},'viewer':{'viewer','gateway'}}
        for target,replaced in update_targets.items():
            previous={name:m.container(self.state,self.directory,name)['Id'] for name in ('postgres','query','gateway','viewer')}
            m.start(self.state,self.directory,update=True,target=target)
            for name,identity in previous.items():
                current=m.container(self.state,self.directory,name)['Id']
                if name in replaced:self.assertNotEqual(identity,current,(target,name))
                else:self.assertEqual(identity,current,(target,name))
            self.assertTrue(self.state['viewerEnabled']);self.assertEqual(volumes,self.state['volumes'])
            self.assertEqual(token,m.private(self.directory/'operator/read-token'))
            self.assertEqual(pki,{name:m.private(self.directory/'gateway-pki'/name) for name in pki})
            with self.viewer_get() as response:self.assertEqual(1,len(json.loads(response.read())['events']))
        m.sql(self.state,self.directory,(m.SQL.parents[2]/'test/resources/db/logging-acceptance/viewer-other-build.sql').read_bytes())
        with self.viewer_get() as response:
            body=response.read();self.assertEqual(2,len(json.loads(body)['events']));self.assertNotIn(token,body)
        with self.viewer_get('/dev/logs') as response:
            html=response.read();self.assertIn(b'logs-',html);self.assertNotIn(token,html)
            self.assertIn("frame-ancestors 'none'",response.headers['Content-Security-Policy'])
        for path,headers,method,status in [('/dev/logs',{'Origin':'https://evil.invalid'},'GET',403),('/dev/logs',{'Origin':'null'},'GET',403),('/dev/logs/api/v1/events',{'Origin':'https://evil.invalid'},'GET',403),('/dev/logs/api/v1/events',{},'POST',405),('/dev/logs/unknown',{},'GET',404),('/logs.html',{},'GET',404)]:
            with self.assertRaises(urllib.error.HTTPError) as failure:self.viewer_get(path,headers,method)
            self.assertEqual(status,failure.exception.code)
        with self.assertRaises(urllib.error.HTTPError) as failure:self.query(False)
        self.assertEqual(401,failure.exception.code)
        viewer=m.container(self.state,self.directory,'viewer');m.docker('restart','--time','2',viewer['Id'])
        m.verify_live(self.state,self.directory)
        self.browser_acceptance()
        m.docker('stop','--time','2',viewer['Id'])
        self.browser_acceptance('UNAVAILABLE')
        m.docker('start',viewer['Id'])
        m.verify_live(self.state,self.directory)
        m.write_private(self.directory/'viewer-acceptance.json',json.dumps({'result':'PASS','anonymousRead':True,'mainApiAuthenticationPreserved':True,'queryAndDatabasePreserved':True,'privateTokenAndPkiPreserved':True,'realBrowser':True,'enabledViewerUpdateTargets':list(update_targets)}))

    def test_persistent_owned_query_lifecycle(self):
        self.assertEqual('PASS',self.result['result'])
        before={service:m.container(self.state,self.directory,service)['Id'] for service in ('postgres','query','gateway')}
        volumes=dict(self.state['volumes']);token=m.private(self.directory/'operator/read-token')
        self.assertEqual('PASS',m.start(self.state,self.directory)['result'])
        self.assertEqual(before,{service:m.container(self.state,self.directory,service)['Id'] for service in before})
        self.assertEqual(token,m.private(self.directory/'operator/read-token'))
        with self.assertRaises(urllib.error.HTTPError) as failure:self.query(False)
        self.assertEqual(401,failure.exception.code)
        fixture=(m.ROOT/'services/dev-logging/adapters-out/src/test/resources/db/logging-acceptance/retained-event.sql').read_bytes()
        m.sql(self.state,self.directory,fixture)
        result=self.recovered();self.assertEqual(1,len(result['events']))
        self.assertEqual('11111111-1111-4111-8111-111111111111',result['events'][0]['event']['buildId'])
        # Built portal and its real asset are served by this same verified TLS gateway.
        base='https://localhost:'+str(self.port)
        context=ssl.create_default_context(cafile=self.directory/'gateway-pki/ca.crt')
        with urllib.request.urlopen(base+'/',context=context,timeout=5) as response: html=response.read().decode()
        import re
        asset=re.search(r'src="([^"]+\.js)"',html).group(1)
        with urllib.request.urlopen(base+asset,context=context,timeout=5) as response: self.assertEqual(200,response.status)
        self.gateway_routes()
        # Controlled application and database restarts retain volume, data and credential identity.
        for service in ('query','postgres','gateway'):
            m.docker('restart','--time','5',before[service],timeout=30)
            self.assertEqual('runtime.started',self.recovered()['events'][0]['event']['eventName'])
        self.assertEqual(volumes,self.state['volumes'])
        m.verify_live(self.state,self.directory)
        # Ordinary crash recovery uses the configured restart policy, without harness assistance.
        query=m.container(self.state,self.directory,'query')
        for attempt in range(60):
            started=dt.datetime.fromisoformat(query['State']['StartedAt'].replace('Z','+00:00'))
            if (dt.datetime.now(dt.timezone.utc)-started).total_seconds()>=10:break
            time.sleep(.25)
        # Signal the JVM from inside its owned container: a Docker kill is an operator stop.
        m.docker('exec',query['Id'],'/bin/sh','-c','kill -SEGV 1')
        self.assertEqual('runtime.started',self.recovered()['events'][0]['event']['eventName'])
        recovered_query=m.container(self.state,self.directory,'query')
        self.assertEqual(query['Id'],recovered_query['Id'])
        self.assertGreater(recovered_query['RestartCount'],query['RestartCount'])
        self.capture_container(recovered_query,'query-after-crash')
        m.write_private(self.directory/'crash-recovery.json',json.dumps({'sameContainer':True,'beforeRestartCount':query['RestartCount'],'afterRestartCount':recovered_query['RestartCount'],'retainedData':True}))
        # Explicit update replaces only the query container, never its persistent database.
        m.start(self.state,self.directory,update=True,target='query')
        self.assertEqual(before['gateway'],m.container(self.state,self.directory,'gateway')['Id'])
        self.assertNotEqual(before['query'],m.container(self.state,self.directory,'query')['Id'])
        self.assertEqual(before['postgres'],m.container(self.state,self.directory,'postgres')['Id'])
        self.assertEqual('runtime.started',self.recovered()['events'][0]['event']['eventName'])
        self.assertEqual(volumes,self.state['volumes'])
        current_query=m.container(self.state,self.directory,'query')['Id']
        m.start(self.state,self.directory,update=True,target='gateway')
        self.assertEqual(current_query,m.container(self.state,self.directory,'query')['Id'])
        self.assertEqual(before['postgres'],m.container(self.state,self.directory,'postgres')['Id'])
        self.assertNotEqual(before['gateway'],m.container(self.state,self.directory,'gateway')['Id'])
        self.recovered()
        # Altered columns and indexes are detected despite an unchanged migration version row.
        for alteration,restore in [(b'ALTER TABLE dev_logs.events ADD COLUMN unexpected text;',b'ALTER TABLE dev_logs.events DROP COLUMN unexpected;'),
                                   (b'DROP INDEX dev_logs.log_build_page;',b'CREATE INDEX log_build_page ON dev_logs.events(build_id,received_at,sequence);'),
                                   (b'GRANT UPDATE(component) ON dev_logs.events TO dkp_log_reader;',b'REVOKE UPDATE(component) ON dev_logs.events FROM dkp_log_reader;'),
                                   (b'GRANT CREATE ON SCHEMA public TO PUBLIC;',b'REVOKE CREATE ON SCHEMA public FROM PUBLIC;')]:
            m.sql(self.state,self.directory,alteration)
            with self.assertRaisesRegex(m.RuntimeFailure,'SCHEMA_DRIFT'):m.verify_live(self.state,self.directory)
            with patch.object(m,'build_image',side_effect=AssertionError('must reject before build')):
                with self.assertRaisesRegex(m.RuntimeFailure,'SCHEMA_DRIFT'):m.start(self.state,self.directory,update=True)
            grants=m.private(self.directory/'query/readers.json')
            with self.assertRaisesRegex(m.RuntimeFailure,'SCHEMA_DRIFT'):m.renew(self.state,self.directory)
            self.assertEqual(grants,m.private(self.directory/'query/readers.json'))
            m.sql(self.state,self.directory,restore);m.verify_live(self.state,self.directory)
        m.renew(self.state,self.directory)
        self.assertEqual(token,m.private(self.directory/'operator/read-token'))
        self.assertEqual('runtime.started',self.recovered()['events'][0]['event']['eventName'])
        result=m.verify_live(self.state,self.directory)
        m.write_private(self.directory/'acceptance-result.json',json.dumps({**result,'result':'PASS','normalRestart':True,'crashRecovery':True,'dataRetained':True,'idempotentStart':True,'schemaDriftRejected':True,'queryOnlyUpdate':True,'readerRenewalPreservedIdentity':True}))
        self.viewer_acceptance()
        # Removed storage is not recreated by update, even when an owner label was previously recorded.
        for service in ('query','postgres','gateway','viewer'):
            identity=m.container(self.state,self.directory,service)['Id'];m.docker('rm','--force','--volumes',identity)
        name=self.project+'-postgres-secrets';m.owned(m.inspect('volume',name),self.state);m.docker('volume','rm',name)
        with patch.object(m,'build_image',side_effect=AssertionError('must reject before build')):
            with self.assertRaisesRegex(m.RuntimeFailure,'VOLUME_MISSING'):m.start(self.state,self.directory,update=True)
        self.assertFalse(m.exists('volume',name))
        self.assertEqual(volumes[self.project+'-postgres-data'],m.volume_identity(m.inspect('volume',self.project+'-postgres-data')))


if __name__=='__main__':unittest.main()
