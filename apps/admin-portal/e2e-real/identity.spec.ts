import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import {
  expect,
  test,
  type BrowserContext,
  type Page,
  type Request,
  type Response,
  type Route,
} from '@playwright/test';
import { nativeBoundaryBrowser } from './native-boundary-browser';
import { confirmSingleCreate, replayUiCreate } from './ui-create-receipt';

const uploadProfile = process.env.DKP_UPLOAD_E2E_ENABLED;
if (
  uploadProfile !== undefined &&
  uploadProfile !== 'true' &&
  uploadProfile !== 'false'
)
  throw new Error(
    'Explicit upload verification profile must be true or false.',
  );

for (const scheme of ['dark', 'light'] as const) {
  test(`real HTTPS ${scheme} provider login, tenant selection, independent contexts and local logout`, async ({
    page,
    context,
    baseURL,
  }) => {
    test.setTimeout(600_000);
    const origin = new URL(baseURL!).origin;
    await page.addInitScript(
      ({ value, portal }) => {
        if (location.origin === portal)
          localStorage.setItem('mantine-color-scheme', value);
      },
      { value: scheme, portal: origin },
    );
    await page.goto('/identity');
    await expect(page.getByRole('switch', { name: 'Light theme' })).toBeChecked(
      { checked: scheme === 'light' },
    );
    await page.getByRole('link', { name: 'Sign in', exact: true }).click();
    await expect(page.locator('#username')).toBeVisible();
    await page.locator('#username').fill(process.env.DKP_IDENTITY_USERNAME!);
    await page.locator('#password').fill(process.env.DKP_IDENTITY_PASSWORD!);
    await page.locator('#kc-login').click();
    // The callback is also same-origin. Wait for its final redirect so a new
    // navigation cannot abort the code exchange or session-cookie persistence.
    await page.waitForURL(
      (url) => url.origin === origin && url.pathname === '/',
    );
    const authenticated = await page.request.get('/api/v1/session');
    expect(
      authenticated.status(),
      'BFF session status after final login redirect',
    ).toBe(200);
    const initialSession = (await authenticated.json()) as Record<
      string,
      unknown
    >;
    expect(
      Object.hasOwn(initialSession, 'selectedTenant'),
      'Session contract includes nullable selectedTenant',
    ).toBe(true);
    await page.goto('/identity');
    await expect(
      page.getByRole('heading', { name: /^Signed in as / }),
    ).toBeVisible();

    const cookie = (await context.cookies()).find(
      (item) => item.name === '__Host-DKP-SESSION',
    );
    expect(Boolean(cookie)).toBe(true);
    expect(cookie?.secure).toBe(true);
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe('Lax');
    expect(cookie?.path).toBe('/');
    expect(cookie?.domain).toBe(new URL(baseURL!).hostname);

    const selector = page.getByRole('combobox', { name: 'Tenant' });
    const tenant = await selector
      .locator('option')
      .nth(1)
      .getAttribute('value');
    expect(Boolean(tenant)).toBe(true);
    await selector.selectOption(tenant!);
    await page.getByRole('button', { name: 'Use tenant' }).click();
    for (const [service, label] of [
      ['dm', 'Document Management'],
      ['kb', 'Knowledge Base'],
      ['search', 'Knowledge Search'],
    ] as const) {
      await page
        .getByRole('button', { name: `Check ${label} context` })
        .click();
      await expect(page.getByLabel('Verified context')).toContainText(service);
      await expect(page.getByLabel('Verified context')).toContainText(tenant!);
    }

    // No routes or business results are mocked: these requests reach the real DM/PG/catalog.
    const initialDrafts = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/v1/dm/documents',
    );
    await page.getByRole('link', { name: 'Documents', exact: true }).click();
    const initialPage = await initialDrafts;
    expect(initialPage.status()).toBe(200);
    // React renders this list only after the real query succeeds. CDP's
    // transport-finished observer may hang even after HTTP200; it is not UI state.
    await expect(
      page
        .getByRole('region', { name: 'Accessible drafts', exact: true })
        .getByRole('list'),
    ).toHaveCount(1);
    await expect(
      page.getByText('Loading drafts…', { exact: true }),
    ).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
    const title = `Synthetic draft ${Date.now()}`;
    await page.getByLabel('Title', { exact: true }).fill(title);
    await page
      .getByLabel('Description', { exact: true })
      .fill('Synthetic metadata; no document content uploaded.');
    const createdResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/v1/dm/documents') &&
        response.request().method() === 'POST',
    );
    await page
      .getByRole('button', { name: 'Create draft', exact: true })
      .click();
    const intended = {
      origin,
      title,
      description: 'Synthetic metadata; no document content uploaded.',
    };
    const receipt = await replayUiCreate(
      page.request,
      await createdResponse,
      intended,
    );
    await expect(
      page.getByRole('status').filter({
        hasText: `Saved document ${receipt.documentId}, revision ${receipt.revision}.`,
      }),
    ).toBeVisible();
    const root = `/api/v1/dm/documents/${receipt.documentId}`;
    await expect(
      page.getByRole('button', { name: 'Create draft', exact: true }),
    ).toBeEnabled();
    // An earlier run can leave more than one visible page. Follow authenticated cursors only.
    for (
      let pages = 0;
      pages < 20 &&
      (await page.getByRole('button', { name: title, exact: true }).count()) ===
        0;
      pages++
    ) {
      const next = page.getByRole('button', { name: 'Next page', exact: true });
      if ((await next.count()) === 0) break;
      const loaded = page.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          response.url().includes('/api/v1/dm/documents?'),
      );
      await next.click();
      const listed = await loaded;
      expect(listed.status()).toBe(200);
      await expect(
        page
          .getByRole('region', { name: 'Accessible drafts', exact: true })
          .getByRole('list'),
      ).toHaveCount(1);
      await expect(
        page.getByText('Loading drafts…', { exact: true }),
      ).toHaveCount(0);
      await expect(page.getByRole('alert')).toHaveCount(0);
    }
    await page.getByRole('button', { name: title, exact: true }).click();
    await expect(
      page.getByRole('heading', { name: title, exact: true }),
    ).toBeVisible();
    await confirmSingleCreate(page.request, intended, receipt.documentId);
    await page
      .getByRole('button', { name: 'Use current metadata', exact: true })
      .click();
    const updatedTitle = `${title} updated`;
    await page.getByLabel('Title', { exact: true }).fill(updatedTitle);
    await page
      .getByRole('button', { name: 'Save metadata', exact: true })
      .click();
    await expect(
      page.getByRole('heading', { name: updatedTitle, exact: true }),
    ).toBeVisible();
    await page
      .getByRole('button', { name: 'Inspect access rules', exact: true })
      .click();
    await expect(
      page.getByLabel('Access rules', { exact: true }),
    ).toBeVisible();
    const selfReadRule = {
      principal: {
        issuer: initialSession.issuer,
        subject: initialSession.subject,
      },
      action: 'document.read',
      effect: 'ALLOW',
    };
    const readAcl = async () => {
      const response = await page.request.get(`${root}/acl`);
      expect(response.status()).toBe(200);
      return (await response.json()) as { revision: number; rules: unknown[] };
    };
    const initialAcl = await readAcl();
    expect(initialAcl.revision).toBe(1);
    expect(initialAcl.rules).not.toContainEqual(selfReadRule);
    await page.getByLabel('Access mode').selectOption('DELEGATE');
    await page.getByLabel('Rule operation').selectOption('ADD_ALLOW');
    await page.getByLabel('Document action').selectOption('document.read');
    await page
      .getByRole('button', { name: 'Apply access change', exact: true })
      .click();
    await expect(
      page.getByRole('status').filter({ hasText: 'Saved document' }),
    ).toContainText('revision 2');
    const delegatedAcl = await readAcl();
    expect(delegatedAcl.revision).toBe(2);
    expect(delegatedAcl.rules).toContainEqual(selfReadRule);
    const selfReadText = `${String(initialSession.issuer)} / ${String(initialSession.subject)}: document.read ALLOW`;
    const visibleSelfRead = page
      .getByLabel('Access rules', { exact: true })
      .getByRole('listitem')
      .filter({ hasText: selfReadText });
    await expect(visibleSelfRead).toHaveCount(1);
    await page.getByLabel('Access mode').selectOption('RESTRICT');
    await page.getByLabel('Rule operation').selectOption('REMOVE_ALLOW');
    await page.getByLabel('Document action').selectOption('document.read');
    await page
      .getByRole('button', { name: 'Apply access change', exact: true })
      .click();
    await expect(
      page.getByRole('status').filter({ hasText: 'Saved document' }),
    ).toContainText('revision 3');
    const restrictedAcl = await readAcl();
    expect(restrictedAcl.revision).toBe(3);
    expect(restrictedAcl.rules).not.toContainEqual(selfReadRule);
    await expect(visibleSelfRead).toHaveCount(0);
    await page
      .getByLabel('Hold reason')
      .fill('Synthetic preservation exercise');
    await page.getByRole('button', { name: 'Place hold', exact: true }).click();
    await expect(
      page.getByRole('status').filter({ hasText: 'Saved document' }),
    ).toContainText('revision 4');
    await page
      .getByRole('button', { name: 'Inspect legal holds', exact: true })
      .click();
    await expect(page.getByLabel('Legal holds', { exact: true })).toContainText(
      'Synthetic preservation exercise',
    );
    const current = (await (
      await page.request.get('/api/v1/session')
    ).json()) as { csrfToken: string };
    const mutationBody = {
      expectedRevision: 4,
      metadata: { title: 'Rejected', description: '' },
    };
    for (const headers of [
      { 'X-CSRF-TOKEN': current.csrfToken },
      { Origin: origin },
    ]) {
      const deniedMutation = await page.request.patch(root, {
        data: mutationBody,
        headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
      });
      expect(deniedMutation.status()).toBe(403);
    }
    const append = await page.request.post(`${root}/versions`, {
      data: { expectedRevision: 4, receiptId: 'synthetic-unverified' },
      headers: {
        Origin: origin,
        'X-CSRF-TOKEN': current.csrfToken,
        'Idempotency-Key': crypto.randomUUID(),
      },
    });
    expect(append.status()).toBe(404);
    expect(await append.json()).toEqual({ code: 'NOT_FOUND' });
    const unchanged = await page.request.get(root);
    expect(((await unchanged.json()) as { revision: number }).revision).toBe(4);
    let lastVerified:
      | { path: string; mime: string; uploadId: string; receiptId: string }
      | undefined;
    // Real untrusted bytes traverse BFF, RustFS quarantine, standard ClamAV and inspector.
    for (const format of uploadProfile === 'true'
      ? (['PDF', 'DOCX', 'PPTX', 'XLSX'] as const)
      : []) {
      console.log(`Real upload ${scheme} ${format}: starting`);
      await page
        .getByLabel('Document format', { exact: true })
        .selectOption(format);
      const fixture = fileURLToPath(
        new URL(
          `./fixtures/synthetic.${format.toLowerCase()}`,
          import.meta.url,
        ),
      );
      await page.locator('input[type=file]').setInputFiles(fixture);
      // CDP may evict a response body once the immediate append/navigation runs.
      // Observe immutable outgoing request data instead. Hold only this real
      // append until a real same-key completion replay proves its closed body.
      const completed = page
        .waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            new URL(response.url()).origin === origin &&
            new URL(response.url()).pathname.endsWith('/complete'),
        )
        .then(
          (response) => ({ response }),
          (error: unknown) => ({ error }),
        );
      const appended = page
        .waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            new URL(response.url()).origin === origin &&
            new URL(response.url()).pathname === `${root}/versions`,
        )
        .then(
          (response) => ({ status: response.status() }),
          (error: unknown) => ({ error }),
        );
      type Verified = { uploadId: string; receiptId: string };
      type Observation =
        { body: Verified; expectedRevision: number } | { error: unknown };
      let report!: (observation: Observation) => void;
      const observed = new Promise<Observation>((resolve) => {
        report = resolve;
      });
      const appendUrl = `${origin}${root}/versions`;
      const barrier = async (route: Route) => {
        if (route.request().method() !== 'POST') {
          await route.continue();
          return;
        }
        let observation: Observation;
        try {
          // Synchronous request data survives CDP response-cache eviction.
          const command = route.request().postDataJSON() as {
            expectedRevision: number;
            receiptId: string;
          };
          expect(Object.keys(command).sort()).toEqual([
            'expectedRevision',
            'receiptId',
          ]);
          expect(command.receiptId).toMatch(/^[0-9a-f-]{36}$/);
          expect(Number.isSafeInteger(command.expectedRevision)).toBe(true);
          expect(command.expectedRevision).toBeGreaterThanOrEqual(0);
          const completion = await completed;
          if ('error' in completion) throw completion.error;
          const original = completion.response;
          expect(original.status()).toBe(200);
          const request = original.request();
          const headers = request.headers();
          const key = headers['idempotency-key'];
          const csrf = headers['x-csrf-token'];
          if (!key || !csrf) throw new Error('Missing actual command guards');
          expect(headers.origin).toBe(origin);
          expect(request.postDataJSON()).toEqual({});
          const replay = await page.request.post(request.url(), {
            data: {},
            headers: {
              Origin: origin,
              'X-CSRF-TOKEN': csrf,
              'Idempotency-Key': key,
            },
          });
          expect(replay.status()).toBe(200);
          const body = (await replay.json()) as Verified;
          expect(Object.keys(body).sort()).toEqual(['receiptId', 'uploadId']);
          expect(body.receiptId).toBe(command.receiptId);
          expect(body.uploadId).toMatch(/^[0-9a-f-]{36}$/);
          expect(new URL(request.url()).pathname).toBe(
            `/api/v1/dm/documents/uploads/${body.uploadId}/complete`,
          );
          observation = { body, expectedRevision: command.expectedRevision };
        } catch (error) {
          // Resolve failures as data: the test explicitly awaits/rethrows them,
          // so route callbacks never leak an unhandled rejected promise.
          observation = { error };
        } finally {
          try {
            // The browser's original authenticated request is unchanged.
            await route.continue();
          } catch (error) {
            observation = { error };
          }
          report(observation!);
        }
      };
      await page.route(appendUrl, barrier);
      let verification: { body: Verified; expectedRevision: number };
      try {
        await page
          .getByRole('button', { name: 'Upload and append', exact: true })
          .click();
        const observation = await observed;
        if ('error' in observation) throw observation.error;
        verification = observation;
        const appendResult = await appended;
        if ('error' in appendResult) throw appendResult.error;
        expect(appendResult.status).toBe(201);
        await expect(
          page.getByRole('button', {
            name: 'Download verified content',
            exact: true,
          }),
        ).toBeVisible({ timeout: 300_000 });
      } finally {
        await page.unroute(appendUrl, barrier);
      }
      const pendingContent = page.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          new URL(response.url()).pathname.endsWith('/content'),
        { timeout: 120_000 },
      );
      const pendingDownload = page.waitForEvent('download', {
        timeout: 120_000,
      });
      await page
        .getByRole('button', { name: 'Download verified content', exact: true })
        .click();
      const contentResponse = await pendingContent;
      const contentHeaders = await contentResponse.allHeaders();
      console.log(
        'Exact content response',
        JSON.stringify({
          status: contentResponse.status(),
          type: contentHeaders['content-type'],
          length: contentHeaders['content-length'],
          cache: contentHeaders['cache-control'],
          options: contentHeaders['x-content-type-options'],
          encoding: contentHeaders['content-encoding'] ?? null,
          transfer: contentHeaders['transfer-encoding'] ?? null,
          dispositionValid:
            /^attachment; filename="[0-9a-f-]{36}\.(pdf|docx|pptx|xlsx)"$/.test(
              contentHeaders['content-disposition'] ?? '',
            ),
        }),
      );
      expect(contentResponse.status()).toBe(200);
      const downloaded = await Promise.race([
        pendingDownload,
        page
          .getByRole('alert')
          .first()
          .waitFor({ state: 'visible', timeout: 120_000 })
          .then(() => {
            throw new Error('Verified download displayed a safe failure');
          }),
      ]);
      const stream = await downloaded.createReadStream();
      const chunks: Buffer[] = [];
      for await (const chunk of stream) chunks.push(Buffer.from(chunk));
      const actual = Buffer.concat(chunks),
        expected = await readFile(fixture);
      expect(actual.length).toBe(expected.length);
      expect(createHash('sha256').update(actual).digest('hex')).toBe(
        createHash('sha256').update(expected).digest('hex'),
      );
      expect(downloaded.suggestedFilename()).toMatch(
        new RegExp(`^[0-9a-f-]{36}\\.${format.toLowerCase()}$`),
      );
      const discovery = await page.request.get(
        `${root}/upload-receipts/${verification.body.receiptId}/version`,
      );
      expect(discovery.status()).toBe(200);
      expect(await discovery.json()).toEqual({
        documentId: receipt.documentId,
        versionId: downloaded.suggestedFilename().split('.')[0],
      });
      const contentPath = `${root}/versions/${downloaded.suggestedFilename().split('.')[0]}/content`;
      const expectedMime = {
        PDF: 'application/pdf',
        DOCX: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        PPTX: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
        XLSX: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      }[format];
      expect(contentHeaders['content-type']).toBe(expectedMime);
      const verified = {
        path: contentPath,
        mime: expectedMime,
        ...verification.body,
      };
      lastVerified = verified;
      for (const [range, start, end] of [
        ['bytes=0-15', 0, 15],
        ['bytes=16-', 16, expected.length - 1],
        ['bytes=-8', expected.length - 8, expected.length - 1],
      ] as const) {
        const partial = await page.request.get(contentPath, {
          headers: { Accept: verified.mime, Range: range },
        });
        expect(partial.status()).toBe(206);
        expect(partial.headers()['content-range']).toBe(
          `bytes ${start}-${end}/${expected.length}`,
        );
        expect(partial.headers()['content-length']).toBe(
          String(end - start + 1),
        );
        expect(partial.headers()['content-type']).toBe(verified.mime);
        expect(partial.headers()['cache-control']).toBe('no-store');
        expect(partial.headers()['x-content-type-options']).toBe('nosniff');
        expect(await partial.body()).toEqual(expected.subarray(start, end + 1));
      }
      for (const range of [`bytes=${expected.length}-`, 'bytes=0-1,3-4']) {
        const rejected = await page.request.get(contentPath, {
          headers: { Accept: verified.mime, Range: range },
        });
        expect(rejected.status()).toBe(416);
        expect(await rejected.json()).toEqual({
          code: 'RANGE_NOT_SATISFIABLE',
        });
      }
      const currentDocument = await page.request.get(root);
      expect(currentDocument.status()).toBe(200);
      const documentAfterAppend = await currentDocument.json();
      expect(documentAfterAppend.activeVersionId).toBeNull();
      expect(documentAfterAppend.revision).toBe(
        verification.expectedRevision + 1,
      );
      console.log(`Real upload ${scheme} ${format}: exact download passed`);
      await page
        .getByRole('button', { name: 'Cancel and clear', exact: true })
        .click();
    }
    if (lastVerified) {
      const mutationSession = (await (
        await page.request.get('/api/v1/session')
      ).json()) as { csrfToken: string };
      const mutationHeaders = {
        Origin: origin,
        'X-CSRF-TOKEN': mutationSession.csrfToken,
      };
      const currentDoc = (await (await page.request.get(root)).json()) as {
        revision: number;
      };
      const reused = await page.request.post(`${root}/versions`, {
        data: {
          expectedRevision: currentDoc.revision,
          receiptId: lastVerified.receiptId,
        },
        headers: { ...mutationHeaders, 'Idempotency-Key': crypto.randomUUID() },
      });
      expect(reused.status()).toBe(409);
      expect(await reused.json()).toEqual({ code: 'RECEIPT_CONSUMED' });
      for (const bytes of [
        Buffer.from('1234'),
        gzipSync(Buffer.from('1234')),
      ]) {
        const encoded = await page.request.put(
          `/api/v1/dm/documents/uploads/${lastVerified.uploadId}/content`,
          {
            data: bytes,
            headers: {
              ...mutationHeaders,
              'Idempotency-Key': crypto.randomUUID(),
              'Content-Type': 'application/octet-stream',
              'Content-Encoding': 'gzip',
            },
          },
        );
        expect(encoded.status()).toBe(400);
        expect(await encoded.json()).toEqual({ code: 'INVALID_REQUEST' });
      }
      const unchangedSlot = (await (
        await page.request.get(
          `/api/v1/dm/documents/uploads/${lastVerified.uploadId}/status`,
        )
      ).json()) as { state: string };
      expect(unchangedSlot.state).toBe('CONSUMED');
      const eicar = Buffer.from(
        'X5O!P%@AP[4\\PZX54(P^)7CC)7}$' +
          'EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*',
      );
      for (const [bytes, code] of [
        [eicar, 'MALWARE_DETECTED'],
        [Buffer.from('%PDF-malformed'), 'INVALID_FILE'],
      ] as const) {
        const admission = await page.request.post(
          '/api/v1/dm/documents/uploads',
          {
            data: {
              documentId: receipt.documentId,
              expectedRevision: currentDoc.revision,
              format: 'PDF',
              sizeBytes: bytes.length,
            },
            headers: {
              ...mutationHeaders,
              'Idempotency-Key': crypto.randomUUID(),
            },
          },
        );
        expect(admission.status()).toBe(201);
        const { uploadId } = (await admission.json()) as { uploadId: string };
        const transfer = await page.request.put(
          `/api/v1/dm/documents/uploads/${uploadId}/content`,
          {
            data: bytes,
            headers: {
              ...mutationHeaders,
              'Idempotency-Key': crypto.randomUUID(),
              'Content-Type': 'application/octet-stream',
            },
          },
        );
        expect(transfer.status()).toBe(200);
        const complete = await page.request.post(
          `/api/v1/dm/documents/uploads/${uploadId}/complete`,
          {
            data: {},
            headers: {
              ...mutationHeaders,
              'Idempotency-Key': crypto.randomUUID(),
            },
          },
        );
        expect(complete.status()).toBe(422);
        expect(await complete.json()).toEqual({ code });
        const state = (await (
          await page.request.get(
            `/api/v1/dm/documents/uploads/${uploadId}/status`,
          )
        ).json()) as { state: string; failureCode: string };
        expect(state.state).toBe('REJECTED');
        expect(state.failureCode).toBe(code);
        const unchanged = (await (await page.request.get(root)).json()) as {
          revision: number;
          activeVersionId: string | null;
        };
        expect(unchanged.revision).toBe(currentDoc.revision);
        expect(unchanged.activeVersionId).toBeNull();
      }
      const alternate = (initialSession.tenants as string[]).find(
        (value) => value !== tenant,
      );
      expect(alternate).toBeTruthy();
      const switchTenant = async (value: string) => {
        const session = (await (
          await page.request.get('/api/v1/session')
        ).json()) as { csrfToken: string };
        const result = await page.request.post('/api/v1/session/tenant', {
          data: { tenantId: value },
          headers: { Origin: origin, 'X-CSRF-TOKEN': session.csrfToken },
        });
        expect(result.status()).toBe(200);
      };
      await switchTenant(alternate!);
      for (const path of [
        lastVerified.path,
        `${root}/upload-receipts/${lastVerified.receiptId}/version`,
      ]) {
        const foreign = await page.request.get(path, {
          headers: {
            Accept:
              path === lastVerified.path
                ? lastVerified.mime
                : 'application/json',
          },
        });
        expect(foreign.status()).toBe(404);
        expect(await foreign.json()).toEqual({ code: 'NOT_FOUND' });
      }
      await switchTenant(tenant!);
      const session = (await (
        await page.request.get('/api/v1/session')
      ).json()) as { csrfToken: string };
      const revoke = await page.request.patch(`${root}/acl`, {
        data: {
          expectedRevision: currentDoc.revision,
          mode: 'RESTRICT',
          changes: [
            {
              operation: 'ADD_DENY',
              principal: {
                issuer: initialSession.issuer,
                subject: initialSession.subject,
              },
              action: 'document.content.review',
            },
          ],
        },
        headers: {
          Origin: origin,
          'X-CSRF-TOKEN': session.csrfToken,
          'Idempotency-Key': crypto.randomUUID(),
        },
      });
      expect(revoke.status()).toBe(200);
      for (const range of [undefined, 'bytes=0-7', 'bytes=invalid']) {
        const denied = await page.request.get(lastVerified.path, {
          headers: {
            Accept: lastVerified.mime,
            ...(range ? { Range: range } : {}),
          },
        });
        expect(denied.status()).toBe(404);
        expect(await denied.json()).toEqual({ code: 'NOT_FOUND' });
      }
      const hiddenMapping = await page.request.get(
        `${root}/upload-receipts/${lastVerified.receiptId}/version`,
      );
      expect(hiddenMapping.status()).toBe(404);
      expect(await hiddenMapping.json()).toEqual({ code: 'NOT_FOUND' });
      console.log(
        `Real negative controls ${scheme}: ranges, receipt reuse, encoded bodies, standard EICAR, malformed PDF, cross-tenant and revoked access passed`,
      );
    }
    await page
      .getByRole('link', { name: 'Workspace access', exact: true })
      .first()
      .click();

    const sessionResponse = await page.request.get('/api/v1/session');
    expect(sessionResponse.status()).toBe(200);
    const safeKeys = Object.keys(
      (await sessionResponse.json()) as object,
    ).sort();
    expect(safeKeys).toEqual([
      'csrfToken',
      'displayName',
      'issuer',
      'selectedTenant',
      'subject',
      'tenants',
    ]);
    expect(
      await page.evaluate(() => ({
        local: Object.entries(localStorage),
        session: Object.entries(sessionStorage),
      })),
    ).toEqual({ local: [['mantine-color-scheme', scheme]], session: [] });
    expect(
      await page.evaluate(() => document.cookie.includes('__Host-DKP-SESSION')),
    ).toBe(false);

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(
      page.getByRole('link', { name: 'Sign in', exact: true }),
    ).toBeVisible();
    const denied = await page.request.get('/api/v1/session');
    expect(denied.status()).toBe(401);
    await page.getByRole('link', { name: 'Documents', exact: true }).click();
    await expect(
      page.getByText('Sign in and choose a tenant to manage drafts.'),
    ).toBeVisible();
    await expect(
      page.getByRole('heading', { name: updatedTitle, exact: true }),
    ).toHaveCount(0);
  });
}

type BoundarySession = {
  issuer: string;
  subject: string;
  selectedTenant: string;
  tenants: string[];
  csrfToken: string;
};

async function boundarySession(page: Page): Promise<BoundarySession> {
  const response = await page.request.get(
    new URL('/api/v1/session', page.url()).href,
  );
  expect(response.status()).toBe(200);
  return (await response.json()) as BoundarySession;
}

async function prepareBoundaryDraft(page: Page, origin: string) {
  await page.goto(`${origin}/identity`);
  await page.getByRole('link', { name: 'Sign in', exact: true }).click();
  await expect(page.locator('#username')).toBeVisible();
  await page.locator('#username').fill(process.env.DKP_IDENTITY_USERNAME!);
  await page.locator('#password').fill(process.env.DKP_IDENTITY_PASSWORD!);
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => url.origin === origin && url.pathname === '/');
  await page.goto(`${origin}/identity`);
  const selector = page.getByRole('combobox', { name: 'Tenant' });
  const tenant = await selector.locator('option').nth(1).getAttribute('value');
  expect(tenant).toBeTruthy();
  await selector.selectOption(tenant!);
  const selected = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/session/tenant' &&
      response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Use tenant', exact: true }).click();
  expect((await selected).status()).toBe(200);
  const session = await boundarySession(page);
  expect(session.selectedTenant).toBe(tenant);
  const listed = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/dm/documents' &&
      response.request().method() === 'GET',
  );
  await page.getByRole('link', { name: 'Documents', exact: true }).click();
  expect((await listed).status()).toBe(200);
  await expect(
    page
      .getByRole('region', { name: 'Accessible drafts', exact: true })
      .getByRole('list'),
  ).toHaveCount(1);
  await expect(page.getByText('Loading drafts…', { exact: true })).toHaveCount(
    0,
  );
  const title = `Synthetic pending boundary ${crypto.randomUUID()}`;
  const description = 'Public synthetic browser boundary fixture.';
  await page.getByLabel('Title', { exact: true }).fill(title);
  await page.getByLabel('Description', { exact: true }).fill(description);
  const created = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/dm/documents' &&
      response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Create draft', exact: true }).click();
  const receipt = await replayUiCreate(page.request, await created, {
    origin,
    title,
    description,
  });
  await expect(
    page
      .getByRole('status')
      .filter({ hasText: `Saved document ${receipt.documentId}, revision 0.` }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Create draft', exact: true }),
  ).toBeEnabled();
  for (
    let count = 0;
    count < 20 &&
    (await page.getByRole('button', { name: title, exact: true }).count()) ===
      0;
    count++
  ) {
    const next = page.getByRole('button', { name: 'Next page', exact: true });
    await expect(next).toBeVisible();
    const nextPage = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === '/api/v1/dm/documents' &&
        response.request().method() === 'GET',
    );
    await next.click();
    expect((await nextPage).status()).toBe(200);
    await expect(
      page
        .getByRole('region', { name: 'Accessible drafts', exact: true })
        .getByRole('list'),
    ).toHaveCount(1);
    await expect(
      page.getByText('Loading drafts…', { exact: true }),
    ).toHaveCount(0);
  }
  await page.getByRole('button', { name: title, exact: true }).click();
  await expect(
    page.getByRole('heading', { name: title, exact: true }),
  ).toBeVisible();
  await confirmSingleCreate(
    page.request,
    { origin, title, description },
    receipt.documentId,
  );
  await page
    .getByRole('button', { name: 'Use current metadata', exact: true })
    .click();
  const unsavedTitle = `${title} unsaved`;
  await page.getByLabel('Title', { exact: true }).fill(unsavedTitle);
  await page
    .getByLabel('Description', { exact: true })
    .fill('Unsaved form must survive a theme change.');
  await page.getByLabel('Document format', { exact: true }).selectOption('PDF');
  const fixture = fileURLToPath(
    new URL('./fixtures/synthetic.pdf', import.meta.url),
  );
  await page.locator('input[type=file]').setInputFiles(fixture);
  return {
    session,
    title,
    unsavedTitle,
    fixture,
    root: `/api/v1/dm/documents/${receipt.documentId}`,
    documentId: receipt.documentId,
  };
}

async function observeThrottledUpload(
  page: Page,
  context: BrowserContext,
  origin: string,
  root: string,
  expectedCsrf: string,
) {
  const cdp = await context.newCDPSession(page);
  let removeListeners = () => {};
  const release = () =>
    cdp.send('Network.emulateNetworkConditionsByRule', {
      offline: false,
      matchedNetworkConditions: [],
    });
  const dispose = async () => {
    removeListeners();
    try {
      await release();
    } finally {
      await cdp.detach();
    }
  };
  try {
    await cdp.send('Network.enable');
    // Real Chromium transport only: no Fetch interception, fabricated reply, or altered body.
    // Pinned Chromium exposes rule IDs on the exact outgoing binary request.
    // https://chromedevtools.github.io/devtools-protocol/tot/Network/#method-emulateNetworkConditionsByRule
    const rules = await cdp.send('Network.emulateNetworkConditionsByRule', {
      offline: false,
      matchedNetworkConditions: [
        {
          urlPattern: `${origin}/api/v1/dm/documents/uploads/*/content`,
          latency: 0,
          downloadThroughput: -1,
          uploadThroughput: 1,
        },
      ],
    });
    expect(rules.ruleIds).toHaveLength(1);
    const binary: Request[] = [],
      completes: Request[] = [],
      appends: Request[] = [],
      initializations: Request[] = [];
    const requests: Request[] = [];
    const canceled = new Map<
      string,
      { canceled?: boolean; errorText: string }
    >();
    const conditions = new Map<string, string | undefined>();
    const wireGuards = new Map<
      string,
      {
        origin: string | undefined;
        csrf: string | undefined;
        key: string | undefined;
      }
    >();
    const finished = new Set<string>();
    let binaryId: string | undefined;
    const observe = (request: Request) => {
      if (new URL(request.url()).origin !== origin) return;
      requests.push(request);
      const path = new URL(request.url()).pathname;
      if (
        request.method() === 'PUT' &&
        /^\/api\/v1\/dm\/documents\/uploads\/[0-9a-f-]{36}\/content$/.test(path)
      )
        binary.push(request);
      if (request.method() === 'POST' && path.endsWith('/complete'))
        completes.push(request);
      if (request.method() === 'POST' && path === `${root}/versions`)
        appends.push(request);
      if (
        request.method() === 'POST' &&
        path === '/api/v1/dm/documents/uploads'
      )
        initializations.push(request);
    };
    const responses: Response[] = [];
    const observeResponse = (response: Response) => responses.push(response);
    page.on('request', observe);
    page.on('response', observeResponse);
    removeListeners = () => {
      page.off('request', observe);
      page.off('response', observeResponse);
    };
    cdp.on('Network.requestWillBeSent', ({ requestId, request }) => {
      if (
        request.method === 'PUT' &&
        new URL(request.url).origin === origin &&
        /^\/api\/v1\/dm\/documents\/uploads\/[0-9a-f-]{36}\/content$/.test(
          new URL(request.url).pathname,
        )
      )
        binaryId = requestId;
    });
    cdp.on(
      'Network.requestWillBeSentExtraInfo',
      ({ requestId, appliedNetworkConditionsId, headers }) => {
        conditions.set(requestId, appliedNetworkConditionsId);
        const selected = (name: string): string | undefined => {
          const matches = Object.entries(headers).filter(
            ([key]) => key.toLowerCase() === name,
          );
          return matches.length === 1 && typeof matches[0]![1] === 'string'
            ? matches[0]![1]
            : undefined;
        };
        wireGuards.set(requestId, {
          origin: selected('origin'),
          csrf: selected('x-csrf-token'),
          key: selected('idempotency-key'),
        });
      },
    );
    cdp.on(
      'Network.loadingFailed',
      ({ requestId, canceled: wasCanceled, errorText }) =>
        canceled.set(requestId, {
          ...(wasCanceled === undefined ? {} : { canceled: wasCanceled }),
          errorText,
        }),
    );
    cdp.on('Network.loadingFinished', ({ requestId }) =>
      finished.add(requestId),
    );
    await page
      .getByRole('button', { name: 'Upload and append', exact: true })
      .click();
    await expect.poll(() => binary.length, { timeout: 10_000 }).toBe(1);
    await expect
      .poll(
        () =>
          binaryId !== undefined &&
          conditions.get(binaryId) === rules.ruleIds[0],
        { timeout: 10_000 },
      )
      .toBe(true);
    const status = () =>
      responses.find(
        (response) =>
          response.request().method() === 'GET' &&
          new URL(response.url()).pathname ===
            `/api/v1/dm/documents/uploads/${new URL(binary[0]!.url()).pathname.split('/')[6]!}/status`,
      );
    await expect.poll(() => status()?.status()).toBe(200);
    await expect(
      page.getByText(/^Upload state: (INITIALIZED|RECEIVING)$/),
    ).toBeVisible();
    await expect(
      page
        .getByRole('status')
        .filter({ hasText: 'Transferring into quarantine…' }),
    ).toBeVisible();
    const request = binary[0]!;
    const uploadId = new URL(request.url()).pathname.split('/')[6]!;
    expect(uploadId).toMatch(/^[0-9a-f-]{36}$/);
    // ExtraInfo belongs to the same requestId and reports headers already on the wire.
    // allHeaders() can wait for completion of this deliberately pending transfer.
    const wire = wireGuards.get(binaryId!);
    expect(wire?.origin).toBe(origin);
    expect(wire?.csrf === expectedCsrf).toBe(true);
    expect(
      typeof wire?.key === 'string' && /^[0-9a-f-]{36}$/.test(wire.key),
    ).toBe(true);
    expect(canceled.has(binaryId!)).toBe(false);
    expect(finished.has(binaryId!)).toBe(false);
    return {
      cdp,
      binary,
      completes,
      appends,
      initializations,
      requests,
      request,
      wire: () => wireGuards.get(binaryId!),
      responses,
      uploadId,
      failure: () => canceled.get(binaryId!),
      finished: () => finished.has(binaryId!),
      release,
      dispose,
    };
  } catch (error) {
    await dispose();
    throw error;
  }
}

if (uploadProfile === 'true') {
  // The successful command releases admission before the cancellation case.
  // A deliberately aborted INITIALIZED slot can legitimately remain charged
  // until its fixed server expiry; never require a sixth same-tenant admission.
  test('real HTTPS keyboard theme changes preserve one pending upload command, form and route through verified download', async ({
    page,
    context,
    baseURL,
  }) => {
    test.setTimeout(180_000);
    const origin = new URL(baseURL!).origin;
    const draft = await prepareBoundaryDraft(page, origin);
    const upload = await observeThrottledUpload(
      page,
      context,
      origin,
      draft.root,
      draft.session.csrfToken,
    );
    const route = page.url();
    const theme = page.getByRole('switch', { name: 'Light theme' });
    const startingLight = await theme.isChecked();
    const protectedReads = () =>
      upload.requests.filter(
        (request) =>
          request.method() === 'GET' &&
          /^\/api\/v1\/(session|dm|context)(?:\/|$)/.test(
            new URL(request.url()).pathname,
          ),
      ).length;
    const beforeReads = protectedReads();
    const key = upload.wire()?.key;
    try {
      for (const expected of [!startingLight, startingLight]) {
        await theme.focus();
        await theme.press('Space');
        await expect(theme).toBeChecked({ checked: expected });
        await expect(theme).toBeFocused();
        await expect(page.locator('html')).toHaveAttribute(
          'data-mantine-color-scheme',
          expected ? 'light' : 'dark',
        );
        await page.evaluate(
          () =>
            new Promise<void>((resolve) =>
              requestAnimationFrame(() =>
                requestAnimationFrame(() => resolve()),
              ),
            ),
        );
        expect(page.url()).toBe(route);
        await expect(page.getByLabel('Title', { exact: true })).toHaveValue(
          draft.unsavedTitle,
        );
        await expect(
          page.getByLabel('Description', { exact: true }),
        ).toHaveValue('Unsaved form must survive a theme change.');
        await expect(
          page.getByLabel('Document format', { exact: true }),
        ).toHaveValue('PDF');
        await expect(
          page.getByText('synthetic.pdf', { exact: true }),
        ).toBeVisible();
        await expect(
          page
            .getByRole('status')
            .filter({ hasText: 'Transferring into quarantine…' }),
        ).toBeVisible();
        expect(upload.failure()).toBeUndefined();
        expect(upload.finished()).toBe(false);
        expect(upload.initializations.length).toBe(1);
        expect(upload.binary.length).toBe(1);
        expect(upload.completes.length).toBe(0);
        expect(upload.appends.length).toBe(0);
        expect(protectedReads()).toBe(beforeReads);
        expect(upload.binary[0] === upload.request).toBe(true);
        expect(
          upload.wire()?.key === key &&
            upload.wire()?.origin === origin &&
            upload.wire()?.csrf === draft.session.csrfToken,
        ).toBe(true);
        const session = await boundarySession(page);
        expect(
          session.issuer === draft.session.issuer &&
            session.subject === draft.session.subject &&
            session.selectedTenant === draft.session.selectedTenant &&
            session.csrfToken === draft.session.csrfToken,
        ).toBe(true);
      }
      await upload.release();
      await expect
        .poll(
          () =>
            upload.responses
              .find((response) => response.request() === upload.request)
              ?.status(),
          { timeout: 30_000 },
        )
        .toBe(200);
      // Completed requests expose full Playwright headers without blocking the boundary.
      const guards = await upload.request.allHeaders();
      expect(guards.origin).toBe(origin);
      expect(guards['x-csrf-token'] === draft.session.csrfToken).toBe(true);
      expect(guards['idempotency-key'] === key).toBe(true);
      await expect
        .poll(
          () =>
            upload.responses
              .find(
                (response) =>
                  response.request().method() === 'POST' &&
                  new URL(response.url()).pathname === `${draft.root}/versions`,
              )
              ?.status(),
          { timeout: 30_000 },
        )
        .toBe(201);
      await expect(
        page.getByRole('button', {
          name: 'Download verified content',
          exact: true,
        }),
      ).toBeVisible({ timeout: 120_000 });
      const downloaded = page.waitForEvent('download');
      await page
        .getByRole('button', { name: 'Download verified content', exact: true })
        .click();
      const result = await downloaded;
      const hash = createHash('sha256');
      for await (const chunk of await result.createReadStream())
        hash.update(chunk);
      expect(hash.digest('hex')).toBe(
        createHash('sha256')
          .update(await readFile(draft.fixture))
          .digest('hex'),
      );
      expect(result.suggestedFilename()).toMatch(/^[0-9a-f-]{36}\.pdf$/);
      expect(upload.initializations.length).toBe(1);
      expect(upload.binary.length).toBe(1);
      expect(upload.completes.length).toBe(1);
      expect(upload.appends.length).toBe(1);
      expect(upload.failure()).toBeUndefined();
      const current = await page.request.get(draft.root);
      expect(current.status()).toBe(200);
      expect((await current.json()).revision).toBe(1);
      const session = await boundarySession(page);
      expect(
        session.issuer === draft.session.issuer &&
          session.subject === draft.session.subject &&
          session.selectedTenant === draft.session.selectedTenant &&
          session.csrfToken === draft.session.csrfToken,
      ).toBe(true);
    } finally {
      await upload.dispose();
    }
  });
  test('real HTTPS tenant change during pending binary upload aborts old scoped work without verify or append', async ({
    baseURL,
  }) => {
    test.setTimeout(120_000);
    const origin = new URL(baseURL!).origin;
    const native = await nativeBoundaryBrowser(
      process.env.DKP_IDENTITY_IDP_ADDRESS ?? '',
    );
    const { page, context } = native;
    try {
      // This fresh native browser must prove trust before it receives OIDC credentials.
      expect((await page.goto(origin))?.status()).toBe(200);
      const provider = new URL(process.env.DKP_IDENTITY_IDP_BASE_URL!);
      expect(provider.protocol).toBe('https:');
      expect(
        (
          await page.goto(
            new URL('/realms/dkp/.well-known/openid-configuration', provider)
              .href,
          )
        )?.status(),
      ).toBe(200);
      const wrongName = new URL(origin);
      wrongName.hostname = 'wrong-name.localhost';
      // Chromium may commit its error page after goto rejects. Keep that
      // expected negative probe separate from the page that receives credentials.
      const hostnameProbe = await context.newPage();
      try {
        await expect(hostnameProbe.goto(wrongName.href)).rejects.toThrow(
          'ERR_CERT_COMMON_NAME_INVALID',
        );
      } finally {
        await hostnameProbe.close();
      }
      await page.bringToFront();
      const draft = await prepareBoundaryDraft(page, origin);
      const other = draft.session.tenants.find(
        (value) => value !== draft.session.selectedTenant,
      );
      expect(other).toBeTruthy();
      // Keep Documents mounted: changing tenant in another real tab must trigger
      // the first tab's current-session recheck/remount, not a navigation-only abort.
      const identity = await context.newPage();
      const identityResponses: Response[] = [];
      const observeIdentity = (response: Response) =>
        identityResponses.push(response);
      identity.on('response', observeIdentity);
      await identity.goto(`${origin}/identity`);
      await expect(
        identity.getByRole('heading', { name: /^Signed in as / }),
      ).toBeVisible();
      await page.bringToFront();
      const upload = await observeThrottledUpload(
        page,
        context,
        origin,
        draft.root,
        draft.session.csrfToken,
      );
      try {
        const failed = page
          .waitForEvent('requestfailed', {
            predicate: (request) => request === upload.request,
            timeout: 15_000,
          })
          .then(
            () => ({ failed: true as const }),
            (error: unknown) => ({ error }),
          );
        await identity.bringToFront();
        await expect
          .poll(() => page.evaluate(() => document.visibilityState))
          .toBe('hidden');
        await identity
          .getByRole('combobox', { name: 'Tenant' })
          .selectOption(other!);
        const beforeChange = identityResponses.length;
        await identity
          .getByRole('button', { name: 'Use tenant', exact: true })
          .click();
        await expect
          .poll(() =>
            identityResponses
              .slice(beforeChange)
              .find(
                (response) =>
                  response.request().method() === 'POST' &&
                  new URL(response.url()).pathname === '/api/v1/session/tenant',
              )
              ?.status(),
          )
          .toBe(200);
        const beforeSession = upload.responses.length;
        await page.bringToFront();
        await expect
          .poll(() =>
            upload.responses
              .slice(beforeSession)
              .find(
                (response) =>
                  response.request().method() === 'GET' &&
                  new URL(response.url()).pathname === '/api/v1/session',
              )
              ?.status(),
          )
          .toBe(200);
        const failure = await failed;
        if ('error' in failure) throw failure.error;
        await expect
          .poll(() => upload.failure()?.canceled, { timeout: 10_000 })
          .toBe(true);
        expect(upload.request.failure()?.errorText).toContain('ERR_ABORTED');
        expect(upload.finished()).toBe(false);
        await expect(
          page.getByRole('heading', { name: draft.title, exact: true }),
        ).toHaveCount(0);
        await expect(
          page.getByRole('heading', {
            name: 'Upload a verified version',
            exact: true,
          }),
        ).toHaveCount(0);
        await expect(page.getByLabel('Title', { exact: true })).toHaveValue('');
        await expect(
          page.getByLabel('Description', { exact: true }),
        ).toHaveValue('');
        const session = await boundarySession(page);
        expect(session.selectedTenant).toBe(other);
        expect(
          session.issuer === draft.session.issuer &&
            session.subject === draft.session.subject,
        ).toBe(true);
        const hidden = await page.request.get(
          `${origin}/api/v1/dm/documents/uploads/${upload.uploadId}/status`,
        );
        expect(hidden.status()).toBe(404);
        expect(await hidden.json()).toEqual({ code: 'NOT_FOUND' });
        const hiddenDraft = await page.request.get(origin + draft.root);
        expect(hiddenDraft.status()).toBe(404);
        // Switch back through the same genuine tenant UI; an aborted job never resurrects.
        await identity.bringToFront();
        await identity
          .getByRole('combobox', { name: 'Tenant' })
          .selectOption(draft.session.selectedTenant);
        const beforeRestore = identityResponses.length;
        await identity
          .getByRole('button', { name: 'Use tenant', exact: true })
          .click();
        await expect
          .poll(() =>
            identityResponses
              .slice(beforeRestore)
              .find(
                (response) =>
                  response.request().method() === 'POST' &&
                  new URL(response.url()).pathname === '/api/v1/session/tenant',
              )
              ?.status(),
          )
          .toBe(200);
        await page.bringToFront();
        const original = await page.request.get(origin + draft.root);
        expect(original.status()).toBe(200);
        const unchanged = await original.json();
        expect(unchanged.revision).toBe(0);
        expect(unchanged.activeVersionId).toBeNull();
        const status = await page.request.get(
          `${origin}/api/v1/dm/documents/uploads/${upload.uploadId}/status`,
        );
        expect(status.status()).toBe(200);
        expect([
          'INITIALIZED',
          'RECEIVING',
          'QUARANTINED',
          'REJECTED',
        ]).toContain((await status.json()).state);
        expect(upload.initializations.length).toBe(1);
        expect(upload.binary.length).toBe(1);
        expect(upload.completes.length).toBe(0);
        expect(upload.appends.length).toBe(0);
        await expect(
          page.getByRole('button', { name: 'Retry upload', exact: true }),
        ).toHaveCount(0);
      } finally {
        try {
          await upload.dispose();
        } finally {
          identity.off('response', observeIdentity);
          await identity.close();
        }
      }
    } finally {
      await native.close();
    }
  });
}
