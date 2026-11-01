#!/usr/bin/env python3
"""Isolated issue-104 real RustFS fixture. Never prints credentials or uses the BP02 stack.

Official release interfaces: docs.rustfs.com/en/integration/tls-configured and
/docs/en/security-compliance/iam/policies. Root SigV4 is bootstrap-only.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import subprocess
import time
import uuid

IMAGE = 'rustfs/rustfs:1.0.4@sha256:ed9210d6411d77ffceb70d94b964a3cb7d225983368bc25bebe01e995293dcc2'
LABEL = 'dkp.issue=104'


def run(*args, input=None, check=True, timeout=None):
    result = subprocess.run(args, input=input, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('fixture command failed: ' + args[0] + ' (private diagnostics suppressed)')
    return result


def write(path, text):
    path.write_text(text)
    path.chmod(0o600)


def curl(directory, endpoint, method, path, body=None):
    response = directory / 'last-response.private'
    args = ['curl', '--silent', '--show-error', '--max-time', '15', '--cacert', str(directory / 'ca.pem'),
            '--config', str(directory / 'root-curl.private'), '--aws-sigv4', 'aws:amz:us-east-1:s3',
            '-X', method, '--output', str(response), '--write-out', '%{http_code}', endpoint + path]
    if body is not None:
        payload = directory / 'request.private'
        write(payload, body)
        args += ['--data-binary', '@' + str(payload)]
    result = run(*args, check=False)
    response.chmod(0o600)
    code = result.stdout.decode().strip()
    if result.returncode or not code.isdigit() or int(code) not in range(200, 300):
        raise RuntimeError('RustFS bootstrap rejected ' + method + ' ' + path.split('?')[0] + ' HTTP ' + code)


def owned(name, kind):
    inspect = json.loads(run('docker', kind, 'inspect', name).stdout)[0]
    labels = inspect.get('Labels') if kind in ('volume', 'network') else inspect['Config'].get('Labels')
    if not labels or labels.get('dkp.issue') != '104':
        raise RuntimeError('refusing unrelated Docker resource')


def wait_ready(directory, endpoint):
    # Pinned RustFS /health is liveness only. Probe readiness AND write quorum
    # before the first mutation; never retry an ambiguous bucket/IAM write.
    deadline = time.monotonic() + 60
    statuses = ['unobserved', 'unobserved']
    while time.monotonic() < deadline:
        ready = True
        for index, path in enumerate(('/health/ready', '/minio/health/cluster')):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                ready = False
                break
            timeout = min(2, remaining)
            try:
                result = run('curl', '--disable', '--silent', '--max-time', str(timeout),
                             '--max-filesize', '1024', '--proto', '=https', '--noproxy', '*',
                             '--cacert', str(directory / 'ca.pem'), '--output', os.devnull,
                             '--write-out', '%{http_code}', endpoint + path,
                             check=False, timeout=timeout)
                statuses[index] = (result.stdout.decode('ascii')
                                   if result.returncode == 0 and re.fullmatch(b'[0-9]{3}', result.stdout)
                                   else 'unavailable')
            except (OSError, subprocess.TimeoutExpired):
                statuses[index] = 'unavailable'
            if statuses[index] != '200':
                ready = False
                break
        if ready and time.monotonic() < deadline:
            return
        time.sleep(min(1, max(0, deadline - time.monotonic())))
    raise RuntimeError('RustFS native readiness unavailable (ready=' + statuses[0]
                       + ', write=' + statuses[1] + '); no bootstrap write dispatched')


def prepare(directory):
    directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    directory.chmod(0o700)
    for part in ('server', 'app'):
        (directory / part).mkdir(mode=0o700)
    root_access, root_secret = secrets.token_hex(16), secrets.token_hex(32)
    app_access, app_secret = secrets.token_hex(16), secrets.token_hex(32)
    cleanup_access, cleanup_secret = secrets.token_hex(16), secrets.token_hex(32)
    write(directory / 'server/root-access', root_access + '\n')
    write(directory / 'server/root-secret', root_secret + '\n')
    write(directory / 'app/access-key', app_access + '\n')
    write(directory / 'app/secret-key', app_secret + '\n')
    write(directory / 'app/cleanup-access-key', cleanup_access + '\n')
    write(directory / 'app/cleanup-secret-key', cleanup_secret + '\n')
    for role, access, secret, name in [('normal', app_access, app_secret, 'access-key'), ('cleanup', cleanup_access, cleanup_secret, 'cleanup-access-key')]:
        write(directory / ('app/' + name + '.role'), 'dkp-storage-' + role + '-v1\n' + hashlib.sha256(access.encode()).hexdigest() + '\n' + hashlib.sha256(secret.encode()).hexdigest() + '\n')
    write(directory / 'app/.spool-authority', 'dkp-private-spool-v1\n')
    write(directory / 'root-curl.private', 'user = "' + root_access + ':' + root_secret + '"\n')
    run('openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '3', '-subj', '/CN=DKP104-isolated-CA',
        '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
        '-keyout', str(directory / 'ca.key'), '-out', str(directory / 'ca.pem'))
    run('openssl', 'req', '-newkey', 'rsa:3072', '-nodes', '-subj', '/CN=rustfs-compat',
        '-keyout', str(directory / 'server/rustfs_key.pem'), '-out', str(directory / 'server.csr'))
    write(directory / 'server.ext', 'subjectAltName=DNS:rustfs-compat,DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
    run('openssl', 'x509', '-req', '-in', str(directory / 'server.csr'), '-CA', str(directory / 'ca.pem'),
        '-CAkey', str(directory / 'ca.key'), '-CAcreateserial', '-days', '3', '-extfile', str(directory / 'server.ext'),
        '-out', str(directory / 'server/rustfs_cert.pem'))
    write(directory / 'app/ca.pem', (directory / 'ca.pem').read_text())
    run('openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '3', '-subj', '/CN=DKP104-untrusted-CA',
        '-addext', 'basicConstraints=critical,CA:TRUE', '-keyout', str(directory / 'untrusted-ca.key'), '-out', str(directory / 'app/untrusted-ca.pem'))
    write(directory / 'app/non-ca.pem', (directory / 'server/rustfs_cert.pem').read_text())
    for file in directory.rglob('*'):
        if file.is_file(): file.chmod(0o600)
    prefix = 'dkp104-' + uuid.uuid4().hex[:12]
    state = {'container': prefix, 'network': prefix + '-net', 'data': prefix + '-data',
             'server': prefix + '-server', 'authority': prefix + '-authority', 'image': IMAGE,
             'tenant': 'rustfs-compat-tenant', 'document': str(uuid.uuid4())}
    state['prefix'] = hashlib.sha256(state['tenant'].encode()).hexdigest() + '/' + state['document'] + '/'
    write(directory / 'state.json', json.dumps(state, indent=2) + '\n')
    run('docker', 'network', 'create', '--label', LABEL, state['network'])
    for volume in ('data', 'server', 'authority'):
        run('docker', 'volume', 'create', '--label', LABEL, state[volume])
    helper = prefix + '-prepare'
    run('docker', 'run', '-d', '--name', helper, '--label', LABEL, '--network', 'none', '--user', '0',
        '-v', state['server'] + ':/server', '-v', state['authority'] + ':/authority', '-v', state['data'] + ':/data',
        '--entrypoint', '/bin/sh', IMAGE, '-c', 'sleep 300')
    try:
        run('docker', 'cp', str(directory / 'server') + '/.', helper + ':/server')
        run('docker', 'cp', str(directory / 'app') + '/.', helper + ':/authority')
        run('docker', 'exec', helper, '/bin/sh', '-c', 'chown -R 10001:10001 /server /authority /data && chmod 0700 /server /authority /data && chmod 0600 /server/* /authority/* /authority/.spool-authority')
    finally:
        run('docker', 'rm', '-f', helper)
    run('docker', 'run', '-d', '--name', prefix, '--label', LABEL, '--network', state['network'],
        '--network-alias', 'rustfs-compat', '--network-alias', 'rustfs-compat-wrong', '-p', '127.0.0.1::9000',
        '-v', state['data'] + ':/data', '-v', state['server'] + ':/run/private:ro',
        '-e', 'RUSTFS_ACCESS_KEY_FILE=/run/private/root-access', '-e', 'RUSTFS_SECRET_KEY_FILE=/run/private/root-secret',
        '-e', 'RUSTFS_TLS_PATH=/run/private', '-e', 'RUSTFS_CHECK_UPDATE=false',
        '-e', 'RUSTFS_CONSOLE_ENABLE=false', IMAGE)
    info = json.loads(run('docker', 'inspect', prefix).stdout)[0]
    state['endpoint'] = 'https://localhost:' + info['NetworkSettings']['Ports']['9000/tcp'][0]['HostPort']
    write(directory / 'state.json', json.dumps(state, indent=2) + '\n')
    wait_ready(directory, state['endpoint'])
    for bucket in ('dkp-quarantine', 'dkp-originals', 'dkp-outside'):
        curl(directory, state['endpoint'], 'PUT', '/' + bucket)
        curl(directory, state['endpoint'], 'PUT', '/' + bucket + '?versioning', '<VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Status>Enabled</Status></VersioningConfiguration>')
    policy = {'Version': '2012-10-17', 'Statement': [
        {'Effect': 'Allow', 'Action': ['s3:GetBucketVersioning'], 'Resource': ['arn:aws:s3:::' + b for b in ('dkp-quarantine', 'dkp-originals')]},
        {'Effect': 'Allow', 'Action': ['s3:ListBucketVersions'], 'Resource': ['arn:aws:s3:::' + b for b in ('dkp-quarantine', 'dkp-originals')],
         'Condition': {'StringLike': {'s3:prefix': [state['prefix'] + '*']}}},
        {'Effect': 'Allow', 'Action': ['s3:GetObject', 's3:GetObjectVersion', 's3:PutObject'],
         'Resource': ['arn:aws:s3:::' + b + '/' + state['prefix'] + '*' for b in ('dkp-quarantine', 'dkp-originals')]}
    ]}
    curl(directory, state['endpoint'], 'PUT', '/rustfs/admin/v3/add-canned-policy?name=dkp104-scoped', json.dumps(policy))
    curl(directory, state['endpoint'], 'PUT', '/rustfs/admin/v3/add-user?accessKey=' + app_access, json.dumps({'secretKey': app_secret, 'status': 'enabled'}))
    curl(directory, state['endpoint'], 'PUT', '/rustfs/admin/v3/set-user-or-group-policy?policyName=dkp104-scoped&userOrGroup=' + app_access + '&isGroup=false')
    policy['Statement'][2]['Action'] = ['s3:GetObjectVersion', 's3:DeleteObjectVersion']
    curl(directory, state['endpoint'], 'PUT', '/rustfs/admin/v3/add-canned-policy?name=dkp104-cleanup', json.dumps(policy))
    curl(directory, state['endpoint'], 'PUT', '/rustfs/admin/v3/add-user?accessKey=' + cleanup_access, json.dumps({'secretKey': cleanup_secret, 'status': 'enabled'}))
    curl(directory, state['endpoint'], 'PUT', '/rustfs/admin/v3/set-user-or-group-policy?policyName=dkp104-cleanup&userOrGroup=' + cleanup_access + '&isGroup=false')
    print('RustFS fixture ready; root bootstrap complete; native IAM scoped app authority volume=' + state['authority'])


def socket_group(image):
    # Host metadata differs from the mounted socket on Docker Desktop. Inspect
    # only the actual mount in the already-built trusted test image, without
    # connecting to the socket or relaxing its permissions.
    args = ['docker', 'run', '--rm', '--pull=never', '--network', 'none', '--read-only',
            '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--user', '10001:0',
            '--memory', '64m', '--pids-limit', '16',
            '--mount', 'type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock,readonly',
            '--entrypoint', 'stat', image, '-c', '%g', '/var/run/docker.sock']
    try:
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError('Docker socket group inspection failed; diagnostics suppressed') from None
    value = result.stdout.decode('ascii', errors='replace').strip()
    if result.returncode or re.fullmatch(r'0|[1-9][0-9]{0,9}', value) is None or int(value) > 2147483647:
        raise RuntimeError('Invalid mounted Docker socket group')
    return value


def execute(directory, image):
    state = json.loads((directory / 'state.json').read_text())
    owned(state['container'], 'container')
    env = {'DKP_RUSTFS_COMPAT_ENDPOINT': 'https://rustfs-compat:9000',
           'DKP_RUSTFS_COMPAT_AUTHORITY': '/authority', 'DKP_RUSTFS_COMPAT_PREFIX': state['prefix'],
           'DKP_RUSTFS_COMPAT_TENANT': state['tenant'], 'DKP_RUSTFS_COMPAT_CONTAINER': state['container'],
           'GRADLE_USER_HOME': '/tmp/dkp-gradle', 'TESTCONTAINERS_HOST_OVERRIDE': 'host.docker.internal',
           'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE': '/var/run/docker.sock'}
    args = ['docker', 'run', '--name', state['container'] + '-test', '--label', LABEL, '--memory', '4g', '--cpus', '4',
            '--network', state['network'], '--add-host', 'host.docker.internal:host-gateway',
            '--group-add', socket_group(image), '-v', state['authority'] + ':/authority',
            '-v', 'dkp-bp02-gradle-cache:/tmp/dkp-gradle', '-v', '/var/run/docker.sock:/var/run/docker.sock']
    for name, value in env.items(): args += ['-e', name + '=' + value]
    args += [image, './gradlew', '--no-daemon', '--max-workers=2', '--rerun-tasks', ':services:document-management:adapters-out:test',
             ':services:document-management:adapters-out:persistenceCoverageVerification',
             ':services:document-management:adapters-out:verificationCoverageVerification',
             ':services:document-management:adapters-out:storageCoverageVerification']
    reportsRoot = directory / 'reports'
    reportsRoot.mkdir(mode=0o700, exist_ok=True)
    reports = reportsRoot / uuid.uuid4().hex[:12]
    reports.mkdir(mode=0o700)
    write(directory / 'latest-reports.private', str(reports) + '\n')
    try:
        result = subprocess.run(args)
        for source, name in [('/workspace/services/document-management/adapters-out/build/test-results/test', 'junit'),
                             ('/workspace/services/document-management/adapters-out/build/reports/jacoco/persistenceCoverageReport/persistenceCoverageReport.xml', 'persistence-coverage.xml'),
                             ('/workspace/services/document-management/adapters-out/build/reports/jacoco/verificationCoverageReport/verificationCoverageReport.xml', 'verification-coverage.xml'),
                             ('/workspace/services/document-management/adapters-out/build/reports/jacoco/storageCoverageReport/storageCoverageReport.xml', 'storage-coverage.xml')]:
            run('docker', 'cp', state['container'] + '-test:' + source, str(reports / name), check=False)
        if result.returncode: raise RuntimeError('real RustFS integration gate failed; inspect isolated reports')
    finally:
        owned(state['container'] + '-test', 'container')
        run('docker', 'rm', '-f', state['container'] + '-test')


def cleanup(directory):
    state = json.loads((directory / 'state.json').read_text())
    owned(state['container'], 'container')
    run('docker', 'rm', '-f', state['container'])
    for kind, keys in [('volume', ('data', 'server', 'authority')), ('network', ('network',))]:
        for key in keys:
            owned(state[key], kind)
            run('docker', kind, 'rm', state[key])
    print('Removed only labeled issue-104 Docker resources; private local files retained for deliberate cleanup')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['prepare', 'cleanup', 'run'])
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--image')
    args = parser.parse_args()
    directory = args.directory.resolve()
    allowed = Path('local-data/security').resolve()
    if not directory.is_relative_to(allowed) or directory == allowed:
        parser.error('fixture directory must be beneath ignored local-data/security')
    try:
        if args.action == 'run':
            if not args.image: parser.error('--image is required for run')
            execute(directory, args.image)
        else: (prepare if args.action == 'prepare' else cleanup)(directory)
    except Exception as failure:
        raise SystemExit(str(failure)) from None

if __name__ == '__main__': main()
