import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import type { APIResponse, Page } from '@playwright/test';

// These destructive admission exercises require their own fresh recorded runtime.
// The default identity journey does not silently run a one-hour resource test.
// The dedicated runner must select this file AND explicitly select one profile.
const profile = process.env.DKP_UPLOAD_LIMITS_PROFILE;
if (profile !== undefined && profile !== 'quota' && profile !== 'expiry')
  throw new Error('Upload limits profile must be quota or expiry.');
if (profile && process.env.DKP_UPLOAD_E2E_ENABLED !== 'true')
  throw new Error('Upload limits require the explicit real upload runtime.');
const base = '/api/v1/dm/documents';
const fixture = fileURLToPath(
  new URL('./fixtures/synthetic.pdf', import.meta.url),
);
type Session = {
  csrfToken: string;
  issuer: string;
  subject: string;
  tenants: string[];
  selectedTenant: string | null;
};
type Draft = { documentId: string; revision: number };
type Slot = {
  uploadId: string;
  expiresAt: string;
  state: string;
  failureCode: string | null;
};

const renewExpiredSession = new WeakMap<
  Page,
  (denied: APIResponse) => Promise<Session>
>();
async function session(page: Page, allowRenewal = true): Promise<Session> {
  const response = await page.request.get('/api/v1/session');
  const renewal = allowRenewal ? renewExpiredSession.get(page) : undefined;
  if (response.status() === 401 && renewal) return renewal(response);
  expect(response.status()).toBe(200);
  return response.json() as Promise<Session>;
}
async function headers(page: Page, key = randomUUID()) {
  return {
    Origin: new URL(page.url()).origin,
    'X-CSRF-TOKEN': (await session(page)).csrfToken,
    'Idempotency-Key': key,
  };
}
async function safeError(response: APIResponse, status: number, code: string) {
  expect(response.status()).toBe(status);
  expect(await response.json()).toEqual({ code });
}
async function login(page: Page) {
  await page.goto('/identity');
  const origin = new URL(page.url()).origin;
  await page.getByRole('link', { name: 'Sign in', exact: true }).click();
  // An unexpired real IdP SSO session may return immediately. Otherwise submit
  // the ordinary provider form. Neither path manufactures a BFF session/token.
  const returned = page
    .waitForURL((url) => url.origin === origin && url.pathname === '/')
    .then(() => 'returned' as const);
  const next = await Promise.race([
    returned,
    page
      .locator('#username')
      .waitFor({ state: 'visible' })
      .then(() => 'credentials' as const),
  ]);
  if (next === 'credentials') {
    await page.locator('#username').fill(process.env.DKP_IDENTITY_USERNAME!);
    await page.locator('#password').fill(process.env.DKP_IDENTITY_PASSWORD!);
    await page.locator('#kc-login').click();
    await returned;
  }
  const current = await session(page, false);
  expect(current.tenants.length).toBeGreaterThanOrEqual(2);
  return current;
}
async function selectTenant(page: Page, tenantId: string) {
  const response = await page.request.post('/api/v1/session/tenant', {
    data: { tenantId },
    headers: await headers(page),
  });
  expect(response.status()).toBe(200);
}
async function create(page: Page): Promise<Draft> {
  const response = await page.request.post(base, {
    data: {
      metadata: {
        title: `Synthetic limits ${randomUUID()}`,
        description: 'Public synthetic acceptance fixture.',
      },
      classification: 'development-restricted',
      classificationPolicyRevision: 1,
    },
    headers: await headers(page),
  });
  expect(response.status()).toBe(201);
  const result = (await response.json()) as Draft;
  expect(Object.keys(result).sort()).toEqual(['documentId', 'revision']);
  return result;
}
async function initialize(
  page: Page,
  draft: Draft,
  bytes: Buffer,
  key = randomUUID(),
) {
  return page.request.post(`${base}/uploads`, {
    data: {
      documentId: draft.documentId,
      expectedRevision: draft.revision,
      format: 'PDF',
      sizeBytes: bytes.length,
    },
    headers: await headers(page, key),
  });
}
async function admitted(page: Page, draft: Draft, bytes: Buffer) {
  const response = await initialize(page, draft, bytes);
  expect(response.status()).toBe(201);
  const { uploadId } = (await response.json()) as { uploadId: string };
  return uploadId;
}
async function status(page: Page, uploadId: string): Promise<Slot> {
  const response = await page.request.get(`${base}/uploads/${uploadId}/status`);
  expect(response.status()).toBe(200);
  return response.json() as Promise<Slot>;
}
async function verified(page: Page, uploadId: string, bytes: Buffer) {
  const bodyKey = randomUUID(),
    completeKey = randomUUID();
  const body = await page.request.put(`${base}/uploads/${uploadId}/content`, {
    data: bytes,
    headers: {
      ...(await headers(page, bodyKey)),
      'Content-Type': 'application/octet-stream',
    },
    timeout: 310_000,
  });
  expect(body.status()).toBe(200);
  const complete = await page.request.post(
    `${base}/uploads/${uploadId}/complete`,
    { data: {}, headers: await headers(page, completeKey), timeout: 250_000 },
  );
  expect(complete.status()).toBe(200);
  const receipt = (await complete.json()) as {
    uploadId: string;
    receiptId: string;
  };
  expect(Object.keys(receipt).sort()).toEqual(['receiptId', 'uploadId']);
  return { ...receipt, bodyKey, completeKey };
}
async function denyAppend(page: Page, draft: Draft, actor: Session) {
  const response = await page.request.patch(`${base}/${draft.documentId}/acl`, {
    data: {
      expectedRevision: draft.revision,
      mode: 'RESTRICT',
      changes: [
        {
          operation: 'ADD_DENY',
          principal: { issuer: actor.issuer, subject: actor.subject },
          action: 'document.version.append',
        },
      ],
    },
    headers: await headers(page),
  });
  expect(response.status()).toBe(200);
  return response.json() as Promise<Draft>;
}

if (profile === 'quota') {
  test('real HTTPS six concurrent admissions, global fairness, unsafe guards and current ACL', async ({
    page,
  }) => {
    test.setTimeout(600_000);
    const actor = await login(page),
      bytes = await readFile(fixture);
    const tenant = actor.tenants[0]!,
      alternate = actor.tenants[1]!;
    await selectTenant(page, tenant);
    // Create before admission, so resource exhaustion cannot be confused with metadata authorization.
    const drafts = await Promise.all(
      Array.from({ length: 6 }, () => create(page)),
    );
    const responses = await Promise.all(
      drafts.map((draft) => initialize(page, draft, bytes)),
    );
    expect(responses.map((response) => response.status()).sort()).toEqual([
      201, 201, 201, 201, 201, 429,
    ]);
    const winners: { draft: Draft; id: string }[] = [];
    for (const [index, response] of responses.entries()) {
      if (response.status() === 429)
        await safeError(response, 429, 'QUOTA_EXCEEDED');
      else
        winners.push({
          draft: drafts[index]!,
          id: ((await response.json()) as { uploadId: string }).uploadId,
        });
    }
    const winner = winners[0]!;
    const before = await status(page, winner.id);
    const receipt = await verified(page, winner.id, bytes);
    expect((await status(page, winner.id)).expiresAt).toBe(before.expiresAt);
    const append = `${base}/${winner.draft.documentId}/versions`;
    const calls = [
      {
        path: `${base}/uploads/${winner.id}/content`,
        method: 'PUT',
        data: bytes,
        type: 'application/octet-stream',
      },
      {
        path: `${base}/uploads/${winner.id}/complete`,
        method: 'POST',
        data: {},
        type: 'application/json',
      },
      {
        path: append,
        method: 'POST',
        data: {
          expectedRevision: winner.draft.revision,
          receiptId: receipt.receiptId,
        },
        type: 'application/json',
      },
    ];
    for (const call of calls)
      for (const unsafe of ['missing-csrf', 'foreign-origin'] as const) {
        const guard = await headers(page);
        if (unsafe === 'missing-csrf')
          delete (guard as Partial<typeof guard>)['X-CSRF-TOKEN'];
        else guard.Origin = 'https://untrusted.invalid';
        await safeError(
          await page.request.fetch(call.path, {
            method: call.method,
            data: call.data,
            headers: { ...guard, 'Content-Type': call.type },
          }),
          403,
          'OPERATION_DENIED',
        );
      }
    await denyAppend(page, winner.draft, actor);
    for (const call of calls)
      await safeError(
        await page.request.fetch(call.path, {
          method: call.method,
          data: call.data,
          headers: { ...(await headers(page)), 'Content-Type': call.type },
        }),
        404,
        'NOT_FOUND',
      );
    // Even a previously committed complete command rechecks current authority.
    await safeError(
      await page.request.post(calls[1]!.path, {
        data: {},
        headers: await headers(page, receipt.completeKey),
      }),
      404,
      'NOT_FOUND',
    );
    await safeError(
      await page.request.put(`${base}/uploads/${winner.id}/content`, {
        data: bytes,
        headers: {
          ...(await headers(page, receipt.bodyKey)),
          'Content-Type': 'application/octet-stream',
        },
      }),
      404,
      'NOT_FOUND',
    );
    await safeError(
      await page.request.get(`${base}/uploads/${winner.id}/status`),
      404,
      'NOT_FOUND',
    );
    // Revocation does not release quota or discard the stored quarantine/original.
    await safeError(
      await initialize(page, drafts[5]!, bytes),
      429,
      'QUOTA_EXCEEDED',
    );
    await selectTenant(page, alternate);
    const otherDrafts = await Promise.all(
      Array.from({ length: 6 }, () => create(page)),
    );
    const other = await Promise.all(
      otherDrafts.map((draft) => initialize(page, draft, bytes)),
    );
    expect(other.map((response) => response.status()).sort()).toEqual([
      201, 201, 201, 201, 201, 429,
    ]);
    for (const response of other)
      if (response.status() === 429)
        await safeError(response, 429, 'QUOTA_EXCEEDED');
    // This is a two-tenant ten-global acceptance; a third-tenant global-vs-tenant distinction
    // requires a separately authorized third catalog actor, never an invented grant.
    await selectTenant(page, tenant);
    await page.goto('/documents');
    const visibleTitle = `Synthetic visible capacity ${randomUUID()}`;
    await page.getByLabel('Title', { exact: true }).fill(visibleTitle);
    await page
      .getByRole('button', { name: 'Create draft', exact: true })
      .click();
    await expect(
      page.getByRole('status').filter({ hasText: 'Saved document' }),
    ).toBeVisible();
    for (
      let pages = 0;
      pages < 20 &&
      (await page
        .getByRole('button', { name: visibleTitle, exact: true })
        .count()) === 0;
      pages++
    ) {
      const next = page.getByRole('button', { name: 'Next page', exact: true });
      await expect(next).toBeVisible();
      const loaded = page.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          response.url().includes('/api/v1/dm/documents?'),
      );
      await next.click();
      await loaded;
      await expect(
        page.getByText('Loading drafts…', { exact: true }),
      ).toHaveCount(0);
    }
    await page.getByRole('button', { name: visibleTitle, exact: true }).click();
    await page.locator('input[type=file]').setInputFiles(fixture);
    await page
      .getByRole('button', { name: 'Upload and append', exact: true })
      .click();
    await expect(
      page.getByRole('alert').filter({
        hasText: 'Upload capacity is currently full. Wait before retrying.',
      }),
    ).toBeVisible();
    await page
      .getByRole('button', { name: 'Retry upload', exact: true })
      .click();
    await expect(
      page.getByRole('alert').filter({
        hasText: 'Upload capacity is currently full. Wait before retrying.',
      }),
    ).toBeVisible();
    // Login/session invalidation is also checked on every fixed mutation route.
    await page.goto('/identity');
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    for (const call of calls)
      await safeError(
        await page.request.fetch(call.path, {
          method: call.method,
          data: call.data,
          headers: {
            Origin: new URL(page.url()).origin,
            'X-CSRF-TOKEN': 'invalid',
            'Idempotency-Key': randomUUID(),
            'Content-Type': call.type,
          },
        }),
        401,
        'UNAUTHENTICATED',
      );
  });
}

if (profile === 'expiry') {
  test('real elapsed 60-minute slot/receipt expiry, held quota, immutable append replay and mapping', async ({
    page,
  }) => {
    test.setTimeout(4_500_000);
    const actor = await login(page),
      bytes = await readFile(fixture);
    await selectTenant(page, actor.tenants[0]!);
    const consumedDraft = await create(page);
    const consumedId = await admitted(page, consumedDraft, bytes);
    const consumedReceipt = await verified(page, consumedId, bytes);
    const appendKey = randomUUID(),
      appendBody = {
        expectedRevision: consumedDraft.revision,
        receiptId: consumedReceipt.receiptId,
      };
    const appendPath = `${base}/${consumedDraft.documentId}/versions`;
    const appended = await page.request.post(appendPath, {
      data: appendBody,
      headers: await headers(page, appendKey),
    });
    expect(appended.status()).toBe(201);
    const committed = (await appended.json()) as Draft;
    expect(Object.keys(committed).sort()).toEqual(['documentId', 'revision']);
    const mappingPath = `${base}/${consumedDraft.documentId}/upload-receipts/${consumedReceipt.receiptId}/version`;
    const mapping = await page.request.get(mappingPath);
    expect(mapping.status()).toBe(200);
    const originalMapping = (await mapping.json()) as {
      versionId: string;
      documentId: string;
      receiptId: string;
    };
    const drafts = await Promise.all(
      Array.from({ length: 5 }, () => create(page)),
    );
    const admittedAt = Date.now();
    const ids = [];
    for (const draft of drafts) ids.push(await admitted(page, draft, bytes));
    const heldDraft = drafts[0]!,
      heldId = ids[0]!;
    const original = await status(page, heldId);
    const heldReceipt = await verified(page, heldId, bytes);
    const hold = await page.request.post(
      `${base}/${heldDraft.documentId}/legal-holds`,
      {
        data: {
          expectedRevision: heldDraft.revision,
          reason: 'Synthetic expiry preservation acceptance.',
        },
        headers: await headers(page),
      },
    );
    expect(hold.status()).toBe(201);
    const heldRevision = ((await hold.json()) as Draft).revision;
    // Same command replay and fresh complete retries cannot extend the original expiry.
    const replay = await page.request.post(
      `${base}/uploads/${heldId}/complete`,
      { data: {}, headers: await headers(page, heldReceipt.completeKey) },
    );
    expect(replay.status()).toBe(200);
    expect(await replay.json()).toEqual({
      uploadId: heldId,
      receiptId: heldReceipt.receiptId,
    });
    expect((await status(page, heldId)).expiresAt).toBe(original.expiresAt);
    const deadlines = await Promise.all(ids.map((id) => status(page, id)));
    const until =
      Math.max(
        ...deadlines.map((slot) => Date.parse(slot.expiresAt)),
        Date.parse((await status(page, consumedId)).expiresAt),
      ) + 2_000;
    const started = Date.now();
    expect(until - admittedAt).toBeGreaterThanOrEqual(3_600_000);
    expect(until).toBeGreaterThan(started);
    // Real wall-clock wait. The selected realm/BFF expires credentials after five
    // minutes and has no refresh-token flow: observe denial and perform normal
    // browser OIDC login again. No clocks/rows/TTLs/session cookies are rewritten.
    let latestSession = await session(page),
      reauthentications = 0;
    const originalExpiries = new Map(
      deadlines.map((slot) => [slot.uploadId, slot.expiresAt]),
    );
    originalExpiries.set(
      consumedId,
      (await status(page, consumedId)).expiresAt,
    );
    const hiddenStatus = new Set<string>();
    renewExpiredSession.set(page, async (denied) => {
      await safeError(denied, 401, 'AUTHENTICATION_REQUIRED');
      await safeError(
        await page.request.get(`${base}/uploads/${heldId}/status`),
        401,
        'UNAUTHENTICATED',
      );
      await safeError(
        await page.request.post(`${base}/uploads/${heldId}/complete`, {
          data: {},
          headers: {
            Origin: new URL(page.url()).origin,
            'X-CSRF-TOKEN': latestSession.csrfToken,
            'Idempotency-Key': heldReceipt.completeKey,
          },
        }),
        401,
        'UNAUTHENTICATED',
      );
      const renewed = await login(page);
      expect(renewed.issuer).toBe(actor.issuer);
      expect(renewed.subject).toBe(actor.subject);
      expect(renewed.tenants).toContain(actor.tenants[0]!);
      expect(
        renewed.csrfToken !== latestSession.csrfToken,
        'A new authenticated session must issue new CSRF authority',
      ).toBe(true);
      await selectTenant(page, actor.tenants[0]!);
      latestSession = await session(page);
      expect(latestSession.selectedTenant).toBe(actor.tenants[0]!);
      for (const [id, expiresAt] of originalExpiries) {
        if (hiddenStatus.has(id))
          await safeError(
            await page.request.get(`${base}/uploads/${id}/status`),
            404,
            'NOT_FOUND',
          );
        else expect((await status(page, id)).expiresAt).toBe(expiresAt);
      }
      reauthentications++;
      return latestSession;
    });
    while (Date.now() < until) {
      await new Promise((resolve) =>
        setTimeout(resolve, Math.min(30_000, until - Date.now())),
      );
      latestSession = await session(page);
    }
    expect(reauthentications).toBeGreaterThan(0);
    expect(Date.now() - admittedAt).toBeGreaterThanOrEqual(3_600_000);
    expect(Date.now() - started).toBeGreaterThanOrEqual(until - started);
    for (const response of [
      await page.request.put(`${base}/uploads/${heldId}/content`, {
        data: bytes,
        headers: {
          ...(await headers(page, heldReceipt.bodyKey)),
          'Content-Type': 'application/octet-stream',
        },
      }),
      await page.request.post(`${base}/uploads/${heldId}/complete`, {
        data: {},
        headers: await headers(page, heldReceipt.completeKey),
      }),
      await page.request.post(`${base}/${heldDraft.documentId}/versions`, {
        data: {
          expectedRevision: heldRevision,
          receiptId: heldReceipt.receiptId,
        },
        headers: await headers(page),
      }),
    ])
      await safeError(response, 409, 'UPLOAD_EXPIRED');
    const expired = await status(page, heldId);
    expect(expired.expiresAt).toBe(original.expiresAt);
    expect(expired.state).toBe('EXPIRED');
    expect((await status(page, consumedId)).state).toBe('CONSUMED');
    const unchanged = await page.request.get(`${base}/${heldDraft.documentId}`);
    expect(unchanged.status()).toBe(200);
    expect(((await unchanged.json()) as Draft).revision).toBe(heldRevision);
    const sameAppend = await page.request.post(appendPath, {
      data: appendBody,
      headers: await headers(page, appendKey),
    });
    expect(sameAppend.status()).toBe(201);
    expect(await sameAppend.json()).toEqual(committed);
    const newAppend = await page.request.post(appendPath, {
      data: { ...appendBody, expectedRevision: committed.revision },
      headers: await headers(page),
    });
    await safeError(newAppend, 409, 'RECEIPT_CONSUMED');
    const afterMapping = await page.request.get(mappingPath);
    expect(afterMapping.status()).toBe(200);
    expect(await afterMapping.json()).toEqual(originalMapping);
    const content = await page.request.get(
      `${base}/${consumedDraft.documentId}/versions/${originalMapping.versionId}/content`,
      { headers: { Accept: 'application/pdf' } },
    );
    expect(content.status()).toBe(200);
    expect(await content.body()).toEqual(bytes);
    await denyAppend(page, committed, actor);
    hiddenStatus.add(consumedId);
    await safeError(
      await page.request.post(appendPath, {
        data: appendBody,
        headers: await headers(page, appendKey),
      }),
      404,
      'NOT_FOUND',
    );
    const independentlyAuthorizedMapping = await page.request.get(mappingPath);
    expect(independentlyAuthorizedMapping.status()).toBe(200);
    expect(await independentlyAuthorizedMapping.json()).toEqual(
      originalMapping,
    );
    // Actual scheduler cleanup may take several cycles. Four absent-object slots must
    // free, but the held verified original remains charged and blocks a fifth admission.
    const replacements = await Promise.all(
      Array.from({ length: 5 }, () => create(page)),
    );
    for (let index = 0; index < 4; index++) {
      const key = randomUUID();
      await expect
        .poll(
          async () => {
            const response = await initialize(
              page,
              replacements[index]!,
              bytes,
              key,
            );
            if (response.status() === 429)
              await safeError(response, 429, 'QUOTA_EXCEEDED');
            return response.status();
          },
          { timeout: 120_000, intervals: [2_000, 5_000, 10_000] },
        )
        .toBe(201);
    }
    await safeError(
      await initialize(page, replacements[4]!, bytes),
      429,
      'QUOTA_EXCEEDED',
    );
  });
}
