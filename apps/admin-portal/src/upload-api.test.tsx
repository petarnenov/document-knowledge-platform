import { afterEach, expect, it, vi } from 'vitest';
import {
  appendUpload,
  completeUpload,
  discoverVersion,
  downloadVersion,
  formats,
  initializeUpload,
  inspectUpload,
  transferUpload,
} from './upload-api';
const id = '11111111-1111-4111-8111-111111111111',
  other = '22222222-2222-4222-8222-222222222222';
const session = {
  issuer: 'https://issuer.example',
  subject: 'user',
  displayName: 'Synthetic',
  tenants: ['tenant-a'],
  selectedTenant: 'tenant-a',
  csrfToken: 'csrf',
};
const signal = () => new AbortController().signal;
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
const file = new File(['data'], 'synthetic.pdf');
afterEach(() => vi.unstubAllGlobals());
it('uses fixed closed requests, streamed file and minimal receipts across the complete journey', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(json({ uploadId: id }, 201))
    .mockResolvedValueOnce(json({ uploadId: id }))
    .mockResolvedValueOnce(json({ uploadId: id, receiptId: other }))
    .mockResolvedValueOnce(json({ documentId: id, revision: 1 }, 201))
    .mockResolvedValueOnce(json({ documentId: id, versionId: other }))
    .mockResolvedValueOnce(
      json({
        uploadId: id,
        state: 'CONSUMED',
        expiresAt: '2026-11-01T00:00:00Z',
        failureCode: null,
      }),
    );
  vi.stubGlobal('fetch', fetcher);
  await initializeUpload(id, 0, 'PDF', file, session, 'init', signal());
  await transferUpload(id, file, session, 'put', signal());
  await completeUpload(id, session, 'complete', signal());
  await appendUpload(id, 0, other, session, 'append', signal());
  await discoverVersion(id, other, signal());
  await inspectUpload(id, signal());
  const first = fetcher.mock.calls[0]![1] as RequestInit;
  expect(JSON.parse(first.body as string)).toEqual({
    documentId: id,
    expectedRevision: 0,
    format: 'PDF',
    sizeBytes: 4,
  });
  expect(first.credentials).toBe('same-origin');
  expect(first.redirect).toBe('error');
  expect(first.headers).toEqual({
    'Content-Type': 'application/json',
    Accept: 'application/json',
    'X-CSRF-TOKEN': 'csrf',
    'Idempotency-Key': 'init',
  });
  expect(fetcher.mock.calls[1]![1].body).toBe(file);
  expect(fetcher.mock.calls[4]![0]).toBe(
    `/api/v1/dm/documents/${id}/upload-receipts/${other}/version`,
  );
});
it('rejects invalid admission before network and mismatched scoped responses', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const invalid of [
    new File([], 'empty.pdf'),
    { size: 100000001 } as File,
  ])
    expect(() =>
      initializeUpload(id, 0, 'PDF', invalid, session, 'k', signal()),
    ).toThrow();
  expect(() =>
    initializeUpload('bad', 0, 'PDF', file, session, 'k', signal()),
  ).toThrow();
  expect(() =>
    initializeUpload(id, -1, 'PDF', file, session, 'k', signal()),
  ).toThrow();
  expect(fetcher).not.toHaveBeenCalled();
  const calls = [
    () => transferUpload(id, file, session, 'k', signal()),
    () => completeUpload(id, session, 'k', signal()),
    () => appendUpload(id, 0, other, session, 'k', signal()),
    () => discoverVersion(id, other, signal()),
    () => inspectUpload(id, signal()),
  ];
  for (const call of calls) {
    fetcher.mockResolvedValue(
      json({
        uploadId: other,
        receiptId: other,
        documentId: other,
        versionId: other,
        revision: 1,
        state: 'VERIFIED',
        expiresAt: '2026-11-01T00:00:00Z',
        failureCode: null,
      }),
    );
    await expect(call()).rejects.toMatchObject({ status: 503 });
  }
});
it('bounds JSON and sanitizes dependency and typed authority failures', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  for (const response of [
    new Response('private', { status: 503 }),
    json({ code: 'NOT_FOUND' }, 404),
    json({ code: 'secret', details: 'private' }, 403),
  ]) {
    fetcher.mockResolvedValue(response);
    await expect(inspectUpload(id, signal())).rejects.toMatchObject({
      status: response.status,
    });
  }
  fetcher.mockResolvedValue(json({ code: 'INTERNAL_SECRET' }, 503));
  await expect(inspectUpload(id, signal())).rejects.toMatchObject({
    code: 'UNAVAILABLE',
  });
  fetcher.mockRejectedValue(new Error('private'));
  await expect(inspectUpload(id, signal())).rejects.toMatchObject({
    status: 503,
  });
  for (const response of [
    new Response('x'.repeat(16385), {
      headers: { 'Content-Type': 'application/json' },
    }),
    json(null),
    json({ uploadId: id }, 201),
    new Response('{}', { headers: { 'Content-Type': 'text/html' } }),
    new Response(null),
  ]) {
    fetcher.mockResolvedValue(response);
    await expect(inspectUpload(id, signal())).rejects.toMatchObject({
      status: 503,
    });
  }
});
it('downloads only exact authorized attachment bytes with strict MIME, length and safety headers', async () => {
  const mime = [
    'application/pdf',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  ];
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  function response(
    format: string,
    type: string,
    overrides: Record<string, string> = {},
    status = 200,
    body = 'data',
  ) {
    return new Response(body, {
      status,
      headers: {
        'Content-Type': type,
        'Content-Length': '4',
        'Content-Disposition': `attachment; filename="${other}.${format.toLowerCase()}"`,
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
        ...overrides,
      },
    });
  }
  for (const [index, format] of formats.entries()) {
    fetcher.mockResolvedValue(response(format, mime[index]!));
    expect((await downloadVersion(id, other, format, signal())).size).toBe(4);
    expect(fetcher.mock.calls.at(-1)?.[1].headers).toEqual({
      Accept: mime[index],
    });
  }
  for (const override of [
    { 'Content-Type': 'text/html' },
    { 'Content-Length': '100000001' },
    { 'Content-Length': '0' },
    { 'Content-Length': '5' },
    { 'Content-Length': '3' },
    { 'Content-Disposition': 'inline' },
    { 'Cache-Control': 'public' },
    { 'X-Content-Type-Options': 'unsafe' },
  ]) {
    fetcher.mockResolvedValue(response('PDF', mime[0]!, override));
    await expect(
      downloadVersion(id, other, 'PDF', signal()),
    ).rejects.toMatchObject({ status: 503 });
  }
  fetcher.mockResolvedValue(response('PDF', mime[0]!, {}, 206));
  await expect(
    downloadVersion(id, other, 'PDF', signal()),
  ).rejects.toMatchObject({ status: 503 });
});
