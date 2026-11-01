"""Explicit #280/#322 pinned-Nginx edge acceptance; synthetic upstream/TLS only.

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
server {
    listen 127.0.0.1:18080;
    access_log off;
    gzip on;
    gzip_http_version 1.0;
    gzip_types application/json;
    gzip_min_length 1;
    add_header Cache-Control no-store always;
    add_header X-Content-Type-Options nosniff always;
    add_header X-Synthetic-Accept-Encoding $http_accept_encoding always;
    default_type application/json;
    location = /api/v1/kb/failure { return 503 '{"code":"AUTHORITY_UNAVAILABLE"}'; }
    location = /api/v1/kb/created { return 201 '{"synthetic":true}'; }
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
                cases = [('/api/v1/kb/collections', 200, large, 1),
                         ('/api/v1/kb/collections?cursor=synthetic', 200, large, 1),
                         ('/api/v1/kb/collections/', 200, large, 1),
                         ('/api/v1/kb/failure', 503, {'code': 'AUTHORITY_UNAVAILABLE'}, 1),
                         ('/api/v1/kb/created', 201, {'synthetic': True}, 1),
                         ('/api/v1/kb', 200, large, 1),
                         ('/api/v1/kb?limit=50', 200, large, 1),
                         ('/api/v1/kb-other', 200, large, 2),
                         ('/api/v1/kbx/collections', 200, large, 2),
                         ('/api/v1/session', 200, large, 2)]
                for path, status, body, count in cases:
                    with self.subTest(path=path):
                        result = command(['docker', 'exec', name, 'curl', '--silent', '--show-error',
                                          '--max-time', '2', '--cacert', '/run/dkp/portal.crt',
                                          '--header', 'Accept-Encoding: gzip', '--dump-header', '-', 'https://localhost:20443' + path])
                        self.assertEqual(0, result.returncode)
                        header, payload = result.stdout.split(b'\r\n\r\n', 1)
                        self.assertEqual(status, int(header.splitlines()[0].split()[1]))
                        fields = [line.decode().lower().split(':', 1) for line in header.splitlines()[1:] if b':' in line]
                        for name_, value in [('cache-control', 'no-store'), ('x-content-type-options', 'nosniff')]:
                            self.assertEqual([value] * count, [v.strip() for k, v in fields if k == name_])
                        encoding = [v.strip() for k, v in fields if k == 'content-encoding']
                        upstream_accept = [v.strip() for k, v in fields if k == 'x-synthetic-accept-encoding']
                        if count == 1:
                            self.assertEqual([], encoding, 'KB must remain unencoded; negotiated upstream=' + repr(upstream_accept))
                            self.assertEqual([], upstream_accept, 'KB proxy must suppress upstream compression negotiation')
                        else:
                            self.assertEqual(['gzip'], encoding, 'Generic route compression behavior must remain intact')
                            self.assertEqual(['gzip'], upstream_accept)
                            payload = gzip.decompress(payload) # Generic legacy route only; never KB.
                        self.assertEqual(json.dumps(body,separators=(',',':')).encode(), payload, 'Status and exact decoded bytes must be preserved')
                        self.assertEqual(body, json.loads(payload))
            finally:
                command(['docker', 'rm', '-f', name])
            self.assertEqual(before, {p.name: p.read_bytes() for p in (cert, key)})


if __name__ == '__main__':
    unittest.main()
