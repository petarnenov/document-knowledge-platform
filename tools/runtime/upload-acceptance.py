#!/usr/bin/env python3
"""Required combined BP03 acceptance; root authority is operator-only, never JVM input.

No provider start/stop/adoption or shared cleanup. prepare mutates only a new native
read-only IAM principal, recorded synthetic exact versions and its own app-only volume.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import tarfile
from urllib.parse import quote, urlsplit
import uuid
import xml.etree.ElementTree as XML

ROOT = Path(__file__).resolve().parents[2]
IMAGE = 'rustfs/rustfs:1.0.4@sha256:ed9210d6411d77ffceb70d94b964a3cb7d225983368bc25bebe01e995293dcc2'
BUCKETS = ('dkp-quarantine', 'dkp-originals')
CASES = ('COPY-01', 'COPY-02', 'COPY-03', 'FENCE-01', 'FENCE-02', 'FENCE-03', 'FENCE-04', 'FENCE-05')
TASK = ':services:document-management:adapters-out:realUploadPersistenceStorageAcceptance'
JAVA_SOURCE = 'services/document-management/adapters-out/src/test/java/org/documentknowledge/documentmanagement/adapters/outbound/upload/RealUploadPersistenceStorageAcceptance.java'
BOUND_SOURCES = ('tools/runtime/upload-acceptance.py', JAVA_SOURCE, 'services/document-management/adapters-out/build.gradle')
FIELDS = {'schemaVersion', 'runId', 'fixture', 'runtime', 'fixtureContainer', 'runtimeProject', 'runtimeRunId', 'workerImage', 'authorityVolume', 'phase', 'files', 'sourceHashes'}


def command(args, *, timeout=60, input=None):
    try:
        result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, input=input)
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError('Owned acceptance command unavailable or timed out; diagnostics suppressed') from None
    if result.returncode:
        raise ValueError('Owned acceptance command failed; diagnostics suppressed')
    return result.stdout


def inspect(kind, name):
    return json.loads(command(['docker', *([] if kind == 'container' else [kind]), 'inspect', name]))[0]


def helper(name):
    spec = importlib.util.spec_from_file_location('acceptance_' + name, ROOT / ('tools/runtime/' + name + '.py'))
    module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def confined(path):
    path = Path(path).absolute();base = ROOT / 'local-data/security'
    if path != path.resolve() or not path.is_relative_to(base) or path == base or any(p.is_symlink() for p in [path, *path.parents]):
        raise ValueError('Ignored owned authority directory required')
    return path


def bounded(path, maximum=65536):
    if (any(p.is_symlink() for p in [path, *path.parents]) or not path.is_file() or path.stat().st_uid != os.getuid()
            or path.stat().st_mode & 0o077 or path.stat().st_size > maximum):
        raise ValueError('Bounded private owned file required')
    return path.read_bytes()


def private(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as stream:
        stream.write(data if isinstance(data, bytes) else data.encode());stream.flush();os.fsync(stream.fileno())


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def owned_directory(path):
    path = confined(path)
    if not path.is_dir() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
        raise ValueError('Private operator directory required')
    return path


def validate_fixture(directory):
    directory = owned_directory(directory);owned_directory(directory / 'server');owned_directory(directory / 'app');state = json.loads(bounded(directory / 'state.json', 16384))
    if set(state) != {'container','network','data','server','authority','image','tenant','document','prefix','endpoint'}:
        raise ValueError('Closed issue104 state required')
    name = state['container'];document = str(uuid.UUID(state['document']))
    if (not re.fullmatch('dkp104-[a-f0-9]{12}', name) or state['image'] != IMAGE
            or state['network'] != name + '-net' or any(state[k] != name + '-' + k for k in ('data','server','authority'))
            or state['tenant'] != 'rustfs-compat-tenant' or state['document'] != document
            or state['prefix'] != hashlib.sha256(state['tenant'].encode()).hexdigest() + '/' + document + '/'):
        raise ValueError('Exact issue104 image and scope binding required')
    endpoint = urlsplit(state['endpoint'])
    if (endpoint.scheme != 'https' or endpoint.hostname != 'localhost' or endpoint.username is not None
            or endpoint.path or endpoint.query or endpoint.fragment or endpoint.port is None):
        raise ValueError('Exact loopback TLS endpoint required')
    container = inspect('container', name);network = inspect('network', state['network'])
    if (container['Config'].get('Labels', {}).get('dkp.issue') != '104' or container['Name'] != '/' + name
            or not container['State']['Running'] or container['Config']['Image'] != IMAGE
            or container['Image'] != inspect('image', IMAGE)['Id']
            or network.get('Labels', {}).get('dkp.issue') != '104' or network['Driver'] != 'bridge'
            or set(container['NetworkSettings']['Networks']) != {state['network']}
            or container['NetworkSettings']['Networks'][state['network']]['NetworkID'] != network['Id']
            or 'rustfs-compat' not in container['NetworkSettings']['Networks'][state['network']].get('Aliases', [])):
        raise ValueError('Actual issue104 provider ownership differs')
    ports = container['NetworkSettings']['Ports']
    if ports.get('9000/tcp') != [{'HostIp':'127.0.0.1','HostPort':str(endpoint.port)}] or any(v for k,v in ports.items() if k != '9000/tcp'):
        raise ValueError('Only the recorded loopback listener is permitted')
    mounts = {m['Destination']:m for m in container['Mounts']}
    if (set(mounts) != {'/data','/run/private'} or mounts['/data'].get('Name') != state['data']
            or mounts['/data'].get('RW') is not True or mounts['/run/private'].get('Name') != state['server']
            or mounts['/run/private'].get('RW') is not False):raise ValueError('Exact issue104 provider mounts required')
    for key in ('data','server','authority'):
        volume = inspect('volume', state[key])
        if volume.get('Labels', {}).get('dkp.issue') != '104' or volume['Driver'] != 'local' or volume.get('Options') not in (None, {}):
            raise ValueError('Only original local issue104 volumes are permitted')
    root_access = bounded(directory / 'server/root-access', 128).decode().strip()
    root_secret = bounded(directory / 'server/root-secret', 128).decode().strip()
    if not re.fullmatch('[a-f0-9]{32}', root_access) or not re.fullmatch('[a-f0-9]{64}', root_secret):raise ValueError('Invalid bootstrap credential binding')
    if bounded(directory / 'root-curl.private', 1024).decode() != 'user = "' + root_access + ':' + root_secret + '"\n':raise ValueError('Root config must bind original server authority')
    if bounded(directory / 'ca.pem') != bounded(directory / 'app/ca.pem'):raise ValueError('Original TLS CA binding required')
    for name, pattern in [('access-key','[a-f0-9]{32}'),('secret-key','[a-f0-9]{64}')]:
        if not re.fullmatch(pattern, bounded(directory / 'app' / name, 128).decode().strip()):raise ValueError('Invalid app authority')
    return state


def readonly_policy(prefix):
    resources = ['arn:aws:s3:::' + bucket + '/' + prefix + '*' for bucket in BUCKETS]
    return {'Version':'2012-10-17','Statement':[
        {'Effect':'Allow','Action':['s3:GetBucketVersioning'],'Resource':['arn:aws:s3:::' + b for b in BUCKETS]},
        {'Effect':'Allow','Action':['s3:GetObject','s3:GetObjectVersion'],'Resource':resources},
        {'Effect':'Deny','Action':['s3:DeleteObjectVersion'],'Resource':resources}]}


def request(work, fixture, state, authority, method, path, body=None):
    # Every URL/signature identifier/body and response stays in private files.
    # curl --disable prevents ambient .curlrc from changing TLS/redirect policy.
    with tempfile.TemporaryDirectory(prefix='.request-', dir=work) as temporary:
        folder = Path(temporary);config,response,headers = [folder / name for name in ('curl.conf','response','headers')]
        private(response,b'');private(headers,b'')
        if authority == 'root': credentials = (fixture / 'root-curl.private').read_text().strip()
        else:
            app = fixture / 'app' if authority == 'app' else work / 'app/denied'
            credentials = 'user = ' + json.dumps(':'.join(bounded(app / name,128).decode().strip() for name in ('access-key','secret-key')))
        lines = ['url = ' + json.dumps(state['endpoint'] + path),'request = ' + json.dumps(method),credentials]
        if method == 'HEAD':lines.append('head = true')
        if body is not None:
            private(folder / 'body',body);lines.append('data-binary = ' + json.dumps('@' + str(folder / 'body')))
        private(config,'\n'.join(lines) + '\n')
        raw = command(['curl','--disable','--silent','--show-error','--max-time','15','--max-filesize','65536','--proto','=https',
                       '--cacert',str(fixture / 'ca.pem'),'--config',str(config),'--aws-sigv4','aws:amz:us-east-1:s3',
                       '--dump-header',str(headers),'--output',str(response),'--write-out','%{http_code}'], timeout=20)
        if not re.fullmatch(b'[0-9]{3}',raw) or response.stat().st_size > 65536 or headers.stat().st_size > 16384:raise ValueError('Bounded provider response required')
        selected = {}
        for line in headers.read_text().splitlines():
            if ':' in line:
                key,value = line.split(':',1);key=key.lower().strip()
                if key == 'x-amz-version-id':
                    if key in selected:raise ValueError('Duplicate exact version response')
                    selected[key] = value.strip()
        return int(raw),response.read_bytes(),selected


def xml(body):
    if b'<!DOCTYPE' in body.upper() or b'<!ENTITY' in body.upper():raise ValueError('Unsafe XML response')
    return XML.fromstring(body)


def matrix(work, fixture, state):
    proof = b'Public synthetic issue151 non-mutating exact-version proof.\n'
    ledger = []
    def record():
        pending = work / 'matrix.pending';private(pending,json.dumps(ledger) + '\n');pending.replace(work / 'matrix-ledger.private')
    for bucket in BUCKETS:
        status,body,_ = request(work,fixture,state,'denied','GET','/' + bucket + '?versioning')
        if status != 200 or not any(item.tag.split('}')[-1] == 'Status' and item.text == 'Enabled' for item in xml(body)):raise ValueError('Readonly principal cannot verify enabled versioning')
        key = state['prefix'] + str(uuid.uuid4());path = '/' + bucket + '/' + key
        ledger.append({'bucket':bucket,'key':key,'version':None,'deleted':False});record()
        status,_,headers = request(work,fixture,state,'app','PUT',path,proof)
        version = headers.get('x-amz-version-id','')
        if status != 200 or not version or version == 'null' or len(version.encode()) > 1024:raise ValueError('Synthetic exact version was not confirmed')
        ledger[-1]['version'] = version;record()
        exact = path + '?versionId=' + quote(version,safe='')
        # No unversioned deletion: only this freshly created exact fixture identity.
        for method in ('HEAD','GET'):
            status,body,_ = request(work,fixture,state,'denied',method,exact)
            if status != 200 or method == 'GET' and body != proof:raise ValueError('Readonly exact object authority rejected')
        status,body,_ = request(work,fixture,state,'denied','DELETE',exact)
        if status != 403 or xml(body).findtext('Code') != 'AccessDenied':raise ValueError('Exact-version delete must return native403AccessDenied')
        status,body,_ = request(work,fixture,state,'denied','GET',exact)
        if status != 200 or body != proof:raise ValueError('Denied deletion mutated exact object')
        status,_,_ = request(work,fixture,state,'app','DELETE',exact)
        if status != 204:raise ValueError('Recorded synthetic exact-version cleanup failed')
        ledger[-1]['deleted'] = True;record()


def authority_labels(record):return {'dkp.issue':'151','dkp.acceptance.run':record['runId'],'dkp.fixture':record['fixtureContainer']}
def owned(item,record):
    labels = item.get('Labels') if 'Labels' in item else item.get('Config',{}).get('Labels')
    if any((labels or {}).get(key) != value for key,value in authority_labels(record).items()):raise ValueError('Foreign acceptance resource refused')


AUTHORITY_FILES = ('denied/access-key','denied/secret-key','inspector/ca.crt','inspector/service.key')
INITIALIZE_SCRIPT = 'test "$(find /authority -mindepth 1 | wc -l)" -eq 6 && '
INITIALIZE_SCRIPT += ' && '.join('test ! -L /authority' + path + ' && test "$(stat -c %u /authority' + path + ')" = 0' for path in ('','/denied','/inspector'))
INITIALIZE_SCRIPT += ' && ' + ' && '.join('test -f /authority/' + path + ' && test ! -L /authority/' + path + ' && test "$(stat -c %u /authority/' + path + ')" = 0' for path in AUTHORITY_FILES)
INITIALIZE_SCRIPT += ' && chmod 0700 /authority /authority/denied /authority/inspector && chmod 0600 ' + ' '.join('/authority/' + path for path in AUTHORITY_FILES)
INITIALIZE_SCRIPT += ' && chown 10001:10001 ' + ' '.join('/authority/' + path for path in AUTHORITY_FILES)
INITIALIZE_SCRIPT += ' && chown 10001:10001 /authority/denied /authority/inspector && chown 10001:10001 /authority'


def authority_archive(work):
    # Docker copy ownership differs across engines. Send only the closed app tree
    # with explicit root metadata, allowing CHOWN-only initialization everywhere.
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream,mode='w',format=tarfile.USTAR_FORMAT) as archive:
        for name in ('denied','inspector'):
            info = tarfile.TarInfo(name);info.type = tarfile.DIRTYPE;info.mode = 0o700
            archive.addfile(info)
        for name in AUTHORITY_FILES:
            data = bounded(work / 'app' / name)
            info = tarfile.TarInfo(name);info.mode = 0o600;info.size = len(data)
            archive.addfile(info,io.BytesIO(data))
    return stream.getvalue()


def volume(work,record):
    name = record['authorityVolume']
    if command(['docker','volume','ls','--format','{{.Name}}','--filter','name=^' + name + '$']).strip():raise ValueError('Refusing existing authority volume')
    label_args = sum((['--label',key + '=' + value] for key,value in authority_labels(record).items()),[])
    command(['docker','volume','create',*label_args,name]);owned(inspect('volume',name),record)
    helper_name = 'dkp151-' + record['runId'] + '-prepare'
    command(['docker','create','--name',helper_name,*label_args,'--network','none','--user','0:0','--read-only',
             '--cap-drop','ALL','--cap-add','CHOWN','--security-opt','no-new-privileges:true','--memory','64m','--pids-limit','16',
             '--mount','type=volume,src=' + name + ',dst=/authority','--entrypoint','/bin/sh',IMAGE,'-c','sleep 120'])
    try:
        owned(inspect('container',helper_name),record);command(['docker','start',helper_name])
        command(['docker','cp','-',helper_name + ':/authority'],input=authority_archive(work))
        command(['docker','exec',helper_name,'/bin/sh','-c',INITIALIZE_SCRIPT])
    finally:
        owned(inspect('container',helper_name),record);command(['docker','rm','--force',helper_name])


def attest_volume(record):
    resource = inspect('volume',record['authorityVolume']);owned(resource,record)
    if resource['Driver'] != 'local' or resource.get('Options') not in (None,{}):raise ValueError('Owned local app authority volume required')
    if command(['docker','ps','-aq','--filter','volume=' + record['authorityVolume']]).strip():raise ValueError('Acceptance authority has another consumer')
    files = AUTHORITY_FILES
    script = 'test "$(find /authority -mindepth 1 | wc -l)" -eq 6 && '
    script += ' && '.join('test ! -L /authority' + p + ' && test "$(stat -c %u:%g:%a /authority' + p + ')" = 10001:10001:700' for p in ('','/denied','/inspector'))
    script += ' && ' + ' && '.join('test -f /authority/' + p + ' && test ! -L /authority/' + p + ' && test "$(stat -c %u:%g:%a /authority/' + p + ')" = 10001:10001:600' for p in files)
    script += ' && sha256sum ' + ' '.join('/authority/' + p for p in files)
    output = command(['docker','run','--rm','--pull=never','--network','none','--read-only','--user','10001:10001',
                      '--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','64m','--pids-limit','16',
                      '--mount','type=volume,src=' + record['authorityVolume'] + ',dst=/authority,readonly',
                      '--entrypoint','/bin/sh',IMAGE,'-c',script]).decode().splitlines()
    expected = [record['files']['app/' + p] + '  /authority/' + p for p in files]
    if output != expected:raise ValueError('Actual app-only authority volume bytes changed')


def save(work,record):
    pending = work / 'state.pending';private(pending,json.dumps(record,separators=(',',':')) + '\n');pending.replace(work / 'state.json')


def prepare(directory, fixture_directory, runtime_directory):
    directory = confined(directory)
    if directory.exists():raise ValueError('Acceptance authority must be a fresh directory; never rotate/adopt')
    fixture = owned_directory(fixture_directory);state = validate_fixture(fixture)
    runtime = helper('upload-runtime');live = runtime.verify(runtime_directory)
    directory.parent.mkdir(parents=True,exist_ok=True);directory.mkdir(mode=0o700)
    run_id = uuid.uuid4().hex
    record = {'schemaVersion':1,'runId':run_id,'fixture':str(fixture),'runtime':live['directory'],'fixtureContainer':state['container'],
              'runtimeProject':live['project'],'runtimeRunId':live['runId'],'workerImage':live['workerImage'],
              'authorityVolume':'dkp151-' + run_id + '-authority','phase':'created','files':{},
              'sourceHashes':{p:sha(ROOT / p) for p in BOUND_SOURCES}}
    save(directory,record)
    for part in ('app','app/denied','app/inspector'):(directory / part).mkdir(mode=0o700)
    access,secret = secrets.token_hex(16),secrets.token_hex(32)
    private(directory / 'app/denied/access-key',access + '\n');private(directory / 'app/denied/secret-key',secret + '\n')
    for name in ('ca.crt','service.key'):
        private(directory / 'app/inspector' / name,bounded(Path(live['directory']) / 'inspector/app' / name))
    policy_name = 'dkp151-readonly-' + run_id
    operations = [('/add-canned-policy?name=' + policy_name,json.dumps(readonly_policy(state['prefix']))),
                  ('/add-user?accessKey=' + access,json.dumps({'secretKey':secret,'status':'enabled'})),
                  ('/set-user-or-group-policy?policyName=' + policy_name + '&userOrGroup=' + access + '&isGroup=false',None)]
    for path,body in operations:
        status,_,_ = request(directory,fixture,state,'root','PUT','/rustfs/admin/v3' + path,body)
        if status != 200:raise ValueError('Native readonly IAM bootstrap failed')
    matrix(directory,fixture,state);volume(directory,record)
    record['files'] = {str(p.relative_to(directory)):sha(p) for p in (directory / 'app').rglob('*') if p.is_file()}
    attest_volume(record);record['phase'] = 'prepared';save(directory,record)
    return record


def read_record(directory):
    directory = owned_directory(directory);record = json.loads(bounded(directory / 'state.json',16384))
    if (set(record) != FIELDS or record['schemaVersion'] != 1 or not re.fullmatch('[a-f0-9]{32}',record['runId'])
            or record['authorityVolume'] != 'dkp151-' + record['runId'] + '-authority' or record['phase'] != 'prepared'
            or set(record['files']) != {'app/denied/access-key','app/denied/secret-key','app/inspector/ca.crt','app/inspector/service.key'}
            or set(record['sourceHashes']) != set(BOUND_SOURCES)):raise ValueError('Complete closed acceptance record required')
    for relative,expected in record['files'].items():
        bounded(directory / relative)
        if sha(directory / relative) != expected:raise ValueError('Acceptance app authority changed')
    for relative,expected in record['sourceHashes'].items():
        if sha(ROOT / relative) != expected:raise ValueError('Reviewed acceptance source changed')
    return record


def load(directory):
    record = read_record(directory)
    state = validate_fixture(Path(record['fixture']));runtime = helper('upload-runtime');live = runtime.verify(record['runtime'])
    if (state['container'] != record['fixtureContainer'] or live['project'] != record['runtimeProject']
            or live['runId'] != record['runtimeRunId'] or live['workerImage'] != record['workerImage']):raise ValueError('Recorded provider identities changed')
    attest_volume(record)
    return record,state,live


def results(output):
    observations = []
    for line in output.splitlines():
        if line.startswith('{"requirement":') or line.startswith('{"issue":'):
            observations.append(json.loads(line))
    expected = [{'requirement':name,'result':'PASS'} for name in CASES] + [{'issue':144,'required':8,'passed':8,'skipped':0}]
    if observations != expected:raise ValueError('Every required combined case must execute exactly once')
    return observations


def attest_image(image,record):
    # No socket, credentials, source/provider mounts or network in this attestor.
    # Immutable image identity alone cannot prove it contains the reviewed tests.
    paths = (JAVA_SOURCE,'services/document-management/adapters-out/build.gradle')
    output = command(['docker','run','--rm','--pull=never','--network','none','--read-only','--user','10001:0',
                      '--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','64m','--pids-limit','16',
                      '--entrypoint','/usr/bin/sha256sum',image,*('/workspace/' + path for path in paths)]).decode().splitlines()
    expected = [record['sourceHashes'][path] + '  /workspace/' + path for path in paths]
    if output != expected:raise ValueError('Actual test image contains stale or missing reviewed source')


def capture(args, log):
    # Retain private local failure evidence without exposing it to CLI/artifacts.
    private(log,b'')
    try:
        with log.open('wb') as stream:
            result = subprocess.run(args,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,timeout=1800)
    except (OSError,subprocess.TimeoutExpired):
        raise ValueError('Combined process unavailable or deadline exceeded; private log retained') from None
    if log.stat().st_size > 16 * 1024 * 1024:raise ValueError('Acceptance diagnostic byte limit exceeded')
    if result.returncode:raise ValueError('Combined process failed; private log retained')
    return log.read_bytes()


def execute(directory, image):
    directory = owned_directory(directory);record = read_record(directory)
    if not re.fullmatch('sha256:[a-f0-9]{64}',image) or inspect('image',image)['Id'] != image:raise ValueError('Explicit actual immutable test image required')
    attest_image(image,record)
    record,state,live = load(directory)
    runtime = helper('upload-runtime');network = record['runtimeProject'] + '_upload-data';net = inspect('network',network)
    runtime.owned(net,live)
    if net.get('Internal') is not True or net.get('Labels',{}).get('com.docker.compose.network') != 'upload-data':raise ValueError('Verified internal provider network required')
    label_args = sum((['--label',key + '=' + value] for key,value in authority_labels(record).items()),[])
    name = 'dkp151-' + record['runId'] + '-run-' + uuid.uuid4().hex[:8]
    env = {'DKP_RUSTFS_COMPAT_ENDPOINT':'https://rustfs-compat:9000','DKP_RUSTFS_COMPAT_AUTHORITY':'/authority',
           'DKP_RUSTFS_COMPAT_PREFIX':state['prefix'],'DKP_RUSTFS_COMPAT_TENANT':state['tenant'],
           'DKP_144_DENIED_STORAGE_AUTHORITY':'/acceptance/denied','DKP_144_PDF':'/workspace/apps/admin-portal/e2e-real/fixtures/synthetic.pdf',
           'DKP_144_SCANNER_HOST':'clamav','DKP_144_SCANNER_PORT':'3310','DKP_144_SCANNER_DEFINITIONS':'/run/dkp/definitions',
           'DKP_144_INSPECTOR_ENDPOINT':'https://structure-inspector:8443/internal/v1/inspect',
           'DKP_144_INSPECTOR_CA':'/acceptance/inspector/ca.crt','DKP_144_INSPECTOR_KEY':'/acceptance/inspector/service.key',
           'DKP_144_INSPECTOR_PROFILE':runtime.PROFILE,'DKP_144_INSPECTOR_IMAGE':live['workerImage'],
           'GRADLE_USER_HOME':'/tmp/dkp-gradle','TESTCONTAINERS_HOST_OVERRIDE':'host.docker.internal','TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE':'/var/run/docker.sock'}
    args = ['docker','create','--name',name,*label_args,'--pull=never','--user','10001:0','--memory','4g','--cpus','4','--pids-limit','512',
            '--cap-drop','ALL','--security-opt','no-new-privileges:true','--network',state['network'],'--add-host','host.docker.internal:host-gateway',
            '--group-add',helper('rustfs-compat-harness').socket_group(image),
            '--mount','type=volume,src=' + state['authority'] + ',dst=/authority',
            '--mount','type=volume,src=' + record['authorityVolume'] + ',dst=/acceptance,readonly',
            '--mount','type=bind,src=' + live['definitions'] + ',dst=/run/dkp/definitions,readonly',
            '--mount','type=volume,src=dkp-bp02-gradle-cache,dst=/tmp/dkp-gradle',
            '--mount','type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock']
    for key,value in env.items():args += ['--env',key + '=' + value]
    args += [image,'./gradlew','--no-daemon','--max-workers=2',TASK]
    command(args)
    try:
        owned(inspect('container',name),record);command(['docker','network','connect',network,name])
        # Capture all Gradle/provider diagnostics privately; emit only closed case results.
        output = capture(['docker','start','--attach',name],directory / ('execution-' + uuid.uuid4().hex + '.private.log'))
        item = inspect('container',name);owned(item,record)
        if item['State']['ExitCode'] != 0:raise ValueError('Combined real-provider acceptance failed')
        observed = results(output.decode('utf8'))
        private(directory / ('results-' + uuid.uuid4().hex + '.json'),json.dumps(observed) + '\n')
        return observed
    finally:
        owned(inspect('container',name),record);command(['docker','rm','--force',name])


def main():
    parser = argparse.ArgumentParser();parser.add_argument('action',choices=['prepare','run']);parser.add_argument('--directory',type=Path,required=True)
    parser.add_argument('--fixture-directory',type=Path);parser.add_argument('--runtime-directory',type=Path);parser.add_argument('--image')
    args = parser.parse_args()
    try:
        if args.action == 'prepare':
            if args.fixture_directory is None or args.runtime_directory is None:raise ValueError('Both owned fixture and ready runtime are required')
            prepare(args.directory,args.fixture_directory,args.runtime_directory);print('PASS151 native readonly403/nonmutating proof and app-only authority prepared')
        else:
            if not args.image:raise ValueError('Immutable acceptance test image required')
            for result in execute(args.directory,args.image):print(json.dumps(result,separators=(',',':')))
    except Exception:
        raise SystemExit('Combined acceptance failed closed; private provider diagnostics suppressed') from None

if __name__ == '__main__':main()
