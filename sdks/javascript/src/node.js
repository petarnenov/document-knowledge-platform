import { createWire, encodeBatch } from './wire.js';
import { LogQueue } from './queue.js';
import { receiptStates } from './responses.js';
import { NodeHttpsTransport } from './node-https.js';

/** One bounded asynchronous dispatcher. Registration and durable-spool ownership stay outside. */
export class NodeLogProducer {
  #wire; #transport; #queue = new LogQueue(); #capture; #timer = null;
  #dispatching = false; #closed = false; #invalid = 0; #failures = 0; #retry = 250;
  #lastStatus = 0; #blocked = false; #closedPromise; #resolveClosed;
  constructor(identity, vocabulary, configuration) {
    this.#wire = createWire(identity, vocabulary);
    this.#transport = new NodeHttpsTransport(configuration);
    this.#capture = this.#transport.credentialFingerprint();
    this.#closedPromise = new Promise(resolve => { this.#resolveClosed = resolve; });
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
  async #dispatch() {
    if (this.#closed || this.#dispatching || this.#blocked) return;
    this.#dispatching = true;
    try {
      let events = this.#queue.batch(this.#capture);
      if (events.length === 0) return;
      if (this.#queue.status().unknown > 0) {
        this.#accept(await this.#transport.post('receipts', new TextEncoder().encode(JSON.stringify({ eventIds: events.map(event => event.id) }))), events.map(event => event.id), false);
        if (this.#closed) return;
        events = this.#queue.batch(this.#capture);
      }
      if (events.length === 0 || this.#closed) return;
      const ids = events.map(event => event.id), body = new TextEncoder().encode(encodeBatch(events));
      this.#queue.submitted(ids);
      const response = await this.#transport.post('batches', body);
      // A response observed before closing is an actual commit witness even when close races dispatch.
      this.#accept(response, ids, true);
      this.#retry = 250;
    } catch { this.#failures++; this.#retry = Math.min(2000, this.#retry * 2); }
    finally {
      this.#dispatching = false;
      if (this.#closed) this.#resolveClosed(); else this.#schedule(this.#retry);
    }
  }
  status() { return Object.freeze({ ...this.#queue.status(), invalid: this.#invalid, deliveryFailures: this.#failures, requestActive: this.#dispatching, blocked: this.#blocked, lastHttpStatus: this.#lastStatus }); }
  close() {
    if (this.#closed) return;
    this.#closed = true; this.#queue.close();
    if (this.#timer !== null) { clearTimeout(this.#timer); this.#timer = null; }
    this.#transport.close();
    if (!this.#dispatching) this.#resolveClosed();
  }
  whenClosed() { return this.#closedPromise; }
}
