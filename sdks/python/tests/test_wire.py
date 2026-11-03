import copy
import json
import unittest
import uuid

from dkp_logging.wire import Wire, Prepared, encode, encode_batch, parse_response, receipt_states


def identity():
    return dict(buildId=str(uuid.uuid4()), runId=str(uuid.uuid4()), instanceId=str(uuid.uuid4()),
        component='runtime-tools', gitSha='a' * 40, sourceFingerprint='b' * 64)


def vocabulary():
    return dict(events=['process.started', 'operation.failed'], phases=['runtime', 'compile'])


def event():
    return dict(eventId=str(uuid.uuid4()), timestamp='2026-11-03T12:00:00.123456789Z',
        severity='DEBUG', eventName='process.started', correlationId=str(uuid.uuid4()), phase='runtime',
        outcome='STARTED', durationMs=0, attempt=1, count=0, cleanupSettled=True)


def receipts(ids, state='COMMITTED'):
    return encode({'receipts': [dict(eventId=value, state=state,
        **({'receivedAt': '2026-11-03T12:00:01Z'} if state == 'COMMITTED' else {})) for value in ids]})


class WireTests(unittest.TestCase):
    def setUp(self):
        self.identity, self.vocabulary = identity(), vocabulary()
        self.wire = Wire(self.identity, self.vocabulary)

    def test_closed_typed_wire_copies_identity_and_catalog(self):
        original = copy.deepcopy(self.identity)
        self.identity['component'] = 'changed'
        self.vocabulary['phases'].append('secret')
        item = event()
        item.update(traceId='c' * 32, spanId='d' * 16)
        prepared = self.wire.prepare(item)
        row = json.loads(prepared.data)
        self.assertEqual(original, {key: row[key] for key in original})
        self.assertEqual({'count': 0, 'cleanupSettled': True}, row['context']['attributes'])
        self.assertEqual('c' * 32, row['traceId'])
        item['phase'] = 'secret'
        with self.assertRaises(ValueError):
            self.wire.prepare(item)

    def test_invalid_identity_and_catalog_refused(self):
        for key, bad in [('buildId', '0' * 36), ('runId', self.identity['buildId']),
                ('instanceId', '00000000-0000-0000-0000-000000000000'), ('component', 'SECRET value'),
                ('gitSha', 'A' * 40), ('sourceFingerprint', False), ('tenant', 'secret')]:
            with self.subTest(key=key), self.assertRaises((ValueError, TypeError)):
                Wire({**self.identity, key: bad}, vocabulary())
        for bad in [{}, {'events': [], 'phases': ['runtime']}, {'events': ['raw.secret'], 'phases': ['runtime']},
                {'events': ['process.started'] * 2, 'phases': ['runtime']},
                {'events': ['process.started'], 'phases': [False]},
                {'events': ['process.started'], 'phases': ['runtime'] * 257}]:
            with self.subTest(vocabulary=bad), self.assertRaises(ValueError):
                Wire(identity(), bad)

    def test_raw_secret_authority_and_unsafe_types_rejected(self):
        for key, bad in [('message', 'password=secret'), ('actor', 'subject'), ('tenant', 'tenant-b'),
                ('severity', []), ('eventName', {}), ('phase', 'unreviewed'), ('outcome', False),
                ('durationMs', True), ('attempt', -1), ('count', 9007199254740992), ('count', 1.2),
                ('cleanupSettled', 1), ('timestamp', '2026-02-31T00:00:00Z'), ('timestamp', 'yesterday'),
                ('eventId', 'bad'), ('correlationId', None), ('traceId', '0' * 32), ('spanId', 'c' * 16)]:
            with self.subTest(key=key, bad=bad), self.assertRaises((ValueError, TypeError)):
                self.wire.prepare({**event(), key: bad})
        with self.assertRaises(ValueError):
            self.wire.prepare({key: value for key, value in event().items() if key != 'count'})

    def test_batch_exact_bytes_duplicates_and_caps(self):
        prepared = self.wire.prepare(event())
        self.assertEqual(json.loads(prepared.data), json.loads(encode_batch([prepared]))['events'][0])
        for bad in [[], [prepared] * 2, [prepared] * 33, [Prepared('id', b'')], [Prepared('id', b'x' * 8193)],
                [Prepared(str(uuid.uuid4()), b'x' * 8192) for _ in range(4)], ['raw secret']]:
            with self.subTest(size=len(bad)), self.assertRaises(ValueError):
                encode_batch(bad)

    def test_strict_response_parser_bounds_before_allocation(self):
        self.assertEqual({'safe': ['escaped"', True, None, -4]}, parse_response(b'{"safe":["escaped\\\"",true,null,-4]}'))
        for bad in [b'', b'x' * 32769, b'\xff', b'{"x":1,"x":2}', b'{} {}', b'{',
                b'[' * 17 + b']' * 17, b']', b'{"x":NaN}', b'{"x":1.5}',
                b'{"x":9007199254740992}', b'{"x":123456789012345678}', b'{"x":Infinity}']:
            with self.subTest(bad=bad[:30]), self.assertRaises((ValueError, UnicodeError)):
                parse_response(bad)

    def test_exact_commit_only_and_scoped_lookup_receipts(self):
        ids = [str(uuid.uuid4()), str(uuid.uuid4())]
        for state in ['COMMITTED', 'UNKNOWN', 'EXPIRED']:
            self.assertEqual(dict.fromkeys(ids, state), receipt_states(receipts(ids, state), ids))
            if state != 'COMMITTED':
                with self.assertRaises(ValueError):
                    receipt_states(receipts(ids, state), ids, True)
        root = json.loads(receipts(ids))
        variants = [{'receipts': []}, {'receipts': root['receipts'] * 2}, {'receipts': [root['receipts'][0]] * 2},
            {'receipts': [dict(root['receipts'][0], state='SAVED'), root['receipts'][1]]},
            {'receipts': [dict(root['receipts'][0], receivedAt='invalid'), root['receipts'][1]]},
            {'receipts': [dict(root['receipts'][0], token='secret'), root['receipts'][1]]},
            {'receipts': [dict(root['receipts'][0], eventId=str(uuid.uuid4())), root['receipts'][1]]},
            {'receipts': [None, root['receipts'][1]]}, {'receipts': root['receipts'], 'extra': True}]
        for bad in variants:
            with self.assertRaises(ValueError):
                receipt_states(encode(bad), ids)
        for bad_ids in [[], [ids[0]] * 2, ['invalid'], ids * 17]:
            with self.assertRaises(ValueError):
                receipt_states(receipts(ids), bad_ids)


if __name__ == '__main__':
    unittest.main()
