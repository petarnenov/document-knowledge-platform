import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import sys
import xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parent))
spec=importlib.util.spec_from_file_location('java_dm_linux',Path(__file__).with_name('java-dm-linux.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class JavaDmLinuxTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve();self.reports=self.root/'reports';self.report_fixture(self.reports)
 def report_fixture(self,directory):
  for layer in m.LAYERS:
   folder=directory/layer/'test-results'/'test';folder.mkdir(parents=True)
   skipped=m.EXPECTED_SKIPS if layer=='adapters-out' else set()
   root=ET.Element('testsuite',tests=str(1+len(skipped)),failures='0',errors='0',skipped=str(len(skipped)))
   ET.SubElement(root,'testcase',name='positive()',classname='Positive')
   for name in skipped:ET.SubElement(ET.SubElement(root,'testcase',name=name,classname='org.fixture.RustFsUploadStorageIntegrationTest'),'skipped')
   ET.ElementTree(root).write(folder/'TEST-example.xml')
  for layer,name in [('bootstrap','service'),('adapters-out','persistence')]:
   folder=directory/layer/'reports'/'jacoco'/(name+'CoverageReport');folder.mkdir(parents=True)
   (folder/(name+'CoverageReport.xml')).write_text('<report><counter type="LINE" covered="75" missed="25"/><counter type="BRANCH" covered="80" missed="20"/></report>')
 def test_results_exact_ordinary_profile_and_floors(self):
  r=m.results(self.reports);self.assertEqual(7,r['counts']['skipped']);self.assertFalse(r['nativeAcceptance']);self.assertEqual(12,r['counts']['tests'])
  p=self.reports/'bootstrap/reports/jacoco/serviceCoverageReport/serviceCoverageReport.xml';p.write_text(p.read_text().replace('covered="75"','covered="74"'))
  with self.assertRaises(ValueError):m.results(self.reports)
 def test_missing_failed_wrong_duplicate_or_partial_skip_rejected(self):
  p=self.reports/'adapters-out/test-results/test/TEST-example.xml';original=p.read_bytes()
  for transform in [lambda r:r.set('failures','1'),lambda r:r.set('errors','1'),lambda r:r.set('skipped','6'),lambda r:r.findall('testcase')[1].set('classname','Other'),lambda r:r.findall('testcase')[2].set('name',r.findall('testcase')[1].get('name'))]:
   r=ET.fromstring(original);transform(r);ET.ElementTree(r).write(p)
   with self.assertRaises(ValueError):m.results(self.reports)
  p.unlink()
  with self.assertRaises(ValueError):m.results(self.reports)
 def test_owned_guard_never_adopts_wrong_container(self):
  valid={'Id':'a'*64,'Image':'sha256:'+'b'*64,'Config':{'Labels':{'dkp.check.run':'run','dkp.issue':'431'}}}
  with mock.patch.object(m,'inspect',return_value=valid):self.assertEqual(valid,m.owned('a'*64,'run','sha256:'+'b'*64))
  for args in [('x','run','sha256:'+'b'*64),('a'*64,'wrong','sha256:'+'b'*64),('a'*64,'run','wrong')]:
   with mock.patch.object(m,'inspect',return_value=valid),self.assertRaises(ValueError):m.owned(*args)
 def fake_run(self,failure=None):
  evidence=self.root/'evidence';source=self.root/'source';source.mkdir(exist_ok=True)
  import socket
  sock=socket.socket(socket.AF_UNIX);sock.bind(str(self.root/'fixture.sock'));self.addCleanup(sock.close)
  image={'Id':'sha256:'+'b'*64,'Os':'linux','Architecture':'amd64','Config':{'Labels':{'dkp.check.run':'1'*32}}}
  calls=[];snapshot={'source':'bound'}
  def cp(args):
   calls.append(args)
   if args[:3]==['docker','context','inspect']:return json.dumps('unix://'+str(self.root/'fixture.sock')).encode()
   if args[:2]==['docker','create']:return ('a'*64).encode()
   if args[:2]==['docker','rm'] and failure=='cleanup':raise RuntimeError('PRIVATE_SENTINEL')
   if args[:2]==['docker','cp']:
    layer=args[2].split('/')[4];what=args[2].split('/')[-1]
    import shutil;shutil.copytree(self.reports/layer/what,Path(args[3])/what)
   return b''
  def logged(args,path,timeout):
   path.write_bytes(b'openjdk version "25.0.5"\n')
   return int(failure==('build' if args[1]=='build' else 'check'))
  def take_snapshot(src,destination=None):
   if destination:(destination/'public').mkdir()
   return snapshot
  state={'State':{'Running':False,'ExitCode':0}}
  with mock.patch.object(m.common,'local_daemon'),mock.patch.object(m.common,'snapshot',side_effect=take_snapshot),mock.patch.object(m.common,'logged',side_effect=logged),mock.patch.object(m,'call',side_effect=cp),mock.patch.object(m,'inspect',return_value=image),mock.patch.object(m,'owned',return_value=state),mock.patch.object(m,'socket_group',return_value=4242,create=True),mock.patch.object(m.uuid,'uuid4',return_value=mock.Mock(hex='1'*32)):
   if failure and failure!='cleanup':
    with self.assertRaises(ValueError):m.run(source,evidence)
    result=json.loads((evidence/'receipt.json').read_text())
   else:
    result=m.run(source,evidence)
    if failure!='cleanup':self.assertEqual('PASS',m.validate(source,evidence)['result'])
  return result,calls,evidence,source
 def test_run_and_validate_exact_socket_contract_and_cleanup(self):
  result,calls,evidence,source=self.fake_run();self.assertEqual('PASS',result['result']);self.assertEqual('REMOVED',result['cleanup'])
  create=next(c for c in calls if c[:2]==['docker','create']);self.assertIn('type=bind,src='+str(self.root/'fixture.sock')+',dst=/var/run/docker.sock',create);self.assertEqual('4242',create[create.index('--group-add')+1]);self.assertIn('--cap-drop',create);self.assertNotIn('--privileged',create);self.assertEqual(1,create.count('--mount'))
  self.assertEqual(['docker','rm','--force','a'*64],calls[-1])
  with mock.patch.object(m.common,'snapshot',return_value={'source':'bound'}):
   (evidence/'check.private.log').write_bytes(b'changed')
   with self.assertRaises(ValueError):m.validate(source,evidence)
 def test_build_and_check_failures_remain_failed(self):
  for failure in ('build','check'):
   with self.subTest(failure=failure):
    result,calls,evidence,_=self.fake_run(failure);self.assertEqual('FAIL',result['result']);self.assertEqual('NOT_CREATED' if failure=='build' else 'REMOVED',result['cleanup'])
    import shutil;shutil.rmtree(evidence);(self.root/'fixture.sock').unlink()
 def test_socket_group_uses_actual_metadata_and_rejects_non_socket(self):
  import stat
  item=mock.Mock();item.stat.return_value=mock.Mock(st_mode=stat.S_IFSOCK|0o660,st_gid=4242)
  self.assertEqual(4242,m.socket_group(item));item.stat.return_value.st_mode=stat.S_IFREG|0o660
  with self.assertRaises(ValueError):m.socket_group(item)
 def test_cleanup_unknown_retains_failure_and_cli_nonzero(self):
  result,_,evidence,_=self.fake_run('cleanup');self.assertEqual('FAIL',result['result']);self.assertEqual('UNKNOWN',result['cleanup'])
  self.assertEqual('FAIL',json.loads((evidence/'receipt.json').read_text())['result'])
  import io
  output=io.StringIO()
  with mock.patch.object(sys,'argv',['java-dm-linux.py','run','--source',str(self.root),'--evidence',str(evidence)]),mock.patch.object(m,'run',return_value=result),mock.patch('sys.stdout',output),self.assertRaises(SystemExit) as failed:m.main()
  self.assertEqual(1,failed.exception.code);self.assertEqual({'profile':'java-dm-linux-real-pg','result':'FAIL'},json.loads(output.getvalue()));self.assertNotIn('PRIVATE_SENTINEL',output.getvalue())
 def test_no_existing_evidence_or_argument_injection(self):
  with self.assertRaises(ValueError):m.run(self.root,self.root)
  with mock.patch.object(sys,'argv',['java-dm-linux.py','run','--source',str(self.root),'--evidence',str(self.root),'--skip-tests']),self.assertRaises(SystemExit):m.main()
 def test_ci_uses_exact_same_entrypoint_and_preserves_other_jobs(self):
  workflow=Path(__file__).parents[2]/'.github/workflows/foundation.yml';text=workflow.read_text()
  self.assertIn("if: matrix.service != 'document-management'",text);self.assertIn('python3 tools/ci/java-dm-linux.py run --source "$GITHUB_WORKSPACE"',text);self.assertIn('python3 tools/ci/java-dm-linux.py validate --source "$GITHUB_WORKSPACE"',text)
  shell=Path(__file__).with_name('java-dm-check.sh').read_text()
  for command in m.COMMANDS:self.assertIn(' '.join(command),shell)
if __name__=='__main__':unittest.main()
