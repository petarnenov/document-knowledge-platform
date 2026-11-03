import { createWire, bytes, encodeBatch, LIMITS, uuid, requireLog } from './wire.js';
import { LogQueue } from './queue.js';
import { closedObject, parseResponse, receiptStates, responseCheck, validTime } from './responses.js';

class RetiredContext extends Error {}

/** Session authority stays in the BFF. Event submission always uses nonblocking Beacon. */
export class BrowserLogProducer {
  #wire; #queue = new LogQueue(); #environment; #origin; #now;
  #capture = null; #csrf = null; #generation = 0; #active = null; #timer = null;
  #closed = false; #invalid = 0; #beaconRejected = 0; #requestFailures = 0; #expired = 0;
  #pagehide; #visibility;
  constructor(identity, vocabulary, { environment = globalThis, now = Date.now } = {}) {
    this.#wire = createWire(identity, vocabulary);
    const origin = environment.location?.origin;
    const url = new URL(origin);
    requireLog(url.protocol === 'https:' && url.origin === origin && typeof environment.navigator?.sendBeacon === 'function' && typeof environment.fetch === 'function' && typeof now === 'function');
    this.#environment = environment; this.#origin = origin; this.#now = now;
    this.#pagehide = () => this.flush();
    this.#visibility = () => { if (environment.document?.visibilityState === 'hidden') this.flush(); };
    environment.addEventListener?.('pagehide', this.#pagehide);
    environment.document?.addEventListener?.('visibilitychange', this.#visibility);
  }
  #live() {
    if (this.#capture && Date.parse(this.#capture.expiresAt) <= this.#now()) { this.#expired++; this.retire(); }
    return !this.#closed && this.#capture !== null;
  }
  #clear() {
    this.#generation++;
    if (this.#capture) this.#queue.retire(this.#capture.contextEpoch);
    this.#capture = null; this.#csrf = null;
    if (this.#timer !== null) { clearTimeout(this.#timer); this.#timer = null; }
  }
  retire() { this.#clear(); this.#active?.abort(); }
  async #request(path, body, csrf, controller) {
    const timer = setTimeout(() => controller.abort(), 5000);
    let reader, cancelRead;
    try {
      const response = await this.#environment.fetch(this.#origin + path, {
        method: 'POST', credentials: 'same-origin', redirect: 'error', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', ...(csrf === null ? {} : { 'X-CSRF-TOKEN': csrf }) },
        body: JSON.stringify(body), signal: controller.signal,
      });
      controller.signal.throwIfAborted();
      if (response.status === 401 || response.status === 403) { throw new RetiredContext('LOG_CONTEXT_RETIRED'); }
      responseCheck(response.status === 200 && /^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') ?? ''));
      const length = response.headers.get('content-length');
      responseCheck(length === null || (/^\d+$/.test(length) && Number(length) > 0 && Number(length) <= LIMITS.batchBytes));
      responseCheck(response.body !== null); reader = response.body.getReader();
      cancelRead = () => { void reader.cancel().catch(() => {}); };
      controller.signal.addEventListener('abort', cancelRead, { once: true });
      const chunks = []; let total = 0;
      for (;;) {
        const chunk = await reader.read(); controller.signal.throwIfAborted();
        if (chunk.done) break;
        responseCheck(chunk.value instanceof Uint8Array); total += chunk.value.byteLength; responseCheck(total <= LIMITS.batchBytes); chunks.push(chunk.value);
      }
      const result = new Uint8Array(total); let offset = 0;
      for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; }
      return result;
    } finally {
      clearTimeout(timer);
      if (cancelRead) controller.signal.removeEventListener('abort', cancelRead);
      controller.abort();
      if (reader) { void reader.cancel().catch(() => {}); reader.releaseLock(); }
    }
  }
  async connect(csrfToken) {
    if (this.#closed) return 'CLOSED';
    if (this.#active) return 'BUSY';
    if (typeof csrfToken !== 'string' || csrfToken.length === 0 || csrfToken.length > 512 || /[\r\n\0]/.test(csrfToken)) { this.#invalid++; return 'REJECTED'; }
    this.#live();
    if (this.#csrf !== null && this.#csrf !== csrfToken) this.#clear();
    const generation = this.#generation, controller = new AbortController(); this.#active = controller;
    try {
      const execution = this.#wire.execution;
      const raw = await this.#request('/api/v1/dev-logs/context', { buildId: execution.buildId, runId: execution.runId }, csrfToken, controller);
      if (generation !== this.#generation || this.#closed) return 'RETIRED';
      const context = parseResponse(raw); closedObject(context, ['contextEpoch', 'buildId', 'runId', 'expiresAt']);
      responseCheck(uuid(context.contextEpoch) && context.buildId === execution.buildId && context.runId === execution.runId && validTime(context.expiresAt));
      responseCheck(Date.parse(context.expiresAt) > this.#now() && Date.parse(context.expiresAt) <= this.#now() + 300000);
      if (this.#capture && this.#capture.contextEpoch !== context.contextEpoch) this.#clear();
      this.#capture = Object.freeze({ ...context }); this.#csrf = csrfToken;
      return 'READY';
    } catch (error) { if (error instanceof RetiredContext && generation === this.#generation) this.#clear(); if (generation === this.#generation && !this.#closed) this.#requestFailures++; return generation === this.#generation ? 'UNKNOWN' : 'RETIRED'; }
    finally { if (this.#active === controller) this.#active = null; }
  }
  emit(input) {
    if (this.#closed) return 'CLOSED';
    if (!this.#live()) { this.#invalid++; return 'NO_CONTEXT'; }
    let event;
    try { event = this.#wire.prepare(input); } catch { this.#invalid++; return 'REJECTED'; }
    const result = this.#queue.admit(event, this.#capture.contextEpoch);
    if (result === 'ACCEPTED') this.#schedule(0);
    return result;
  }
  #schedule(delay = 1000) {
    if (this.#closed || !this.#capture || this.#timer !== null || this.#queue.status().pending === 0) return;
    this.#timer = setTimeout(() => {
      this.#timer = null; this.flush();
      void this.reconcile().finally(() => this.#schedule());
    }, delay);
  }
  flush() {
    if (!this.#live()) return 'NO_CONTEXT';
    if (this.#timer !== null) { clearTimeout(this.#timer); this.#timer = null; }
    const capture = this.#capture, csrf = this.#csrf;
    const envelope = events => JSON.stringify({ csrfToken: csrf, contextEpoch: capture.contextEpoch, captureClass: 'CURRENT_CONTEXT', batch: JSON.parse(encodeBatch(events)) });
    const events = this.#queue.batch(capture.contextEpoch, envelope);
    if (events.length === 0) return 'EMPTY';
    const text = envelope(events); requireLog(bytes(text) <= LIMITS.batchBytes);
    let accepted = false;
    try { accepted = this.#environment.navigator.sendBeacon(this.#origin + '/api/v1/dev-logs/beacon', new Blob([text], { type: 'application/json' })) === true; } catch { /* Visible rejection; no arbitrary console/exception text. */ }
    this.#schedule();
    if (accepted) { this.#queue.submitted(events.map(event => event.id)); return 'UNKNOWN'; }
    this.#beaconRejected++; return 'REJECTED';
  }
  async reconcile() {
    if (!this.#live()) return 'NO_CONTEXT';
    if (this.#active) return 'BUSY';
    const capture = this.#capture, generation = this.#generation;
    const events = this.#queue.batch(capture.contextEpoch); if (events.length === 0) return 'EMPTY';
    const ids = events.map(event => event.id), controller = new AbortController(); this.#active = controller;
    try {
      const raw = await this.#request('/api/v1/dev-logs/beacon/receipts', { csrfToken: this.#csrf, contextEpoch: capture.contextEpoch, buildId: capture.buildId, runId: capture.runId, captureClass: 'CURRENT_CONTEXT', eventIds: ids }, null, controller);
      if (generation !== this.#generation || !this.#live()) return 'RETIRED';
      const states = receiptStates(raw, ids);
      this.#queue.committed([...states].filter(([, state]) => state === 'COMMITTED').map(([id]) => id), capture.contextEpoch);
      this.#queue.expired([...states].filter(([, state]) => state === 'EXPIRED').map(([id]) => id), capture.contextEpoch);
      // EXPIRED is a scope-bound server witness; it still does not prove historical absence.
      return [...states.values()].every(state => state === 'COMMITTED') ? 'COMMITTED' : 'UNKNOWN';
    } catch (error) { if (error instanceof RetiredContext && generation === this.#generation) this.#clear(); if (generation === this.#generation && !this.#closed) this.#requestFailures++; return generation === this.#generation ? 'UNKNOWN' : 'RETIRED'; }
    finally { if (this.#active === controller) this.#active = null; }
  }
  status() { const ready = this.#live(); return Object.freeze({ ...this.#queue.status(), invalid: this.#invalid, beaconRejected: this.#beaconRejected, requestFailures: this.#requestFailures, captureExpired: this.#expired, contextReady: ready, requestActive: this.#active !== null }); }
  close() {
    if (this.#closed) return;
    this.#closed = true; this.retire(); this.#queue.close();
    this.#environment.removeEventListener?.('pagehide', this.#pagehide);
    this.#environment.document?.removeEventListener?.('visibilitychange', this.#visibility);
  }
}
