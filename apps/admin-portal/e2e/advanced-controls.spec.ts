import { expect, test } from '@playwright/test';

// Supplemental browser lifecycle regression. Fixtures are never backend proof.
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
  creator: { issuer: session.issuer, subject: session.subject },
  createdAt: '2026-10-31T10:00:00Z',
  revision: 0,
  lifecycle: 'DRAFT',
  metadata: {
    title: 'Synthetic advanced regression',
    description: 'Synthetic metadata',
  },
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

for (const theme of ['dark', 'light']) {
  for (const kind of ['metadata', 'acl', 'hold']) {
    test(`${theme} advanced controls remain expanded after ${kind} reauthorization`, async ({
      page,
    }) => {
      let revision = 0;
      let resume: (() => void) | undefined;
      const commands: { method: string; path: string; body: unknown }[] = [];
      await page.addInitScript(
        (value) => localStorage.setItem('mantine-color-scheme', value),
        theme,
      );
      await page.route('**/api/v1/**', async (route) => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        const respond = (value: unknown, status = 200) =>
          route.fulfill({
            status,
            contentType: 'application/json',
            body: JSON.stringify(value),
          });
        if (path === '/api/v1/session') return respond(session);
        if (request.method() !== 'GET') {
          commands.push({
            method: request.method(),
            path,
            body: request.postDataJSON(),
          });
          expect(request.headers()['x-csrf-token']).toBe(session.csrfToken);
          expect(request.headers()['idempotency-key']).toMatch(
            /^[0-9a-f-]{36}$/,
          );
          revision++;
          return respond(
            { documentId: id, revision },
            request.method() === 'POST' ? 201 : 200,
          );
        }
        if (path === '/api/v1/dm/documents')
          return respond({ items: [{ ...draft, revision }], nextCursor: null });
        expect(path).toBe(`/api/v1/dm/documents/${id}`);
        if (revision > 0)
          await new Promise<void>((resolve) => {
            resume = resolve;
          });
        return respond({ ...draft, revision });
      });
      await page.goto(`/documents?documentId=${id}`);
      const advanced = page
        .locator('details')
        .filter({
          has: page.locator('summary', {
            hasText: 'Advanced document controls',
          }),
        });
      await expect(advanced).toHaveJSProperty('open', false);
      await advanced.locator('summary').click();
      await expect(advanced).toHaveJSProperty('open', true);
      if (kind === 'metadata') {
        await page
          .getByRole('button', { name: 'Use current metadata', exact: true })
          .click();
        await page
          .getByRole('button', { name: 'Save metadata', exact: true })
          .click();
      } else if (kind === 'acl') {
        await page
          .getByRole('button', { name: 'Apply access change', exact: true })
          .click();
      } else {
        await page
          .getByLabel('Hold reason', { exact: true })
          .fill('Synthetic preservation');
        await page
          .getByRole('button', { name: 'Place hold', exact: true })
          .click();
      }
      await expect(
        page.getByText('Checking draft access…', { exact: true }),
      ).toBeVisible();
      await expect(advanced).toHaveCount(0);
      await expect.poll(() => typeof resume).toBe('function');
      resume!();
      await expect(advanced).toHaveJSProperty('open', true);
      await expect(
        page.getByRole('button', { name: 'Inspect access rules', exact: true }),
      ).toBeVisible();
      await expect(
        page.getByRole('button', { name: 'Inspect legal holds', exact: true }),
      ).toBeVisible();
      expect(commands).toHaveLength(1);
      expect(commands[0]?.method).toBe(kind === 'hold' ? 'POST' : 'PATCH');
      expect(commands[0]?.path).toBe(
        `/api/v1/dm/documents/${id}${kind === 'acl' ? '/acl' : kind === 'hold' ? '/legal-holds' : ''}`,
      );
      expect(commands[0]?.body).toMatchObject({ expectedRevision: 0 });
      await advanced.locator('summary').click();
      await expect(advanced).toHaveJSProperty('open', false);
    });
  }
}
