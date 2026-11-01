import { z } from 'zod';
import type { Collection } from '../../../generated-clients/typescript/types/models/Collection';
import type { CollectionPage } from '../../../generated-clients/typescript/types/models/CollectionPage';
import type { MembershipPage } from '../../../generated-clients/typescript/types/models/MembershipPage';
import type { Membership } from '../../../generated-clients/typescript/types/models/Membership';
import type { SessionView } from './identity-api';
import * as schemas from './knowledge-schema';
export type KnowledgeCollection = Collection;
export type KnowledgeMembership = Membership;
export type AudienceRule = z.infer<typeof schemas.audienceRuleSchema>;
export type AclChange = z.infer<typeof schemas.aclChangeEntrySchema>;
export const knowledgeActions = schemas.knowledgeActionSchema.options;
export class KnowledgeError extends Error {
  constructor(
    readonly status: number,
    readonly code = 'UNAVAILABLE',
  ) {
    super('Knowledge request failed');
  }
}
const uuid = z
  .string()
  .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
const id = (value: string) => uuid.parse(value);
export async function knowledgeRequest<T>(
  path: string,
  schema: z.ZodType<T>,
  signal: AbortSignal,
  mutation?: {
    method: string;
    session: SessionView;
    body: object;
    key?: string;
    status?: number;
  },
): Promise<T> {
  try {
    const response = await fetch(`/api/v1/kb${path}`, {
      method: mutation?.method ?? 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      signal: AbortSignal.any([signal, AbortSignal.timeout(12000)]),
      headers: mutation
        ? {
            'Content-Type': 'application/json',
            Accept: 'application/json',
            'X-CSRF-TOKEN': mutation.session.csrfToken,
            ...(mutation.key ? { 'Idempotency-Key': mutation.key } : {}),
          }
        : { Accept: 'application/json' },
      ...(mutation ? { body: JSON.stringify(mutation.body) } : {}),
    });
    if (
      response.headers
        .get('content-type')
        ?.split(';')[0]
        ?.trim()
        .toLowerCase() !== 'application/json' ||
      response.headers.get('content-encoding') ||
      response.headers.get('cache-control') !== 'no-store'
    )
      throw new KnowledgeError(503);
    const reader = response.body?.getReader();
    if (!reader) throw new KnowledgeError(503);
    const chunks: Uint8Array[] = [];
    let total = 0;
    try {
      for (;;) {
        const next = await reader.read();
        if (next.done) break;
        total += next.value.length;
        if (total > 2097152) throw new KnowledgeError(503);
        chunks.push(next.value);
      }
    } finally {
      await reader.cancel();
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.length;
    }
    const data: unknown = JSON.parse(
      new TextDecoder('utf-8', { fatal: true }).decode(bytes),
    );
    if (!response.ok) {
      const error = schemas.errorSchema.parse(data);
      throw new KnowledgeError(response.status, error.code);
    }
    if (response.status !== (mutation?.status ?? 200))
      throw new KnowledgeError(503);
    return schema.parse(data);
  } catch (failure) {
    if (failure instanceof KnowledgeError) throw failure;
    throw new KnowledgeError(503);
  }
}
export async function listCollections(
  signal: AbortSignal,
  cursor?: string,
): Promise<CollectionPage> {
  const result = await knowledgeRequest(
    `/collections?limit=20${cursor ? `&cursor=${encodeURIComponent(z.string().min(1).max(2048).parse(cursor))}` : ''}`,
    schemas.collectionPageSchema,
    signal,
  );
  if (
    result.items.length > 20 ||
    new Set(result.items.map((item) => item.collectionId)).size !==
      result.items.length
  )
    throw new KnowledgeError(503);
  return result;
}
export async function inspectCollection(
  collectionId: string,
  signal: AbortSignal,
): Promise<KnowledgeCollection> {
  const result = await knowledgeRequest(
    `/collections/${id(collectionId)}`,
    schemas.collectionSchema,
    signal,
  );
  if (result.collectionId !== collectionId) throw new KnowledgeError(503);
  return result;
}
export async function inspectCollectionAcl(
  collectionId: string,
  signal: AbortSignal,
) {
  const result = await knowledgeRequest(
    `/collections/${id(collectionId)}/acl`,
    schemas.aclSchema,
    signal,
  );
  if (result.collectionId !== collectionId) throw new KnowledgeError(503);
  return result;
}
export async function inspectAudience(
  collectionId: string,
  signal: AbortSignal,
) {
  const result = await knowledgeRequest(
    `/collections/${id(collectionId)}/audience`,
    schemas.audiencePolicySchema,
    signal,
  );
  if (result.collectionId !== collectionId) throw new KnowledgeError(503);
  return result;
}
export async function listMembers(
  collectionId: string,
  signal: AbortSignal,
  cursor?: string,
): Promise<MembershipPage> {
  const result = await knowledgeRequest(
    `/collections/${id(collectionId)}/memberships?limit=20${cursor ? `&cursor=${encodeURIComponent(z.string().min(1).max(2048).parse(cursor))}` : ''}`,
    schemas.membershipPageSchema,
    signal,
  );
  if (
    result.items.length > 20 ||
    new Set(result.items.map((m) => m.membershipId)).size !==
      result.items.length ||
    result.items.some((m) => m.collectionId !== collectionId)
  )
    throw new KnowledgeError(503);
  return {
    ...result,
    items: result.items.map((item) => ({
      ...item,
      createdAt: new Date(item.createdAt),
    })),
  };
}
export type KnowledgeCommand =
  | 'create'
  | 'metadata'
  | 'status'
  | 'acl'
  | 'audience'
  | 'attach'
  | 'membership';
export async function knowledgeCommand(
  kind: KnowledgeCommand,
  session: SessionView,
  signal: AbortSignal,
  body: object,
  key: string,
  collectionId?: string,
  membershipId?: string,
) {
  const create = kind === 'create';
  const path = create
    ? '/collections'
    : `/collections/${id(collectionId ?? '')}${kind === 'metadata' ? '' : kind === 'attach' ? '/memberships' : kind === 'membership' ? `/memberships/${id(membershipId ?? '')}` : `/${kind}`}`;
  const requestSchema = {
    create: schemas.createCollectionSchema,
    metadata: schemas.updateCollectionSchema,
    status: schemas.updateStatusSchema,
    acl: schemas.updateAclSchema,
    audience: schemas.updateAudienceSchema,
    attach: schemas.createMembershipSchema,
    membership: schemas.updateMembershipSchema,
  }[kind];
  requestSchema.parse(body);
  const result = await knowledgeRequest(
    path,
    kind === 'attach' || kind === 'membership'
      ? schemas.membershipReceiptSchema
      : schemas.collectionReceiptSchema,
    signal,
    {
      method:
        create || kind === 'attach'
          ? 'POST'
          : kind === 'audience'
            ? 'PUT'
            : 'PATCH',
      session,
      body,
      key,
      status: create || kind === 'attach' ? 201 : 200,
    },
  );
  if (collectionId && result.collectionId !== collectionId)
    throw new KnowledgeError(503);
  if (
    membershipId &&
    (!('membershipId' in result) || result.membershipId !== membershipId)
  )
    throw new KnowledgeError(503);
  return result;
}
export async function evaluateAudience(
  collectionId: string,
  session: SessionView,
  signal: AbortSignal,
) {
  const result = await knowledgeRequest(
    `/collections/${id(collectionId)}/audience-evaluations`,
    schemas.audienceEvaluationSchema,
    signal,
    { method: 'POST', session, body: {} },
  );
  if (result.collectionId !== collectionId) throw new KnowledgeError(503);
  return result;
}
export async function snapshotPolicy(
  documentId: string,
  revision: number,
  session: SessionView,
  signal: AbortSignal,
  key: string,
) {
  const result = await knowledgeRequest(
    `/documents/${id(documentId)}/policy-snapshots`,
    schemas.policySnapshotSchema,
    signal,
    {
      method: 'POST',
      session,
      body: schemas.createPolicySnapshotSchema.parse({
        expectedDocumentRevision: revision,
      }),
      key,
      status: 201,
    },
  );
  if (result.documentId !== documentId || result.documentRevision !== revision)
    throw new KnowledgeError(503);
  return result;
}
export async function validatePolicy(
  documentId: string,
  revision: number,
  token: string,
  session: SessionView,
  signal: AbortSignal,
) {
  const result = await knowledgeRequest(
    `/documents/${id(documentId)}/policy-validations`,
    schemas.policyValidationSchema,
    signal,
    {
      method: 'POST',
      session,
      body: schemas.validatePolicySchema.parse({
        expectedDocumentRevision: revision,
        snapshotToken: token,
      }),
    },
  );
  if (result.documentId !== documentId) throw new KnowledgeError(503);
  return result;
}
export function knowledgeError(error: unknown): string {
  if (!(error instanceof KnowledgeError))
    return 'Knowledge services are unavailable. Try again.';
  switch (error.status) {
    case 401:
      return 'Your session has expired. Sign in again.';
    case 403:
      return 'This knowledge action is not permitted.';
    case 404:
      return 'This collection or policy is unavailable or you do not have access.';
    case 409:
      return 'The knowledge state changed. Refresh before trying again.';
    case 429:
      return 'Knowledge services are busy. Try again later.';
    case 400:
    case 413:
    case 415:
    case 422:
      return 'The request was rejected. Check the fields and current permissions.';
    default:
      return 'Knowledge services are unavailable. Try again.';
  }
}
