import { readFile } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { expect, test, type APIResponse } from '@playwright/test';
import { z } from 'zod';
import {
  acceptsFaultUnavailable,
  type FaultRoute,
} from './upload-fault-contract';

type Artifact = {
  documentId: string;
  uploadId: string;
  receiptId: string | null;
  versionId: string | null;
  completeKey: string;
  appendKey: string;
};
type Fixture = {
  tenant: string;
  sha256: string;
  sizeBytes: number;
  consumed: Artifact;
  unconsumed: Artifact;
  candidate: Artifact | null;
};
type Input = {
  schemaVersion: 1;
  phase:
    | 'seed'
    | 'verify'
    | 'readonly-outage'
    | 'candidate'
    | 'candidate-outage'
    | 'recover'
    | 'consume';
  service: 'pg' | 'rustfs' | 'scanner' | 'inspector' | 'dm' | null;
  fixture: Fixture | null;
};
const artifactSchema = z
  .object({
    documentId: z.uuid(),
    uploadId: z.uuid(),
    receiptId: z.uuid().nullable(),
    versionId: z.uuid().nullable(),
    completeKey: z.uuid(),
    appendKey: z.uuid(),
  })
  .strict();
const inputSchema = z
  .object({
    schemaVersion: z.literal(1),
    phase: z.enum([
      'seed',
      'verify',
      'readonly-outage',
      'candidate',
      'candidate-outage',
      'recover',
      'consume',
    ]),
    service: z.enum(['pg', 'rustfs', 'scanner', 'inspector', 'dm']).nullable(),
    fixture: z
      .object({
        tenant: z.enum(['tenant-a', 'tenant-b']),
        sha256: z.string().regex(/^[a-f0-9]{64}$/),
        sizeBytes: z.number().int().min(1).max(100000000),
        consumed: artifactSchema,
        unconsumed: artifactSchema,
        candidate: artifactSchema.nullable(),
      })
      .strict()
      .nullable(),
  })
  .strict();
const source = process.env.DKP_UPLOAD_FAULT_INPUT;
// Runner and orchestrator independently validate the closed data. No cookies,
// tokens, executable commands, endpoints or storage authority are supplied here.
if (source !== undefined) {
  if (
    process.env.DKP_UPLOAD_E2E_ENABLED !== 'true' ||
    process.env.DKP_UPLOAD_LIMITS_PROFILE !== undefined ||
    process.env.DKP_UPLOAD_DURABILITY_FIXTURE !== undefined
  )
    throw new Error('Explicit separate real upload fault profile required');
  if (source.length > 16384) throw new Error('Bounded fault input required');
  const input: Input = inputSchema.parse(JSON.parse(source));
  if (
    input.phase === 'seed' &&
    (input.fixture !== null || input.service !== null)
  )
    throw new Error('Fresh seed cannot adopt fixtures');
  if (
    ['readonly-outage', 'candidate-outage', 'recover'].includes(input.phase) &&
    input.service === null
  )
    throw new Error('Finite owned fault service required');
  if (
    input.fixture &&
    (!input.fixture.consumed.receiptId ||
      !input.fixture.consumed.versionId ||
      !input.fixture.unconsumed.receiptId)
  )
    throw new Error('Both persistent receipt states required');
  if (
    input.schemaVersion !== 1 ||
    ![
      'seed',
      'verify',
      'readonly-outage',
      'candidate',
      'candidate-outage',
      'recover',
      'consume',
    ].includes(input.phase)
  )
    throw new Error('Finite fault phase required');
  test(`real authenticated upload fault phase ${input.phase}`, async ({
    page,
    baseURL,
  }) => {
    test.setTimeout(600_000);
    const origin = new URL(baseURL!).origin;
    expect(new URL(origin).protocol).toBe('https:');
    await page.goto('/identity');
    await page.getByRole('link', { name: 'Sign in', exact: true }).click();
    await page.locator('#username').fill(process.env.DKP_IDENTITY_USERNAME!);
    await page.locator('#password').fill(process.env.DKP_IDENTITY_PASSWORD!);
    await page.locator('#kc-login').click();
    await page.waitForURL(
      (url) => url.origin === origin && url.pathname === '/',
    );
    const session = await page.request.get('/api/v1/session');
    expect(session.status()).toBe(200);
    const actor = (await session.json()) as {
      tenants: string[];
      csrfToken: string;
    };
    const tenant = input.fixture?.tenant ?? actor.tenants[0]!;
    expect(['tenant-a', 'tenant-b']).toContain(tenant);
    expect(actor.tenants).toContain(tenant);
    const selected = await page.request.post('/api/v1/session/tenant', {
      data: { tenantId: tenant },
      headers: { Origin: origin, 'X-CSRF-TOKEN': actor.csrfToken },
    });
    expect(selected.status()).toBe(200);
    const selectedSession = await page.request.get('/api/v1/session');
    expect(selectedSession.status()).toBe(200);
    const active = (await selectedSession.json()) as {
      selectedTenant: string;
      csrfToken: string;
    };
    expect(active.selectedTenant).toBe(tenant);
    const headers = (key: string = randomUUID()) => ({
      Origin: origin,
      'X-CSRF-TOKEN': active.csrfToken,
      'Idempotency-Key': key,
    });
    const base = '/api/v1/dm/documents';
    const bytes = await readFile(
      fileURLToPath(new URL('./fixtures/synthetic.pdf', import.meta.url)),
    );
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    if (input.fixture) {
      expect(input.fixture.sha256).toBe(sha256);
      expect(input.fixture.sizeBytes).toBe(bytes.length);
    }
    const complete = (a: Artifact) =>
      page.request.post(`${base}/uploads/${a.uploadId}/complete`, {
        data: {},
        headers: headers(a.completeKey),
        timeout: 250_000,
      });
    async function inspectComplete(a: Artifact, response: APIResponse) {
      expect(response.status()).toBe(200);
      const result = (await response.json()) as {
        uploadId: string;
        receiptId: string;
      };
      expect(Object.keys(result).sort()).toEqual(['receiptId', 'uploadId']);
      expect(result.uploadId).toBe(a.uploadId);
      expect(result.receiptId).toMatch(/^[a-f0-9-]{36}$/);
      if (a.receiptId) expect(result.receiptId).toBe(a.receiptId);
      a.receiptId = result.receiptId;
    }
    async function create(): Promise<Artifact> {
      const draft = await page.request.post(base, {
        data: {
          metadata: {
            title: `Synthetic faults ${randomUUID()}`,
            description: 'Public synthetic provider fault acceptance.',
          },
          classification: 'development-restricted',
          classificationPolicyRevision: 1,
        },
        headers: headers(),
      });
      expect(draft.status()).toBe(201);
      const document = (await draft.json()) as {
        documentId: string;
        revision: number;
      };
      expect(Object.keys(document).sort()).toEqual(['documentId', 'revision']);
      expect(document.revision).toBe(0);
      const admission = await page.request.post(`${base}/uploads`, {
        data: {
          documentId: document.documentId,
          expectedRevision: 0,
          format: 'PDF',
          sizeBytes: bytes.length,
        },
        headers: headers(),
      });
      expect(admission.status()).toBe(201);
      const admitted = (await admission.json()) as { uploadId: string };
      expect(Object.keys(admitted)).toEqual(['uploadId']);
      const body = await page.request.put(
        `${base}/uploads/${admitted.uploadId}/content`,
        {
          data: bytes,
          headers: { ...headers(), 'Content-Type': 'application/octet-stream' },
          timeout: 310_000,
        },
      );
      expect(body.status()).toBe(200);
      expect(await body.json()).toEqual(admitted);
      return {
        documentId: document.documentId,
        uploadId: admitted.uploadId,
        receiptId: null,
        versionId: null,
        completeKey: randomUUID(),
        appendKey: randomUUID(),
      };
    }
    async function append(a: Artifact) {
      expect(a.receiptId).not.toBeNull();
      const result = await page.request.post(
        `${base}/${a.documentId}/versions`,
        {
          data: { expectedRevision: 0, receiptId: a.receiptId },
          headers: headers(a.appendKey),
        },
      );
      expect(result.status()).toBe(201);
      expect(await result.json()).toEqual({
        documentId: a.documentId,
        revision: 1,
      });
      const discovery = await page.request.get(
        `${base}/${a.documentId}/upload-receipts/${a.receiptId}/version`,
      );
      expect(discovery.status()).toBe(200);
      const mapping = (await discovery.json()) as {
        documentId: string;
        versionId: string;
      };
      expect(Object.keys(mapping).sort()).toEqual(['documentId', 'versionId']);
      expect(mapping.documentId).toBe(a.documentId);
      expect(mapping.versionId).toMatch(/^[a-f0-9-]{36}$/);
      if (a.versionId) expect(mapping.versionId).toBe(a.versionId);
      a.versionId = mapping.versionId;
    }
    async function download(a: Artifact, available = true) {
      const result = await page.request.get(
        `${base}/${a.documentId}/versions/${a.versionId}/content`,
        {
          headers: { Accept: 'application/pdf' },
          timeout: 75_000,
        },
      );
      if (!available) {
        await unavailable(result);
        return;
      }
      expect(result.status()).toBe(200);
      expect(result.headers()['content-type']).toBe('application/pdf');
      expect(result.headers()['content-length']).toBe(String(bytes.length));
      expect(result.headers()['cache-control']).toBe('no-store');
      expect(result.headers()['x-content-type-options']).toBe('nosniff');
      expect(result.headers()['content-disposition']).toBe(
        `attachment; filename="${a.versionId}.pdf"`,
      );
      const actual = await result.body();
      expect(actual).toEqual(bytes);
      expect(createHash('sha256').update(actual).digest('hex')).toBe(sha256);
    }
    async function unavailable(
      result: APIResponse,
      route: FaultRoute = 'upload',
    ) {
      const body = await result.body();
      expect(
        acceptsFaultUnavailable(result.status(), body, input.service, route),
      ).toBe(true);
    }
    async function verify(fixture: Fixture) {
      await append(fixture.consumed); // Same successful command replay, no new version.
      await download(fixture.consumed);
      const status = await page.request.get(
        `${base}/uploads/${fixture.unconsumed.uploadId}/status`,
      );
      expect(status.status()).toBe(200);
      expect((await status.json()).state).toBe('VERIFIED');
      await inspectComplete(
        fixture.unconsumed,
        await complete(fixture.unconsumed),
      );
      const hidden = await page.request.get(
        `${base}/${fixture.unconsumed.documentId}/upload-receipts/${fixture.unconsumed.receiptId}/version`,
      );
      expect(hidden.status()).toBe(404);
      expect(await hidden.json()).toEqual({ code: 'NOT_FOUND' });
      for (const [a, revision] of [
        [fixture.consumed, 1],
        [fixture.unconsumed, 0],
      ] as const) {
        const document = await page.request.get(`${base}/${a.documentId}`);
        expect(document.status()).toBe(200);
        const facts = await document.json();
        expect(facts.revision).toBe(revision);
        expect(facts.activeVersionId).toBeNull();
      }
    }
    let fixture = input.fixture;
    if (input.phase === 'seed') {
      const consumed = await create();
      await inspectComplete(consumed, await complete(consumed));
      await append(consumed);
      await download(consumed);
      const unconsumed = await create();
      await inspectComplete(unconsumed, await complete(unconsumed));
      fixture = {
        tenant,
        sizeBytes: bytes.length,
        sha256,
        consumed,
        unconsumed,
        candidate: null,
      };
      await verify(fixture);
    } else {
      if (!fixture) throw new Error('Owned fixture required');
      if (input.phase === 'verify') await verify(fixture);
      if (input.phase === 'readonly-outage') {
        if (input.service === 'pg' || input.service === 'dm') {
          await unavailable(
            await page.request.get(`${base}/${fixture.consumed.documentId}`, {
              timeout: 15_000,
            }),
            'legacy-detail',
          );
          await unavailable(
            await page.request.get(
              `${base}/uploads/${fixture.unconsumed.uploadId}/status`,
              { timeout: 15_000 },
            ),
          );
          await unavailable(await complete(fixture.unconsumed));
          await download(fixture.consumed, false);
        } else {
          await download(fixture.consumed, input.service !== 'rustfs');
          await inspectComplete(
            fixture.unconsumed,
            await complete(fixture.unconsumed),
          );
          const status = await page.request.get(
            `${base}/uploads/${fixture.unconsumed.uploadId}/status`,
          );
          expect(status.status()).toBe(200);
          expect((await status.json()).state).toBe('VERIFIED');
        }
      }
      if (input.phase === 'candidate') {
        await verify(fixture);
        fixture.candidate = await create();
      }
      if (input.phase === 'candidate-outage') {
        if (!fixture.candidate) throw new Error('Owned candidate required');
        await unavailable(await complete(fixture.candidate));
      }
      if (input.phase === 'recover') {
        if (!fixture.candidate) throw new Error('Owned candidate required');
        await inspectComplete(
          fixture.candidate,
          await complete(fixture.candidate),
        );
        await append(fixture.candidate);
        await download(fixture.candidate);
        await verify(fixture);
      }
      if (input.phase === 'consume') {
        await verify(fixture);
        await append(fixture.unconsumed);
        await append(fixture.unconsumed);
        await download(fixture.unconsumed);
      }
    }
    // Orchestrator captures this output privately, validates its exact schema and
    // identity continuity. No cookie, identity claims or authority is exported.
    console.log(
      'DKP_UPLOAD_FAULT_RESULT ' +
        JSON.stringify({ phase: input.phase, result: 'PASS', fixture }),
    );
  });
}
