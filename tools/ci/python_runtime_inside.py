"""Only the finite Python runtime profile; runs inside the isolated pinned image."""
import json
import platform
import os
import subprocess
import sys
from pathlib import Path

HELPERS=('clam-definitions.py','rustfs-runtime.py','upload-runtime.py','upload-durability.py','upload-accounting-acceptance.py','upload-acceptance.py','upload-faults.py')
FEATURE_HELPERS=('provider-jobs.py','application-provenance.py','provider-job-control-acceptance.py')
RUNNER_HELPERS=('verify.py','python_runtime_inside.py')
COMMANDS=[['python','-m','coverage','run','--branch','--data-file=/tmp/runner.coverage','-m','unittest','discover','-s','/opt/dkp','-p','test_verify.py'],
          ['python','-m','coverage','json','--data-file=/tmp/runner.coverage','-o','/tmp/runner-coverage.json'],
          ['python','-m','coverage','run','--branch','-m','unittest','discover','-s','tools/runtime','-p','test_*.py'],
          ['python','-m','coverage','run','--branch','--append','-m','unittest','discover','-s','infra/docker-compose','-p','test_*.py'],
          ['python','-m','coverage','report','--include=*/smoke.py,*/bootstrap-security.py,*/identity-provider-check.py,*/document-database-check.py','--fail-under=75'],
          ['python','-m','coverage','json','-o','/tmp/coverage.json']]

def helper_names(source=Path('.')):
    return HELPERS+tuple(name for name in FEATURE_HELPERS if (source/'tools/runtime'/name).is_file())

def floors(report,source=Path('.')):
    return selected_floors(report,helper_names(source))

def selected_floors(report,names):
    result={}
    for name in names:
        found=[v['summary'] for p,v in report['files'].items() if p.endswith('/'+name)]
        if len(found)!=1:raise ValueError('Required helper coverage absent')
        s=found[0]
        if s['num_statements']<=0 or s['num_branches']<=0 or s['covered_lines']/s['num_statements']<.75 or s['covered_branches']/s['num_branches']<.75:
            raise ValueError('Required helper coverage below floor')
        result[name]={key:s[key] for key in ('covered_lines','num_statements','covered_branches','num_branches')}
    return result

def main():
    import coverage
    record={'schemaVersion':1,'result':'FAIL','stage':'VERSIONS','python':platform.python_version(),'coverage':coverage.__version__,'coverageCore':os.environ.get('COVERAGE_CORE'),'java':'','commands':[],'helpers':{},'runnerHelpers':{}}
    try:
        version=subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,timeout=10).decode().splitlines()
        record['java']=next(line for line in version if line.startswith('openjdk version '))
        if record['python']!='3.14.10' or record['coverage']!='7.17.0' or record['coverageCore']!='ctrace' or '25.0.5' not in record['java']:raise ValueError('Pinned versions required')
        for command in COMMANDS:
            record['stage']='COMMAND_'+str(len(record['commands'])+1);record['commands'].append(command)
            subprocess.run(command,check=True,timeout=180)
        record['stage']='FLOORS';record['helpers']=floors(json.loads(Path('/tmp/coverage.json').read_text()))
        record['runnerHelpers']=selected_floors(json.loads(Path('/tmp/runner-coverage.json').read_text()),RUNNER_HELPERS)
        record.update(result='PASS',stage='COMPLETE')
    except Exception:
        pass # Raw test output is retained in the private host log; receipt fields are finite.
    print('DKP_PYTHON_RECEIPT\t'+json.dumps(record,separators=(',',':')),flush=True)
    return 0 if record['result']=='PASS' else 1

if __name__=='__main__':sys.exit(main())
