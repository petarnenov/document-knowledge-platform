import type { APIRequestContext, Response } from '@playwright/test';
import { z } from 'zod';

const path = '/api/v1/dm/documents';
const receiptSchema = z
  .object({
    documentId: z.uuid().regex(/^[a-f0-9-]{36}$/),
    revision: z.literal(0),
  })
  .strict();
const commandSchema = z
  .object({
    metadata: z
      .object({
        title: z.string().min(1).max(256),
        description: z.string().max(4096),
      })
      .strict(),
    classification: z.literal('development-restricted'),
    classificationPolicyRevision: z.literal(1),
  })
  .strict();
export type IntendedCreate = {
  origin: string;
  title: string;
  description: string;
};
export type CapturedCreate = {
  status: number;
  method: string;
  url: string;
  body: string | null;
  headers: { name: string; value: string }[];
};

/** Validate the actual UI command before replaying it; never manufacture a key or body. */
export function createReplay(
  captured: CapturedCreate,
  intended: IntendedCreate,
) {
  const target = new URL(captured.url),
    origin = new URL(intended.origin);
  if (
    origin.protocol !== 'https:' ||
    origin.origin !== intended.origin ||
    captured.status !== 201 ||
    captured.method !== 'POST' ||
    target.origin !== origin.origin ||
    target.pathname !== path ||
    target.search ||
    target.hash ||
    target.username ||
    target.password ||
    captured.body === null ||
    new TextEncoder().encode(captured.body).length > 16384
  )
    throw new Error(
      'Original successful bounded same-origin UI create required',
    );
  const command = commandSchema.parse(JSON.parse(captured.body));
  if (
    command.metadata.title !== intended.title ||
    command.metadata.description !== intended.description
  )
    throw new Error('Original UI metadata differs');
  const original = (name: string) => {
    const values = captured.headers.filter(
      (h) => h.name.toLowerCase() === name,
    );
    if (values.length !== 1)
      throw new Error('One original mutation header required');
    return values[0]!.value;
  };
  const requestOrigin = original('origin'),
    csrf = original('x-csrf-token'),
    key = original('idempotency-key'),
    type = original('content-type');
  if (
    requestOrigin !== origin.origin ||
    !/^[!-~]{1,512}$/.test(csrf) ||
    !/^[!-~]{1,128}$/.test(key) ||
    type !== 'application/json'
  )
    throw new Error('Original guarded JSON command required');
  return {
    url: captured.url,
    body: captured.body,
    headers: {
      Origin: requestOrigin,
      'X-CSRF-TOKEN': csrf,
      'Idempotency-Key': key,
      'Content-Type': type,
    },
  };
}

export function createReceipt(body: Uint8Array) {
  if (body.length === 0 || body.length > 1024)
    throw new Error('Bounded create receipt required');
  return receiptSchema.parse(
    JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body)),
  );
}

/** APIRequestContext shares the real browser cookie jar. No original CDP response body is read. */
export async function replayUiCreate(
  api: APIRequestContext,
  original: Response,
  intended: IntendedCreate,
) {
  const request = original.request();
  const plan = createReplay(
    {
      status: original.status(),
      method: request.method(),
      url: request.url(),
      body: request.postData(),
      headers: await request.headersArray(),
    },
    intended,
  );
  const replay = await api.post(plan.url, {
    data: plan.body,
    headers: plan.headers,
  });
  if (
    replay.status() !== 201 ||
    replay.headers()['content-type']?.split(';')[0] !== 'application/json'
  )
    throw new Error('Current-authorized create replay must return201 JSON');
  return createReceipt(await replay.body());
}

/** Read every bounded authenticated list page to detect an accidental second creation. */
export async function confirmSingleCreate(
  api: APIRequestContext,
  intended: IntendedCreate,
  documentId: string,
) {
  let cursor: string | null = null,
    matches = 0;
  const visited = new Set<string>();
  for (let page = 0; page < 20; page++) {
    const query =
      '?limit=50' + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '');
    const response = await api.get(intended.origin + path + query);
    if (response.status() !== 200)
      throw new Error('Current authorized discovery required');
    const result = (await response.json()) as {
      items: { documentId: string; metadata: { title: string } }[];
      nextCursor: string | null;
    };
    if (
      !Array.isArray(result.items) ||
      result.items.length > 50 ||
      !(
        result.nextCursor === null ||
        (typeof result.nextCursor === 'string' &&
          result.nextCursor.length > 0 &&
          result.nextCursor.length <= 2048)
      )
    )
      throw new Error('Bounded discovery page required');
    for (const item of result.items)
      if (item.metadata.title === intended.title) {
        if (item.documentId !== documentId)
          throw new Error('Replay created another document');
        matches++;
      }
    if (result.nextCursor === null) {
      if (matches !== 1)
        throw new Error('Exactly one created document required');
      return;
    }
    if (visited.has(result.nextCursor))
      throw new Error('Discovery cursor repeated');
    visited.add(result.nextCursor);
    cursor = result.nextCursor;
  }
  throw new Error('Complete discovery exceeded finite page budget');
}
