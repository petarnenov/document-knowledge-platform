"""Fixed original provider runner with producer-only bounded diagnostics."""
import argparse
import builtins
import importlib
import importlib.util
import io
import ipaddress
import json
import marshal
import os
from pathlib import Path
import platform
import stat
import sys
import time
import types
import uuid

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent


def helper(alias, filename):
    spec = importlib.util.spec_from_file_location(alias, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


i = helper('tool_run_install', 'log-tool-install.py')
d = helper('tool_run_sdk_verify', 'log-python-deliver.py')
v = i.v
CONFIG = frozenset(('schemaVersion', 'installationDirectory', 'installationSha256',
    'sdkArtifact', 'sdkArtifactSha256', 'sdkManifestSha256', 'runId', 'instanceId',
    'correlationId', 'endpoint', 'connectAddress', 'caFile', 'producerToken',
    'spoolDirectory', 'statusFile', 'finishWaitSeconds'))


def path_string(value, *, exists=True):
    v.require(type(value) is str and len(value.encode('utf-8')) <= 4096)
    return i.canonical(value, exists=exists)


def private_file(path, maximum):
    info = path.lstat()
    v.require(stat.S_IMODE(info.st_mode) == 0o600)
    return v.read(path, maximum, owner=os.getuid())


def overlaps(left, right):
    return left.is_relative_to(right) or right.is_relative_to(left)


def configuration(path, business=()):
    path = i.canonical(path)
    i.directory(path.parent, 0o700)
    config = v.record(private_file(path, 16384), 16384)
    v.require(config.keys() == CONFIG and type(config['schemaVersion']) is int and config['schemaVersion'] == 1
        and type(config['finishWaitSeconds']) is int and 0 <= config['finishWaitSeconds'] <= 5)
    for key in ('runId', 'instanceId', 'correlationId'):v.m.identifier(config[key])
    for key in ('installationSha256', 'sdkArtifactSha256', 'sdkManifestSha256'):
        v.m.text(config[key], '[0-9a-f]{64}', 64)
    paths = {key:path_string(config[key], exists=key not in ('spoolDirectory', 'statusFile'))
        for key in ('installationDirectory', 'sdkArtifact', 'caFile', 'spoolDirectory', 'statusFile')}
    installation = i.verify_installation(paths['installationDirectory'], config['installationSha256'])
    v.require(config['runId'] != installation['manifest']['buildId'])
    for key in ('spoolDirectory', 'statusFile'):i.directory(paths[key].parent, 0o700)
    spool, status = paths['spoolDirectory'], paths['statusFile']
    if spool.exists():i.directory(spool, 0o700)
    if status.exists():private_file(status, 256 * 1024)
    ca = paths['caFile'];ca_info = ca.lstat()
    v.require(stat.S_ISREG(ca_info.st_mode) and not ca_info.st_mode & 0o022)
    v.read(ca, 65536)
    inputs = [path, ca, paths['sdkArtifact'], paths['installationDirectory'] / 'installation.json']
    inputs.extend(paths['installationDirectory'] / row['path'] for row in installation['record']['files'])
    trees = [paths['installationDirectory'], *(i.canonical(Path(value).absolute(), exists=False) for value in business)]
    v.require(all(not overlaps(spool, tree) and not overlaps(status, tree) for tree in trees)
        and not overlaps(spool, status) and all(not input_path.is_relative_to(spool) for input_path in inputs)
        and status not in inputs)
    if status.exists():
        info = status.stat()
        v.require(all((info.st_dev, info.st_ino) != (entry.stat().st_dev, entry.stat().st_ino) for entry in inputs))
    v.require(type(config['endpoint']) is str and type(config['connectAddress']) is str)
    ipaddress.ip_address(config['connectAddress'])
    d.load_artifact(paths['sdkArtifact'], config['sdkArtifactSha256'], config['sdkManifestSha256'])
    v.m.text(config['producerToken'], '[A-Za-z0-9_-]{32,512}', 512)
    return config, installation


def sdk(config):
    v.require(not any(name == 'dkp_logging' or name.startswith('dkp_logging.') for name in sys.modules))
    artifact = config['sdkArtifact']
    sys.path.insert(0, artifact)
    modules = {name:importlib.import_module('dkp_logging' + ('.' + name if name else ''))
        for name in ('', 'wire', 'producer', 'https', 'spool')}
    v.require(all(module.__file__ == artifact + '/dkp_logging/' + (name or '__init__') + '.pyc'
        for name, module in modules.items()))
    return modules


def code_graphs(root, compiler):
    codes, frames = {}, []
    for row, (name, source, filename) in zip(compiler['modules'], v.c.MODULES):
        raw = v.read(root / 'compiled' / filename, v.c.MAX_COMPILED, owner=os.getuid())
        stream = io.BytesIO(raw[16:]);code = marshal.load(stream)
        v.require(stream.tell() == len(raw) - 16)
        for frame in row['frames']:
            actual = code
            for index in frame['locator']:actual = actual.co_consts[index]
            v.require(type(actual) is types.CodeType)
            frames.append((actual, dict(symbol=frame['symbol'], source=source)))
        codes[name] = code
    return codes, frames


def original_module(name, code, root):
    module = types.ModuleType(name)
    source = next(source for module_name, source, _ in v.c.MODULES if module_name == name)
    module.__file__ = str(root / 'source' / source)
    module.__package__ = ''
    sys.modules[name] = module
    exec(code, module.__dict__)
    return module


class Delivery:
    def __init__(self, config, manifest, modules):
        self.config, self.modules = config, modules
        self.ids, self.omitted = {}, 0
        identity = {key:manifest[key] for key in ('buildId', 'component', 'gitSha', 'sourceFingerprint')}
        identity.update(runId=config['runId'], instanceId=config['instanceId'])
        self.identity = identity
        self.codec = modules['wire'].Wire(identity, manifest['vocabulary'])
        transport = self.transport()
        spool = None
        try:
            spool = modules['spool'].FileLogSpool(Path(config['spoolDirectory']), identity, transport.credential_fingerprint())
            restored = spool.load()
            for event in restored:self.remember(event.event_id)
            self.producer = modules['producer'].LogProducer(identity, manifest['vocabulary'], transport, spool)
        except BaseException:
            transport.close()
            if spool is not None:spool.close()
            raise

    def transport(self):
        c = self.config
        return self.modules['https'].HttpsTransport(c['endpoint'], c['producerToken'], c['caFile'], c['connectAddress'])

    def remember(self, event_id):
        if event_id in self.ids:return
        if len(self.ids) < 2048:self.ids[event_id] = None
        else:self.omitted += 1

    def emit(self, event):
        result = self.producer.emit(event)
        if result in ('ACCEPTED', 'DUPLICATE'):self.remember(event['eventId'])
        return result

    def finish(self, diagnostics=None):
        deadline = time.monotonic() + self.config['finishWaitSeconds']
        try:
            while self.producer.status()['pending'] and time.monotonic() < deadline:
                time.sleep(min(.05, max(0, deadline - time.monotonic())))
        finally:
            self.producer.close()
        stopped = self.producer.await_closed(5)
        states = dict.fromkeys(self.ids, 'UNKNOWN')
        if stopped and states:
            lookup = self.transport()
            try:
                deadline = time.monotonic() + 5
                ids = list(states)
                for offset in range(0, len(ids), 32):
                    if time.monotonic() >= deadline:break
                    batch = ids[offset:offset + 32]
                    try:
                        code, raw = lookup.post('receipts', self.modules['wire'].encode({'eventIds':batch}))
                        if code == 200:
                            found = self.modules['wire'].receipt_states(raw, batch)
                            states.update({key:value if value == 'COMMITTED' else 'UNKNOWN' for key, value in found.items()})
                    except Exception:pass
            finally:lookup.close()
        result = dict(schemaVersion=1, buildId=self.identity['buildId'], runId=self.identity['runId'],
            instanceId=self.identity['instanceId'], eventStates=states, omittedSelection=self.omitted,
            workerStopped=stopped, sdk=self.producer.status(), diagnostics=diagnostics.status() if diagnostics else {},
            result='COMMITTED' if stopped and not self.omitted and all(value == 'COMMITTED' for value in states.values()) else 'PENDING')
        status_file(Path(self.config['statusFile']), result)
        return result


def status_file(path, result):
    i.directory(path.parent, 0o700)
    if path.exists():private_file(path, 256 * 1024)
    raw = v.m.canonical(result)
    v.require(len(raw) <= 256 * 1024)
    temporary = path.with_name('.dkp-tool-status-' + uuid.uuid4().hex)
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, 'wb') as output:output.write(raw)
        os.replace(temporary, path)
    finally:
        if temporary.exists():temporary.unlink()


def execute(configuration_path, directory=None, runtime_directory=None, *, forward_only=False):
    v.require(platform.python_version() == '3.14.10')
    v.require((forward_only and directory is None and runtime_directory is None)
        or (not forward_only and directory is not None and runtime_directory is not None))
    v.require(not any(name in sys.modules for name in ('dkp_tool_diagnostics', 'dkp_provider_jobs')))
    config, installed = configuration(configuration_path, () if forward_only else (directory, runtime_directory))
    root = Path(config['installationDirectory'])
    v.require(Path(__file__).resolve() == root / 'source/tools/runtime/log-tool-run.py')
    # Every local bootstrap helper was read from this already verified immutable tree.
    v.require(HERE == root / 'source/tools/runtime')
    sys.dont_write_bytecode = True
    modules = sdk(config)
    codes, frames = code_graphs(root, installed['compiler'])
    port = original_module('dkp_tool_diagnostics', codes['dkp_tool_diagnostics'], root)
    delivery = Delivery(config, installed['manifest'], modules)
    diagnostics = None
    try:
        classes = [(getattr(builtins, name), 'builtins.' + name)
            for name in ('ValueError', 'OSError', 'RuntimeError', 'KeyboardInterrupt', 'SystemExit')]
        diagnostics = port.Diagnostics(delivery.codec, installed['manifest']['vocabulary'], delivery.emit,
            config['correlationId'], classes, frames)
        if not forward_only:
            with diagnostics.operation('PROVIDER_JOBS_MODULE_LOAD', error_code='PROVIDER_JOBS_MODULE_LOAD_FAILED'):
                provider = original_module('dkp_provider_jobs', codes['dkp_provider_jobs'], root)
                concrete = provider.AcceptanceFailure
                v.require(type(concrete) is type and concrete.__module__ == 'dkp_provider_jobs'
                    and concrete.__qualname__ == 'AcceptanceFailure' and concrete.__bases__ == (ValueError,)
                    and any(concrete.__init__.__code__ is actual for actual, _ in frames)
                    and diagnostics.bind(classes=[(concrete, 'dkp_provider_jobs.AcceptanceFailure')]))
            with diagnostics.operation('PROVIDER_JOBS_ENTRYPOINT', error_code='PROVIDER_JOBS_ENTRYPOINT_FAILED'):
                provider.main(diagnostics, ['--directory', str(directory), '--runtime-directory', str(runtime_directory)])
    finally:
        try:result = delivery.finish(diagnostics)
        except BaseException:result = None  # Finish never replaces the original business outcome.
    return 0 if not forward_only or (result is not None and result['result'] == 'COMMITTED') else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--configuration', type=Path, required=True)
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--runtime-directory', type=Path)
    parser.add_argument('--forward-only', action='store_true')
    args = parser.parse_args()
    try:return execute(args.configuration, args.directory, args.runtime_directory, forward_only=args.forward_only)
    except Exception:
        print('{"result":"FAIL","code":"LOG_TOOL_RUN_INVALID"}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.dont_write_bytecode = True
    raise SystemExit(main())
