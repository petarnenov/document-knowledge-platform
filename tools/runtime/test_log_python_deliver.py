"""Real compiled SDK/HTTPS bootstrap tests; actual PostgreSQL adoption is separate."""
import contextlib
import hashlib
import http.server
import io
import json
import os
from pathlib import Path
import ssl
import subprocess
import sys
import threading
import unittest
from unittest.mock import patch

import test_log_python_build as build_tests
from test_log_python_build import load, b

d = load('python_deliver_test', 'log-python-deliver.py')


class PythonDeliveryTests(unittest.TestCase):
    def setUp(self):
        self.fixture = build_tests.PythonBuildTests(methodName='test_real_compiler_original_manifest_seal_and_sourceless_import_two_builds')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.previous = {key:value for key,value in sys.modules.items() if key == 'dkp_logging' or key.startswith('dkp_logging.')}
        self.previous_path = list(sys.path)
        self.addCleanup(self.reset_imports)
        with self.fixture.controlled():
            self.built = b.build(self.fixture.root)
        self.directory = Path(self.built['manifest']).parent
        self.cert, self.key = self.directory / 'ca.pem', self.directory / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
            '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost', '-keyout', str(self.key),
            '-out', str(self.cert)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=15)
        self.rows, self.requests, self.available = {}, [], True
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                raw = self.rfile.read(int(self.headers['Content-Length']))
                fixture.requests.append((self.path, raw))
                if self.headers.get('Authorization') != 'Bearer ' + 'p' * 48:
                    code, response = 403, {}
                elif not fixture.available:
                    code, response = 503, {}
                else:
                    root = json.loads(raw)
                    if self.path.endswith('/batches'):
                        ids = [row['eventId'] for row in root['events']]
                        for row in root['events']:
                            if row['eventId'] in fixture.rows:
                                assert fixture.rows[row['eventId']] == row
                            fixture.rows[row['eventId']] = row
                    else:
                        ids = root['eventIds']
                    code, response = 200, {'receipts':[dict(eventId=value,
                        **({'state':'COMMITTED', 'receivedAt':'2026-11-03T12:00:00Z'} if value in fixture.rows else {'state':'UNKNOWN'})) for value in ids]}
                data = json.dumps(response).encode()
                try:
                    self.send_response(code)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except OSError:
                    pass

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(self.cert, self.key)
        self.server.socket = tls.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.01), daemon=True)
        self.thread.start()
        self.addCleanup(self.cleanup_server)
        observed = json.loads(Path(self.built['observations']).read_text())
        self.config = {'schemaVersion':1, 'manifestSha256':self.built['manifestSha256'],
            'runId':observed['runId'], 'instanceId':observed['instanceId'],
            'endpoint':'https://localhost:' + str(self.server.server_port) + '/api/v1/dev-logs',
            'connectAddress':'127.0.0.1', 'producerToken':'p' * 48, 'caFile':str(self.cert),
            'spoolDirectory':str(self.directory / 'queue')}
        self.config_path = self.directory / 'producer.private.json'
        self.save_config()

    def reset_imports(self):
        for key in list(sys.modules):
            if key == 'dkp_logging' or key.startswith('dkp_logging.'):
                del sys.modules[key]
        sys.modules.update(self.previous)
        sys.path[:] = self.previous_path

    def cleanup_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.assertFalse(self.thread.is_alive())

    def save_config(self):
        self.config_path.write_text(json.dumps(self.config))
        self.config_path.chmod(0o600)

    def arguments(self):
        return [Path(self.built['artifact']), json.loads(Path(self.built['receipt']).read_text())['artifact']['sha256'],
            self.built['manifestSha256'], Path(self.built['observations']), self.built['observationsSha256'], self.config_path]

    def test_real_compiled_sdk_tls_exact_receipts_and_original_build_observations(self):
        result = d.deliver(*self.arguments(), wait_seconds=2)
        self.assertEqual('COMMITTED', result['result'])
        self.assertTrue(result['workerStopped'])
        observed = json.loads(Path(self.built['observations']).read_text())
        ids = {observed['startedEventId'], observed['finishedEventId']}
        self.assertEqual(ids, set(self.rows))
        self.assertEqual(dict.fromkeys(ids, 'COMMITTED'), result['eventStates'])
        self.assertEqual({'build.started', 'build.succeeded'}, {row['eventName'] for row in self.rows.values()})
        self.assertTrue(all(row['buildId'] == self.built['buildId'] and row['runId'] == observed['runId'] for row in self.rows.values()))
        self.assertNotIn(self.config['producerToken'], json.dumps(result))
        # The final query names exact target IDs, independently of delivery counters.
        self.assertEqual(ids, set(json.loads(self.requests[-1][1])['eventIds']))
        with self.assertRaises(ValueError):
            d.deliver(*self.arguments(), wait_seconds=0)  # No preloaded/fallback SDK.

    def test_real_outage_pending_then_same_original_execution_resume(self):
        self.available = False
        pending = d.deliver(*self.arguments(), wait_seconds=.1)
        self.assertEqual('PENDING', pending['result'])
        self.assertEqual({'UNKNOWN'}, set(pending['eventStates'].values()))
        self.assertGreaterEqual(pending['status']['spooled'], 2)
        original_ids = set(pending['eventStates'])
        self.reset_imports()
        self.available = True
        recovered = d.deliver(*self.arguments(), wait_seconds=2)
        self.assertEqual('COMMITTED', recovered['result'])
        self.assertEqual(original_ids, set(self.rows))

    def test_private_configuration_and_original_digest_identity_refusals_before_network(self):
        original = dict(self.config)
        for change in [{'runId':'00000000-0000-0000-0000-000000000001'}, {'producerToken':'short'},
                {'manifestSha256':'a' * 64}, {'unexpected':'raw-secret'}, {'schemaVersion':True}]:
            self.config = {**original, **change}
            self.save_config()
            with self.subTest(change=change), self.assertRaises(ValueError):
                d.deliver(*self.arguments())
        self.config = original
        self.save_config()
        self.config_path.chmod(0o644)
        with self.assertRaises(ValueError):
            d.deliver(*self.arguments())
        self.config_path.chmod(0o600)
        args = self.arguments()
        args[1] = 'a' * 64
        with self.assertRaises(ValueError):
            d.deliver(*args)
        args = self.arguments()
        args[4] = 'a' * 64
        with self.assertRaises(ValueError):
            d.deliver(*args)
        self.assertEqual([], self.requests)

    def test_raw_observation_fields_and_unsafe_numbers_refused(self):
        path = Path(self.built['observations'])
        original = json.loads(path.read_text())
        for changes in [{'message':'private uploaded body'}, {'filesCompiled':True}, {'durationMs':-1},
                {'finishedEventId':original['startedEventId']}, {'finishedAt':'invalid'}]:
            path.write_text(json.dumps({**original, **changes}))
            args = self.arguments()
            args[4] = hashlib.sha256(path.read_bytes()).hexdigest()
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                d.deliver(*args)
            self.reset_imports()
        self.assertEqual([], self.requests)

    def test_fixed_cli_errors_and_strict_private_reads(self):
        args = self.arguments()
        cli = ['deliver', '--artifact', str(args[0]), '--artifact-sha256', args[1],
            '--manifest-sha256', args[2], '--observations', str(args[3]), '--observations-sha256', args[4],
            '--configuration', str(args[5]), '--wait-seconds', '0']
        with patch.object(sys, 'argv', cli), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, d.main())
        with patch.object(sys, 'argv', cli), patch.object(d, 'deliver', side_effect=ValueError('private-secret')), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, d.main())
        self.assertNotIn('private-secret', output.getvalue())
        alias = self.directory / 'alias'
        alias.symlink_to(self.config_path)
        with self.assertRaises(ValueError):
            d.private(alias, 8192)
        with self.assertRaises(ValueError):
            d.unique([('key', 1), ('key', 2)])
        with patch.object(d.platform, 'python_version', return_value='3.11.2'), self.assertRaises(ValueError):
            d.deliver(*args)
        with self.assertRaises(ValueError):
            d.deliver(*args, wait_seconds=16)


if __name__ == '__main__':
    unittest.main()
