"""Explicit #280/#322/#395 pinned-Nginx edge acceptance; synthetic upstream/TLS only.

Run: python3 tools/runtime/kb_edge_headers_acceptance.py
No real provider, Compose, identity material, or external network access.
"""
import gzip
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
PIN = 'nginxinc/nginx-unprivileged:1.30.2-alpine@sha256:de3e40ec8b7debd7194fc798d4bbfb102c7f8b012b2c73032816b5f72393acdd'


def command(args, timeout=30):
    return subprocess.run(args, capture_output=True, timeout=timeout, check=False)


class KnowledgeEdgeHeadersAcceptance(unittest.TestCase):
    def test_real_tls_negotiated_upstream_preserves_only_kb_unencoded_contract(self):
        self.exercise(False)

    def test_real_tls_inherited_edge_compression_cannot_encode_kb_mirrors(self):
        self.exercise(True)

    def exercise(self, inherited_compression):
        self.assertEqual(0, command(['docker', 'image', 'inspect', PIN]).returncode,
                         'Exact pinned local image required; no fallback or pull')
        source = (ROOT / 'infra/docker-compose/kb-portal.conf').read_text()
        self.assertIn('proxy_read_timeout 10s;', source)
        self.assertIn('proxy_connect_timeout 3s;', source)
        self.assertIn('client_max_body_size 16k;', source)
        self.assertIn('listen 20443 ssl;', source)
        with tempfile.TemporaryDirectory(prefix='dkp322-') as temporary:
            directory = Path(temporary)
            cert, key = directory / 'cert.pem', directory / 'key.pem'
            result = command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                              '-days', '1', '-subj', '/CN=localhost', '-addext',
                              'subjectAltName=DNS:localhost', '-keyout', str(key), '-out', str(cert)])
            self.assertEqual(0, result.returncode, 'Synthetic TLS creation failed')
            uid, gid = (os.getuid(), os.getgid()) if os.getuid() else (10001, 10001)
            for path in (cert, key):
                path.chmod(0o600)
                if os.getuid() == 0:
                    os.chown(path, uid, gid)
            before = {p.name: p.read_bytes() for p in (cert, key)}
            # Substitute only the fixed test upstream listener. All production
            # matching, TLS, header, timeout and body-size directives stay intact.
            config = directory / 'default.conf'
            large = {'synthetic': True, 'payload': 'public-synthetic-' * 512}
            large_file = directory / 'large.json'
            large_file.write_text(json.dumps(large,separators=(',',':')))
            large_file.chmod(0o444)
            upstream = """
map $uri $synthetic_cookie {
    default "synthetic-session=public; Path=/; Secure; HttpOnly; SameSite=Strict";
    /api/v1/session/logout "synthetic-session=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict";
}
server {
    listen 127.0.0.1:18080;
    access_log off;
    gzip on;
    gzip_http_version 1.0;
    gzip_types application/json;
    gzip_min_length 1;
    # Model controller/filter copies, as well as the edge's own header.
    add_header Cache-Control no-store always;
    add_header Cache-Control no-store always;
    add_header X-Content-Type-Options nosniff always;
    add_header X-Content-Type-Options nosniff always;
    add_header Set-Cookie $synthetic_cookie always;
    add_header X-Synthetic-Accept-Encoding $http_accept_encoding always;
    add_header X-Synthetic-Host $http_host always;
    add_header X-Synthetic-Proto $http_x_forwarded_proto always;
    add_header X-Synthetic-Forwarded $http_forwarded always;
    add_header X-Synthetic-Origin $http_origin always;
    add_header X-Synthetic-CSRF $http_x_csrf_token always;
    default_type application/json;
    location = /api/v1/kb/failure { return 503 '{"code":"AUTHORITY_UNAVAILABLE"}'; }
    location = /api/v1/kb/created { return 201 '{"synthetic":true}'; }
    location = /api/v1/session {
        if ($http_x_synthetic_actor = "") { return 401 '{"code":"UNAUTHENTICATED"}'; }
        return 200 '{"authenticated":true,"tenant":"tenant-a"}';
    }
    location = /api/v1/session/tenant {
        if ($http_x_csrf_token = "") { return 403 '{"code":"CSRF_REQUIRED"}'; }
        return 200 '{"tenant":"tenant-b"}';
    }
    location = /api/v1/session/logout { return 204; }
    location = /oauth/login/keycloak { return 302 https://idp.example.invalid/authorize?state=synthetic; }
    location = /api/v1/dm/documents { return 201 '{"documentId":"00000000-0000-4000-8000-000000000001","revision":1}'; }
    location = /api/v1/dm/documents/00000000-0000-4000-8000-000000000001 { return 200 '{"revision":1,"title":"synthetic"}'; }
    location = /api/v1/dm/failure { return 400 '{"code":"INVALID_REQUEST"}'; }
    location / { root /run/synthetic; try_files /large.json =404; }
}
"""
            # Make inherited edge compression explicit as well as a genuinely
            # negotiating upstream. KB must disable both; generic routes keep it.
            edge = source.replace('server {', 'server {\n    gzip on;\n    gzip_types application/json;\n    gzip_min_length 1;', 1) if inherited_compression else source
            config.write_text(edge.replace('http://browser-bff:8080', 'http://127.0.0.1:18080') + upstream)
            config.chmod(0o444)
            name = 'dkp322-' + uuid.uuid4().hex
            args = ['docker', 'run', '-d', '--name', name, '--label', 'dkp.issue=322',
                    '--pull=never', '--network=none', '--user', f'{uid}:{gid}',
                    '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges:true',
                    '--memory=128m', '--cpus=1', '--pids-limit=64',
                    '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,size=16m',
                    '--mount', f'type=bind,src={config},dst=/etc/nginx/conf.d/default.conf,readonly',
                    '--mount', f'type=bind,src={large_file},dst=/run/synthetic/large.json,readonly',
                    '--mount', f'type=bind,src={cert},dst=/run/dkp/portal.crt,readonly',
                    '--mount', f'type=bind,src={key},dst=/run/dkp/portal.key,readonly',
                    '--entrypoint', 'nginx', PIN, '-g', 'daemon off;']
            try:
                self.assertEqual(0, command(args).returncode, 'Owned isolated test container failed')
                ready = time.monotonic() + 10
                while True:
                    result = command(['docker', 'exec', name, 'curl', '--silent', '--show-error',
                                      '--max-time', '2', '--cacert', '/run/dkp/portal.crt',
                                      '--dump-header', '-', 'https://localhost:20443/api/v1/kb/collections'])
                    if result.returncode == 0 or time.monotonic() >= ready:
                        break
                    time.sleep(0.05)  # Bounded readiness, never an assertion retry.
                self.assertEqual(0, result.returncode, 'Actual CA-verified TLS probe failed')
                # Header cardinality and compression are independent contracts.
                # Existing generic routes retain gzip; only KB suppresses negotiation.
                cases = [
                    ('/api/v1/kb/collections', 200, large, True),
                    ('/api/v1/kb/collections?cursor=synthetic', 200, large, True),
                    ('/api/v1/kb/collections/', 200, large, True),
                    ('/api/v1/kb/failure', 503, {'code': 'AUTHORITY_UNAVAILABLE'}, True),
                    ('/api/v1/kb/created', 201, {'synthetic': True}, True),
                    ('/api/v1/kb', 200, large, True),
                    ('/api/v1/kb?limit=50', 200, large, True),
                    ('/api/v1/kb-other', 200, large, False),
                    ('/api/v1/kbx/collections', 200, large, False),
                    ('/api/v1/session', 401, {'code': 'UNAUTHENTICATED'}, False),
                    ('/api/v1/session', 200, {'authenticated': True, 'tenant': 'tenant-a'}, False),
                    ('/api/v1/session/tenant', 403, {'code': 'CSRF_REQUIRED'}, False),
                    ('/api/v1/session/tenant', 200, {'tenant': 'tenant-b'}, False),
                    ('/api/v1/session/logout', 204, None, False),
                    ('/oauth/login/keycloak', 302, None, False),
                    ('/api/v1/dm/documents', 201, {'documentId': '00000000-0000-4000-8000-000000000001', 'revision': 1}, False),
                    ('/api/v1/dm/documents/00000000-0000-4000-8000-000000000001', 200, {'revision': 1, 'title': 'synthetic'}, False),
                    ('/api/v1/dm/failure', 400, {'code': 'INVALID_REQUEST'}, False),
                ]
                for path, status, body, is_kb in cases:
                    with self.subTest(path=path, status=status):
                        request_headers = ['--header', 'Accept-Encoding: gzip',
                                           '--header', 'Origin: https://localhost:20443']
                        if path == '/api/v1/session' and status == 200:
                            request_headers += ['--header', 'X-Synthetic-Actor: public']
                        csrf = path.endswith('/tenant') and status == 200 or path.endswith('/logout')
                        if csrf:
                            request_headers += ['--header', 'X-CSRF-Token: public-synthetic']
                        method = 'POST' if path.endswith(('/tenant', '/logout', '/documents')) else 'GET'
                        result = command(['docker', 'exec', name, 'curl', '--silent', '--show-error',
                                          '--max-time', '2', '--cacert', '/run/dkp/portal.crt',
                                          '--request', method, *request_headers, '--dump-header', '-',
                                          'https://localhost:20443' + path])
                        self.assertEqual(0, result.returncode)
                        header, payload = result.stdout.split(b'\r\n\r\n', 1)
                        self.assertEqual(status, int(header.splitlines()[0].split()[1]))
                        fields = [(key.lower(), value.strip()) for line in header.splitlines()[1:]
                                  if b':' in line for key, value in [line.decode().split(':', 1)]]
                        values = lambda key: [v for k, v in fields if k == key]
                        for header_name, value in [('cache-control', 'no-store'), ('x-content-type-options', 'nosniff')]:
                            self.assertEqual([value], values(header_name), 'Exact canonical header required')
                        self.assertEqual(['no-referrer'], values('referrer-policy'))
                        self.assertEqual(['localhost:20443'], values('x-synthetic-host'))
                        self.assertEqual(['https'], values('x-synthetic-proto'))
                        self.assertEqual([], values('x-synthetic-forwarded'))
                        self.assertEqual(['https://localhost:20443'], values('x-synthetic-origin'))
                        self.assertEqual(['public-synthetic'] if csrf else [], values('x-synthetic-csrf'))
                        cookie = ('synthetic-session=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict'
                                  if path.endswith('/logout') else 'synthetic-session=public; Path=/; Secure; HttpOnly; SameSite=Strict')
                        self.assertEqual([cookie], values('set-cookie'), 'Cookies must survive canonicalization unchanged')
                        location = ['https://idp.example.invalid/authorize?state=synthetic'] if status == 302 else []
                        self.assertEqual(location, values('location'), 'OIDC redirect must survive unchanged')
                        encoding = values('content-encoding')
                        if is_kb:
                            self.assertEqual([], encoding, 'KB must remain unencoded')
                            self.assertEqual([], values('x-synthetic-accept-encoding'), 'KB must suppress upstream negotiation')
                        else:
                            self.assertEqual(['gzip'], values('x-synthetic-accept-encoding'))
                            # Nginx gzip supports these statuses; 201/204/302/400/401 remain plain.
                            expected_encoding = ['gzip'] if status in (200, 403) else []
                            self.assertEqual(expected_encoding, encoding, 'Generic compression semantics must remain intact')
                            if encoding:
                                payload = gzip.decompress(payload)  # Generic only, never KB.
                        expected_body = b'' if body is None else json.dumps(body, separators=(',', ':')).encode()
                        if status == 302:
                            direct = command(['docker', 'exec', name, 'curl', '--silent', '--show-error',
                                              '--max-time', '2', '--dump-header', '-',
                                              'http://127.0.0.1:18080' + path])
                            self.assertEqual(0, direct.returncode)
                            direct_header, expected_body = direct.stdout.split(b'\r\n\r\n', 1)
                            self.assertEqual(302, int(direct_header.splitlines()[0].split()[1]))
                            self.assertTrue(expected_body, 'Actual upstream redirect body required')
                        self.assertEqual(expected_body, payload, 'Status and exact decoded bytes must be preserved')
                        if body is not None:
                            self.assertEqual(body, json.loads(payload))
            finally:
                command(['docker', 'rm', '-f', name])
            self.assertEqual(before, {p.name: p.read_bytes() for p in (cert, key)})


if __name__ == '__main__':
    unittest.main()
