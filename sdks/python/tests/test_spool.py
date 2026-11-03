import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from dkp_logging.producer import LogProducer
from dkp_logging.spool import FileLogSpool
from dkp_logging.wire import Wire, Prepared, encode, queue_binding
from test_producer import Transport, until
from test_wire import identity, vocabulary, event


FINGERPRINT = hashlib.sha256(b'fixture-producer-credential').hexdigest()


class BoundTransport(Transport):
    def credential_fingerprint(self):
        return FINGERPRINT


class SpoolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.identity = identity()
        self.wire = Wire(self.identity, vocabulary())

    def spool(self, name='queue', binding=None):
        spool = FileLogSpool(self.root / name, binding or self.identity, FINGERPRINT)
        self.addCleanup(spool.close)
        return spool

    def producer(self, transport, spool):
        producer = LogProducer(self.identity, vocabulary(), transport, spool)
        self.addCleanup(lambda: (transport.release.set(), producer.close(), producer.await_closed()))
        return producer

    def test_actual_private_atomic_roundtrip_original_bytes_and_lifetime_lock(self):
        spool = self.spool()
        self.assertEqual([], spool.load())
        prepared = self.wire.prepare(event())
        spool.replace([prepared])
        self.assertEqual([prepared], spool.load())
        self.assertEqual(0o700, spool.directory.stat().st_mode & 0o777)
        self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in spool.directory.iterdir()))
        with self.assertRaises(OSError):
            self.spool()
        spool.close()
        restored = self.spool()
        self.assertEqual([prepared], restored.load())
        restored.replace([])
        self.assertEqual([], restored.load())
        restored.close()
        with self.assertRaises(OSError):
            restored.load()

    def test_binding_corruption_unknown_files_permissions_and_symlinks_refused_without_erasure(self):
        spool = self.spool()
        spool.load()
        spool.replace([self.wire.prepare(event())])
        path = spool.directory / 'pending.bin'
        good = path.read_bytes()
        spool.close()
        wrong = self.spool(binding=identity())
        with self.assertRaises(OSError):
            wrong.load()
        wrong.close()
        for corrupt in [b'', b'invalid', good[:-1], good + b'extra']:
            path.write_bytes(corrupt)
            refused = self.spool()
            with self.assertRaises(OSError):
                refused.load()
            refused.close()
            self.assertEqual(corrupt, path.read_bytes())
        path.write_bytes(good)
        path.chmod(0o644)
        with self.assertRaises(OSError):
            self.spool()
        path.chmod(0o600)
        foreign = spool.directory / 'foreign'
        foreign.write_bytes(b'preserve')
        with self.assertRaises(OSError):
            self.spool()
        foreign.unlink()
        path.unlink()
        path.symlink_to(self.root / 'missing')
        with self.assertRaises(OSError):
            self.spool()
        self.assertTrue(path.is_symlink())
        path.unlink()
        (self.root / 'alias').symlink_to(spool.directory, target_is_directory=True)
        with self.assertRaises(OSError):
            FileLogSpool(self.root / 'alias', self.identity, FINGERPRINT)
        spool.directory.chmod(0o755)
        with self.assertRaises(OSError):
            self.spool()

    def test_staging_interruption_requires_verified_stable_snapshot_and_retains_previous_good_data(self):
        spool = self.spool()
        with self.assertRaises(OSError):
            spool.replace([])
        spool.load()
        prepared = self.wire.prepare(event())
        spool.replace([prepared])
        good = (spool.directory / 'pending.bin').read_bytes()
        with patch('dkp_logging.spool.os.replace', side_effect=OSError('FIXTURE')):
            with self.assertRaises(OSError):
                spool.replace([])
        self.assertEqual(good, (spool.directory / 'pending.bin').read_bytes())
        spool.close()
        recovered = self.spool()
        self.assertEqual([prepared], recovered.load())
        recovered.replace([])
        recovered.close()
        (recovered.directory / 'pending.next').write_bytes(b'unconfirmed')
        (recovered.directory / 'pending.next').chmod(0o600)
        (recovered.directory / 'pending.bin').unlink()
        with self.assertRaises(OSError):
            self.spool()
        self.assertTrue((recovered.directory / 'pending.next').exists())

    def test_bounds_duplicate_refusal_and_typed_restore_revalidation(self):
        spool = self.spool()
        spool.load()
        prepared = self.wire.prepare(event())
        for bad in [[prepared] * 2, [prepared] * 2049, [Prepared(prepared.event_id, b'x' * 8193)],
                [Prepared('bad', b'x')], [Prepared(prepared.event_id, b'')]]:
            with self.assertRaises(OSError):
                spool.replace(bad)
        spool.replace([prepared])
        with patch('dkp_logging.spool.DISK_BYTES', 1):
            with self.assertRaises(OSError):
                spool.replace([])
        self.assertEqual(prepared, self.wire.restore(prepared.data))
        row = json.loads(prepared.data)
        row['context']['attributes']['count'] = True
        for bad in [b'{}', prepared.data + b'{}', encode(row), b'x' * 8193]:
            with self.assertRaises(ValueError):
                self.wire.restore(bad)
        other = Wire(identity(), vocabulary())
        with self.assertRaises(ValueError):
            other.restore(prepared.data)
        traced = self.wire.prepare({**event(), 'traceId': 'a' * 32, 'spanId': 'b' * 16})
        self.assertEqual(traced, self.wire.restore(traced.data))

    def test_two_producer_instances_restart_original_ids_and_reconcile_before_replay(self):
        spool = self.spool()
        transport = BoundTransport()
        transport.code = -1
        producer = self.producer(transport, spool)
        original = event()
        producer.emit(original)
        until(lambda: producer.status()['unknown'] == 1 and producer.status()['spooled'] == 1)
        producer.close()
        self.assertTrue(producer.await_closed())
        saved = transport.calls[0][1]
        replacement_transport = BoundTransport()
        replacement_transport.lookup = 'UNKNOWN'
        replacement = self.producer(replacement_transport, self.spool())
        until(lambda: replacement.status()['committed'] == 1)
        self.assertEqual(['receipts', 'batches'], [route for route, _ in replacement_transport.calls])
        self.assertEqual(saved, replacement_transport.calls[1][1])
        self.assertEqual([original['eventId']], json.loads(replacement_transport.calls[0][1])['eventIds'])
        until(lambda: self.spool_snapshot_empty(replacement._spool))

    def test_reordered_same_execution_fields_reopen_file_and_recover_producer(self):
        reordered = dict(reversed(list(self.identity.items())))
        self.assertEqual(self.identity, reordered)
        original = self.wire.prepare(event())
        for mode in ('file', 'producer'):
            with self.subTest(mode=mode):
                name = 'reordered-' + mode
                spool = self.spool(name)
                spool.load()
                spool.replace([original])
                spool.close()
                if mode == 'file':
                    reopened = self.spool(name, reordered)
                    self.assertEqual([original], reopened.load())
                else:
                    reopened = self.spool(name)
                    transport = BoundTransport()
                    transport.lookup = 'UNKNOWN'
                    producer = LogProducer(reordered, vocabulary(), transport, reopened)
                    self.addCleanup(lambda: (producer.close(), producer.await_closed()))
                    until(lambda: producer.status()['committed'] == 1)
                    self.assertEqual(['receipts', 'batches'], [route for route, _ in transport.calls])
                    self.assertEqual(b'{"schemaVersion":1,"events":[' + original.data + b']}', transport.calls[1][1])

    @staticmethod
    def spool_snapshot_empty(spool):
        return spool.load() == []

    def test_committed_before_cleanup_failure_reconciles_without_second_batch(self):
        spool = self.spool()
        original_replace = spool.replace

        def fail_cleanup(events):
            if not events:
                raise OSError('FIXTURE_CLEANUP')
            original_replace(events)

        spool.replace = fail_cleanup
        transport = BoundTransport()
        producer = self.producer(transport, spool)
        producer.emit(event())
        until(lambda: producer.status()['committed'] == 1 and producer.status()['spoolDegraded'])
        producer.close()
        self.assertTrue(producer.await_closed())
        replacement_transport = BoundTransport()
        replacement = self.producer(replacement_transport, self.spool())
        until(lambda: replacement.status()['committed'] == 1)
        self.assertEqual(['receipts'], [route for route, _ in replacement_transport.calls])

    def test_delayed_or_failed_storage_keeps_emit_close_nonblocking_and_lock_until_worker_exit(self):
        spool = self.spool()
        entered, release = threading.Event(), threading.Event()
        original_replace = spool.replace

        def delayed(events):
            entered.set()
            if not release.wait(3):
                raise OSError('FIXTURE_STORAGE_WAIT')
            original_replace(events)

        spool.replace = delayed
        transport = BoundTransport()
        producer = self.producer(transport, spool)
        self.addCleanup(release.set)
        producer.emit(event())
        self.assertTrue(entered.wait(1))
        self.assertEqual('ACCEPTED', producer.emit(event()))
        self.assertEqual(2, producer.status()['unspooled'])
        producer.close()
        self.assertFalse(producer.await_closed(0))
        with self.assertRaises(OSError):
            self.spool()
        release.set()
        self.assertTrue(producer.await_closed())
        self.assertEqual([], transport.calls)
        self.assertEqual(1, producer.status()['unspooled'])
        reopened = self.spool()
        self.assertEqual(1, len(reopened.load()))

    def test_invalid_recovered_event_or_credential_never_reaches_http(self):
        for name, binding, events in [('wrong-binding', queue_binding(identity(), FINGERPRINT), []),
                ('unsafe-event', queue_binding(self.identity, FINGERPRINT), [Prepared(event()['eventId'], b'{}')])]:
            class InvalidSpool:
                closed = False

                def load(inner):
                    return events

                def close(inner):
                    inner.closed = True

            spool = InvalidSpool()
            spool.binding = binding
            transport = BoundTransport()
            with self.subTest(name=name), self.assertRaises(ValueError):
                LogProducer(self.identity, vocabulary(), transport, spool)
            self.assertTrue(spool.closed)
            self.assertEqual([], transport.calls)


if __name__ == '__main__':
    unittest.main()
