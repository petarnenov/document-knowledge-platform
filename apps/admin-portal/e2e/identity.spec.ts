import { expect, test } from '@playwright/test';

const session = {
  issuer: 'https://idp.example/realms/dkp',
  subject: 'browser-user',
  displayName: 'Browser User',
  tenants: ['tenant-a', 'tenant-b'],
  selectedTenant: null as string | null,
  csrfToken: 'csrf-initial',
};

test('identity controls use the same-origin guarded session contract', async ({
  page,
}) => {
  let current = { ...session };
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith('/tenant')) {
      expect(request.method()).toBe('POST');
      expect(request.headers()['x-csrf-token']).toBe('csrf-initial');
      expect(request.headers()['authorization']).toBeUndefined();
      expect(request.postDataJSON()).toEqual({ tenantId: 'tenant-b' });
      current = {
        ...current,
        selectedTenant: 'tenant-b',
        csrfToken: 'csrf-rotated',
      };
    } else if (path.endsWith('/logout')) {
      expect(request.method()).toBe('POST');
      expect(request.headers()['x-csrf-token']).toBe('csrf-rotated');
      await route.fulfill({ status: 204 });
      return;
    } else if (path.includes('/context/')) {
      await route.fulfill({
        json: {
          service: path.split('/').at(-1),
          issuer: session.issuer,
          subject: session.subject,
          tenant: current.selectedTenant,
        },
      });
      return;
    }
    await route.fulfill({ json: current });
  });
  await page.goto('/identity');
  await expect(
    page.getByRole('heading', { name: 'Signed in as Browser User' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Check Document Management context' }),
  ).toBeDisabled();
  await page.getByRole('combobox', { name: 'Tenant' }).selectOption('tenant-b');
  await page.getByRole('button', { name: 'Use tenant' }).click();
  for (const [service, label] of [
    ['dm', 'Document Management'],
    ['kb', 'Knowledge Base'],
    ['search', 'Knowledge Search'],
  ] as const) {
    await page.getByRole('button', { name: `Check ${label} context` }).click();
    await expect(page.getByLabel('Verified context')).toContainText(service);
    await expect(page.getByLabel('Verified context')).toContainText('tenant-b');
  }
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(
    page.getByRole('link', { name: 'Sign in', exact: true }),
  ).toHaveAttribute('href', '/oauth/login/keycloak');
  expect(
    await page.evaluate(() => [localStorage.length, sessionStorage.length]),
  ).toEqual([0, 0]);
});

test('an expired session removes verified context and offers reauthentication', async ({
  page,
}) => {
  await page.route('**/api/v1/session', (route) =>
    route.fulfill({ json: { ...session, selectedTenant: 'tenant-a' } }),
  );
  await page.route('**/api/v1/security/context/*', (route) =>
    route.fulfill({ status: 401, json: { code: 'expired' } }),
  );
  await page.goto('/identity');
  await page
    .getByRole('button', { name: 'Check Document Management context' })
    .click();
  await expect(page.getByRole('alert')).toHaveText(
    'Your session has expired. Sign in again to continue.',
  );
  await expect(
    page.getByRole('link', { name: 'Sign in', exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel('Verified context')).toHaveCount(0);
});
