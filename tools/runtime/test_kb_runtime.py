import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location('kb_runtime', Path(__file__).with_name('kb-runtime.py'))
kb = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(kb)


class RuntimeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.run = self.root / 'fresh'
        self.material = Mock()
        self.material.safe_path.side_effect = lambda value: Path(value)
        self.security = patch.object(kb, 'security', return_value=self.material)
        self.security.start()
        self.state = {'version': 1, 'project': 'dkp-bp04-' + 'a' * 12, 'owner': 'b' * 32,
                      'phase': 'running', 'source': 'c' * 64, 'images': {}, 'overlay': '',
                      'resources': {'container': [], 'network': [], 'volume': []}, 'commit': 'a' * 40}

    def tearDown(self):
        self.security.stop()
        self.tmp.cleanup()

    def record(self):
        self.run.mkdir(exist_ok=True, mode=0o700)
        kb.write(self.run / 'runtime-state.json', self.state)

    def labels(self, **extra):
        return {kb.LABEL: self.state['owner'], 'dkp.profile': 'dkp-bp04',
                'com.docker.compose.project': self.state['project'], **extra}

    def resources(self, healthy=True):
        result = {'container': {}, 'network': {}, 'volume': {}}
        for number, service in enumerate(['admin-portal', 'keycloak', 'kb-migrate']):
            identity = str(number + 1) * 64
            result['container'][identity] = {'Id': identity, 'Image': 'sha256:' + 'd' * 64,
                'Config': {'Labels': self.labels(**{'com.docker.compose.service': service})},
                'State': {'Status': 'exited' if service == 'kb-migrate' else 'running', 'ExitCode': 0,
                          'Health': {'Status': 'healthy' if healthy else 'unhealthy'}}}
            self.state['images'][service] = 'sha256:' + 'd' * 64
        result['network']['4' * 64] = {'Id': '4' * 64, 'Name': self.state['project'] + '_default', 'Labels': self.labels()}
        result['volume'][self.state['project'] + '_kb-postgres-data'] = {'Name': self.state['project'] + '_kb-postgres-data', 'Labels': self.labels()}
        self.state['resources'] = kb.resource_ids(result)
        return result

    def test_closed_record_rejects_unknown_and_unsafe_selectors(self):
        self.record()
        self.assertEqual(kb.load(self.run)[1], self.state)
        for field, value in [('project', 'other'), ('owner', 'not-owner'), ('version', 2), ('phase', 'ready'), ('source', 'x'), ('commit', 'not-commit')]:
            bad = dict(self.state, **{field: value})
            kb.write(self.run / 'runtime-state.json', bad)
            with self.assertRaises(ValueError): kb.load(self.run)
        for ids in [['--all'], ['1' * 64, '1' * 64], 'not-list']:
            bad = dict(self.state, resources={'container': ids, 'network': [], 'volume': []})
            kb.write(self.run / 'runtime-state.json', bad)
            with self.assertRaises(ValueError): kb.load(self.run)
        kb.write(self.run / 'runtime-state.json', dict(self.state, images={'dm': 'floating-tag'}))
        with self.assertRaises(ValueError): kb.load(self.run)
        kb.write(self.run / 'runtime-state.json', dict(self.state, extra='unknown'))
        with self.assertRaises(ValueError): kb.load(self.run)

    def test_private_record_symlink_permissions_and_size_rejected(self):
        p = self.root / 'record'
        p.write_text('x'); p.chmod(0o644)
        with self.assertRaises(ValueError): kb.private_file(p)
        p.chmod(0o600); kb.private_file(p)
        alias = self.root / 'alias'; alias.symlink_to(p)
        with self.assertRaises(ValueError): kb.private_file(alias)
        p.write_bytes(b'x' * 1048577)
        with self.assertRaises(ValueError): kb.private_file(p)

    def git_fixture(self):
        source = self.root / 'repository'
        source.mkdir()
        def git(*args):
            return subprocess.run(['git', *args], cwd=source, check=True, capture_output=True).stdout
        git('init', '--quiet')
        git('config', 'user.name', 'Synthetic unit fixture')
        git('config', 'user.email', 'unit@example.invalid')
        (source / 'public').write_text('committed-public')
        (source / 'public').chmod(0o700)
        git('add', 'public'); git('commit', '--quiet', '-m', 'synthetic fixture')
        return source, git

    def test_committed_snapshot_never_hashes_or_copies_untracked_sensitive_file(self):
        source, git = self.git_fixture()
        self.run.mkdir()
        with patch.object(kb, 'ROOT', source):
            commit = kb.source_identity(); expected = kb.source_digest()
            (source / 'private-untracked').write_text('SYNTHETIC-SENSITIVE-NEVER-PUBLISH')
            self.assertEqual(kb.source_paths(), [b'public'])
            self.assertEqual(kb.source_digest(), expected)
            target = kb.snapshot(self.run, commit)
            self.assertEqual(sorted(p.name for p in target.iterdir()), ['public'])
            self.assertEqual(kb.source_digest(target), expected)
            self.assertEqual((target / 'public').stat().st_mode & 0o777, 0o500)
            (target / 'unexpected').write_text('snapshot-drift')
            self.assertNotEqual(kb.source_digest(target), expected)
            (target / 'alias').symlink_to(source / 'public')
            with self.assertRaises(ValueError): kb.source_digest(target)

    def test_dirty_tracked_or_new_commit_rejected_before_docker_mutation(self):
        source, git = self.git_fixture()
        with patch.object(kb, 'ROOT', source), patch.object(kb, 'execute') as execute, patch.object(kb, 'preflight_ports') as ports:
            original = kb.source_identity()
            (source / 'public').write_text('dirty-uncommitted')
            with self.assertRaisesRegex(ValueError, 'tracked changes'): kb.start(self.run)
            self.assertFalse(self.run.exists()); execute.assert_not_called(); ports.assert_not_called()
            git('add', 'public')
            with self.assertRaisesRegex(ValueError, 'tracked changes'): kb.start(self.run)
            # Index differs, but the working file is restored to HEAD: comparing
            # only HEAD-to-worktree would hide this uncommitted staged change.
            (source / 'public').write_text('committed-public')
            with self.assertRaisesRegex(ValueError, 'tracked changes'): kb.start(self.run)
            self.assertFalse(self.run.exists()); execute.assert_not_called(); ports.assert_not_called()
            (source / 'public').write_text('dirty-uncommitted')
            git('commit', '--quiet', '-m', 'new committed fixture')
            self.assertNotEqual(kb.source_identity(), original)
            self.run.mkdir()
            with self.assertRaisesRegex(ValueError, 'changed before snapshot'): kb.snapshot(self.run, original)
            (source / 'alias').symlink_to('public'); git('add', 'alias'); git('commit', '--quiet', '-m', 'symlink negative')
            with self.assertRaisesRegex(ValueError, 'regular files'): kb.source_paths()

    def test_verify_binds_committed_identity_and_rejects_snapshot_and_worktree_drift(self):
        source, git = self.git_fixture()
        self.run.mkdir()
        with patch.object(kb, 'ROOT', source), patch.object(kb, 'healthy', return_value=True), patch.object(kb, 'database'):
            self.state['commit'] = kb.source_identity(); self.state['source'] = kb.source_digest()
            kb.snapshot(self.run, self.state['commit']); self.record()
            self.assertEqual(kb.verify(self.run)[1]['commit'], self.state['commit'])
            (self.run / 'public-source/public').chmod(0o600)
            (self.run / 'public-source/public').write_text('snapshot drift')
            with self.assertRaisesRegex(ValueError, 'differs'): kb.verify(self.run)
            (self.run / 'public-source/public').write_text('committed-public')
            (source / 'public').write_text('dirty tracked')
            with self.assertRaisesRegex(ValueError, 'tracked changes'): kb.verify(self.run)
            git('add', 'public'); git('commit', '--quiet', '-m', 'head drift')
            with self.assertRaisesRegex(ValueError, 'differs'): kb.verify(self.run)

    def test_snapshot_rejects_private_and_parent_traversal_even_if_provided_as_committed_paths(self):
        for index, name in enumerate([b'../outside', b'/absolute', b'local-data/private']):
            other = self.root / ('case-' + str(index)); other.mkdir()
            with patch.object(kb, 'source_identity', return_value='a' * 40), patch.object(kb, 'source_paths', return_value=[name]):
                with self.assertRaises(ValueError): kb.snapshot(other)

    def test_command_capture_private_and_finite_on_failure_and_timeout(self):
        self.run.mkdir()
        self.assertEqual(kb.execute(self.run, [sys.executable, '-c', 'print("synthetic")']), 'synthetic')
        self.assertEqual((self.run / 'commands.log').stat().st_mode & 0o777, 0o600)
        with self.assertRaisesRegex(ValueError, 'private evidence'):
            kb.execute(self.run, [sys.executable, '-c', 'import sys;sys.exit(1)'])
        with patch.object(kb.subprocess, 'run', side_effect=subprocess.TimeoutExpired('owned', 1)):
            with self.assertRaisesRegex(ValueError, 'timed out'): kb.execute(self.run, ['unused'])
        (self.run / 'commands.log').unlink(); (self.run / 'commands.log').symlink_to(self.root / 'outside')
        with self.assertRaises(OSError): kb.execute(self.run, ['unused'])

    def test_overlay_cannot_change_compose_selector_or_configuration(self):
        self.record(); self.state['phase'] = 'prepared'
        self.assertIn(self.state['project'], kb.compose(self.run, self.state))
        self.state['phase'] = 'running'
        with self.assertRaises(ValueError): kb.compose(self.run, self.state)
        kb.write(self.run / 'ownership.json', {'services': {}})
        self.state['overlay'] = hashlib.sha256((self.run / 'ownership.json').read_bytes()).hexdigest()
        arguments = kb.compose(self.run, self.state)
        self.assertIn(str(self.run / 'security/runtime.env'), arguments)
        kb.write(self.run / 'ownership.json', {'services': {'foreign': {}}})
        with self.assertRaises(ValueError): kb.compose(self.run, self.state)

    def test_inventory_requires_exact_owner_profile_and_project(self):
        labels = self.labels()
        with patch.object(kb, 'execute', return_value='1' * 64) as execute, patch.object(kb, 'inspect') as inspect:
            inspect.return_value = {'Id': '1' * 64, 'Name': self.state['project'] + '_data', 'Labels': labels, 'Config': {'Labels': labels}}
            self.assertEqual(len(kb.inventory(self.run, self.state)['container']), 1)
            self.assertIn('-a', execute.call_args_list[0].args[1])
            for field in [kb.LABEL, 'dkp.profile', 'com.docker.compose.project']:
                changed = dict(labels, **{field: 'foreign'})
                inspect.return_value = {'Id': '1' * 64, 'Labels': changed, 'Config': {'Labels': changed}}
                with self.assertRaises(ValueError): kb.inventory(self.run, self.state)
            inspect.return_value = {'Labels': labels, 'Config': {'Labels': labels}}
            with self.assertRaises(ValueError): kb.inventory(self.run, self.state)

    def test_health_checks_immutable_image_exact_set_and_completed_migration(self):
        resources = self.resources()
        with patch.object(kb, 'inventory', return_value=resources):
            self.assertTrue(kb.healthy(self.run, self.state))
            resources['container']['1' * 64]['State']['Health']['Status'] = 'starting'
            self.assertFalse(kb.healthy(self.run, self.state))
            resources['container']['1' * 64]['State']['Health']['Status'] = 'healthy'
            resources['container']['3' * 64]['State']['ExitCode'] = 1
            self.assertFalse(kb.healthy(self.run, self.state))
            resources['container']['3' * 64]['State']['ExitCode'] = 0
            resources['container']['1' * 64]['Image'] = 'sha256:' + 'e' * 64
            with self.assertRaises(ValueError): kb.healthy(self.run, self.state)
            resources['container'].pop('1' * 64)
            with self.assertRaises(ValueError): kb.healthy(self.run, self.state)

    def test_verify_has_no_adoption_and_requires_material_and_real_database(self):
        self.record()
        with patch.object(kb, 'source_identity', return_value='a' * 40), patch.object(kb, 'source_digest', return_value='c' * 64), patch.object(kb, 'healthy', return_value=True), patch.object(kb, 'database') as db:
            self.assertEqual(kb.verify(self.run)[1], self.state); db.assert_called_once()
            self.material.verify.assert_called_with(self.run / 'security')
            with patch.object(kb, 'healthy', return_value=False):
                with self.assertRaises(ValueError): kb.verify(self.run)
            with patch.object(kb, 'source_digest', return_value='f' * 64):
                with self.assertRaises(ValueError): kb.verify(self.run)

    def test_database_exec_has_no_secret_arguments_and_demands_one_boolean(self):
        with patch.object(kb, 'compose', return_value=['docker', 'compose']), patch.object(kb, 'execute', return_value='1') as execute:
            kb.database(self.run, self.state)
            args = execute.call_args.args[1]
            self.assertIn('kb-postgres', args)
            self.assertIn('13', execute.call_args.kwargs['input_text'])
            self.assertIn('PGPASSWORD="$POSTGRES_PASSWORD"', args[-1])
            self.assertNotIn('--password=', ' '.join(args))
            execute.return_value = '0'
            with self.assertRaises(ValueError): kb.database(self.run, self.state)

    def test_stop_only_recorded_ids_never_deletes_data_or_uses_compose_down(self):
        resources = self.resources(); self.record()
        with patch.object(kb, 'inventory', return_value=resources), patch.object(kb, 'execute') as execute:
            kb.stop(self.run)
            commands = [call.args[1] for call in execute.call_args_list]
            self.assertEqual(len(commands), 4)
            self.assertTrue(all(args[1] != 'volume' and 'down' not in args for args in commands))
            state = kb.load(self.run)[1]
            self.assertEqual(state['phase'], 'stopped'); self.assertEqual(state['resources']['volume'], self.state['resources']['volume'])
        self.record(); resources['network']['f' * 64] = {}
        with patch.object(kb, 'inventory', return_value=resources), patch.object(kb, 'execute') as execute:
            with self.assertRaises(ValueError): kb.stop(self.run)
            execute.assert_not_called()

    def test_browser_record_and_exact_explicit_kb_profile(self):
        resources = self.resources(); self.record()
        with patch.object(kb, 'inventory', return_value=resources), patch.object(kb, 'verify', return_value=(self.run, self.state)) as verify, patch.object(kb, 'execute') as execute:
            kb.e2e(self.run)
            self.assertEqual(verify.call_count, 2)
            record = json.loads((self.run / 'browser-state.json').read_text())
            self.assertEqual(record['portalId'], '1' * 64); self.assertEqual(record['keycloakId'], '2' * 64)
            self.assertEqual(record['networkId'], '4' * 64); self.assertEqual(record['runId'], self.state['owner'])
            self.assertEqual((self.run / 'browser-state.json').stat().st_mode & 0o777, 0o600)
            args = execute.call_args.args[1]
            self.assertIn('--kb-runtime', args); self.assertIn('--kb-runtime-state', args)
            self.assertNotIn('--upload-runtime', args)
            resources['network'] = {}
            with self.assertRaises(ValueError): kb.browser_record(self.run, self.state)

    def test_start_fresh_success_and_failed_build_preserve_evidence_without_cleanup(self):
        config = {'services': {'kb-postgres': {'image': 'pinned:example'}}, 'networks': {'kb-data': {}}, 'volumes': {'kb-data': {}}}
        empty = {'container': {}, 'network': {}, 'volume': {}}
        def command(directory, args, **kwargs):
            return json.dumps(config) if 'config' in args and '--format' in args else ''
        with patch.object(kb, 'source_identity', return_value='a' * 40), patch.object(kb, 'snapshot'), patch.object(kb, 'preflight_ports'), patch.object(kb, 'source_digest', return_value='c' * 64), patch.object(kb, 'inventory', return_value=empty), patch.object(kb, 'execute', side_effect=command), patch.object(kb, 'inspect', return_value={'Id': 'sha256:' + 'd' * 64}), patch.object(kb, 'healthy', return_value=True), patch.object(kb, 'database'):
            kb.start(self.run)
            state = kb.load(self.run)[1]; self.assertEqual(state['phase'], 'running')
            overlay = json.loads((self.run / 'ownership.json').read_text())
            self.assertEqual(overlay['services']['kb-postgres'], {'labels': {kb.LABEL: state['owner'], 'dkp.profile': 'dkp-bp04'}})
            with self.assertRaises(ValueError): kb.start(self.run)
        failed = self.root / 'failed'
        with patch.object(kb, 'source_identity', return_value='a' * 40), patch.object(kb, 'snapshot'), patch.object(kb, 'preflight_ports'), patch.object(kb, 'source_digest', return_value='c' * 64), patch.object(kb, 'inventory', return_value=empty), patch.object(kb, 'execute', side_effect=ValueError('synthetic build failure')):
            with self.assertRaises(ValueError): kb.start(failed)
            self.assertEqual(kb.load(failed)[1]['phase'], 'failed')
            self.assertTrue((failed / 'runtime-state.json').exists())

    def test_port_preflight_closes_all_sockets_even_on_conflict(self):
        socket = Mock(); socket.bind.side_effect = OSError('occupied')
        with patch.object(kb.socket, 'socket', return_value=socket):
            with self.assertRaises(OSError): kb.preflight_ports()
            socket.close.assert_called_once()
        socket = Mock()
        with patch.object(kb.socket, 'socket', return_value=socket):
            kb.preflight_ports(); self.assertEqual(socket.close.call_count, 4)

    def test_main_does_not_print_private_failure(self):
        with patch.object(sys, 'argv', ['kb-runtime', 'verify', '--directory', str(self.run)]), patch.object(kb, 'verify', side_effect=ValueError('private synthetic value')), patch('builtins.print') as output:
            self.assertEqual(kb.main(), 1)
            self.assertNotIn('private synthetic value', str(output.call_args))
        with patch.object(sys, 'argv', ['kb-runtime', 'verify', '--directory', str(self.run)]), patch.object(kb, 'verify'), patch('builtins.print'):
            self.assertEqual(kb.main(), 0)


if __name__ == '__main__':
    unittest.main()
