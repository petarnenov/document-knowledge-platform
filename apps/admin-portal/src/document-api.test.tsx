import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  command,
  documentError,
  DocumentRequestError,
  inspectAcl,
  inspectDraft,
  inspectHolds,
  listDrafts,
} from './document-api';
const id = '11111111-1111-4111-8111-111111111111';
const session = {
  issuer: 'https://idp.example',
  subject: 'staff',
  displayName: 'Synthetic',
  selectedTenant: 'tenant-a',
  tenants: ['tenant-a'],
  csrfToken: 'csrf',
};
const draft = {
  documentId: id,
  tenant: 'tenant-a',
  creator: { issuer: session.issuer, subject: 'other' },
  createdAt: '2026-10-31T10:00:00Z',
  revision: 0,
  lifecycle: 'DRAFT',
  metadata: { title: 'Draft', description: '' },
  classification: 'development-restricted',
  classificationPolicyRevision: 1,
  activeVersionId: null,
  retention: {
    policyRef: 'development-unresolved',
    policyRevision: 1,
    resolution: 'UNRESOLVED',
    retainUntil: null,
    destructiveEligible: false,
  },
};
const json = (value: unknown, status = 200, headers?: HeadersInit) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
afterEach(() => vi.unstubAllGlobals());
describe('document boundary', () => {
  it('accepts only tenant scoped drafts, bounded pages and same-document ACL/holds', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        json({ items: [draft], nextCursor: 'opaque cursor' }),
      )
      .mockResolvedValueOnce(json(draft))
      .mockResolvedValueOnce(json({ documentId: id, revision: 0, rules: [] }))
      .mockResolvedValueOnce(json({ items: [] }));
    vi.stubGlobal('fetch', fetcher);
    const signal = new AbortController().signal;
    expect(
      (await listDrafts(session, signal, 'opaque cursor')).items,
    ).toHaveLength(1);
    expect(fetcher.mock.calls[0]?.[0]).toContain('cursor=opaque%20cursor');
    expect((await inspectDraft(id, session, signal)).metadata.title).toBe(
      'Draft',
    );
    expect((await inspectAcl(id, signal)).rules).toEqual([]);
    expect((await inspectHolds(id, signal)).items).toEqual([]);
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({
      cache: 'no-store',
      redirect: 'error',
      credentials: 'same-origin',
    });
  });
  it('uses fixed mutation methods, CSRF and retry key; receipts never carry inspection facts', async () => {
    const fetcher = vi.fn<
      (path: string, init?: RequestInit) => Promise<Response>
    >(async (_path, init) =>
      json(
        { documentId: id, revision: 1 },
        init?.method === 'POST' ? 201 : 200,
      ),
    );
    vi.stubGlobal('fetch', fetcher);
    for (const kind of ['create', 'metadata', 'acl', 'hold'] as const)
      await command(
        session,
        new AbortController().signal,
        kind,
        {},
        kind === 'create' ? undefined : id,
        'same-key',
      );
    expect(fetcher.mock.calls.map((call) => call[1]?.method)).toEqual([
      'POST',
      'PATCH',
      'PATCH',
      'POST',
    ]);
    expect(fetcher.mock.calls[2]?.[0]).toBe(`/api/v1/dm/documents/${id}/acl`);
    expect(fetcher.mock.calls[0]?.[1]?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'csrf',
      'Idempotency-Key': 'same-key',
    });
    fetcher.mockImplementation(async () =>
      json({ documentId: id, revision: 2, metadata: draft.metadata }, 201),
    );
    await expect(
      command(session, new AbortController().signal, 'create', {}),
    ).rejects.toMatchObject({ status: 503 });
  });
  it('fails closed for wrong tenant, wrong document and invalid schema fields', async () => {
    const signal = new AbortController().signal;
    const other = '22222222-2222-4222-8222-222222222222';
    for (const field of Object.keys(draft)) {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => json({ ...draft, [field]: 'invalid' })),
      );
      await expect(inspectDraft(id, session, signal)).rejects.toMatchObject({
        status: 503,
      });
    }
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => json({ ...draft, tenant: 'tenant-b' })),
    );
    await expect(inspectDraft(id, session, signal)).rejects.toMatchObject({
      status: 503,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        json({ items: [{ ...draft, tenant: 'tenant-b' }], nextCursor: null }),
      ),
    );
    await expect(listDrafts(session, signal)).rejects.toMatchObject({
      status: 503,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => json({ documentId: other, revision: 0, rules: [] })),
    );
    await expect(inspectAcl(id, signal)).rejects.toMatchObject({ status: 503 });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => json({ ...draft, documentId: other })),
    );
    await expect(inspectDraft(id, session, signal)).rejects.toMatchObject({
      status: 503,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        json({
          items: [
            {
              holdId: id,
              documentId: other,
              revision: 0,
              active: true,
              reason: 'Hold',
              placedBy: draft.creator,
              placedAt: draft.createdAt,
            },
          ],
        }),
      ),
    );
    await expect(inspectHolds(id, signal)).rejects.toMatchObject({
      status: 503,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => json({ documentId: other, revision: 0 })),
    );
    await expect(
      command(session, signal, 'metadata', {}, id),
    ).rejects.toMatchObject({ status: 503 });
    await expect(
      command(session, signal, 'metadata', {}),
    ).rejects.toMatchObject({ status: 400 });
    await expect(
      inspectDraft('../steal', session, signal),
    ).rejects.toMatchObject({ status: 400 });
  });
  it('bounds streaming responses and sanitizes every transport failure', async () => {
    const signal = new AbortController().signal;
    for (const response of [
      json(draft, 202),
      new Response(JSON.stringify(draft), {
        headers: { 'Content-Type': 'text/html' },
      }),
      new Response(JSON.stringify(draft)),
      new Response('bad-json', {
        headers: { 'Content-Type': 'application/json' },
      }),
      new Response(null, { headers: { 'Content-Type': 'application/json' } }),
      json({}, 200, { 'content-length': '1048577' }),
      new Response('x'.repeat(1048577), {
        headers: { 'Content-Type': 'application/json' },
      }),
    ]) {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => response),
      );
      await expect(inspectDraft(id, session, signal)).rejects.toMatchObject({
        status: 503,
      });
    }
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('private')));
    await expect(listDrafts(session, signal)).rejects.toMatchObject({
      status: 503,
    });
    for (const status of [400, 401, 403, 404, 409, 413, 415, 422, 503]) {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => json({ code: 'DENIED' }, status)),
      );
      await expect(inspectDraft(id, session, signal)).rejects.toMatchObject({
        status,
        code: 'DENIED',
      });
      expect(documentError(new DocumentRequestError(status))).not.toContain(
        'private',
      );
    }
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('private', { status: 503 })),
    );
    await expect(listDrafts(session, signal)).rejects.toMatchObject({
      code: 'REQUEST_FAILED',
    });
    expect(documentError(new Error('private'))).toContain('unavailable');
  });
});

it('describes upload expiry, capacity and rejection without exposing scanner diagnostics', () => {
  for (const code of [
    'UPLOAD_EXPIRED',
    'QUOTA_EXCEEDED',
    'MALWARE_DETECTED',
    'INVALID_FILE',
    'STRUCTURE_LIMIT',
    'UNSUPPORTED_CONTENT',
    'SIZE_MISMATCH',
  ])
    expect(documentError(new DocumentRequestError(422, code))).not.toContain(
      'request was rejected',
    );
});
