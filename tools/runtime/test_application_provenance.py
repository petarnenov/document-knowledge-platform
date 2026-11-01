"""Before-action provenance regressions; never create or contact providers."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

spec=importlib.util.spec_from_file_location('application_provenance',Path(__file__).with_name('application-provenance.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
runtime_spec=importlib.util.spec_from_file_location('provenance_runtime',Path(__file__).with_name('upload-runtime.py'))
r=importlib.util.module_from_spec(runtime_spec);runtime_spec.loader.exec_module(r)


class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name).resolve()
        actual=r.ROOT
        for relative in m.inputs(actual):
            path=self.root/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes((actual/relative).read_bytes())
        self.directory=self.root/'run';self.directory.mkdir(mode=0o700)
        self.state={'runId':'a'*32,'project':'dkp-bp03-'+'a'*12,'directory':str(self.directory)}
        self.runtime=SimpleNamespace(ROOT=self.root,private=r.private,docker=Mock(side_effect=self.docker),compose=Mock(return_value=('b'*64).encode()),owned=Mock())
        self.source=None;self.images={};self.current_service=None

    def docker(self,*args):
        if args[:2]==('image','inspect'):
            service=next(service for service in m.SERVICES if args[2].endswith('-'+service+':local'))
            self.current_service=service;return [self.images[service]]
        expected=self.images[self.current_service]
        return [{'Id':'b'*64,'Image':expected['Id'],'Config':{'Image':'dkp137-'+self.state['runId']+'-'+self.current_service+':local','Labels':{**m.build_labels(self.state,self.source['sourceDigest'],self.current_service),'com.docker.compose.service':self.current_service}}}]

    def captured(self):
        self.source=m.capture(self.runtime,self.state)
        for service in m.SERVICES:self.images[service]={'Id':'sha256:'+'c'*64,'RootFS':{'Layers':['sha256:'+'d'*64]},'Config':{'Labels':m.build_labels(self.state,self.source['sourceDigest'],service)}}
        return self.source

    def test_real_inventory_before_build_is_complete_and_excludes_private_generated_state(self):
        result=m.inputs(self.root)
        self.assertTrue(any('document-management/' in path and '/src/main/' in path for path in result))
        self.assertTrue(any('browser-bff/' in path and '/src/main/' in path for path in result))
        self.assertTrue(any('apps/admin-portal/src/' in path for path in result))
        secret=self.root/'local-data/security/synthetic.key';secret.parent.mkdir(parents=True);secret.write_text('not-a-build-input')
        self.assertEqual(result,m.inputs(self.root))
        runtime_build=self.root/'services/document-management/bootstrap/build';runtime_build.mkdir();(runtime_build/'stale.class').write_text('ignored')
        self.assertEqual(result,m.inputs(self.root))
        cache=self.root/'infra/docker-compose/__pycache__';cache.mkdir();(cache/'generated.pyc').write_bytes(b'ignored')
        (self.root/'apps/admin-portal/.env').write_text('never-a-public-build-input')
        self.assertEqual(result,m.inputs(self.root))
        file=self.root/'gradle.properties';file.unlink();file.symlink_to(self.root/'build.gradle')
        with self.assertRaises(ValueError):m.inputs(self.root)

    def test_fresh_capture_seal_attest_immutable_ids_rootfs_and_run_labels(self):
        self.captured();m.seal(self.runtime,self.state);record=m.attest(self.runtime,self.state)
        self.assertEqual(set(m.SERVICES),set(record['images']))
        self.assertEqual(0o600,(self.directory/m.IMAGE_RECORD).stat().st_mode&0o777)
        for row in record['images'].values():self.assertEqual('sha256:'+'c'*64,row['imageId']);self.assertEqual(['sha256:'+'d'*64],row['rootfs'])
        with self.assertRaises(FileExistsError):m.capture(self.runtime,self.state)
        with self.assertRaises(FileExistsError):m.seal(self.runtime,self.state)

    def test_old_record_has_no_backfill_and_stale_or_forged_prebuilt_tag_cannot_be_sealed(self):
        with self.assertRaises(ValueError):m.attest(self.runtime,self.state)
        self.assertEqual([],list(self.directory.iterdir()))
        self.captured();self.images['document-management']['Config']['Labels']={}
        with self.assertRaises(ValueError):m.seal(self.runtime,self.state)
        self.assertFalse((self.directory/m.IMAGE_RECORD).exists())
        self.images['document-management']['Config']['Labels']=m.build_labels(self.state,self.source['sourceDigest'],'document-management');self.images['document-management']['Config']['Labels']['dkp.application.run']='old'
        with self.assertRaises(ValueError):m.seal(self.runtime,self.state)
        self.images['document-management']['Config']['Labels']=m.build_labels(self.state,self.source['sourceDigest'],'browser-bff')
        with self.assertRaises(ValueError):m.seal(self.runtime,self.state)

    def test_changed_production_source_and_build_input_fail_before_any_image_or_container_access(self):
        for relative in ('services/document-management/application/build.gradle','apps/admin-portal/package.json','build.gradle'):
            with self.subTest(relative=relative):
                self.captured();path=self.root/relative;original=path.read_bytes();path.write_bytes(original+b'\n// changed')
                self.runtime.docker.reset_mock()
                with self.assertRaises(ValueError):m.seal(self.runtime,self.state)
                self.runtime.docker.assert_not_called();path.write_bytes(original);(self.directory/m.INPUT_RECORD).unlink()

    def test_retagged_image_changed_rootfs_or_live_image_rejected_even_when_tag_matches(self):
        self.captured();m.seal(self.runtime,self.state)
        for field,bad in [('Id','sha256:'+'e'*64),('RootFS',{'Layers':['sha256:'+'e'*64]})]:
            original=self.images['document-management'][field];self.images['document-management'][field]=bad
            with self.assertRaises(ValueError):m.attest(self.runtime,self.state)
            self.images['document-management'][field]=original
        original=self.runtime.docker.side_effect
        def wrong(*args):
            rows=original(*args)
            if args[0]=='inspect':rows[0]['Image']='sha256:'+'e'*64
            return rows
        self.runtime.docker.side_effect=wrong
        with self.assertRaises(ValueError):m.attest(self.runtime,self.state)

    def test_closed_records_and_private_material_guards(self):
        self.captured();m.seal(self.runtime,self.state)
        path=self.directory/m.INPUT_RECORD;original=path.read_text()
        for value in ('{}',json.dumps({**self.source,'extra':1}),json.dumps({**self.source,'sourceDigest':'f'*64})):
            path.write_text(value)
            with self.assertRaises(ValueError):m.current(self.runtime,self.state)
        path.write_text(original);path.chmod(0o644)
        with self.assertRaises(ValueError):m.current(self.runtime,self.state)
        path.chmod(0o600)
        record_path=self.directory/m.IMAGE_RECORD;record=json.loads(record_path.read_text());record['images'].pop('browser-bff');record_path.write_text(json.dumps(record))
        with self.assertRaises(ValueError):m.attest(self.runtime,self.state)
        with self.assertRaises(ValueError):m.build_labels(self.state,'not-digest','document-management')


if __name__=='__main__':unittest.main()
