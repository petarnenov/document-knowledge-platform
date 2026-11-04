"""One bounded asynchronous dispatcher. Business callers never perform I/O."""
import threading

from .wire import Wire, encode, encode_batch, receipt_states, queue_binding, parse_response

MAX_EVENTS, MAX_BYTES = 2048, 8 * 1024 * 1024


class LogProducer:
    def __init__(self, identity, vocabulary, transport, spool=None):
        self._wire = Wire(identity, vocabulary)
        self._transport = transport
        self._spool = spool
        self._generation = 0
        self._spool_dirty = self._spool_degraded = False
        self._condition = threading.Condition()
        self._pending = {}
        self._bytes = self._committed = self._rejected = self._dropped = self._expired = 0
        self._failures = self._last_status = 0
        self._discarded_unknown = 0
        self._closed = self._blocked = self._active = False
        try:
            if spool is not None:
                if spool.binding != queue_binding(self._wire._identity, transport.credential_fingerprint()):
                    raise ValueError('INVALID_LOG_SPOOL_BINDING')
                retained = spool.load()
                if type(retained) is not list or len(retained) > MAX_EVENTS:
                    raise ValueError('INVALID_LOG_SPOOL_STATE')
                for stored in retained:
                    restored = self._wire.restore(stored.data)
                    if (restored.event_id != stored.event_id or restored.event_id in self._pending
                            or self._bytes + len(restored.data) > MAX_BYTES):
                        raise ValueError('INVALID_LOG_SPOOL_STATE')
                    self._pending[restored.event_id] = [restored, True, True]
                    self._bytes += len(restored.data)
        except Exception:
            try:
                if spool is not None:
                    spool.close()
            finally:
                transport.close()
            raise ValueError('INVALID_LOG_SPOOL_STATE') from None
        self._worker = threading.Thread(target=self._dispatch, name='dkp-log-dispatch', daemon=True)
        self._worker.start()

    def emit(self, event):
        try:
            prepared = self._wire.prepare(event)
        except (ValueError, TypeError, OverflowError):
            with self._condition:
                self._rejected += 1
            return 'REJECTED'
        with self._condition:
            if self._closed:
                self._dropped += 1
                return 'CLOSED'
            prior = self._pending.get(prepared.event_id)
            if prior is not None:
                if prior[0].data == prepared.data or parse_response(prior[0].data) == parse_response(prepared.data):
                    return 'DUPLICATE'
                self._rejected += 1
                return 'REJECTED'
            if len(self._pending) >= MAX_EVENTS or self._bytes + len(prepared.data) > MAX_BYTES:
                self._dropped += 1
                return 'FULL'
            self._pending[prepared.event_id] = [prepared, False, self._spool is None]
            self._bytes += len(prepared.data)
            self._generation += 1
            self._spool_dirty = self._spool is not None
            self._condition.notify_all()
            return 'ACCEPTED'

    def status(self):
        with self._condition:
            return dict(pending=len(self._pending), pendingBytes=self._bytes,
                unknown=sum(item[1] for item in self._pending.values()), committed=self._committed,
                rejected=self._rejected, dropped=self._dropped, expired=self._expired,
                discardedUnknown=self._discarded_unknown,
                deliveryFailures=self._failures, blocked=self._blocked, lastHttpStatus=self._last_status,
                closed=self._closed, requestActive=self._active, dispatcherAlive=self._worker.is_alive(),
                spooled=sum(item[2] for item in self._pending.values()) if self._spool is not None else 0,
                unspooled=sum(not item[2] for item in self._pending.values()) if self._spool is not None else len(self._pending),
                spoolDegraded=self._spool_degraded)

    def _batch(self):
        with self._condition:
            selected, size = [], 32
            for item in self._pending.values():
                if not item[2]:
                    continue
                if len(selected) == 32 or size + len(item[0].data) + 1 > 32768:
                    break
                selected.append(item)
                size += len(item[0].data) + 1
            return selected

    def _remove(self, event_id):
        self._bytes -= len(self._pending.pop(event_id)[0].data)
        self._generation += 1
        self._spool_dirty = self._spool is not None

    def _persist(self):
        with self._condition:
            if not self._spool_dirty:
                return True
            snapshot = [item[0] for item in self._pending.values()]
            generation = self._generation
        try:
            self._spool.replace(snapshot)
            with self._condition:
                for event in snapshot:
                    retained = self._pending.get(event.event_id)
                    if retained is not None:
                        retained[2] = True
                if generation == self._generation:
                    self._spool_dirty = False
                self._spool_degraded = False
            return True
        except Exception:
            with self._condition:
                self._spool_degraded = True
            return False

    def _accept(self, response, selected, commit_only, previous_unknown):
        status, body = response
        with self._condition:
            self._last_status = status
            if status in (401, 403):
                self._blocked = True
            if status in (400, 409, 413):
                for item in selected:
                    event_id = item[0].event_id
                    # Retire automatic retry, preserving earlier persistence uncertainty.
                    # A rejected current request does not prove the old attempt rolled back.
                    self._discarded_unknown += int(event_id in previous_unknown)
                    self._remove(event_id)
                    self._rejected += 1
                return
        if status != 200:
            raise OSError('LOG_HTTP_UNAVAILABLE')
        states = receipt_states(body, [item[0].event_id for item in selected], commit_only)
        with self._condition:
            for event_id, state in states.items():
                if state == 'COMMITTED':
                    self._remove(event_id)
                    self._committed += 1
                elif state == 'EXPIRED':
                    self._remove(event_id)
                    self._expired += 1

    def _dispatch(self):
        try:
            while True:
                with self._condition:
                    self._condition.wait_for(lambda: self._closed or self._spool_dirty or (self._pending and not self._blocked))
                    if self._closed:
                        break
                    self._active = True
                try:
                    if not self._persist():
                        continue
                    with self._condition:
                        if self._closed:
                            break
                        if self._blocked:
                            continue
                    selected = self._batch()
                    if any(item[1] for item in selected):
                        ids = [item[0].event_id for item in selected]
                        response = self._transport.post('receipts', encode({'eventIds': ids}))
                        self._accept(response, selected, False, set(ids))
                    selected = self._batch()
                    with self._condition:
                        if self._closed:
                            break
                        prior = {item[0].event_id for item in selected if item[1]}
                        for item in selected:
                            item[1] = True
                    if selected:
                        response = self._transport.post('batches', encode_batch([item[0] for item in selected]))
                        self._accept(response, selected, True, prior)
                except Exception:
                    # No exception messages or recursive diagnostic emission.
                    with self._condition:
                        self._failures += 1
                finally:
                    with self._condition:
                        self._active = False
                        # Storage/authority failure uses the same finite retry pause.
                        # Keep it in finally so a failed persist cannot busy-spin.
                        if not self._closed and (self._pending or self._spool_dirty):
                            self._condition.wait(timeout=1)
        finally:
            try:
                self._transport.close()
            finally:
                if self._spool is not None:
                    self._spool.close()

    def close(self):
        with self._condition:
            self._closed = True
            self._condition.notify_all()
        self._transport.close()

    def await_closed(self, timeout=5):
        if type(timeout) not in (int, float) or not 0 <= timeout <= 5:
            raise ValueError('INVALID_LOG_CLOSE_WAIT')
        self._worker.join(timeout)
        return not self._worker.is_alive()
