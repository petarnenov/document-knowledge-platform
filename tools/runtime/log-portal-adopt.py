"""Controlled gateway-only adoption of an original portal and an owned native BFF."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import tempfile
import urllib.parse
import urllib.request
import uuid

spec = importlib.util.spec_from_file_location('adopt_native_bff',Path(__file__).with_name('log-native-bff.py'))
n = importlib.util.module_from_spec(spec); spec.loader.exec_module(n)
r = n.r


def digest(raw): return hashlib.sha256(raw).hexdigest()


def native_identity(settings, directory, shared):
    # The caller separately verifies the owned shared runtime/transition. Avoid a
    # recursive live check while its gateway image/config is being replaced.
    value = n.status(settings,directory,verify_shared=False)
    record = n.i.document(directory/'state.json',2*n.i.m.MAX_MANIFEST)
    r.require(record.get('stage') == 'READY', 'LOG_PORTAL_NATIVE_NOT_READY')
    config = n.i.document(settings,32768)
    r.require(config['sharedDirectory'] == str(shared), 'LOG_PORTAL_NATIVE_SHARED_MISMATCH')
    browser = n.i.document(Path(config['browserConfig']),n.i.m.MAX_MANIFEST)
    return {'status':value,'stateSha256':digest(r.private(directory/'state.json')),
            'settingsSha256':digest(r.private(settings)),
            'portalManifests':sorted(row['sha256'] for row in browser['portalManifests'])}


def bridge_probe(state, directory, port):
    r.require(type(port) is int and 1024 <= port <= 65535, 'LOG_PORTAL_NATIVE_PORT_INVALID')
    operation = uuid.uuid4().hex
    image = json.loads((r.ROOT/'tools/ci/python-runtime-pins.json').read_text())['pythonImage']
    r.require(re.fullmatch(r'python:[A-Za-z0-9_.-]+@sha256:[0-9a-f]{64}',image))
    script = ('import urllib.request\n'
        'class NoRedirect(urllib.request.HTTPRedirectHandler):\n'
        ' def redirect_request(self,*args,**kwargs): return None\n'
        'opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())\n'
        'with opener.open("http://host.docker.internal:'+str(port)+'/health/liveness",timeout=3) as response:\n'
        ' assert response.status==200\n'
        'print("PASS")\n')
    args = ['create','--platform','linux/amd64','--name',state['project']+'-bff-probe-'+operation,
        '--network','bridge','--read-only','--user','10001:10001','--cap-drop','ALL','--security-opt','no-new-privileges:true',
        '--memory','128m','--cpus','1','--pids-limit','32']
    for key,value in {**r.labels(state),'dkp.logging.operation':'native-bff-probe','dkp.logging.operation-id':operation}.items():
        args += ['--label',key+'='+value]
    identity = r.docker(*args,image,'python','-I','-c',script).decode().strip()
    r.require(re.fullmatch('[0-9a-f]{64}',identity))
    evidence = directory/'operator'/('native-bff-probe-'+operation+'.json')
    record = {'containerId':identity,'port':port,'result':'UNKNOWN','cleanup':'RETAINED'}
    def owned():
        item = r.inspect('container',identity);r.owned(item,state)
        r.require(item['Id'] == identity and item['Config']['Image'] == image
            and item['Config']['Labels'].get('dkp.logging.operation-id') == operation, 'LOG_PORTAL_PROBE_OWNER_MISMATCH')
        return item
    try:
        owned();r.write_private(evidence,json.dumps(record))
        output = r.docker('start','--attach',identity,timeout=10)
        item = owned()
        r.require(not item['State']['Running'] and item['State']['ExitCode'] == 0 and output == b'PASS\n', 'LOG_PORTAL_NATIVE_UNREACHABLE')
        record['result'] = 'PASS'
    finally:
        item = owned()
        if not item['State']['Running']:
            r.docker('rm',identity);record['cleanup'] = 'REMOVED'
        r.write_private(evidence,json.dumps(record),replace=evidence.exists())
    return {'result':'PASS','evidence':str(evidence)}


def gateway_files(directory):
    return {name:digest(r.private(directory/'gateway'/name)) for name in ('server.conf','log-proxy.conf')}


def served_assets(state, directory):
    selected = r.portal_selection(state,directory); adapter = r.portal_adapter()
    record = adapter.verify_prepared(Path(selected['context']))
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs): return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=directory/'gateway-pki/ca.crt')))
    for name,entry in record['files'].items():
        route = '/dev/logs' if name == 'logs.html' else '/'+urllib.parse.quote(name)
        with opener.open('https://localhost:'+str(state['port'])+route,timeout=5) as response:
            data = response.read(entry['bytes']+1)
            r.require(response.status == 200 and len(data) == entry['bytes'] and digest(data) == entry['sha256'], 'LOG_PORTAL_ASSET_MISMATCH')
    return len(record['files'])


def adopt(state, directory, settings_file):
    adapter = r.portal_adapter()
    config = adapter.document(settings_file,32768)
    r.require(type(config) is dict and config.keys() == {'schemaVersion','portalContext','portalContextSha256','nativeSettings','nativeDirectory'}
        and type(config['schemaVersion']) is int and config['schemaVersion'] == 1, 'LOG_PORTAL_ADOPTION_INPUT_INVALID')
    r.require(state['stage'] == 'READY' and state.get('writerEnabled') and state.get('viewerEnabled')
        and 'browserGrantUpdate' not in state, 'LOG_PORTAL_ADOPTION_REQUIRES_READY_RUNTIME')
    context = Path(config['portalContext']); original = adapter.verify_prepared(context)
    context_sha = digest(r.private(context/'image-context.json'))
    r.require(context_sha == config['portalContextSha256'], 'LOG_PORTAL_ADOPTION_INPUT_INVALID')
    native_settings = Path(config['nativeSettings']); native_directory = Path(config['nativeDirectory'])
    current_native = native_identity(native_settings,native_directory,directory)
    r.require(original['manifestSha256'] in current_native['portalManifests'], 'LOG_PORTAL_MANIFEST_NOT_CONFIGURED')
    input_sha = digest(r.private(settings_file))
    target = directory/'operator/portal-builds'/original['buildId']
    selected = {'context':str(target),'contextSha256':context_sha,'buildId':original['buildId'],
        'manifestSha256':original['manifestSha256'],'artifactSha256':original['artifact']['sha256'],
        'nativeBffPort':current_native['status']['port']}
    pending = state.get('portalAdoption')
    if pending is None:
        r.verify_live(state,directory)
        if state.get('portalRuntime') == selected and state.get('portalLastAdoption') == {'inputSha256':input_sha,'native':current_native}:
            return {'result':'UNCHANGED','stage':'PORTAL_ADOPTED','servedAssets':served_assets(state,directory),'full457':'OPEN'}
        target.parent.mkdir(mode=0o700,exist_ok=True)
        r.require(target.parent.resolve(strict=True) == target.parent and target.parent.stat().st_uid == os.getuid()
            and not target.parent.stat().st_mode & 0o077, 'LOG_PORTAL_ADOPTION_INPUT_INVALID')
        if not target.exists(): shutil.copytree(context,target)
        r.require(adapter.verify_prepared(context) == adapter.verify_prepared(target)
            and digest(r.private(target/'image-context.json')) == context_sha, 'LOG_PORTAL_ADOPTION_INPUT_INVALID')
        candidate = {**state,'portalRuntime':selected};r.portal_selection(candidate,directory)
        with tempfile.TemporaryDirectory(prefix='dkp-portal-config-') as temporary:
            rendered = Path(temporary)
            r.gateway_config(rendered,state['port'],state['idpPort'],viewer=True,writer=True,native_bff_port=selected['nativeBffPort'])
            after_files = gateway_files(rendered)
        pending = {'inputSha256':input_sha,'native':current_native,'selection':selected,'previousSelection':state.get('portalRuntime'),
            'bindings':r.writer_bindings(directory),'volumes':state['volumes'].copy(),
            'preserved':{service:{'id':r.container(state,directory,service)['Id'],'image':r.container(state,directory,service)['Image']}
                for service in ('postgres','query','viewer')},
            'beforeFiles':gateway_files(directory),'afterFiles':after_files,
            'beforeImage':{key:state[key] for key in ('gatewayImage','gatewayImageId','gatewaySourceFingerprint')}}
        state['portalAdoption'] = pending;r.save(directory,state)
    r.require(pending['inputSha256'] == input_sha and pending['native'] == current_native and pending['selection'] == selected
        and state.get('portalRuntime') in (pending['previousSelection'],selected)
        and r.writer_bindings(directory) == pending['bindings'] and state['volumes'] == pending['volumes'], 'LOG_PORTAL_ADOPTION_DRIFT')
    r.check_resources(state);r.assert_schema(state,directory)
    for service,identity in pending['preserved'].items():
        item = r.container(state,directory,service)
        r.require(item['State']['Running'] and {'id':item['Id'],'image':item['Image']} == identity, 'LOG_PORTAL_ADOPTION_DRIFT')
    files = gateway_files(directory)
    r.require(all(files[name] in (pending['beforeFiles'][name],pending['afterFiles'][name]) for name in files), 'LOG_PORTAL_ADOPTION_DRIFT')
    with tempfile.TemporaryDirectory(prefix='dkp-portal-config-check-') as temporary:
        rendered = Path(temporary)
        r.gateway_config(rendered,state['port'],state['idpPort'],viewer=True,writer=True,native_bff_port=selected['nativeBffPort'])
        r.require(gateway_files(rendered) == pending['afterFiles'], 'LOG_PORTAL_TEMPLATE_DRIFT')
    if 'afterImage' not in pending:
        pending['afterImage'] = r.build_image({**state,'portalRuntime':selected},directory,component='gateway');r.save(directory,state)
    gateway = r.container(state,directory,'gateway')
    allowed = (pending['beforeImage'],pending['afterImage'])
    r.require(gateway['Image'] in [image['gatewayImageId'] for image in allowed]
        and {key:state[key] for key in pending['beforeImage']} in allowed, 'LOG_PORTAL_ADOPTION_DRIFT')
    image = r.inspect('image',gateway['Image']);r.owned(image,state)
    expected = next(value for value in allowed if value['gatewayImageId'] == gateway['Image'])
    r.require(image['Config']['Labels'].get('dkp.logging.source') == expected['gatewaySourceFingerprint'], 'LOG_PORTAL_ADOPTION_DRIFT')
    bridge_probe(state,directory,selected['nativeBffPort'])
    r.require(native_identity(native_settings,native_directory,directory) == current_native, 'LOG_PORTAL_ADOPTION_DRIFT')
    state['portalRuntime'] = selected;r.save(directory,state)
    r.start(state,directory,update=True,target='gateway',prepared_images={'gateway':pending['afterImage']})
    r.require(gateway_files(directory) == pending['afterFiles'], 'LOG_PORTAL_TEMPLATE_DRIFT')
    r.require(r.writer_bindings(directory) == pending['bindings'] and state['volumes'] == pending['volumes'], 'LOG_PORTAL_ADOPTION_DRIFT')
    for service,identity in pending['preserved'].items():
        item = r.container(state,directory,service)
        r.require({'id':item['Id'],'image':item['Image']} == identity, 'LOG_PORTAL_ADOPTION_DRIFT')
    count = served_assets(state,directory)
    r.require(native_identity(native_settings,native_directory,directory) == current_native, 'LOG_PORTAL_ADOPTION_DRIFT')
    state['portalLastAdoption'] = {'inputSha256':input_sha,'native':current_native}
    del state['portalAdoption'];r.save(directory,state)
    return {'result':'ADOPTED','stage':'PORTAL_ADOPTED','buildId':selected['buildId'],'servedAssets':count,'full457':'OPEN'}


def main():
    parser = argparse.ArgumentParser(description=__doc__,allow_abbrev=False)
    parser.add_argument('--directory',required=True,type=Path);parser.add_argument('--settings',required=True,type=Path)
    args = parser.parse_args()
    try:
        r.local_daemon()
        with r.operator_lock(args.directory):
            state = json.loads(r.private(args.directory/'state.json'),object_pairs_hook=r.unique_object)
            r.validate_state(state,args.directory,state['project'],state['port'],state['idpPort'])
            print(json.dumps(adopt(state,args.directory,args.settings)));return 0
    except Exception:
        print(json.dumps({'result':'FAIL','code':'LOG_PORTAL_ADOPTION_INCOMPLETE','full457':'OPEN'}));return 1


if __name__ == '__main__': raise SystemExit(main())
