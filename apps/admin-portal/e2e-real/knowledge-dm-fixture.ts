import { z } from 'zod';

const revision = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const metadata = z
  .object({
    title: z.string().min(1).max(256),
    description: z.string().max(4096),
  })
  .strict();
const draft = z
  .object({
    metadata,
    classification: z.literal('development-restricted'),
    classificationPolicyRevision: z.literal(1),
  })
  .strict();
const sharingDenial = z
  .object({
    expectedRevision: revision,
    mode: z.literal('RESTRICT'),
    changes: z.tuple([
      z
        .object({
          operation: z.literal('ADD_DENY'),
          principal: z
            .object({
              issuer: z.string().min(1).max(2048),
              subject: z.string().min(1).max(256),
            })
            .strict(),
          action: z.literal('document.share-to-collection'),
        })
        .strict(),
    ]),
  })
  .strict();
const receipt = z.object({ documentId: z.uuid(), revision }).strict();

/** KB-only mirror assertions must never silently accept another service's route. */
export function requireKbMirrorPath(path: string): void {
  const origin = 'https://kb-acceptance.invalid';
  const url = new URL(path, origin);
  const rawPath = path.split('?')[0]!;
  if (
    url.origin !== origin ||
    path.includes('#') ||
    rawPath.includes('%') ||
    url.pathname !== rawPath ||
    !/^\/api\/v1\/kb(?:\/|$)/.test(url.pathname)
  )
    throw new Error('KB mirror route required.');
}

/** Exactly the two legacy DM fixture operations used by complete-set acceptance. */
export function dmFixtureBody(
  method: string,
  path: string,
  value: unknown,
): string {
  if (method === 'POST' && path === '/api/v1/dm/documents') draft.parse(value);
  else if (
    method === 'PATCH' &&
    /^\/api\/v1\/dm\/documents\/[0-9a-f-]+\/acl$/.test(path)
  ) {
    z.uuid().parse(path.split('/')[5]);
    sharingDenial.parse(value);
  } else throw new Error('Closed legacy DM fixture operation required.');
  const body = JSON.stringify(value);
  if (new TextEncoder().encode(body).length > 16384)
    throw new Error('Legacy DM command exceeds16KiB.');
  return body;
}

/** Legacy DM gateway bounds:16KiB command,1MiB response, closed minimal receipt.
 * Its upstream3s deadline is not a claim about observable HTTPS wall latency.
 * The browser fixture retains the existing DM client's10s transport deadline.
 */
export function dmFixtureReceipt(
  status: number,
  expected: 200 | 201,
  headers: Record<string, string>,
  bytes: Uint8Array,
): z.infer<typeof receipt> {
  if (
    status !== expected ||
    bytes.byteLength > 1048576 ||
    headers['content-type']?.split(';')[0]?.trim().toLowerCase() !==
      'application/json'
  )
    throw new Error('Invalid legacy DM fixture response.');
  // Repeated no-store directives retain legacy semantics; KB's singleton rule
  // belongs exclusively to the fixed KB mirror response boundary.
  const cache = headers['cache-control']
    ?.split(',')
    .map((value) => value.trim().toLowerCase());
  if (!cache?.length || cache.some((value) => value !== 'no-store'))
    throw new Error('Legacy DM fixture must remain noncacheable.');
  return receipt.parse(
    JSON.parse(
      new TextDecoder('utf-8', { fatal: true }).decode(bytes),
    ) as unknown,
  );
}
