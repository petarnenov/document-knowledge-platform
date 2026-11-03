import { createWire, encodeBatch } from './wire.js';
import { LogQueue } from './queue.js';
import { receiptStates } from './responses.js';
import { NodeHttpsTransport } from './node-https.js';

const spoolOwners = new WeakSet();

/** One bounded asynchronous dispatcher. Registration and durable-spool ownership stay outside. */
export class NodeLogProducer {
  #wire; #transport; #queue = new LogQueue(); #capture; #timer = null;
  #dispatching = false; #closed = false; #invalid = 0; #failures = 0; #retry = 250;
  #lastStatus = 0; #blocked = false; #closedPromise; #resolveClosed;
  #spool; #spooled = new Map(); #degraded = false; #leaseReleased = true;
  #settled = false; #finishing = false; #posting = false;
  constructor(identity, vocabulary, configuration, spool = null) {
    this.#wire = createWire(identity, vocabulary);
    this.#transport = new NodeHttpsTransport(configuration);
    this.#capture = this.#transport.credentialFingerprint();
    this.#closedPromise = new Promise(resolve => { this.#resolveClosed = resolve; });
    this.#spool = spool;
    if (spool !== null) {
      try {
        if (spoolOwners.has(spool)) throw new Error();
        const texts = spool.load(this.#wire.execution, this.#capture);
        if (!Array.isArray(texts) || texts.length > 2048) throw new Error();
        const events = texts.map(text => this.#wire.restore(text));
        if (new Set(events.map(event => event.id)).size !== events.length) throw new Error();
        for (const event of events) {
          if (this.#queue.admit(event, this.#capture) !== 'ACCEPTED') throw new Error();
          this.#spooled.set(event.id, event.text);
        }
        this.#queue.submitted(events.map(event => event.id));
        spoolOwners.add(spool);
        this.#leaseReleased = false;
        this.#schedule(0);
      } catch { this.#transport.close(); throw new Error('LOG_SPOOL_UNAVAILABLE'); }
    }
  }
  emit(input) {
    if (this.#closed) return 'CLOSED';
    let event;
    try { event = this.#wire.prepare(input); } catch { this.#invalid++; return 'REJECTED'; }
    const result = this.#queue.admit(event, this.#capture);
    if (result === 'ACCEPTED') this.#schedule(0);
    return result;
  }
  #schedule(delay) {
    if (this.#closed || this.#blocked || this.#dispatching || this.#timer !== null || this.#queue.status().pending === 0) return;
    this.#timer = setTimeout(() => { this.#timer = null; void this.#dispatch(); }, delay);
    // Diagnostic timers must not keep an otherwise finished application alive; not a flush guarantee.
    this.#timer.unref();
  }
  #accept(response, ids, commitOnly) {
    this.#lastStatus = response.status;
    if ([400,409,413].includes(response.status)) {
      this.#queue.reject(ids, this.#capture);
      return new Map(ids.map(id => [id, 'REJECTED']));
    }
    if (response.status !== 200) { if ([401,403].includes(response.status)) this.#blocked = true; throw new Error('LOG_HTTP_UNAVAILABLE'); }
    const states = receiptStates(response.body, ids, commitOnly);
    this.#queue.committed([...states].filter(([,state]) => state === 'COMMITTED').map(([id]) => id), this.#capture);
    this.#queue.expired([...states].filter(([,state]) => state === 'EXPIRED').map(([id]) => id), this.#capture);
    return states;
  }
  async #persist() {
    if (this.#spool === null) return true;
    const events = this.#queue.snapshot(this.#capture);
    try {
      await this.#spool.replace(events.map(event => event.text));
      this.#spooled = new Map(events.map(event => [event.id, event.text]));
      this.#degraded = false; return true;
    } catch { this.#degraded = true; return false; }
  }
  async #post(route, body) {
    this.#posting = true;
    try { return await this.#transport.post(route, body); }
    finally { this.#posting = false; }
  }
  async #finish() {
    if (this.#finishing) return;
    this.#finishing = true;
    if (this.#spool !== null) {
      try { await this.#spool.close(); this.#leaseReleased = true; }
      catch { this.#degraded = true; }
    }
    this.#settled = true; this.#resolveClosed();
  }
  #batch() {
    return this.#queue.batch(this.#capture, encodeBatch,
      event => this.#spool === null || this.#spooled.get(event.id) === event.text);
  }
  async #dispatch() {
    if (this.#closed || this.#dispatching || this.#blocked) return;
    this.#dispatching = true;
    try {
      if (!await this.#persist() || this.#closed) return;
      let events = this.#batch();
      if (events.length === 0) return;
      if (this.#queue.status().unknown > 0) {
        this.#accept(await this.#post('receipts', new TextEncoder().encode(JSON.stringify({ eventIds: events.map(event => event.id) }))), events.map(event => event.id), false);
        if (this.#closed) return;
        events = this.#batch();
      }
      if (events.length === 0 || this.#closed) return;
      const ids = events.map(event => event.id), body = new TextEncoder().encode(encodeBatch(events));
      this.#queue.submitted(ids);
      const response = await this.#post('batches', body);
      // A response observed before closing is an actual commit witness even when close races dispatch.
      this.#accept(response, ids, true);
      this.#retry = 250;
    } catch { this.#failures++; this.#retry = Math.min(2000, this.#retry * 2); }
    finally {
      await this.#persist();
      this.#dispatching = false;
      if (this.#closed) await this.#finish(); else this.#schedule(this.#retry);
    }
  }
  status() {
    const status = this.#queue.status(), events = this.#queue.snapshot(this.#capture);
    const spooled = events.filter(event => this.#spooled.get(event.id) === event.text).length;
    return Object.freeze({ ...status, invalid:this.#invalid, deliveryFailures:this.#failures,
      requestActive:this.#posting, dispatcherAlive:!this.#settled, blocked:this.#blocked, lastHttpStatus:this.#lastStatus,
      spooled, unspooled:status.pending - spooled, spoolDegraded:this.#degraded, leaseReleased:this.#leaseReleased });
  }
  close() {
    if (this.#closed) return;
    this.#closed = true; this.#queue.close();
    if (this.#timer !== null) { clearTimeout(this.#timer); this.#timer = null; }
    this.#transport.close();
    if (!this.#dispatching) void this.#finish();
  }
  whenClosed() { return this.#closedPromise; }
}
