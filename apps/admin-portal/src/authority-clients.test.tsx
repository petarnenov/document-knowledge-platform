import { afterEach, expect, it, vi } from 'vitest';
import {
  AuthorityChangedError,
  type RequestAuthority,
} from './request-authority';
import { getContext } from './identity-api';
import {
  command,
  inspectAcl,
  inspectDraft,
  inspectHolds,
  listDrafts,
} from './document-api';
import {
  appendUpload,
  completeUpload,
  discoverVersion,
  downloadVersion,
  initializeUpload,
  inspectUpload,
  transferUpload,
} from './upload-api';

const id = '11111111-1111-4111-8111-111111111111';
const session = {
  issuer: 'https://idp.test',
  subject: 'synthetic',
  displayName: 'Synthetic',
  tenants: ['a'],
  selectedTenant: 'a',
  csrfToken: 'synthetic-csrf',
};
const file = new File(['data'], 'synthetic.pdf');
const calls: [string, (guard: RequestAuthority) => Promise<unknown>][] = [
  ['list', (g) => listDrafts(session, g)],
  ['detail', (g) => inspectDraft(id, session, g)],
  ['acl', (g) => inspectAcl(id, g)],
  ['holds', (g) => inspectHolds(id, g)],
  ['command', (g) => command(session, g, 'create', {})],
  ['context', (g) => getContext('dm', session, g)],
  [
    'initialize',
    (g) => initializeUpload(id, 0, 'PDF', file, session, 'key', g),
  ],
  ['transfer', (g) => transferUpload(id, file, session, 'key', g)],
  ['complete', (g) => completeUpload(id, session, 'key', g)],
  ['append', (g) => appendUpload(id, 0, id, session, 'key', g)],
  ['discover', (g) => discoverVersion(id, id, g)],
  ['status', (g) => inspectUpload(id, g)],
  ['download', (g) => downloadVersion(id, id, 'PDF', g)],
];
afterEach(() => vi.unstubAllGlobals());

it.each(calls)(
  'guards %s before transport and suppresses stale HTTP401 failure',
  async (_name, invoke) => {
    let current = false;
    const guard = {
      signal: new AbortController().signal,
      assertCurrent: () => {
        if (!current) throw new AuthorityChangedError();
      },
    };
    const fetcher = vi.fn(async () => {
      current = false;
      return new Response('{}', { status: 401 });
    });
    vi.stubGlobal('fetch', fetcher);
    await expect(invoke(guard)).rejects.toBeInstanceOf(AuthorityChangedError);
    expect(fetcher).not.toHaveBeenCalled();
    current = true;
    await expect(invoke(guard)).rejects.toBeInstanceOf(AuthorityChangedError);
    expect(fetcher).toHaveBeenCalledOnce();
  },
);

it.each(['documents', 'upload', 'download'] as const)(
  'guards %s after delayed response-body completion',
  async (kind) => {
    let current = true;
    let finish!: () => void;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        finish = () => {
          controller.enqueue(
            new TextEncoder().encode(kind === 'download' ? 'data' : '{}'),
          );
          controller.close();
        };
      },
    });
    const headers =
      kind === 'download'
        ? {
            'Content-Type': 'application/pdf',
            'Content-Length': '4',
            'Content-Disposition': `attachment; filename="${id}.pdf"`,
            'Cache-Control': 'no-store',
            'X-Content-Type-Options': 'nosniff',
          }
        : { 'Content-Type': 'application/json' };
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(body, { headers })),
    );
    const guard = {
      signal: new AbortController().signal,
      assertCurrent: () => {
        if (!current) throw new AuthorityChangedError();
      },
    };
    const result =
      kind === 'documents'
        ? listDrafts(session, guard)
        : kind === 'upload'
          ? inspectUpload(id, guard)
          : downloadVersion(id, id, 'PDF', guard);
    const assertion = expect(result).rejects.toBeInstanceOf(
      AuthorityChangedError,
    );
    await Promise.resolve();
    current = false;
    finish();
    await assertion;
  },
);
