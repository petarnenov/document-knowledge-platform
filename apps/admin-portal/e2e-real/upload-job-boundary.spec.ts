import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';
import { sourcePdf } from './upload-job-fixture';

const enabled = process.env.DKP_UPLOAD_JOB_INPUT;
if (
  enabled !== undefined &&
  (enabled !== '{"schemaVersion":1}' ||
    process.env.DKP_UPLOAD_E2E_ENABLED !== 'true')
)
  throw Error('Explicit closed real provider job profile required.');
const control = '/tmp/dkp-job-boundary/';
async function emit(
  name: 'prepared' | 'submitted' | 'aborted' | 'completed',
  value: unknown,
) {
  await writeFile(control + name + '.json', JSON.stringify(value), {
    mode: 0o600,
    flag: 'wx',
  });
}
async function gate(
  name: 'begin' | 'abort-now' | 'restored' | 'finish',
  timeout: number,
) {
  await expect
    .poll(
      async () => {
        try {
          const value: unknown = JSON.parse(
            await readFile(control + name + '.json', 'utf8'),
          );
          expect(value).toEqual({ schemaVersion: 1, checkpoint: name });
          return true;
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code === 'ENOENT') return false;
          throw error;
        }
      },
      { timeout },
    )
    .toBe(true);
}
if (enabled !== undefined)
  test('real guarded COMPLETE abort then exact DM crash with another admission and fresh-provider recovery', async ({
    page,
    baseURL,
  }) => {
    test.setTimeout(600_000);
    await mkdir(control, { mode: 0o700 });
    const origin = new URL(baseURL!).origin;
    expect(origin).toBe('https://localhost:19443');
    await page.goto('/identity');
    await page.getByRole('link', { name: 'Sign in', exact: true }).click();
    await expect(page.locator('#username')).toBeVisible();
    await page.locator('#username').fill(process.env.DKP_IDENTITY_USERNAME!);
    await page.locator('#password').fill(process.env.DKP_IDENTITY_PASSWORD!);
    await page.locator('#kc-login').click();
    await page.waitForURL(
      (url) => url.origin === origin && url.pathname === '/',
    );
    const initial = await page.request.get('/api/v1/session');
    expect(initial.status()).toBe(200);
    const session = (await initial.json()) as {
      csrfToken: string;
      tenants: string[];
    };
    const tenant = session.tenants[0]!;
    expect(['tenant-a', 'tenant-b']).toContain(tenant);
    const selected = await page.request.post('/api/v1/session/tenant', {
      data: { tenantId: tenant },
      headers: { Origin: origin, 'X-CSRF-TOKEN': session.csrfToken },
    });
    expect(selected.status()).toBe(200);
    const activeResponse = await page.request.get('/api/v1/session');
    expect(activeResponse.status()).toBe(200);
    const active = (await activeResponse.json()) as {
      csrfToken: string;
      selectedTenant: string;
    };
    expect(active.selectedTenant).toBe(tenant);
    const headers = (key = randomUUID()) => ({
      Origin: origin,
      'X-CSRF-TOKEN': active.csrfToken,
      'Idempotency-Key': key,
    });
    const bytes = sourcePdf();
    expect(bytes.length).toBeLessThan(100_000_000);
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const base = '/api/v1/dm/documents';
    async function admission() {
      const created = await page.request.post(base, {
        headers: headers(),
        data: {
          metadata: { title: 'Public synthetic job boundary', description: '' },
          classification: 'development-restricted',
          classificationPolicyRevision: 1,
        },
      });
      expect(created.status()).toBe(201);
      const draft = (await created.json()) as {
        documentId: string;
        revision: number;
      };
      expect(Object.keys(draft).sort()).toEqual(['documentId', 'revision']);
      expect(draft.revision).toBe(0);
      const admitted = await page.request.post(base + '/uploads', {
        headers: headers(),
        data: {
          documentId: draft.documentId,
          expectedRevision: 0,
          format: 'PDF',
          sizeBytes: bytes.length,
        },
      });
      expect(admitted.status()).toBe(201);
      const slot = (await admitted.json()) as { uploadId: string };
      expect(Object.keys(slot)).toEqual(['uploadId']);
      const transfer = await page.request.put(
        `${base}/uploads/${slot.uploadId}/content`,
        {
          headers: { ...headers(), 'Content-Type': 'application/octet-stream' },
          data: bytes,
          timeout: 310_000,
        },
      );
      expect(transfer.status()).toBe(200);
      expect(await transfer.json()).toEqual(slot);
      return {
        documentId: draft.documentId,
        uploadId: slot.uploadId,
        completeKey: randomUUID(),
      };
    }
    const first = await admission(),
      second = await admission();
    await emit('prepared', {
      tenant,
      sizeBytes: bytes.length,
      sha256,
      first,
      second,
    });
    await gate('begin', 30_000);
    type Pending = { controller: AbortController; outcome: Promise<string> };
    await page.evaluate(
      ({ path, guard }) => {
        const controller = new AbortController();
        const outcome = fetch(path, {
          method: 'POST',
          headers: { ...guard, 'Content-Type': 'application/json' },
          body: '{}',
          signal: controller.signal,
        }).then(
          (r) => `HTTP_${r.status}`,
          () => (controller.signal.aborted ? 'ABORTED' : 'FAILED'),
        );
        (window as unknown as { jobs: Pending }).jobs = { controller, outcome };
      },
      {
        path: `${base}/uploads/${first.uploadId}/complete`,
        guard: headers(first.completeKey),
      },
    );
    await emit('submitted', {
      schemaVersion: 1,
      checkpoint: 'COMPLETE_SUBMITTED',
    });
    await gate('abort-now', 30_000);
    const aborted = await page.evaluate(async () => {
      const jobs = (window as unknown as { jobs: Pending }).jobs;
      jobs.controller.abort();
      return jobs.outcome;
    });
    expect(aborted).toBe('ABORTED');
    await emit('aborted', { schemaVersion: 1, checkpoint: 'BROWSER_ABORTED' });
    await gate('restored', 120_000);
    const completed = await page.request.post(
      `${base}/uploads/${second.uploadId}/complete`,
      { headers: headers(second.completeKey), data: {}, timeout: 250_000 },
    );
    expect(completed.status()).toBe(200);
    const receipt = (await completed.json()) as {
      uploadId: string;
      receiptId: string;
    };
    expect(Object.keys(receipt).sort()).toEqual(['receiptId', 'uploadId']);
    expect(receipt.uploadId).toBe(second.uploadId);
    expect(receipt.receiptId).toMatch(/^[a-f0-9-]{36}$/);
    const status = await page.request.get(
      `${base}/uploads/${first.uploadId}/status`,
    );
    expect(status.status()).toBe(200);
    expect(['VERIFYING', 'QUARANTINED']).toContain(
      ((await status.json()) as { state: string }).state,
    );
    await emit('completed', {
      schemaVersion: 1,
      checkpoint: 'SECOND_VERIFIED',
    });
    await gate('finish', 30_000);
    console.log('DKP_PROVIDER_JOB_BROWSER_PASS');
  });
