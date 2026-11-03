import { LIMITS, isPrepared, bytes, encodeBatch, requireLog } from './wire.js';

/** One bounded in-memory queue; no I/O, timers or implicit persistence claims. */
export class LogQueue {
  #events = new Map();
  #size = 0;
  #closed = false;
  #committed = 0;
  #rejected = 0;
  #dropped = 0;
  #unknownDiscarded = 0;
  #expired = 0;
  admit(event, capture) {
    if (this.#closed) return 'CLOSED';
    if (!isPrepared(event) || typeof capture !== 'string' || capture.length < 1 || capture.length > 128) { this.#rejected++; return 'REJECTED'; }
    const existing = this.#events.get(event.id);
    if (existing) {
      if (existing.event.text === event.text && existing.capture === capture) return 'DUPLICATE';
      this.#rejected++; return 'REJECTED';
    }
    if (this.#events.size >= LIMITS.queueEvents || this.#size + event.size > LIMITS.queueBytes) { this.#dropped++; return 'DROPPED'; }
    this.#events.set(event.id, { event, capture, unknown: false }); this.#size += event.size;
    return 'ACCEPTED';
  }
  /** envelope is a trusted SDK encoder; it may add transient fields without retaining them. */
  batch(capture, envelope = encodeBatch, eligible = () => true) {
    const result = [];
    for (const row of this.#events.values()) {
      if (row.capture !== capture || !eligible(row.event)) continue;
      const next = [...result, row.event];
      if (next.length > LIMITS.batchEvents) break;
      let text;
      try { text = envelope(next); } catch { break; }
      requireLog(typeof text === 'string');
      if (bytes(text) > LIMITS.batchBytes) break;
      result.push(row.event);
    }
    return Object.freeze(result);
  }
  submitted(ids) { for (const id of ids) { const row = this.#events.get(id); if (row) row.unknown = true; } }
  snapshot(capture) { return Object.freeze([...this.#events.values()].filter(row => row.capture === capture).map(row => row.event)); }
  /** Only a transport's exact scoped valid receipt may call this method. */
  committed(ids, capture) {
    for (const id of ids) {
      const row = this.#events.get(id);
      if (row?.capture === capture) { this.#events.delete(id); this.#size -= row.event.size; this.#committed++; }
    }
  }
  /** Permanent response retires local retry; it does not prove earlier submitted data was absent. */
  reject(ids, capture) {
    for (const id of ids) {
      const row = this.#events.get(id);
      if (row?.capture !== capture) continue;
      this.#events.delete(id); this.#size -= row.event.size; this.#rejected++;
      if (row.unknown) this.#unknownDiscarded++;
    }
  }
  expired(ids, capture) {
    for (const id of ids) {
      const row = this.#events.get(id);
      if (row?.capture !== capture) continue;
      this.#events.delete(id); this.#size -= row.event.size; this.#expired++;
      if (row.unknown) this.#unknownDiscarded++;
    }
  }
  retire(capture) {
    for (const [id, row] of this.#events) {
      if (row.capture !== capture) continue;
      this.#events.delete(id); this.#size -= row.event.size;
      if (row.unknown) this.#unknownDiscarded++; else this.#dropped++;
    }
  }
  status() {
    return Object.freeze({ pending: this.#events.size, pendingBytes: this.#size, unknown: [...this.#events.values()].filter(row => row.unknown).length, committed: this.#committed, rejected: this.#rejected, dropped: this.#dropped, discardedUnknown: this.#unknownDiscarded, expired: this.#expired, closed: this.#closed });
  }
  close() { this.#closed = true; }
}
