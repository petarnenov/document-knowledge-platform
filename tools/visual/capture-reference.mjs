import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(root, 'apps/admin-portal/package.json'));
const { chromium } = require('@playwright/test');
export function referenceMeasurements() {
  return Object.fromEntries(
    [
      '.app',
      'header',
      '.workspace',
      'aside',
      'main',
      'h1',
      '.card',
      'button',
      'input',
      'select',
    ].map((selector) => {
      const element = document.querySelector(selector);
      if (!element) return [selector, null];
      const rect = element.getBoundingClientRect(),
        css = getComputedStyle(element);
      return [
        selector,
        {
          x: rect.x,
          y: rect.y,
          width: rect.width,
          height: rect.height,
          padding: css.padding,
          gap: css.gap,
          font: css.font,
          fontSize: css.fontSize,
          background: css.backgroundColor,
          color: css.color,
          border: css.border,
          borderRadius: css.borderRadius,
        },
      ];
    }),
  );
}

async function captureReference(output) {
  if (!output)
    throw new Error(
      'Usage: node tools/visual/capture-reference.mjs <new-evidence-directory>',
    );
  // A fresh explicit destination prevents replacing previously reviewed evidence.
  await mkdir(resolve(output), { mode: 0o700 });
  const prototype = resolve(
    root,
    'docs/prototypes/DKP_Interactive_Design_v0.5.html',
  );
  const captures = [];
  const browser = await chromium.launch();
  try {
    for (const viewport of [
      { width: 1440, height: 1000 },
      { width: 768, height: 1024 },
      { width: 390, height: 844 },
    ]) {
      for (const theme of ['dark', 'light']) {
        const page = await browser.newPage({ viewport, deviceScaleFactor: 1 });
        try {
          await page.goto(pathToFileURL(prototype).href);
          if (theme === 'light') await page.locator('#theme').click();
          async function shot(name) {
            await page.evaluate(() => window.scrollTo(0, 0));
            const file = `${name}-${theme}-${viewport.width}.png`;
            await page.screenshot({
              path: resolve(output, file),
              fullPage: true,
            });
            captures.push({
              name,
              theme,
              viewport,
              file,
              heading: await page.locator('h1').textContent(),
              measurements: await page.evaluate(referenceMeasurements),
            });
          }
          await shot('home');
          for (const screen of [
            'documents',
            'collections',
            'reviews',
            'search',
            'operations',
            'governance',
            'audit',
          ]) {
            await page.locator(`[data-nav="${screen}"]`).click();
            await shot(screen);
          }
          await page.locator('[data-nav="documents"]').click();
          await page.locator('[data-open-doc="DKP-002"]').click();
          await shot('detail');
          await page.locator('[data-action="upload"]').click();
          await shot('upload');
          await page.locator('#fixture').selectOption('scanner');
          await page.locator('[data-action="nextUpload"]').click();
          await shot('upload-blocked');
          await page.locator('[data-nav="operations"]').click();
          await page.locator('[data-process="DKP-003"]').click();
          await shot('processing');
          await page.locator('[data-nav="documents"]').click();
          await page.locator('[data-action="create"]').click();
          await shot('draft');
          await page.locator('[data-nav="reviews"]').click();
          await page.locator('#conflict').selectOption('revision');
          await shot('review-conflict');
          await page.locator('[data-nav="search"]').click();
          await page.locator('[data-action^="citation:"]').first().click();
          await shot('source');
          await page.locator('[data-nav="governance"]').click();
          await page.locator('#conflict').selectOption('none');
          await page.locator('[data-action="request:hold"]').click();
          await shot('governance-confirm');
          await page.locator('#reason').fill('Synthetic reference inspection');
          await page.locator('[data-action="confirmGovern"]').click();
          await page.locator('[data-nav="audit"]').click();
          await shot('audit-event');
          await page.locator('#workspace').selectOption('Northwind');
          await shot('workspace-switch');
        } finally {
          await page.close();
        }
      }
    }
    await writeFile(
      resolve(output, 'inventory.json'),
      JSON.stringify(
        {
          prototypeSHA256: createHash('sha256')
            .update(await readFile(prototype))
            .digest('hex'),
          browser: browser.version(),
          captureEnvironment: {
            platform: process.platform,
            architecture: process.arch,
            node: process.version,
          },
          captures,
        },
        null,
        2,
      ),
      { mode: 0o600 },
    );
    console.log(
      JSON.stringify({ captures: captures.length, output: resolve(output) }),
    );
  } finally {
    await browser.close();
  }
}

if (
  process.argv[1] &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  await captureReference(process.argv[2]);
}
