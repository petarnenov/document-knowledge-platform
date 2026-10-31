#!/usr/bin/env python3
"""Generate isolated synthetic development identity material; never global trust."""

import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = Path(__file__).with_name('realm-template.json')
REQUIRED = ('ca.crt', 'ca.key', 'portal.crt', 'portal.key', 'idp.crt', 'idp.key',
            'truststore.p12', 'java-security.options', 'dkp-realm.json', 'runtime.env',
            'authority/grants.json', 'authority/cursor.key')
ACTIONS = ('document.create', 'document.inspect-draft', 'document.read',
           'document.update-metadata', 'document.acl.restrict', 'document.acl.delegate',
           'document.version.append', 'document.legal-hold.place', 'document.legal-hold.inspect')


def run(*args, env=None):
    result = subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, env=env)
    if result.returncode:
        # Do not echo subprocess arguments/output: a provider could include secrets.
        raise RuntimeError(f'Local security tool failed: {Path(args[0]).name}')


def generate(destination, *, keytool=None, runtime_uid=None, runtime_gid=None):
    destination = Path(destination).absolute()
    if destination.is_symlink():
        raise ValueError('Refusing a symlink security directory')
    if destination.exists():
        if not destination.is_dir() or not all((destination / name).is_file() and not (destination / name).is_symlink() for name in REQUIRED):
            raise ValueError('Incomplete security directory; preserve it and inspect before replacing')
        if destination.stat().st_mode & 0o077:
            raise ValueError('Security directory must be owner-only')
        for path in destination.iterdir():
            if path.is_file() and path.stat().st_mode & 0o077:
                raise ValueError('Security files must be owner-only')
        authority = destination / 'authority'
        if authority.is_symlink() or authority.stat().st_mode & 0o227:
            raise ValueError('Authority directory must be read-only and private to its owner/group')
        for name in ('grants.json', 'cursor.key'):
            if (authority / name).stat().st_mode & 0o377:
                raise ValueError('Authority files must be owner-read-only')
        run('openssl', 'x509', '-checkend', '3600', '-noout', '-in', str(destination / 'portal.crt'))
        run('openssl', 'x509', '-checkend', '3600', '-noout', '-in', str(destination / 'idp.crt'))
        return False
    runtime_uid = os.getuid() if runtime_uid is None else runtime_uid
    runtime_gid = os.getgid() if runtime_gid is None else runtime_gid
    if runtime_uid <= 0 or runtime_gid < 0:
        raise ValueError('A non-root runtime UID is required')
    if keytool is None:
        java_home = os.environ.get('JAVA_HOME')
        keytool = str(Path(java_home) / 'bin/keytool') if java_home else shutil.which('keytool')
    if not keytool or not shutil.which('openssl'):
        raise ValueError('OpenSSL and JDK keytool are required')
    destination.parent.mkdir(parents=True, exist_ok=True)
    old_umask = os.umask(0o077)
    scratch = Path(tempfile.mkdtemp(prefix='.dkp-security-', dir=destination.parent))
    try:
        material = {name: secrets.token_urlsafe(36) for name in ('CLIENT_SECRET', 'USER_PASSWORD', 'ADMIN_PASSWORD', 'TRUSTSTORE_PASSWORD',
                    'DB_ADMIN_PASSWORD', 'DB_MIGRATOR_PASSWORD', 'DB_RUNTIME_PASSWORD')}
        run('openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-sha256', '-nodes',
            '-days', '365', '-subj', '/CN=DKP isolated development CA',
            '-keyout', str(scratch / 'ca.key'), '-out', str(scratch / 'ca.crt'),
            '-addext', 'basicConstraints=critical,CA:TRUE',
            '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
        for name, dns in [('portal', 'localhost'), ('idp', 'idp.localhost')]:
            config = scratch / f'{name}.ext'
            config.write_text(f'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:{dns},IP:127.0.0.1\n')
            run('openssl', 'req', '-new', '-newkey', 'rsa:3072', '-nodes', '-sha256',
                '-subj', f'/CN={dns}', '-keyout', str(scratch / f'{name}.key'),
                '-out', str(scratch / f'{name}.csr'))
            run('openssl', 'x509', '-req', '-sha256', '-days', '30',
                '-in', str(scratch / f'{name}.csr'), '-CA', str(scratch / 'ca.crt'),
                '-CAkey', str(scratch / 'ca.key'), '-CAcreateserial',
                '-out', str(scratch / f'{name}.crt'), '-extfile', str(config))
            (scratch / f'{name}.csr').unlink()
            config.unlink()
        env = dict(os.environ, DKP_BOOTSTRAP_STORE_PASSWORD=material['TRUSTSTORE_PASSWORD'])
        run(keytool, '-importcert', '-noprompt', '-alias', 'dkp-local-ca',
            '-file', str(scratch / 'ca.crt'), '-keystore', str(scratch / 'truststore.p12'),
            '-storetype', 'PKCS12', '-storepass:env', 'DKP_BOOTSTRAP_STORE_PASSWORD', env=env)
        (scratch / 'java-security.options').write_text('-Djavax.net.ssl.trustStore=/run/dkp/truststore.p12\n-Djavax.net.ssl.trustStoreType=PKCS12\n-Djavax.net.ssl.trustStorePassword=' + material['TRUSTSTORE_PASSWORD'] + '\n')
        template = TEMPLATE.read_text()
        for name in ('CLIENT_SECRET', 'USER_PASSWORD'):
            template = template.replace(f'@@{name}@@', material[name])
        realm = json.loads(template)
        if '@@' in template:
            raise ValueError('Unresolved realm placeholder')
        (scratch / 'dkp-realm.json').write_text(json.dumps(realm, indent=2) + '\n')
        user = realm['users'][0]
        if user.get('username') != 'dkp-tester' or not user.get('id'):
            raise ValueError('Explicit synthetic realm subject required')
        authority = scratch / 'authority'
        authority.mkdir(mode=0o700)
        grants = {'schemaVersion': 1, 'entries': [
            {'issuer': 'https://idp.localhost:18444/realms/dkp', 'subject': user['id'],
             'tenant': tenant, 'actions': list(ACTIONS), 'delegationCeiling': list(ACTIONS)}
            for tenant in user['attributes']['dkp_tenants']]}
        (authority / 'grants.json').write_text(json.dumps(grants, indent=2) + '\n')
        (authority / 'cursor.key').write_text(base64.b64encode(secrets.token_bytes(32)).decode() + '\n')
        for path in authority.iterdir():
            path.chmod(0o400)
        authority.chmod(0o550)
        values = {'DKP_LOCAL_UID': str(runtime_uid), 'DKP_LOCAL_GID': str(runtime_gid),
                  'OIDC_CLIENT_SECRET': material['CLIENT_SECRET'],
                  'DKP_TEST_USERNAME': 'dkp-tester', 'DKP_TEST_PASSWORD': material['USER_PASSWORD'],
                  'KC_BOOTSTRAP_ADMIN_USERNAME': 'dkp-local-admin',
                  'KC_BOOTSTRAP_ADMIN_PASSWORD': material['ADMIN_PASSWORD'],
                  'DKP_TRUSTSTORE_PASSWORD': material['TRUSTSTORE_PASSWORD'],
                  'DKP_DB_ADMIN_PASSWORD': material['DB_ADMIN_PASSWORD'],
                  'DKP_DM_MIGRATOR_PASSWORD': material['DB_MIGRATOR_PASSWORD'],
                  'DKP_DM_RUNTIME_PASSWORD': material['DB_RUNTIME_PASSWORD']}
        (scratch / 'runtime.env').write_text(''.join(f'{name}={value}\n' for name, value in values.items()))
        for path in scratch.iterdir():
            if path.is_file():
                path.chmod(0o600)
        scratch.chmod(0o700)
        scratch.rename(destination)
    finally:
        os.umask(old_umask)
        if scratch.exists():
            for path in scratch.iterdir():
                if path.is_dir():
                    path.chmod(0o700)
            shutil.rmtree(scratch)
    return True


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ROOT / 'local-data/security')
    arguments = parser.parse_args()
    created = generate(arguments.directory)
    print('Local security material generated.' if created else 'Existing local security material verified; credentials preserved.')
