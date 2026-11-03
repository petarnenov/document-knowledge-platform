"""Positive and negative checks for the SDK's dependency direction."""
import ast
from pathlib import Path
import unittest


ALLOWED = {
    '__init__': {'.wire'},
    'wire': {'datetime', 'json', 're', 'uuid', 'dataclasses'},
    'producer': {'threading', '.wire'},
    'https': {'hashlib', 'http.client', 'ipaddress', 'socket', 'ssl', 'threading', 'time', 'urllib.parse', '.wire'},
}


def check(name, source):
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name not in ALLOWED[name]:
                    raise ValueError('SDK_DEPENDENCY_BOUNDARY')
        elif isinstance(node, ast.ImportFrom):
            module = '.' * node.level + (node.module or '')
            if module not in ALLOWED[name]:
                raise ValueError('SDK_DEPENDENCY_BOUNDARY')
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in ('__import__', 'eval', 'exec'):
            raise ValueError('SDK_DYNAMIC_IMPORT_BOUNDARY')


class BoundaryTests(unittest.TestCase):
    def test_all_runtime_modules_follow_small_replaceable_ports(self):
        root = Path(__file__).resolve().parents[1] / 'dkp_logging'
        self.assertEqual(set(ALLOWED), {path.stem for path in root.glob('*.py')})
        for path in root.glob('*.py'):
            check(path.stem, path.read_text())

    def test_kernel_and_adapters_reject_service_container_and_dynamic_imports(self):
        for name, source in [('wire', 'import socket'), ('wire', 'from .producer import LogProducer'),
                ('producer', 'from services.identity import Session'), ('https', 'import requests'),
                ('https', 'from .registrar import register'), ('wire', '__import__("os")')]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                check(name, source)
        check('producer', 'from .wire import Wire\nimport threading')
