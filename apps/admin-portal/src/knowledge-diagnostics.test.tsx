import { expect, it, vi } from 'vitest';
import {
  KnowledgeDiagnostics,
  KnowledgeBodyObservations,
  finiteKnowledgeCode,
  knowledgeRoute,
} from '../e2e-real/knowledge-diagnostics';
const id = 'aaaaaaaa-1111-4111-8111-111111111111';
const origin = 'https://localhost:20443/api/v1/kb';
const ui = {
  selection: 'absent',
  settings: 'absent',
  alert: 'unavailable',
} as const;
const encode = (value: unknown) =>
  new TextEncoder().encode(JSON.stringify(value));
it('classifies only closed KB route families without identifiers or query data', () => {
  for (const [path, family] of [
    ['/collections', 'collections'],
    [`/collections/${id}`, 'collection'],
    [`/collections/${id}/status`, 'status'],
    [`/collections/${id}/acl`, 'acl'],
    [`/collections/${id}/audience`, 'audience'],
    [`/collections/${id}/audience-evaluations`, 'audience-evaluation'],
    [`/collections/${id}/memberships`, 'members'],
    [`/collections/${id}/memberships/${id}`, 'member'],
    [`/documents/${id}/policy-snapshots`, 'snapshot'],
    [`/documents/${id}/policy-validations`, 'validation'],
  ]) {
    expect(knowledgeRoute('GET', `${origin}${path}?cursor=PRIVATE_QUERY`)).toBe(
      `GET:${family}`,
    );
  }
  expect(knowledgeRoute('POST', `${origin}/collections`)).toBe(
    'POST:collections',
  );
  expect(knowledgeRoute('PUT', `${origin}/collections/${id}/audience`)).toBe(
    'PUT:audience',
  );
  expect(knowledgeRoute('PATCH', `${origin}/collections/${id}`)).toBe(
    'PATCH:collection',
  );
  expect(knowledgeRoute('DELETE', `${origin}/collections/${id}`)).toBe(
    'DELETE:collection',
  );
  expect(knowledgeRoute('UNTRUSTED_METHOD', `${origin}/PRIVATE_PATH`)).toBe(
    'OTHER:unknown',
  );
  expect(knowledgeRoute('GET', `${origin}/collections/PRIVATE_ID`)).toBe(
    'GET:unknown',
  );
  for (const path of [
    'not a URL',
    'https://localhost/api/v1/dm/documents',
    'https://localhost/api/v1/kb-other',
  ])
    expect(knowledgeRoute('GET', path)).toBeNull();
});
it('retains only closed finite error codes and never arbitrary bodies or diagnostics', () => {
  expect(finiteKnowledgeCode(encode({ code: 'UNAVAILABLE' }))).toBe(
    'UNAVAILABLE',
  );
  expect(finiteKnowledgeCode(encode({ code: 'STALE_POLICY' }))).toBe(
    'STALE_POLICY',
  );
  expect(finiteKnowledgeCode(new Uint8Array(1025))).toBe('UNOBSERVED');
  for (const value of [
    { code: 'PRIVATE_SECRET' },
    { code: 'UNAVAILABLE', details: 'PRIVATE_BODY' },
    [],
    null,
    'PRIVATE_BODY',
  ])
    expect(finiteKnowledgeCode(encode(value))).toBe('UNRECOGNIZED');
  expect(finiteKnowledgeCode(new TextEncoder().encode('{'))).toBe(
    'UNRECOGNIZED',
  );
  expect(finiteKnowledgeCode(new Uint8Array([255]))).toBe('UNRECOGNIZED');
});
it('keeps exactly the32most recent events, phase and immutable safe snapshot', () => {
  const ring = new KnowledgeDiagnostics();
  expect(ring.failure(ui).phase).toBe('start');
  expect(
    ring.observe(
      'GET',
      'https://localhost/api/v1/session?PRIVATE_QUERY',
      200,
      1,
    ),
  ).toBeNull();
  ring.mark('reactivate-member');
  for (let i = 0; i < 40; i++)
    ring.observe(
      'GET',
      `${origin}/collections/${id}/memberships?cursor=PRIVATE_QUERY`,
      200,
      i + 0.4,
    );
  const last = ring.observe('GET', `${origin}/collections/${id}`, 503, 2000);
  expect(last?.code).toBe('UNOBSERVED');
  if (last) last.code = finiteKnowledgeCode(encode({ code: 'UNAVAILABLE' }));
  const snapshot = ring.failure(ui);
  expect(snapshot.recent).toHaveLength(32);
  expect(snapshot.recent[0]?.observedElapsedMs).toBe(9);
  expect(snapshot.phase).toBe('reactivate-member');
  expect(snapshot.recent.at(-1)).toEqual({
    route: 'GET:collection',
    status: 503,
    observedElapsedMs: 2000,
    code: 'UNAVAILABLE',
  });
  expect(JSON.stringify(snapshot)).not.toMatch(
    /PRIVATE|aaaaaaaa|cursor|https|authorization/i,
  );
  snapshot.recent[0]!.code = 'changed-snapshot';
  expect(ring.failure(ui).recent[0]?.code).toBe('NONE');
});
it('closes invalid status/timing to finite diagnostic categories', () => {
  const ring = new KnowledgeDiagnostics();
  for (const status of ['FAILED', NaN, 99, 600, 200.5] as const)
    expect(
      ring.observe('GET', `${origin}/collections`, status, -1),
    ).toMatchObject({
      status: 'FAILED',
      code: 'TRANSPORT_FAILURE',
      observedElapsedMs: null,
    });
  for (const time of [null, NaN, Infinity])
    expect(
      ring.observe('GET', `${origin}/collections`, 200, time)
        ?.observedElapsedMs,
    ).toBeNull();
  expect(
    ring.observe('GET', `${origin}/collections`, 200, 900001)
      ?.observedElapsedMs,
  ).toBe(900000);
});

it('holds all four actual read permits across repeated expired observation windows', async () => {
  vi.useFakeTimers();
  try {
    const bodies = new KnowledgeBodyObservations();
    const ring = new KnowledgeDiagnostics();
    const entry = ring.observe('GET', `${origin}/collections`, 503, 1)!;
    let starts = 0;
    let settle: ((bytes: Uint8Array) => void) | undefined;
    const stalled = () => {
      starts++;
      return new Promise<Uint8Array>(() => {});
    };
    expect(
      bodies.observe(() => {
        starts++;
        return new Promise((resolve) => {
          settle = resolve;
        });
      }, entry),
    ).toBe(true);
    for (let i = 0; i < 3; i++)
      expect(bodies.observe(stalled, entry)).toBe(true);
    for (let i = 0; i < 5; i++) {
      await vi.advanceTimersByTimeAsync(201);
      expect(bodies.observe(stalled, entry)).toBe(false);
      expect(starts).toBe(4);
    }
    settle!(encode({ code: 'UNAVAILABLE' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(entry.code).toBe('UNOBSERVED');
    expect(bodies.observe(stalled, entry)).toBe(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(starts).toBe(5);
    bodies.dispose();
    expect(bodies.observe(stalled, entry)).toBe(false);
  } finally {
    vi.useRealTimers();
  }
});
it('observes timely bodies, releases rejected reads, and suppresses disposed observations', async () => {
  vi.useFakeTimers();
  try {
    const bodies = new KnowledgeBodyObservations();
    const entry = new KnowledgeDiagnostics().observe(
      'GET',
      `${origin}/collections`,
      503,
      1,
    )!;
    bodies.observe(
      () => Promise.resolve(encode({ code: 'UNAVAILABLE' })),
      entry,
    );
    await vi.advanceTimersByTimeAsync(0);
    expect(entry.code).toBe('UNAVAILABLE');
    for (let i = 0; i < 5; i++) {
      expect(
        bodies.observe(() => {
          throw new Error('CDP eviction');
        }, entry),
      ).toBe(true);
      await vi.advanceTimersByTimeAsync(0);
    }
    let settle: ((bytes: Uint8Array) => void) | undefined;
    bodies.observe(
      () =>
        new Promise((resolve) => {
          settle = resolve;
        }),
      entry,
    );
    await vi.advanceTimersByTimeAsync(0);
    bodies.dispose();
    settle!(encode({ code: 'STALE_POLICY' }));
    await vi.advanceTimersByTimeAsync(0);
    expect(entry.code).toBe('UNAVAILABLE');
  } finally {
    vi.useRealTimers();
  }
});
