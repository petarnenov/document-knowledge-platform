import { BrowserLogProducer } from '../../../sdks/javascript/src/browser.js';
import type { Admission, BrowserStatus, ExecutionIdentity, LifecycleInput, Vocabulary } from '../../../sdks/javascript/src/browser.js';

interface LogSession { issuer: string; subject: string; selectedTenant: string | null; csrfToken: string; }
interface Producer {
  connect(csrf: string): Promise<string>;
  emit(input: LifecycleInput): Admission | 'NO_CONTEXT';
  close(): void;
  status(): Pick<BrowserStatus, 'pending' | 'unknown' | 'committed' | 'dropped' | 'discardedUnknown' | 'contextReady'>;
}
type Factory = (identity: ExecutionIdentity, vocabulary: Vocabulary) => Producer;
type Phase = 'STARTUP' | 'CONSOLE' | 'ERROR' | 'PROMISE' | 'HTTP' | 'QUERY' | 'MUTATION' | 'ROUTE' | 'IDENTITY';
type State = 'UNCONFIGURED' | 'INVALID' | 'AWAITING_SESSION' | 'CONNECTING' | 'READY' | 'UNKNOWN' | 'CLOSED';

/** Browser composition only. Session authority and persistence remain server-owned. */
export class PortalLogging {
  #factory: Factory;
  #identity: ExecutionIdentity | undefined;
  #vocabulary: Vocabulary | undefined;
  #session: LogSession | undefined;
  #producer: Producer | undefined;
  #state: State = 'UNCONFIGURED';
  #started = false;
  #startup = false;
  #errors = 0;
  #retiredDiscarded = 0;
  #undo: (() => void)[] = [];

  constructor(factory: Factory = (identity, vocabulary) => new BrowserLogProducer(identity, vocabulary)) { this.#factory = factory; }

  start(document: Document = window.document, environment: Window & { console: Console } = window) {
    if (this.#started) return;
    this.#started = true;
    const raw = document.getElementById('dkp-log-build-manifest')?.textContent;
    if (!raw) return;
    try {
      if (new TextEncoder().encode(raw).length > 1024 * 1024) throw new Error();
      const manifest = JSON.parse(raw) as Record<string, unknown>;
      if (manifest.schemaVersion !== 1 || manifest.component !== 'admin-portal') throw new Error();
      // The SDK validates closed original identity/catalog primitives; the BFF independently selects a configured original artifact.
      this.#identity = Object.freeze({ buildId: manifest.buildId, runId: crypto.randomUUID(), instanceId: crypto.randomUUID(),
        component: manifest.component, gitSha: manifest.gitSha, sourceFingerprint: manifest.sourceFingerprint }) as ExecutionIdentity;
      const vocabulary = manifest.vocabulary as Vocabulary;
      this.#vocabulary = { events: [...vocabulary.events], phases: [...vocabulary.phases] };
      const probe = this.#factory(this.#identity, this.#vocabulary); probe.close();
      this.#state = 'AWAITING_SESSION';
      this.#installHooks(environment);
      const timer = environment.setInterval(() => this.#connect(), 60_000);
      this.#undo.push(() => environment.clearInterval(timer));
    } catch {
      this.#identity = undefined; this.#vocabulary = undefined; this.#state = 'INVALID';
    }
  }

  session(value: LogSession) {
    if (!this.#identity || !this.#vocabulary || this.#state === 'CLOSED') return;
    const old = this.#session;
    if (old && old.issuer === value.issuer && old.subject === value.subject
      && old.selectedTenant === value.selectedTenant && old.csrfToken === value.csrfToken) return;
    this.retire();
    try {
      this.#producer = this.#factory(this.#identity, this.#vocabulary);
      this.#session = { issuer: value.issuer, subject: value.subject, selectedTenant: value.selectedTenant, csrfToken: value.csrfToken };
      this.#connect();
    } catch { this.#errors++; this.#state = 'UNKNOWN'; }
  }

  #connect() {
    const producer = this.#producer, session = this.#session;
    if (!producer || !session) return;
    this.#state = 'CONNECTING';
    void producer.connect(session.csrfToken).then(result => {
      if (this.#producer !== producer) return;
      this.#state = result === 'READY' ? 'READY' : 'UNKNOWN';
      if (result === 'READY') {
        if (!this.#startup) { this.#startup = true; this.record('STARTUP', 'process.started'); }
        this.record('IDENTITY', 'ui.identity.changed');
      }
    }).catch(() => { if (this.#producer === producer) { this.#errors++; this.#state = 'UNKNOWN'; } });
  }

  retire() {
    const old = this.#producer;
    // Remove authority before invoking any replaceable adapter code.
    this.#producer = undefined; this.#session = undefined;
    if (old) {
      // SDK pending already includes submitted rows with UNKNOWN commit state.
      try { const state = old.status(); this.#retiredDiscarded += state.pending; }
      catch { this.#errors++; }
      try { old.close(); } catch { this.#errors++; }
    }
    if (this.#identity && this.#state !== 'CLOSED') this.#state = 'AWAITING_SESSION';
  }

  record(phase: Phase, eventName: LifecycleInput['eventName'] = 'operation.succeeded', severity: LifecycleInput['severity'] = 'DEBUG',
    outcome: LifecycleInput['outcome'] = 'SUCCEEDED', count = 1) {
    if (!this.#producer) return;
    this.#emit(this.#producer, phase, eventName, severity, outcome, count, crypto.randomUUID(), 0);
  }

  /** A completion remains tied to the producer that captured its start. */
  begin(phase: 'HTTP' | 'QUERY' | 'MUTATION') {
    if (!this.#producer) return () => {};
    const producer = this.#producer, correlationId = crypto.randomUUID(), started = performance.now();
    this.#emit(producer, phase, 'operation.started', 'DEBUG', 'STARTED', 1, correlationId, 0);
    let settled = false;
    return (outcome: 'SUCCEEDED' | 'FAILED' | 'CANCELLED') => {
      if (settled) return;
      settled = true;
      if (this.#producer !== producer) return;
      this.#emit(producer, phase, outcome === 'SUCCEEDED' ? 'operation.succeeded' : outcome === 'FAILED' ? 'operation.failed' : 'operation.cancelled',
        outcome === 'FAILED' ? 'ERROR' : 'DEBUG', outcome, 1, correlationId, Math.max(0, Math.round(performance.now() - started)));
    };
  }

  #emit(producer: Producer | undefined, phase: Phase, eventName: LifecycleInput['eventName'], severity: LifecycleInput['severity'],
    outcome: LifecycleInput['outcome'], count: number, correlationId: string, durationMs: number) {
    if (!producer) return;
    try {
      const result = producer.emit({ eventId: crypto.randomUUID(), timestamp: new Date().toISOString(), severity, eventName,
        correlationId, phase, outcome, durationMs, attempt: 1, count, cleanupSettled: true });
      if (result !== 'ACCEPTED') this.#errors++;
    } catch { this.#errors++; }
  }

  status() {
    return { state: this.#state, failures: this.#errors, retiredDiscarded: this.#retiredDiscarded,
      delivery: this.#producer?.status() ?? null };
  }

  close() {
    this.retire(); this.#state = 'CLOSED';
    for (const undo of this.#undo.splice(0).reverse()) undo();
  }

  #installHooks(environment: Window & { console: Console }) {
    let recording = false;
    const console = environment.console;
    for (const name of ['log', 'debug', 'info', 'warn', 'error', 'trace'] as const) {
      const original = console[name];
      const wrapper = (...args: unknown[]) => {
        if (recording) return original.apply(console, args);
        recording = true;
        try {
          this.record('CONSOLE', name === 'error' ? 'operation.failed' : 'operation.succeeded',
            name === 'error' ? 'ERROR' : name === 'warn' ? 'WARN' : name === 'info' ? 'INFO' : 'DEBUG',
            name === 'error' ? 'FAILED' : 'SUCCEEDED', args.length);
          return original.apply(console, args);
        } finally { recording = false; }
      };
      console[name] = wrapper;
      this.#undo.push(() => { if (console[name] === wrapper) console[name] = original; });
    }
    const error = () => this.record('ERROR', 'operation.failed', 'ERROR', 'FAILED');
    const rejection = () => this.record('PROMISE', 'operation.failed', 'ERROR', 'FAILED');
    environment.addEventListener('error', error); environment.addEventListener('unhandledrejection', rejection);
    this.#undo.push(() => { environment.removeEventListener('error', error); environment.removeEventListener('unhandledrejection', rejection); });
  }
}

export const portalLogging = new PortalLogging();
