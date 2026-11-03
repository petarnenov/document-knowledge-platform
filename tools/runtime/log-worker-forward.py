"""External producer for one explicitly bound inspector; never gives transport authority to the worker."""
import argparse
import datetime
import fcntl
import importlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import selectors
import signal
import stat
import subprocess
import sys
import threading
import time


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    result = importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


image = module('worker_forward_image', 'log-worker-image.py')
delivery = module('worker_forward_delivery', 'log-python-deliver.py')
records = module('worker_forward_records', 'log-worker-records.py')
LAUNCH_ENV = ('JAVA_HOME', 'PATH', 'JAVA_OPTS', 'STRUCTURE_INSPECTOR_OPTS', 'JAVA_TOOL_OPTIONS',
    'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'LD_PRELOAD', 'LD_LIBRARY_PATH', 'ENV', 'BASH_ENV', 'CLASSPATH')
CONFIG = frozenset(('schemaVersion', 'imageDirectory', 'containerId', 'containerStartedAt', 'runId', 'instanceId',
    'sdkArtifact', 'sdkArtifactSha256', 'sdkManifestSha256', 'endpoint', 'connectAddress', 'producerToken', 'caFile'))


def require(value):
    if not value:raise ValueError('LOG_WORKER_FORWARD_INVALID')


def directory(path):
    path = Path(path)
    require(path.is_absolute() and path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISDIR(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o700 and info.st_uid == os.getuid())
    return path


def binding(config, proof):
    """Recheck exact live process, immutable image, identity and bounded log source; no mutation."""
    raw = image.r.command(['docker', 'container', 'inspect', config['containerId']], timeout=5)
    actual = json.loads(raw)[0]
    require(actual['Id'] == config['containerId'] and actual['Image'] == proof['imageId']
        and actual['Config']['Image'] == proof['imageId'] and actual['State']['Running']
        and actual['State']['StartedAt'] == config['containerStartedAt'] and actual['RestartCount'] == 0)
    cfg, host = actual['Config'], actual['HostConfig']
    labels = cfg['Labels'] or {}
    expected = dict(image.labels_for(proof), **{'dkp.log.run-id':config['runId'], 'dkp.log.instance-id':config['instanceId']})
    require(all(labels.get(key) == value for key, value in expected.items()))
    require(actual['Path'] == '/app/bin/structure-inspector' and actual['Args'] == [])
    env = cfg['Env'] or []
    original = image.r.inspect('image', proof['imageId'])
    require(original['Id'] == proof['imageId'])
    defaults = original['Config']['Env'] or []
    for name in LAUNCH_ENV:
        require([entry for entry in env if entry.startswith(name+'=')]
            == [entry for entry in defaults if entry.startswith(name+'=')])
    for key, value in {'DKP_INSPECTOR_LOG_MANIFEST_SHA256':proof['manifestSha256'],
            'DKP_INSPECTOR_LOG_RUN_ID':config['runId'], 'DKP_INSPECTOR_LOG_INSTANCE_ID':config['instanceId']}.items():
        require([entry for entry in env if entry.startswith(key+'=')] == [key+'='+value])
    require(cfg['User'] == '10001:10001' and cfg['Entrypoint'] == ['/app/bin/structure-inspector']
        and cfg['Cmd'] in (None, []) and not cfg['Tty'] and host['ReadonlyRootfs']
        and host['CapDrop'] == ['ALL'] and not host.get('CapAdd') and not host['Privileged']
        and 'no-new-privileges:true' in host['SecurityOpt']
        and re.fullmatch(r'container:[0-9a-f]{64}', host['NetworkMode'])
        and host['RestartPolicy']['Name'] in ('no', '')
        and host['LogConfig'] == {'Type':'json-file', 'Config':{'max-size':'1m', 'max-file':'3'}})
    # Preserve the selected compose profile: TLS authority only, plus disposable /tmp.
    # No bind/volume/tmpfs may replace distribution, JRE or launcher-system bytes.
    mounts = actual['Mounts']
    authority = [mount for mount in mounts if mount['Type'] == 'volume'
        and mount['Destination'] == '/run/dkp/inspector' and mount['RW'] is False]
    temporary = [mount for mount in mounts if mount['Type'] == 'tmpfs' and mount['Destination'] == '/tmp' and mount['RW'] is True]
    require(len(authority) == 1 and len(temporary) <= 1 and len(mounts) == len(authority)+len(temporary)
        and set(host['Tmpfs']) == {'/tmp'})
    return actual


def configuration(path):
    require(platform.python_version() == '3.14.10')
    config = json.loads(delivery.private(path, 8192), object_pairs_hook=delivery.unique)
    require(type(config) is dict and config.keys() == CONFIG and type(config['schemaVersion']) is int and config['schemaVersion'] == 1)
    require(type(config['containerId']) is str and re.fullmatch(r'[0-9a-f]{64}', config['containerId']))
    require(type(config['containerStartedAt']) is str and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{1,9}Z', config['containerStartedAt']))
    for key in ('runId', 'instanceId'):image.i.m.identifier(config[key])
    require(type(config['producerToken']) is str and re.fullmatch(r'[A-Za-z0-9_-]{32,512}', config['producerToken']))
    ca = Path(config['caFile'])
    require(ca.is_absolute() and ca.resolve(strict=True) == ca and ca.is_file()
        and not ca.stat().st_mode & 0o022 and ca.stat().st_size <= 65536)
    proof = image.image(Path(config['imageDirectory']))
    binding(config, proof)
    manifest = image.i.document(Path(config['imageDirectory'])/'manifest.json', image.i.m.MAX_MANIFEST)
    identity = {key:manifest[key] for key in ('buildId', 'component', 'gitSha', 'sourceFingerprint')}
    identity.update(runId=config['runId'], instanceId=config['instanceId'])
    return config, proof, identity, manifest['vocabulary']


def producer(config, identity, vocabulary, state):
    artifact = Path(config['sdkArtifact'])
    delivery.load_artifact(artifact, config['sdkArtifactSha256'], config['sdkManifestSha256'])
    require(not any(name == 'dkp_logging' or name.startswith('dkp_logging.') for name in sys.modules))
    sys.path.insert(0, str(artifact))
    wire, outbound, spool, dispatcher = [importlib.import_module('dkp_logging.'+name) for name in ('wire', 'https', 'spool', 'producer')]
    require(all(value.__file__.startswith(str(artifact)+'/') for value in (wire, outbound, spool, dispatcher)))
    transport = outbound.HttpsTransport(config['endpoint'], config['producerToken'], config['caFile'], config['connectAddress'])
    try:
        durable = spool.FileLogSpool(state/'spool', identity, transport.credential_fingerprint())
        worker = dispatcher.LogProducer(identity, vocabulary, transport, durable)
    except Exception:
        transport.close();raise
    return worker, wire.Wire


def forward(configuration_path, state, stop=None):
    state = directory(state)
    config, proof, identity, vocabulary = configuration(configuration_path)
    lock = os.open(state/'forward.lock', os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW, 0o600)
    worker = stream = reader = None
    stream_state, exit_code, stopped, last_input = 'NOT_STARTED', None, False, None
    failure = None
    try:
        info = os.fstat(lock)
        require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600 and info.st_uid == os.getuid() and info.st_size == 0)
        fcntl.flock(lock, fcntl.LOCK_EX|fcntl.LOCK_NB)
        worker, factory = producer(config, identity, vocabulary, state)
        reader = records.WorkerRecords(identity, vocabulary, proof['manifestSha256'], factory, worker.emit)
        # Fixed read-only Docker command. Caller cannot supply a command or an arbitrary stream path.
        stream = subprocess.Popen(['docker', 'logs', '--follow', '--tail', '512', config['containerId']],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
        stream_state = 'ACTIVE'
        stop = threading.Event() if stop is None else stop
        last_check = time.monotonic()
        with selectors.DefaultSelector() as selector:
            selector.register(stream.stdout, selectors.EVENT_READ)
            while not stop.is_set():
                for key, _ in selector.select(.2):
                    chunk = os.read(key.fileobj.fileno(), 4096)
                    if not chunk:
                        stream_state = 'EOF';reader.finish();break
                    reader.feed(chunk);last_input = time.monotonic()
                if stream_state == 'EOF':break
                if time.monotonic()-last_check >= 2:
                    try:binding(config, proof)
                    except Exception:
                        stream_state = 'BINDING_LOST';break
                    last_check = time.monotonic()
                save_status(state, identity, proof, reader, worker, stream_state, stream.poll(), last_input, False)
            if stream_state == 'ACTIVE':stream_state = 'STOP_REQUESTED'
    except Exception as error:
        failure = error;stream_state = 'FAILED'
    finally:
        if stream is not None:
            if stream.poll() is None:
                stream.terminate()
                try:stream.wait(timeout=2)
                except subprocess.TimeoutExpired:stream.kill();stream.wait(timeout=2)
            exit_code = stream.poll();stream.stdout.close()
        if reader is not None:reader.finish()
        if worker is not None:
            worker.close();stopped = worker.await_closed(5)
        try:
            if reader is not None:save_status(state, identity, proof, reader, worker, stream_state, exit_code, last_input, stopped)
        finally:os.close(lock)
    if failure is not None:raise ValueError('LOG_WORKER_FORWARD_FAILED') from None
    return json.loads(delivery.private(state/'status.json', 16384))


def save_status(state, identity, proof, reader, worker, stream_state, exit_code, last_input, stopped):
    value = dict(schemaVersion=1, updatedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        identity=identity, manifestSha256=proof['manifestSha256'], imageId=proof['imageId'],
        streamState=stream_state, streamExitCode=exit_code, historyBounded=True, historyComplete=False,
        inputIdleMs=None if last_input is None else min(records.MAX_INTEGER, int((time.monotonic()-last_input)*1000)),
        reader=reader.status(), producer=worker.status(), dispatcherStopped=stopped, persistence='NOT_ASSERTED')
    target = state/'status.json'
    if target.exists() or target.is_symlink():delivery.private(target, 16384)
    image.r.write_private(target, json.dumps(value, separators=(',', ':')), replace=target.exists())


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--configuration', type=Path, required=True);parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args();stop = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):signal.signal(sig, lambda *_:stop.set())
    try:
        result = forward(args.configuration, args.directory, stop)
        print(json.dumps({'streamState':result['streamState'], 'persistence':result['persistence']}))
        return 0 if result['streamState'] == 'STOP_REQUESTED' else 1
    except Exception:
        print('{"result":"FAIL","code":"LOG_WORKER_FORWARD_INVALID"}');return 1


if __name__ == '__main__':raise SystemExit(main())
