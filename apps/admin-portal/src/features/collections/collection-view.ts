/** Presentation data, never a wire DTO or evidence of authorization.
 * A future authenticated, tenant/session-scoped query adapter must supply only
 * currently authorized records and discard them on access or context changes.
 */
export type CollectionBoundary<T> =
  | { kind: 'available'; data: T }
  | { kind: 'unavailable' | 'unknown' | 'denied' | 'conflict' };

export interface CollectionMemberView {
  membershipId: string;
  documentId: string;
  state: 'ACTIVE' | 'INACTIVE';
}

export interface CollectionAudienceView {
  revision: number;
  rules: readonly {
    effect: 'ALLOW' | 'DENY';
    selectorLabel: string;
  }[];
}

export interface CollectionView {
  collectionId: string;
  title: string;
  description: string;
  status: 'ACTIVE' | 'ARCHIVED';
  classificationCeiling: string | null;
  classificationPolicyRevision: number | null;
  memberships: CollectionBoundary<readonly CollectionMemberView[]>;
  audience: CollectionBoundary<CollectionAudienceView>;
}

export type CollectionsView = CollectionBoundary<readonly CollectionView[]>;
