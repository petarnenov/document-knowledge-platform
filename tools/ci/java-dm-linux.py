#!/usr/bin/env python3
"""Pinned Linux real-integration controller; Docker API is required by existing PG tests.

This is NOT a socket-free unit sandbox. Only tracked public source is built. No application
runtime authority, host source/cache, or credentials are mounted. Testcontainers owns its
fresh random-password PostgreSQL fixtures and Ryuk lifecycle.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import uuid
import xml.etree.ElementTree as ET
import verify as common

PIN='eclipse-temurin:25.0.5_8-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507'
LAYERS=('domain','application','adapters-in','adapters-out','bootstrap')
COMMANDS=(['./gradlew','--no-daemon',':services:document-management:bootstrap:build'],['./gradlew','--no-daemon',':services:document-management:adapters-out:persistenceCoverageVerification'])
EXPECTED_SKIPS={
'actualServerOutageFailsClosedAndDurableExactVersionsSurviveRestart()',
'conditionalPutReturnsExactVersionAndCopyPreservesReadbackHashAndRange()',
'productionFactoryRejectsUnsafeAuthorityCaCredentialsAndSpoolMarker()',
'conditionalCreateCannotOverwriteOrAccumulateVersionsAndWrongVersionCannotSubstitute()',
'exactVersionDeletionHasRealRequestBoundTerminalProofAndAuthoritativeAbsence()',
'nativeNormalDeleteIsDeniedWhileCleanupIsNarrowAndRoleSwapsFailBeforeTransport()',
'nativeIamDeniesAnonymousWrongCredentialsAdminUnversionedDeleteAndOutsidePrefix()'}

def call(args):return common.command(args)
def inspect(kind,name):return json.loads(call(['docker',kind,'inspect',name]))[0]
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def socket_group(socket):
    metadata=socket.stat()
    if not stat.S_ISSOCK(metadata.st_mode):raise ValueError("Local Docker socket required")
    return metadata.st_gid
def owned(name,run,image):
    item=inspect('container',name)
    if item['Id']!=name or item['Image']!=image or item['Config']['Labels'].get('dkp.check.run')!=run or item['Config']['Labels'].get('dkp.issue')!='431':raise ValueError('Owned controller mismatch')
    return item

def results(directory):
    counts={'tests':0,'failures':0,'errors':0,'skipped':0};skips=set()
    for layer in LAYERS:
        files=list((directory/layer/'test-results'/'test').glob('TEST-*.xml'))
        if not files:raise ValueError('Missing layer test results')
        for path in files:
            root=ET.parse(path).getroot()
            for key in counts:counts[key]+=int(root.attrib[key])
            for case in root.findall('testcase'):
                if case.find('skipped') is not None:
                    if not case.attrib.get('classname','').endswith('.RustFsUploadStorageIntegrationTest') or case.attrib['name'] in skips:raise ValueError('Unexpected skipped test')
                    skips.add(case.attrib['name'])
    if counts['failures'] or counts['errors'] or skips!=EXPECTED_SKIPS or counts['skipped']!=len(skips):raise ValueError('Incomplete ordinary Java profile')
    coverage={}
    for name,path in [('service',directory/'bootstrap'/'reports'/'jacoco'/'serviceCoverageReport'/'serviceCoverageReport.xml'),('persistence',directory/'adapters-out'/'reports'/'jacoco'/'persistenceCoverageReport'/'persistenceCoverageReport.xml')]:
        root=ET.parse(path).getroot();counters={c.attrib['type']:c.attrib for c in root.findall('counter')};coverage[name]={}
        for key in ('LINE','BRANCH'):
            counter=counters[key];covered=int(counter['covered']);missed=int(counter['missed'])
            if covered+missed<=0 or covered/(covered+missed)<.75:raise ValueError('Coverage floor failed')
            coverage[name][key]={'covered':covered,'missed':missed}
    return {'counts':counts,'coverage':coverage,'expectedNativeSkips':sorted(skips),'nativeAcceptance':False}

def run(source,evidence):
    source=Path(source);evidence=Path(evidence)
    if not evidence.is_absolute() or evidence.exists() or evidence.parent.resolve(strict=True)!=evidence.parent:raise ValueError('Fresh canonical evidence required')
    evidence.mkdir(mode=0o700);name=None;image=None;run_id=uuid.uuid4().hex
    record={'schemaVersion':1,'profile':'java-dm-linux-real-pg','result':'FAIL','stage':'PREFLIGHT','cleanup':'NOT_CREATED','pin':PIN,'commands':COMMANDS,'source':None,'imageId':None,'results':None}
    try:
        common.local_daemon()
        endpoint=json.loads(call(['docker','context','inspect','--format','{{json .Endpoints.docker.Host}}']))
        socket=Path(endpoint.removeprefix('unix://')).resolve(strict=True)
        socket_gid=socket_group(socket)
        with tempfile.TemporaryDirectory(prefix='dkp431-public-') as temporary:
            snapshot=Path(temporary);record['source']=common.snapshot(source,snapshot)
            for directory in [snapshot]+[p for p in snapshot.rglob('*') if p.is_dir()]:directory.chmod(0o755)
            record['stage']='BUILD';tag='dkp431-java-'+run_id
            if common.logged(['docker','build','--platform','linux/amd64','--label','dkp.issue=431','--label','dkp.check.run='+run_id,'--tag',tag,'--file',str(snapshot/'tools/ci/java-dm-linux.Dockerfile'),str(snapshot)],evidence/'build.private.log',900):raise ValueError('Image build failed')
            image=inspect('image',tag)
            if not re.fullmatch('sha256:[a-f0-9]{64}',image['Id']) or image['Os']!='linux' or image['Architecture']!='amd64' or image['Config']['Labels'].get('dkp.check.run')!=run_id:raise ValueError('Pinned built image required')
            record['imageId']=image['Id'];record['stage']='CHECK'
            candidate=call(['docker','create','--platform','linux/amd64','--label','dkp.issue=431','--label','dkp.check.run='+run_id,'--memory','5g','--cpus','4','--pids-limit','768','--group-add',str(socket_gid),'--cap-drop','ALL','--security-opt','no-new-privileges','--add-host','host.docker.internal:host-gateway','--mount','type=bind,src='+str(socket)+',dst=/var/run/docker.sock','--env','TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal','--env','TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock',image['Id']]).decode().strip()
            if not re.fullmatch('[a-f0-9]{64}',candidate):raise ValueError('Exact controller ID required')
            name=candidate
            owned(name,run_id,image['Id']);record['cleanup']='RETAINED'
            status=common.logged(['docker','start','--attach',name],evidence/'check.private.log',1800)
            state=owned(name,run_id,image['Id'])['State']
            reports=evidence/'reports';reports.mkdir()
            for layer in LAYERS:
                target=reports/layer;target.mkdir()
                call(['docker','cp',name+':/workspace/services/document-management/'+layer+'/build/test-results',str(target)])
                if layer in ('bootstrap','adapters-out'):call(['docker','cp',name+':/workspace/services/document-management/'+layer+'/build/reports',str(target)])
            record['stage']='RESULTS'
            if status or state['Running'] or state['ExitCode']:raise ValueError('Java check failed')
            if not re.search(rb'openjdk version "25\.0\.5"', (evidence/'check.private.log').read_bytes()):raise ValueError('Pinned Java version absent')
            record['results']=results(reports)
            if common.snapshot(source)!=record['source']:raise ValueError('Source changed during verification')
            record['result']='PASS';record['stage']='COMPLETE'
    finally:
        if name is not None and image is not None:
            try:
                owned(name,run_id,image['Id']);call(['docker','rm','--force',name]);record['cleanup']='REMOVED'
            except Exception:
                record['result']='FAIL';record['cleanup']='UNKNOWN'
        record['logs']={path.name:digest(path) for path in evidence.glob('*.private.log')}
        (evidence/'receipt.json').write_text(json.dumps(record,indent=2)+'\n')
        for path in evidence.rglob('*'):
            if path.is_file():path.chmod(0o600)
    return record

def validate(source,evidence):
    evidence=Path(evidence);r=json.loads((evidence/'receipt.json').read_text())
    if r['result']!='PASS' or r['stage']!='COMPLETE' or r['cleanup']!='REMOVED' or r['pin']!=PIN or r['commands']!=[list(c) for c in COMMANDS] or r['source']!=common.snapshot(Path(source)):raise ValueError('Current passing receipt required')
    if not re.fullmatch('sha256:[a-f0-9]{64}',r['imageId']) or r['results']!=results(evidence/'reports'):raise ValueError('Executed image/results changed')
    if r['logs']!={path.name:digest(path) for path in evidence.glob('*.private.log')}:raise ValueError('Executed logs changed')
    return r

def main():
    parser=argparse.ArgumentParser(allow_abbrev=False);parser.add_argument('action',choices=('run','validate'));parser.add_argument('--source',required=True);parser.add_argument('--evidence',required=True);args=parser.parse_args()
    try:
        r=run(args.source,args.evidence) if args.action=='run' else validate(args.source,args.evidence)
        if r['result']!='PASS':raise ValueError('Check or owned cleanup failed')
        print(json.dumps({'profile':r['profile'],'result':r['result'],'stage':r['stage'],'cleanup':r['cleanup']}))
    except (Exception,KeyboardInterrupt):
        print('{"profile":"java-dm-linux-real-pg","result":"FAIL"}');raise SystemExit(1)
if __name__=='__main__':main()
