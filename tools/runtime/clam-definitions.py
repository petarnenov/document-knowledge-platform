#!/usr/bin/env python3
"""Publish a new read-only generation only from a completed real FreshClam update.

The updater's staging volume is never mounted into an active scanner or DM.
Published generations are mounted read-only into both and are never overwritten.
"""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
import re
import uuid
from pathlib import Path
import shutil
import subprocess
import tempfile

IMAGE = 'clamav/clamav:1.5.4@sha256:bda39705e291105049c3936e2803ac90b955172af8030f94f05dce1252df6c6d'
MAX_DATABASE_BYTES = 1_000_000_000
UPDATE_COMMAND = ['--config-file=/run/dkp/freshclam.conf', '--stdout']
# Reviewed profile: real standard mirror, bytecode and database load-testing enabled.
CONFIGURATION_DIGEST = 'a0318e8cf340ffa9658e2675390c420fc60a9a551ba13c0fff197be9a4211ab1'
ENVIRONMENT = ['PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin', 'TZ=Etc/UTC']
TMPFS = {'/tmp': 'uid=100,gid=101,mode=0700,size=1g'}
RECORD_FIELDS = {'schemaVersion', 'runId', 'containerId', 'containerName', 'imageId',
                 'stagingVolume', 'configurationPath', 'configurationDigest', 'recordedAt'}


def scanner_root():
    return Path(__file__).resolve().parents[2] / 'local-data/scanner'


def confined(path):
    path = path.absolute()
    allowed = scanner_root().resolve()
    if (not path.resolve().is_relative_to(allowed) or path.resolve() == allowed
            or any(p.is_symlink() for p in [path, *path.parents])):
        raise ValueError('Path must be beneath ignored local-data/scanner without symlinks')
    return path


def docker_json(*arguments):
    return json.loads(subprocess.check_output(['docker', *arguments], text=True))


def configuration(path):
    if (path.is_symlink() or not path.is_file() or path.stat().st_size > 4096
            or hashlib.sha256(path.read_bytes()).hexdigest() != CONFIGURATION_DIGEST):
        raise ValueError('Exact reviewed standard FreshClam configuration is required')


def labels(run_id):
    return {'dkp.issue': '97', 'dkp.clam.run': run_id}


def exclusive_volume(record):
    volume = docker_json('volume', 'inspect', record['stagingVolume'])[0]
    if (volume['Name'] != record['stagingVolume'] or volume['Driver'] != 'local'
            or volume.get('Options') not in (None, {}) or volume.get('Scope') != 'local'
            or volume.get('Labels') != labels(record['runId'])):
        raise ValueError('An exclusively owned local staging volume is required')
    references = subprocess.check_output(['docker', 'ps', '-aq', '--no-trunc', '--filter',
                                         'volume=' + record['stagingVolume']], text=True).splitlines()
    if references != [record['containerId']]:
        raise ValueError('Staging volume must reference only its recorded updater')
    return volume


def read_record(path):
    path = confined(path)
    if (not path.is_file() or path.stat().st_size > 16384 or path.stat().st_mode & 0o077
            or path.stat().st_uid != os.getuid()):
        raise ValueError('Private operator-owned pre-launch record is required')
    record = json.loads(path.read_text())
    if (set(record) != RECORD_FIELDS or record['schemaVersion'] != 1
            or not re.fullmatch(r'[0-9a-f]{32}', record['runId'])
            or record['containerName'] != 'dkp97-freshclam-' + record['runId']
            or record['stagingVolume'] != 'dkp97-clam-staging-' + record['runId']
            or not re.fullmatch(r'[0-9a-f]{64}', record['containerId'])
            or not re.fullmatch(r'sha256:[0-9a-f]{64}', record['imageId'])
            or record['configurationDigest'] != CONFIGURATION_DIGEST
            or record['configurationPath'] != str(Path(__file__).resolve().parents[2] / 'infra/docker-compose/freshclam.conf')):
        raise ValueError('Invalid closed updater run record')
    return record


def attest(record, *, completed):
    """Assumes the local Docker daemon, operator and reviewed config remain trusted.

    These inspect checks detect unsafe or concurrent ordinary executions; they do not
    authenticate an adversarial host operator capable of forging Docker metadata/records.
    """
    item = docker_json('inspect', record['containerId'])[0]
    pinned = docker_json('image', 'inspect', IMAGE)[0]
    state, config, host = item['State'], item['Config'], item['HostConfig']
    configuration_path = Path(record['configurationPath'])
    configuration(configuration_path)
    volume = exclusive_volume(record)
    expected_mounts = {('/run/dkp/freshclam.conf', 'bind', False, str(configuration_path)),
                       ('/var/lib/clamav', 'volume', True, volume['Mountpoint'])}
    actual_mounts = {(m['Destination'], m['Type'], m['RW'], m['Source']) for m in item['Mounts']}
    volume_mount = [m for m in item['Mounts'] if m['Destination'] == '/var/lib/clamav']
    if (item['Id'] != record['containerId'] or item['Name'] != '/' + record['containerName']
            or item['Image'] != record['imageId'] or pinned['Id'] != record['imageId']
            or config['Image'] != IMAGE or config['Entrypoint'] != ['freshclam']
            or config['Cmd'] != UPDATE_COMMAND
            or config['Labels'] != {**(pinned.get('Config', {}).get('Labels') or {}), **labels(record['runId'])}
            or config['User'] != '100:101' or sorted(config['Env']) != sorted(ENVIRONMENT)
            or not host['ReadonlyRootfs'] or host['Privileged'] or host.get('CapAdd')
            or host.get('CapDrop') != ['ALL']
            or host.get('SecurityOpt') not in (['no-new-privileges:true'], ['no-new-privileges'])
            or host.get('Tmpfs') != TMPFS or host.get('Memory') != 4 * 1024 ** 3
            or host.get('NanoCpus') != 2_000_000_000 or host.get('PidsLimit') != 64
            or host.get('NetworkMode') != 'bridge' or host.get('PidMode') not in ('', None)
            or host.get('IpcMode') != 'private' or host.get('UTSMode') not in ('', None)
            or host.get('UsernsMode') not in ('', None) or host.get('CgroupnsMode') != 'private'
            or any(host.get(k) for k in ('Devices', 'DeviceRequests', 'VolumesFrom', 'GroupAdd', 'ExtraHosts', 'Dns', 'DnsSearch', 'DnsOptions', 'Sysctls'))
            or len(item['Mounts']) != 2 or actual_mounts != expected_mounts
            or len(volume_mount) != 1 or volume_mount[0].get('Name') != record['stagingVolume']):
        raise ValueError('Exact reviewed FreshClam execution profile is required')
    recorded = datetime.fromisoformat(record['recordedAt'].replace('Z', '+00:00'))
    created = datetime.fromisoformat(item['Created'].replace('Z', '+00:00'))
    if recorded.tzinfo is None or created > recorded:
        raise ValueError('Record must follow creation and precede execution')
    if not completed:
        if state['Status'] != 'created' or state['Running'] or not state['StartedAt'].startswith('0001-'):
            raise ValueError('Run records cannot be backfilled after execution')
        return None
    started = datetime.fromisoformat(state['StartedAt'].replace('Z', '+00:00'))
    finished = datetime.fromisoformat(state['FinishedAt'].replace('Z', '+00:00'))
    now = datetime.now(timezone.utc)
    if (state['Status'] != 'exited' or state['Running'] or state['ExitCode'] != 0
            or state.get('OOMKilled') or state.get('Error') or not created <= recorded < started <= finished
            or not now - timedelta(hours=24) <= finished <= now):
        raise ValueError('A recent successful recorded FreshClam execution is required')
    return finished


def launch_updater():
    """Create, attest and record a new run before executing any updater code."""
    run_id = uuid.uuid4().hex
    name, volume = 'dkp97-freshclam-' + run_id, 'dkp97-clam-staging-' + run_id
    config = Path(__file__).resolve().parents[2] / 'infra/docker-compose/freshclam.conf'
    configuration(config)
    image_id = docker_json('image', 'inspect', IMAGE)[0]['Id']
    subprocess.run(['docker', 'volume', 'create', '--driver', 'local', '--label', 'dkp.issue=97',
                    '--label', 'dkp.clam.run=' + run_id, volume], check=True, stdout=subprocess.DEVNULL)
    container = subprocess.check_output(['docker', 'create', '--name', name, '--label', 'dkp.issue=97',
        '--label', 'dkp.clam.run=' + run_id, '--user', '100:101', '--read-only', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges:true', '--memory', '4g', '--cpus', '2', '--pids-limit', '64',
        '--network', 'bridge', '--ipc', 'private', '--cgroupns', 'private',
        '--tmpfs', '/tmp:' + TMPFS['/tmp'], '--entrypoint', 'freshclam',
        '--mount', 'type=bind,src=' + str(config) + ',dst=/run/dkp/freshclam.conf,readonly',
        '--mount', 'type=volume,src=' + volume + ',dst=/var/lib/clamav', IMAGE, *UPDATE_COMMAND], text=True).strip()
    record = {'schemaVersion': 1, 'runId': run_id, 'containerId': container, 'containerName': name,
              'imageId': image_id, 'stagingVolume': volume, 'configurationPath': str(config),
              'configurationDigest': CONFIGURATION_DIGEST,
              'recordedAt': datetime.now(timezone.utc).isoformat()}
    # No docker start is reachable before a complete, fsynced O_EXCL private record exists.
    target = confined(scanner_root() / 'runs' / (run_id + '.json'))
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'w') as stream:
        json.dump(record, stream, separators=(',', ':'));stream.write('\n');stream.flush();os.fsync(stream.fileno())
    attest(read_record(target), completed=False)
    subprocess.run(['docker', 'start', container], check=True, stdout=subprocess.DEVNULL)
    return target


def publish(source, target, updated_at, *, now=None):
    """Structural snapshot primitive; tests of this function are not scanner acceptance."""
    now = now or datetime.now(timezone.utc)
    if updated_at.tzinfo is None or not now - timedelta(hours=24) <= updated_at <= now:
        raise ValueError('FreshClam completion must be recent and timezone aware')
    if target.exists() or target.is_symlink():
        raise ValueError('Refusing to replace a definition generation')
    databases = []
    total = 0
    daily_version = None
    for family in ('bytecode', 'daily', 'main'):
        candidates = [source / (family + suffix) for suffix in ('.cvd', '.cld')]
        present = [p for p in candidates if p.exists() or p.is_symlink()]
        if len(present) != 1 or present[0].is_symlink() or not present[0].is_file():
            raise ValueError('Exactly one regular standard database per family is required')
        path = present[0]
        total += path.stat().st_size
        if total > MAX_DATABASE_BYTES:
            raise ValueError('Standard database byte limit exceeded')
        with path.open('rb') as stream:
            header = stream.read(512).decode('ascii').strip().split(':')
        if len(header) < 9 or header[0] != 'ClamAV-VDB' or not header[2].isdigit():
            raise ValueError('Invalid standard database header')
        if not header[8].strip().isdigit() or int(header[8].strip()) > now.timestamp():
            raise ValueError('Invalid standard database timestamp')
        if family == 'daily':
            daily_version = header[2]
        databases.append(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix='.clam-generation-', dir=target.parent))
    try:
        canonical = []
        for source_path in sorted(databases, key=lambda p: p.name):
            path = scratch / source_path.name
            shutil.copyfile(source_path, path, follow_symlinks=False)
            with path.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            canonical.append(digest + '  ' + path.name + '\n')
            path.chmod(0o444)
        # Preserve official detached signatures where FreshClam supplied them.
        for signature in source.glob('*.cvd.sign'):
            if signature.is_symlink() or not signature.is_file() or signature.stat().st_size > 65536:
                raise ValueError('Invalid detached database signature')
            shutil.copyfile(signature, scratch / signature.name, follow_symlinks=False)
            (scratch / signature.name).chmod(0o444)
        manifest = {'schemaVersion': 1,
                    'updatedAt': updated_at.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z'),
                    'databaseVersion': daily_version,
                    'databaseDigest': hashlib.sha256(''.join(canonical).encode()).hexdigest()}
        metadata = scratch / 'dkp-update.json'
        metadata.write_text(json.dumps(manifest, separators=(',', ':')) + '\n')
        metadata.chmod(0o444)
        scratch.chmod(0o555)
        # No active runtime sees the generation until all files and evidence are complete.
        scratch.rename(target)
        return manifest
    finally:
        if scratch.exists():
            scratch.chmod(0o700)
            shutil.rmtree(scratch)


def publish_completed(container, target, run_record):
    record = read_record(run_record)
    if container != record['containerId']:
        raise ValueError('Only the recorded container identity may publish')
    finished = attest(record, completed=True)
    with tempfile.TemporaryDirectory(prefix='dkp-clam-staging-') as directory:
        copied_configuration = Path(directory) / 'freshclam.conf'
        subprocess.run(['docker', 'cp', container + ':/run/dkp/freshclam.conf', str(copied_configuration)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        configuration(copied_configuration)
        source = Path(directory) / 'definitions'
        subprocess.run(['docker', 'cp', container + ':/var/lib/clamav/.', str(source)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # A writer appearing during either copy must prevent publication, not merely log it.
        if attest(record, completed=True) != finished:
            raise ValueError('Updater completion changed during snapshot')
        return publish(source, target, finished)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--launch-updater', action='store_true')
    parser.add_argument('--completed-updater')
    parser.add_argument('--run-record', type=Path)
    parser.add_argument('--generation', type=Path)
    args = parser.parse_args()
    if args.launch_updater:
        if args.completed_updater or args.run_record or args.generation:
            parser.error('launch and publish are separate operations')
        print('Created and started owned FreshClam run; retain record: ' + str(launch_updater()))
        return
    if not args.completed_updater or not args.run_record or not args.generation:
        parser.error('publication requires completed-updater, run-record and generation')
    try:
        target = confined(args.generation)
    except ValueError as error:
        parser.error(str(error))
    manifest = publish_completed(args.completed_updater, target, args.run_record)
    print('Published immutable standard-definition generation: daily ' + manifest['databaseVersion']
          + ', digest ' + manifest['databaseDigest'])


if __name__ == '__main__':
    main()
