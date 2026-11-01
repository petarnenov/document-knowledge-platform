import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';

const prototypePath = new URL(
  '../../../docs/prototypes/DKP_Interactive_Design_v0.5.html',
  import.meta.url,
);
// Properties are compared exactly in the same Chromium process and viewport.
// Content-dependent heights are intentionally reported but not compared: live
// unavailable states cannot truthfully reproduce the prototype's fixture data.
const targets = {
  app: {
    selector: '.app',
    properties: ['x', 'width', 'borderLeftWidth', 'borderRightWidth'],
  },
  header: {
    selector: 'header',
    properties: [
      'x',
      'y',
      'width',
      'height',
      'padding',
      'gap',
      'backgroundColor',
      'color',
      'borderBottomColor',
    ],
  },
  workspace: {
    selector: '.workspace',
    properties: [
      'x',
      'y',
      'width',
      'height',
      'padding',
      'gap',
      'borderBottomColor',
    ],
  },
  sidebar: {
    selector: 'aside',
    properties: [
      'x',
      'y',
      'width',
      'padding',
      'backgroundColor',
      'color',
      'borderRightColor',
    ],
  },
  main: { selector: 'main', properties: ['x', 'y', 'width', 'padding'] },
  heading: {
    selector: 'h1',
    properties: ['fontSize', 'fontWeight', 'lineHeight', 'color'],
  },
  card: {
    selector: '.card',
    properties: [
      'padding',
      'borderRadius',
      'backgroundColor',
      'borderTopColor',
    ],
  },
} as const;

async function measurements(page: Page) {
  return page.evaluate(
    (definitions) =>
      Object.fromEntries(
        Object.entries(definitions).map(([name, { selector }]) => {
          const element = document.querySelector(selector);
          if (!element) throw new Error(`Missing visual target: ${selector}`);
          const rect = element.getBoundingClientRect();
          const css = getComputedStyle(element);
          return [
            name,
            {
              x: rect.x,
              y: rect.y,
              width: rect.width,
              height: rect.height,
              padding: css.padding,
              gap: css.gap,
              backgroundColor: css.backgroundColor,
              color: css.color,
              borderRadius: css.borderRadius,
              borderLeftWidth: css.borderLeftWidth,
              borderRightWidth: css.borderRightWidth,
              borderBottomColor: css.borderBottomColor,
              borderRightColor: css.borderRightColor,
              borderTopColor: css.borderTopColor,
              fontSize: css.fontSize,
              fontWeight: css.fontWeight,
              lineHeight: css.lineHeight,
            },
          ];
        }),
      ),
    targets,
  );
}

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 768, height: 1024 },
  { width: 390, height: 844 },
]) {
  for (const scheme of ['dark', 'light'] as const) {
    test(`prototype shell matches at ${viewport.width}px in ${scheme}`, async ({
      page,
      context,
    }, info) => {
      await page.setViewportSize(viewport);
      const response = await page.goto('/');
      expect(
        response?.status(),
        'Built portal document must load successfully',
      ).toBe(200);
      const theme = page.getByRole('switch', { name: 'Light theme' });
      await expect(theme).toBeVisible();
      if (
        (await theme.getAttribute('aria-checked')) !==
        String(scheme === 'light')
      )
        await theme.click();
      await expect(page.locator('html')).toHaveAttribute(
        'data-mantine-color-scheme',
        scheme,
      );
      await expect(
        page.getByRole('heading', { name: 'Knowledge workspace', exact: true }),
      ).toBeVisible();
      const reference = await context.newPage();
      try {
        await reference.setViewportSize(viewport);
        const html = await readFile(prototypePath, 'utf8');
        await reference.setContent(html, { waitUntil: 'load' });
        if (scheme === 'light') await reference.locator('#theme').click();
        await Promise.all([
          page.evaluate(() => document.fonts.ready),
          reference.evaluate(() => document.fonts.ready),
        ]);
        await page.evaluate(() => window.scrollTo(0, 0));
        await reference.evaluate(() => window.scrollTo(0, 0));
        const expected = await measurements(reference);
        const actual = await measurements(page);
        const deviations = Object.entries(targets).flatMap(
          ([name, { properties }]) =>
            properties.flatMap((property) => {
              const actualTarget = actual[name];
              const expectedTarget = expected[name];
              if (!actualTarget || !expectedTarget)
                throw new Error(`Missing measurement: ${name}`);
              return actualTarget[property] === expectedTarget[property]
                ? []
                : [
                    {
                      target: name,
                      property,
                      reference: expectedTarget[property],
                      react: actualTarget[property],
                    },
                  ];
            }),
        );
        await info.attach('reference', {
          body: await reference.screenshot({ fullPage: true }),
          contentType: 'image/png',
        });
        await info.attach('react', {
          body: await page.screenshot({ fullPage: true }),
          contentType: 'image/png',
        });
        await info.attach('measured-comparison', {
          body: JSON.stringify(
            {
              prototypeSHA256: createHash('sha256').update(html).digest('hex'),
              viewport,
              scheme,
              expected,
              actual,
              deviations,
              excluded:
                'Content-dependent heights, fixture totals, data and unsupported workflow content; no full-page pixel equivalence asserted.',
            },
            null,
            2,
          ),
          contentType: 'application/json',
        });
        expect(
          deviations,
          'Shared shell must match measured reference properties exactly; see attached evidence.',
        ).toEqual([]);
        await expect(page.locator('body')).toHaveJSProperty(
          'scrollWidth',
          viewport.width,
        );
        // Responsive icon-only navigation retains usable accessible names.
        for (const name of [
          'Home',
          'Documents',
          'Collections',
          'Reviews',
          'Search',
          'Operations',
          'Governance',
          'Audit',
        ]) {
          await expect(
            page
              .getByRole('navigation', { name: 'Main navigation' })
              .getByRole('link', { name, exact: true }),
          ).toBeVisible();
        }
        await theme.focus();
        await page.keyboard.press('Space');
        await expect(theme).toHaveAttribute(
          'aria-checked',
          String(scheme !== 'light'),
        );
      } finally {
        await reference.close();
      }
    });
  }
}

// These DTOs exist only in Playwright routing. They are not production data or
// evidence that a real identity/provider accepted a command.
const fixtureSession = {
  issuer: 'https://identity.example.test/realms/dkp',
  subject: 'visual-user',
  displayName: 'Visual Test User',
  tenants: ['Acme Enterprise'],
  selectedTenant: 'Acme Enterprise',
  csrfToken: 'visual-test-csrf',
};
const fixtureDocument = {
  documentId: '00000000-0000-4000-8000-000000000004',
  tenant: fixtureSession.selectedTenant,
  creator: { issuer: fixtureSession.issuer, subject: fixtureSession.subject },
  createdAt: '2026-11-01T00:00:00Z',
  revision: 0,
  lifecycle: 'DRAFT',
  metadata: { title: 'Quarterly Budget Summary', description: '' },
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
const uploadId = '00000000-0000-4000-8000-000000000005';
const receiptId = '00000000-0000-4000-8000-000000000006';
const versionId = '00000000-0000-4000-8000-000000000007';
const featureDifferences = [
  'Real UUID and development-restricted classification replace illustrative ID/taxonomy/collection metadata.',
  'Latest version remains unknown; draft active pointer is null. No processing, approval or publication is inferred.',
  'Draft title/description remain writable; collection is unavailable and classification is fixed. Exact control borders/primary text have separately recorded accessibility adjustments.',
  'Real file/format fields and server confirmation hints replace fixture selector/simulation controls. Card content heights and resulting downstream positions differ.',
];
const boxProperties = [
  'margin-top',
  'margin-bottom',
  'x',
  'width',
  'padding',
  'border-radius',
  'background-color',
  'border-top-color',
];
const textProperties = [
  'font-size',
  'font-weight',
  'line-height',
  'color',
  'margin-top',
  'margin-bottom',
];

type VisualPair = {
  name: string;
  reference: string;
  react: string;
  properties: readonly string[];
};
async function featureEvidence(
  page: Page,
  reference: Page,
  info: import('@playwright/test').TestInfo,
  stage: string,
  pairs: VisualPair[],
) {
  await Promise.all([
    page.evaluate(() => window.scrollTo(0, 0)),
    reference.evaluate(() => window.scrollTo(0, 0)),
  ]);
  const comparison = [];
  for (const pair of pairs) {
    async function measure(target: Page, selector: string) {
      return target
        .locator(selector)
        .first()
        .evaluate(
          (element, properties) => {
            const rect = element.getBoundingClientRect();
            const css = getComputedStyle(element);
            return Object.fromEntries(
              properties.map((property) => [
                property,
                property === 'x'
                  ? rect.x
                  : property === 'width'
                    ? rect.width
                    : property === 'height'
                      ? rect.height
                      : css.getPropertyValue(property),
              ]),
            );
          },
          [...pair.properties],
        );
    }
    const expected = await measure(reference, pair.reference);
    const actual = await measure(page, pair.react);
    comparison.push({
      name: pair.name,
      reference: expected,
      react: actual,
      deviations: pair.properties
        .filter((property) => expected[property] !== actual[property])
        .map((property) => ({
          property,
          reference: expected[property],
          react: actual[property],
        })),
    });
  }
  await info.attach(`${stage}-reference`, {
    body: await reference.screenshot({ fullPage: true }),
    contentType: 'image/png',
  });
  await info.attach(`${stage}-react`, {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png',
  });
  await info.attach(`${stage}-comparison`, {
    body: JSON.stringify(
      {
        stage,
        comparableFixture: fixtureDocument.metadata.title,
        comparison,
        requiredContentDifferences: featureDifferences,
        scope:
          'Exact selected geometry/style comparisons; no pixel equivalence or real-provider acceptance claim.',
      },
      null,
      2,
    ),
    contentType: 'application/json',
  });
  expect
    .soft(
      comparison.flatMap((pair) =>
        pair.deviations.map((deviation) => ({
          target: pair.name,
          ...deviation,
        })),
      ),
      `${stage}: exact comparable feature metrics`,
    )
    .toEqual([]);
}

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 768, height: 1024 },
  { width: 390, height: 844 },
]) {
  for (const scheme of ['dark', 'light'] as const) {
    test(`document feature reference journey at ${viewport.width}px in ${scheme}`, async ({
      page,
      context,
    }, info) => {
      let consumed = false;
      const unexpected: string[] = [];
      await page.route('**/api/v1/**', async (route) => {
        const request = route.request(),
          path = new URL(request.url()).pathname;
        if (request.method() !== 'GET') {
          expect(request.headers()['x-csrf-token']).toBe(
            fixtureSession.csrfToken,
          );
          expect(request.headers()['idempotency-key']).toBeTruthy();
          expect(request.headers()['authorization']).toBeUndefined();
        }
        if (path === '/api/v1/session')
          return route.fulfill({ json: fixtureSession });
        const root = '/api/v1/dm/documents';
        if (path === root && request.method() === 'GET')
          return route.fulfill({
            json: {
              items: [{ ...fixtureDocument, revision: consumed ? 1 : 0 }],
              nextCursor: null,
            },
          });
        if (path === `${root}/${fixtureDocument.documentId}`)
          return route.fulfill({
            json: { ...fixtureDocument, revision: consumed ? 1 : 0 },
          });
        if (path === `${root}/uploads` && request.method() === 'POST') {
          expect(request.postDataJSON()).toMatchObject({
            documentId: fixtureDocument.documentId,
            expectedRevision: 0,
            format: 'PDF',
          });
          return route.fulfill({ status: 201, json: { uploadId } });
        }
        if (
          path === `${root}/uploads/${uploadId}/content` &&
          request.method() === 'PUT'
        )
          return route.fulfill({ json: { uploadId } });
        if (
          path === `${root}/uploads/${uploadId}/complete` &&
          request.method() === 'POST'
        )
          return route.fulfill({ json: { uploadId, receiptId } });
        if (
          path === `${root}/${fixtureDocument.documentId}/versions` &&
          request.method() === 'POST'
        ) {
          expect(request.postDataJSON()).toEqual({
            expectedRevision: 0,
            receiptId,
          });
          consumed = true;
          return route.fulfill({
            status: 201,
            json: { documentId: fixtureDocument.documentId, revision: 1 },
          });
        }
        if (
          path ===
          `${root}/${fixtureDocument.documentId}/upload-receipts/${receiptId}/version`
        )
          return route.fulfill({
            json: { documentId: fixtureDocument.documentId, versionId },
          });
        if (path === `${root}/uploads/${uploadId}/status`)
          return route.fulfill({
            json: {
              uploadId,
              state: consumed ? 'CONSUMED' : 'INITIALIZED',
              expiresAt: '2026-11-02T00:00:00Z',
              failureCode: null,
            },
          });
        unexpected.push(`${request.method()} ${path}`);
        return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } });
      });
      await page.setViewportSize(viewport);
      expect((await page.goto('/documents'))?.status()).toBe(200);
      const theme = page.getByRole('switch', { name: 'Light theme' });
      await expect(theme).toBeVisible();
      if (
        (await theme.getAttribute('aria-checked')) !==
        String(scheme === 'light')
      )
        await theme.click();
      const reference = await context.newPage();
      try {
        await reference.setViewportSize(viewport);
        await reference.setContent(await readFile(prototypePath, 'utf8'));
        if (scheme === 'light') await reference.locator('#theme').click();
        await reference.locator('[data-nav="documents"]').click();
        await reference.locator('#filter').fill('Quarterly Budget Summary');
        await expect(
          page.getByRole('button', {
            name: fixtureDocument.metadata.title,
            exact: true,
          }),
        ).toBeVisible();
        const common: VisualPair[] = [
          {
            name: 'breadcrumb',
            reference: '.breadcrumbs',
            react: '.breadcrumbs',
            properties: textProperties,
          },
          {
            name: 'heading',
            reference: 'h1',
            react: 'h1',
            properties: ['x', 'width', ...textProperties],
          },
          {
            name: 'card',
            reference: '.card',
            react: 'main .card',
            properties: boxProperties,
          },
        ];
        await featureEvidence(page, reference, info, 'library', [
          ...common,
          {
            name: 'new-document-primary',
            reference: '[data-action="create"]',
            react: '.document-toolbar button',
            properties: [
              'width',
              'height',
              'padding',
              'border-radius',
              'font-size',
              'background-color',
            ],
          },
          {
            name: 'filter',
            reference: '#filter',
            react: 'input[aria-label="Search documents on this page"]',
            properties: ['height', 'padding', 'border-radius', 'font-size'],
          },
        ]);
        await page.getByRole('button', { name: 'New document' }).click();
        await reference.locator('[data-action="create"]').click();
        await featureEvidence(page, reference, info, 'draft', [
          ...common,
          {
            name: 'create-primary',
            reference: '[data-action="submitDraft"]',
            react: 'button[type="submit"]',
            properties: [
              'width',
              'height',
              'padding',
              'border-radius',
              'font-size',
              'background-color',
            ],
          },
          {
            name: 'title-label',
            reference: 'label:has(+ #draftTitle)',
            react: 'label[for="draft-title"]',
            properties: textProperties,
          },
          {
            name: 'title-input',
            reference: '#draftTitle',
            react: '#draft-title',
            properties: [
              'width',
              'height',
              'padding',
              'border-radius',
              'font-size',
            ],
          },
        ]);
        await expect(
          page.getByRole('combobox', { name: 'Collection (metadata only)' }),
        ).toBeDisabled();
        await expect(
          page.getByRole('textbox', { name: 'Classification', exact: true }),
        ).toHaveValue('development-restricted');
        await expect(
          page.getByRole('textbox', { name: 'Classification', exact: true }),
        ).toHaveAttribute('readonly', '');
        await page
          .getByRole('textbox', { name: 'Title', exact: true })
          .fill(fixtureDocument.metadata.title);
        await expect(
          page.getByRole('button', { name: 'Create draft', exact: true }),
        ).toBeEnabled();
        await page
          .getByRole('button', { name: 'Back to library', exact: true })
          .click();
        await page
          .getByRole('button', {
            name: fixtureDocument.metadata.title,
            exact: true,
          })
          .click();
        await reference.locator('[data-nav="documents"]').click();
        await reference.locator('[data-open-doc="DKP-004"]').click();
        await featureEvidence(page, reference, info, 'detail', [
          ...common,
          {
            name: 'upload-primary',
            reference: '[data-action="upload"]',
            react: 'button:has-text("Upload version")',
            properties: [
              'width',
              'height',
              'padding',
              'border-radius',
              'font-size',
              'background-color',
            ],
          },
          {
            name: 'unavailable-preview',
            reference: '[data-action="openPreview"]',
            react: 'button:has-text("Preview")',
            properties: [
              'opacity',
              'cursor',
              'height',
              'padding',
              'border-radius',
              'font-size',
            ],
          },
        ]);
        await expect(
          page.getByText('Version history is unavailable.'),
        ).toBeVisible();
        await expect(
          page.getByRole('button', { name: 'Processing', exact: true }),
        ).toBeDisabled();
        await page
          .getByRole('button', { name: 'Upload version', exact: true })
          .click();
        await reference.locator('[data-action="upload"]').click();
        const uploadPairs: VisualPair[] = [
          ...common,
          {
            name: 'step',
            reference: '.step',
            react: '.step',
            properties: ['padding', 'gap'],
          },
          {
            name: 'stepmark',
            reference: '.stepmark',
            react: '.stepmark',
            properties: [
              'width',
              'height',
              'border-radius',
              'font-size',
              'color',
            ],
          },
        ];
        await featureEvidence(
          page,
          reference,
          info,
          'upload-initial',
          uploadPairs,
        );
        expect
          .soft(
            await page.getByLabel('Upload lifecycle').innerText(),
            'Step status remains visually separated from its label',
          )
          .toMatch(/Transfer\s+Not confirmed/);
        await page.locator('input[type="file"]').setInputFiles({
          name: 'visual-test-only.pdf',
          mimeType: 'application/pdf',
          buffer: Buffer.from('%PDF-1.7\nSynthetic visual fixture only\n%%EOF'),
        });
        await page
          .getByRole('button', { name: 'Upload and append', exact: true })
          .click();
        await expect(
          page.getByText(`Version ${versionId}`, { exact: true }),
        ).toBeVisible();
        for (let step = 0; step < 4; step++)
          await reference.locator('[data-action="nextUpload"]').click();
        await expect(page.locator('.stepmark.done')).toHaveCount(4);
        await featureEvidence(
          page,
          reference,
          info,
          'upload-recorded',
          uploadPairs,
        );
        expect(unexpected).toEqual([]);
        await expect(page.getByRole('alert')).toHaveCount(0);
        await expect(page.locator('body')).toHaveJSProperty(
          'scrollWidth',
          viewport.width,
        );
      } finally {
        await reference.close();
      }
    });
  }
}
