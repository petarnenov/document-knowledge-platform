import { readFile } from 'node:fs/promises';
import { expect, test, type Locator, type Page } from '@playwright/test';

// Mocked BFF fixtures prove browser behavior, never real backend authorization.
const session = {
  issuer: 'https://idp.example/realms/dkp',
  subject: 'synthetic-browser',
  displayName: 'Synthetic browser account',
  tenants: ['tenant-a', 'tenant-b'],
  selectedTenant: 'tenant-a',
  csrfToken: 'synthetic-a',
};
const replacement = {
  ...session,
  selectedTenant: 'tenant-b',
  csrfToken: 'synthetic-b',
};
const draft = (tenant: string) => ({
  documentId: '11111111-1111-4111-8111-111111111111',
  tenant,
  creator: { issuer: session.issuer, subject: session.subject },
  createdAt: '2026-11-01T00:00:00Z',
  revision: 0,
  lifecycle: 'DRAFT',
  metadata: { title: `Synthetic ${tenant} draft`, description: '' },
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
});
const selector = (page: Page) =>
  page.getByRole('combobox', { name: 'Workspace (verified membership)' });
async function measure(control: Locator) {
  return control.evaluate((element) => {
    const rect = element.getBoundingClientRect(),
      css = getComputedStyle(element);
    return {
      x: rect.x,
      y: rect.y,
      width: rect.width,
      height: rect.height,
      padding: css.padding,
      borderRadius: css.borderRadius,
      fontSize: css.fontSize,
      backgroundColor: css.backgroundColor,
      color: css.color,
    };
  });
}
for (const width of [1440, 390])
  for (const scheme of ['dark', 'light'] as const) {
    test(`mock BFF keyboard selector matches reference ${width}px ${scheme}`, async ({
      page,
      context,
    }, info) => {
      await page.setViewportSize({ width, height: 1000 });
      let current = session,
        posts = 0,
        reads = 0;
      let release!: () => void;
      const pending = new Promise<void>((resolve) => {
        release = resolve;
      });
      await page.route('**/api/v1/**', async (route) => {
        const request = route.request(),
          path = new URL(request.url()).pathname;
        if (path === '/api/v1/session/tenant') {
          posts++;
          expect(request.method()).toBe('POST');
          expect(request.headers()['x-csrf-token']).toBe(session.csrfToken);
          expect(request.headers()['authorization']).toBeUndefined();
          expect(request.postDataJSON()).toEqual({ tenantId: 'tenant-b' });
          await pending;
          current = replacement;
          await route.fulfill({ json: current });
        } else if (path === '/api/v1/session')
          await route.fulfill({ json: current });
        else if (path === '/api/v1/dm/documents') {
          reads++;
          await route.fulfill({
            json: { items: [draft(current.selectedTenant)], nextCursor: null },
          });
        } else
          throw new Error(
            `Unexpected mocked operation: ${request.method()} ${path}`,
          );
      });
      await page.goto('/documents');
      await expect(
        page.getByRole('button', { name: /Synthetic tenant-a draft/ }),
      ).toBeVisible();
      const toggle = page.getByRole('switch', { name: 'Light theme' });
      if (
        (await toggle.getAttribute('aria-checked')) !==
        String(scheme === 'light')
      )
        await toggle.click();
      await expect(page.locator('html')).toHaveAttribute(
        'data-mantine-color-scheme',
        scheme,
      );
      const control = selector(page);
      await expect(control.locator('option')).toHaveText(session.tenants);
      const reference = await context.newPage();
      try {
        await reference.setViewportSize({ width, height: 1000 });
        await reference.setContent(
          await readFile(
            new URL(
              '../../../docs/prototypes/DKP_Interactive_Design_v0.5.html',
              import.meta.url,
            ),
            'utf8',
          ),
        );
        if (scheme === 'light') await reference.locator('#theme').click();
        await Promise.all([
          page.evaluate(() => document.fonts.ready),
          reference.evaluate(() => document.fonts.ready),
        ]);
        const expected = await measure(reference.locator('#workspace')),
          actual = await measure(control);
        await info.attach('selector-reference-comparison.json', {
          body: JSON.stringify({ expected, actual }, null, 2),
          contentType: 'application/json',
        });
        expect(actual).toEqual(expected);
        await reference.screenshot({
          path: info.outputPath('prototype.png'),
          fullPage: true,
        });
      } finally {
        await reference.close();
      }
      await page.screenshot({
        path: info.outputPath('portal-before.png'),
        fullPage: true,
      });
      await control.focus();
      // Native type-ahead is keyboard selection; macOS headless arrow popup keys
      // do not select even on a standalone HTML select. No synthetic change event.
      await page.keyboard.type('tenant-b');
      await expect.poll(() => posts).toBe(1);
      await expect(control).toBeDisabled();
      await expect(control).toHaveValue('tenant-a');
      await expect(page.getByRole('status')).toHaveText(
        'Updating workspace access…',
      );
      await expect(
        page.getByRole('button', { name: /Synthetic tenant-a draft/ }),
      ).toHaveCount(0);
      expect(reads).toBe(1);
      release();
      await expect(control).toHaveValue('tenant-b');
      await expect(control).toBeEnabled();
      await expect(
        page.getByRole('button', { name: /Synthetic tenant-b draft/ }),
      ).toBeVisible();
      await expect(
        page.getByRole('button', { name: /Synthetic tenant-a draft/ }),
      ).toHaveCount(0);
      await expect(page).toHaveURL(/\/documents$/);
      expect(posts).toBe(1);
      expect(reads).toBe(2);
      await page.screenshot({
        path: info.outputPath('portal-after.png'),
        fullPage: true,
      });
    });
  }
for (const recovery of ['fresh', '401'] as const) {
  test(`mock BFF unknown outcome fences unchanged session until ${recovery} recovery`, async ({
    page,
  }) => {
    let gets = 0,
      posts = 0,
      reads = 0,
      recover = false;
    await page.route('**/api/v1/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === '/api/v1/session/tenant') {
        posts++;
        await route.fulfill({ status: 503, json: {} });
      } else if (path === '/api/v1/session') {
        gets++;
        if (recover && recovery === '401')
          await route.fulfill({ status: 401, json: {} });
        else await route.fulfill({ json: recover ? replacement : session });
      } else if (path === '/api/v1/dm/documents') {
        reads++;
        await route.fulfill({
          json: {
            items: [draft(recover ? 'tenant-b' : 'tenant-a')],
            nextCursor: null,
          },
        });
      } else throw new Error(`Unexpected mocked operation: ${path}`);
    });
    await page.goto('/documents');
    await expect(
      page.getByRole('button', { name: /Synthetic tenant-a draft/ }),
    ).toBeVisible();
    await selector(page).selectOption('tenant-b');
    await expect(
      page.getByRole('heading', { name: 'Verify your workspace access' }),
    ).toBeVisible();
    await expect.poll(() => gets).toBe(2);
    await expect(selector(page)).toBeDisabled();
    await expect(
      page.getByRole('button', { name: /Synthetic tenant-a draft/ }),
    ).toHaveCount(0);
    await expect(
      page.getByRole('link', { name: 'Sign in again', exact: true }),
    ).toHaveAttribute('href', '/oauth/login/keycloak');
    expect(reads).toBe(1);
    expect(posts).toBe(1);
    recover = true;
    await page
      .getByRole('button', { name: 'Retry session verification' })
      .click();
    if (recovery === 'fresh') {
      await expect(selector(page)).toHaveValue('tenant-b');
      await expect(selector(page)).toBeEnabled();
      await expect(
        page.getByRole('button', { name: /Synthetic tenant-b draft/ }),
      ).toBeVisible();
      expect(reads).toBe(2);
    } else {
      await expect(selector(page)).toBeDisabled();
      await expect(selector(page).locator('option')).toHaveText('Signed out');
      await expect(
        page.getByRole('button', { name: /Synthetic tenant-[ab] draft/ }),
      ).toHaveCount(0);
      expect(reads).toBe(1);
    }
    await expect(page).toHaveURL(/\/documents$/);
    expect(posts).toBe(1);
  });
}
