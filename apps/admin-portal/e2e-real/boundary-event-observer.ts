import { createHash } from 'node:crypto';
import type { CDPSession, Page } from '@playwright/test';

const marks = [
  'before-completion-wait',
  'binary-200-observed',
  'append-observed',
  'before-download',
  'download-event',
  'download-hash-checked',
] as const;
type Mark = (typeof marks)[number];
type Kind =
  | Mark
  | 'binary-request'
  | 'binary-response'
  | 'binary-failed'
  | 'binary-finished'
  | 'append-response'
  | 'download-response'
  | 'flush';
type Row = {
  sequence: number;
  elapsedUs: number;
  kind: Kind;
  request: string | null;
  status?: number;
  canceled?: boolean;
  failure?: 'ERR_ABORTED' | 'OTHER';
};
type Recorder = { mark: (mark: Mark) => void; flush: () => void };
const active = new WeakMap<Page, Recorder>();
const allowedMarks = new Set<string>(marks);
const MAX_EVENTS = 128;
const MAX_DROPPED = 1_000_000;
const validId = (id: unknown): id is string =>
  typeof id === 'string' && id.length > 0 && id.length <= 256;

/** Test-only passive telemetry. Never changes transport or propagates diagnostic output errors. */
export function startBoundaryObserver(
  cdp: CDPSession,
  page: Page,
  origin: string,
  root: string,
) {
  // A duplicate start must not strand listeners or multiply diagnostic output.
  if (active.has(page)) return;
  const started = performance.now();
  const rows: Row[] = [];
  const tracked = new Set<string>();
  let dropped = 0,
    sealed = false;
  const add = (
    kind: Kind,
    id: string | null = null,
    detail: Pick<Row, 'status' | 'canceled' | 'failure'> = {},
  ) => {
    if (sealed) return;
    if (rows.length === MAX_EVENTS) {
      dropped = Math.min(dropped + 1, MAX_DROPPED);
      return;
    }
    const elapsed = Math.floor((performance.now() - started) * 1000);
    rows.push({
      sequence: rows.length + 1,
      elapsedUs: Number.isFinite(elapsed)
        ? Math.max(0, Math.min(elapsed, 1_000_000_000))
        : 0,
      kind,
      request:
        id === null
          ? null
          : createHash('sha256').update(id).digest('hex').slice(0, 24),
      ...detail,
    });
  };
  const request = ({
    requestId,
    request,
  }: {
    requestId: string;
    request: { method: string; url: string };
  }) => {
    try {
      const url = new URL(request.url);
      if (
        validId(requestId) &&
        request.method === 'PUT' &&
        url.origin === origin &&
        /^\/api\/v1\/dm\/documents\/uploads\/[0-9a-f-]{36}\/content$/.test(
          url.pathname,
        )
      ) {
        if (tracked.size < 4) tracked.add(requestId);
        if (tracked.has(requestId)) add('binary-request', requestId);
      }
    } catch {
      /* Ignore unrelated/malformed events without logging their payloads. */
    }
  };
  const response = ({
    requestId,
    response,
  }: {
    requestId: string;
    response: { url: string; status: number };
  }) => {
    try {
      if (!validId(requestId)) return;
      const status =
        Number.isInteger(response.status) &&
        response.status >= 100 &&
        response.status <= 599
          ? response.status
          : 0;
      if (tracked.has(requestId)) add('binary-response', requestId, { status });
      const url = new URL(response.url);
      if (url.origin !== origin) return;
      if (url.pathname === `${root}/versions`)
        add('append-response', requestId, { status });
      if (
        url.pathname.startsWith(`${root}/versions/`) &&
        url.pathname.endsWith('/content')
      )
        add('download-response', requestId, { status });
    } catch {
      /* Never echo URLs, response data or arbitrary exceptions. */
    }
  };
  const failed = ({
    requestId,
    canceled,
    errorText,
  }: {
    requestId: string;
    canceled?: boolean;
    errorText: string;
  }) => {
    if (tracked.has(requestId))
      add('binary-failed', requestId, {
        canceled: canceled === true,
        failure: errorText === 'net::ERR_ABORTED' ? 'ERR_ABORTED' : 'OTHER',
      });
  };
  const finished = ({ requestId }: { requestId: string }) => {
    if (tracked.has(requestId)) add('binary-finished', requestId);
  };
  cdp.on('Network.requestWillBeSent', request);
  cdp.on('Network.responseReceived', response);
  cdp.on('Network.loadingFailed', failed);
  cdp.on('Network.loadingFinished', finished);
  active.set(page, {
    mark: (kind) => {
      if (allowedMarks.has(kind)) add(kind);
    },
    flush: () => {
      if (sealed) return;
      add('flush');
      sealed = true;
      cdp.off('Network.requestWillBeSent', request);
      cdp.off('Network.responseReceived', response);
      cdp.off('Network.loadingFailed', failed);
      cdp.off('Network.loadingFinished', finished);
      // <=128 fixed-schema rows, bounded integers and 24hex IDs keep each record <32KiB.
      try {
        process.stdout.write(
          'DKP270_EVENT_ORDER ' +
            JSON.stringify({ schemaVersion: 1, rows, dropped }) +
            '\n',
        );
      } catch {
        /* Best-effort telemetry must not prevent original test disposal. */
      }
    },
  });
}
export function markBoundaryObserver(page: Page, mark: Mark) {
  active.get(page)?.mark(mark);
}
export function flushBoundaryObserver(page: Page) {
  const recorder = active.get(page);
  active.delete(page);
  recorder?.flush();
}
