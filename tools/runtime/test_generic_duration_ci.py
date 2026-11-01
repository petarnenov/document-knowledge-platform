"""Actual compiler/CI boundary regression; no Docker/provider or duration execution."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('generic_duration_ci_guard', ROOT / 'tools/runtime/generic-temp-system.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


def normalization_prefix():
    # Execute the exact published shell/Python prefix, not a copied normalizer.
    workflow = (ROOT / '.github/workflows/foundation.yml').read_text()
    step = workflow.split('      - name: Public generic temporary-file Linux duration proof\n', 1)[1]
    block = step.split('        run: |\n', 1)[1].split('      - run:', 1)[0]
    lines = []
    for line in block.splitlines():
        if line.startswith('          /tmp/dkp-runtime-checks/bin/coverage json'):
            break
        if not line.startswith('          '):
            raise AssertionError('Exact CI run block required')
        lines.append(line[10:])
    prefix = '\n'.join(lines) + '\n'
    if not prefix.endswith('export JAVA_HOME\n'):
        raise AssertionError('Compiler home must be exported before launch')
    if "python3 tools/runtime/generic-temp-system.py" not in block:
        raise AssertionError('Required proof invocation missing')
    return prefix


class GenericDurationCiTests(unittest.TestCase):
    def test_original_alias_rejected_but_exact_ci_boundary_uses_identical_pinned_compiler(self):
        real = Path(os.environ['JAVA_HOME']).resolve(strict=True)
        expected = subprocess.run([str(real / 'bin/javac'), '-version'], capture_output=True, check=True)
        self.assertEqual(b'javac 25.0.5\n', expected.stdout)
        with tempfile.TemporaryDirectory() as temp:
            alias = Path(temp).resolve() / 'trusted-setup-java-alias'
            alias.symlink_to(real, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'Canonical'):
                guard.canonical(alias)
            # Same exported JAVA_HOME reaches both compiler selection and child environment.
            probe = "python3 - <<'PROBE'\nimport os,json,subprocess\nfrom pathlib import Path\nh=Path(os.environ['JAVA_HOME']); assert h==h.resolve(strict=True)\np=subprocess.run([str(h/'bin/javac'),'-version'],capture_output=True,check=True)\nprint(json.dumps({'home':str(h),'compiler':p.stdout.decode(),'childHome':subprocess.check_output(['python3','-c','import os;print(os.environ[\"JAVA_HOME\"])']).decode().strip()}))\nPROBE\n"
            result = subprocess.run(['bash', '-e', '-c', normalization_prefix() + probe], env={**os.environ, 'JAVA_HOME':str(alias)}, capture_output=True, text=True, check=True)
            value = json.loads(result.stdout)
            self.assertEqual({'home':str(real),'compiler':expected.stdout.decode(),'childHome':str(real)}, value)
            self.assertEqual(real, guard.canonical(Path(value['home'])))

    def test_nonexistent_relative_and_non_directory_home_fail_before_child_launch(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve(); regular=root/'not-a-directory';regular.write_text('public')
            for candidate in (str(root/'missing'), 'relative-home', str(regular)):
                marker = root/'launched'
                result = subprocess.run(['bash', '-e', '-c', normalization_prefix() + "python3 -c 'from pathlib import Path;Path(\""+str(marker)+"\").touch()'\n"], env={**os.environ,'JAVA_HOME':candidate}, capture_output=True, text=True)
                self.assertNotEqual(0,result.returncode)
                self.assertFalse(marker.exists())
                self.assertEqual('',result.stdout)
                self.assertEqual('Trusted CI compiler home unavailable\n',result.stderr)
                self.assertNotIn(candidate,result.stderr)
