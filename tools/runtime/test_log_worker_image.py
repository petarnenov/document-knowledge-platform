"""Real sealed distribution/JAR fixtures; Docker unit seam is not actual image acceptance."""
import contextlib
import io
import sys
import importlib.util
import json
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch
import test_log_java_build as fixture

spec = importlib.util.spec_from_file_location('worker_image_test', Path(__file__).with_name('log-worker-image.py'))
m = importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class WorkerImageTest(unittest.TestCase):
    def setUp(self):
        local=patch.object(m.r,'local_daemon',return_value=None);local.start();self.addCleanup(local.stop)
        self.fixture=fixture.JavaBuildTest();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        self.fixture.component='structure-inspector';self.fixture.settings['record']['component']='structure-inspector'
        built=self.fixture.build();self.parent=self.fixture.root.parent/'private-image';self.parent.mkdir(mode=0o700)
        self.installation=self.parent/'installed';self.output=self.parent/'image'
        self.installed=m.i.install(self.fixture.root,Path(built['receipt']),self.installation)
        self.image_id='sha256:'+'1'*64;self.container='2'*64;self.labels={};self.commands=[];self.corrupt=False;self.foreign=False

    def prepare(self):return m.prepare(self.installation,self.output)

    def docker(self,*args,**kwargs):
        self.commands.append(args)
        if args[0]=='build':
            self.labels={args[n+1].split('=',1)[0]:args[n+1].split('=',1)[1] for n in range(len(args)) if args[n]=='--label'}
            return b'fixed unit build output'
        if args[0]=='create':return self.container.encode()
        if args[0]=='cp':
            shutil.copytree(self.output/'distribution',Path(args[2]))
            if self.corrupt:(Path(args[2])/'lib/bootstrap.jar').write_bytes(b'tampered')
            return b''
        if args[0]=='rm':self.assertEqual(self.container,args[1]);return b''
        self.fail('unexpected Docker operation')

    def inspect(self,kind,name):
        if kind=='image':return dict(Id=self.image_id,Os='linux',Architecture='amd64',Config=dict(User='10001:10001',Entrypoint=['/app/bin/structure-inspector'],Labels=dict(self.labels)))
        self.assertEqual(self.container,name)
        return dict(Id=self.container,Image=('sha256:'+'9'*64) if self.foreign else self.image_id,Config=dict(Labels=dict(self.labels)),State=dict(Status='created',Running=False))

    def test_prepare_copies_original_private_distribution_and_fixed_recipe_without_recompilation(self):
        record=self.prepare();self.assertEqual(record,m.context(self.output)[0]);self.assertEqual(self.installed,m.i.verify_installed(self.output))
        self.assertEqual((self.installation/'manifest.json').read_bytes(),(self.output/'manifest.json').read_bytes());self.assertEqual(m.RECIPE.read_bytes(),(self.output/'Dockerfile').read_bytes())
        self.assertEqual(m.IGNORE,(self.output/'.dockerignore').read_text());self.assertEqual(0,(self.output/'image-context.json').stat().st_mode&0o077)
        with self.assertRaises(ValueError):self.prepare()

    def test_context_rejects_manifest_recipe_inventory_ignore_or_mode_drift(self):
        self.prepare()
        for name in ('manifest.json','install.json','Dockerfile','.dockerignore','image-context.json','distribution/lib/bootstrap.jar'):
            path=self.output/name;raw=path.read_bytes();path.write_bytes(raw+b'tampered')
            try:
                with self.assertRaises(Exception):m.context(self.output)
            finally:path.write_bytes(raw)
        row=json.loads((self.output/'image-context.json').read_bytes());row['schemaVersion']=True;(self.output/'image-context.json').write_bytes(m.i.m.canonical(row))
        with self.assertRaises(ValueError):m.context(self.output)

    def test_exact_owned_image_bytes_are_checked_before_binding_and_only_probe_is_removed(self):
        self.prepare()
        with patch.object(m.r,'docker',self.docker),patch.object(m.r,'inspect',self.inspect):
            record=m.build(self.output);self.assertEqual(record,m.image(self.output))
            self.assertEqual(self.image_id,record['imageId']);self.assertEqual('REMOVED',record['probeCleanup']);self.assertEqual(record['filesSha256'],record['actualFilesSha256'])
            self.assertEqual(['build','create','cp','rm'],[args[0] for args in self.commands]);self.assertNotIn('start',[args[0] for args in self.commands])
            with self.assertRaises(ValueError):m.build(self.output)
            proof=self.output/'image.json';raw=proof.read_bytes()
            changed=json.loads(raw);changed['schemaVersion']=True;proof.write_bytes(m.i.m.canonical(changed))
            with self.assertRaises(ValueError):m.image(self.output)
            proof.write_bytes(raw)
            actual=self.inspect('image',self.image_id)
            for field,value in [('User','0'),('Entrypoint',['/bin/sh']),('Labels',{})]:
                changed=json.loads(json.dumps(actual));changed['Config'][field]=value
                with patch.object(m.r,'inspect',return_value=changed):
                    with self.assertRaises(ValueError):m.image(self.output)

    def test_build_metadata_failure_never_publishes_success_and_permits_retry(self):
        self.prepare()
        original = self.inspect
        def invalid(kind, name):
            actual = original(kind, name)
            if kind == 'image':actual['Config']['User'] = '0'
            return actual
        with patch.object(m.r, 'docker', self.docker), patch.object(m.r, 'inspect', invalid):
            with self.assertRaises(ValueError):m.build(self.output)
        self.assertFalse((self.output/'image.json').exists())
        self.assertEqual('rm', self.commands[-1][0])
        with patch.object(m.r, 'docker', self.docker), patch.object(m.r, 'inspect', original):
            self.assertEqual('BUILT', m.build(self.output)['result'])

    def test_corrupt_image_copy_removes_owned_probe_and_refuses_success_record(self):
        self.prepare();self.corrupt=True
        with patch.object(m.r,'docker',self.docker),patch.object(m.r,'inspect',self.inspect):
            with self.assertRaises(ValueError):m.build(self.output)
        self.assertEqual('rm',self.commands[-1][0]);self.assertFalse((self.output/'image.json').exists())

    def test_foreign_probe_is_never_started_copied_or_removed(self):
        self.prepare();self.foreign=True
        with patch.object(m.r,'docker',self.docker),patch.object(m.r,'inspect',self.inspect):
            with self.assertRaises(ValueError):m.build(self.output)
        self.assertEqual(['build','create'],[args[0] for args in self.commands]);self.assertFalse((self.output/'image.json').exists())

    def test_cli_prepare_build_verify_and_fixed_failure_use_the_same_original_binding(self):
        captured=io.StringIO()
        def invoke(action):
            args=['log-worker-image.py',action,'--directory',str(self.output)]
            if action=='prepare':args+=['--installation',str(self.installation)]
            with patch.object(sys,'argv',args),contextlib.redirect_stdout(captured):return m.main()
        self.assertEqual(0,invoke('prepare'))
        with patch.object(m.r,'docker',self.docker),patch.object(m.r,'inspect',self.inspect):
            self.assertEqual(0,invoke('build'));self.assertEqual(0,invoke('verify'))
        self.assertEqual(1,invoke('prepare'))
        lines=[json.loads(line) for line in captured.getvalue().splitlines()]
        self.assertTrue(all(line['buildId']==self.installed['buildId'] for line in lines[:3]))
        self.assertEqual({'result':'FAIL','code':'LOG_WORKER_IMAGE_INVALID'},lines[-1]);self.assertNotIn(str(self.parent),captured.getvalue())

    def test_wrong_component_and_unsafe_output_refuse_before_creation(self):
        with self.assertRaises(ValueError):m.prepare(self.installation,self.installation/'nested')
        with self.assertRaises(ValueError):m.prepare(self.installation,Path('relative'))
        original=m.i.verify_installed
        with patch.object(m.i,'verify_installed',side_effect=lambda path:dict(original(path),component='knowledge-search')):
            with self.assertRaises(ValueError):self.prepare()
        self.assertFalse(self.output.exists())


if __name__=='__main__':unittest.main()
