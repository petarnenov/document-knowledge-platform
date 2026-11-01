import { membershipPageSchema } from '../src/knowledge-schema';

/** Acceptance-only complete traversal: never infer hidden counts from a server cursor. */
export class ExactMembershipTraversal {
  private readonly seen = new Set<string>();
  private readonly cursors = new Set<string>();
  private last = '';
  private ended = false;
  constructor(
    private readonly collectionId: string,
    private readonly allowedDocuments: ReadonlySet<string>,
    private readonly limit: number,
  ) {
    if (!Number.isInteger(limit) || limit < 1 || limit > 50)
      throw new Error('Invalid acceptance page bound.');
  }
  accept(value: unknown): string | null {
    if (this.ended) throw new Error('Page after terminal page.');
    const page = membershipPageSchema.parse(value);
    if (page.items.length > this.limit)
      throw new Error('Page exceeds requested limit.');
    for (const item of page.items) {
      if (
        item.collectionId !== this.collectionId ||
        !this.allowedDocuments.has(item.documentId) ||
        this.seen.has(item.documentId) ||
        item.membershipId <= this.last ||
        item.state !== 'ACTIVE'
      )
        throw new Error('Unexpected, duplicate, unordered or hidden member.');
      this.last = item.membershipId;
      this.seen.add(item.documentId);
    }
    if (page.nextCursor !== null) {
      if (
        page.items.length === 0 ||
        this.cursors.has(page.nextCursor) ||
        [...this.allowedDocuments, this.collectionId].some((id) =>
          page.nextCursor!.includes(id),
        )
      )
        throw new Error('Non-progressing or public-identifier cursor.');
      this.cursors.add(page.nextCursor);
    } else {
      this.ended = true;
      if (this.seen.size !== this.allowedDocuments.size)
        throw new Error('Traversal omitted an authorized member.');
    }
    return page.nextCursor;
  }
  count(): number {
    if (!this.ended) throw new Error('Traversal has not ended.');
    return this.seen.size;
  }
}
