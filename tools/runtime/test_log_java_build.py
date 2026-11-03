"""Real Git/JAR orchestration fixtures; compiler identity is proven separately with actual Gradle."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import shlex
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('log_java_build', Path(__file__).with_name('log-java-build.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


class JavaBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()/'source'; self.root.mkdir()
        self.java = self.root.parent/'jdk'; (self.java/'bin').mkdir(parents=True)
        for name in ('java','javac'): (self.java/'bin'/name).write_text('fixture only')
        (self.root/'.gitignore').write_text('**/build/\n')
        (self.root/'source.txt').write_text('original')
        wrapper = self.root/'gradle/wrapper/gradle-wrapper.properties'; wrapper.parent.mkdir(parents=True)
        wrapper.write_text('distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.2-bin.zip\n')
        self.settings = {'record':{'schemaVersion':2,'component':'knowledge-search',
            'toolchain':[{'name':'java','version':'25.0.5+8-LTS','vendorBuildProvenance':'UNKNOWN'},
                         {'name':'gradle','version':'9.8.2','vendorBuildProvenance':'UNKNOWN'}],
            'dependencies':[{'name':'fixture.library','version':'1.0','vendorBuildProvenance':'UNKNOWN'}], 'compilation':{}}}
        # Orchestration fixture only; real javac extraction is covered by test_log_java_catalog.
        vocabulary={'events':sorted(m.m.EVENTS),'phases':['STARTUP','SHUTDOWN'],'errorCodes':[],
            'dependencies':[],'exceptionClasses':[],'frames':[]}
        self.extract_patch=patch.object(m.catalog,'extract',return_value={'vocabulary':vocabulary,'bindings':[], 'counts':{}})
        self.extract_patch.start();self.addCleanup(self.extract_patch.stop)
        self.save()
        script = self.root/'gradlew'
        script.write_text('#!/bin/sh\nexec '+shlex.quote(sys.executable)+' fixture-gradle.py "$@"\n')
        (self.root/'fixture-gradle.py').write_text('''import json,sys,zipfile,shutil
from pathlib import Path
root=Path.cwd();cfg=json.loads((root/'fixture.json').read_text())
directory=Path(next(a.split('=',1)[1] for a in sys.argv if a.startswith('-PdkpLogBuildDirectory=')))
if any(a.endswith(':compileJava') for a in sys.argv):
 if cfg.get('exit'):sys.exit(2)
 if cfg.get('mutate'):(root/'source.txt').write_text('changed during compilation')
 target=directory/'build-record.json'
 if cfg.get('symlink'):target.symlink_to(root/'source.txt')
 else:target.write_text(cfg.get('raw',json.dumps(cfg['record'])));target.chmod(0o600)
else:
 if cfg.get('packageExit'):sys.exit(3)
 output=root/'services/knowledge-search/bootstrap/build/libs';output.mkdir(parents=True,exist_ok=True)
 with zipfile.ZipFile(output/'bootstrap.jar','w') as archive:
  name='META-INF/dkp-logging/manifest.json'
  if cfg.get('jar')!='missing':archive.writestr(name, b'wrong' if cfg.get('jar')=='wrong' else (directory/'manifest.json').read_bytes())
  if cfg.get('jar')=='duplicate':archive.writestr(name,b'duplicate')
 dist=root/'services/knowledge-search/bootstrap/build/install/knowledge-search'
 (dist/'bin').mkdir(parents=True,exist_ok=True);(dist/'lib').mkdir(exist_ok=True)
 (dist/'bin/knowledge-search').write_text('#!/bin/sh\\n# simulated fixture only\\n')
 (dist/'bin/knowledge-search.bat').write_text('@rem simulated fixture only\\n')
 shutil.copyfile(output/'bootstrap.jar',dist/'lib/bootstrap.jar')
 (dist/'lib/fixture-library.jar').write_bytes(b'fixture dependency bytes')
'''); script.chmod(0o755)
        for command in (['init','-q'],['add','.'],['-c','core.hooksPath=/dev/null','-c','commit.gpgsign=false',
                '-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture']):
            subprocess.run(['git','-C',str(self.root),*command],check=True,capture_output=True)

    def save(self): (self.root/'fixture.json').write_text(json.dumps(self.settings))
    def build(self): self.save(); return m.build(self.root,'knowledge-search',self.java)

    def test_actual_manifest_embedded_and_sealed_with_distinct_original_compilations(self):
        first=self.build();second=self.build()
        self.assertNotEqual(first['buildId'],second['buildId'])
        a=json.loads(Path(first['manifest']).read_text());b=json.loads(Path(second['manifest']).read_text())
        self.assertEqual(a['sourceFingerprint'],b['sourceFingerprint'])
        self.assertEqual('25.0.5+8-LTS',json.loads(Path(first['manifest']).with_name('finalized.json').read_text())['toolchainRecord'][0]['version'])
        self.assertEqual(first['manifestSha256'],json.loads(Path(first['receipt']).read_text())['manifestSha256'])
        dist=json.loads(Path(first['receipt']).with_name('distribution.json').read_text())
        self.assertEqual(first['buildId'],dist['buildId']);self.assertIn('lib/fixture-library.jar',dist['files'])
        self.assertNotIn('runId',a); self.assertEqual('NOT_STARTED',first['sharedStoreDelivery'])
        for path in Path(first['manifest']).parent.iterdir(): self.assertEqual(0,path.stat().st_mode & 0o077)

    def test_compile_and_package_failure_do_not_seal_success(self):
        for flag in ('exit','packageExit'):
            with self.subTest(flag=flag):
                self.settings[flag]=True
                with self.assertRaises(ValueError): self.build()
                del self.settings[flag]
        self.assertEqual([],list((self.root/'build').rglob('receipt.json')))

    def test_source_change_during_compilation_refuses_finalization(self):
        self.settings['mutate']=True
        with self.assertRaises(ValueError):self.build()
        self.assertEqual([],list((self.root/'build').rglob('manifest.json')))

    def test_duplicate_extra_wrong_component_and_toolchain_records_reject(self):
        original=json.loads(json.dumps(self.settings))
        cases=[{'raw':'{"schemaVersion":1,"schemaVersion":1}'}, {'symlink':True},
            {'record':{**original['record'],'component':'browser-bff'}},
            {'record':{**original['record'],'extra':'untrusted'}},
            {'record':{**original['record'],'schemaVersion':True}},
            {'record':{**original['record'],'toolchain':[]}},
            {'record':{**original['record'],'toolchain':[{'name':'java','version':'21','vendorBuildProvenance':'UNKNOWN'},original['record']['toolchain'][1]]}},
            {'record':{**original['record'],'toolchain':[original['record']['toolchain'][0],{'name':'gradle','version':'9.9','vendorBuildProvenance':'UNKNOWN'}]}}]
        for case in cases:
            with self.subTest(case=case):
                self.settings={**original,**case}
                with self.assertRaises(ValueError):self.build()

    def test_missing_wrong_or_duplicate_embedded_manifest_rejects(self):
        for value in ('missing','wrong','duplicate'):
            with self.subTest(value=value):
                self.settings['jar']=value
                with self.assertRaises(ValueError):self.build()

    def test_untrusted_component_relative_paths_and_missing_toolchain_reject(self):
        for root,component,java in ((self.root,'unregistered',self.java),(Path('.'),'knowledge-search',self.java),
                (self.root,'knowledge-search',self.root)):
            with self.subTest(component=component):
                with self.assertRaises(ValueError):m.build(root,component,java)

    def test_cli_success_and_fixed_failure_have_no_raw_exception_output(self):
        args=['log-java-build.py','--source',str(self.root),'--component','knowledge-search','--java-home',str(self.java)]
        for fail in (False,True):
            self.settings['exit']=fail;self.save()
            stream=io.StringIO()
            with patch.object(sys,'argv',args),contextlib.redirect_stdout(stream):status=m.main()
            value=json.loads(stream.getvalue());self.assertEqual(int(fail),status)
            self.assertEqual('FAIL' if fail else 'BUILT',value['result'])
            if fail:self.assertEqual({'result':'FAIL','code':'LOG_JAVA_BUILD_INVALID'},value)


if __name__ == '__main__':unittest.main()
