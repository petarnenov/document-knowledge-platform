import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { expect, test, type Route } from '@playwright/test';
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
    // React renders a table or the bounded empty result only after the real query succeeds. CDP's
    // transport-finished observer may hang even after HTTP200; it is not UI state.
    await expect(
      page
        .getByRole('region', { name: 'Accessible drafts', exact: true })
        .getByRole('table', { name: 'Document library' })
        .or(
          page
            .getByRole('region', { name: 'Accessible drafts', exact: true })
            .getByText('No accessible drafts on this page.', { exact: true }),
        ),
    ).toHaveCount(1);
    await expect(
      page.getByText('Loading drafts…', { exact: true }),
    ).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
    const title = `Synthetic draft ${Date.now()}`;
    await page
      .getByRole('button', { name: 'New document', exact: true })
      .click();
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
      page.getByRole('heading', { name: 'Document Library', exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'New document', exact: true }),
    ).toBeEnabled();
    await expect(page.locator('.documents')).toHaveAttribute(
      'aria-busy',
      'false',
    );
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
          .getByRole('table', { name: 'Document library' })
          .or(
            page
              .getByRole('region', { name: 'Accessible drafts', exact: true })
              .getByText('No accessible drafts on this page.', { exact: true }),
          ),
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
    await page.getByText('Advanced document controls', { exact: true }).click();
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
    if (uploadProfile === 'true')
      await page
        .getByRole('button', { name: 'Upload version', exact: true })
        .click();
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
