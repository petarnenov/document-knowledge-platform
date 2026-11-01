import { z } from 'zod';
// Closed approved KB wire schemas; API results are parsed before entering query state.
export const actorSchema = z
  .object({
    issuer: z
      .string()
      .min(1)
      .max(2048)
      .refine((value) => {
        try {
          return Boolean(new URL(value).protocol);
        } catch {
          return false;
        }
      }),
    subject: z.string().min(1).max(256),
  })
  .strict();
export const knowledgeActionSchema = z.enum([
  'knowledge.collection.create',
  'knowledge.collection.inspect',
  'knowledge.collection.update',
  'knowledge.collection.archive',
  'knowledge.acl.restrict',
  'knowledge.acl.delegate',
  'knowledge.audience.manage',
  'knowledge.membership.manage',
  'knowledge.policy.evaluate',
]);
export const errorSchema = z
  .object({
    code: z.enum([
      'BAD_REQUEST',
      'UNAUTHENTICATED',
      'FORBIDDEN',
      'NOT_FOUND',
      'STALE_REVISION',
      'IDEMPOTENCY_CONFLICT',
      'STALE_POLICY',
      'INVALID_POLICY',
      'DUPLICATE_ACL_CHANGE',
      'ACL_RULE_LIMIT',
      'RESTRICT_SCOPE',
      'TARGET_NOT_GRANTABLE',
      'DELEGATION_CEILING',
      'LIMIT_EXCEEDED',
      'UNAVAILABLE',
      'TOO_MANY_REQUESTS',
      'PAYLOAD_TOO_LARGE',
      'UNSUPPORTED_MEDIA_TYPE',
      'NOT_ACCEPTABLE',
      'DUPLICATE_DOCUMENT',
      'STALE_AUTHORITY',
    ]),
  })
  .strict();
export const collectionReceiptSchema = z
  .object({
    collectionId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    revision: z.number().int().min(0).max(9007199254740991),
  })
  .strict();
export const membershipReceiptSchema = z
  .object({
    collectionId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    membershipId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    revision: z.number().int().min(0).max(9007199254740991),
  })
  .strict();
export const collectionMetadataSchema = z
  .object({
    title: z.string().min(1).max(256),
    description: z.string().max(4096),
  })
  .strict();
export const createCollectionSchema = collectionMetadataSchema;
export const updateCollectionSchema = z
  .object({
    expectedRevision: z.number().int().min(0).max(9007199254740991),
    metadata: collectionMetadataSchema,
  })
  .strict();
export const collectionStatusSchema = z.enum(['ACTIVE', 'ARCHIVED']);
export const updateStatusSchema = z
  .object({
    expectedRevision: z.number().int().min(0).max(9007199254740991),
    status: collectionStatusSchema,
    reason: z.string().min(1).max(1024),
  })
  .strict();
export const collectionSchema = z
  .object({
    collectionId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    revision: z.number().int().min(0).max(9007199254740991),
    metadata: collectionMetadataSchema,
    status: collectionStatusSchema,
    membershipRevision: z.number().int().min(0).max(9007199254740991),
    audiencePolicyRevision: z.number().int().min(1).max(9007199254740991),
    classificationCeiling: z.literal('development-restricted'),
    classificationPolicyRevision: z.literal(1),
  })
  .strict();
export const collectionPageSchema = z
  .object({
    items: z.array(collectionSchema).max(50),
    nextCursor: z.string().min(1).max(2048).nullable(),
  })
  .strict();
export const audienceSelectorSchema = z.union([
  z.object({ kind: z.literal('PRINCIPAL'), principal: actorSchema }).strict(),
  z
    .object({ kind: z.literal('TENANT'), tenant: z.string().min(1).max(256) })
    .strict(),
  z
    .object({
      kind: z.literal('FIRM'),
      issuer: z
        .string()
        .min(1)
        .max(2048)
        .refine((value) => {
          try {
            return Boolean(new URL(value).protocol);
          } catch {
            return false;
          }
        }),
      firmId: z.string().min(1).max(256),
    })
    .strict(),
  z
    .object({
      kind: z.literal('RESOURCE_SCOPE'),
      issuer: z
        .string()
        .min(1)
        .max(2048)
        .refine((value) => {
          try {
            return Boolean(new URL(value).protocol);
          } catch {
            return false;
          }
        }),
      resourceScopeId: z.string().min(1).max(256),
    })
    .strict(),
]);
export const audienceEvaluationSchema = z
  .object({
    collectionId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    audiencePolicyRevision: z.number().int().min(1).max(9007199254740991),
    decision: z.enum(['ALLOW', 'DENY']),
  })
  .strict();
export const evaluateAudienceSchema = z.object({}).strict();
export const audienceRuleSchema = z
  .object({
    effect: z.enum(['ALLOW', 'DENY']),
    selector: audienceSelectorSchema,
  })
  .strict();
export const audiencePolicySchema = z
  .object({
    collectionId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    revision: z.number().int().min(1).max(9007199254740991),
    classificationCeiling: z.literal('development-restricted'),
    classificationPolicyRevision: z.literal(1),
    rules: z
      .array(audienceRuleSchema)
      .max(100)
      .refine(
        (items) =>
          new Set(items.map((item) => JSON.stringify(item))).size ===
          items.length,
      ),
    effectiveAt: z.string().datetime({ offset: true }),
  })
  .strict();
export const updateAudienceSchema = z
  .object({
    expectedRevision: z.number().int().min(0).max(9007199254740991),
    rules: z
      .array(audienceRuleSchema)
      .max(100)
      .refine(
        (items) =>
          new Set(items.map((item) => JSON.stringify(item))).size ===
          items.length,
      ),
    reason: z.string().min(1).max(1024),
  })
  .strict();
export const aclRuleSchema = z
  .object({
    principal: actorSchema,
    action: knowledgeActionSchema,
    effect: z.enum(['ALLOW', 'DENY']),
  })
  .strict();
export const aclSchema = z
  .object({
    collectionId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    revision: z.number().int().min(0).max(9007199254740991),
    rules: z
      .array(aclRuleSchema)
      .max(100)
      .refine(
        (items) =>
          new Set(items.map((item) => JSON.stringify(item))).size ===
          items.length,
      ),
  })
  .strict();
export const aclChangeEntrySchema = z
  .object({
    operation: z.enum(['ADD_ALLOW', 'REMOVE_ALLOW', 'ADD_DENY', 'REMOVE_DENY']),
    principal: actorSchema,
    action: knowledgeActionSchema,
  })
  .strict();
export const updateAclSchema = z
  .object({
    expectedRevision: z.number().int().min(0).max(9007199254740991),
    mode: z.enum(['RESTRICT', 'DELEGATE']),
    changes: z.array(aclChangeEntrySchema).min(1).max(100),
    reason: z.string().min(1).max(1024),
  })
  .strict();
export const createMembershipSchema = z
  .object({
    expectedRevision: z.number().int().min(0).max(9007199254740991),
    documentId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    expectedDocumentRevision: z.number().int().min(0).max(9007199254740991),
    sharingScope: z.literal('SELECTED_TENANT'),
    reason: z.string().min(1).max(1024),
  })
  .strict();
export const updateMembershipSchema = z
  .object({
    expectedRevision: z.number().int().min(0).max(9007199254740991),
    expectedMembershipRevision: z.number().int().min(0).max(9007199254740991),
    expectedDocumentRevision: z.number().int().min(0).max(9007199254740991),
    state: z.enum(['ACTIVE', 'INACTIVE']),
    reason: z.string().min(1).max(1024),
  })
  .strict();
export const membershipSchema = z
  .object({
    membershipId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    collectionId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    documentId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    revision: z.number().int().min(0).max(9007199254740991),
    state: z.enum(['ACTIVE', 'INACTIVE']),
    sharingScope: z.literal('SELECTED_TENANT'),
    creator: actorSchema,
    createdAt: z.string().datetime({ offset: true }),
    reason: z.string().min(1).max(1024),
  })
  .strict();
export const membershipPageSchema = z
  .object({
    items: z.array(membershipSchema).max(50),
    nextCursor: z.string().min(1).max(2048).nullable(),
  })
  .strict();
export const createPolicySnapshotSchema = z
  .object({
    expectedDocumentRevision: z.number().int().min(0).max(9007199254740991),
  })
  .strict();
export const policyDecisionSchema = z.enum(['COMPATIBLE', 'INCOMPATIBLE']);
export const policySnapshotSchema = z
  .object({
    snapshotToken: z
      .string()
      .min(1)
      .max(128)
      .regex(new RegExp('^[A-Za-z0-9_-]{43}$')),
    documentId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    documentRevision: z.number().int().min(0).max(9007199254740991),
    classificationPolicyRevision: z.literal(1),
    decision: policyDecisionSchema,
  })
  .strict();
export const validatePolicySchema = z
  .object({
    snapshotToken: z
      .string()
      .min(1)
      .max(128)
      .regex(new RegExp('^[A-Za-z0-9_-]{43}$')),
    expectedDocumentRevision: z.number().int().min(0).max(9007199254740991),
  })
  .strict();
export const policyValidationSchema = z
  .object({
    documentId: z
      .string()
      .regex(
        new RegExp(
          '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
        ),
      ),
    decision: policyDecisionSchema,
  })
  .strict();
