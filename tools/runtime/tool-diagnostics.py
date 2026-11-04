"""Pure original-catalog tool diagnostics. Transport and authority are injected."""
import datetime
import math
import time
import types
import uuid

MAX_INTEGER = 9007199254740991
FAMILIES = frozenset(('operation', 'dependency', 'build'))
FACTS = frozenset(('status', 'bytes', 'queueDepth', 'cancelRequested', 'count',
    'cleanupSettled', 'remainingDeadlineMs'))


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')


def number(value):
    return type(value) is int and 0 <= value <= MAX_INTEGER


def monotonic_value(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


class Diagnostics:
    """No I/O, registration, process execution or retained exception objects.

    The trusted original loader supplies exact class/code objects and vocabulary.
    Admission is not a persistence receipt. Configuration errors fail before use;
    optional diagnostic calls never replace the original work or exception.
    """
    def __init__(self, codec, vocabulary, emit, correlation_id, classes, frames,
                 *, clock=time.monotonic, now=utc, identifier=lambda: str(uuid.uuid4())):
        self._codec, self._emit = codec, emit
        self._clock, self._now, self._identifier = clock, now, identifier
        if (type(correlation_id) is not str or str(uuid.UUID(correlation_id)) != correlation_id
                or uuid.UUID(correlation_id).int == 0):
            raise ValueError('TOOL_DIAGNOSTICS_CONFIG_INVALID')
        self._correlation = correlation_id
        self._phases = frozenset(vocabulary['phases'])
        self._errors = frozenset(vocabulary['errorCodes'])
        known_classes = frozenset(vocabulary['exceptionClasses'])
        known_frames = {(row['symbol'], row['source']) for row in vocabulary['frames']}
        if not (callable(emit) and callable(clock) and callable(now) and callable(identifier)):
            raise ValueError('TOOL_DIAGNOSTICS_CONFIG_INVALID')
        self._classes, self._frames = {}, {}
        for cls, name in classes.items():
            if not isinstance(cls, type) or not issubclass(cls, BaseException) or name not in known_classes:
                raise ValueError('TOOL_DIAGNOSTICS_CONFIG_INVALID')
            self._classes[cls] = name
        for code, frame in frames.items():
            if (not isinstance(code, types.CodeType) or type(frame) is not dict
                    or frame.keys() != {'symbol', 'source'}
                    or (frame['symbol'], frame['source']) not in known_frames):
                raise ValueError('TOOL_DIAGNOSTICS_CONFIG_INVALID')
            # Code equality deliberately ignores co_filename. Retain the exact
            # original object so a different source's equal code cannot match.
            self._frames[id(code)] = (code, dict(frame))
        self._counts = dict(admitted=0, rejected=0, emitFailures=0)

    def _increment(self, name):
        self._counts[name] = min(MAX_INTEGER, self._counts[name] + 1)

    def status(self):
        return dict(self._counts)

    def boundary_start(self):
        try:
            marker = self._clock()
            if not monotonic_value(marker):
                raise ValueError('INVALID_CLOCK')
            return marker
        except BaseException:
            self._increment('emitFailures')
            return None

    def _elapsed(self, marker):
        current = self._clock()
        if not monotonic_value(marker) or not monotonic_value(current) or current < marker:
            raise ValueError('INVALID_CLOCK')
        elapsed = (current - marker) * 1000
        return MAX_INTEGER if not math.isfinite(elapsed) else min(MAX_INTEGER, int(elapsed))

    def _failure(self, error):
        if not isinstance(error, BaseException):
            raise ValueError('INVALID_FAILURE')
        failure = dict(causeClasses=[], stackFrames=[])
        counts = dict(unregisteredSymbols=0, truncatedFields=0)
        current, visited, frame_count = error, set(), 0
        for depth in range(5):
            visited.add(id(current))
            name = self._classes.get(type(current))
            if name is None:
                counts['unregisteredSymbols'] += 1
            elif depth == 0:
                failure['exceptionClass'] = name
            else:
                failure['causeClasses'].append(name)
            trace = BaseException.__getattribute__(current, '__traceback__')
            while trace is not None and frame_count < 32:
                frame_count += 1
                actual = trace.tb_frame.f_code
                registered = self._frames.get(id(actual))
                if registered is None or registered[0] is not actual:
                    counts['unregisteredSymbols'] += 1
                else:
                    failure['stackFrames'].append({**registered[1], 'line': trace.tb_lineno})
                trace = trace.tb_next
            if trace is not None:
                counts['truncatedFields'] += 1
            following = BaseException.__getattribute__(current, '__cause__')
            if following is None and not BaseException.__getattribute__(current, '__suppress_context__'):
                following = BaseException.__getattribute__(current, '__context__')
            if following is None:
                break
            if depth == 4 or id(following) in visited:
                counts['truncatedFields'] += 1
                break
            current = following
        return {'failure': failure, 'counts': {key: value for key, value in counts.items() if value}}

    def _send(self, phase, name, outcome, duration, attempt, facts, error_code, error=None):
        try:
            event = dict(eventId=self._identifier(), timestamp=self._now(), severity='DEBUG',
                eventName=name, correlationId=self._correlation, phase=phase, outcome=outcome,
                durationMs=duration, attempt=attempt, count=facts.get('count', 0),
                cleanupSettled=facts.get('cleanupSettled', False))
            diagnostics = self._failure(error) if error is not None else {}
            if error_code is not None:
                diagnostics['errorCode'] = error_code
            for key in ('status', 'remainingDeadlineMs'):
                if key in facts:
                    diagnostics[key] = facts[key]
            attributes = {key: facts[key] for key in ('bytes', 'queueDepth', 'cancelRequested') if key in facts}
            if attributes:
                diagnostics['attributes'] = attributes
            if diagnostics:
                event['diagnostics'] = diagnostics
            self._codec.prepare(event)
            result = self._emit(event)
            if result in ('ACCEPTED', 'DUPLICATE'):
                self._increment('admitted')
                return result
            self._increment('rejected')
            return 'REJECTED'
        except BaseException:
            self._increment('emitFailures')
            return 'REJECTED'

    def operation(self, phase, *, family='operation', attempt=1, count=0, error_code=None):
        try:
            valid = (type(phase) is str and phase in self._phases and type(family) is str
                and family in FAMILIES and number(attempt) and number(count)
                and (error_code is None or (type(error_code) is str and error_code in self._errors)))
        except BaseException:
            valid = False
        if not valid:
            self._increment('rejected')
        return _Scope(self, phase, family, attempt, count, error_code, valid)

    def failure_note(self, phase, error, *, boundary_started_at, error_code):
        try:
            if phase not in self._phases or error_code not in self._errors:
                raise ValueError('INVALID_NOTE')
            duration = self._elapsed(boundary_started_at)
            if not isinstance(error, BaseException):
                raise ValueError('INVALID_NOTE')
        except BaseException:
            self._increment('rejected')
            return 'REJECTED'
        return self._send(phase, 'operation.failed', 'FAILED', duration, 1, {}, error_code, error)


class _Scope:
    def __init__(self, owner, phase, family, attempt, count, error_code, enabled):
        if not enabled:
            phase, family, attempt, count, error_code = None, 'operation', 0, 0, None
        self._owner, self._phase, self._family = owner, phase, family
        self._attempt, self._error_code, self._enabled = attempt, error_code, enabled
        self._facts, self._marker = {'count': count}, None

    def record(self, **facts):
        try:
            if not facts.keys() <= FACTS:
                raise ValueError('INVALID_FACTS')
            for key, value in facts.items():
                if key in ('cancelRequested', 'cleanupSettled'):
                    valid = type(value) is bool
                else:
                    valid = number(value) and (key != 'status' or 100 <= value <= 599)
                if not valid:
                    raise ValueError('INVALID_FACTS')
            self._facts.update(facts)
            return True
        except BaseException:
            self._owner._increment('rejected')
            return False

    def __enter__(self):
        if self._enabled:
            self._marker = self._owner.boundary_start()
            self._enabled = self._marker is not None
            if self._enabled:
                self._owner._send(self._phase, self._family + '.started', 'STARTED',
                    0, self._attempt, self._facts, None)
        return self

    def __exit__(self, kind, error, trace):
        if self._enabled:
            try:
                duration = self._owner._elapsed(self._marker)
                cancelled = isinstance(error, KeyboardInterrupt)
                succeeded = error is None or (isinstance(error, SystemExit)
                    and (error.code is None or (isinstance(error.code, int) and error.code == 0)))
                name = ('operation.cancelled' if cancelled else
                    self._family + ('.succeeded' if succeeded else '.failed'))
                outcome = 'CANCELLED' if cancelled else ('SUCCEEDED' if succeeded else 'FAILED')
                self._owner._send(self._phase, name, outcome, duration, self._attempt,
                    self._facts, self._error_code if not succeeded else None, error if not succeeded else None)
            except BaseException:
                self._owner._increment('emitFailures')
        return False
