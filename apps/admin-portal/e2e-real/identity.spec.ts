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
});
