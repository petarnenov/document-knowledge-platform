"""Real temporary Git repositories and artifacts; these are not application builds."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('log_build_manifest', Path(__file__).with_name('log-build-manifest.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


class BuildManifestTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve() / 'repo'; self.root.mkdir()
        self.git('init', '-q', '-b', 'dev')
        (self.root/'.gitignore').write_text('**/build/\ndist/\n')
        (self.root/'source.txt').write_text('public source\n')
        (self.root/'gradle.lockfile').write_text('test-dependency:1\n')
        source = self.root/'generated-clients/java/Client.java'; source.parent.mkdir(parents=True)
        source.write_text('class Client {}\n')
        self.git('add', '--all'); self.commit()

    def git(self, *args, data=None):
        result = subprocess.run(['git','-C',str(self.root), *args], input=data, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout

    def commit(self):
        self.git('-c','core.hooksPath=/dev/null','-c','commit.gpgsign=false',
            '-c','user.name=DKP test','-c','user.email=fixture@example.invalid','commit','-qm','fixture public inputs')

    def vocabulary(self):
        return {'events':sorted(m.EVENTS), 'phases':['STARTUP'], 'errorCodes':['DEPENDENCY_FAILED'],
            'dependencies':[], 'exceptionClasses':[], 'frames':[]}

    def toolchain(self):
        return [{'name':'java', 'version':'25.0.5_8', 'vendorBuildProvenance':'UNKNOWN'}]

    def finalized(self, prepared=None, vocabulary=None):
        prepared = m.prepare(self.root,'runtime-tools') if prepared is None else prepared
        result = m.finalize(self.root, prepared, self.vocabulary() if vocabulary is None else vocabulary, self.toolchain(), [])
        return prepared, result

    def artifact(self, manifest):
        path = self.root/'build/test/fixture.jar'; path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('META-INF/dkp/build-manifest.json', m.canonical(manifest))
        return path

    def test_exact_rows_canonical_vector_and_actual_tracked_generated_source(self):
        row = {'path':'a', 'sha256':'0'*64, 'bytes':1, 'executable':False}
        expected = ('[{"bytes":1,"executable":false,"path":"a","sha256":"'+'0'*64+'"}]').encode()
        self.assertEqual(expected, m.canonical([row]))
        self.assertEqual(hashlib.sha256(expected).hexdigest(), m.digest([row]))
        result = m.inputs(self.root); rows = []
        for name in ('.gitignore','generated-clients/java/Client.java','gradle.lockfile','source.txt'):
            content = (self.root/name).read_bytes()
            rows.append({'path':name,'sha256':hashlib.sha256(content).hexdigest(),'bytes':len(content),'executable':False})
        self.assertEqual(rows, result['inputs']); self.assertFalse(result['dirty'])
        self.assertEqual(hashlib.sha256(m.canonical(rows)).hexdigest(), result['sourceFingerprint'])

    def test_independent_worktree_locations_same_inputs_and_repeated_compilations_distinct_ids(self):
        other = self.root.parent/'other'
        self.git('worktree','add','--detach',str(other),'HEAD')
        self.assertEqual(m.inputs(self.root), m.inputs(other))
        first = m.prepare(self.root,'runtime-tools'); second = m.prepare(other,'runtime-tools')
        self.assertNotEqual(first['buildId'], second['buildId'])
        self.assertEqual(first['sourceFingerprint'], second['sourceFingerprint'])
        self.assertNotIn('runId', first)
        self.assertNotIn(str(self.root), m.canonical(first).decode())

    def test_dirty_staged_unstaged_modes_and_locks_affect_actual_inputs(self):
        clean = m.inputs(self.root)
        (self.root/'source.txt').write_text('staged source\n'); self.git('add','source.txt')
        staged = m.inputs(self.root); self.assertTrue(staged['dirty'])
        (self.root/'source.txt').write_text('working source\n')
        working = m.inputs(self.root); self.assertTrue(working['dirty'])
        (self.root/'source.txt').chmod(0o755)
        executable = m.inputs(self.root)
        self.assertTrue(next(row for row in executable['inputs'] if row['path']=='source.txt')['executable'])
        (self.root/'gradle.lockfile').write_text('test-dependency:2\n')
        lock = m.inputs(self.root)
        self.assertEqual(5, len({value['sourceFingerprint'] for value in (clean,staged,working,executable,lock)}))
        self.assertEqual(1, len({value['gitSha'] for value in (clean,staged,working,executable,lock)}))

    def test_unicode_path_sorting_is_exact_utf8_bytes(self):
        for name in ('é.txt','z.txt','😀.txt'):
            (self.root/name).write_text('public\n')
        self.git('add','--all')
        paths = [row['path'] for row in m.inputs(self.root)['inputs']]
        self.assertEqual(sorted(paths,key=lambda p:p.encode('utf-8')),paths)
        self.assertIn('é.txt',m.canonical(m.inputs(self.root)['inputs']).decode())

    def test_untracked_source_rejected_outputs_excluded_and_tracked_outputs_rejected(self):
        before = m.inputs(self.root)
        (self.root/'surprise.py').write_text('print(1)\n')
        with self.assertRaises(ValueError): m.prepare(self.root,'runtime-tools')
        (self.root/'surprise.py').unlink()
        path = self.root/'build/generated/dkp-logging/test/manifest.json'; path.parent.mkdir(parents=True)
        path.write_text('{}')
        self.assertEqual(before, m.inputs(self.root))
        self.git('add','--force',str(path))
        with self.assertRaises(ValueError): m.inputs(self.root)

    def test_declared_untracked_output_without_ignore_is_not_source_but_cannot_be_written(self):
        (self.root/'.gitignore').write_text(''); self.git('add','.gitignore')
        path = self.root/'apps/test/dist/output.json'; path.parent.mkdir(parents=True)
        path.write_text('{}'); m.inputs(self.root)
        with self.assertRaises(ValueError): m.write_output(self.root,'test','11111111-1111-4111-8111-111111111111','manifest.json',{'buildId':'11111111-1111-4111-8111-111111111111','component':'test'})

    def test_tracked_symlink_parent_symlink_missing_file_and_unmerged_index_reject(self):
        (self.root/'linked').symlink_to('source.txt'); self.git('add','linked')
        with self.assertRaises(ValueError): m.inputs(self.root)
        self.git('reset','--hard','HEAD')
        original = self.root/'generated-clients'; moved = self.root/'build/moved'
        moved.parent.mkdir(); original.rename(moved); original.symlink_to(moved, target_is_directory=True)
        with self.assertRaises(ValueError): m.inputs(self.root)
        original.unlink(); moved.rename(original)
        (self.root/'source.txt').unlink()
        with self.assertRaises(ValueError): m.inputs(self.root)
        self.git('reset','--hard','HEAD')
        blob = self.git('rev-parse','HEAD:source.txt').decode().strip()
        self.git('update-index','--index-info', data=('0 '+'0'*40+'\tsource.txt\n100644 '+blob+' 1\tsource.txt\n').encode())
        with self.assertRaises(ValueError): m.inputs(self.root)

    def test_non_utf8_control_private_and_unsafe_paths_reject(self):
        for value in ('../private','/absolute','a//b','a/./b','a\\b','a\nb','local-data/value','x/node_modules/value'):
            with self.subTest(value=value), self.assertRaises(ValueError): m.path_name(value)
        with self.assertRaises(UnicodeError): m.path_name(b'bad-\xff')
        blob = self.git('rev-parse','HEAD:source.txt').strip()
        self.git('update-index','-z','--index-info',data=b'100644 '+blob+b'\tbad-\xff\0')
        with self.assertRaises(UnicodeError): m.inputs(self.root)

    def test_real_file_mutation_during_hash_rejects_mixed_snapshot(self):
        original = m.os.fstat; calls = []
        def observed(fd):
            calls.append(fd)
            if len(calls)==2: (self.root/'source.txt').write_text('changed while hashing\n')
            return original(fd)
        with patch.object(m.os,'fstat',side_effect=observed), self.assertRaises(ValueError):
            m.file_hash(self.root/'source.txt')

    def test_original_manifest_embedded_in_artifact_and_separate_output_receipt(self):
        prepared = m.prepare(self.root,'runtime-tools')
        path = m.write_output(self.root,'runtime-tools',prepared['buildId'],'prepared.json',prepared)
        self.assertEqual(0o600, path.stat().st_mode & 0o777)
        before = m.inputs(self.root)
        _, finalized = self.finalized(prepared)
        manifest = finalized['manifest']; raw = m.canonical(manifest)
        self.assertEqual(prepared['buildId'], manifest['buildId'])
        self.assertNotIn('artifact', manifest); self.assertNotIn('manifestSha256', manifest)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), finalized['manifestSha256'])
        self.assertEqual(m.digest(self.toolchain()),manifest['toolchainFingerprint'])
        self.assertEqual(m.digest([]),manifest['dependencyFingerprint'])
        self.assertEqual(self.toolchain(),finalized['toolchainRecord'])
        self.assertEqual([],finalized['dependencyRecord'])
        artifact = self.artifact(manifest); receipt = m.seal(self.root,prepared,finalized,artifact)
        with zipfile.ZipFile(artifact) as archive:
            self.assertEqual(raw,archive.read('META-INF/dkp/build-manifest.json'))
        self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(),receipt['artifact']['sha256'])
        self.assertEqual(len(artifact.read_bytes()),receipt['artifact']['bytes'])
        self.assertEqual(prepared['buildId'],receipt['buildId'])
        m.write_output(self.root,'runtime-tools',prepared['buildId'],'manifest.json',manifest)
        evidence=m.write_output(self.root,'runtime-tools',prepared['buildId'],'finalized.json',finalized)
        self.assertEqual(finalized,json.loads(evidence.read_bytes()))
        m.seal(self.root,prepared,json.loads(evidence.read_bytes()),artifact)
        m.write_output(self.root,'runtime-tools',prepared['buildId'],'receipt.json',receipt)
        self.assertEqual(before,m.inputs(self.root))

    def test_source_head_preparation_and_manifest_changes_reject_before_seal(self):
        prepared, finalized = self.finalized(); artifact = self.artifact(finalized['manifest'])
        altered = copy.deepcopy(finalized); altered['manifest']['buildId'] = '11111111-1111-4111-8111-111111111111'
        with self.assertRaises(ValueError): m.seal(self.root,prepared,altered,artifact)
        altered = copy.deepcopy(finalized); altered['preparationSha256'] = '0'*64
        with self.assertRaises(ValueError): m.seal(self.root,prepared,altered,artifact)
        changed = copy.deepcopy(prepared); changed['dirty'] = not changed['dirty']
        with self.assertRaises(ValueError): self.finalized(changed)
        (self.root/'source.txt').write_text('changed after preparation\n')
        with self.assertRaises(ValueError): self.finalized(prepared)
        with self.assertRaises(ValueError): m.seal(self.root,prepared,finalized,artifact)
        self.git('add','source.txt'); self.commit()
        with self.assertRaises(ValueError): self.finalized(prepared)

    def test_schema_identity_time_canonical_types_and_record_bounds(self):
        prepared = m.prepare(self.root,'runtime-tools')
        for key,value in (('schemaVersion',True),('buildId','00000000-0000-0000-0000-000000000000'),
            ('component','private/source'),('preparedAt','yesterday'),('sourceFingerprintAlgorithm','other')):
            changed = copy.deepcopy(prepared); changed[key]=value
            with self.subTest(key=key), self.assertRaises(ValueError): self.finalized(changed)
        with self.assertRaises(ValueError): m.finalize(self.root,prepared,self.vocabulary(),self.toolchain(),[],'1970-01-01T00:00:00Z')
        for value in (1.5,9007199254740992,{'é':'value'},[None]*0+[{'x':float('nan')}]):
            with self.subTest(value=value), self.assertRaises(ValueError): m.canonical(value)
        self.assertEqual(b'[null,true,-1]',m.canonical([None,True,-1]))
        nested = []
        for _ in range(18): nested=[nested]
        with self.assertRaises(ValueError): m.canonical(nested)

    def test_reviewed_vocabulary_and_actual_records_are_bounded_and_strict(self):
        valid = self.vocabulary()
        valid['frames']=[{'symbol':'org.example.App.start','source':'src/App.java'}]
        valid['exceptionClasses']=['java.io.IOException']
        dependency={'name':'postgres-jdbc','version':'42.7.14','vendorBuildProvenance':'UNKNOWN','imageDigest':'sha256:'+'a'*64}
        valid['dependencies']=[dependency]
        prepared = m.prepare(self.root,'runtime-tools')
        m.finalize(self.root,prepared,valid,self.toolchain(),[dependency])
        for key,value in (('events',[]),('phases',['STARTUP','STARTUP']),('errorCodes',['secret input']),
            ('exceptionClasses',['message: password']),('frames',[{'symbol':'App','source':'../private'}]),
            ('dependencies',[{'name':'x','version':'1','vendorBuildProvenance':'invented'}])):
            bad=self.vocabulary();bad[key]=value
            with self.subTest(key=key), self.assertRaises(ValueError): self.finalized(vocabulary=bad)
        for bad in ([dependency,dependency],[dict(dependency,imageDigest='changed')],
            [dict(dependency,extra=True)], [dict(dependency,version='raw credentials secret')]):
            with self.assertRaises(ValueError): m.dependencies(bad)
        with self.assertRaises(ValueError): m.finalize(self.root,prepared,valid,self.toolchain(),[])
        with self.assertRaises(ValueError): m.finalize(self.root,prepared,self.vocabulary(),[],[])
        for frames in ([{'symbol':'App','source':'/private'}], [{'symbol':'App','source':'src/App.java'}]*2):
            bad=self.vocabulary();bad['frames']=frames
            with self.assertRaises(ValueError): m.vocabulary(bad)
        oversized=self.vocabulary();oversized['frames']=[{'symbol':'App'+str(i),'source':'src/'+'x'*200+str(i)+'.java'} for i in range(6000)]
        with self.assertRaises(ValueError): self.finalized(vocabulary=oversized)

    def test_output_rejects_source_mutation_unknown_names_existing_files_and_symlinks(self):
        prepared=m.prepare(self.root,'runtime-tools')
        value={'buildId':prepared['buildId'],'component':'runtime-tools'}
        inventory=m.write_output(self.root,'runtime-tools',prepared['buildId'],'distribution.json',value)
        self.assertEqual(value,json.loads(inventory.read_text()));self.assertEqual(0,inventory.stat().st_mode & 0o077)
        with self.assertRaises(ValueError): m.write_output(self.root,'runtime-tools',prepared['buildId'],'manifest.json',[])
        for name in ('source.txt','../manifest.json'):
            with self.assertRaises(ValueError): m.write_output(self.root,'runtime-tools',prepared['buildId'],name,value)
        path=m.write_output(self.root,'runtime-tools',prepared['buildId'],'manifest.json',value)
        with self.assertRaises(FileExistsError): m.write_output(self.root,'runtime-tools',prepared['buildId'],'manifest.json',value)
        path.unlink();path.symlink_to(self.root/'source.txt')
        with self.assertRaises(ValueError): m.write_output(self.root,'runtime-tools',prepared['buildId'],'manifest.json',value)
        path.unlink();directory=path.parent;directory.rename(directory.with_name('moved'));directory.symlink_to(directory.with_name('moved'))
        with self.assertRaises(ValueError): m.write_output(self.root,'runtime-tools',prepared['buildId'],'receipt.json',value)

    def test_artifact_source_or_link_cannot_be_sealed_as_generated_output(self):
        prepared,finalized=self.finalized()
        with self.assertRaises(ValueError): m.seal(self.root,prepared,finalized,self.root/'source.txt')
        path=self.root/'build/linked.jar';path.parent.mkdir();path.symlink_to(self.root/'source.txt')
        with self.assertRaises(ValueError): m.seal(self.root,prepared,finalized,path)

    def test_all_existing_component_output_shapes_reject_tracked_outputs_and_worker_seal_works(self):
        for name in ('workers/structure-inspector/build/out.class','generated-clients/java/build/out.jar','architecture-tests/build/out.class'):
            path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('fixture output')
            self.git('add','--force',name)
            with self.assertRaises(ValueError): m.inputs(self.root)
            self.git('reset','--',name)
        prepared,finalized=self.finalized()
        artifact=self.root/'workers/structure-inspector/build/fixture.jar';artifact.write_bytes(b'fixture artifact')
        self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(),m.seal(self.root,prepared,finalized,artifact)['artifact']['sha256'])

    def test_retained_records_are_independent_and_tampering_cannot_change_finalized_fingerprints(self):
        prepared=m.prepare(self.root,'runtime-tools');tools=self.toolchain()
        finalized=m.finalize(self.root,prepared,self.vocabulary(),tools,[])
        artifact=self.artifact(finalized['manifest']);tools[0]['version']='changed'
        self.assertEqual('25.0.5_8',finalized['toolchainRecord'][0]['version'])
        for key in ('toolchainRecord','dependencyRecord'):
            altered=copy.deepcopy(finalized);altered[key]=self.toolchain() if key=='dependencyRecord' else []
            with self.assertRaises(ValueError): m.seal(self.root,prepared,altered,artifact)
        second=m.prepare(self.root,'runtime-tools')
        first_path=m.write_output(self.root,'runtime-tools',prepared['buildId'],'prepared.json',prepared)
        second_path=m.write_output(self.root,'runtime-tools',second['buildId'],'prepared.json',second)
        self.assertNotEqual(first_path,second_path)

    def test_initial_vocabulary_matches_actual_reviewed_java_enum(self):
        source=Path(__file__).resolve().parents[2]/'modules/observability-support/src/main/java/org/documentknowledge/observability/EventCode.java'
        self.assertEqual(m.EVENTS,set(re.findall(r'\("([a-z.]+)"\)',source.read_text())))


if __name__ == '__main__': unittest.main()
