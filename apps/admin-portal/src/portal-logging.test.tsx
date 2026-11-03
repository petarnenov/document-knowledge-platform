import { afterEach, describe, expect, it, vi } from 'vitest';
import { PortalLogging } from './portal-logging';
import type { ExecutionIdentity, LifecycleInput, Vocabulary } from '../../../sdks/javascript/src/browser.js';

const manifest = { schemaVersion: 1, component: 'admin-portal', buildId: 'a137a5b3-975c-40f6-8be1-9bf03d98cc13',
  gitSha: 'a'.repeat(40), sourceFingerprint: 'b'.repeat(64), vocabulary: { events: ['process.started', 'operation.succeeded',
    'operation.failed', 'ui.identity.changed'], phases: ['STARTUP', 'CONSOLE', 'ERROR', 'PROMISE', 'IDENTITY'] } };
const session = { issuer: 'https://issuer.example', subject: 'private-actor', selectedTenant: 'private-tenant', csrfToken: 'private-csrf' };
const runtimes: PortalLogging[] = [];
afterEach(() => runtimes.splice(0).forEach(runtime => runtime.close()));
async function settle() { await Promise.resolve(); await Promise.resolve(); }
function fixture(raw: string | null = JSON.stringify(manifest)) {
  const document = window.document.implementation.createHTMLDocument();
  if (raw !== null) {
    const script = document.createElement('script'); script.id = 'dkp-log-build-manifest'; script.textContent = raw; document.head.append(script);
  }
  const console = { log: vi.fn(), debug: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn(), trace: vi.fn() };
  let tick = () => {};
  const environment = Object.assign(new EventTarget(), { console, setInterval: vi.fn((callback: () => void) => { tick = callback; return 1; }), clearInterval: vi.fn() });
  const producers: ReturnType<typeof createProducer>[] = [];
  const factory = vi.fn((identity: ExecutionIdentity, vocabulary: Vocabulary) => {
    expect(identity.component).toBe('admin-portal'); expect(vocabulary.events).toContain('process.started');
    const producer = createProducer(); producers.push(producer); return producer;
  });
  const runtime = new PortalLogging(factory); runtimes.push(runtime);
  const start = () => runtime.start(document, environment as unknown as Window & { console: Console });
  return { runtime, document, environment, console, producers, factory, start, tick: () => tick() };
}
function createProducer() {
  return { connect: vi.fn(async (csrf: string): Promise<string> => csrf.length > 0 ? 'READY' : 'UNKNOWN'),
    emit: vi.fn((event: LifecycleInput) => { expect(event.eventId).toMatch(/^[0-9a-f-]{36}$/); return 'ACCEPTED' as const; }), close: vi.fn(),
    status: vi.fn(() => ({ pending: 2, unknown: 1, committed: 4, dropped: 1, discardedUnknown: 0, contextReady: true })) };
}

describe('portal logging composition', () => {
  it('uses original build identity and one page run across session rotations; waits only inside logging', async () => {
    const f = fixture(); f.start(); f.start();
    expect(f.factory).toHaveBeenCalledTimes(1); expect(f.producers[0]!.close).toHaveBeenCalledOnce();
    f.runtime.session(session); expect(f.runtime.status().state).toBe('CONNECTING');
    await settle(); expect(f.runtime.status().state).toBe('READY');
    expect(f.producers[1]!.connect).toHaveBeenCalledWith('private-csrf');
    expect(f.producers[1]!.emit.mock.calls.map(([event]) => event.phase)).toEqual(['STARTUP', 'IDENTITY']);
    f.runtime.session({ ...session }); expect(f.factory).toHaveBeenCalledTimes(2);
    f.runtime.retire(); expect(f.producers[1]!.close).toHaveBeenCalledOnce();
    expect(f.runtime.status().retiredDiscarded).toBe(2);
    f.runtime.session({ ...session, selectedTenant: 'another', csrfToken: 'rotated' }); await settle();
    expect(f.factory.mock.calls[0]![0]).toEqual(f.factory.mock.calls[2]![0]);
    expect(f.factory.mock.calls[0]![0].buildId).toBe(manifest.buildId);
    expect(f.producers[2]!.emit.mock.calls.map(([event]) => event.phase)).toEqual(['IDENTITY']);
    expect(JSON.stringify(f.producers.flatMap(producer => producer.emit.mock.calls))).not.toMatch(/private-|rotated|another/);
    f.tick(); await settle(); expect(f.producers[2]!.connect).toHaveBeenCalledTimes(2);
  });
  it('never resumes a retired pending context', async () => {
    const f = fixture(); f.start();
    let finish: (value: string) => void = () => {};
    f.factory.mockImplementationOnce(() => { const producer = createProducer(); producer.connect.mockImplementation(() => new Promise(resolve => { finish = resolve; })); f.producers.push(producer); return producer; });
    f.runtime.session(session); f.runtime.retire(); finish('READY'); await settle();
    expect(f.runtime.status().state).toBe('AWAITING_SESSION'); expect(f.producers[1]!.emit).not.toHaveBeenCalled();
  });
  it('preserves console arguments and errors while recording only typed primitives; removes hooks', async () => {
    const f = fixture(); const originals = { ...f.console }; f.start(); f.runtime.session(session); await settle();
    for (const name of ['log', 'debug', 'info', 'warn', 'error', 'trace'] as const) {
      f.console[name]('private-message', { password: 'secret' });
      expect(originals[name]).toHaveBeenCalledWith('private-message', { password: 'secret' });
    }
    f.environment.dispatchEvent(new Event('error')); f.environment.dispatchEvent(new Event('unhandledrejection'));
    const events = f.producers[1]!.emit.mock.calls.map(([event]) => event);
    expect(events.filter(event => event.phase === 'CONSOLE')).toHaveLength(6);
    expect(events.filter(event => event.phase === 'ERROR' || event.phase === 'PROMISE')).toHaveLength(2);
    expect(JSON.stringify(events)).not.toMatch(/private-message|password|secret/);
    const count = events.length; f.runtime.close();
    expect(f.runtime.status().state).toBe('CLOSED'); expect(f.environment.clearInterval).toHaveBeenCalledWith(1);
    expect(f.console).toEqual(originals); f.environment.dispatchEvent(new Event('error'));
    f.runtime.session(session); f.runtime.record('HTTP'); expect(f.producers[1]!.emit).toHaveBeenCalledTimes(count);
  });
  it.each([null, '', '{', '{}', 'null', JSON.stringify({ ...manifest, component: 'other' }), 'x'.repeat(1024 * 1024 + 1)])(
    'fails closed without invented provenance %#', raw => {
      const f = fixture(raw); f.start(); f.runtime.session(session); f.tick();
      expect(f.runtime.status().state).toBe(raw ? 'INVALID' : 'UNCONFIGURED'); expect(f.factory).not.toHaveBeenCalled();
      expect(f.environment.setInterval).not.toHaveBeenCalled();
    });
  it('shows dependency rejection and exceptions without throwing into a business action', async () => {
    const f = fixture(); f.start(); f.runtime.session(session);
    f.producers[1]!.connect.mockResolvedValue('UNKNOWN'); await settle(); f.tick(); await settle();
    expect(f.runtime.status().state).toBe('UNKNOWN');
    f.producers[1]!.connect.mockRejectedValue(new Error('private-error')); f.tick(); await settle();
    f.producers[1]!.emit.mockImplementation(() => { throw new Error('secret'); }); f.runtime.record('QUERY');
    f.producers[1]!.status.mockImplementation(() => { throw new Error('secret'); });
    f.producers[1]!.close.mockImplementation(() => { throw new Error('secret'); });
    expect(() => f.runtime.retire()).not.toThrow(); expect(f.runtime.status().failures).toBe(4);
    f.factory.mockImplementationOnce(() => { throw new Error('private'); });
    expect(() => f.runtime.session(session)).not.toThrow(); expect(f.runtime.status().state).toBe('UNKNOWN');
  });
});
