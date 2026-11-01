"""Fresh trusted-local-Docker application build provenance; never backfills a run.

Public inputs are hashed BEFORE builds. Per-run source labels must originate in
that build, then immutable image identities/rootfs are recorded immediately.
This trusts the local Docker builder/operator, not arbitrary image tags.
"""
import hashlib
import json
from pathlib import Path
import re

INPUT_RECORD = 'application-build-inputs.json'
IMAGE_RECORD = 'application-images.json'
SERVICES = ('document-management', 'knowledge-base', 'knowledge-search', 'browser-bff', 'admin-portal', 'keycloak', 'dm-postgres')
FIXED = ('build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','package.json','pnpm-lock.yaml','pnpm-workspace.yaml','.npmrc','.dockerignore','tools/runtime/upload-runtime.py','tools/runtime/application-provenance.py')
TREES = ('gradle','patches','generated-clients/java','generated-clients/typescript','infra/docker-compose')
IGNORED = {'build','.gradle','node_modules','dist','coverage','test-results','playwright-report','__pycache__'}


def inputs(root):
    files = {root / p for p in FIXED}
    for relative in TREES:
        files.update(p for p in (root / relative).rglob('*') if p.is_file() and not (set(p.relative_to(root).parts) & IGNORED))
    for tree in ('services','modules'):
        files.update(p for p in (root / tree).rglob('*') if p.is_file() and not (set(p.relative_to(root).parts) & IGNORED) and ('src/main/' in p.relative_to(root).as_posix() or p.name in ('build.gradle','gradle.lockfile')))
    portal = root / 'apps/admin-portal'
    files.update(p for p in portal.rglob('*') if p.is_file() and not (set(p.relative_to(root).parts) & IGNORED) and (p.is_relative_to(portal/'src') or p.is_relative_to(portal/'public') or (p.parent == portal and p.name in ('index.html','package.json','tsconfig.json','vite.config.ts'))))
    if not 20 <= len(files) <= 4096:raise ValueError('Bounded complete public build inventory required')
    for prefix in ('services/document-management/','services/browser-bff/','services/knowledge-base/','services/knowledge-search/','modules/identity-contracts/','modules/authorization-catalog/','modules/security-micronaut/','apps/admin-portal/src/','generated-clients/java/','generated-clients/typescript/'):
        if not any(p.relative_to(root).as_posix().startswith(prefix) for p in files):raise ValueError('Required public application build input missing')
    result={};total=0
    for path in sorted(files):
        if path.is_symlink() or not path.is_file() or path.resolve() != path or any(parent.is_symlink() for parent in path.parents):raise ValueError('Regular canonical public build input required')
        total += path.stat().st_size
        if total > 64000000:raise ValueError('Bounded public build input bytes required')
        result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def fingerprint(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()


def build_labels(state, digest, service):
    if service not in SERVICES or not re.fullmatch('[a-f0-9]{64}',digest):raise ValueError('Exact source digest required')
    return {'dkp.application.run':state['runId'],'dkp.application.source':digest,'dkp.application.service':service}


def capture(runtime, state):
    source = inputs(runtime.ROOT)
    record={'schemaVersion':1,'runId':state['runId'],'project':state['project'],'sources':source,'sourceDigest':fingerprint(source)}
    runtime.private(Path(state['directory'])/INPUT_RECORD,json.dumps(record,separators=(',',':')))
    return record


def read(runtime,state,name):
    path=Path(state['directory'])/name
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077 or path.stat().st_uid != __import__('os').getuid() or path.stat().st_size>2000000:raise ValueError('Fresh private application provenance required')
    return json.loads(path.read_text())


def current(runtime,state):
    record=read(runtime,state,INPUT_RECORD)
    if set(record)!={'schemaVersion','runId','project','sources','sourceDigest'} or type(record['schemaVersion']) is not int or record['schemaVersion']!=1 or record['runId']!=state['runId'] or record['project']!=state['project'] or record['sources']!=inputs(runtime.ROOT) or record['sourceDigest']!=fingerprint(record['sources']):raise ValueError('Original application build input changed')
    return record


def image_record(runtime,state,service,source_digest):
    tag='dkp137-'+state['runId']+'-'+service+':local'
    item=runtime.docker('image','inspect',tag)[0]
    layers=item.get('RootFS',{}).get('Layers')
    if not re.fullmatch('sha256:[a-f0-9]{64}',item['Id']) or not layers or len(layers)>128 or any(not re.fullmatch('sha256:[a-f0-9]{64}',v) for v in layers) or any(item.get('Config',{}).get('Labels',{}).get(k)!=v for k,v in build_labels(state,source_digest,service).items()):raise ValueError('Image was not freshly built from recorded inputs')
    return {'tag':tag,'imageId':item['Id'],'rootfs':layers,'labels':build_labels(state,source_digest,service)}


def seal(runtime,state):
    before=current(runtime,state)
    images={service:image_record(runtime,state,service,before['sourceDigest']) for service in SERVICES}
    current(runtime,state) # No changing source can be sealed after an interleaved build.
    runtime.private(Path(state['directory'])/IMAGE_RECORD,json.dumps({'schemaVersion':1,'runId':state['runId'],'sourceDigest':before['sourceDigest'],'images':images},separators=(',',':')))


def attest(runtime,state):
    source=current(runtime,state);record=read(runtime,state,IMAGE_RECORD)
    if set(record)!={'schemaVersion','runId','sourceDigest','images'} or type(record['schemaVersion']) is not int or record['schemaVersion']!=1 or record['runId']!=state['runId'] or record['sourceDigest']!=source['sourceDigest'] or set(record['images'])!=set(SERVICES):raise ValueError('Complete original application image provenance required')
    for service, expected in record['images'].items():
        if image_record(runtime,state,service,source['sourceDigest']) != expected:raise ValueError('Immutable prepared application image changed')
        identifier=runtime.compose(state,'ps','-a','-q',service).decode().strip()
        if not re.fullmatch('[a-f0-9]{64}',identifier):raise ValueError('Exact owned application instance required')
        item=runtime.docker('inspect',identifier)[0];runtime.owned(item,state)
        if item['Id']!=identifier or item['Image']!=expected['imageId'] or item['Config']['Image']!=expected['tag'] or item['Config'].get('Labels',{}).get('com.docker.compose.service')!=service or any(item['Config']['Labels'].get(k)!=v for k,v in expected['labels'].items()):raise ValueError('Live application differs from prepared build')
    return record
