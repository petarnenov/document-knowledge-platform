"""Test native-Linux runner routing without granting wider storage authority."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('compat', Path(__file__).with_name('rustfs-compat-harness.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RunnerPortabilityTests(unittest.TestCase):
    def test_native_linux_gateway_is_explicit_with_same_scoped_fixture_and_coverage_gates(self):
        for status in (0, 1):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                state = {'container': 'dkp104-synthetic', 'network': 'owned-network',
                         'authority': 'app-only-authority', 'prefix': 'a' * 64 + '/', 'tenant': 'synthetic'}
                (directory / 'state.json').write_text(json.dumps(state))
                calls = []
                def execute(args):
                    calls.append(args)
                    return subprocess.CompletedProcess(args, status)
                with patch.object(module, 'owned') as owned, patch.object(module, 'run') as runtime, \
                        patch.object(module.subprocess, 'run', side_effect=execute), patch.object(module, 'socket_group', return_value='123'):
                    if status:
                        with self.assertRaisesRegex(RuntimeError, 'integration gate failed'):
                            module.execute(directory, 'synthetic-pinned-source-image')
                    else:
                        module.execute(directory, 'synthetic-pinned-source-image')
                command = calls[0]
                self.assertEqual('host.docker.internal:host-gateway', command[command.index('--add-host') + 1])
                self.assertIn('TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal', command)
                self.assertEqual('123', command[command.index('--group-add') + 1])
                self.assertEqual('owned-network', command[command.index('--network') + 1])
                self.assertIn('app-only-authority:/authority', command)
                self.assertIn('--rerun-tasks', command)
                self.assertNotIn('--privileged', command)
                self.assertNotIn('--network=host', command)
                for area in ('persistence', 'verification', 'storage'):
                    self.assertIn(':services:document-management:adapters-out:' + area + 'CoverageVerification', command)
                self.assertEqual([(('dkp104-synthetic', 'container'), {}),
                                  (('dkp104-synthetic-test', 'container'), {})], owned.call_args_list)
                self.assertEqual(('docker', 'rm', '-f', 'dkp104-synthetic-test'), runtime.call_args.args)
                self.assertEqual(5, runtime.call_count)  # JUnit + three coverage reports, then owned runner cleanup.
                report = Path((directory / 'latest-reports.private').read_text().strip())
                self.assertTrue(report.is_dir())
                self.assertEqual(0o700, report.stat().st_mode & 0o777)

    def test_socket_group_is_from_actual_private_mount_without_widening_permissions(self):
        def inspected(args, **kwargs):
            self.assertEqual(15, kwargs['timeout'])
            self.assertIn('--pull=never', args)
            self.assertIn('--read-only', args)
            self.assertEqual('none', args[args.index('--network') + 1])
            self.assertEqual('ALL', args[args.index('--cap-drop') + 1])
            self.assertEqual('stat', args[args.index('--entrypoint') + 1])
            self.assertEqual('type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock,readonly', args[args.index('--mount') + 1])
            self.assertNotIn('--privileged', args)
            self.assertFalse(any('chmod' in a or 'chown' in a for a in args))
            return subprocess.CompletedProcess(args, 0, b'123\n', b'')
        with patch.object(module.subprocess, 'run', side_effect=inspected):
            self.assertEqual('123', module.socket_group('trusted-source-image'))
        for status, value in [(0, b'0'), (0, b'2147483647')]:
            with patch.object(module.subprocess, 'run', return_value=subprocess.CompletedProcess([], status, value, b'')):
                self.assertEqual(value.decode(), module.socket_group('trusted-source-image'))
        for status, value in [(1,b'123'), (0,b'-1'), (0,b'001'), (0,b'2147483648'),
                              (0,b'99999999999'), (0,b'123;command'), (0,b'123\n456'), (0,b'\xff')]:
            with self.subTest(status=status,value=value), patch.object(module.subprocess, 'run',
                    return_value=subprocess.CompletedProcess([],status,value,b'secret')), self.assertRaises(RuntimeError):
                module.socket_group('trusted-source-image')
        for failure in (OSError('private'), subprocess.TimeoutExpired([],15)):
            with patch.object(module.subprocess, 'run', side_effect=failure), self.assertRaisesRegex(RuntimeError, 'diagnostics suppressed'):
                module.socket_group('trusted-source-image')


if __name__ == '__main__':
    unittest.main()
