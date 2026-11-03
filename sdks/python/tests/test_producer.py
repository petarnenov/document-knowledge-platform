import json
import threading
import time
import unittest
from unittest.mock import patch

from dkp_logging.producer import LogProducer
from test_wire import identity, vocabulary, event, receipts


def until(predicate):
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.01)
    raise AssertionError('SDK condition did not complete')


class Transport:
    def __init__(self):
        self.entered, self.release = threading.Event(), threading.Event()
        self.release.set()
        self.calls = []
        self.code, self.lookup, self.bad = 200, 'COMMITTED', False

    def post(self, route, data):
        self.calls.append((route, data))
        self.entered.set()
        if not self.release.wait(3):
            raise OSError('FIXTURE_WAIT')
        root = json.loads(data)
        ids = root['eventIds'] if route == 'receipts' else [row['eventId'] for row in root['events']]
        if self.code == -1:
            raise OSError('DO_NOT_LOG_RAW_SECRET')
        return self.code, b'bad' if self.bad else receipts(ids, self.lookup if route == 'receipts' else 'COMMITTED')

    def close(self):
        pass


class ProducerTests(unittest.TestCase):
    def make(self, transport):
        producer = LogProducer(identity(), vocabulary(), transport)
        self.addCleanup(lambda: (transport.release.set(), producer.close(), producer.await_closed()))
        return producer

    def test_commit_and_close_observe_actual_worker_exit(self):
        transport = Transport()
        producer = self.make(transport)
        self.assertEqual('ACCEPTED', producer.emit(event()))
        until(lambda: producer.status()['committed'] == 1)
        self.assertEqual(0, producer.status()['pendingBytes'])
        producer.close()
        self.assertTrue(producer.await_closed())
        self.assertFalse(producer.status()['dispatcherAlive'])
        self.assertEqual('CLOSED', producer.emit(event()))
        self.assertEqual('REJECTED', producer.emit({'message': 'secret'}))
        with self.assertRaises(ValueError):
            producer.await_closed(6)

    def test_emit_and_close_do_not_wait_for_delayed_transport_or_invent_termination(self):
        transport = Transport()
        transport.release.clear()
        producer = self.make(transport)
        original = event()
        producer.emit(original)
        self.assertTrue(transport.entered.wait(1))
        start = time.monotonic()
        self.assertEqual('DUPLICATE', producer.emit(original))
        self.assertEqual('REJECTED', producer.emit({**original, 'count': 2}))
        producer.emit(event())
        producer.close()
        self.assertLess(time.monotonic() - start, .2)
        self.assertFalse(producer.await_closed(0))
        self.assertEqual(2, producer.status()['pending'])
        self.assertGreaterEqual(producer.status()['unknown'], 1)
        transport.release.set()
        self.assertTrue(producer.await_closed())

    def test_actual_event_cap_and_byte_cap_are_explicit(self):
        transport = Transport()
        transport.release.clear()
        producer = self.make(transport)
        for _ in range(2048):
            self.assertEqual('ACCEPTED', producer.emit(event()))
        self.assertEqual('FULL', producer.emit(event()))
        self.assertEqual(2048, producer.status()['pending'])
        other = self.make(Transport())
        with patch('dkp_logging.producer.MAX_BYTES', 1):
            self.assertEqual('FULL', other.emit(event()))

    def test_unknown_reconciles_original_ids_before_any_replay(self):
        transport = Transport()
        transport.code = -1
        producer = self.make(transport)
        original = event()
        producer.emit(original)
        until(lambda: producer.status()['deliveryFailures'] > 0)
        self.assertEqual(1, producer.status()['unknown'])
        transport.code = 200
        until(lambda: producer.status()['committed'] == 1)
        self.assertEqual(['batches', 'receipts'], [route for route, _ in transport.calls])
        self.assertEqual([original['eventId']], json.loads(transport.calls[1][1])['eventIds'])

    def test_unknown_lookup_then_idempotent_replay_and_expired(self):
        for lookup in ('UNKNOWN', 'EXPIRED'):
            with self.subTest(lookup=lookup):
                transport = Transport()
                transport.bad = True
                producer = self.make(transport)
                producer.emit(event())
                until(lambda: producer.status()['deliveryFailures'] > 0)
                transport.bad, transport.lookup = False, lookup
                until(lambda: producer.status()['pending'] == 0)
                self.assertEqual(1, producer.status()['expired' if lookup == 'EXPIRED' else 'committed'])
                if lookup == 'UNKNOWN':
                    self.assertEqual(transport.calls[0][1], transport.calls[-1][1])

    def test_permanent_rejection_and_authority_blocking(self):
        for code in (400, 409, 413, 401, 403, 202):
            with self.subTest(code=code):
                transport = Transport()
                transport.code = code
                producer = self.make(transport)
                producer.emit(event())
                until(lambda: producer.status()['lastHttpStatus'] == code)
                status = producer.status()
                if code in (400, 409, 413):
                    self.assertEqual(1, status['rejected'])
                    self.assertEqual(0, status['pending'])
                else:
                    self.assertEqual(1, status['unknown'])
                    self.assertEqual(0, status['committed'])
                    self.assertEqual(code in (401, 403), status['blocked'])
                producer.close()
                self.assertTrue(producer.await_closed())

    def test_uncertain_permanent_rejection_retires_retry_preserves_uncertainty_and_allows_next_event(self):
        for code in (400, 409, 413):
            with self.subTest(code=code):
                original = event()

                class RejectionTransport(Transport):
                    def post(inner, route, data):
                        inner.calls.append((route, data))
                        if len(inner.calls) == 1:
                            raise OSError('UNCERTAIN_INITIAL_ATTEMPT')
                        if route == 'receipts':
                            return 200, receipts(json.loads(data)['eventIds'], 'UNKNOWN')
                        ids = [row['eventId'] for row in json.loads(data)['events']]
                        if original['eventId'] in ids:
                            return code, b'{}'
                        return 200, receipts(ids)

                transport = RejectionTransport()
                producer = self.make(transport)
                producer.emit(original)
                until(lambda: producer.status()['lastHttpStatus'] == code)
                self.assertEqual(0, producer.status()['pending'])
                self.assertEqual(0, producer.status()['unknown'])
                self.assertEqual(1, producer.status()['discardedUnknown'])
                self.assertEqual(1, producer.status()['rejected'])
                self.assertEqual(0, producer.status()['committed'])
                following = event()
                self.assertEqual('ACCEPTED', producer.emit(following))
                until(lambda: producer.status()['committed'] == 1)
                self.assertEqual(['batches', 'receipts', 'batches', 'batches'], [route for route, _ in transport.calls])
                self.assertEqual([following['eventId']], [row['eventId'] for row in json.loads(transport.calls[-1][1])['events']])
                self.assertEqual(1, producer.status()['discardedUnknown'])
                producer.close()
                self.assertTrue(producer.await_closed())


if __name__ == '__main__':
    unittest.main()
