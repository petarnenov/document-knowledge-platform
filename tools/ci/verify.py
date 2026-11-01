#!/usr/bin/env python3
"""Same finite pinned Linux Python check locally and in CI; no provider socket/access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import shutil
import subprocess
import sys
import uuid

HERE=Path(__file__).resolve().parent
RUNNER_FILES=('verify.py','python_runtime_inside.py','test_verify.py','python-runtime.Dockerfile','python-runtime-requirements.txt','python-runtime-pins.json')
MARKER=b'DKP_PYTHON_RECEIPT\t'

def sha(data):return hashlib.sha256(data).hexdigest()
def command(args,**kwargs):return subprocess.check_output(args,timeout=30,**kwargs)

def local_daemon():
    if os.environ.get('DOCKER_HOST') or os.environ.get('DOCKER_CONTEXT'):raise ValueError('Local default Docker context without overrides required')
    endpoint=json.loads(command(['docker','context','inspect','--format','{{json .Endpoints.docker.Host}}']))
    if not isinstance(endpoint,str) or not endpoint.startswith('unix:///'):raise ValueError('Local Unix-socket Docker daemon required')

def snapshot(source,destination=None):
    source=Path(source)
    if not source.is_absolute() or source.resolve(strict=True)!=source:raise ValueError('Canonical checkout required')
    head=command(['git','-C',str(source),'rev-parse','HEAD']).decode().strip()
    index=command(['git','-C',str(source),'ls-files','--stage','-z'])
    names=command(['git','-C',str(source),'ls-files','-z']).decode().split('\0')[:-1]
    rows=[]
    for name in sorted(names):
        relative=Path(name)
        if relative.is_absolute() or '..' in relative.parts or '.git' in relative.parts or relative.parts[0]=='local-data':raise ValueError('Public tracked source required')
        path=source/relative
        if path.resolve(strict=True)!=path or not path.is_file():raise ValueError('Regular canonical source required')
        data=path.read_bytes();executable=bool(path.stat().st_mode&0o111);rows.append({'path':name,'sha256':sha(data),'executable':executable})
        if destination is not None:
            target=destination/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data);target.chmod(0o555 if executable else 0o444)
    if destination is not None:
        for directory in sorted([destination]+[p for p in destination.rglob('*') if p.is_dir()],key=lambda p:len(p.parts),reverse=True):directory.chmod(0o555)
    return {'commit':head,'indexSha256':sha(index),'files':rows,'fingerprint':sha(json.dumps(rows,separators=(',',':')).encode())}

def runner_binding():return [{'path':name,'sha256':sha((HERE/name).read_bytes())} for name in RUNNER_FILES]

def log_limit():resource.setrlimit(resource.RLIMIT_FSIZE,(32*1024*1024,32*1024*1024))

def logged(args,path,timeout):
    with path.open('xb') as output:
        return subprocess.run(args,stdout=output,stderr=subprocess.STDOUT,timeout=timeout,preexec_fn=log_limit).returncode

def inspect(kind,identifier):return json.loads(command(['docker',kind,'inspect',identifier]))[0]

def owned(identifier,run,image):
    item=inspect('container',identifier)
    if item['Id']!=identifier or item['Image']!=image or item['Config']['Labels'].get('dkp.check.run')!=run or item['Config']['Labels'].get('dkp.issue')!='427':raise ValueError('Exact owned check container required')
    return item

def validate(source,evidence):
    evidence=Path(evidence);r=json.loads((evidence/'receipt.json').read_text())
    if r['schemaVersion']!=1 or r['profile']!='python-runtime' or r['result']!='PASS' or r['stage']!='COMPLETE' or r['cleanup']!='REMOVED':raise ValueError('Passing exact receipt required')
    if r['source']!=snapshot(source) or r['runner']!=runner_binding():raise ValueError('Evidence invalidated by source/index/runner change')
    if r['pins']!=json.loads((HERE/'python-runtime-pins.json').read_text()):raise ValueError('Pinned environment changed')
    if not re.fullmatch('sha256:[a-f0-9]{64}',r['imageId']) or r['platform']!='linux/amd64':raise ValueError('Immutable Linux image evidence required')
    if sha((evidence/'check.private.log').read_bytes())!=r['logSha256']:raise ValueError('Evidence log changed')
    child=r['check']
    actual=[line[len(MARKER):] for line in (evidence/'check.private.log').read_bytes().splitlines() if line.startswith(MARKER)]
    if len(actual)!=1 or json.loads(actual[0])!=child:raise ValueError('Exact executed receipt required')
    if child['result']!='PASS' or child['stage']!='COMPLETE' or child['python']!='3.14.10' or child['coverage']!='7.17.0' or child['coverageCore']!='ctrace' or '25.0.5' not in child['java']:raise ValueError('Complete pinned check required')
    # Reuse exact finite command/floor definitions rather than trusting claimed counters.
    import importlib.util
    spec=importlib.util.spec_from_file_location('inside_validation',HERE/'python_runtime_inside.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    if child['commands']!=module.COMMANDS or set(child['helpers'])!=set(module.helper_names(Path(source))):raise ValueError('Exact applicable commands/helpers required')
    for s in child['helpers'].values():
        if s['num_statements']<=0 or s['num_branches']<=0 or s['covered_lines']/s['num_statements']<.75 or s['covered_branches']/s['num_branches']<.75:raise ValueError('Coverage floor absent')
    if set(child['runnerHelpers'])!=set(module.RUNNER_HELPERS):raise ValueError('Runner selfcheck coverage absent')
    for s in child['runnerHelpers'].values():
        if s['num_statements']<=0 or s['num_branches']<=0 or s['covered_lines']/s['num_statements']<.75 or s['covered_branches']/s['num_branches']<.75:raise ValueError('Runner coverage floor absent')
    return r

def run(source,evidence):
    evidence=Path(evidence)
    if not evidence.is_absolute() or evidence.exists() or evidence.parent.resolve(strict=True)!=evidence.parent:raise ValueError('Fresh canonical private evidence directory required')
    evidence.mkdir(mode=0o700)
    run_id=uuid.uuid4().hex;identifier=None;image=None
    record={'schemaVersion':1,'profile':'python-runtime','result':'FAIL','stage':'PREFLIGHT','source':None,'runner':None,'pins':None,'platform':'linux/amd64','imageId':None,'check':None,'logSha256':None,'cleanup':'NOT_CREATED'}
    try:
        local_daemon()
        original=snapshot(source,evidence/'snapshot');runner=runner_binding();pins=json.loads((HERE/'python-runtime-pins.json').read_text())
        record.update(source=original,runner=runner,pins=pins,stage='IMAGE_BUILD')
        context=evidence/'image-context';context.mkdir(mode=0o700)
        for name in RUNNER_FILES:shutil.copyfile(HERE/name,context/name)
        if logged(['docker','build','--platform','linux/amd64','--iidfile',str(evidence/'image.id'),'-f',str(context/'python-runtime.Dockerfile'),str(context)],evidence/'build.private.log',600)!=0:raise ValueError('Pinned image build failed')
        image=(evidence/'image.id').read_text().strip()
        if not re.fullmatch('sha256:[a-f0-9]{64}',image):raise ValueError('Exact image ID required')
        metadata=inspect('image',image)
        if metadata['Os']!='linux' or metadata['Architecture']!='amd64':raise ValueError('Exact platform required')
        record['imageId']=image
        record['stage']='CONTAINER_CREATE'
        record['cleanup']='UNKNOWN'
        identifier=command(['docker','create','--platform','linux/amd64','--name','dkp-python-check-'+run_id,'--label','dkp.issue=427','--label','dkp.check.run='+run_id,'--network','none','--read-only','--user','10001:10001','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','1g','--cpus','2','--pids-limit','128','--tmpfs','/tmp:rw,nosuid,nodev,noexec,uid=10001,gid=10001,mode=0700,size=256m','--mount','type=bind,src='+str(evidence/'snapshot')+',dst=/workspace,readonly',image]).decode().strip()
        if not re.fullmatch('[a-f0-9]{64}',identifier):raise ValueError('Exact container identity required')
        record['cleanup']='UNKNOWN';owned(identifier,run_id,image)
        record['stage']='CHECK'
        code=logged(['docker','start','--attach',identifier],evidence/'check.private.log',600)
        item=owned(identifier,run_id,image)
        data=(evidence/'check.private.log').read_bytes();record['logSha256']=sha(data)
        lines=[line[len(MARKER):] for line in data.splitlines() if line.startswith(MARKER)]
        if len(lines)!=1:raise ValueError('One exact check receipt required')
        record['check']=json.loads(lines[0])
        if code!=0 or item['State']['Running'] or item['State']['ExitCode']!=0:raise ValueError('Check failed')
        if snapshot(source)!=original or runner_binding()!=runner:raise ValueError('Source/index changed during check')
        record.update(result='PASS',stage='COMPLETE')
    finally:
        try:
            if identifier and re.fullmatch('[a-f0-9]{64}',identifier):
                owned(identifier,run_id,image);command(['docker','rm','--force',identifier]);record['cleanup']='REMOVED'
        except Exception:
            record['result']='FAIL';raise
        finally:
            (evidence/'receipt.json').write_text(json.dumps(record,indent=2)+'\n');(evidence/'receipt.json').chmod(0o600)
    return validate(source,evidence)

def main():
    parser=argparse.ArgumentParser(description=__doc__,allow_abbrev=False);parser.add_argument('action',choices=['python-runtime','validate-evidence']);parser.add_argument('--source',required=True);parser.add_argument('--evidence',required=True);args=parser.parse_args()
    try:
        result=run(args.source,args.evidence) if args.action=='python-runtime' else validate(args.source,args.evidence)
        print(json.dumps({'profile':'python-runtime','result':result['result'],'sourceFingerprint':result['source']['fingerprint']}));return 0
    except Exception:
        print('Pinned Linux Python check failed; private evidence retained',file=sys.stderr);return 1

if __name__=='__main__':sys.exit(main())
