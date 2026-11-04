import hashlib
import importlib.util
import json
import marshal
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('tool_compile_test_subject', Path(__file__).with_name('log-tool-compile.py'))
c = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(c)
ROOT = Path(__file__).resolve().parents[2]


class ToolCompilerTests(unittest.TestCase):
    def test_actual_original_modules_and_every_constant_locator_without_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            output = Path(directory)
            with patch.object(c.platform, 'python_version', return_value='3.14.10'):
                record = c.compile_distribution(ROOT, output)
            self.assertEqual(json.loads((output / 'compiler-record.json').read_bytes()), record)
            self.assertEqual(len(record['modules']), 2)
            for row, (_, source, destination) in zip(record['modules'], c.MODULES):
                raw = (output / destination).read_bytes()
                code = marshal.loads(raw[16:])
                self.assertEqual(row['sourceSha256'], hashlib.sha256((ROOT / source).read_bytes()).hexdigest())
                self.assertEqual(row['compiledSha256'], hashlib.sha256(raw).hexdigest())
                self.assertEqual((output / destination).stat().st_mode & 0o777, 0o600)
                self.assertTrue(row['frames'])
                for frame in row['frames']:
                    actual = code
                    for index in frame['locator']:actual = actual.co_consts[index]
                    self.assertIs(type(actual), types.CodeType)
                    self.assertEqual((actual.co_filename, actual.co_qualname, actual.co_firstlineno),
                        (frame['source'], frame['qualname'], frame['firstLine']))
            with patch.object(c.platform, 'python_version', return_value='3.14.10'):
                with self.assertRaises(ValueError):c.compile_distribution(ROOT, output)

    def test_compiler_does_not_execute_source_or_import_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            source = Path(directory) / 'source';output = Path(directory) / 'out'
            output.mkdir(mode=0o700)
            for _, relative, _ in c.MODULES:
                path = source / relative;path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("def outer():\n def inner():return 1\n return inner\nraise RuntimeError('synthetic-secret-canary')\n")
            with patch.object(c.platform, 'python_version', return_value='3.14.10'):
                record = c.compile_distribution(source, output)
            self.assertTrue(any('<locals>' in f['qualname'] for f in record['modules'][0]['frames']))
            self.assertNotIn('synthetic-secret-canary', repr(record))

    def test_catalog_rejects_wrong_origin_unknown_module_and_invalid_symbols(self):
        module, source, _ = c.MODULES[0]
        code = compile('pass', source, 'exec')
        for wrong, name, path in ((code.replace(co_filename='private.py'), module, source),
                (code, 'UNKNOWN', source), (None, module, source),
                (code.replace(co_qualname='private/secret'), module, source)):
            with self.assertRaises(ValueError):c.code_catalog(wrong, name, path)

    def test_catalog_preserves_equal_distinct_code_and_refuses_shared_or_deep_objects(self):
        module, source, _ = c.MODULES[0]
        leaf = compile('pass', source, 'exec')
        twin = leaf.replace()
        self.assertEqual(leaf, twin)
        parent = leaf.replace(co_consts=(leaf, twin))
        rows = c.code_catalog(parent, module, source)
        self.assertEqual([row['locator'] for row in rows], [[], [0], [1]])
        with self.assertRaises(ValueError):c.code_catalog(leaf.replace(co_consts=(leaf, leaf)), module, source)
        deep = leaf
        for _ in range(33):deep = leaf.replace(co_consts=(deep,))
        with self.assertRaises(ValueError):c.code_catalog(deep, module, source)
        with patch.object(c, 'MAX_FRAMES', 2):
            with self.assertRaises(ValueError):c.code_catalog(parent, module, source)

    def test_rejects_unpinned_runtime_nonprivate_output_source_link_and_bounds(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = str(Path(directory).resolve())
            output = Path(directory) / 'output';output.mkdir(mode=0o700)
            with patch.object(c.platform, 'python_version', return_value='0.0.0'):
                with self.assertRaises(ValueError):c.compile_distribution(ROOT, output)
            with patch.object(c.platform, 'python_version', return_value='3.14.10'):
                output.chmod(0o755)
                with self.assertRaises(ValueError):c.compile_distribution(ROOT, output)
                output.chmod(0o700)
                with patch.object(c, 'MAX_SOURCE', 1):
                    with self.assertRaises(ValueError):c.compile_distribution(ROOT, output)
                source = Path(directory) / 'source';(source / 'tools/runtime').mkdir(parents=True)
                (source / c.MODULES[0][1]).symlink_to(ROOT / c.MODULES[0][1])
                with self.assertRaises(ValueError):c.compile_distribution(source, output)

    def test_cli_fails_closed_and_success_is_separate_from_adoption(self):
        with patch('sys.argv', ['compiler', '--source', '/source', '--output', '/output']):
            with patch.object(c, 'compile_distribution', side_effect=ValueError('synthetic-secret-canary')):
                with patch('builtins.print') as printed:self.assertEqual(c.main(), 1)
                self.assertNotIn('synthetic-secret-canary', str(printed.call_args))
            with patch.object(c, 'compile_distribution', return_value={}):
                with patch('builtins.print'):self.assertEqual(c.main(), 0)


if __name__ == '__main__':unittest.main()
