import { randomUUID } from 'node:crypto';
import {
  expect,
  test,
  type APIResponse,
  type Page,
  type Request,
  type Response,
} from '@playwright/test';
import {
  KnowledgeDiagnostics,
  KnowledgeBodyObservations,
  type KnowledgeUiState,
} from './knowledge-diagnostics';
import { ExactMembershipTraversal } from './knowledge-acceptance';
import {
  dmFixtureBody,
  dmFixtureReceipt,
  requireKbMirrorPath,
} from './knowledge-dm-fixture';
import {
  collectionSchema,
  collectionReceiptSchema,
  membershipReceiptSchema,
  membershipPageSchema,
  policySnapshotSchema,
  errorSchema,
} from '../src/knowledge-schema';
const diagnostics = new WeakMap<
  Page,
  { ring: KnowledgeDiagnostics; dispose: () => void }
>();
test.beforeEach(({ page }) => {
  const ring = new KnowledgeDiagnostics();
  const starts = new WeakMap<Request, number>();
  const bodies = new KnowledgeBodyObservations();
  const request = (value: Request) => {
    starts.set(value, performance.now());
  };
  const elapsed = (value: Request) => {
    const at = starts.get(value);
    return at === undefined ? null : performance.now() - at;
  };
  const failed = (value: Request) => {
    ring.observe(value.method(), value.url(), 'FAILED', elapsed(value));
  };
  const response = (value: Response) => {
    const entry = ring.observe(
      value.request().method(),
      value.url(),
      value.status(),
      elapsed(value.request()),
    );
    // Observe only tiny, declared error responses. Never await diagnostics in
    // a business assertion or retain/print the received bytes.
    const responseHeaders = value.headers();
    const length = responseHeaders['content-length'];
    if (
      !entry ||
      value.status() < 400 ||
      responseHeaders['content-encoding'] ||
      responseHeaders['content-type']?.split(';')[0]?.trim().toLowerCase() !==
        'application/json' ||
      !length ||
      !/^[0-9]{1,4}$/.test(length) ||
      Number(length) > 1024
    )
      return;
    bodies.observe(() => value.body(), entry);
  };
  page.on('request', request);
  page.on('requestfailed', failed);
  page.on('response', response);
  diagnostics.set(page, {
    ring,
    dispose: () => {
      bodies.dispose();
      page.off('request', request);
      page.off('requestfailed', failed);
      page.off('response', response);
    },
  });
});
test.afterEach(async ({ page }, info) => {
  const observed = diagnostics.get(page);
  if (!observed) return;
  try {
    observed.dispose();
  } catch {
    /* Diagnostics never replace the test outcome. */
  }
  if (info.status === info.expectedStatus) return;
  let ui: KnowledgeUiState = {
    selection: 'unobserved',
    settings: 'unobserved',
    alert: 'unobserved',
  };
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const snapshot = async (): Promise<KnowledgeUiState> => {
      const selection = await page
        .getByRole('region', { name: 'Selected collection', exact: true })
        .count();
      const settings = await page
        .getByRole('button', { name: 'Collection settings', exact: true })
        .count();
      const alert = page.getByRole('alert');
      const categories = [
        ['session', 'Your session has expired. Sign in again.'],
        ['forbidden', 'This knowledge action is not permitted.'],
        [
          'not-found',
          'This collection or policy is unavailable or you do not have access.',
        ],
        [
          'conflict',
          'The knowledge state changed. Refresh before trying again.',
        ],
        ['busy', 'Knowledge services are busy. Try again later.'],
        [
          'rejected',
          'The request was rejected. Check the fields and current permissions.',
        ],
        ['unavailable', 'Knowledge services are unavailable. Try again.'],
      ] as const;
      let category: KnowledgeUiState['alert'] = (await alert.count())
        ? 'other'
        : 'none';
      for (const [name, text] of categories)
        if (await alert.filter({ hasText: text }).count()) {
          category = name;
          break;
        }
      return {
        selection: selection ? 'present' : 'absent',
        settings: settings ? 'present' : 'absent',
        alert: category,
      };
    };
    ui = await Promise.race([
      snapshot(),
      new Promise<KnowledgeUiState>((resolve) => {
        timer = setTimeout(() => resolve(ui), 200);
      }),
    ]);
  } catch {
    /* Preserve the original failure even when the page already closed. */
  } finally {
    clearTimeout(timer);
    console.error(JSON.stringify(observed.ring.failure(ui)));
  }
});
function phase(page: Page, value: Parameters<KnowledgeDiagnostics['mark']>[0]) {
  diagnostics.get(page)?.ring.mark(value);
}
const enabled = process.env.DKP_KB_E2E_ENABLED;
if (enabled !== undefined && enabled !== 'true')
  throw new Error('Knowledge acceptance requires explicit true profile.');
// This explicit isolated KB profile never substitutes for legacy DM/upload acceptance.
if (enabled === 'true')
  for (const scheme of ['dark', 'light'] as const) {
    test(`real HTTPS ${scheme} complete knowledge policy workflow`, async ({
      page,
      baseURL,
    }) => {
      test.setTimeout(180000);
      const origin = new URL(baseURL!).origin;
      expect(new URL(origin).protocol).toBe('https:');
      await page.addInitScript(
        ({ theme, portal }) => {
          if (location.origin === portal)
            localStorage.setItem('mantine-color-scheme', theme);
        },
        { theme: scheme, portal: origin },
      );
      phase(page, 'login');
      await page.goto('/identity');
      await page.getByRole('link', { name: 'Sign in', exact: true }).click();
      await expect(page.locator('#username')).toBeVisible();
      await page.locator('#username').fill(process.env.DKP_IDENTITY_USERNAME!);
      await page.locator('#password').fill(process.env.DKP_IDENTITY_PASSWORD!);
      await page.locator('#kc-login').click();
      await page.waitForURL(
        (url) => url.origin === origin && url.pathname === '/',
      );
      await page.goto('/identity');
      await expect(
        page.getByRole('heading', { name: /^Signed in as / }),
      ).toBeVisible();
      const tenant = await page
        .getByRole('combobox', { name: 'Tenant' })
        .locator('option')
        .nth(1)
        .getAttribute('value');
      expect(tenant).toBeTruthy();
      await page
        .getByRole('combobox', { name: 'Tenant' })
        .selectOption(tenant!);
      const selection = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/session/tenant' &&
          r.request().method() === 'POST',
      );
      await page.getByRole('button', { name: 'Use tenant' }).click();
      expect((await selection).status()).toBe(200);
      const sessionResponse = await page.request.get('/api/v1/session');
      expect(sessionResponse.status()).toBe(200);
      const session = (await sessionResponse.json()) as {
        csrfToken: string;
        issuer: string;
        subject: string;
        selectedTenant: string;
      };
      expect(session.selectedTenant).toBe(tenant);
      const guarded = (key: string = randomUUID()) => ({
        Origin: origin,
        'X-CSRF-TOKEN': session.csrfToken,
        'Idempotency-Key': key,
      });
      const createdDraft = await page.request.post('/api/v1/dm/documents', {
        headers: guarded(),
        data: {
          metadata: {
            title: `Synthetic KB attachment ${randomUUID()}`,
            description: 'Metadata-only public acceptance fixture.',
          },
          classification: 'development-restricted',
          classificationPolicyRevision: 1,
        },
      });
      expect(createdDraft.status()).toBe(201);
      const draft = (await createdDraft.json()) as {
        documentId: string;
        revision: number;
      };
      expect(Object.keys(draft).sort()).toEqual(['documentId', 'revision']);
      phase(page, 'open-knowledge');
      await page.getByRole('link', { name: 'Knowledge', exact: true }).click();
      await expect(
        page
          .getByRole('region', { name: 'Accessible collections' })
          .getByRole('list'),
      ).toHaveCount(1);
      await expect(
        page.getByRole('switch', { name: 'Light theme' }),
      ).toBeChecked({ checked: scheme === 'light' });
      phase(page, 'create-collection');
      const title = `Synthetic ${scheme} collection ${randomUUID()}`;
      await page.getByLabel('New collection title').fill(title);
      await page
        .getByLabel('New collection description')
        .fill('Public synthetic knowledge acceptance.');
      const original = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/kb/collections' &&
          r.request().method() === 'POST',
      );
      await page
        .getByRole('button', { name: 'Create collection', exact: true })
        .click();
      const created = await original;
      expect(created.status()).toBe(201);
      const sent = created.request();
      const replay = await page.request.post('/api/v1/kb/collections', {
        headers: {
          ...guarded(sent.headers()['idempotency-key']!),
          'Content-Type': 'application/json',
        },
        data: sent.postDataJSON() as object,
      });
      expect(replay.status()).toBe(201);
      const receipt = (await replay.json()) as {
        collectionId: string;
        revision: number;
      };
      expect(Object.keys(receipt).sort()).toEqual(['collectionId', 'revision']);
      await page.getByRole('button', { name: title, exact: true }).click();
      const selected = page.getByRole('region', {
        name: 'Selected collection',
      });
      await expect(selected).toBeVisible();
      await page
        .getByLabel('Change reason')
        .fill('Synthetic acceptance change');
      await page.getByRole('button', { name: 'Load current metadata' }).click();
      await page
        .getByLabel('Updated collection title')
        .fill(`${title} updated`);
      let response = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname ===
            `/api/v1/kb/collections/${receipt.collectionId}` &&
          r.request().method() === 'PATCH',
      );
      await page
        .getByRole('button', { name: 'Save collection metadata' })
        .click();
      expect((await response).status()).toBe(200);
      await expect(
        selected.getByRole('heading', { name: `${title} updated` }),
      ).toBeVisible();
      await page
        .getByRole('button', { name: 'Audience policy', exact: true })
        .click();
      await expect(page.getByText(/Policy revision 1/)).toBeVisible();
      await page.getByRole('button', { name: 'Add audience rule' }).click();
      response = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname.endsWith('/audience') &&
          r.request().method() === 'PUT',
      );
      await page
        .getByRole('button', { name: 'Replace audience policy' })
        .click();
      expect((await response).status()).toBe(200);
      await page
        .getByRole('button', { name: 'Evaluate my audience access' })
        .click();
      await expect(
        page.getByText(/Your audience decision is ALLOW/),
      ).toBeVisible();
      await page
        .getByRole('button', { name: 'Memberships', exact: true })
        .click();
      await expect(
        page.getByText('No accessible memberships on this page.'),
      ).toBeVisible();
      await page.getByLabel('Document ID to attach').fill(draft.documentId);
      await page
        .getByLabel('Current document revision')
        .fill(String(draft.revision));
      response = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname.endsWith('/memberships') &&
          r.request().method() === 'POST',
      );
      await page.getByRole('button', { name: 'Attach document' }).click();
      expect((await response).status()).toBe(201);
      await expect(
        page.getByRole('button', {
          name: `Deactivate membership ${draft.documentId}`,
        }),
      ).toBeVisible();
      response = page.waitForResponse(
        (r) =>
          /\/memberships\/[0-9a-f-]+$/.test(new URL(r.url()).pathname) &&
          r.request().method() === 'PATCH',
      );
      phase(page, 'deactivate-member');
      await page
        .getByRole('button', {
          name: `Deactivate membership ${draft.documentId}`,
        })
        .click();
      expect((await response).status()).toBe(200);
      await expect(
        page.getByRole('button', {
          name: `Activate membership ${draft.documentId}`,
        }),
      ).toBeVisible();
      phase(page, 'reactivate-member');
      const reactivation = page.waitForResponse(
        (r) =>
          /\/memberships\/[0-9a-f-]+$/.test(new URL(r.url()).pathname) &&
          r.request().method() === 'PATCH',
      );
      const currentMembers = page.waitForResponse(
        async (r) =>
          new URL(r.url()).pathname.endsWith('/memberships') &&
          r.request().method() === 'GET' &&
          r.request().timing().startTime >=
            (await reactivation).request().timing().startTime,
      );
      await page
        .getByRole('button', {
          name: `Activate membership ${draft.documentId}`,
        })
        .click();
      expect((await reactivation).status()).toBe(200);
      const refreshed = await currentMembers;
      expect(refreshed.status()).toBe(200);
      const refreshedBody = (await refreshed.json()) as {
        items: { documentId: string; state: string; revision: number }[];
      };
      expect(
        refreshedBody.items.find(
          (member) => member.documentId === draft.documentId,
        ),
      ).toMatchObject({ state: 'ACTIVE', revision: 2 });
      await expect(
        page.getByRole('button', {
          name: `Deactivate membership ${draft.documentId}`,
        }),
      ).toBeEnabled();
      phase(page, 'open-settings');
      await page
        .getByRole('button', { name: 'Collection settings', exact: true })
        .click();
      phase(page, 'archive');
      await page.getByRole('button', { name: 'Archive collection' }).click();
      await expect(
        page.getByRole('button', { name: 'Unarchive collection' }),
      ).toBeVisible();
      await page.getByRole('button', { name: 'Unarchive collection' }).click();
      await expect(
        page.getByRole('button', { name: 'Archive collection' }),
      ).toBeVisible();
      await page
        .getByRole('button', { name: 'Create policy snapshot' })
        .click();
      await expect(
        page.getByText(/Complete policy decision: COMPATIBLE/),
      ).toBeVisible();
      response = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname.endsWith('/policy-validations') &&
          r.request().method() === 'POST',
      );
      await page
        .getByRole('button', { name: 'Validate current policy snapshot' })
        .click();
      expect((await response).status()).toBe(200);
      expect(await page.locator('body').innerText()).not.toContain(
        'snapshotToken',
      );
      // Browser-selected actor/tenant overrides and missing CSRF never become API authority.
      expect(
        (
          await page.request.get('/api/v1/kb/collections', {
            headers: { 'X-DKP-Tenant': 'foreign' },
          })
        ).status(),
      ).toBe(400);
      expect(
        (
          await page.request.post('/api/v1/kb/collections', {
            headers: { Origin: origin },
            data: { title: 'Rejected', description: '' },
          })
        ).status(),
      ).toBe(403);
      await page
        .getByRole('button', { name: 'Collection ACL', exact: true })
        .click();
      await expect(
        page.getByRole('heading', { name: 'Current collection ACL' }),
      ).toBeVisible();
      await page.getByRole('button', { name: 'Add ACL change' }).click();
      response = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname.endsWith('/acl') &&
          r.request().method() === 'PATCH',
      );
      await page.getByRole('button', { name: 'Apply ACL changes' }).click();
      expect((await response).status()).toBe(200);
      await expect(
        page.getByRole('region', { name: 'Selected collection' }),
      ).toHaveCount(0);
      expect(
        (
          await page.request.get(
            `/api/v1/kb/collections/${receipt.collectionId}`,
          )
        ).status(),
      ).toBe(404);
      await expect(
        page.getByRole('button', { name: 'Validate current policy snapshot' }),
      ).toBeDisabled();
      await page
        .getByRole('link', { name: 'Workspace access', exact: true })
        .click();
      await page.getByRole('button', { name: 'Sign out', exact: true }).click();
      await page.getByRole('link', { name: 'Knowledge', exact: true }).click();
      await expect(
        page.getByText('Sign in and choose a tenant to manage knowledge.'),
      ).toBeVisible();
      await expect(
        page.getByText(`${title} updated`, { exact: true }),
      ).toHaveCount(0);
    });
  }

if (enabled === 'true')
  test('real HTTPS 100-member complete-set current authority and private pagination', async ({
    page,
    baseURL,
  }) => {
    // Many independent genuine requests; this is not an increase to any single-operation budget.
    test.setTimeout(600_000);
    const origin = new URL(baseURL!).origin;
    expect(new URL(origin).protocol).toBe('https:');
    await page.goto('/identity');
    await page.getByRole('link', { name: 'Sign in', exact: true }).click();
    await page.locator('#username').fill(process.env.DKP_IDENTITY_USERNAME!);
    await page.locator('#password').fill(process.env.DKP_IDENTITY_PASSWORD!);
    await page.locator('#kc-login').click();
    await page.waitForURL(
      (url) => url.origin === origin && url.pathname === '/',
    );
    await page.goto('/identity');
    await expect(
      page.getByRole('heading', { name: /^Signed in as / }),
    ).toBeVisible();
    const tenant = await page
      .getByRole('combobox', { name: 'Tenant' })
      .locator('option')
      .nth(1)
      .getAttribute('value');
    expect(tenant).toBeTruthy();
    await page.getByRole('combobox', { name: 'Tenant' }).selectOption(tenant!);
    const selected = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/session/tenant' &&
        r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Use tenant' }).click();
    expect((await selected).status()).toBe(200);
    const identityResponse = await page.request.get('/api/v1/session');
    expect(identityResponse.status()).toBe(200);
    const identity = (await identityResponse.json()) as {
      csrfToken: string;
      issuer: string;
      subject: string;
      selectedTenant: string;
    };
    expect(identity.selectedTenant).toBe(tenant);
    const headers = (key: string = randomUUID()) => ({
      Origin: origin,
      'X-CSRF-TOKEN': identity.csrfToken,
      'Idempotency-Key': key,
    });
    const timings: number[] = [];
    async function request(
      method: 'GET' | 'POST' | 'PATCH',
      path: string,
      status: number,
      data?: object,
      key?: string,
    ) {
      requireKbMirrorPath(path);
      const started = performance.now();
      const response = await page.request.fetch(path, {
        method,
        headers: headers(key),
        ...(data === undefined ? {} : { data }),
        timeout: 12_000,
      });
      const bytes = await response.body();
      const elapsed = performance.now() - started;
      timings.push(elapsed);
      // Observable HTTPS whole latency only, including TLS/proxy/browser overhead; no internal phase claim.
      expect(elapsed).toBeLessThan(9000);
      expect(response.status()).toBe(status);
      expect(response.headers()['cache-control']).toBe('no-store');
      expect(response.headers()['content-encoding']).toBeUndefined();
      expect(response.headers()['content-type']).toMatch(
        /^application\/json(?:;|$)/,
      );
      expect(bytes.length).toBeLessThanOrEqual(2 * 1024 * 1024);
      return JSON.parse(bytes.toString()) as unknown;
    }
    async function dmFixture(
      method: 'POST' | 'PATCH',
      path: string,
      data: object,
    ) {
      const body = dmFixtureBody(method, path, data);
      const response = await page.request.fetch(path, {
        method,
        headers: { ...headers(), 'Content-Type': 'application/json' },
        data: body,
        timeout: 10_000,
      });
      return dmFixtureReceipt(
        response.status(),
        method === 'POST' ? 201 : 200,
        response.headers(),
        await response.body(),
      );
    }
    async function rejected(
      method: 'GET' | 'POST' | 'PATCH',
      path: string,
      status: number,
      code: string,
      data?: object,
      key?: string,
    ) {
      expect(
        errorSchema.parse(await request(method, path, status, data, key)),
      ).toEqual({ code });
    }
    const root = '/api/v1/kb/collections';
    const created = collectionReceiptSchema.parse(
      await request('POST', root, 201, {
        title: `Synthetic maximum ${randomUUID()}`,
        description: 'Public synthetic maximum complete-set acceptance.',
      }),
    );
    const base = `${root}/${created.collectionId}`;
    let collection = collectionSchema.parse(await request('GET', base, 200));
    const activeKey = randomUUID();
    const activeBody = {
      expectedRevision: collection.revision,
      status: 'ACTIVE',
      reason: 'Original ACTIVE before any membership.',
    };
    const activeReceipt = collectionReceiptSchema.parse(
      await request('PATCH', `${base}/status`, 200, activeBody, activeKey),
    );
    collection = collectionSchema.parse(await request('GET', base, 200));
    expect(collection.revision).toBe(activeReceipt.revision);
    const documents: { documentId: string; revision: number }[] = [];
    for (let index = 0; index < 101; index++) {
      const draft = (await dmFixture('POST', '/api/v1/dm/documents', {
        metadata: {
          title: `Synthetic maximum member ${index} ${randomUUID()}`,
          description: 'Metadata only; no content trust claim.',
        },
        classification: 'development-restricted',
        classificationPolicyRevision: 1,
      })) as { documentId: string; revision: number };
      expect(Object.keys(draft).sort()).toEqual(['documentId', 'revision']);
      expect(draft.documentId).toMatch(/^[0-9a-f-]{36}$/);
      expect(draft.revision).toBeGreaterThanOrEqual(0);
      documents.push(draft);
      if (index < 100) {
        const receipt = membershipReceiptSchema.parse(
          await request('POST', `${base}/memberships`, 201, {
            expectedRevision: collection.revision,
            documentId: draft.documentId,
            expectedDocumentRevision: draft.revision,
            sharingScope: 'SELECTED_TENANT',
            reason: 'Synthetic exact maximum member.',
          }),
        );
        expect(receipt.collectionId).toBe(created.collectionId);
        collection = collectionSchema.parse(await request('GET', base, 200));
        expect(collection.revision).toBe(receipt.revision);
      }
    }
    const complete = new Set(documents.slice(0, 100).map((d) => d.documentId));
    const fullBefore = collection;
    await rejected('POST', `${base}/memberships`, 422, 'LIMIT_EXCEEDED', {
      expectedRevision: collection.revision,
      documentId: documents[100]!.documentId,
      expectedDocumentRevision: documents[100]!.revision,
      sharingScope: 'SELECTED_TENANT',
      reason: 'Must not exceed the exact maximum.',
    });
    expect(collectionSchema.parse(await request('GET', base, 200))).toEqual(
      fullBefore,
    );
    async function traverse(allowed: ReadonlySet<string>, limit?: number) {
      const observed = new ExactMembershipTraversal(
        created.collectionId,
        allowed,
        limit ?? 20,
      );
      let cursor: string | null = null;
      let first = true;
      do {
        const query = new URLSearchParams();
        if (limit !== undefined) query.set('limit', String(limit));
        if (cursor !== null) query.set('cursor', cursor);
        const parsed = membershipPageSchema.parse(
          await request(
            'GET',
            `${base}/memberships${query.size ? `?${query}` : ''}`,
            200,
          ),
        );
        if (first)
          expect(parsed.items).toHaveLength(
            Math.min(limit ?? 20, allowed.size),
          );
        first = false;
        cursor = observed.accept(parsed);
      } while (cursor !== null);
      expect(observed.count()).toBe(allowed.size);
    }
    for (const limit of [1, undefined, 50]) await traverse(complete, limit);
    for (const limit of ['0', '51', '-1', '01', '1.5'])
      await rejected(
        'GET',
        `${base}/memberships?limit=${limit}`,
        400,
        'BAD_REQUEST',
      );
    const previousPage = membershipPageSchema.parse(
      await request('GET', `${base}/memberships?limit=1`, 200),
    );
    expect(previousPage.nextCursor).not.toBeNull();
    const changed = collectionReceiptSchema.parse(
      await request('PATCH', base, 200, {
        expectedRevision: collection.revision,
        metadata: {
          title: `Synthetic changed maximum ${randomUUID()}`,
          description: collection.metadata.description,
        },
      }),
    );
    await rejected(
      'GET',
      `${base}/memberships?limit=1&cursor=${encodeURIComponent(previousPage.nextCursor!)}`,
      409,
      'STALE_POLICY',
    );
    collection = collectionSchema.parse(await request('GET', base, 200));
    expect(collection.revision).toBe(changed.revision);
    await traverse(complete, 50);
    // A new ACTIVE no-op must inspect all100; original ACTIVE replay must inspect the CURRENT set.
    const noOpKey = randomUUID();
    const noOpBody = {
      expectedRevision: collection.revision,
      status: 'ACTIVE',
      reason: 'Maximum-size current complete-set no-op.',
    };
    const noOpReceipt = collectionReceiptSchema.parse(
      await request('PATCH', `${base}/status`, 200, noOpBody, noOpKey),
    );
    collection = collectionSchema.parse(await request('GET', base, 200));
    expect(collection.revision).toBe(noOpReceipt.revision);
    expect(
      collectionReceiptSchema.parse(
        await request('PATCH', `${base}/status`, 200, activeBody, activeKey),
      ),
    ).toEqual(activeReceipt);
    expect(collectionSchema.parse(await request('GET', base, 200))).toEqual(
      collection,
    );
    const target = documents[0]!;
    const snapshotPath = `/api/v1/kb/documents/${target.documentId}/policy-snapshots`;
    const snapshot = policySnapshotSchema.parse(
      await request('POST', snapshotPath, 201, {
        expectedDocumentRevision: target.revision,
      }),
    );
    expect(snapshot.documentId).toBe(target.documentId);
    const beforeAuthorityChange = membershipPageSchema.parse(
      await request('GET', `${base}/memberships?limit=1`, 200),
    );
    expect(beforeAuthorityChange.nextCursor).not.toBeNull();
    // Deny only sharing: metadata inspection stays allowed, so no hidden resource status is guessed.
    const denied = (await dmFixture(
      'PATCH',
      `/api/v1/dm/documents/${target.documentId}/acl`,
      {
        expectedRevision: target.revision,
        mode: 'RESTRICT',
        changes: [
          {
            operation: 'ADD_DENY',
            principal: { issuer: identity.issuer, subject: identity.subject },
            action: 'document.share-to-collection',
          },
        ],
      },
    )) as { documentId: string; revision: number };
    expect(Object.keys(denied).sort()).toEqual(['documentId', 'revision']);
    await rejected(
      'GET',
      `${base}/memberships?limit=1&cursor=${encodeURIComponent(beforeAuthorityChange.nextCursor!)}`,
      409,
      'STALE_POLICY',
    );
    const visible = new Set(
      [...complete].filter((id) => id !== target.documentId),
    );
    await traverse(visible);
    await traverse(visible, 50);
    const beforeDenials = collectionSchema.parse(
      await request('GET', base, 200),
    );
    await rejected(
      'PATCH',
      `${base}/status`,
      404,
      'NOT_FOUND',
      activeBody,
      activeKey,
    );
    await rejected(
      'PATCH',
      `${base}/status`,
      404,
      'NOT_FOUND',
      noOpBody,
      noOpKey,
    );
    await rejected('PATCH', `${base}/status`, 404, 'NOT_FOUND', {
      expectedRevision: beforeDenials.revision,
      status: 'ACTIVE',
      reason: 'Current denied member must abort complete no-op.',
    });
    await rejected('POST', snapshotPath, 404, 'NOT_FOUND', {
      expectedDocumentRevision: denied.revision,
    });
    await rejected(
      'POST',
      `/api/v1/kb/documents/${target.documentId}/policy-validations`,
      404,
      'NOT_FOUND',
      {
        expectedDocumentRevision: denied.revision,
        snapshotToken: snapshot.snapshotToken,
      },
    );
    expect(collectionSchema.parse(await request('GET', base, 200))).toEqual(
      beforeDenials,
    );
    const assertGuardError = async (
      response: APIResponse,
      status: number,
      code: string,
    ) => {
      expect(response.status()).toBe(status);
      expect(errorSchema.parse(await response.json())).toEqual({ code });
    };
    await assertGuardError(
      await page.request.get(`${base}/memberships`, {
        headers: { 'X-DKP-Tenant': 'foreign' },
      }),
      400,
      'BAD_REQUEST',
    );
    await assertGuardError(
      await page.request.patch(`${base}/status`, {
        headers: { Origin: origin },
        data: {
          expectedRevision: beforeDenials.revision,
          status: 'ACTIVE',
          reason: 'Missing CSRF.',
        },
      }),
      403,
      'FORBIDDEN',
    );
    await assertGuardError(
      await page.request.patch(`${base}/status`, {
        headers: { ...headers(), Origin: 'https://foreign.example' },
        data: {
          expectedRevision: beforeDenials.revision,
          status: 'ACTIVE',
          reason: 'Foreign origin.',
        },
      }),
      403,
      'FORBIDDEN',
    );
    await traverse(visible, 50);
    // Public-safe summary only: no resource IDs, cursors, tokens, authority rows or request bodies.
    console.info(
      JSON.stringify({
        acceptance: 'KB_MAXIMUM_CURRENT_AUTHORITY',
        documents: 101,
        members: 100,
        visibleAfterRevocation: 99,
        measuredHttpsRequests: timings.length,
        maximumObservedWholeMilliseconds: Math.ceil(Math.max(...timings)),
        internalPhaseTimingClaim: false,
      }),
    );
  });
