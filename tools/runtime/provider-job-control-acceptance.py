#!/usr/bin/env python3
"""Real isolated cross-UID private control regression. No provider/network access."""
import importlib.util
import json
from pathlib import Path
import re
import uuid

spec=importlib.util.spec_from_file_location('control_jobs',Path(__file__).with_name('provider-jobs.py'))
jobs=importlib.util.module_from_spec(spec);spec.loader.exec_module(jobs)
r=jobs.runtime
BROWSER_IMAGE="mcr.microsoft.com/playwright:v1.64.1-noble@sha256:47b510dd9230bf0b9bfc9b20102241e577ea7a99334ab4c3ca19f19770e9b8ea"


def owned(identifier,image,run_id):
    item=r.docker('inspect',identifier)[0];host=item['HostConfig'];config=item['Config']
    if (item['Id']!=identifier or item['Image']!=image or config['Image']!=jobs.NODE_IMAGE or config.get('Labels',{}).get('dkp.control.run')!=run_id or config.get('Labels',{}).get('dkp.issue')!='305' or config['User']!='1001:1001' or config['Entrypoint']!=['node'] or config['Cmd']!=['-e','setTimeout(()=>{},30000)'] or item['Mounts'] or host['NetworkMode']!='none' or not host['ReadonlyRootfs'] or host.get('CapAdd') or host.get('Privileged') or host['CapDrop']!=['ALL'] or host['SecurityOpt'] not in (['no-new-privileges:true'],['no-new-privileges']) or host['Memory']!=67108864 or host['NanoCpus']!=250000000 or host['PidsLimit']!=16 or host['Tmpfs']!={'/tmp/dkp-job-boundary':'uid=1001,gid=1001,mode=0700,size=16k'}):raise ValueError('Exact isolated control fixture required')
    return item


def run():
    r.local_daemon();image=r.image(jobs.NODE_IMAGE);run_id=uuid.uuid4().hex
    actual_uid=r.command(['docker','run','--rm','--pull=never','--name','dkp305-browser-uid-'+run_id,'--label','dkp.issue=305','--network','none','--read-only','--user','1001:1001','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','64m','--cpus','.25','--pids-limit','16','--entrypoint','/usr/bin/id',BROWSER_IMAGE,'-u','pwuser'],timeout=10)
    if actual_uid.strip()!=b'1001':raise ValueError('Pinned browser pwuser UID differs')
    identifier=r.command(['docker','create','--pull=never','--name','dkp305-control-'+run_id,'--label','dkp.issue=305','--label','dkp.control.run='+run_id,'--user','1001:1001','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','64m','--cpus','.25','--pids-limit','16','--tmpfs','/tmp/dkp-job-boundary:uid=1001,gid=1001,mode=0700,size=16k','--entrypoint','node',jobs.NODE_IMAGE,'-e','setTimeout(()=>{},30000)']).decode().strip()
    if not re.fullmatch('[a-f0-9]{64}',identifier):raise ValueError('Exact fixture identity required')
    def execute(user,script,*args):
        owned(identifier,image,run_id)
        return r.command(['docker','exec','--user',user,identifier,'node','-e',script,*args],timeout=5)
    try:
        owned(identifier,image,run_id);r.command(['docker','start',identifier])
        execute('1001:1001',"require('node:fs').writeFileSync('/tmp/dkp-job-boundary/prepared.json','{\"schemaVersion\":1}',{mode:384,flag:'wx'});")
        if json.loads(execute('1001:1001',jobs.CONTROL_SCRIPT,'read','prepared'))!={'schemaVersion':1}:raise ValueError('Private read failed')
        if json.loads(execute('1001:1001',jobs.CONTROL_SCRIPT,'write','begin'))!={'written':True}:raise ValueError('Private write failed')
        proof=execute('1001:1001',"const fs=require('node:fs'),p='/tmp/dkp-job-boundary',d=fs.lstatSync(p),s=fs.lstatSync(p+'/begin.json');if(d.uid!==1001||(d.mode&511)!==448||s.uid!==1001||(s.mode&511)!==384||fs.readFileSync(p+'/begin.json','utf8')!=='{\"schemaVersion\":1,\"checkpoint\":\"begin\"}')process.exit(1);process.stdout.write('PRIVATE');")
        if proof!=b'PRIVATE':raise ValueError('Private owner/mode proof failed')
        denied=execute('1000:1000',"try{require('node:fs').readFileSync('/tmp/dkp-job-boundary/prepared.json');process.exit(1);}catch(error){if(error.code!=='EACCES')process.exit(2);process.stdout.write('DENIED');}")
        if denied!=b'DENIED':raise ValueError('Different UID was not denied')
        return {'schemaVersion':1,'result':'PASS','privateUid':1001,'differentUidDenied':1000,'directoryMode':'0700','fileMode':'0600','providerAccess':False}
    finally:
        owned(identifier,image,run_id);r.command(['docker','rm','--force',identifier])


if __name__=='__main__':
    try:print(json.dumps(run()))
    except Exception:raise SystemExit('Isolated private control acceptance failed closed') from None
