import { readFile } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { expect, test } from '@playwright/test';

// A separate fresh-runtime fixture, never a substitute for the full two-theme
// positive/negative journeys. No holds, rejections, clock writes or provider
// lifecycle operations: normal quarantine cleanup can reach quiescence.
const enabled = process.env.DKP_UPLOAD_DURABILITY_FIXTURE;
if (enabled !== undefined && enabled !== 'true')
  throw new Error('Durability fixture requires an explicit true profile.');
if (
  enabled === 'true' &&
  (process.env.DKP_UPLOAD_E2E_ENABLED !== 'true' ||
    process.env.DKP_UPLOAD_LIMITS_PROFILE !== undefined)
)
  throw new Error(
    'Durability fixture requires real uploads and excludes quota/expiry profiles.',
  );

if (enabled === 'true') {
  test('real HTTPS quiescent durability fixture: verified PDF append and exact content', async ({
    page,
    context,
    baseURL,
  }) => {
    test.setTimeout(600_000);
    const origin = new URL(baseURL!).origin;
    expect(new URL(origin).protocol).toBe('https:');
    await page.goto('/identity');
    await page.getByRole('link', { name: 'Sign in', exact: true }).click();
    await expect(page.locator('#username')).toBeVisible();
    await page.locator('#username').fill(process.env.DKP_IDENTITY_USERNAME!);
    await page.locator('#password').fill(process.env.DKP_IDENTITY_PASSWORD!);
    await page.locator('#kc-login').click();
    await page.waitForURL(
      (url) => url.origin === origin && url.pathname === '/',
    );
    const cookie = (await context.cookies()).find(
      (value) => value.name === '__Host-DKP-SESSION',
    );
    expect(cookie?.secure).toBe(true);
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe('Lax');
    const sessionResponse = await page.request.get('/api/v1/session');
    expect(sessionResponse.status()).toBe(200);
    const session = (await sessionResponse.json()) as {
      csrfToken: string;
      issuer: string;
      subject: string;
      tenants: string[];
    };
    expect(session.tenants.length).toBeGreaterThan(0);
    expect(session.issuer).toMatch(/^https:\/\//);
    expect(session.subject.length).toBeGreaterThan(0);
    const tenantId = session.tenants[0]!;
    const selected = await page.request.post('/api/v1/session/tenant', {
      data: { tenantId },
      headers: { Origin: origin, 'X-CSRF-TOKEN': session.csrfToken },
    });
    expect(selected.status()).toBe(200);
    const selectedSessionResponse = await page.request.get('/api/v1/session');
    expect(selectedSessionResponse.status()).toBe(200);
    const selectedSession = (await selectedSessionResponse.json()) as {
      csrfToken: string;
      selectedTenant: string;
    };
    expect(selectedSession.selectedTenant).toBe(tenantId);
    const guarded = () => ({
      Origin: origin,
      'X-CSRF-TOKEN': selectedSession.csrfToken,
      'Idempotency-Key': randomUUID(),
    });
    const base = '/api/v1/dm/documents';
    const created = await page.request.post(base, {
      data: {
        metadata: {
          title: `Synthetic durability ${randomUUID()}`,
          description: 'Public synthetic restart acceptance fixture.',
        },
        classification: 'development-restricted',
        classificationPolicyRevision: 1,
      },
      headers: guarded(),
    });
    expect(created.status()).toBe(201);
    const draft = (await created.json()) as {
      documentId: string;
      revision: number;
    };
    expect(Object.keys(draft).sort()).toEqual(['documentId', 'revision']);
    expect(draft.documentId).toMatch(/^[0-9a-f-]{36}$/);
    expect(draft.revision).toBe(0);
    const bytes = await readFile(
      fileURLToPath(new URL('./fixtures/synthetic.pdf', import.meta.url)),
    );
    const admission = await page.request.post(`${base}/uploads`, {
      data: {
        documentId: draft.documentId,
        expectedRevision: draft.revision,
        format: 'PDF',
        sizeBytes: bytes.length,
      },
      headers: guarded(),
    });
    expect(admission.status()).toBe(201);
    const admitted = (await admission.json()) as { uploadId: string };
    expect(Object.keys(admitted)).toEqual(['uploadId']);
    expect(admitted.uploadId).toMatch(/^[0-9a-f-]{36}$/);
    const upload = `${base}/uploads/${admitted.uploadId}`;
    const transferred = await page.request.put(`${upload}/content`, {
      data: bytes,
      headers: { ...guarded(), 'Content-Type': 'application/octet-stream' },
      timeout: 310_000,
    });
    expect(transferred.status()).toBe(200);
    expect(await transferred.json()).toEqual(admitted);
    const completed = await page.request.post(`${upload}/complete`, {
      data: {},
      headers: guarded(),
      timeout: 250_000,
    });
    expect(completed.status()).toBe(200);
    const receipt = (await completed.json()) as {
      uploadId: string;
      receiptId: string;
    };
    expect(Object.keys(receipt).sort()).toEqual(['receiptId', 'uploadId']);
    expect(receipt.uploadId).toBe(admitted.uploadId);
    expect(receipt.receiptId).toMatch(/^[0-9a-f-]{36}$/);
    const root = `${base}/${draft.documentId}`;
    const appended = await page.request.post(`${root}/versions`, {
      data: { expectedRevision: draft.revision, receiptId: receipt.receiptId },
      headers: guarded(),
    });
    expect(appended.status()).toBe(201);
    expect(await appended.json()).toEqual({
      documentId: draft.documentId,
      revision: 1,
    });
    const discovery = await page.request.get(
      `${root}/upload-receipts/${receipt.receiptId}/version`,
    );
    expect(discovery.status()).toBe(200);
    expect(discovery.headers()['cache-control']).toBe('no-store');
    const mapping = (await discovery.json()) as {
      documentId: string;
      versionId: string;
    };
    expect(Object.keys(mapping).sort()).toEqual(['documentId', 'versionId']);
    expect(mapping.documentId).toBe(draft.documentId);
    expect(mapping.versionId).toMatch(/^[0-9a-f-]{36}$/);
    const content = await page.request.get(
      `${root}/versions/${mapping.versionId}/content`,
      { headers: { Accept: 'application/pdf' } },
    );
    expect(content.status()).toBe(200);
    expect(content.headers()['content-type']).toBe('application/pdf');
    expect(content.headers()['content-length']).toBe(String(bytes.length));
    expect(content.headers()['cache-control']).toBe('no-store');
    expect(content.headers()['x-content-type-options']).toBe('nosniff');
    expect(content.headers()['content-disposition']).toBe(
      `attachment; filename="${mapping.versionId}.pdf"`,
    );
    const downloaded = await content.body();
    expect(downloaded).toEqual(bytes);
    const sha = (value: Buffer) =>
      createHash('sha256').update(value).digest('hex');
    expect(sha(downloaded)).toBe(sha(bytes));
    const document = await page.request.get(root);
    expect(document.status()).toBe(200);
    const final = await document.json();
    expect(final.revision).toBe(1);
    expect(final.activeVersionId).toBeNull();
    // Report IDs and a public synthetic hash only; never cookies, credentials,
    // identity claims or scanner/storage authority. Later tools inspect PG truth.
    await test.info().attach('synthetic-durability-fixture', {
      body: JSON.stringify({
        documentId: draft.documentId,
        uploadId: admitted.uploadId,
        receiptId: receipt.receiptId,
        versionId: mapping.versionId,
        sha256: sha(bytes),
      }),
      contentType: 'application/json',
    });
  });
}
