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

    def test_fresh_preparation_generates_distinct_role_bound_principals_and_scoped_native_policies(self):
        import hashlib
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary)/'fresh';calls=[];native=[]
            def command(*args,**kwargs):
                calls.append(args)
                if args[0]=='openssl':
                    for option in ('-out','-keyout'):
                        if option in args:Path(args[args.index(option)+1]).write_text('synthetic-test-material')
                if args[:2]==('docker','inspect'):
                    return subprocess.CompletedProcess(args,0,json.dumps([{'NetworkSettings':{'Ports':{'9000/tcp':[{'HostPort':'19900'}]}}}]).encode())
                return subprocess.CompletedProcess(args,0,b'200' if args[0]=='curl' else b'')
            with patch.object(module,'run',side_effect=command),patch.object(module,'curl',side_effect=lambda *a:native.append(a)),patch.object(module.time,'sleep'):
                module.prepare(directory)
            policies=[json.loads(a[4]) for a in native if 'add-canned-policy' in a[3]]
            self.assertEqual(2,len(policies))
            normal={x for s in policies[0]['Statement'] for x in s['Action']};cleanup={x for s in policies[1]['Statement'] for x in s['Action']}
            self.assertNotIn('s3:DeleteObjectVersion',normal);self.assertNotIn('s3:PutObject',cleanup)
            self.assertEqual({'s3:GetBucketVersioning','s3:ListBucketVersions','s3:GetObjectVersion','s3:DeleteObjectVersion'},cleanup)
            credentials=[]
            for prefix,role in [('', 'normal'),('cleanup-', 'cleanup')]:
                access=(directory/('app/'+prefix+'access-key')).read_text().strip();secret=(directory/('app/'+prefix+'secret-key')).read_text().strip();credentials.extend([access,secret])
                expected='dkp-storage-'+role+'-v1\n'+hashlib.sha256(access.encode()).hexdigest()+'\n'+hashlib.sha256(secret.encode()).hexdigest()+'\n'
                self.assertTrue((directory/('app/'+prefix+'access-key.role')).read_text()==expected)
            self.assertEqual(4,len(set(credentials)))
            self.assertFalse(any('root' in p.name for p in (directory/'app').iterdir()))
            self.assertTrue(all(p.stat().st_mode&0o777==0o600 for p in directory.rglob('*') if p.is_file()))
            self.assertTrue(all(module.IMAGE in a for a in calls if a[:3]==('docker','run','-d')))

    def test_native_bootstrap_requires_both_readiness_probes_before_first_write(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / 'fresh'
            observed = []
            native = []
            pending = {'/health/ready': [b'403', b'404', b'200', b'200'],
                       '/minio/health/cluster': [b'503', b'200']}
            latest = {}
            def command(*args, **kwargs):
                if args[0] == 'openssl':
                    for option in ('-out', '-keyout'):
                        if option in args:
                            Path(args[args.index(option) + 1]).write_text('synthetic-test-material')
                if args[:2] == ('docker', 'inspect'):
                    return subprocess.CompletedProcess(args, 0, json.dumps([{'NetworkSettings': {'Ports': {'9000/tcp': [{'HostPort': '19900'}]}}}]).encode())
                if args[0] == 'curl':
                    self.assertIn('--cacert', args)
                    self.assertNotIn('--insecure', args)
                    path = args[-1].removeprefix('https://localhost:19900')
                    observed.append(path)
                    values = pending.get(path, [b'403'])
                    status = values.pop(0) if len(values) > 1 else values[0]
                    latest[path] = status
                    return subprocess.CompletedProcess(args, 0, status)
                return subprocess.CompletedProcess(args, 0, b'')
            def write(*args):
                self.assertEqual(b'200', latest.get('/health/ready'))
                self.assertEqual(b'200', latest.get('/minio/health/cluster'))
                native.append(args)
            with patch.object(module, 'run', side_effect=command), patch.object(module, 'curl', side_effect=write), patch.object(module.time, 'sleep'):
                module.prepare(directory)
            self.assertTrue(native)
            self.assertNotIn('/health', observed)
            self.assertGreaterEqual(observed.count('/health/ready'), 4)
            self.assertEqual(2, observed.count('/minio/health/cluster'))

    def test_readiness_failure_and_transport_errors_cannot_dispatch_or_expose_diagnostics(self):
        cases = [(0, b'403'), (0, b'404'), (0, b'503'), (1, b'200'),
                 (0, b'private-response'), OSError('private-response'),
                 subprocess.TimeoutExpired(['private-response'], 2)]
        for result in cases:
            with self.subTest(result=type(result).__name__):
                now = [0.0]
                calls = []
                def command(*args, **kwargs):
                    calls.append(args)
                    now[0] += 0.1
                    self.assertEqual('curl', args[0])
                    self.assertIn('--disable', args)
                    self.assertIn('--proto', args)
                    self.assertIn('--cacert', args)
                    self.assertNotIn('--config', args)
                    self.assertNotIn('--insecure', args)
                    self.assertLessEqual(kwargs['timeout'], 2)
                    if isinstance(result, Exception):
                        raise result
                    return subprocess.CompletedProcess(args, result[0], result[1])
                with patch.object(module.time, 'monotonic', side_effect=lambda: now[0]), patch.object(module.time, 'sleep', side_effect=lambda n: now.__setitem__(0, now[0] + n)), patch.object(module, 'run', side_effect=command):
                    with self.assertRaisesRegex(RuntimeError, 'no bootstrap write dispatched') as failure:
                        module.wait_ready(Path('/owned-private'), 'https://localhost:19900')
                self.assertNotIn('private-response', str(failure.exception))
                self.assertLessEqual(now[0], 60.1)
                self.assertTrue(calls)
                self.assertTrue(all(c[-1].endswith('/health/ready') for c in calls))

    def test_readiness_and_write_health_must_succeed_within_same_fixed_deadline(self):
        for advances in ([60.0], [1.0, 59.0], [1.0, 0.0]):
            now = [0.0]
            calls = []
            def command(*args, **kwargs):
                calls.append(args[-1])
                now[0] += advances[min(len(calls) - 1, len(advances) - 1)]
                status = b'503' if advances == [1.0, 0.0] and args[-1].endswith('/minio/health/cluster') else b'200'
                return subprocess.CompletedProcess(args, 0, status)
            with patch.object(module.time, 'monotonic', side_effect=lambda: now[0]), patch.object(module.time, 'sleep', side_effect=lambda n: now.__setitem__(0, now[0] + n)), patch.object(module, 'run', side_effect=command):
                with self.assertRaisesRegex(RuntimeError, 'no bootstrap write dispatched'):
                    module.wait_ready(Path('/owned-private'), 'https://localhost:19900')
            self.assertLessEqual(now[0], 60)
            if len(advances) == 1:
                self.assertEqual(1, len(calls))
            else:
                self.assertTrue(any(c.endswith('/minio/health/cluster') for c in calls))

    def test_private_curl_parser_failures_remain_failures_without_secret_diagnostics(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary);(directory/'ca.pem').write_text('public-ca');(directory/'root-curl.private').write_text('private-root-config')
            def command(*args,**kwargs):
                Path(args[args.index('--output')+1]).write_bytes(b'private-response')
                self.assertNotIn('private-root-config',args);self.assertNotIn('synthetic-body',args)
                return subprocess.CompletedProcess(args,0,b'200')
            with patch.object(module,'run',side_effect=command):module.curl(directory,'https://localhost:19900','PUT','/synthetic','synthetic-body')
            for status,code in [(1,b'200'),(0,b'403'),(0,b'invalid')]:
                with patch.object(module,'run',side_effect=lambda *a,**k:subprocess.CompletedProcess(a,status,code)),self.assertRaises(RuntimeError):
                    module.curl(directory,'https://localhost:19900','GET','/synthetic')

    def test_owned_cleanup_rejects_foreign_resources_and_removes_only_recorded_fixture(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary);state={'container':'owned','data':'data','server':'server','authority':'authority','network':'network'};(directory/'state.json').write_text(json.dumps(state));events=[]
            with patch.object(module,'owned',side_effect=lambda *a:events.append(('owned',*a))),patch.object(module,'run',side_effect=lambda *a:events.append(a)):
                module.cleanup(directory)
            self.assertEqual(('owned','owned','container'),events[0]);self.assertEqual(('docker','rm','-f','owned'),events[1]);self.assertEqual(10,len(events))
            with patch.object(module,'run',return_value=subprocess.CompletedProcess([],0,json.dumps([{'Config':{'Labels':{'dkp.issue':'foreign'}}}]).encode())),self.assertRaises(RuntimeError):module.owned('foreign','container')
            for kind,metadata in [('container',{'Config':{'Labels':{'dkp.issue':'104'}}}),('volume',{'Labels':{'dkp.issue':'104'}})]:
                with patch.object(module,'run',return_value=subprocess.CompletedProcess([],0,json.dumps([metadata]).encode())):module.owned('owned',kind)

    def test_command_failure_is_finite_and_cli_never_selects_outside_private_root(self):
        import argparse
        with patch.object(module.subprocess,'run',return_value=subprocess.CompletedProcess([],1,b'private-output',b'private-secret')):
            with self.assertRaisesRegex(RuntimeError,'private diagnostics suppressed'):module.run('docker')
            self.assertEqual(1,module.run('docker',check=False).returncode)
        for action,image in [('prepare',None),('cleanup',None),('run','test-image')]:
            args=argparse.Namespace(action=action,image=image,directory=Path('local-data/security/unit-fixture'))
            with patch.object(module.argparse.ArgumentParser,'parse_args',return_value=args),patch.object(module,'prepare') as prepare,patch.object(module,'cleanup') as cleanup,patch.object(module,'execute') as execute:
                module.main();{'prepare':prepare,'cleanup':cleanup,'run':execute}[action].assert_called_once()
        for directory,image in [(Path('/outside'),None),(Path('local-data/security'),None),(Path('local-data/security/unit-fixture'),None)]:
            args=argparse.Namespace(action='run',image=image,directory=directory)
            with patch.object(module.argparse.ArgumentParser,'parse_args',return_value=args),patch.object(module.argparse.ArgumentParser,'error',side_effect=SystemExit),self.assertRaises(SystemExit):module.main()
        args=argparse.Namespace(action='prepare',image=None,directory=Path('local-data/security/unit-fixture'))
        with patch.object(module.argparse.ArgumentParser,'parse_args',return_value=args),patch.object(module,'prepare',side_effect=RuntimeError('finite failure')),self.assertRaises(SystemExit):module.main()


if __name__ == '__main__':
    unittest.main()
