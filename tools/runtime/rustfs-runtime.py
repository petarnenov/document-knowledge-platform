#!/usr/bin/env python3
"""Prepare private BP03 authority and apply native RustFS IAM over verified HTTPS.

Apply can start bounded ephemeral clients on the owned private network.
Docker/host operators remain trusted. Private files
are ignored local development material, never production release authority.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
from urllib.parse import urlsplit, quote
import xml.etree.ElementTree as XML

IMAGE = 'rustfs/rustfs:1.0.4@sha256:ed9210d6411d77ffceb70d94b964a3cb7d225983368bc25bebe01e995293dcc2'
TENANTS = ('tenant-a', 'tenant-b')
BUCKETS = ('dkp-quarantine', 'dkp-originals')
POLICY_NAME = 'dkp-bp03-upload'
FILES = ('server/rustfs_cert.pem', 'server/rustfs_key.pem', 'server/root-access',
         'server/root-secret', 'app/ca.crt', 'app/access.key', 'app/secret.key', 'app/access.key.role',
         'cleanup/access.key', 'cleanup/secret.key', 'cleanup/access.key.role',
         'spool/.spool-authority', 'bootstrap/ca.key', 'bootstrap/root-curl.conf')


def authority_root():
    return Path(__file__).resolve().parents[2] / 'local-data/security/bp03-upload'


def confined(path):
    path = path.absolute()
    if (path != path.resolve() or not path.is_relative_to(authority_root().absolute()) or path == authority_root().absolute()
            or any(p.is_symlink() for p in (path, *path.parents))):
        raise ValueError('Authority must be beneath ignored local-data/security/bp03-upload without symlinks')
    return path


def run(*args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise ValueError('Private RustFS authority command failed; diagnostics suppressed')
    return result.stdout


def private(path, text):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'w') as f:
        f.write(text)


def validate(directory):
    directory = confined(directory)
    for role, expected in [('app', {'ca.crt', 'access.key', 'secret.key', 'access.key.role'}), ('cleanup', {'access.key', 'secret.key', 'access.key.role'})]:
        if {p.name for p in (directory / role).iterdir()} != expected:
            raise ValueError('Separated authority must exclude unrelated material')
    for path in [directory, *(directory / p for p in ('server', 'app', 'cleanup', 'bootstrap', 'spool'))]:
        if not path.is_dir() or path.is_symlink() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
            raise ValueError('Private operator-owned authority directories required')
    manifest = directory / 'bootstrap/authority.json'
    for name in (*FILES, 'bootstrap/authority.json'):
        path = directory / name
        if (path.is_symlink() or not path.is_file() or path.stat().st_uid != os.getuid()
                or path.stat().st_mode & 0o077 or path.stat().st_size > 16384):
            raise ValueError('Incomplete or unsafe RustFS authority; refusing rotation')
    data = json.loads(manifest.read_text())
    if (set(data) != {'schemaVersion', 'image', 'tenants', 'files'} or data['schemaVersion'] != 1
            or data['image'] != IMAGE or data['tenants'] != list(TENANTS)
            or set(data['files']) != set(FILES)
            or any(hashlib.sha256((directory / n).read_bytes()).hexdigest() != data['files'][n] for n in FILES)):
        raise ValueError('RustFS authority identity changed; refusing rotation')
    if (directory / 'spool/.spool-authority').read_text() != 'dkp-private-spool-v1\n':
        raise ValueError('Invalid private spool authority')
    for name, size in [('server/root-access', 32), ('server/root-secret', 64), ('app/access.key', 32), ('app/secret.key', 64), ('cleanup/access.key', 32), ('cleanup/secret.key', 64)]:
        if not re.fullmatch('[0-9a-f]{' + str(size) + '}\n', (directory / name).read_text()):
            raise ValueError('Invalid private credentials')
    credentials = [directory / (role + '/' + name) for role in ('server', 'app', 'cleanup') for name in (('root-access', 'root-secret') if role == 'server' else ('access.key', 'secret.key'))]
    if len({p.read_bytes().strip() for p in credentials}) != len(credentials) or len({(p.stat().st_dev, p.stat().st_ino) for p in credentials}) != len(credentials):
        raise ValueError('Distinct independent authority required')
    for role, kind in [('app', 'normal'), ('cleanup', 'cleanup')]:
        if (directory / role / 'access.key.role').read_text() != role_binding(directory / role, kind):
            raise ValueError('Credential role binding mismatch')
    expected = 'user = "' + (directory / 'server/root-access').read_text().strip() + ':' + (directory / 'server/root-secret').read_text().strip() + '"\n'
    if (directory / 'bootstrap/root-curl.conf').read_text() != expected:
        raise ValueError('Bootstrap credential identity mismatch')
    cert, ca = directory / 'server/rustfs_cert.pem', directory / 'app/ca.crt'
    for option, host in [('-verify_hostname', 'rustfs'), ('-verify_hostname', 'localhost'), ('-verify_ip', '127.0.0.1')]:
        run('openssl', 'verify', '-CAfile', str(ca), '-purpose', 'sslserver', option, host, str(cert))
    key = run('openssl', 'pkey', '-in', str(directory / 'server/rustfs_key.pem'), '-pubout')
    if key != run('openssl', 'x509', '-in', str(cert), '-pubkey', '-noout'):
        raise ValueError('Server TLS key does not match certificate')
    return directory


def prepare(directory):
    directory = confined(directory)
    if directory.exists():
        return validate(directory)
    directory.parent.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix='.rustfs-authority-', dir=directory.parent))
    try:
        for part in ('server', 'app', 'cleanup', 'bootstrap', 'spool'):
            (scratch / part).mkdir(mode=0o700)
        for name, size in [('server/root-access', 16), ('server/root-secret', 32), ('app/access.key', 16), ('app/secret.key', 32), ('cleanup/access.key', 16), ('cleanup/secret.key', 32)]:
            private(scratch / name, secrets.token_hex(size) + '\n')
        for role, kind in [('app', 'normal'), ('cleanup', 'cleanup')]:
            private(scratch / role / 'access.key.role', role_binding(scratch / role, kind))
        private(scratch / 'spool/.spool-authority', 'dkp-private-spool-v1\n')
        private(scratch / 'bootstrap/root-curl.conf', 'user = "' + (scratch / 'server/root-access').read_text().strip() + ':' + (scratch / 'server/root-secret').read_text().strip() + '"\n')
        run('openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '3', '-subj', '/CN=DKP-BP03-local-CA', '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign', '-keyout', str(scratch / 'bootstrap/ca.key'), '-out', str(scratch / 'app/ca.crt'))
        run('openssl', 'req', '-newkey', 'rsa:3072', '-nodes', '-subj', '/CN=rustfs', '-keyout', str(scratch / 'server/rustfs_key.pem'), '-out', str(scratch / 'bootstrap/server.csr'))
        private(scratch / 'bootstrap/server.ext', 'subjectAltName=DNS:rustfs,DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
        run('openssl', 'x509', '-req', '-in', str(scratch / 'bootstrap/server.csr'), '-CA', str(scratch / 'app/ca.crt'), '-CAkey', str(scratch / 'bootstrap/ca.key'), '-CAcreateserial', '-days', '3', '-extfile', str(scratch / 'bootstrap/server.ext'), '-out', str(scratch / 'server/rustfs_cert.pem'))
        for name in ('bootstrap/server.csr', 'bootstrap/server.ext', 'app/ca.srl'):
            (scratch / name).unlink(missing_ok=True)
        for name in FILES:
            (scratch / name).chmod(0o600)
        data = {'schemaVersion': 1, 'image': IMAGE, 'tenants': list(TENANTS), 'files': {n: hashlib.sha256((scratch / n).read_bytes()).hexdigest() for n in FILES}}
        private(scratch / 'bootstrap/authority.json', json.dumps(data, separators=(',', ':')) + '\n')
        validate(scratch)
        scratch.rename(directory)
        return validate(directory)
    finally:
        if scratch.exists():
            shutil.rmtree(scratch)


def role_binding(directory, role):
    return 'dkp-storage-' + role + '-v1\n' + ''.join(hashlib.sha256((directory / (name + '.key')).read_bytes().strip()).hexdigest() + '\n' for name in ('access', 'secret'))


def policy(*, cleanup=False):
    prefixes = [hashlib.sha256(t.encode()).hexdigest() + '/*' for t in TENANTS]
    return {'Version': '2012-10-17', 'Statement': [
        {'Effect': 'Allow', 'Action': ['s3:GetBucketVersioning'], 'Resource': ['arn:aws:s3:::' + b for b in BUCKETS]},
        {'Effect': 'Allow', 'Action': ['s3:ListBucketVersions'], 'Resource': ['arn:aws:s3:::' + b for b in BUCKETS], 'Condition': {'StringLike': {'s3:prefix': prefixes}}},
        {'Effect': 'Allow', 'Action': (['s3:GetObjectVersion', 's3:DeleteObjectVersion'] if cleanup else ['s3:GetObject', 's3:GetObjectVersion', 's3:PutObject']), 'Resource': ['arn:aws:s3:::' + b + '/' + p for b in BUCKETS for p in prefixes]}]}


def endpoint(value):
    parsed = urlsplit(value)
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment or parsed.path not in ('', '/')):
        raise ValueError('Explicit HTTPS RustFS origin required')
    return value.rstrip('/')


def owned_network(name, origin):
    if origin != 'https://rustfs:9000' or not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', name):
        raise ValueError('Private bootstrap requires the exact RustFS service origin')
    network = json.loads(run('docker', 'network', 'inspect', name))[0]
    network_id = network.get('Id', '')
    labels = network.get('Labels') or {}
    project = labels.get('com.docker.compose.project', '')
    if (not re.fullmatch('[0-9a-f]{64}', network_id) or network.get('Internal') is not True
            or re.fullmatch(r'dkp-bp03(?:-[0-9a-f]{12})?', project) is None
            or labels.get('com.docker.compose.network') != 'upload-data'):
        raise ValueError('Owned internal upload network required')
    for container in network.get('Containers', {}):
        lines = run('docker', 'inspect', '--format',
                    '{{json .Config.Labels}}\n{{json .NetworkSettings.Networks}}', container).decode().splitlines()
        container_labels, attachments = map(json.loads, lines)
        if (container_labels.get('com.docker.compose.project') == project
                and container_labels.get('com.docker.compose.service') == 'rustfs'
                and any(n.get('NetworkID') == network_id for n in attachments.values())):
            return network_id
    raise ValueError('Owned RustFS service is not attached to the selected network')


def private_network_request(arguments, root, ca, network_id):
    # This ephemeral operator client receives only one request's root credential
    # configuration and a public CA. Applications and parsers never receive it.
    cidfile = root / 'runner.cid'
    command = ['docker', 'run', '--rm', '--cidfile', str(cidfile), '--label', 'dkp.issue=131',
               '--network', network_id, '--user', f'{os.getuid()}:{os.getgid()}',
               '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--read-only',
               '--memory', '64m', '--cpus', '0.5', '--pids-limit', '16',
               '--tmpfs', f'/tmp:uid={os.getuid()},gid={os.getgid()},mode=0700,size=1m',
               '--mount', f'type=bind,src={root},dst={root}',
               '--mount', f'type=bind,src={ca},dst={ca},readonly',
               '--entrypoint', 'curl', IMAGE, *arguments[1:]]
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=35)
        if result.returncode:
            raise ValueError('Private bootstrap transport failed; diagnostics suppressed')
        return result.stdout
    except (subprocess.TimeoutExpired, OSError):
        raise ValueError('Private bootstrap transport deadline failed; diagnostics suppressed') from None
    finally:
        # A cidfile exists only after this invocation created a container. Never
        # remove a guessed name on startup failure or touch another task's data.
        if cidfile.is_file() and not cidfile.is_symlink():
            container_id = cidfile.read_text().strip()
            if re.fullmatch('[0-9a-f]{64}', container_id):
                try:
                    subprocess.run(['docker', 'rm', '--force', container_id],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                except (subprocess.TimeoutExpired, OSError):
                    pass  # --rm and curl's own fifteen-second deadline also remain active.


def request(directory, origin, method, path, body=None, allowed=(200, 204), *, docker_network=None,
            authority='root', response_headers=None):
    if authority not in ('root', 'app', 'cleanup', 'anonymous'):
        raise ValueError('Unknown bootstrap request authority')
    # Validate ownership before writing any credential into the request mount.
    network_id = owned_network(docker_network, origin) if docker_network is not None else None
    # URLs may contain the app credential identifier. Keep all signing/config/body material
    # in private files, not argv, environment, stdout or exception messages.
    with tempfile.TemporaryDirectory(prefix='.apply-', dir=directory / 'bootstrap') as temporary:
        root = Path(temporary)
        config, response, headers = root / 'curl.conf', root / 'response', root / 'headers'
        private(response, '')
        private(headers, '')
        lines = ['url = ' + json.dumps(origin + path), 'request = ' + json.dumps(method)]
        if authority == 'root':
            lines.append((directory / 'bootstrap/root-curl.conf').read_text().rstrip())
        elif authority in ('app', 'cleanup'):
            credentials = ':'.join((directory / (authority + '/' + n + '.key')).read_text().strip()
                                   for n in ('access', 'secret'))
            lines.append('user = ' + json.dumps(credentials))
        if method == 'HEAD':
            lines.append('head')
        if body is not None:
            private(root / 'body', body)
            lines.append('data-binary = ' + json.dumps('@' + str(root / 'body')))
        private(config, '\n'.join(lines) + '\n')
        arguments = ('curl', '--disable', '--silent', '--show-error', '--max-time', '15', '--max-filesize', '65536', '--proto', '=https', '--cacert', str(directory / 'app/ca.crt'), '--config', str(config), '--dump-header', str(headers), '--output', str(response), '--write-out', '%{http_code}')
        if authority != 'anonymous':
            arguments += ('--aws-sigv4', 'aws:amz:us-east-1:s3')
        raw = (private_network_request(arguments, root, directory / 'app/ca.crt', network_id)
               if network_id is not None else run(*arguments))
        if raw.decode().strip() not in {str(c) for c in allowed} or response.stat().st_size > 65536:
            raise ValueError('RustFS bootstrap request rejected; private response suppressed')
        if headers.stat().st_size > 16384:
            raise ValueError('Bootstrap response headers exceed bound')
        if response_headers is not None:
            for line in headers.read_text().splitlines():
                if ':' in line:
                    name, value = line.split(':', 1)
                    name = name.lower().strip()
                    if name == 'x-amz-version-id' and name in response_headers:
                        raise ValueError('Ambiguous exact object version')
                    response_headers[name] = value.strip()
        return int(raw), response.read_bytes()


def apply(directory, origin, *, docker_network=None):
    directory, origin = validate(directory), endpoint(origin)
    if docker_network is not None:
        owned_network(docker_network, origin)
    def send(*args, **kwargs):
        if docker_network is not None:
            kwargs['docker_network'] = docker_network
        return request(*args, **kwargs)
    for bucket in BUCKETS:
        status, body = send(directory, origin, 'PUT', '/' + bucket, allowed=(200, 409))
        if status == 409 and XML.fromstring(body).findtext('Code') != 'BucketAlreadyOwnedByYou':
            raise ValueError('Bucket ownership is not established')
        status, body = send(directory, origin, 'GET', '/' + bucket + '?policy', allowed=(200, 404))
        if status != 404 or XML.fromstring(body).findtext('Code') != 'NoSuchBucketPolicy':
            raise ValueError('Owned bucket must have no public or custom bucket policy')
        # RustFS 1.0.4 GetBucketAcl returns a FULL_CONTROL placeholder with
        # an empty CanonicalUser grantee. ACL authorization is unsupported;
        # privacy is demonstrated below through IAM and unsigned 403 responses.
        send(directory, origin, 'PUT', '/' + bucket + '?versioning', '<VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Status>Enabled</Status></VersioningConfiguration>')
        _, body = send(directory, origin, 'GET', '/' + bucket + '?versioning')
        if XML.fromstring(body).findtext('{*}Status') != 'Enabled':
            raise ValueError('Versioned private bucket required')
    fixtures = []
    try:
        for bucket in BUCKETS:
            verify_fixture(directory, origin, send, bucket, 'root', fixtures)
        for role in ('app', 'cleanup'):
            access = (directory / role / 'access.key').read_text().strip()
            secret = (directory / role / 'secret.key').read_text().strip()
            policy_name = POLICY_NAME + ('-cleanup' if role == 'cleanup' else '')
            send(directory, origin, 'PUT', '/rustfs/admin/v3/add-canned-policy?name=' + policy_name, json.dumps(policy(cleanup=role == 'cleanup')))
            send(directory, origin, 'PUT', '/rustfs/admin/v3/add-user?accessKey=' + quote(access, safe=''), json.dumps({'secretKey': secret, 'status': 'enabled'}))
            send(directory, origin, 'PUT', '/rustfs/admin/v3/set-user-or-group-policy?policyName=' + policy_name + '&userOrGroup=' + quote(access, safe='') + '&isGroup=false')
        for bucket in BUCKETS:
            verify_fixture(directory, origin, send, bucket, 'app', fixtures)
            _, body = send(directory, origin, 'GET', '/' + bucket + '?versioning', authority='app')
            if XML.fromstring(body).findtext('{*}Status') != 'Enabled':
                raise ValueError('Application cannot verify bucket versioning')
            outside = '/' + bucket + '/' + hashlib.sha256(b'outside-tenant').hexdigest() + '/bootstrap-denied'
            for method, path, data in [('GET', outside, None), ('PUT', outside, 'synthetic'),
                                       ('DELETE', outside + '?versionId=denied-fixture', None),
                                       ('GET', '/' + bucket + '?versions&prefix=' + hashlib.sha256(b'outside-tenant').hexdigest() + '/', None),
                                       ('DELETE', '/' + bucket, None)]:
                require_denied(send, directory, origin, method, path, data, authority='app')
        for method, path in [('GET', '/rustfs/admin/v3/info'), ('PUT', '/dkp-bootstrap-denied')]:
            require_denied(send, directory, origin, method, path, authority='app')
    finally:
        # Only exact versions returned by our bounded synthetic writes are removed.
        # Unknown outcomes never lead to unversioned deletion or prefix sweeps.
        for path, version, authority in fixtures:
            send(directory, origin, 'DELETE', path + '?versionId=' + quote(version, safe=''),
                 authority='root')


def require_denied(send, directory, origin, method, path, body=None, *, authority):
    status, response = send(directory, origin, method, path, body, allowed=(403,), authority=authority)
    if status != 403 or XML.fromstring(response).findtext('{*}Code') != 'AccessDenied':
        raise ValueError('Exact AccessDenied privacy proof required')


def verify_fixture(directory, origin, send, bucket, authority, fixtures):
    # Both admitted tenant prefixes are exercised with app credentials; root's
    # pre-IAM test uses the first one. Every anonymous GET addresses real bytes.
    for tenant in TENANTS if authority == 'app' else TENANTS[:1]:
        prefix = hashlib.sha256(tenant.encode()).hexdigest() + '/'
        path = '/' + bucket + '/' + prefix + secrets.token_hex(16) + '/' + secrets.token_hex(16)
        payload, headers = 'DKP bounded synthetic bootstrap fixture\n', {}
        send(directory, origin, 'PUT', path, payload, authority=authority, response_headers=headers)
        version = headers.get('x-amz-version-id', '')
        if not re.fullmatch(r'[A-Za-z0-9._~-]{1,256}', version) or version == 'null':
            raise ValueError('Exact synthetic fixture version required')
        fixtures.append((path, version, authority))
        version_path = path + '?versionId=' + quote(version, safe='')
        for read_path in (path, version_path):
            read_headers = {}
            _, content = send(directory, origin, 'GET', read_path, authority=authority, response_headers=read_headers)
            if content != payload.encode() or read_headers.get('x-amz-version-id') != version:
                raise ValueError('Authenticated exact fixture readback failed')
            require_denied(send, directory, origin, 'GET', read_path, authority='anonymous')
        for method, denied_path, data in [('GET', '/' + bucket, None),
                                          ('GET', '/' + bucket + '?versions', None),
                                          ('PUT', path, 'anonymous must not write')]:
            require_denied(send, directory, origin, method, denied_path, data, authority='anonymous')
        if authority == 'app':
            _, listed = send(directory, origin, 'GET', '/' + bucket + '?versions&prefix=' + prefix,
                             authority='app')
            entries = XML.fromstring(listed).findall('{*}Version')
            if not any(e.findtext('{*}Key') == path[len(bucket) + 2:] and
                       e.findtext('{*}VersionId') == version for e in entries):
                raise ValueError('Scoped application version listing failed')
            require_denied(send, directory, origin, 'DELETE', path, authority='app')
            require_denied(send, directory, origin, 'DELETE', version_path, authority='app')
            _, unchanged = send(directory, origin, 'GET', version_path, authority='app')
            if unchanged != payload.encode():
                raise ValueError('Normal delete denial changed immutable object')
            for method, denied_path, body in [('PUT', path, payload), ('DELETE', path, None), ('GET', '/rustfs/admin/v3/info', None), ('PUT', '/dkp-bootstrap-denied', None), ('DELETE', '/' + bucket + '/' + hashlib.sha256(b'outside-tenant').hexdigest() + '/denied?versionId=synthetic', None)]:
                require_denied(send, directory, origin, method, denied_path, body, authority='cleanup')
            send(directory, origin, 'HEAD', version_path, authority='cleanup')
            send(directory, origin, 'DELETE', version_path, authority='cleanup')
            status, absent = send(directory, origin, 'GET', version_path, allowed=(404,), authority='root')
            if status != 404 or XML.fromstring(absent).findtext('{*}Code') not in ('NoSuchKey', 'NoSuchVersion'):
                raise ValueError('Cleanup exact version absence unproven')



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'apply'))
    parser.add_argument('--directory', type=Path, default=authority_root() / 'rustfs')
    parser.add_argument('--endpoint')
    parser.add_argument('--docker-network')
    args = parser.parse_args()
    try:
        if args.action == 'prepare':
            if args.endpoint or args.docker_network:
                raise ValueError('Endpoint belongs to the separate apply operation')
            prepare(args.directory)
        else:
            if not args.endpoint:
                raise ValueError('Apply requires explicit HTTPS endpoint')
            apply(args.directory, args.endpoint, docker_network=args.docker_network)
    except (ValueError, OSError, XML.ParseError, KeyError, TypeError):
        raise SystemExit('Private RustFS authority operation failed; no credentials or private responses printed') from None
    print('Private RustFS authority ' + args.action + ' complete; no production readiness implied')


if __name__ == '__main__':
    main()
