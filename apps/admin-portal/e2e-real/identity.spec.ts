import { expect, test } from '@playwright/test';

test('real HTTPS provider login, tenant selection, independent contexts and local logout', async ({
  page,
  context,
  baseURL,
}) => {
  const origin = new URL(baseURL!).origin;
  await page.goto('/identity');
  await page.getByRole('link', { name: 'Sign in', exact: true }).click();
  await expect(page.locator('#username')).toBeVisible();
  await page.locator('#username').fill(process.env.DKP_IDENTITY_USERNAME!);
  await page.locator('#password').fill(process.env.DKP_IDENTITY_PASSWORD!);
  await page.locator('#kc-login').click();
  // The callback is also same-origin. Wait for its final redirect so a new
  // navigation cannot abort the code exchange or session-cookie persistence.
  await page.waitForURL((url) => url.origin === origin && url.pathname === '/');
  const authenticated = await page.request.get('/api/v1/session');
  expect(
    authenticated.status(),
    'BFF session status after final login redirect',
  ).toBe(200);
  const initialSession = (await authenticated.json()) as Record<
    string,
    unknown
  >;
  expect(
    Object.hasOwn(initialSession, 'selectedTenant'),
    'Session contract includes nullable selectedTenant',
  ).toBe(true);
  await page.goto('/identity');
  await expect(
    page.getByRole('heading', { name: /^Signed in as / }),
  ).toBeVisible();

  const cookie = (await context.cookies()).find(
    (item) => item.name === '__Host-DKP-SESSION',
  );
  expect(Boolean(cookie)).toBe(true);
  expect(cookie?.secure).toBe(true);
  expect(cookie?.httpOnly).toBe(true);
  expect(cookie?.sameSite).toBe('Lax');
  expect(cookie?.path).toBe('/');
  expect(cookie?.domain).toBe(new URL(baseURL!).hostname);

  const selector = page.getByRole('combobox', { name: 'Tenant' });
  const tenant = await selector.locator('option').nth(1).getAttribute('value');
  expect(Boolean(tenant)).toBe(true);
  await selector.selectOption(tenant!);
  await page.getByRole('button', { name: 'Use tenant' }).click();
  for (const [service, label] of [
    ['dm', 'Document Management'],
    ['kb', 'Knowledge Base'],
    ['search', 'Knowledge Search'],
  ] as const) {
    await page.getByRole('button', { name: `Check ${label} context` }).click();
    await expect(page.getByLabel('Verified context')).toContainText(service);
    await expect(page.getByLabel('Verified context')).toContainText(tenant!);
  }

  // No routes or business results are mocked: these requests reach the real DM/PG/catalog.
  await page.getByRole('link', { name: 'Documents', exact: true }).click();
  const title = `Synthetic draft ${Date.now()}`;
  await page.getByLabel('Title', { exact: true }).fill(title);
  await page
    .getByLabel('Description', { exact: true })
    .fill('Synthetic metadata; no document content uploaded.');
  const createdResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith('/api/v1/dm/documents') &&
      response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Create draft', exact: true }).click();
  const created = await createdResponse;
  expect(created.status()).toBe(201);
  const receipt = (await created.json()) as {
    documentId: string;
    revision: number;
  };
  expect(Object.keys(receipt).sort()).toEqual(['documentId', 'revision']);
  const root = `/api/v1/dm/documents/${receipt.documentId}`;
  await expect(
    page.getByRole('button', { name: 'Create draft', exact: true }),
  ).toBeEnabled();
  // An earlier run can leave more than one visible page. Follow authenticated cursors only.
  for (
    let pages = 0;
    pages < 20 &&
    (await page.getByRole('button', { name: title, exact: true }).count()) ===
      0;
    pages++
  ) {
    const next = page.getByRole('button', { name: 'Next page', exact: true });
    if ((await next.count()) === 0) break;
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
  await page.getByRole('button', { name: title, exact: true }).click();
  await expect(
    page.getByRole('heading', { name: title, exact: true }),
  ).toBeVisible();
  await page
    .getByRole('button', { name: 'Use current metadata', exact: true })
    .click();
  const updatedTitle = `${title} updated`;
  await page.getByLabel('Title', { exact: true }).fill(updatedTitle);
  await page
    .getByRole('button', { name: 'Save metadata', exact: true })
    .click();
  await expect(
    page.getByRole('heading', { name: updatedTitle, exact: true }),
  ).toBeVisible();
  await page
    .getByRole('button', { name: 'Inspect access rules', exact: true })
    .click();
  await expect(page.getByLabel('Access rules', { exact: true })).toBeVisible();
  const selfReadRule = {
    principal: {
      issuer: initialSession.issuer,
      subject: initialSession.subject,
    },
    action: 'document.read',
    effect: 'ALLOW',
  };
  const readAcl = async () => {
    const response = await page.request.get(`${root}/acl`);
    expect(response.status()).toBe(200);
    return (await response.json()) as { revision: number; rules: unknown[] };
  };
  const initialAcl = await readAcl();
  expect(initialAcl.revision).toBe(1);
  expect(initialAcl.rules).not.toContainEqual(selfReadRule);
  await page.getByLabel('Access mode').selectOption('DELEGATE');
  await page.getByLabel('Rule operation').selectOption('ADD_ALLOW');
  await page.getByLabel('Document action').selectOption('document.read');
  await page
    .getByRole('button', { name: 'Apply access change', exact: true })
    .click();
  await expect(
    page.getByRole('status').filter({ hasText: 'Saved document' }),
  ).toContainText('revision 2');
  const delegatedAcl = await readAcl();
  expect(delegatedAcl.revision).toBe(2);
  expect(delegatedAcl.rules).toContainEqual(selfReadRule);
  const selfReadText = `${String(initialSession.issuer)} / ${String(initialSession.subject)}: document.read ALLOW`;
  const visibleSelfRead = page
    .getByLabel('Access rules', { exact: true })
    .getByRole('listitem')
    .filter({ hasText: selfReadText });
  await expect(visibleSelfRead).toHaveCount(1);
  await page.getByLabel('Access mode').selectOption('RESTRICT');
  await page.getByLabel('Rule operation').selectOption('REMOVE_ALLOW');
  await page.getByLabel('Document action').selectOption('document.read');
  await page
    .getByRole('button', { name: 'Apply access change', exact: true })
    .click();
  await expect(
    page.getByRole('status').filter({ hasText: 'Saved document' }),
  ).toContainText('revision 3');
  const restrictedAcl = await readAcl();
  expect(restrictedAcl.revision).toBe(3);
  expect(restrictedAcl.rules).not.toContainEqual(selfReadRule);
  await expect(visibleSelfRead).toHaveCount(0);
  await page.getByLabel('Hold reason').fill('Synthetic preservation exercise');
  await page.getByRole('button', { name: 'Place hold', exact: true }).click();
  await expect(
    page.getByRole('status').filter({ hasText: 'Saved document' }),
  ).toContainText('revision 4');
  await page
    .getByRole('button', { name: 'Inspect legal holds', exact: true })
    .click();
  await expect(page.getByLabel('Legal holds', { exact: true })).toContainText(
    'Synthetic preservation exercise',
  );
  const current = (await (
    await page.request.get('/api/v1/session')
  ).json()) as { csrfToken: string };
  const mutationBody = {
    expectedRevision: 4,
    metadata: { title: 'Rejected', description: '' },
  };
  for (const headers of [
    { 'X-CSRF-TOKEN': current.csrfToken },
    { Origin: origin },
  ]) {
    const deniedMutation = await page.request.patch(root, {
      data: mutationBody,
      headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
    });
    expect(deniedMutation.status()).toBe(403);
  }
  const append = await page.request.post(`${root}/versions`, {
    data: { expectedRevision: 4, receiptId: 'synthetic-unverified' },
    headers: {
      Origin: origin,
      'X-CSRF-TOKEN': current.csrfToken,
      'Idempotency-Key': crypto.randomUUID(),
    },
  });
  expect(append.status()).toBe(503);
  expect(await append.json()).toEqual({ code: 'UNAVAILABLE' });
  const unchanged = await page.request.get(root);
  expect(((await unchanged.json()) as { revision: number }).revision).toBe(4);
  await page
    .getByRole('link', { name: 'Workspace access', exact: true })
    .first()
    .click();

  const sessionResponse = await page.request.get('/api/v1/session');
  expect(sessionResponse.status()).toBe(200);
  const safeKeys = Object.keys((await sessionResponse.json()) as object).sort();
  expect(safeKeys).toEqual([
    'csrfToken',
    'displayName',
    'issuer',
    'selectedTenant',
    'subject',
    'tenants',
  ]);
  expect(
    await page.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
  expect(
    await page.evaluate(() => document.cookie.includes('__Host-DKP-SESSION')),
  ).toBe(false);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(
    page.getByRole('link', { name: 'Sign in', exact: true }),
  ).toBeVisible();
  const denied = await page.request.get('/api/v1/session');
  expect(denied.status()).toBe(401);
  await page.getByRole('link', { name: 'Documents', exact: true }).click();
  await expect(
    page.getByText('Sign in and choose a tenant to manage drafts.'),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { name: updatedTitle, exact: true }),
  ).toHaveCount(0);
});
