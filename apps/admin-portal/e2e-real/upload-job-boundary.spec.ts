import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
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
const phases = [
  'SETUP',
  'SIGN_IN',
  'SESSION',
  'SELECT_TENANT',
  'ACTIVE_SESSION',
  'SOURCE',
  'FIRST_CREATE',
  'FIRST_ADMIT',
  'FIRST_TRANSFER',
  'SECOND_CREATE',
  'SECOND_ADMIT',
  'SECOND_TRANSFER',
  'PREPARED',
  'BEGIN',
  'SUBMIT',
  'ABORT',
  'RESTORED',
  'COMPLETE',
  'STATUS',
  'FINISH',
] as const;
type Phase = (typeof phases)[number];
let diagnostic: {
  schemaVersion: 1;
  phase: Phase;
  errorClass: 'NONE' | 'ASSERTION' | 'TIMEOUT' | 'ERROR';
  expectedStatus: number | null;
  actualStatus: number | null;
};
async function saveDiagnostic() {
  // Atomic private replacement: the bridge never observes a partially written record.
  await writeFile(
    control + 'diagnostic.next.json',
    JSON.stringify(diagnostic),
    { mode: 0o600, flag: 'wx' },
  );
  await rename(control + 'diagnostic.next.json', control + 'diagnostic.json');
}
async function phase(value: Phase) {
  if (!phases.includes(value)) throw Error('Closed diagnostic phase required');
  diagnostic = {
    schemaVersion: 1,
    phase: value,
    errorClass: 'NONE',
    expectedStatus: null,
    actualStatus: null,
  };
  await saveDiagnostic();
}
async function status(actual: number, expected: number) {
  diagnostic.expectedStatus = expected;
  diagnostic.actualStatus = actual;
  if (actual !== expected) diagnostic.errorClass = 'ASSERTION';
  await saveDiagnostic();
  expect(actual).toBe(expected);
}

async function failure(error: unknown) {
  diagnostic.errorClass =
    error instanceof Error && error.name === 'TimeoutError'
      ? 'TIMEOUT'
      : error instanceof Error && 'matcherResult' in error
        ? 'ASSERTION'
        : 'ERROR';
  try {
    await saveDiagnostic();
  } catch {
    /* Diagnostics never replace the original failure. */
  }
}

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
    await phase('SETUP');
    try {
      const origin = new URL(baseURL!).origin;
      expect(origin).toBe('https://localhost:19443');
      await phase('SIGN_IN');
      await page.goto('/identity');
      await page.getByRole('link', { name: 'Sign in', exact: true }).click();
      await expect(page.locator('#username')).toBeVisible();
      await page.locator('#username').fill(process.env.DKP_IDENTITY_USERNAME!);
      await page.locator('#password').fill(process.env.DKP_IDENTITY_PASSWORD!);
      await page.locator('#kc-login').click();
      await page.waitForURL(
        (url) => url.origin === origin && url.pathname === '/',
      );
      await phase('SESSION');
      const initial = await page.request.get('/api/v1/session');
      await status(initial.status(), 200);
      const session = (await initial.json()) as {
        csrfToken: string;
        tenants: string[];
      };
      const tenant = session.tenants[0]!;
      expect(['tenant-a', 'tenant-b']).toContain(tenant);
      await phase('SELECT_TENANT');
      const selected = await page.request.post('/api/v1/session/tenant', {
        data: { tenantId: tenant },
        headers: { Origin: origin, 'X-CSRF-TOKEN': session.csrfToken },
      });
      await status(selected.status(), 200);
      await phase('ACTIVE_SESSION');
      const activeResponse = await page.request.get('/api/v1/session');
      await status(activeResponse.status(), 200);
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
      await phase('SOURCE');
      const bytes = sourcePdf();
      expect(bytes.length).toBeLessThan(100_000_000);
      const sha256 = createHash('sha256').update(bytes).digest('hex');
      const base = '/api/v1/dm/documents';
      async function admission(which: 'FIRST' | 'SECOND') {
        await phase(`${which}_CREATE`);
        const created = await page.request.post(base, {
          headers: headers(),
          data: {
            metadata: {
              title: 'Public synthetic job boundary',
              description: '',
            },
            classification: 'development-restricted',
            classificationPolicyRevision: 1,
          },
        });
        await status(created.status(), 201);
        const draft = (await created.json()) as {
          documentId: string;
          revision: number;
        };
        expect(Object.keys(draft).sort()).toEqual(['documentId', 'revision']);
        expect(draft.revision).toBe(0);
        await phase(`${which}_ADMIT`);
        const admitted = await page.request.post(base + '/uploads', {
          headers: headers(),
          data: {
            documentId: draft.documentId,
            expectedRevision: 0,
            format: 'PDF',
            sizeBytes: bytes.length,
          },
        });
        await status(admitted.status(), 201);
        const slot = (await admitted.json()) as { uploadId: string };
        expect(Object.keys(slot)).toEqual(['uploadId']);
        await phase(`${which}_TRANSFER`);
        const transfer = await page.request.put(
          `${base}/uploads/${slot.uploadId}/content`,
          {
            headers: {
              ...headers(),
              'Content-Type': 'application/octet-stream',
            },
            data: bytes,
            timeout: 310_000,
          },
        );
        await status(transfer.status(), 200);
        expect(await transfer.json()).toEqual(slot);
        return {
          documentId: draft.documentId,
          uploadId: slot.uploadId,
          completeKey: randomUUID(),
        };
      }
      const first = await admission('FIRST'),
        second = await admission('SECOND');
      await phase('PREPARED');
      await emit('prepared', {
        tenant,
        sizeBytes: bytes.length,
        sha256,
        first,
        second,
      });
      await phase('BEGIN');
      await gate('begin', 30_000);
      type Pending = { controller: AbortController; outcome: Promise<string> };
      await phase('SUBMIT');
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
          (window as unknown as { jobs: Pending }).jobs = {
            controller,
            outcome,
          };
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
      await phase('ABORT');
      await gate('abort-now', 30_000);
      const aborted = await page.evaluate(async () => {
        const jobs = (window as unknown as { jobs: Pending }).jobs;
        jobs.controller.abort();
        return jobs.outcome;
      });
      expect(aborted).toBe('ABORTED');
      await emit('aborted', {
        schemaVersion: 1,
        checkpoint: 'BROWSER_ABORTED',
      });
      await phase('RESTORED');
      await gate('restored', 120_000);
      await phase('COMPLETE');
      const completed = await page.request.post(
        `${base}/uploads/${second.uploadId}/complete`,
        { headers: headers(second.completeKey), data: {}, timeout: 250_000 },
      );
      await status(completed.status(), 200);
      const receipt = (await completed.json()) as {
        uploadId: string;
        receiptId: string;
      };
      expect(Object.keys(receipt).sort()).toEqual(['receiptId', 'uploadId']);
      expect(receipt.uploadId).toBe(second.uploadId);
      expect(receipt.receiptId).toMatch(/^[a-f0-9-]{36}$/);
      await phase('STATUS');
      const uploadStatus = await page.request.get(
        `${base}/uploads/${first.uploadId}/status`,
      );
      await status(uploadStatus.status(), 200);
      expect(['VERIFYING', 'QUARANTINED']).toContain(
        ((await uploadStatus.json()) as { state: string }).state,
      );
      await emit('completed', {
        schemaVersion: 1,
        checkpoint: 'SECOND_VERIFIED',
      });
      await phase('FINISH');
      await gate('finish', 30_000);
      console.log('DKP_PROVIDER_JOB_BROWSER_PASS');
    } catch (error: unknown) {
      await failure(error);
      throw error;
    }
  });
