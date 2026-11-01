import { afterEach, expect, it, vi } from 'vitest';
import { z } from 'zod';
import * as api from './knowledge-api';
import * as schemas from './knowledge-schema';
const id = '11111111-1111-4111-8111-111111111111',
  other = '22222222-2222-4222-8222-222222222222';
const session = {
  issuer: 'https://issuer.example',
  subject: 'staff',
  displayName: 'Synthetic',
  tenants: ['tenant-a'],
  selectedTenant: 'tenant-a',
  csrfToken: 'csrf',
};
const signal = () => new AbortController().signal;
const json = (
  value: unknown,
  status = 200,
  headers: Record<string, string> = {},
) =>
  new Response(JSON.stringify(value), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Cache-Control': 'no-store',
      ...headers,
    },
  });
afterEach(() => vi.unstubAllGlobals());
it('rejects upstream status/header/encoding/schema/size failures instead of caching diagnostics', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const call = () =>
    api.knowledgeRequest(
      '/collections',
      z.object({ ok: z.literal(true) }).strict(),
      signal(),
    );
  for (const response of [
    json({ ok: true }, 201),
    json({ ok: true }, 200, { 'Content-Type': 'text/html' }),
    json({ ok: true }, 200, { 'Cache-Control': 'public' }),
    json({ ok: true }, 200, { 'Content-Encoding': 'gzip' }),
    json({ ok: true, private: 'diagnostic' }),
    json({ code: 'PRIVATE_DIAGNOSTIC' }, 503),
    new Response('not-json', {
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
      },
    }),
    new Response(null, {
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
      },
    }),
    new Response('x'.repeat(2097153), {
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
      },
    }),
  ]) {
    fetcher.mockResolvedValueOnce(response);
    await expect(call()).rejects.toMatchObject({ status: 503 });
  }
  fetcher.mockRejectedValueOnce(new Error('sensitive network detail'));
  await expect(call()).rejects.toMatchObject({ status: 503 });
  fetcher.mockResolvedValueOnce(json({ code: 'NOT_FOUND' }, 404));
  await expect(call()).rejects.toMatchObject({
    status: 404,
    code: 'NOT_FOUND',
  });
});
it('binds every individual resource, receipt and policy revision to the requested target', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const collection = {
    collectionId: other,
    revision: 1,
    metadata: { title: 'Synthetic', description: '' },
    status: 'ACTIVE',
    membershipRevision: 0,
    audiencePolicyRevision: 1,
    classificationCeiling: 'development-restricted',
    classificationPolicyRevision: 1,
  };
  fetcher.mockResolvedValueOnce(json(collection));
  await expect(api.inspectCollection(id, signal())).rejects.toMatchObject({
    status: 503,
  });
  fetcher.mockResolvedValueOnce(
    json({ collectionId: other, revision: 1, rules: [] }),
  );
  await expect(api.inspectCollectionAcl(id, signal())).rejects.toMatchObject({
    status: 503,
  });
  fetcher.mockResolvedValueOnce(
    json({
      collectionId: other,
      revision: 1,
      classificationCeiling: 'development-restricted',
      classificationPolicyRevision: 1,
      rules: [],
      effectiveAt: '2026-11-01T00:00:00Z',
    }),
  );
  await expect(api.inspectAudience(id, signal())).rejects.toMatchObject({
    status: 503,
  });
  fetcher.mockResolvedValueOnce(
    json({ collectionId: other, audiencePolicyRevision: 1, decision: 'DENY' }),
  );
  await expect(
    api.evaluateAudience(id, session, signal()),
  ).rejects.toMatchObject({ status: 503 });
  fetcher.mockResolvedValueOnce(json({ collectionId: other, revision: 1 }));
  await expect(
    api.knowledgeCommand(
      'metadata',
      session,
      signal(),
      {
        expectedRevision: 1,
        metadata: { title: 'Synthetic', description: '' },
      },
      'key',
      id,
    ),
  ).rejects.toMatchObject({ status: 503 });
  fetcher.mockResolvedValueOnce(
    json({ collectionId: id, membershipId: other, revision: 1 }),
  );
  await expect(
    api.knowledgeCommand(
      'membership',
      session,
      signal(),
      {
        expectedRevision: 1,
        expectedMembershipRevision: 1,
        expectedDocumentRevision: 1,
        state: 'ACTIVE',
        reason: 'Synthetic',
      },
      'key',
      id,
      id,
    ),
  ).rejects.toMatchObject({ status: 503 });
  fetcher.mockResolvedValueOnce(
    json(
      {
        snapshotToken: 'a'.repeat(43),
        documentId: id,
        documentRevision: 2,
        classificationPolicyRevision: 1,
        decision: 'COMPATIBLE',
      },
      201,
    ),
  );
  await expect(
    api.snapshotPolicy(id, 1, session, signal(), 'key'),
  ).rejects.toMatchObject({ status: 503 });
  fetcher.mockResolvedValueOnce(
    json({ documentId: other, decision: 'COMPATIBLE' }),
  );
  await expect(
    api.validatePolicy(id, 1, 'a'.repeat(43), session, signal()),
  ).rejects.toMatchObject({ status: 503 });
  fetcher.mockResolvedValueOnce(
    json({ items: [collection, collection], nextCursor: null }),
  );
  await expect(api.listCollections(signal())).rejects.toMatchObject({
    status: 503,
  });
});
it('closed schemas reject forged principals, unsafe revisions, selector widening and unknown decisions', () => {
  expect(
    schemas.actorSchema.safeParse({ issuer: 'relative', subject: 'staff' })
      .success,
  ).toBe(false);
  expect(
    schemas.actorSchema.safeParse({
      issuer: 'https://issuer.example',
      subject: 'staff',
      tenant: 'foreign',
    }).success,
  ).toBe(false);
  expect(
    schemas.updateCollectionSchema.safeParse({
      expectedRevision: Number.MAX_SAFE_INTEGER + 1,
      metadata: { title: 'x', description: '' },
    }).success,
  ).toBe(false);
  expect(
    schemas.audienceSelectorSchema.safeParse({
      kind: 'TENANT',
      tenant: 'tenant-a',
      issuer: 'https://forged',
    }).success,
  ).toBe(false);
  expect(
    schemas.audienceSelectorSchema.safeParse({
      kind: 'PRINCIPAL',
      principal: { issuer: 'https://issuer.example', subject: 'staff' },
      all: true,
    }).success,
  ).toBe(false);
  const rule = {
    effect: 'ALLOW',
    selector: { kind: 'TENANT', tenant: 'tenant-a' },
  };
  expect(
    schemas.updateAudienceSchema.safeParse({
      expectedRevision: 1,
      rules: [rule, rule],
      reason: 'Synthetic',
    }).success,
  ).toBe(false);
  expect(
    schemas.errorSchema.safeParse({ code: 'SQL_DIAGNOSTIC' }).success,
  ).toBe(false);
  expect(
    schemas.validatePolicySchema.safeParse({
      snapshotToken: 'private-fingerprint',
      expectedDocumentRevision: 1,
    }).success,
  ).toBe(false);
});
it('offers finite safe messages for each rejection class without provider text', () => {
  for (const status of [400, 401, 403, 404, 409, 413, 415, 422, 429, 503]) {
    const text = api.knowledgeError(
      new api.KnowledgeError(status, 'PRIVATE_SQL'),
    );
    expect(text).not.toContain('PRIVATE_SQL');
    expect(text.length).toBeGreaterThan(10);
  }
  expect(api.knowledgeError(new Error('secret'))).not.toContain('secret');
});
