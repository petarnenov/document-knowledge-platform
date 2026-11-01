import { EventEmitter } from 'node:events';
import { createHash } from 'node:crypto';
import type { CDPSession, Page } from '@playwright/test';
import { afterEach, expect, it, vi } from 'vitest';
import {
  flushBoundaryObserver,
  markBoundaryObserver,
  startBoundaryObserver,
} from '../e2e-real/boundary-event-observer';

const origin = 'https://localhost:19443';
const root = '/api/v1/dm/documents/11111111-1111-4111-8111-111111111111';
const binary = `${origin}/api/v1/dm/documents/uploads/22222222-2222-4222-8222-222222222222/content`;
type EventRow = {
  sequence: number;
  elapsedUs: number;
  kind: string;
  request: string | null;
  status?: number;
  canceled?: boolean;
  failure?: string;
};
function fixture() {
  const cdp = new EventEmitter();
  const page = {} as Page;
  const output: string[] = [];
  vi.spyOn(process.stdout, 'write').mockImplementation((chunk) => {
    output.push(String(chunk));
    return true;
  });
  startBoundaryObserver(cdp as unknown as CDPSession, page, origin, root);
  return {
    cdp,
    page,
    output,
    request: (id = 'PRIVATE_REQUEST_ID', url = binary, method = 'PUT') =>
      cdp.emit('Network.requestWillBeSent', {
        requestId: id,
        request: { url, method, headers: { Authorization: 'PRIVATE_TOKEN' } },
      }),
    response: (id: string, url: string, status: number) =>
      cdp.emit('Network.responseReceived', {
        requestId: id,
        response: { url, status, headers: { 'set-cookie': 'PRIVATE_COOKIE' } },
      }),
    record: () => {
      flushBoundaryObserver(page);
      expect(output).toHaveLength(1);
      expect(output[0]).toMatch(/^DKP270_EVENT_ORDER /);
      return JSON.parse(output[0]!.slice('DKP270_EVENT_ORDER '.length)) as {
        schemaVersion: number;
        rows: EventRow[];
        dropped: number;
      };
    },
  };
}
afterEach(() => vi.restoreAllMocks());

it('retains response200 then failure order and exact test markers without exposing correlation or transport secrets', () => {
  const f = fixture();
  f.request();
  markBoundaryObserver(f.page, 'before-completion-wait');
  f.response('PRIVATE_REQUEST_ID', binary, 200);
  markBoundaryObserver(f.page, 'binary-200-observed');
  f.response('append-id', `${origin}${root}/versions`, 201);
  markBoundaryObserver(f.page, 'append-observed');
  markBoundaryObserver(f.page, 'before-download');
  f.cdp.emit('Network.loadingFailed', {
    requestId: 'PRIVATE_REQUEST_ID',
    canceled: true,
    errorText: 'net::ERR_ABORTED',
  });
  f.response(
    'download-id',
    `${origin}${root}/versions/33333333-3333-4333-8333-333333333333/content`,
    200,
  );
  markBoundaryObserver(f.page, 'download-event');
  markBoundaryObserver(f.page, 'download-hash-checked');
  const result = f.record();
  expect(result.schemaVersion).toBe(1);
  expect(result.rows.map((row) => row.kind)).toEqual([
    'binary-request',
    'before-completion-wait',
    'binary-response',
    'binary-200-observed',
    'append-response',
    'append-observed',
    'before-download',
    'binary-failed',
    'download-response',
    'download-event',
    'download-hash-checked',
    'flush',
  ]);
  const hash = createHash('sha256')
    .update('PRIVATE_REQUEST_ID')
    .digest('hex')
    .slice(0, 24);
  expect(
    result.rows
      .filter((row) => row.kind.startsWith('binary-'))
      .map((row) => row.request),
  ).toEqual([hash, hash, null, hash]);
  expect(result.rows[2]?.status).toBe(200);
  expect(result.rows[7]).toMatchObject({
    canceled: true,
    failure: 'ERR_ABORTED',
  });
  expect(
    result.rows.every(
      (row, index) =>
        row.sequence === index + 1 &&
        Number.isInteger(row.elapsedUs) &&
        row.elapsedUs >= 0 &&
        row.elapsedUs <= 1_000_000_000,
    ),
  ).toBe(true);
  for (const secret of [
    'PRIVATE_REQUEST_ID',
    'PRIVATE_TOKEN',
    'PRIVATE_COOKIE',
    binary,
    root,
    origin,
  ])
    expect(f.output.join('')).not.toContain(secret);
});

it('rejects unexpected runtime marker kinds and ignores irrelevant or malformed CDP data without leaks', () => {
  const f = fixture();
  for (const [url, method] of [
    [binary.replace(origin, 'https://foreign.invalid'), 'PUT'],
    ['PRIVATE_INVALID_URL', 'PUT'],
    [binary, 'GET'],
    [`${origin}/other`, 'PUT'],
  ])
    f.request('PRIVATE_IRRELEVANT', url, method);
  f.request('x'.repeat(257));
  f.cdp.emit('Network.requestWillBeSent', {
    requestId: 'PRIVATE_ID',
    request: null,
  });
  f.cdp.emit('Network.loadingFailed', {
    requestId: 'PRIVATE_IRRELEVANT',
    errorText: 'PRIVATE_EXCEPTION',
  });
  f.response('id', `${origin}/unrelated`, 200);
  f.response('id', `https://foreign.invalid${root}/versions`, 200);
  f.response('id', 'PRIVATE_INVALID_URL', 200);
  f.response('x'.repeat(257), `${origin}${root}/versions`, 200);
  markBoundaryObserver(
    f.page,
    'PRIVATE_UNEXPECTED_MARKER' as Parameters<typeof markBoundaryObserver>[1],
  );
  expect(f.record().rows.map((row) => row.kind)).toEqual(['flush']);
  expect(f.output.join('')).not.toMatch(/PRIVATE_|foreign|https:|xxx/);
});

it('keeps status and failure classifiers finite, supports successful transport finish, and never emits arbitrary exceptions', () => {
  const f = fixture();
  f.request('id');
  for (const status of [0, 600, 200.5, Number.NaN, Number.POSITIVE_INFINITY])
    f.response('id', binary, status);
  f.cdp.emit('Network.loadingFailed', {
    requestId: 'id',
    errorText: 'PRIVATE_ERROR\nBearer secret',
  });
  f.cdp.emit('Network.loadingFinished', { requestId: 'id' });
  const result = f.record();
  expect(
    result.rows
      .filter((row) => row.kind === 'binary-response')
      .map((row) => row.status),
  ).toEqual([0, 0, 0, 0, 0]);
  expect(result.rows.find((row) => row.kind === 'binary-failed')).toMatchObject(
    { canceled: false, failure: 'OTHER' },
  );
  expect(result.rows.at(-2)?.kind).toBe('binary-finished');
  expect(f.output.join('')).not.toContain('PRIVATE_ERROR');
});

it('caps correlation at four requests, rows at128, dropped counter and each output record below32KiB', () => {
  const f = fixture();
  for (let i = 0; i < 5; i++) f.request(`id-${i}`);
  f.cdp.emit('Network.loadingFinished', { requestId: 'id-4' });
  for (let i = 0; i < 1_000_140; i++)
    f.cdp.emit('Network.loadingFinished', { requestId: 'id-0' });
  const result = f.record();
  expect(
    result.rows.filter((row) => row.kind === 'binary-request'),
  ).toHaveLength(4);
  expect(result.rows).toHaveLength(128);
  expect(result.dropped).toBe(1_000_000);
  expect(Buffer.byteLength(f.output[0]!)).toBeLessThan(32768);
});

it('flushes once, removes only its own listeners and releases the page recorder for reuse', () => {
  const f = fixture();
  const originalListener = vi.fn();
  f.cdp.on('Network.loadingFinished', originalListener);
  startBoundaryObserver(f.cdp as unknown as CDPSession, f.page, origin, root);
  expect(f.cdp.listenerCount('Network.requestWillBeSent')).toBe(1);
  f.record();
  flushBoundaryObserver(f.page);
  markBoundaryObserver(f.page, 'before-completion-wait');
  expect(f.output).toHaveLength(1);
  expect(f.cdp.eventNames()).toEqual(['Network.loadingFinished']);
  f.cdp.emit('Network.loadingFinished', { requestId: 'id' });
  expect(originalListener).toHaveBeenCalledOnce();
  startBoundaryObserver(f.cdp as unknown as CDPSession, f.page, origin, root);
  expect(f.cdp.listenerCount('Network.requestWillBeSent')).toBe(1);
  flushBoundaryObserver(f.page);
  expect(f.output).toHaveLength(2);
});

it('keeps finite timestamps and permits original disposal when diagnostic output fails', () => {
  const f = fixture();
  vi.spyOn(performance, 'now').mockReturnValue(Number.NaN);
  markBoundaryObserver(f.page, 'before-completion-wait');
  expect(f.record().rows[0]?.elapsedUs).toBe(0);
  startBoundaryObserver(f.cdp as unknown as CDPSession, f.page, origin, root);
  vi.mocked(process.stdout.write).mockImplementation(() => {
    throw new Error('PRIVATE_OUTPUT_FAILURE');
  });
  expect(() => flushBoundaryObserver(f.page)).not.toThrow();
  expect(f.cdp.eventNames()).toHaveLength(0);
});
