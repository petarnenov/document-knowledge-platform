"""Explicit #268 Docker acceptance; no Compose/provider operations or real authority.

Run: python3 tools/runtime/public_image_modes_acceptance.py
Tests the exact pinned runtime FROM/public-config COPY instructions, not a full
portal JavaScript build. Docker must already contain the pinned image; no pull.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
PIN = 'nginxinc/nginx-unprivileged:1.30.2-alpine@sha256:de3e40ec8b7debd7194fc798d4bbfb102c7f8b012b2c73032816b5f72393acdd'


def command(arguments, timeout=60):
    return subprocess.run(arguments, capture_output=True, timeout=timeout, check=False)


class PublicImageModesAcceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        result = command(['docker', 'image', 'inspect', PIN])
        if result.returncode:
            raise AssertionError('Pinned local Nginx image is required; no pull or fallback')

    def test_dm_proxy_config_from_owner_only_source(self):
        self.verify('kb-dm-proxy.Dockerfile', 'kb-dm-sharing.conf', '/run/dkp/dm-tls/server.crt', '/run/dkp/dm-tls/server.key')

    def test_portal_config_from_owner_only_source(self):
        self.verify('portal.Dockerfile', 'nginx.conf', '/run/dkp/portal.crt', '/run/dkp/portal.key')

    def verify(self, dockerfile, config, certificate_target, key_target):
        original = (ROOT / 'infra/docker-compose' / dockerfile).read_text()
        runtime = original.rsplit('FROM ', 1)[1]
        self.assertEqual(PIN, runtime.splitlines()[0].split(' AS ', 1)[0])
        copies = [line for line in runtime.splitlines() if line.startswith('COPY ') and '/etc/nginx/conf.d/default.conf' in line]
        self.assertEqual([f'COPY --chmod=0444 infra/docker-compose/{config} /etc/nginx/conf.d/default.conf'], copies)
        self.assertIn('USER 101:101', runtime)
        uid, gid = (os.getuid(), os.getgid()) if os.getuid() else (10001, 10001)
        with tempfile.TemporaryDirectory(prefix='dkp-268-image-') as temporary:
            directory = Path(temporary)
            context = directory / 'context'; public = context / 'infra/docker-compose'; public.mkdir(parents=True)
            source = public / config; source.write_bytes((ROOT / 'infra/docker-compose' / config).read_bytes()); source.chmod(0o400)
            self.assertEqual(0o400, source.stat().st_mode & 0o777)
            tls = directory / 'tls'; tls.mkdir(mode=0o700)
            certificate, key = tls / 'certificate.pem', tls / 'private-key.pem'
            result = command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=synthetic-dkp268', '-keyout', str(key), '-out', str(certificate)], 30)
            self.assertEqual(0, result.returncode, 'Fresh synthetic test TLS generation failed')
            for path in (certificate, key):
                path.chmod(0o600)
                if os.getuid() == 0:
                    os.chown(path, uid, gid)
            before_tls = {path.name: (path.read_bytes(), path.stat().st_mode & 0o777) for path in (certificate, key)}
            for fixed in (False, True):
                with self.subTest(config=config, fixed=fixed):
                    tag = 'dkp-268-public-config:' + uuid.uuid4().hex
                    instruction = copies[0] if fixed else copies[0].replace(' --chmod=0444', '')
                    (context / 'Dockerfile').write_text(f'FROM {PIN}\n{instruction}\nUSER 101:101\n')
                    try:
                        build = command(['docker', 'build', '--pull=false', '--network=none', '--label', 'dkp.issue=268', '-t', tag, str(context)], 120)
                        self.assertEqual(0, build.returncode, 'Owned public-config image build failed')
                        run = command(['docker', 'run', '--rm', '--pull=never', '--network=none', '--user', f'{uid}:{gid}', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges:true', '--memory=128m', '--cpus=1', '--pids-limit=64', '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,size=16m', '--add-host', 'document-management:127.0.0.1', '--add-host', 'browser-bff:127.0.0.1', '--mount', f'type=bind,src={certificate},dst={certificate_target},readonly', '--mount', f'type=bind,src={key},dst={key_target},readonly', '--entrypoint', 'nginx', tag, '-t'])
                        diagnostic = run.stderr.decode(errors='replace')
                        if fixed:
                            self.assertEqual(0, run.returncode, diagnostic)
                            self.assertIn('test is successful', diagnostic)
                        else:
                            self.assertNotEqual(0, run.returncode)
                            self.assertIn('/etc/nginx/conf.d/default.conf', diagnostic)
                            self.assertIn('Permission denied', diagnostic)
                    finally:
                        command(['docker', 'image', 'rm', tag])
            self.assertEqual(before_tls, {path.name: (path.read_bytes(), path.stat().st_mode & 0o777) for path in (certificate, key)})
            self.assertEqual(0o400, source.stat().st_mode & 0o777)


if __name__ == '__main__':
    unittest.main()
