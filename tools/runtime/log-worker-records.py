"""Bounded structure-inspector stdout adapter. Runtime ownership/transport stay outside this port."""
import json
import re

PREFIX = b'DKP-INSPECTOR-LOG/1 '
MAX_RECORD, MAX_EVENT, MAX_INTEGER = 8192, 6144, 9007199254740991
FIELDS = frozenset(('schemaVersion', 'buildId', 'runId', 'instanceId', 'manifestSha256', 'event', 'loss'))
LOSS = frozenset(('dropped', 'rejected', 'writerFailures'))
PHASES = frozenset(('STARTUP', 'SHUTDOWN', 'REQUEST', 'OPERATION', 'CLEANUP', 'DEADLINE'))
EVENTS = frozenset(('process.started', 'process.stopped', 'operation.started', 'operation.succeeded', 'operation.failed', 'operation.cancelled'))
RESULTS = frozenset(('ACCEPTED', 'DUPLICATE', 'REJECTED', 'FULL', 'CLOSED'))


def require(value):
    if not value:
        raise ValueError('INVALID_WORKER_LOG_RECORD')


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


class WorkerRecords:
    """Trusted runtime supplies verified ORIGINAL worker identity/catalog and SDK Wire factory.

    No file, Docker, network, credential or registration authority. Accepted means SDK
    admission only; this adapter cannot assert COMMITTED or complete stream recovery.
    """
    def __init__(self, identity, vocabulary, manifest_sha256, wire_factory, emit):
        require(identity.get('component') == 'structure-inspector')
        require(type(manifest_sha256) is str and re.fullmatch(r'[0-9a-f]{64}', manifest_sha256))
        self.codec = wire_factory(identity, vocabulary)
        self.expected = {key: identity[key] for key in ('buildId', 'runId', 'instanceId')}
        self.expected['manifestSha256'] = manifest_sha256
        self.exceptions = frozenset(vocabulary.get('exceptionClasses', []))
        self.frames = {row['symbol']: row['source'] for row in vocabulary.get('frames', [])}
        self.emit = emit
        self.buffer = bytearray()
        self.draining = self.eof = self.incomplete = False
        self.loss = dict.fromkeys(LOSS, 0)
        self.counts = dict.fromkeys(('accepted', 'rejected', 'ignored', 'oversized', 'emitFailures'), 0)
        self.last = 'NONE'

    def _increment(self, key):
        self.counts[key] = min(MAX_INTEGER, self.counts[key] + 1)

    def _validate(self, raw):
        require(len(raw) <= MAX_RECORD and raw.startswith(PREFIX) and raw.endswith(b'\n'))
        record = json.loads(raw[len(PREFIX):-1].decode('utf-8'), object_pairs_hook=unique)
        require(type(record) is dict and record.keys() == FIELDS
            and type(record['schemaVersion']) is int and record['schemaVersion'] == 1)
        require(all(record[key] == value for key, value in self.expected.items()))
        loss = record['loss']
        require(type(loss) is dict and loss.keys() == LOSS)
        for key, value in loss.items():
            require(type(value) is int and self.loss[key] <= value <= MAX_INTEGER)
        event = record['event']
        require(type(event) is dict and len(encode(event)) <= MAX_EVENT)
        require(event.get('eventName') in EVENTS and event.get('phase') in PHASES)
        # SDK shape/type/enum validation remains authoritative. Unlike the public SDK
        # redactor, a worker record must already contain only original registered symbols.
        self.codec.prepare(event)
        failure = event.get('diagnostics', {}).get('failure', {})
        if 'exceptionClass' in failure:
            require(failure['exceptionClass'] in self.exceptions)
        require(all(name in self.exceptions for name in failure.get('causeClasses', [])))
        for frame in failure.get('stackFrames', []):
            source = self.frames.get(frame['symbol'])
            require(source is not None and ('source' not in frame or frame['source'] == source))
        return event, dict(loss)

    def _record(self, raw):
        if not raw.startswith(PREFIX):
            self._increment('ignored')
            return
        try:
            event, loss = self._validate(raw)
        except (ValueError, TypeError, KeyError, RecursionError, OverflowError):
            self._increment('rejected')
            return
        self.loss = loss
        try:
            result = self.emit(event)
            require(result in RESULTS)
        except Exception:
            self._increment('emitFailures')
            self.last = 'EMIT_FAILED'
            return
        self.last = result
        if result in ('ACCEPTED', 'DUPLICATE'):
            self._increment('accepted')

    def feed(self, data):
        require(type(data) is bytes and not self.eof)
        for value in data:
            if self.draining:
                if value == 10:
                    self.draining = False
                continue
            if value == 10:
                self._record(bytes(self.buffer) + b'\n')
                self.buffer.clear()
            elif len(self.buffer) < MAX_RECORD - 1:
                self.buffer.append(value)
            else:
                self.buffer.clear()
                self.draining = True
                self._increment('oversized')
                self._increment('rejected')

    def finish(self):
        if self.eof:
            return
        self.eof = True
        self.incomplete = bool(self.buffer) or self.draining
        if self.buffer:
            self._increment('rejected')
        self.buffer.clear()

    def status(self):
        return dict(self.counts, sourceLoss=dict(self.loss), bufferedBytes=len(self.buffer),
            draining=self.draining, streamState='EOF' if self.eof else 'ACTIVE',
            incomplete=self.incomplete, lastAdmission=self.last)
