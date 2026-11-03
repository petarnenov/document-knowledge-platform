"""Owned persistent known Keycloak peer; no implicit replacement or public ports."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import ssl
import tempfile
import time
import urllib.request
import uuid

spec=importlib.util.spec_from_file_location('identity_logging_runtime',Path(__file__).with_name('dev-logging.py'))
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)


def digest(raw): return hashlib.sha256(raw).hexdigest()


def private(path, maximum=1048576):
    r.require(path.is_absolute() and path.resolve(strict=True)==path,'LOG_IDENTITY_PATH_INVALID')
    return r.private(path,maximum)


def labels(record):
    return {'dkp.owner':'portal-identity-peer','dkp.issue':'457','dkp.identity.owner-id':record['ownerId'],
            'dkp.identity.project':record['name']}


def inputs(shared):
    r.require(shared.is_absolute() and shared.resolve(strict=True)==shared,'LOG_IDENTITY_PATH_INVALID')
    state=json.loads(private(shared/'state.json'),object_pairs_hook=r.unique_object)
    r.validate_state(state,shared,state['project'],state['port'],state['idpPort']);r.verify_live(state,shared)
    name=state['project']+'-edge';edge=r.inspect('network',name);r.owned(edge,state)
    r.require(not edge['Internal'] and [row['Subnet'] for row in edge['IPAM']['Config']]==[state['subnets']['edge']])
    pki=shared/'gateway-pki'
    references={name:digest(private(pki/name)) for name in ('idp.crt','idp.key','ca.crt','dkp-realm.json','runtime.env')}
    values={}
    for line in private(pki/'runtime.env',32768).decode().splitlines():
        key,separator,value=line.partition('=');r.require(separator and key not in values);values[key]=value
    r.require(re.fullmatch('[A-Za-z0-9_-]{1,128}',values['KC_BOOTSTRAP_ADMIN_USERNAME'])
        and re.fullmatch('[A-Za-z0-9_-]{32,512}',values['KC_BOOTSTRAP_ADMIN_PASSWORD']))
    issuer=r.security_bootstrap().origins(state['port'],state['idpPort'])[1]
    environment={'KC_DB':'dev-file','KC_HOSTNAME':issuer.removesuffix('/realms/dkp'),'KC_HTTP_ENABLED':'false',
        'KC_HTTPS_PORT':'18444','KC_HTTPS_CERTIFICATE_FILE':'/run/dkp/idp.crt','KC_HTTPS_CERTIFICATE_KEY_FILE':'/run/dkp/idp.key',
        'KC_HTTP_MANAGEMENT_SCHEME':'http','KC_BOOTSTRAP_ADMIN_USERNAME':values['KC_BOOTSTRAP_ADMIN_USERNAME'],
        'KC_BOOTSTRAP_ADMIN_PASSWORD':values['KC_BOOTSTRAP_ADMIN_PASSWORD'],'KC_LOG_LEVEL':'warn'}
    env=''.join(key+'='+value+'\n' for key,value in environment.items()).encode()
    recipe=r.ROOT/'infra/docker-compose/keycloak.Dockerfile';probe=recipe.with_name('keycloak-probe.sh')
    return {'sharedOwnerId':state['ownerId'],'edge':name,'edgeId':edge['Id'],'references':references,
        'issuer':issuer,'environment':environment,'env':env,'recipeSha256':digest(recipe.read_bytes()),
        'probeSha256':digest(probe.read_bytes()),'uid':state['uid'],'gid':state['gid']}


def no_alias_collision(current, container_id=None):
    ids=r.docker('ps','--all','--filter','network='+current['edge'],'--format','{{.ID}}').decode().split()
    for identity in ids:
        item=r.inspect('container',identity)
        aliases=item['NetworkSettings']['Networks'].get(current['edge'],{}).get('Aliases') or []
        r.require(item['Id']==container_id or 'keycloak' not in aliases,'LOG_IDENTITY_ALIAS_CONFLICT')


def verify(record, directory, shared):
    r.require(directory.is_absolute() and directory.resolve(strict=True)==directory and directory.is_dir()
        and directory.stat().st_uid==os.getuid() and not directory.stat().st_mode & 0o077,'LOG_IDENTITY_PATH_INVALID')
    current=inputs(shared)
    r.require(record['schemaVersion']==1 and re.fullmatch('[0-9a-f]{32}',record['ownerId'])
        and record['sharedDirectory']==str(shared) and record['name']=='dkp-identity-peer-'+record['ownerId'])
    for key in ('sharedOwnerId','edge','edgeId','references','recipeSha256','probeSha256','uid','gid'):
        r.require(record[key]==current[key],'LOG_IDENTITY_BINDING_DRIFT')
    r.require(digest(private(directory/'runtime.env',32768))==record['envSha256']==digest(current['env'])
        and digest(private(directory/'data/import/dkp-realm.json'))==current['references']['dkp-realm.json'],'LOG_IDENTITY_BINDING_DRIFT')
    data=directory/'data'
    r.require(data.resolve(strict=True)==data and data.stat().st_uid==os.getuid()
        and not data.stat().st_mode & 0o077,'LOG_IDENTITY_BINDING_DRIFT')
    image=r.inspect('image',record['imageId']);actual=image['Config']['Labels']
    r.require(all(actual.get(key)==value for key,value in {**labels(record),'dkp.identity.source':record['sourceFingerprint']}.items())
        and image['Os']=='linux' and image['Architecture']=='amd64','LOG_IDENTITY_IMAGE_MISMATCH')
    item=r.inspect('container',record['containerId']);actual=item['Config']['Labels']
    r.require(item['Id']==record['containerId'] and item['Image']==record['imageId']
        and item['Name']=='/'+record['name'] and all(actual.get(key)==value for key,value in labels(record).items()),'LOG_IDENTITY_OWNER_MISMATCH')
    host=item['HostConfig']
    r.require(host['ReadonlyRootfs'] and host['RestartPolicy']['Name']=='unless-stopped' and 'ALL' in host['CapDrop']
        and 'no-new-privileges:true' in host['SecurityOpt'] and not host.get('PortBindings')
        and (host['Memory'],host['NanoCpus'],host['PidsLimit'])==(768*1024*1024,2_000_000_000,128)
        and host['Tmpfs']=={'/tmp':'rw,nosuid,nodev,uid='+str(current['uid'])+',gid='+str(current['gid'])+',mode=0700'}
        and item['Config']['User']==str(current['uid'])+':'+str(current['gid']),'LOG_IDENTITY_PROFILE_DRIFT')
    r.require(set(item['NetworkSettings']['Networks'])=={current['edge']},'LOG_IDENTITY_NETWORK_DRIFT')
    network=item['NetworkSettings']['Networks'][current['edge']]
    r.require((network['NetworkID']==current['edgeId'] or
        (item['State'].get('Status')=='created' and network['NetworkID']=='')) and 'keycloak' in (network.get('Aliases') or [])
        and set(network.get('Aliases') or [])<={'keycloak',record['name']},'LOG_IDENTITY_NETWORK_DRIFT')
    expected={('/opt/keycloak/data',str(data),True),('/run/dkp/idp.crt',str(shared/'gateway-pki/idp.crt'),False),
        ('/run/dkp/idp.key',str(shared/'gateway-pki/idp.key'),False)}
    r.require(all(mount['Type']=='bind' for mount in item['Mounts'])
        and {(mount['Destination'],mount['Source'],mount['RW']) for mount in item['Mounts']}==expected,'LOG_IDENTITY_MOUNT_DRIFT')
    env=dict(row.split('=',1) for row in item['Config']['Env'] if '=' in row)
    inherited=dict(row.split('=',1) for row in image['Config'].get('Env',[]) if '=' in row)
    r.require(env=={**inherited,**current['environment']}
        and item['Config']['Cmd']==['start','--optimized','--import-realm']
        and item['Config']['Entrypoint']==image['Config']['Entrypoint']
        and item['Config']['Healthcheck']==image['Config']['Healthcheck'],'LOG_IDENTITY_PROFILE_DRIFT')
    no_alias_collision(current,item['Id'])
    return current,item


def discovery(current, shared):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs):return None
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=shared/'gateway-pki/ca.crt')))
    with opener.open(current['issuer']+'/.well-known/openid-configuration',timeout=3) as response:
        raw=response.read(65537)
        r.require(response.status==200 and len(raw)<=65536 and json.loads(raw)['issuer']==current['issuer'],'LOG_IDENTITY_DISCOVERY_INVALID')


def status(directory, shared):
    record=json.loads(private(directory/'state.json'),object_pairs_hook=r.unique_object)
    current,item=verify(record,directory,shared)
    r.require(item['State']['Running'] and item['State'].get('Health',{}).get('Status')=='healthy','LOG_IDENTITY_NOT_READY')
    discovery(current,shared)
    return {'result':'PASS','stage':'IDENTITY_PEER_ACTIVE','containerId':item['Id'],'issuer':current['issuer'],'full457':'OPEN'}


def start(directory, shared):
    r.require(directory.is_absolute() and directory.parent.resolve(strict=True)==directory.parent
        and directory.parent.stat().st_uid==os.getuid() and not directory.parent.stat().st_mode & 0o077,'LOG_IDENTITY_PATH_INVALID')
    if not directory.exists():
        r.require(not directory.is_symlink(),'LOG_IDENTITY_PATH_INVALID')
        current=inputs(shared);no_alias_collision(current)
        directory.mkdir(mode=0o700);(directory/'data').mkdir(mode=0o700);(directory/'data/import').mkdir(mode=0o700)
        r.write_private(directory/'data/import/dkp-realm.json',private(shared/'gateway-pki/dkp-realm.json'))
        r.write_private(directory/'runtime.env',current['env'])
        record={key:current[key] for key in ('sharedOwnerId','edge','edgeId','references','recipeSha256','probeSha256','uid','gid')}
        record.update(schemaVersion=1,ownerId=uuid.uuid4().hex,sharedDirectory=str(shared),envSha256=digest(current['env']),stage='PREPARED')
        record['name']='dkp-identity-peer-'+record['ownerId'];r.write_private(directory/'state.json',json.dumps(record))
        sys_path=str(r.ROOT/'tools/ci')
        import sys
        sys.path.insert(0,sys_path);import verify as public
        with tempfile.TemporaryDirectory(prefix='dkp-identity-public-') as temporary:
            source=public.snapshot(r.ROOT,Path(temporary));image=record['name']+':'+source['fingerprint'][:16]
            args=['build','--platform','linux/amd64','--tag',image,'--file',str(Path(temporary)/'infra/docker-compose/keycloak.Dockerfile')]
            for key,value in {**labels(record),'dkp.identity.source':source['fingerprint']}.items():args+=['--label',key+'='+value]
            r.docker(*args,temporary,timeout=600)
            r.require(public.snapshot(r.ROOT)==source,'LOG_IDENTITY_SOURCE_DRIFT')
        metadata=r.inspect('image',image);record.update(imageId=metadata['Id'],sourceFingerprint=source['fingerprint'],stage='IMAGE_READY')
        r.write_private(directory/'state.json',json.dumps(record),replace=True)
        r.require(inputs(shared)==current,'LOG_IDENTITY_BINDING_DRIFT');no_alias_collision(current)
        r.require(not r.exists('container',record['name']),'LOG_IDENTITY_UNKNOWN_CONTAINER')
        args=['create','--platform','linux/amd64','--name',record['name'],'--user',str(current['uid'])+':'+str(current['gid']),
            '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--restart','unless-stopped',
            '--memory','768m','--cpus','2','--pids-limit','128','--tmpfs','/tmp:rw,nosuid,nodev,uid='+str(current['uid'])+',gid='+str(current['gid'])+',mode=0700',
            '--network',current['edge'],'--network-alias','keycloak','--env-file',str(directory/'runtime.env')]
        for key,value in labels(record).items():args+=['--label',key+'='+value]
        for source,target,writable in [(directory/'data','/opt/keycloak/data',True),(shared/'gateway-pki/idp.crt','/run/dkp/idp.crt',False),
                (shared/'gateway-pki/idp.key','/run/dkp/idp.key',False)]:
            args+=['--mount','type=bind,src='+str(source)+',dst='+target+('' if writable else ',readonly')]
        identity=r.docker(*args,record['imageId'],'start','--optimized','--import-realm').decode().strip()
        r.require(re.fullmatch('[0-9a-f]{64}',identity),'LOG_IDENTITY_CONTAINER_INVALID')
        record.update(containerId=identity,stage='CREATED');r.write_private(directory/'state.json',json.dumps(record),replace=True)
    record=json.loads(private(directory/'state.json'),object_pairs_hook=r.unique_object)
    r.require('containerId' in record,'LOG_IDENTITY_START_INCOMPLETE')
    current,item=verify(record,directory,shared)
    if not item['State']['Running']:r.docker('start',item['Id'])
    deadline=time.monotonic()+75
    while True:
        try:
            result=status(directory,shared);break
        except (r.RuntimeFailure,OSError):
            r.require(time.monotonic()<deadline,'LOG_IDENTITY_NOT_READY');time.sleep(.5)
    record['stage']='READY';r.write_private(directory/'state.json',json.dumps(record),replace=True)
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__,allow_abbrev=False);parser.add_argument('action',choices=('start','status'))
    parser.add_argument('--directory',required=True,type=Path);parser.add_argument('--shared-directory',required=True,type=Path);args=parser.parse_args()
    try:
        r.local_daemon()
        with r.operator_lock(args.directory),r.operator_lock(args.shared_directory):
            result=start(args.directory,args.shared_directory) if args.action=='start' else status(args.directory,args.shared_directory)
        print(json.dumps(result));return 0
    except Exception:
        print(json.dumps({'result':'FAIL','code':'LOG_IDENTITY_PEER_INCOMPLETE','full457':'OPEN'}));return 1


if __name__=='__main__':raise SystemExit(main())
