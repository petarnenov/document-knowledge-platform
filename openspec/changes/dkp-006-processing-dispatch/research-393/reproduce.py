#!/usr/bin/env python3
"""Research-only artifact verification/compilation; never modifies application builds."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
PINS = HERE / 'artifact-pins.json'
REFERENCE = HERE.parent / 'research-379/candidate-pins.json'


def fetch(item, target):
    if not item['url'].startswith('https://repo.maven.apache.org/maven2/'):
        raise ValueError('Exact primary Maven origin required')
    subprocess.run(['curl','--disable','--fail','--silent','--show-error',item['url'],'-o',str(target)],check=True)
    if hashlib.sha256(target.read_bytes()).hexdigest() != item['sha256']:
        raise ValueError('Artifact identity mismatch')


def run(work):
    work = Path(work)
    if not work.is_absolute() or work.exists() or work.parent.resolve(strict=True) != work.parent:
        raise ValueError('Fresh canonical absolute research directory required')
    work.mkdir(mode=0o700)
    records=json.loads(PINS.read_text())['artifacts']
    jars=[]
    for coordinate in records:
        for item in coordinate['artifacts']:
            target=work/'artifacts'/Path(*coordinate['coordinate'].split(':'))/item['url'].rsplit('/',1)[1]
            target.parent.mkdir(parents=True,exist_ok=True)
            fetch(item,target)
            if target.suffix=='.jar' and not target.name.endswith('-sources.jar'):
                jars.append(str(target))
    ref=json.loads(REFERENCE.read_text())['javascriptReference']
    tarball=subprocess.check_output(['curl','--disable','--fail','--silent','--show-error',ref['tarball']])
    integrity='sha512-'+base64.b64encode(hashlib.sha512(tarball).digest()).decode()
    if hashlib.sha256(tarball).hexdigest()!=ref['tarballSha256'] or integrity!=ref['integrity']:
        raise ValueError('Reference tarball identity mismatch')
    with tarfile.open(fileobj=io.BytesIO(tarball),mode='r:gz') as archive:
        reference=archive.extractfile('package/lib/canonicalize.js').read()
    if hashlib.sha256(reference).hexdigest()!=ref['librarySha256']:
        raise ValueError('Reference module identity mismatch')
    (work/'reference.mjs').write_bytes(reference)
    jhome=Path(os.environ['JAVA_HOME']).resolve(strict=True)
    if subprocess.check_output([str(jhome/'bin/javac'),'-version']).strip()!=b'javac 25.0.5':
        raise ValueError('Pinned Java25.0.5 required')
    classes=work/'classes';classes.mkdir()
    cp=os.pathsep.join(jars)
    subprocess.run([str(jhome/'bin/javac'),'-g','-sourcepath','','-cp',cp,'-d',str(classes),str(HERE/'StrictCompositionProbe.java'),str(HERE/'PolyglotCompositionProbe.java')],check=True)
    subprocess.run(['node',str(HERE/'compare.mjs'),'prepare',str(work)],check=True)
    for mode in ['TITANIUM_STRICT','ERDTMAN_STRICT']:
        output=subprocess.check_output([str(jhome/'bin/java'),'-cp',str(classes)+os.pathsep+cp,'StrictCompositionProbe',mode,str(work/'input.tsv')])
        (work/(mode+'.tsv')).write_bytes(output)
    research_home=work/'research-home';research_home.mkdir(mode=0o700)
    with (work/'polyglot-stderr.log').open('wb') as errors:
        output=subprocess.check_output([str(jhome/'bin/java'),'-Xmx128m','-XX:ActiveProcessorCount=2',
            '-Duser.home='+str(research_home),'-cp',str(classes)+os.pathsep+cp,'PolyglotCompositionProbe',
            str(work/'input.tsv'),str(work/'reference.mjs')],stderr=errors,timeout=60)
    (work/'GRAALJS_STRICT.tsv').write_bytes(output)
    subprocess.run(['node',str(HERE/'compare.mjs'),'assess',str(work)],check=True)
    bindings={str(p.relative_to(work)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(work.rglob('*')) if p.is_file()}
    (work/'executed-input-hashes.json').write_text(json.dumps(bindings,indent=2)+'\n')
    return json.loads((work/'observed-results.json').read_text())


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work-directory',required=True)
    args=parser.parse_args()
    result=run(args.work_directory)
    print(json.dumps({mode:{'shared':value['shared'],'additional':value['additional']} for mode,value in result['modes'].items()}))
