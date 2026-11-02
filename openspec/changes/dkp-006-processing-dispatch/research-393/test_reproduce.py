"""Research-runner negative integrity controls; no providers or production inputs."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('research393',Path(__file__).with_name('reproduce.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class ResearchReproductionTests(unittest.TestCase):
    def test_saved_file_provenance_has_explicit_lossless_hash_fields(self):
        bindings=json.loads(Path(__file__).with_name('execution-bindings.json').read_text())
        rows=bindings['files'];self.assertIsInstance(rows,list)
        paths={}
        for row in rows:
            self.assertEqual({'path','sha256'},set(row))
            self.assertRegex(row['sha256'],r'^[a-f0-9]{64}$')
            self.assertNotIn(row['path'],paths)
            paths[row['path']]=row['sha256']
        artifacts=bindings['artifacts'];self.assertEqual(56,len(artifacts))
        for artifact in artifacts:self.assertEqual(artifact['sha256'],paths[artifact['path']])
        js_poms=[path for path in paths if path.endswith('/js-25.4.4.1.1.pom')]
        self.assertEqual(2,len(js_poms));self.assertNotEqual(paths[js_poms[0]],paths[js_poms[1]])
        for profile,output in bindings['outputs'].items():
            self.assertEqual(83,output['rowCount'])
            self.assertEqual(output['sha256'],paths[profile+'.tsv'])

    def test_only_fresh_canonical_directory_can_start_artifact_or_compiler_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();alias=root/'alias';alias.symlink_to(root,target_is_directory=True)
            for work in ('relative',str(root),str(alias/'fresh')):
                with patch.object(m.subprocess,'run') as command,self.assertRaises(ValueError):m.run(work)
                command.assert_not_called()

    def test_exact_primary_artifact_bytes_are_checked_not_only_download_success(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory).resolve()/'public.jar';content=b'public-test-artifact';good={'url':'https://repo.maven.apache.org/maven2/research/public.jar','sha256':hashlib.sha256(content).hexdigest()}
            def download(*args,**kwargs):target.write_bytes(content)
            with patch.object(m.subprocess,'run',side_effect=download):m.fetch(good,target)
            with patch.object(m.subprocess,'run',side_effect=download),self.assertRaises(ValueError):m.fetch({**good,'sha256':'0'*64},target)
            with patch.object(m.subprocess,'run') as command,self.assertRaises(ValueError):m.fetch({**good,'url':'http://repo.maven.apache.org/research.jar'},target)
            command.assert_not_called()

    def test_distinct_actual_js_coordinates_with_identical_pom_names_retain_both_inputs(self):
        # Real pinned coordinate/URL collision; synthetic POM bytes keep this unit offline.
        records = [x for x in json.loads(m.PINS.read_text())['artifacts']
                   if x['coordinate'] in ('org.graalvm.polyglot:js:25.4.4.1.1', 'org.graalvm.js:js:25.4.4.1.1')]
        self.assertEqual(2, len(records))
        fixtures = {}
        for record in records:
            record['artifacts'] = [x for x in record['artifacts'] if x['url'].endswith('.pom')]
            for item in record['artifacts']:
                data = ('<project>' + record['coordinate'] + '</project>').encode()
                fixtures[item['url']] = data
                item['sha256'] = hashlib.sha256(data).hexdigest()
        self.assertEqual(1, len({url.rsplit('/', 1)[1] for url in fixtures}))
        class StopAfterArtifacts(Exception):
            pass
        def download(arguments, **kwargs):
            position = arguments.index('-o')
            Path(arguments[position + 1]).write_bytes(fixtures[arguments[position - 1]])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            pins = root / 'pins.json'
            pins.write_text(json.dumps({'artifacts': records}))
            with patch.object(m, 'PINS', pins), patch.object(m.subprocess, 'run', side_effect=download), patch.object(m.subprocess, 'check_output', side_effect=StopAfterArtifacts):
                with self.assertRaises(StopAfterArtifacts):
                    m.run(root / 'fresh')
            retained = list((root / 'fresh').rglob('*.pom'))
            self.assertEqual(2, len(retained))
            self.assertEqual(set(fixtures.values()), {path.read_bytes() for path in retained})
