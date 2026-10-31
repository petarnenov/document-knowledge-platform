import { z } from 'zod';
import type { CommandReceipt } from '../../../generated-clients/typescript/types/models/CommandReceipt';
import type { AclChange } from '../../../generated-clients/typescript/types/models/AclChange';
import type { SessionView } from './identity-api';

const text = (min: number, max: number) =>
  z
    .string()
    .min(min)
    .max(max)
    .refine((value) =>
      Array.from(value).every((character) => {
        const code = character.charCodeAt(0);
        return code > 31 && (code < 127 || code > 159);
      }),
    );
const revision = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const actor = z
  .object({ issuer: text(1, 2048), subject: text(1, 256) })
  .strict();
const metadata = z
  .object({ title: text(1, 256), description: text(0, 4096) })
  .strict();
export const actions = [
  'document.create',
  'document.inspect-draft',
  'document.read',
  'document.update-metadata',
  'document.acl.restrict',
  'document.acl.delegate',
  'document.version.append',
  'document.legal-hold.place',
  'document.legal-hold.inspect',
] as const;
const action = z.enum(actions);
const receiptSchema = z.object({ documentId: z.uuid(), revision }).strict();
export const documentSchema = z
  .object({
    documentId: z.uuid(),
    tenant: text(1, 256),
    creator: actor,
    createdAt: z.iso.datetime({ offset: true }),
    revision,
    lifecycle: z.literal('DRAFT'),
    metadata,
    classification: z.literal('development-restricted'),
    classificationPolicyRevision: z.literal(1),
    activeVersionId: z.null(),
    retention: z
      .object({
        policyRef: z.literal('development-unresolved'),
        policyRevision: z.literal(1),
        resolution: z.literal('UNRESOLVED'),
        retainUntil: z.null(),
        destructiveEligible: z.literal(false),
      })
      .strict(),
  })
  .strict();
const pageSchema = z
  .object({
    items: z.array(documentSchema).max(50),
    nextCursor: text(1, 2048).nullable(),
  })
  .strict();
const aclSchema = z
  .object({
    documentId: z.uuid(),
    revision,
    rules: z
      .array(
        z
          .object({
            principal: actor,
            action,
            effect: z.enum(['ALLOW', 'DENY']),
          })
          .strict(),
      )
      .max(100),
  })
  .strict();
const holdsSchema = z
  .object({
    items: z
      .array(
        z
          .object({
            holdId: z.uuid(),
            documentId: z.uuid(),
            revision,
            active: z.literal(true),
            reason: text(1, 1024),
            placedBy: actor,
            placedAt: z.iso.datetime({ offset: true }),
          })
          .strict(),
      )
      .max(100),
  })
  .strict();
export type Draft = z.infer<typeof documentSchema>;
export type Receipt = CommandReceipt;
export type Acl = z.infer<typeof aclSchema>;
export type Holds = z.infer<typeof holdsSchema>;
export type AclCommand = AclChange;
export class DocumentRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code = 'REQUEST_FAILED',
  ) {
    super('Document request failed');
  }
}
async function request<T>(
  path: string,
  schema: z.ZodType<T>,
  signal: AbortSignal,
  mutation?: {
    method: string;
    session: SessionView;
    body: object;
    key?: string;
  },
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/v1/dm/documents${path}`, {
      method: mutation?.method ?? 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      signal: AbortSignal.any([signal, AbortSignal.timeout(10_000)]),
      headers: mutation
        ? {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': mutation.session.csrfToken,
            'Idempotency-Key': mutation.key ?? crypto.randomUUID(),
            Accept: 'application/json',
          }
        : { Accept: 'application/json' },
      ...(mutation ? { body: JSON.stringify(mutation.body) } : {}),
    });
  } catch {
    throw new DocumentRequestError(503);
  }
  if (!response.ok) {
    let code = 'REQUEST_FAILED';
    try {
      const error = z
        .object({ code: z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/) })
        .strict()
        .parse(await response.json());
      code = error.code;
    } catch {
      /* Do not expose upstream diagnostics. */
    }
    throw new DocumentRequestError(response.status, code);
  }
  const expectedStatus = mutation?.method === 'POST' ? 201 : 200;
  if (
    response.status !== expectedStatus ||
    response.headers
      .get('content-type')
      ?.split(';')[0]
      ?.trim()
      .toLowerCase() !== 'application/json'
  )
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  try {
    const length = response.headers.get('content-length');
    if (length && Number(length) > 1_048_576) throw new Error('Oversize');
    const reader = response.body?.getReader();
    if (!reader) throw new Error('Missing body');
    const chunks: Uint8Array[] = [];
    let total = 0;
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) break;
      total += chunk.value.byteLength;
      if (total > 1_048_576) {
        await reader.cancel();
        throw new Error('Oversize');
      }
      chunks.push(chunk.value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    return schema.parse(JSON.parse(new TextDecoder().decode(bytes)));
  } catch {
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  }
}
function id(value: string) {
  if (!z.uuid().safeParse(value).success) throw new DocumentRequestError(400);
  return value;
}
export async function listDrafts(
  session: SessionView,
  signal: AbortSignal,
  cursor?: string,
) {
  const result = await request(
    `?limit=20${cursor ? `&cursor=${encodeURIComponent(text(1, 2048).parse(cursor))}` : ''}`,
    pageSchema,
    signal,
  );
  if (result.items.some((item) => item.tenant !== session.selectedTenant))
    throw new DocumentRequestError(503);
  return result;
}
export async function inspectDraft(
  documentId: string,
  session: SessionView,
  signal: AbortSignal,
) {
  const draft = await request(`/${id(documentId)}`, documentSchema, signal);
  if (
    draft.documentId !== documentId ||
    draft.tenant !== session.selectedTenant
  )
    throw new DocumentRequestError(503);
  return draft;
}
export async function inspectAcl(documentId: string, signal: AbortSignal) {
  const result = await request(`/${id(documentId)}/acl`, aclSchema, signal);
  if (result.documentId !== documentId) throw new DocumentRequestError(503);
  return result;
}
export async function inspectHolds(documentId: string, signal: AbortSignal) {
  const result = await request(
    `/${id(documentId)}/legal-holds`,
    holdsSchema,
    signal,
  );
  if (result.items.some((item) => item.documentId !== documentId))
    throw new DocumentRequestError(503);
  return result;
}
export async function command(
  session: SessionView,
  signal: AbortSignal,
  kind: 'create' | 'metadata' | 'acl' | 'hold',
  body: object,
  documentId?: string,
  key?: string,
): Promise<Receipt> {
  const suffix =
    kind === 'acl' ? '/acl' : kind === 'hold' ? '/legal-holds' : '';
  const path = kind === 'create' ? '' : `/${id(documentId ?? '')}${suffix}`;
  const result = await request(path, receiptSchema, signal, {
    session,
    method: kind === 'metadata' || kind === 'acl' ? 'PATCH' : 'POST',
    body,
    ...(key ? { key } : {}),
  });
  if (documentId && result.documentId !== documentId)
    throw new DocumentRequestError(503);
  return result;
}
export function documentError(error: unknown): string {
  if (!(error instanceof DocumentRequestError))
    return 'Document services are unavailable. Try again.';
  switch (error.status) {
    case 401:
      return 'Your session has expired. Sign in again.';
    case 403:
      return 'This document action is not permitted.';
    case 404:
      return 'This document is unavailable or you do not have access.';
    case 409:
      return 'The document changed or this retry conflicts. Refresh before trying again.';
    case 400:
    case 413:
    case 415:
    case 422:
      return 'The request was rejected. Check the fields and current permissions.';
    default:
      return 'Document services are unavailable. Try again.';
  }
}
