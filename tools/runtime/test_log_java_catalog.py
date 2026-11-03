"""Pure catalog assembly exercised with real javac/ClassFile/JDK metadata, not caught-stack catalogs."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

spec=importlib.util.spec_from_file_location('java_catalog',Path(__file__).with_name('log-java-catalog.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
REPOSITORY=Path(__file__).resolve().parents[2]


class JavaCatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.environment=tempfile.TemporaryDirectory();cls.addClassCleanup(cls.environment.cleanup)
        cls.base=Path(cls.environment.name).resolve();cls.java=Path(os.environ['JAVA_HOME']).resolve()
        cls.helper=cls.base/'helper';cls.helper.mkdir()
        subprocess.run([str(cls.java/'bin/javac'),'-proc:none','-classpath','','-d',str(cls.helper),
            str(REPOSITORY/'tools/runtime/java/org/documentknowledge/loggingbuild/LogJavaClassMetadata.java')],check=True,capture_output=True)
        chosen=json.loads((REPOSITORY/'tools/runtime/log-java-vocabulary.json').read_bytes())
        names=set(chosen['requiredExceptionClasses'])|{'java.lang.Object','java.io.InterruptedIOException','java.net.SocketException',
            'java.nio.file.FileSystemException','java.sql.SQLTransientException'}
        paths=[]
        for name in sorted(names):
            module='java.sql' if name.startswith('java.sql.') else 'java.net.http' if name.startswith('java.net.http.') else 'java.base'
            paths.append('jrt:/'+module+'/'+name.replace('.','/')+'.class')
        metadata=cls.inspect(cls.base,paths)
        cls.hierarchy={row['name']:row['superclass'] for row in metadata['classes']}

    @classmethod
    def inspect(cls,directory,paths):
        index=directory/'metadata-inputs.txt';index.write_text(''.join(str(path)+'\n' for path in paths))
        result=subprocess.run([str(cls.java/'bin/java'),'-cp',str(cls.helper),'org.documentknowledge.loggingbuild.LogJavaClassMetadata',str(index)],
            check=True,capture_output=True,timeout=30)
        return json.loads(result.stdout)

    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name).resolve();self.project=self.root/'services/knowledge-search/bootstrap';self.project.mkdir(parents=True)
        self.sources=self.project/'src/main/java';self.classes=self.project/'build/classes/java/main'
        source=self.sources/'example/ActualFailure.java';source.parent.mkdir(parents=True);self.classes.mkdir(parents=True)
        source.write_text('package example; public class ActualFailure extends java.io.IOException { public void operation() {} public void operation(int attempt) {} static class Nested {} }')
        subprocess.run([str(self.java/'bin/javac'),'-proc:none','-classpath','','-d',str(self.classes),str(source)],check=True,capture_output=True)
        paths=sorted(self.classes.rglob('*.class'));self.metadata=self.inspect(self.project/'build',paths)
        self.jar=self.project/'build/fixture.jar'
        with zipfile.ZipFile(self.jar,'w') as archive:archive.writestr('META-INF/fixture.txt','explicit unit dependency fixture')
        selection=self.root/'tools/runtime/log-java-vocabulary.json';selection.parent.mkdir(parents=True)
        shutil.copyfile(REPOSITORY/'tools/runtime/log-java-vocabulary.json',selection)
        (self.root/'.gitignore').write_text('**/build/\n')
        for args in (['init','-q'],['add','.'],['-c','core.hooksPath=/dev/null','-c','commit.gpgsign=false','-c','user.name=Fixture',
                '-c','user.email=fixture@example.invalid','commit','-qm','explicit metadata unit fixture']):
            subprocess.run(['git','-C',str(self.root),*args],check=True,capture_output=True)
        self.record={'schemaVersion':2,'component':'knowledge-search',
            'toolchain':[{'name':'java','version':'25.0.5+8-LTS','vendorBuildProvenance':'UNKNOWN'}],
            'dependencies':[{'name':'fixture.library','version':'1.0','vendorBuildProvenance':'UNKNOWN'}],
            'compilation':{'projects':[{'project':':services:knowledge-search:bootstrap','sourceRoots':[str(self.sources)],'classRoots':[str(self.classes)]}],
                'classes':[{'project':':services:knowledge-search:bootstrap',**c.original_file(path,8*1024*1024)} for path in paths],
                'artifacts':[{'name':'fixture.library','version':'1.0',**c.original_file(self.jar,64*1024*1024)}]}}

    def assemble(self):return c.assemble(self.root,self.record,self.metadata,self.hierarchy)

    def test_private_reader_extracts_actual_jdk_hierarchy_and_revalidates_original_bytes(self):
        directory=self.project/'build/original';directory.mkdir()
        helper=self.root/'tools/runtime/java/org/documentknowledge/loggingbuild/LogJavaClassMetadata.java'
        helper.parent.mkdir(parents=True);shutil.copyfile(REPOSITORY/'tools/runtime/java/org/documentknowledge/loggingbuild/LogJavaClassMetadata.java',helper)
        subprocess.run(['git','-C',str(self.root),'add','.'],check=True,capture_output=True)
        result=c.extract(self.root,self.record,self.java,directory)
        self.assertIn('example.ActualFailure',result['vocabulary']['exceptionClasses'])
        self.assertIn('java.sql.SQLException',result['vocabulary']['exceptionClasses'])
        self.assertGreater(result['counts']['hierarchyClasses'],27)
        self.assertTrue(any(row['path']==str(self.java/'lib/modules') for row in result['bindings']))
        c.revalidate(result['bindings'])
        path=Path(self.record['compilation']['classes'][0]['path']);path.write_bytes(path.read_bytes()+b'replaced')
        with self.assertRaises(ValueError):c.revalidate(result['bindings'])

    def test_resolved_jar_exception_is_read_without_loading_and_duplicate_class_is_refused(self):
        helper=self.root/'tools/runtime/java/org/documentknowledge/loggingbuild/LogJavaClassMetadata.java'
        helper.parent.mkdir(parents=True);shutil.copyfile(REPOSITORY/'tools/runtime/java/org/documentknowledge/loggingbuild/LogJavaClassMetadata.java',helper)
        selected=self.root/'tools/runtime/log-java-vocabulary.json';value=json.loads(selected.read_bytes())
        value['optionalExceptionClasses']=['fixture.DependencyFailure'];selected.write_text(json.dumps(value))
        source=self.project/'build/DependencyFailure.java'
        source.write_text('package fixture; public class DependencyFailure extends java.io.IOException { static { if (true) throw new Error("NEVER_INITIALIZE"); } }')
        output=self.project/'build/dependency';output.mkdir()
        subprocess.run([str(self.java/'bin/javac'),'-proc:none','-d',str(output),str(source)],check=True,capture_output=True)
        raw=(output/'fixture/DependencyFailure.class').read_bytes()
        with zipfile.ZipFile(self.jar,'w') as archive:archive.writestr('fixture/DependencyFailure.class',raw)
        self.record['compilation']['artifacts'][0].update(c.original_file(self.jar,64*1024*1024))
        subprocess.run(['git','-C',str(self.root),'add','.'],check=True,capture_output=True)
        directory=self.project/'build/with-dependency';directory.mkdir()
        result=c.extract(self.root,self.record,self.java,directory)
        self.assertIn('fixture.DependencyFailure',result['vocabulary']['exceptionClasses'])
        self.assertEqual(0,result['counts']['missingOptionalExceptions'])
        with zipfile.ZipFile(self.jar,'a') as archive:archive.writestr('fixture/DependencyFailure.class',raw)
        self.record['compilation']['artifacts'][0].update(c.original_file(self.jar,64*1024*1024))
        directory=self.project/'build/duplicate-dependency';directory.mkdir()
        with self.assertRaises(ValueError):c.extract(self.root,self.record,self.java,directory)

    def test_actual_compiled_source_inner_overload_exception_and_exact_dependency_values(self):
        result=self.assemble();v=result['vocabulary'];self.assertIn('example.ActualFailure',v['exceptionClasses'])
        self.assertNotIn('example.ActualFailure$Nested',v['exceptionClasses'])
        frames={row['symbol']:row['source'] for row in v['frames']}
        expected='services/knowledge-search/bootstrap/src/main/java/example/ActualFailure.java'
        self.assertEqual(expected,frames['example.ActualFailure.operation']);self.assertEqual(expected,frames['example.ActualFailure$Nested.<init>'])
        self.assertEqual(1,len([row for row in v['frames'] if row['symbol']=='example.ActualFailure.operation']))
        self.assertEqual(self.record['dependencies'][0],next(row for row in v['dependencies'] if row['name']=='fixture.library'))
        self.assertTrue(all(row['vendorBuildProvenance']=='UNKNOWN' for row in v['dependencies']))
        self.assertEqual(5,result['counts']['missingOptionalExceptions']);self.assertEqual(0,result['counts']['missingSources'])

    def test_missing_source_is_visible_and_unknown_catalog_values_are_not_guessed(self):
        first=self.metadata['classes'][0];first['sourceFile']='Unregistered.java'
        result=self.assemble();self.assertEqual(1,result['counts']['missingSources'])
        self.assertFalse(any(row['symbol'].startswith(first['name']+'.') for row in result['vocabulary']['frames']))
        self.assertNotIn(first['name'],result['vocabulary']['exceptionClasses'])

    def test_compiled_input_or_dependency_replacement_rejects(self):
        for row in self.record['compilation']['classes']+[self.record['compilation']['artifacts'][0]]:
            path=Path(row['path']);raw=path.read_bytes();path.write_bytes(raw+b'replaced')
            with self.assertRaisesRegex(ValueError,'LOG_JAVA_CATALOG_INVALID'):self.assemble()
            path.write_bytes(raw)
        self.record['compilation']['artifacts'][0]['version']='other'
        with self.assertRaises(ValueError):self.assemble()

    def test_ambiguous_source_cross_component_paths_and_metadata_identity_reject(self):
        first=copy.deepcopy(self.metadata);self.metadata['classes'][0]['name']='example.Invented'
        with self.assertRaises(ValueError):self.assemble()
        self.metadata=first
        alternate=self.project/'src/main/alternate';target=alternate/'example/ActualFailure.java';target.parent.mkdir(parents=True)
        shutil.copyfile(self.sources/'example/ActualFailure.java',target)
        subprocess.run(['git','-C',str(self.root),'add','.'],check=True,capture_output=True)
        self.record['compilation']['projects'][0]['sourceRoots'].append(str(alternate))
        with self.assertRaises(ValueError):self.assemble()
        self.record['compilation']['projects'][0]['sourceRoots']=[str(self.sources)]
        link=self.project/'src/main/link';link.symlink_to(self.sources,target_is_directory=True)
        self.record['compilation']['projects'][0]['sourceRoots']=[str(link)]
        with self.assertRaises(ValueError):self.assemble()

    def test_missing_or_cyclic_hierarchy_duplicate_records_and_closed_selection_reject(self):
        self.hierarchy=copy.deepcopy(type(self).hierarchy);self.hierarchy['java.io.IOException']='example.ActualFailure'
        with self.assertRaises(ValueError):self.assemble()
        self.hierarchy=copy.deepcopy(type(self).hierarchy);del self.hierarchy['java.io.IOException']
        with self.assertRaises(ValueError):self.assemble()
        self.hierarchy=type(self).hierarchy
        self.record['compilation']['classes'].append(copy.deepcopy(self.record['compilation']['classes'][0]))
        with self.assertRaises(ValueError):self.assemble()
        self.record['compilation']['classes'].pop()
        path=self.root/'tools/runtime/log-java-vocabulary.json';value=json.loads(path.read_bytes());value['extra']='private-content-canary';path.write_text(json.dumps(value))
        with self.assertRaises(ValueError):self.assemble()


if __name__=='__main__':unittest.main()
