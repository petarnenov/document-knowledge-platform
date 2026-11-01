import { expect, it } from 'vitest';
import { ExactMembershipTraversal } from '../e2e-real/knowledge-acceptance';
const collection = '11111111-1111-4111-8111-111111111111';
const doc = (n: number) =>
  `22222222-2222-4222-8222-${String(n).padStart(12, '0')}`;
const member = (n: number) => ({
  collectionId: collection,
  documentId: doc(n),
  membershipId: `33333333-3333-4333-8333-${String(n).padStart(12, '0')}`,
  revision: 0,
  state: 'ACTIVE',
  sharingScope: 'SELECTED_TENANT',
  creator: { issuer: 'https://issuer.example', subject: 'staff' },
  createdAt: '2026-11-01T00:00:00Z',
  reason: 'Synthetic acceptance',
});
const traversal = (limit = 1) =>
  new ExactMembershipTraversal(collection, new Set([doc(1), doc(2)]), limit);
it('requires exact complete ordered sets and opaque progressing cursors', () => {
  const t = traversal();
  expect(() => t.count()).toThrow();
  expect(t.accept({ items: [member(1)], nextCursor: 'private-view-a' })).toBe(
    'private-view-a',
  );
  expect(t.accept({ items: [member(2)], nextCursor: null })).toBeNull();
  expect(t.count()).toBe(2);
  expect(() => t.accept({ items: [], nextCursor: null })).toThrow();
  const empty = new ExactMembershipTraversal(collection, new Set(), 50);
  empty.accept({ items: [], nextCursor: null });
  expect(empty.count()).toBe(0);
});
it.each([0, 51, 1.5, NaN])('rejects invalid bound %s', (limit) => {
  expect(() => traversal(limit)).toThrow();
});
it('rejects unknown counts, malformed members, overflow and omissions', () => {
  for (const page of [
    { items: [member(1)], nextCursor: null, hiddenCount: 1 },
    { items: [{ ...member(1), revision: -1 }], nextCursor: null },
    { items: [member(1), member(2)], nextCursor: null },
    { items: [member(1)], nextCursor: null },
  ])
    expect(() => traversal().accept(page)).toThrow();
});
it('rejects hidden IDs, foreign collections, inactive members and unstable order', () => {
  for (const item of [
    member(3),
    { ...member(1), collectionId: doc(1) },
    { ...member(1), state: 'INACTIVE' },
  ])
    expect(() =>
      traversal().accept({ items: [item], nextCursor: 'private' }),
    ).toThrow();
  const t = traversal();
  t.accept({ items: [member(2)], nextCursor: 'private' });
  expect(() => t.accept({ items: [member(1)], nextCursor: null })).toThrow();
  const duplicate = traversal();
  duplicate.accept({ items: [member(1)], nextCursor: 'a' });
  expect(() =>
    duplicate.accept({ items: [member(1)], nextCursor: 'b' }),
  ).toThrow();
});
it('rejects empty continuation, repeated cursors and exposed public identifiers', () => {
  expect(() =>
    traversal().accept({ items: [], nextCursor: 'private' }),
  ).toThrow();
  for (const cursor of [doc(1), collection])
    expect(() =>
      traversal().accept({ items: [member(1)], nextCursor: cursor }),
    ).toThrow();
  const t = traversal();
  t.accept({ items: [member(1)], nextCursor: 'same' });
  expect(() => t.accept({ items: [member(2)], nextCursor: 'same' })).toThrow();
});
