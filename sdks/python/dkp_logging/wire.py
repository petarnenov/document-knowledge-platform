"""Closed v1 lifecycle codec. Never accepts raw messages or identity authority."""
import datetime
import json
import re
import uuid
from dataclasses import dataclass

MAX_INTEGER = 9007199254740991
EVENT_BYTES, BATCH_BYTES, BATCH_EVENTS = 8192, 32768, 32
EVENTS = frozenset(('process.started', 'process.stopped', 'operation.started',
    'operation.succeeded', 'operation.failed', 'operation.cancelled', 'dependency.started',
    'dependency.succeeded', 'dependency.failed', 'build.started', 'build.succeeded',
    'build.failed', 'ui.route.changed', 'ui.identity.changed', 'producer.delivery.degraded',
    'producer.events.lost'))
IDENTITY = frozenset(('buildId', 'runId', 'instanceId', 'component', 'gitSha', 'sourceFingerprint'))
INPUT = frozenset(('eventId', 'timestamp', 'severity', 'eventName', 'correlationId',
    'phase', 'outcome', 'durationMs', 'attempt', 'count', 'cleanupSettled'))


def require(value):
    if not value:
        raise ValueError('INVALID_LOG_VALUE')


def matches(value, pattern):
    return type(value) is str and re.fullmatch(pattern, value) is not None


def identifier(value):
    return matches(value, r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}') and uuid.UUID(value).int != 0


def timestamp(value):
    if not matches(value, r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z'):
        return False
    try:
        datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))
        return True
    except ValueError:
        return False


def closed(value, fields, optional=frozenset()):
    require(type(value) is dict and fields <= value.keys() and value.keys() <= fields | optional)


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


@dataclass(frozen=True)
class Prepared:
    event_id: str
    data: bytes


class Wire:
    def __init__(self, identity, vocabulary):
        closed(identity, IDENTITY)
        require(all(identifier(identity[key]) for key in ('buildId', 'runId', 'instanceId')))
        require(identity['buildId'] != identity['runId'])
        require(matches(identity['component'], r'[a-z][a-z0-9.-]{0,95}'))
        require(matches(identity['gitSha'], r'[0-9a-f]{40}'))
        require(matches(identity['sourceFingerprint'], r'[0-9a-f]{64}'))
        closed(vocabulary, frozenset(('events', 'phases')))
        events, phases = vocabulary['events'], vocabulary['phases']
        require(type(events) is list and 0 < len(events) <= len(EVENTS))
        require(type(phases) is list and 0 < len(phases) <= 256)
        require(all(type(event) is str and event in EVENTS for event in events))
        require(all(matches(phase, r'[A-Za-z0-9_.-]{1,96}') for phase in phases))
        require(len(set(events)) == len(events) and len(set(phases)) == len(phases))
        self._identity = dict(identity)
        self._events, self._phases = frozenset(events), frozenset(phases)

    def prepare(self, value):
        closed(value, INPUT, frozenset(('traceId', 'spanId')))
        require(identifier(value['eventId']) and identifier(value['correlationId']) and timestamp(value['timestamp']))
        require(type(value['severity']) is str and value['severity'] in ('TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'))
        require(type(value['eventName']) is str and value['eventName'] in self._events)
        require(type(value['phase']) is str and value['phase'] in self._phases)
        require(type(value['outcome']) is str and value['outcome'] in ('STARTED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'RETRYING', 'REJECTED', 'UNKNOWN'))
        require(all(type(value[key]) is int and 0 <= value[key] <= MAX_INTEGER for key in ('durationMs', 'attempt', 'count')))
        require(type(value['cleanupSettled']) is bool)
        if 'traceId' in value:
            require(matches(value['traceId'], r'[0-9a-f]{32}') and int(value['traceId'], 16) != 0)
        if 'spanId' in value:
            require('traceId' in value and matches(value['spanId'], r'[0-9a-f]{16}') and int(value['spanId'], 16) != 0)
        row = {**self._identity, **{key: value[key] for key in ('eventId', 'timestamp', 'severity', 'eventName', 'correlationId')}}
        for key in ('traceId', 'spanId'):
            if key in value:
                row[key] = value[key]
        row['context'] = {key: value[key] for key in ('phase', 'outcome', 'durationMs', 'attempt')}
        row['context']['attributes'] = {key: value[key] for key in ('count', 'cleanupSettled')}
        data = encode(row)
        require(len(data) <= EVENT_BYTES)
        return Prepared(value['eventId'], data)


def encode_batch(events):
    require(type(events) is list and 0 < len(events) <= BATCH_EVENTS)
    require(all(type(event) is Prepared and 0 < len(event.data) <= EVENT_BYTES for event in events))
    require(len({event.event_id for event in events}) == len(events))
    result = b'{"schemaVersion":1,"events":[' + b','.join(event.data for event in events) + b']}'
    require(len(result) <= BATCH_BYTES)
    return result


def parse_response(data):
    require(type(data) is bytes and 0 < len(data) <= BATCH_BYTES)
    text = data.decode('utf-8', errors='strict')
    # Bound nesting BEFORE json.loads allocates recursive containers.
    depth, quoted, escaped = 0, False, False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in '[{':
            depth += 1
            require(depth <= 16)
        elif char in ']}':
            depth -= 1
            require(depth >= 0)

    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            result[key] = value
        return result

    def integer(raw):
        require(len(raw) <= 17)
        value = int(raw)
        require(abs(value) <= MAX_INTEGER)
        return value

    def invalid(_):
        raise ValueError('INVALID_LOG_RESPONSE')

    return json.loads(text, object_pairs_hook=pairs, parse_int=integer,
        parse_float=invalid, parse_constant=invalid)


def receipt_states(data, event_ids, commit_only=False):
    require(type(event_ids) is list and 0 < len(event_ids) <= BATCH_EVENTS)
    require(all(identifier(value) for value in event_ids) and len(set(event_ids)) == len(event_ids))
    root = parse_response(data)
    closed(root, frozenset(('receipts',)))
    require(type(root['receipts']) is list and len(root['receipts']) == len(event_ids))
    wanted, result = set(event_ids), {}
    for row in root['receipts']:
        require(type(row) is dict and identifier(row.get('eventId')) and row['eventId'] in wanted)
        state = row.get('state')
        require(type(state) is str and state in ('COMMITTED', 'UNKNOWN', 'EXPIRED') and (not commit_only or state == 'COMMITTED'))
        fields = frozenset(('eventId', 'state', 'receivedAt')) if state == 'COMMITTED' else frozenset(('eventId', 'state'))
        closed(row, fields)
        if state == 'COMMITTED':
            require(timestamp(row['receivedAt']))
        wanted.remove(row['eventId'])
        result[row['eventId']] = state
    require(not wanted)
    return result
