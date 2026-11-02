import process from 'node:process';
import { URL } from 'node:url';
import console from 'node:console';
import { chromium, expect } from '@playwright/test';
import net from 'node:net';
import { mkdir } from 'node:fs/promises';

const port = Number(process.env.DKP_LOG_PORT);
if (!Number.isInteger(port) || port < 1024 || port > 65535)
  throw new Error('Invalid fixture origin');
// Test-only TCP forwarding preserves the exact public TLS authority and Host.
const forwarder = net.createServer((incoming) => {
  const outgoing = net.connect(8443, 'gateway');
  incoming.pipe(outgoing).pipe(incoming);
  incoming.on('error', () => outgoing.destroy());
  outgoing.on('error', () => incoming.destroy());
  incoming.on('close', () => outgoing.destroy());
});
await new Promise((resolve) => forwarder.listen(port, '127.0.0.1', resolve));
let browser;
try {
  browser = await chromium.launch();
  const context = await browser.newContext({
    ignoreHTTPSErrors: false,
    viewport: { width: 1440, height: 1000 },
  });
  const page = await context.newPage();
  const businessRequests = [];
  page.on('request', (request) => {
    if (
      /^\/(oauth|login|api\/v1\/session)/.test(new URL(request.url()).pathname)
    )
      businessRequests.push(request.url());
  });
  const base = `https://localhost:${port}`;
  const response = await page.goto(base + '/dev/logs');
  expect(response.status()).toBe(200);
  await expect(
    page.getByRole('heading', { name: 'Development logs', exact: true }),
  ).toBeVisible();
  if (process.env.DKP_LOG_BROWSER_MODE === 'UNAVAILABLE') {
    await expect(page.getByText('Unable to read logs')).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'No records in this window' }),
    ).toHaveCount(0);
  } else {
    await expect(page.locator('tbody tr')).toHaveCount(2);
    await expect(page.getByText('Connected', { exact: true })).toBeVisible();
    const build = '11111111-1111-4111-8111-111111111111';
    await page.getByLabel('Build ID', { exact: true }).fill(build);
    await page.getByRole('button', { name: 'Apply filters' }).click();
    await expect(page.locator('tbody tr')).toHaveCount(1);
    await expect(page.locator('tbody tr')).toContainText(build);
    await page.getByText('Details', { exact: true }).click();
    await expect(page.locator('.log-details')).toContainText('runtime.started');
    const severityLabel = page
      .locator('.log-severity')
      .getByText('DEBUG', { exact: true });
    await expect(severityLabel).toBeVisible();
    expect(
      await severityLabel.evaluate(
        (element) => element.scrollWidth <= element.clientWidth,
      ),
    ).toBe(true);
    await expect(page.locator('input[type=password]')).toHaveCount(0);
    await mkdir('test-results', { recursive: true });
    await page.screenshot({
      path: 'test-results/dev-logs-dark.png',
      fullPage: true,
    });
    await page.getByRole('switch', { name: 'Light theme' }).check();
    await expect(page.locator('html')).toHaveAttribute(
      'data-mantine-color-scheme',
      'light',
    );
    await page.screenshot({
      path: 'test-results/dev-logs-light.png',
      fullPage: true,
    });
    await page
      .getByLabel('Build ID', { exact: true })
      .fill('99999999-9999-4999-8999-999999999999');
    await page.getByRole('button', { name: 'Apply filters' }).click();
    await expect(page.locator('tbody tr')).toHaveCount(1);
    await expect(page.locator('tbody tr')).toContainText(
      '99999999-9999-4999-8999-999999999999',
    );
    await page
      .getByLabel('Build ID', { exact: true })
      .fill('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    await page.getByRole('button', { name: 'Apply filters' }).click();
    await expect(
      page.getByRole('heading', { name: 'No records in this window' }),
    ).toBeVisible();
    expect(
      (await context.request.get(base + '/api/v1/dev-logs/events')).status(),
    ).toBe(401);
  }
  expect(businessRequests).toEqual([]);
  console.log(
    JSON.stringify({
      result: 'PASS',
      mode: process.env.DKP_LOG_BROWSER_MODE || 'NORMAL',
      strictTls: true,
      oidcStarted: false,
    }),
  );
} finally {
  await browser?.close();
  await new Promise((resolve) => forwarder.close(resolve));
}
