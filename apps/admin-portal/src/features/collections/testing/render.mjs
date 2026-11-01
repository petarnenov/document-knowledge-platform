/* global process, document, getComputedStyle */
import { strict as assert } from 'node:assert';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { URL } from 'node:url';
import { chromium } from '@playwright/test';
import { createServer } from 'vite';

// Independent ephemeral localhost Vite; never the manual preview or an existing server.
const output = resolve(
  process.env.DKP_COLLECTIONS_VISUAL_OUTPUT ??
    '/private/tmp/dkp446-collections',
);
await mkdir(output, { recursive: true });
const server = await createServer({
  server: { host: '127.0.0.1', port: 0, strictPort: true },
});
let browser;
const results = [];
try {
  await server.listen();
  const address = server.httpServer.address();
  assert(address && typeof address !== 'string');
  const origin = `http://127.0.0.1:${address.port}`;
  browser = await chromium.launch({ headless: true });
  for (const width of [1440, 390]) {
    for (const theme of ['dark', 'light']) {
      for (const state of [
        'available',
        'unavailable',
        'unknown',
        'denied',
        'conflict',
      ]) {
        const page = await browser.newPage({
          viewport: { width, height: 1000 },
        });
        const apiRequests = [];
        page.on('request', (request) => {
          if (new URL(request.url()).pathname.startsWith('/api/'))
            apiRequests.push(request.url());
        });
        const errors = [];
        page.on('pageerror', (error) => errors.push(error.message));
        await page.goto(
          `${origin}/src/features/collections/testing/harness.html?theme=${theme}&state=${state}`,
        );
        await page
          .getByRole('heading', { name: 'Knowledge Collections', exact: true })
          .waitFor();
        await page.evaluate(() => document.fonts.ready);
        assert.deepEqual(apiRequests, []);
        assert.deepEqual(errors, []);
        const metrics = await page
          .locator('.visual-stage')
          .evaluate((stage) => {
            const card = stage.querySelector('.card');
            const style = getComputedStyle(card);
            return {
              radius: style.borderRadius,
              padding: style.padding,
              scrollWidth: document.documentElement.scrollWidth,
              clientWidth: document.documentElement.clientWidth,
            };
          });
        assert.equal(metrics.radius, '13px');
        assert.equal(metrics.padding, '17px');
        assert(
          metrics.scrollWidth <= metrics.clientWidth,
          'Page must not overflow horizontally',
        );
        for (const button of await page.getByRole('button').all()) {
          if (
            !(await button.getAttribute('aria-label'))?.startsWith(
              'Select collection ',
            )
          )
            assert(await button.isDisabled());
        }
        assert.equal(await page.getByRole('link').count(), 0);
        if (state === 'available') {
          await page
            .getByRole('button', { name: 'Select collection Engineering' })
            .click();
          await page
            .getByRole('heading', { name: 'Engineering', exact: true })
            .waitFor();
          await page
            .getByRole('button', {
              name: 'Select collection Corporate Policies',
            })
            .click();
          await page
            .getByRole('heading', { name: 'Corporate Policies', exact: true })
            .waitFor();
        } else {
          const notices = await page.getByRole('status').allTextContents();
          assert.equal(notices.length, 3);
          assert(
            notices.every((notice) => notice === notices[0]),
            'Every nested notice must preserve the parent boundary',
          );
          assert.equal(
            await page
              .getByRole('heading', { name: 'Corporate Policies', exact: true })
              .count(),
            0,
          );
        }
        const file = `collections-${state}-${theme}-${width}.png`;
        await page
          .locator('.visual-stage')
          .screenshot({ path: join(output, file) });
        results.push({
          file,
          width,
          theme,
          state,
          metrics,
          apiRequests,
          pageErrors: errors,
        });
        await page.close();
      }
    }
  }
  const fingerprints = {};
  for (const file of [
    'CollectionsWorkspace.tsx',
    'collection-view.ts',
    'testing/harness.tsx',
    'testing/harness.css',
  ]) {
    const bytes = await readFile(resolve('src/features/collections', file));
    fingerprints[file] = createHash('sha256').update(bytes).digest('hex');
  }
  await writeFile(
    join(output, 'report.json'),
    JSON.stringify(
      {
        proof:
          'Supplemental test-fixture presentation only; no backend or integrated shared-theme acceptance.',
        fingerprints,
        results,
      },
      null,
      2,
    ),
  );
  process.stdout.write(
    `PASS ${results.length} isolated Collections presentation journeys; evidence ${output}\n`,
  );
} finally {
  await browser?.close();
  await server.close();
}
