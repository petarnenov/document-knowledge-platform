"""Owned loopback-only original BFF process. No implicit restart or unrelated process adoption."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import urllib.request
import uuid


def module(name, file):
    spec = importlib.util.spec_from_file_location(name,Path(__file__).with_name(file))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result); return result


i = module('bff_install','log-java-install.py')
r = module('bff_shared_runtime','dev-logging.py')
PROFILE = ['-XX:ActiveProcessorCount=2','-Xmx512m','-Djava.util.concurrent.ForkJoinPool.common.parallelism=2',
    '-Djava.util.concurrent.ForkJoinPool.common.maximumSpares=0','-Djdk.httpclient.connectionPoolSize=32',
    '-Djdk.httpclient.bufsize=16384','-Djdk.httpclient.redirects.retrylimit=5','-Djdk.httpclient.disableRetryConnect=false',
    '-Djdk.httpclient.enableAllMethodRetry=false']


def require(value):
    if not value: raise ValueError('LOG_NATIVE_BFF_INVALID')


def digest(raw): return hashlib.sha256(raw).hexdigest()


def spawn(command, **kwargs): return subprocess.Popen(command, **kwargs)


def inputs(settings_file, directory, *, verify_shared=True):
    settings = i.document(settings_file,32768)
    require(type(settings) is dict and settings.keys() == {'schemaVersion','installation','javaHome','port',
        'sharedDirectory','producerConfig','browserConfig'} and type(settings['schemaVersion']) is int and settings['schemaVersion'] == 1)
    port = settings['port']
    require(type(port) is int and 1024 <= port <= 65535 and port not in {18081,18082,18083,19081,19082,19083})
    installation = Path(settings['installation']); installed = i.verify_installed(installation)
    require(installed['component'] == 'browser-bff')
    shared = Path(settings['sharedDirectory'])
    require(shared.is_absolute() and shared.resolve(strict=True) == shared)
    state = i.document(shared/'state.json',i.m.MAX_MANIFEST)
    r.validate_state(state,shared,state['project'],state['port'],state['idpPort'])
    if verify_shared: r.verify_live(state,shared)
    require(port not in (state['port'],state['idpPort']))
    java_home = Path(settings['javaHome']); java = java_home/'bin/java'
    require(java_home.is_absolute() and java_home.resolve(strict=True) == java_home and os.access(java,os.X_OK))
    java_sha = digest(i.read(java,64*1024*1024))
    version = subprocess.run([str(java),'-version'],capture_output=True,timeout=5)
    require(version.returncode == 0 and b'25.0.5' in version.stderr and b'25.0.5+8' in version.stderr)
    endpoint = 'https://localhost:'+str(state['port'])+'/api/v1/dev-logs'
    ca = shared/'gateway-pki/ca.crt'; references = {str(settings_file):digest(i.read(settings_file,32768,True)),
        str(ca):digest(i.read(ca,16384,True)),str(java):java_sha,
        str(installation/'install.json'):digest(i.read(installation/'install.json',2*i.m.MAX_MANIFEST,True))}
    producer = Path(settings['producerConfig']); browser = Path(settings['browserConfig'])
    for path in (producer,browser): references[str(path)] = digest(i.read(path,32768,True))
    producer_config = i.document(producer,8192); browser_config = i.document(browser,32768)
    require(type(producer_config) is dict and producer_config.keys() == {'schemaVersion','manifestSha256','runId','instanceId',
        'endpoint','producerToken','caFile','spoolDirectory'} and type(producer_config['schemaVersion']) is int and producer_config['schemaVersion'] == 1
        and producer_config['manifestSha256'] == installed['manifestSha256'] and producer_config['spoolDirectory'] == str(directory/'producer-spool'))
    i.m.identifier(producer_config['runId']);i.m.identifier(producer_config['instanceId'])
    require(type(browser_config) is dict and browser_config.keys() == {'schemaVersion','endpoint','parentProducerId','delegatorToken','caFile','portalManifests','spoolDirectory'}
        and type(browser_config['schemaVersion']) is int and browser_config['schemaVersion'] == 1)
    spool = directory/'browser-spool'
    require(browser_config['spoolDirectory'] == str(spool))
    if directory.exists():
        require(spool.is_dir() and not spool.is_symlink() and spool.resolve(strict=True) == spool
            and spool.stat().st_uid == os.getuid() and spool.stat().st_mode & 0o7777 == 0o700)
    i.m.identifier(browser_config['parentProducerId'])
    require(isinstance(producer_config['producerToken'],str) and re.fullmatch('[A-Za-z0-9_-]{32,512}',producer_config['producerToken'])
        and isinstance(browser_config['delegatorToken'],str) and re.fullmatch('[A-Za-z0-9_-]{32,512}',browser_config['delegatorToken'])
        and producer_config['producerToken'] != browser_config['delegatorToken'])
    for config in (producer_config,browser_config): require(config['endpoint'] == endpoint and config['caFile'] == str(ca))
    manifests = browser_config['portalManifests']; require(type(manifests) is list and 1 <= len(manifests) <= 64)
    for value in manifests:
        require(type(value) is dict and value.keys() == {'file','sha256'})
        path = Path(value['file']); raw = i.read(path,i.m.MAX_MANIFEST,True)
        require(digest(raw) == value['sha256'] and str(path) not in references);references[str(path)] = digest(raw)
    security = shared/'gateway-pki'; raw = i.read(security/'runtime.env',32768,True)
    references[str(security/'runtime.env')] = digest(raw)
    values = {}
    for line in raw.decode('utf-8').splitlines():
        name,separator,value = line.partition('=');require(separator and name not in values);values[name] = value
    for key in ('OIDC_CLIENT_SECRET','DKP_TRUSTSTORE_PASSWORD'):require(re.fullmatch('[A-Za-z0-9_-]{32,512}',values[key]))
    trust = security/'truststore.p12';references[str(trust)] = digest(i.read(trust,65536,True))
    origin,issuer = r.security_bootstrap().origins(state['port'],state['idpPort'])
    properties = ['-Djavax.net.ssl.trustStore='+str(trust),'-Djavax.net.ssl.trustStoreType=PKCS12',
        '-Djavax.net.ssl.trustStorePassword='+values['DKP_TRUSTSTORE_PASSWORD'],
        '-Dmicronaut.server.host=127.0.0.1','-Dmicronaut.server.port='+str(port),
        '-Ddkp.logging.producer-config='+str(producer),'-Ddkp.logging.browser-config='+str(browser)]
    require(all('\n' not in value and '\r' not in value for value in properties))
    options = ''.join('"'+value.replace('\\','\\\\').replace('"','\\"')+'"\n' for value in properties).encode()
    environment = {'PATH':'/usr/bin:/bin','JAVA_HOME':str(java_home),'OIDC_ENABLED':'true','OIDC_PROFILE':'standalone',
        'OIDC_CLIENT_ID':'dkp-bff','OIDC_CLIENT_SECRET':values['OIDC_CLIENT_SECRET'],'OIDC_ISSUER':issuer,
        'OIDC_CALLBACK_URI':origin+'/oauth/callback/keycloak','DKP_PORTAL_ORIGIN':origin,
        'DKP_DM_URL':'http://document-management:8080','DKP_KB_URL':'http://knowledge-base:8080','DKP_SEARCH_URL':'http://knowledge-search:8080'}
    command = [str(java),*PROFILE,'@'+str(directory/'java.options'),'-cp',str(installation/'distribution/lib/*'),
        'org.documentknowledge.browserbff.bootstrap.Application']
    return {'installed':installed,'sharedOwnerId':state['ownerId'],'port':port,'references':references,
        'options':options,'environment':environment,'command':command}


def process_identity(pid):
    require(type(pid) is int and pid > 1)
    raw = r.command(['ps','-p',str(pid),'-o','uid=','-o','lstart=','-o','command='],timeout=3)
    require(len(raw) <= 32768)
    fields = raw.decode().strip().split(None,6)
    require(len(fields) == 7 and int(fields[0]) == os.getuid())
    return {'uid':int(fields[0]),'started':' '.join(fields[1:6]),'command':fields[6]}


def listening(pid, port):
    raw = r.command(['lsof','-nP','-a','-p',str(pid),'-iTCP','-sTCP:LISTEN','-Fpn'],timeout=3)
    require(len(raw) <= 32768)
    lines = raw.decode().splitlines()
    require([value for value in lines if value.startswith('p')] == ['p'+str(pid)]
        and [value for value in lines if value.startswith('n')] == ['n127.0.0.1:'+str(port)])


def healthy(port):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs): return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    with opener.open('http://127.0.0.1:'+str(port)+'/health/liveness',timeout=2) as response:
        require(response.status == 200)


def status(settings_file, directory, *, verify_shared=True):
    require(directory.is_absolute() and directory.resolve(strict=True) == directory and directory.is_dir()
        and directory.stat().st_uid == os.getuid() and not directory.stat().st_mode & 0o077)
    record = i.document(directory/'state.json',2*i.m.MAX_MANIFEST)
    current = inputs(settings_file,directory,verify_shared=verify_shared)
    require(record['references'] == current['references'] and record['installation'] == current['installed']
        and record['sharedOwnerId'] == current['sharedOwnerId'] and record['port'] == current['port']
        and record['optionsSha256'] == digest(current['options']) == digest(i.read(directory/'java.options',32768,True)))
    identity = process_identity(record['pid'])
    require(identity == record['processIdentity'] and identity['command'] == ' '.join(current['command']))
    listening(record['pid'],record['port']);healthy(record['port'])
    return {'result':'PASS','stage':'NATIVE_BFF_ACTIVE','pid':record['pid'],'port':record['port'],
        'buildId':record['installation']['buildId'],'sharedStoreDelivery':'NOT_ASSERTED','full457':'OPEN'}


def start(settings_file, directory):
    settings_file = Path(settings_file); directory = Path(directory)
    require(directory.is_absolute() and directory.parent.resolve(strict=True) == directory.parent
        and directory.parent.stat().st_uid == os.getuid() and not directory.parent.stat().st_mode & 0o077)
    if directory.exists(): return status(settings_file,directory)
    require(not directory.is_symlink())
    current = inputs(settings_file,directory)
    with socket.socket() as listener: listener.bind(('127.0.0.1',current['port']))
    directory.mkdir(mode=0o700)
    (directory/'browser-spool').mkdir(mode=0o700)
    r.write_private(directory/'java.options',current['options'])
    record = {'schemaVersion':1,'ownerId':str(uuid.uuid4()),'stage':'PREPARED','references':current['references'],
        'installation':current['installed'],'sharedOwnerId':current['sharedOwnerId'],'port':current['port'],
        'optionsSha256':digest(current['options'])}
    r.write_private(directory/'state.json',json.dumps(record))
    r.write_private(directory/'application.private.log',b'')
    with (directory/'application.private.log').open('ab') as output:
        app = spawn(current['command'],cwd=directory,env=current['environment'],stdin=subprocess.DEVNULL,
            stdout=output,stderr=subprocess.STDOUT,start_new_session=True,close_fds=True)
    record.update(pid=app.pid,stage='STARTING');r.write_private(directory/'state.json',json.dumps(record),replace=True)
    record['processIdentity'] = process_identity(app.pid)
    require(record['processIdentity']['command'] == ' '.join(current['command']))
    r.write_private(directory/'state.json',json.dumps(record),replace=True)
    deadline = time.monotonic()+20
    while True:
        require(app.poll() is None)
        try:
            listening(app.pid,current['port']);healthy(current['port']);break
        except (ValueError,r.RuntimeFailure,OSError):
            require(time.monotonic() < deadline);time.sleep(.1)
    record['stage'] = 'READY';r.write_private(directory/'state.json',json.dumps(record),replace=True)
    return status(settings_file,directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=('start','status'))
    parser.add_argument('--settings',required=True,type=Path);parser.add_argument('--directory',required=True,type=Path)
    args = parser.parse_args()
    try:
        r.local_daemon()
        with r.operator_lock(args.directory):result = start(args.settings,args.directory) if args.action == 'start' else status(args.settings,args.directory)
        print(json.dumps(result));return 0
    except Exception:
        print(json.dumps({'result':'FAIL','code':'LOG_NATIVE_BFF_UNAVAILABLE','processCleanup':'NOT_STOPPED'}));return 1


if __name__ == '__main__':raise SystemExit(main())
