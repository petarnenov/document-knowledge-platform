"""Structural publishing tests with synthetic headers; no malware acceptance claim."""
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import shutil

spec = importlib.util.spec_from_file_location('clam_definitions', Path(__file__).with_name('clam-definitions.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class GenerationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.source = self.root / 'source'
        self.source.mkdir()
        self.target = self.root / 'generation'
        self.now = datetime(2026, 10, 31, 19, 0, tzinfo=timezone.utc)
        for family in ('bytecode', 'daily', 'main'):
            (self.source / (family + '.cvd')).write_bytes(
                f'ClamAV-VDB:test:123:1:90:hash:signature:builder:{int(self.now.timestamp())}:synthetic'.encode().ljust(512, b' ') + b'fixture')

    def tearDown(self):
        if self.target.exists():
            self.target.chmod(0o700)

    def publish(self, updated=None):
        return module.publish(self.source, self.target, updated or self.now, now=self.now)

    def test_exact_manifest_and_byte_identity_are_frozen_and_not_overwritten(self):
        result = self.publish()
        canonical = ''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n'
                            for p in sorted(self.source.glob('*.cvd')))
        self.assertEqual(hashlib.sha256(canonical.encode()).hexdigest(), result['databaseDigest'])
        self.assertEqual('123', result['databaseVersion'])
        self.assertEqual(result, json.loads((self.target / 'dkp-update.json').read_text()))
        self.assertEqual({'schemaVersion', 'updatedAt', 'databaseVersion', 'databaseDigest'}, set(result))
        self.assertEqual(0o555, self.target.stat().st_mode & 0o777)
        for path in self.target.iterdir():
            self.assertEqual(0o444, path.stat().st_mode & 0o777)
        before = (self.target / 'daily.cvd').read_bytes()
        (self.source / 'daily.cvd').write_bytes(b'updater can change staging later')
        self.assertEqual(before, (self.target / 'daily.cvd').read_bytes())
        with self.assertRaises(ValueError):
            self.publish()

    def test_stale_future_and_naive_completion_rejected(self):
        for updated in (self.now - timedelta(days=2), self.now + timedelta(seconds=1), self.now.replace(tzinfo=None)):
            with self.assertRaises(ValueError):
                self.publish(updated)
        self.assertFalse(self.target.exists())

    def test_missing_duplicate_and_symlink_databases_rejected(self):
        daily = self.source / 'daily.cvd'
        backup = daily.read_bytes()
        daily.unlink()
        with self.assertRaises(ValueError):
            self.publish()
        daily.write_bytes(backup)
        duplicate = self.source / 'daily.cld'
        duplicate.write_bytes(backup)
        with self.assertRaises(ValueError):
            self.publish()
        duplicate.unlink()
        daily.unlink()
        daily.symlink_to(self.source / 'main.cvd')
        with self.assertRaises(ValueError):
            self.publish()

    def test_invalid_header_and_byte_budget_rejected(self):
        with patch.object(module, 'MAX_DATABASE_BYTES', 1):
            with self.assertRaises(ValueError):
                self.publish()
        (self.source / 'daily.cvd').write_bytes(b'not a database')
        with self.assertRaises(ValueError):
            self.publish()
        self.assertFalse(self.target.exists())

    def test_future_database_timestamp_and_unsafe_signature_rejected(self):
        daily = self.source / 'daily.cvd'
        daily.write_bytes(daily.read_bytes().replace(str(int(self.now.timestamp())).encode(),
                                                   str(int(self.now.timestamp()) + 86400).encode()))
        with self.assertRaises(ValueError):
            self.publish()
        daily.write_bytes((self.source / 'main.cvd').read_bytes())
        signature = self.source / 'daily-123.cvd.sign'
        signature.symlink_to(daily)
        with self.assertRaises(ValueError):
            self.publish()
        self.assertFalse(self.target.exists())
        self.assertEqual([], list(self.root.glob('.clam-generation-*')))
        signature.unlink()
        signature.write_bytes(b'synthetic detached signature')
        self.publish()
        self.assertEqual(signature.read_bytes(), (self.target / signature.name).read_bytes())

class AttestationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.run_id = 'a' * 32
        self.container = 'b' * 64
        self.image = 'sha256:' + 'c' * 64
        self.now = datetime.now(timezone.utc) - timedelta(seconds=5)
        self.config = Path(__file__).resolve().parents[2] / 'infra/docker-compose/freshclam.conf'
        self.record = {'schemaVersion': 1, 'runId': self.run_id, 'containerId': self.container,
                       'containerName': 'dkp97-freshclam-' + self.run_id, 'imageId': self.image,
                       'stagingVolume': 'dkp97-clam-staging-' + self.run_id,
                       'configurationPath': str(self.config), 'configurationDigest': module.CONFIGURATION_DIGEST,
                       'recordedAt': (self.now - timedelta(seconds=2)).isoformat()}
        self.volume = {'Name': self.record['stagingVolume'], 'Driver': 'local', 'Scope': 'local',
                       'Options': None, 'Labels': module.labels(self.run_id), 'Mountpoint': '/var/lib/docker/volumes/owned/_data'}
        self.item = {'Id': self.container, 'Name': '/' + self.record['containerName'], 'Image': self.image,
                     'Created': (self.now - timedelta(seconds=3)).isoformat(),
                     'State': {'Status': 'exited', 'Running': False, 'ExitCode': 0, 'OOMKilled': False,
                               'Error': '', 'StartedAt': (self.now - timedelta(seconds=1)).isoformat(),
                               'FinishedAt': self.now.isoformat()},
                     'Config': {'Image': module.IMAGE, 'Entrypoint': ['freshclam'], 'Cmd': module.UPDATE_COMMAND.copy(),
                                'Labels': {**module.labels(self.run_id), 'maintainer': 'pinned-image-label'}, 'User': '100:101', 'Env': module.ENVIRONMENT.copy()},
                     'HostConfig': {'ReadonlyRootfs': True, 'Privileged': False, 'CapAdd': None, 'CapDrop': ['ALL'],
                                    'SecurityOpt': ['no-new-privileges:true'], 'Tmpfs': module.TMPFS.copy(),
                                    'Memory': 4 * 1024 ** 3, 'NanoCpus': 2_000_000_000, 'PidsLimit': 64,
                                    'NetworkMode': 'bridge', 'PidMode': '', 'IpcMode': 'private', 'UTSMode': '',
                                    'UsernsMode': '', 'CgroupnsMode': 'private'},
                     'Mounts': [{'Destination': '/run/dkp/freshclam.conf', 'Type': 'bind', 'RW': False, 'Source': str(self.config)},
                                {'Destination': '/var/lib/clamav', 'Type': 'volume', 'RW': True,
                                 'Source': self.volume['Mountpoint'], 'Name': self.record['stagingVolume']}]}
        self.record_path = self.root / 'runs/record.json'
        self.record_path.parent.mkdir(mode=0o700)
        self.write_record()
        self.addCleanup(patch.stopall)
        patch.object(module, 'scanner_root', return_value=self.root).start()
        self.json_call = patch.object(module, 'docker_json', side_effect=self.docker_json).start()
        self.output = patch.object(module.subprocess, 'check_output', return_value=self.container + '\n').start()
        self.commands = patch.object(module.subprocess, 'run', side_effect=self.copy).start()
        self.publish = patch.object(module, 'publish', return_value={'databaseVersion': '123'}).start()
        self.copied = []

    def write_record(self):
        self.record_path.write_text(json.dumps(self.record))
        self.record_path.chmod(0o600)

    def docker_json(self, *args):
        if args[:2] == ('image', 'inspect'):
            return [{'Id': self.image, 'Config': {'Labels': {'maintainer': 'pinned-image-label'}}}]
        if args[:2] == ('volume', 'inspect'):
            return [self.volume]
        self.assertEqual(('inspect', self.container), args)
        return [self.item]

    def copy(self, args, **kwargs):
        self.assertEqual(['docker', 'cp'], args[:2])
        self.copied.append(args[2])
        if args[2].endswith(':/run/dkp/freshclam.conf'):
            Path(args[3]).write_bytes(self.config.read_bytes())
        else:
            self.assertEqual(self.container + ':/var/lib/clamav/.', args[2])
            Path(args[3]).mkdir()

    def completed(self):
        return module.publish_completed(self.container, self.root / 'generation', self.record_path)

    def reject(self):
        with self.assertRaises((ValueError, FileNotFoundError, KeyError)):
            self.completed()
        self.publish.assert_not_called()

    def test_exact_run_checks_volume_both_sides_of_copy_and_preserves_finished_at(self):
        self.assertEqual('123', self.completed()['databaseVersion'])
        self.assertEqual(self.now, self.publish.call_args.args[2])
        self.assertEqual(2, self.output.call_count)
        self.assertEqual(2, len(self.copied))
        self.item['HostConfig']['SecurityOpt'] = ['no-new-privileges']
        self.completed()

    def test_unsafe_execution_profile_and_environment_are_rejected_before_copy(self):
        import copy
        original = copy.deepcopy(self.item)
        changes = {'Config': {'Image': 'other', 'Entrypoint': ['sh'], 'Cmd': ['--help'], 'Labels': {}, 'User': '0',
                              'Env': [module.ENVIRONMENT[0] + ':/attacker', module.ENVIRONMENT[1]]},
                   'HostConfig': {'ReadonlyRootfs': False, 'Privileged': True, 'CapAdd': ['SYS_ADMIN'],
                                  'CapDrop': [], 'SecurityOpt': [], 'Tmpfs': {'/tmp': 'size=2g'}, 'Memory': 0,
                                  'NanoCpus': 0, 'PidsLimit': -1, 'NetworkMode': 'host', 'PidMode': 'host',
                                  'IpcMode': 'host', 'UTSMode': 'host', 'UsernsMode': 'host', 'CgroupnsMode': 'host',
                                  'Devices': [{}], 'DeviceRequests': [{}], 'VolumesFrom': ['other'],
                                  'GroupAdd': ['0'], 'ExtraHosts': ['mirror:127.0.0.1'], 'Dns': ['127.0.0.1'],
                                  'DnsSearch': ['attacker'], 'DnsOptions': ['ndots:1'], 'Sysctls': {'unsafe': '1'}}}
        for section, values in changes.items():
            for key, value in values.items():
                with self.subTest(section=section, key=key):
                    self.item = copy.deepcopy(original);self.item[section][key] = value;self.reject()
        for environment in (module.ENVIRONMENT + ['LD_PRELOAD=/code.so'], module.ENVIRONMENT * 2,
                            ['PATH=/code', 'TZ=Etc/UTC'], module.ENVIRONMENT + ['EXTRA=1']):
            self.item = copy.deepcopy(original);self.item['Config']['Env'] = environment;self.reject()
        self.assertEqual([], self.copied)

    def test_mount_substitution_writable_code_and_other_mounts_are_rejected(self):
        import copy
        original = copy.deepcopy(self.item['Mounts'])
        for index, key, value in ((0, 'RW', True), (0, 'Source', '/attacker/freshclam.conf'),
                                  (1, 'Name', 'another-volume'), (1, 'Source', '/other'),
                                  (1, 'RW', False), (1, 'Type', 'bind')):
            self.item['Mounts'] = copy.deepcopy(original);self.item['Mounts'][index][key] = value;self.reject()
        self.item['Mounts'] = original + [{'Destination': '/usr/bin', 'Type': 'bind', 'RW': True, 'Source': '/code'}]
        self.reject();self.assertEqual([], self.copied)

    def test_nonlocal_unowned_or_concurrently_referenced_volume_rejected(self):
        for key, value in (('Driver', 'nfs'), ('Scope', 'global'), ('Options', {'device': '/host'}),
                           ('Labels', {}), ('Name', 'other')):
            old = self.volume[key];self.volume[key] = value;self.reject();self.volume[key] = old
        for references in ('', self.container + '\nother-writer\n'):
            self.output.return_value = references;self.reject()
        self.output.side_effect = [self.container + '\n', self.container + '\nconcurrent-writer\n']
        self.reject();self.assertEqual(2, len(self.copied))

    def test_record_is_mandatory_private_closed_and_bound_to_image_container_config(self):
        original = self.record.copy()
        for key, value in (('schemaVersion', 2), ('runId', 'invalid'), ('containerId', 'd' * 64),
                           ('containerName', 'other'), ('imageId', 'sha256:' + 'd' * 64),
                           ('configurationPath', '/other'), ('configurationDigest', 'bad'),
                           ('stagingVolume', 'other'), ('extra', 'forbidden')):
            self.record = original.copy();self.record[key] = value;self.write_record();self.reject()
        self.record = original;self.write_record();self.record_path.chmod(0o644);self.reject()
        self.record_path.unlink();self.reject()
        self.write_record();saved = self.record_path.with_suffix('.saved');self.record_path.rename(saved)
        self.record_path.symlink_to(saved);self.reject()
        self.assertEqual([], self.copied)

    def test_completion_start_order_and_old_runs_cannot_be_backfilled(self):
        import copy
        original = copy.deepcopy(self.item)
        for key, value in (('Status', 'running'), ('Running', True), ('ExitCode', 1), ('OOMKilled', True),
                           ('Error', 'failed'), ('StartedAt', self.record['recordedAt']),
                           ('FinishedAt', (self.now - timedelta(days=2)).isoformat()),
                           ('FinishedAt', (self.now + timedelta(days=2)).isoformat())):
            self.item = copy.deepcopy(original);self.item['State'][key] = value;self.reject()
        self.item = copy.deepcopy(original);self.item['Created'] = self.now.isoformat();self.reject()
        with self.assertRaises(ValueError):
            module.attest(self.record, completed=False)
        self.assertEqual([], self.copied)

    def test_config_changed_in_source_or_snapshot_prevents_publication(self):
        for invalid in (self.config.read_bytes().replace(b'TestDatabases yes', b'TestDatabases no'),
                        self.config.read_bytes().replace(b'database.clamav.net', b'untrusted.invalid')):
            def changed(args, **kwargs):
                Path(args[3]).write_bytes(invalid)
            self.commands.side_effect = changed;self.reject()
        with patch.object(module.Path, 'read_bytes', return_value=b'changed-source'):
            self.reject()

    def test_launch_persists_private_identity_before_start_and_never_backfills(self):
        self.output.side_effect = lambda args, **kwargs: self.container + '\n'
        self.item['State'] = {'Status': 'created', 'Running': False, 'StartedAt': '0001-01-01T00:00:00Z'}
        with patch.object(module.uuid, 'uuid4') as random:
            random.return_value.hex = self.run_id
            def run(args, **kwargs):
                if args[:2] == ['docker', 'start']:
                    path = self.root / 'runs' / (self.run_id + '.json')
                    saved = module.read_record(path)
                    self.assertEqual(self.container, saved['containerId'])
                    self.assertEqual(self.record['stagingVolume'], saved['stagingVolume'])
                    self.assertEqual(0o600, path.stat().st_mode & 0o777)
                    self.assertEqual(['docker', 'start', self.container], args)
                else:
                    self.assertEqual(['docker', 'volume', 'create'], args[:3])
            self.commands.side_effect = run
            result = module.launch_updater()
            self.assertTrue(result.is_file())
            creates = [c.args[0] for c in self.output.call_args_list if c.args[0][:2] == ['docker', 'create']]
            self.assertEqual(1, len(creates));self.assertIn('--read-only', creates[0]);self.assertIn('--cap-drop', creates[0])
            with self.assertRaises(FileExistsError):
                module.launch_updater()

    def test_launch_refuses_to_start_when_created_container_attestation_fails(self):
        # Even a container already bearing the right labels cannot receive a new start
        # when it has execution history instead of a freshly created state.
        with patch.object(module.uuid, 'uuid4') as random:
            random.return_value.hex = self.run_id
            self.commands.side_effect = None
            with self.assertRaises(ValueError):
                module.launch_updater()
            self.assertFalse(any(c.args[0][:2] == ['docker', 'start'] for c in self.commands.call_args_list))

    def test_changed_completion_during_snapshot_never_publishes(self):
        original_copy = self.copy
        def changed(args, **kwargs):
            original_copy(args, **kwargs)
            if args[2].endswith(':/var/lib/clamav/.'):
                self.item['State']['FinishedAt'] = (self.now + timedelta(seconds=1)).isoformat()
        self.commands.side_effect = changed
        self.reject()

    def test_cli_confines_generation_and_requires_provenance(self):
        with patch.object(module, 'publish_completed', return_value={'databaseVersion': '123', 'databaseDigest': 'a'*64}) as publish:
            argv = ['clam-definitions', '--completed-updater', self.container, '--run-record', str(self.record_path)]
            with patch('sys.argv', argv + ['--generation', str(self.root / 'new')]):
                module.main()
            publish.assert_called_once_with(self.container, self.root / 'new', self.record_path)
            for target in (self.root, self.root.parent / 'outside'):
                with patch('sys.argv', argv + ['--generation', str(target)]), patch('sys.stderr'), self.assertRaises(SystemExit):
                    module.main()
            for args in ([], ['--completed-updater', self.container], ['--launch-updater', '--generation', 'bad']):
                with patch('sys.argv', ['clam-definitions'] + args), patch('sys.stderr'), self.assertRaises(SystemExit):
                    module.main()
        with patch('sys.argv', ['clam-definitions', '--launch-updater']), patch.object(module, 'launch_updater', return_value=self.record_path) as launch:
            module.main();launch.assert_called_once()


if __name__ == '__main__':
    unittest.main()
