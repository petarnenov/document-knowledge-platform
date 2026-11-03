import http.server
import json
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

from dkp_logging.https import HttpsTransport
from test_wire import receipts


class HttpsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.cert, cls.key = cls.root / 'cert.pem', cls.root / 'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
            '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost',
            '-keyout', str(cls.key), '-out', str(cls.cert)], check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.code, self.body, self.headers = 200, b'{"receipts":[]}', {'Content-Type': 'application/json'}
        self.requests = []
        self.hold, self.entered, self.release = False, threading.Event(), threading.Event()
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                fixture.requests.append((self.path, self.headers.get('Authorization'), body))
                fixture.entered.set()
                if fixture.hold:
                    fixture.release.wait(2)
                try:
                    self.send_response(fixture.code)
                    for key, value in fixture.headers.items():
                        self.send_header(key, value)
                    self.send_header('Content-Length', str(len(fixture.body)))
                    self.end_headers()
                    self.wfile.write(fixture.body)
                except OSError:
                    pass

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(self.cert, self.key)
        self.server.socket = tls.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=lambda: self.server.serve_forever(poll_interval=.01), daemon=True)
        self.thread.start()
        self.addCleanup(self.cleanup_server)
        self.endpoint = 'https://localhost:' + str(self.server.server_port) + '/api/v1/dev-logs'

    def cleanup_server(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.assertFalse(self.thread.is_alive())

    def make(self, endpoint=None):
        transport = HttpsTransport(endpoint or self.endpoint, 'p' * 48, str(self.cert), '127.0.0.1')
        self.addCleanup(transport.close)
        return transport

    def test_actual_tls_fixed_routes_headers_and_no_dns_or_proxy(self):
        transport = self.make()
        self.assertEqual(64, len(transport.credential_fingerprint()))
        with patch('socket.getaddrinfo', side_effect=AssertionError('No DNS permitted')):
            for route in ('batches', 'receipts'):
                self.assertEqual((200, self.body), transport.post(route, b'{}'))
        self.assertEqual(['/api/v1/dev-logs/batches', '/api/v1/dev-logs/receipts'], [row[0] for row in self.requests])
        self.assertTrue(all(row[1] == 'Bearer ' + 'p' * 48 and row[2] == b'{}' for row in self.requests))

    def test_configuration_body_route_and_closed_guards(self):
        for endpoint in ['http://localhost/api/v1/dev-logs', self.endpoint + '?q=1', self.endpoint + '#fragment',
                'https://user:secret@localhost/api/v1/dev-logs', 'https://localhost/other']:
            with self.assertRaises(ValueError):
                self.make(endpoint)
        for token, address in [('short', '127.0.0.1'), ('p' * 48, 'localhost')]:
            with self.assertRaises(ValueError):
                HttpsTransport(self.endpoint, token, str(self.cert), address)
        transport = self.make()
        for route, body in [('registrations', b'{}'), ('batches', b''), ('batches', b'x' * 32769), ('batches', '{}')]:
            with self.assertRaises(ValueError):
                transport.post(route, body)
        transport.close()
        with self.assertRaises(OSError):
            transport.post('batches', b'{}')

    def test_real_tls_hostname_mismatch_refused(self):
        transport = self.make(self.endpoint.replace('localhost', '127.0.0.1'))
        with self.assertRaisesRegex(OSError, '^LOG_HTTP_UNAVAILABLE$'):
            transport.post('batches', b'{}')
        self.assertEqual([], self.requests)

    def test_response_encoding_type_size_and_redirect_are_not_success(self):
        transport = self.make()
        for headers, body in [({'Content-Encoding': 'gzip'}, b'compressed'),
                ({'Content-Type': 'text/plain'}, b'secret'),
                ({'Content-Type': 'application/json'}, b'x' * 32769)]:
            self.headers, self.body = headers, body
            with self.assertRaises(OSError):
                transport.post('batches', b'{}')
        self.code, self.headers, self.body = 302, {'Location': 'https://untrusted.invalid/'}, b''
        self.assertEqual((302, b''), transport.post('batches', b'{}'))
        self.assertEqual(4, len(self.requests))

    def test_normal_close_cancels_exchange_and_keeps_active_slot_until_exit(self):
        transport = self.make()
        self.hold = True
        result = []

        def send():
            try:
                transport.post('batches', b'{}')
            except OSError as failure:
                result.append(str(failure))

        worker = threading.Thread(target=send)
        worker.start()
        self.assertTrue(self.entered.wait(1))
        with self.assertRaises(OSError):
            transport.post('receipts', b'{}')
        transport.close()
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual(['LOG_HTTP_UNAVAILABLE'], result)

    def test_deadline_actively_aborts_normal_wait(self):
        transport = self.make()
        self.hold = True
        real_timer = threading.Timer
        with patch('dkp_logging.https.threading.Timer', side_effect=lambda seconds, callback: real_timer(.1, callback)):
            with self.assertRaises(OSError):
                transport.post('batches', b'{}')

    def test_connect_failure_has_fixed_safe_error(self):
        transport = self.make()
        with patch('dkp_logging.https.socket.socket') as factory:
            factory.return_value.connect.side_effect = OSError('raw-secret')
            with self.assertRaisesRegex(OSError, '^LOG_HTTP_UNAVAILABLE$'):
                transport.post('batches', b'{}')


if __name__ == '__main__':
    unittest.main()
