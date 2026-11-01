#!/usr/bin/env python3
"""Real isolated cross-UID private control regression. No provider/network access."""
import importlib.util
from enum import Enum
import sys
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


class Stage(Enum):
    DAEMON='DAEMON'
    IMAGE='IMAGE'
    BROWSER_UID='BROWSER_UID'
    CREATE='CREATE'
    IDENTITY='IDENTITY'
    START='START'
    PREPARE='PREPARE'
    READ='READ'
    WRITE='WRITE'
    MODES='MODES'
    DENIAL='DENIAL'
    CLEANUP='CLEANUP'
    COMPLETE='COMPLETE'
    UNKNOWN='UNKNOWN'


class Boundary(Enum):
    COMMAND='COMMAND'
    OWNERSHIP='OWNERSHIP'
    VALIDATION='VALIDATION'
    NONE='NONE'
    UNKNOWN='UNKNOWN'


class Category(Enum):
    TIMEOUT='TIMEOUT'
    COMMAND_UNAVAILABLE='COMMAND_UNAVAILABLE'
    COMMAND_FAILED='COMMAND_FAILED'
    VALIDATION='VALIDATION'
    LOCAL_IO='LOCAL_IO'
    NONE='NONE'
    UNKNOWN='UNKNOWN'


class Cleanup(Enum):
    NOT_CREATED='NOT_CREATED'
    UNKNOWN='UNKNOWN'
    REMOVED='REMOVED'


class ControlFailure(ValueError):
    def __init__(self, stage, boundary, category):
        if not isinstance(stage,Stage) or not isinstance(boundary,Boundary) or not isinstance(category,Category):
            raise ValueError('Closed private-control diagnostic required')
        self.stage=stage;self.boundary=boundary;self.category=category
        super().__init__('Isolated private control acceptance failed closed')


def category(error):
    if isinstance(error,r.CommandFailure):
        if error.category=='command-timeout':return Category.TIMEOUT
        if error.category=='command-unavailable':return Category.COMMAND_UNAVAILABLE
        return Category.COMMAND_FAILED
    if isinstance(error,ValueError):return Category.VALIDATION
    if isinstance(error,OSError):return Category.LOCAL_IO
    return Category.UNKNOWN


def step(stage,boundary,operation):
    if not isinstance(stage,Stage) or not isinstance(boundary,Boundary):raise ValueError('Closed diagnostic stage required')
    try:return operation()
    except ControlFailure:raise
    except Exception as error:raise ControlFailure(stage,boundary,category(error)) from None


def require(stage,condition):
    if not condition:raise ControlFailure(stage,Boundary.VALIDATION,Category.VALIDATION)


def diagnostic(result,stage,boundary,cause,cleanup):
    # Only locally selected enum values enter this public record, never exception text/class/command output.
    if result not in ('PASS','FAIL') or not isinstance(stage,Stage) or not isinstance(boundary,Boundary) or not isinstance(cause,Category) or not isinstance(cleanup,Cleanup):
        raise ValueError('Closed diagnostic fields required')
    record={'schemaVersion':1,'issue':413,'result':result,'stage':stage.value,'boundary':boundary.value,'category':cause.value,'controlFixtureCleanup':cleanup.value}
    try:print(json.dumps(record,separators=(',',':')),file=sys.stderr)
    except Exception:pass  # A diagnostic sink never replaces known acceptance/failure or cleanup semantics.


def run():
    progress={'cleanup':Cleanup.NOT_CREATED}
    try:
        result=execute_fixture(progress)
    except Exception as error:
        failure=error if isinstance(error,ControlFailure) else ControlFailure(Stage.UNKNOWN,Boundary.UNKNOWN,category(error))
        diagnostic('FAIL',failure.stage,failure.boundary,failure.category,progress['cleanup'])
        raise failure from None
    diagnostic('PASS',Stage.COMPLETE,Boundary.NONE,Category.NONE,progress['cleanup'])
    return result


def execute_fixture(progress):
    step(Stage.DAEMON,Boundary.COMMAND,r.local_daemon)
    image=step(Stage.IMAGE,Boundary.COMMAND,lambda:r.image(jobs.NODE_IMAGE));run_id=uuid.uuid4().hex
    actual_uid=step(Stage.BROWSER_UID,Boundary.COMMAND,lambda:r.command(['docker','run','--rm','--pull=never','--name','dkp305-browser-uid-'+run_id,'--label','dkp.issue=305','--network','none','--read-only','--user','1001:1001','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','64m','--cpus','.25','--pids-limit','16','--entrypoint','/usr/bin/id',BROWSER_IMAGE,'-u','pwuser'],timeout=10))
    require(Stage.BROWSER_UID,actual_uid.strip()==b'1001')
    progress['cleanup']=Cleanup.UNKNOWN
    raw=step(Stage.CREATE,Boundary.COMMAND,lambda:r.command(['docker','create','--pull=never','--name','dkp305-control-'+run_id,'--label','dkp.issue=305','--label','dkp.control.run='+run_id,'--user','1001:1001','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','64m','--cpus','.25','--pids-limit','16','--tmpfs','/tmp/dkp-job-boundary:uid=1001,gid=1001,mode=0700,size=16k','--entrypoint','node',jobs.NODE_IMAGE,'-e','setTimeout(()=>{},30000)']))
    identifier=step(Stage.IDENTITY,Boundary.VALIDATION,lambda:raw.decode().strip())
    require(Stage.IDENTITY,bool(re.fullmatch('[a-f0-9]{64}',identifier)))
    def execute(stage,user,script,*args):
        step(stage,Boundary.OWNERSHIP,lambda:owned(identifier,image,run_id))
        return step(stage,Boundary.COMMAND,lambda:r.command(['docker','exec','--user',user,identifier,'node','-e',script,*args],timeout=5))
    try:
        step(Stage.START,Boundary.OWNERSHIP,lambda:owned(identifier,image,run_id))
        step(Stage.START,Boundary.COMMAND,lambda:r.command(['docker','start',identifier]))
        execute(Stage.PREPARE,'1001:1001',"require('node:fs').writeFileSync('/tmp/dkp-job-boundary/prepared.json','{\"schemaVersion\":1}',{mode:384,flag:'wx'});")
        actual=step(Stage.READ,Boundary.VALIDATION,lambda:json.loads(execute(Stage.READ,'1001:1001',jobs.CONTROL_SCRIPT,'read','prepared')))
        require(Stage.READ,actual=={'schemaVersion':1})
        actual=step(Stage.WRITE,Boundary.VALIDATION,lambda:json.loads(execute(Stage.WRITE,'1001:1001',jobs.CONTROL_SCRIPT,'write','begin')))
        require(Stage.WRITE,actual=={'written':True})
        proof=execute(Stage.MODES,'1001:1001',"const fs=require('node:fs'),p='/tmp/dkp-job-boundary',d=fs.lstatSync(p),s=fs.lstatSync(p+'/begin.json');if(d.uid!==1001||(d.mode&511)!==448||s.uid!==1001||(s.mode&511)!==384||fs.readFileSync(p+'/begin.json','utf8')!=='{\"schemaVersion\":1,\"checkpoint\":\"begin\"}')process.exit(1);process.stdout.write('PRIVATE');")
        require(Stage.MODES,proof==b'PRIVATE')
        denied=execute(Stage.DENIAL,'1000:1000',"try{require('node:fs').readFileSync('/tmp/dkp-job-boundary/prepared.json');process.exit(1);}catch(error){if(error.code!=='EACCES')process.exit(2);process.stdout.write('DENIED');}")
        require(Stage.DENIAL,denied==b'DENIED')
        return {'schemaVersion':1,'result':'PASS','privateUid':1001,'differentUidDenied':1000,'directoryMode':'0700','fileMode':'0600','providerAccess':False}
    finally:
        step(Stage.CLEANUP,Boundary.OWNERSHIP,lambda:owned(identifier,image,run_id))
        step(Stage.CLEANUP,Boundary.COMMAND,lambda:r.command(['docker','rm','--force',identifier]))
        progress['cleanup']=Cleanup.REMOVED


if __name__=='__main__':
    try:print(json.dumps(run()))
    except Exception:raise SystemExit('Isolated private control acceptance failed closed') from None
