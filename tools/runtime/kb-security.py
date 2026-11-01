#!/usr/bin/env python3
"""Prepare fresh synthetic BP04 authority. Never starts, adopts or alters a runtime."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import stat
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
ISSUER = 'https://idp.localhost:20444/realms/dkp'
PORTAL = 'https://localhost:20443'
KB_ACTIONS = ('knowledge.collection.create', 'knowledge.collection.inspect',
              'knowledge.collection.update', 'knowledge.collection.archive',
              'knowledge.acl.restrict', 'knowledge.acl.delegate',
              'knowledge.audience.manage', 'knowledge.membership.manage',
              'knowledge.policy.evaluate')
ENV_KEYS = {'DKP_LOCAL_UID', 'DKP_LOCAL_GID', 'OIDC_CLIENT_SECRET', 'DKP_TEST_USERNAME',
            'DKP_TEST_PASSWORD', 'KC_BOOTSTRAP_ADMIN_USERNAME', 'KC_BOOTSTRAP_ADMIN_PASSWORD',
            'DKP_TRUSTSTORE_PASSWORD', 'DKP_DB_ADMIN_PASSWORD', 'DKP_DM_MIGRATOR_PASSWORD',
            'DKP_DM_RUNTIME_PASSWORD', 'DKP_KB_ADMIN_PASSWORD', 'DKP_KB_MIGRATOR_PASSWORD',
            'DKP_KB_RUNTIME_PASSWORD', 'DKP_KB_SECURITY_DIRECTORY'}


def tool(*arguments):
    result = subprocess.run(arguments, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            timeout=60, check=False)
    if result.returncode:
        raise ValueError('Required local security tool failed')


def baseline(destination):
    path = ROOT / 'infra/docker-compose/bootstrap-security.py'
    spec = importlib.util.spec_from_file_location('dkp_kb_identity_bootstrap', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.generate(destination)


def safe_path(destination):
    destination = Path(os.path.abspath(destination))
    allowed = ROOT / 'local-data/security'
    if not destination.is_relative_to(allowed) or destination == allowed:
        raise ValueError('Use a fresh ignored security child directory')
    for path in (destination, *destination.parents):
        if path.exists() or path.is_symlink():
            info = path.lstat()
            if (not stat.S_ISDIR(info.st_mode) or info.st_uid not in (0, os.getuid())
                    or info.st_mode & 0o1022):
                raise ValueError('Unsafe security directory ancestry')
    return destination


def write(path, value, mode=0o400):
    path.write_text(value)
    path.chmod(mode)


def env_read(path):
    if path.stat().st_size > 32768:
        raise ValueError('Oversized private configuration')
    result = {}
    for line in path.read_text().splitlines():
        if '=' not in line:
            raise ValueError('Malformed private configuration')
        name, value = line.split('=', 1)
        if name in result or not name or not value or any(c in value for c in '\r\n\x00'):
            raise ValueError('Malformed private configuration')
        result[name] = value
    return result


def files(directory):
    result = {}
    for path in sorted(directory.rglob('*')):
        info = path.lstat()
        if (stat.S_ISLNK(info.st_mode) or info.st_uid != os.getuid()
                or info.st_mode & 0o077 or not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode))):
            raise ValueError('Unsafe private material')
        if path.is_file() and path != directory / 'state.json':
            if info.st_size > 1048576:
                raise ValueError('Oversized private material')
            result[str(path.relative_to(directory))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def authority(identity):
    realm = json.loads((identity / 'dkp-realm.json').read_text())
    users = realm['users']
    if len(users) != 1 or users[0].get('username') != 'dkp-tester':
        raise ValueError('Exact synthetic actor required')
    user = users[0]
    import uuid
    if str(uuid.UUID(user['id'])) != user['id'] or user['attributes']['dkp_tenants'] != ['tenant-a', 'tenant-b']:
        raise ValueError('Exact synthetic actor and tenant set required')
    clients = [client for client in realm['clients'] if client['clientId'] == 'dkp-bff']
    if len(clients) != 1:
        raise ValueError('Exact synthetic browser client required')
    clients[0]['redirectUris'] = [PORTAL + '/oauth/callback/keycloak']
    clients[0]['webOrigins'] = [PORTAL]
    write(identity / 'dkp-realm.json', json.dumps(realm, indent=2) + '\n', 0o600)
    grants_path = identity / 'authority/grants.json'
    grants = json.loads(grants_path.read_text())
    if {entry['tenant'] for entry in grants['entries']} != {'tenant-a', 'tenant-b'} or len(grants['entries']) != 2:
        raise ValueError('Exact synthetic DM grants required')
    for entry in grants['entries']:
        if entry['subject'] != user['id']:
            raise ValueError('Synthetic subject mismatch')
        entry['issuer'] = ISSUER
        for action in ('document.content.review', 'document.share-to-collection'):
            if action not in entry['actions']:
                entry['actions'].append(action)
            if action not in entry['delegationCeiling']:
                entry['delegationCeiling'].append(action)
    grants_path.chmod(0o600)
    write(grants_path, json.dumps(grants, indent=2) + '\n')
    return user['id']


def tls(directory):
    private = directory / 'dm-ca'
    server = directory / 'dm-proxy'
    for path in (private, server):
        path.mkdir(mode=0o700)
    tool('openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-sha256', '-nodes',
         '-days', '365', '-subj', '/CN=DKP isolated KB DM development CA',
         '-keyout', str(private / 'ca.key'), '-out', str(private / 'ca.crt'),
         '-addext', 'basicConstraints=critical,CA:TRUE', '-addext', 'keyUsage=critical,keyCertSign,cRLSign')
    extension = private / 'server.ext'
    extension.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:dm-sharing\n')
    tool('openssl', 'req', '-new', '-newkey', 'rsa:3072', '-nodes', '-sha256',
         '-subj', '/CN=dm-sharing', '-keyout', str(server / 'server.key'), '-out', str(private / 'server.csr'))
    tool('openssl', 'x509', '-req', '-sha256', '-days', '30', '-in', str(private / 'server.csr'),
         '-CA', str(private / 'ca.crt'), '-CAkey', str(private / 'ca.key'), '-CAcreateserial',
         '-out', str(server / 'server.crt'), '-extfile', str(extension))
    shutil.copyfile(private / 'ca.crt', directory / 'kb-authority/dm-ca.crt')


def verify(directory):
    directory = safe_path(directory)
    state_path = directory / 'state.json'
    if state_path.is_symlink() or not state_path.is_file() or state_path.stat().st_size > 32768:
        raise ValueError('Missing complete owned material record')
    state = json.loads(state_path.read_text())
    if (set(state) != {'schemaVersion', 'profile', 'uid', 'gid', 'files'} or state['schemaVersion'] != 1
            or state['profile'] != 'dkp-bp04' or state['uid'] != os.getuid() or state['gid'] != os.getgid()
            or state['files'] != files(directory)):
        raise ValueError('Existing material differs; preserve and use a new directory')
    values = env_read(directory / 'runtime.env')
    if set(values) != ENV_KEYS or values['DKP_KB_SECURITY_DIRECTORY'] != str(directory):
        raise ValueError('Unexpected private configuration')
    passwords = [values[name] for name in ENV_KEYS if name.endswith('_PASSWORD')]
    if len(passwords) != len(set(passwords)):
        raise ValueError('Distinct private passwords required')
    key = (directory / 'kb-authority/cursor.key').read_text().removesuffix('\n')
    if len(base64.b64decode(key, validate=True)) != 32:
        raise ValueError('Invalid cursor authority')
    for path in ('identity/portal.crt', 'identity/idp.crt', 'dm-proxy/server.crt'):
        tool('openssl', 'x509', '-checkend', '3600', '-noout', '-in', str(directory / path))
    return False


def prepare(directory):
    destination = safe_path(directory)
    if destination.exists():
        return verify(destination)
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    old_umask = os.umask(0o077)
    scratch = Path(tempfile.mkdtemp(prefix='.kb-private-', dir=destination.parent))
    try:
        baseline(scratch / 'identity')
        actor = authority(scratch / 'identity')
        kb = scratch / 'kb-authority'
        kb.mkdir(mode=0o700)
        grants = {'schemaVersion': 1, 'entries': [
            {'issuer': ISSUER, 'subject': actor, 'tenant': tenant, 'actions': list(KB_ACTIONS),
             'delegationCeiling': list(KB_ACTIONS), 'firms': ['synthetic-firm'],
             'resourceScopes': ['synthetic-resource-scope']} for tenant in ('tenant-a', 'tenant-b')]}
        write(kb / 'grants.json', json.dumps(grants, indent=2) + '\n')
        write(kb / 'cursor.key', base64.b64encode(secrets.token_bytes(32)).decode() + '\n')
        tls(scratch)
        values = env_read(scratch / 'identity/runtime.env')
        values.update({name: secrets.token_urlsafe(36) for name in ('DKP_KB_ADMIN_PASSWORD', 'DKP_KB_MIGRATOR_PASSWORD', 'DKP_KB_RUNTIME_PASSWORD')})
        values['DKP_KB_SECURITY_DIRECTORY'] = str(destination)
        write(scratch / 'runtime.env', ''.join(f'{name}={value}\n' for name, value in values.items()), 0o600)
        for path in scratch.rglob('*'):
            path.chmod(0o700 if path.is_dir() else 0o400)
        (scratch / 'runtime.env').chmod(0o600)
        state = {'schemaVersion': 1, 'profile': 'dkp-bp04', 'uid': os.getuid(), 'gid': os.getgid(), 'files': files(scratch)}
        write(scratch / 'state.json', json.dumps(state, sort_keys=True) + '\n', 0o600)
        scratch.rename(destination)
    finally:
        os.umask(old_umask)
        if scratch.exists():
            shutil.rmtree(scratch)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'verify'))
    parser.add_argument('--directory', type=Path, required=True)
    arguments = parser.parse_args()
    try:
        created = prepare(arguments.directory) if arguments.command == 'prepare' else verify(arguments.directory)
    except Exception:
        print('KB private material validation failed; existing material preserved.')
        return 1
    print('Fresh KB private material prepared.' if created else 'Existing KB private material verified.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
