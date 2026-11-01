import { errorSchema } from '../src/knowledge-schema';

export type KnowledgePhase =
  | 'start'
  | 'login'
  | 'select-tenant'
  | 'open-knowledge'
  | 'create-collection'
  | 'metadata'
  | 'audience'
  | 'attach-member'
  | 'deactivate-member'
  | 'reactivate-member'
  | 'open-settings'
  | 'archive'
  | 'snapshot'
  | 'revoke-access'
  | 'sign-out';
const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const families = [
  ['collections', '^/collections$'],
  ['collection', `^/collections/${uuid}$`],
  ['status', `^/collections/${uuid}/status$`],
  ['acl', `^/collections/${uuid}/acl$`],
  ['audience', `^/collections/${uuid}/audience$`],
  ['audience-evaluation', `^/collections/${uuid}/audience-evaluations$`],
  ['members', `^/collections/${uuid}/memberships$`],
  ['member', `^/collections/${uuid}/memberships/${uuid}$`],
  ['snapshot', `^/documents/${uuid}/policy-snapshots$`],
  ['validation', `^/documents/${uuid}/policy-validations$`],
] as const;
export function knowledgeRoute(method: string, url: string): string | null {
  let path: string;
  try {
    path = new URL(url).pathname;
  } catch {
    return null;
  }
  if (!/^\/api\/v1\/kb(?:\/|$)/.test(path)) return null;
  const verb = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(method)
    ? method
    : 'OTHER';
  const family =
    families.find(([, pattern]) =>
      new RegExp(pattern).test(path.slice(10)),
    )?.[0] ?? 'unknown';
  return `${verb}:${family}`;
}
export function finiteKnowledgeCode(bytes: Uint8Array): string {
  if (bytes.byteLength > 1024) return 'UNOBSERVED';
  try {
    return errorSchema.parse(
      JSON.parse(
        new TextDecoder('utf-8', { fatal: true }).decode(bytes),
      ) as unknown,
    ).code;
  } catch {
    return 'UNRECOGNIZED';
  }
}
export type KnowledgeObservation = {
  route: string;
  status: number | 'FAILED';
  observedElapsedMs: number | null;
  code: string;
};
export type KnowledgeUiState = {
  selection: 'present' | 'absent' | 'unobserved';
  settings: 'present' | 'absent' | 'unobserved';
  alert:
    | 'none'
    | 'session'
    | 'forbidden'
    | 'not-found'
    | 'conflict'
    | 'busy'
    | 'rejected'
    | 'unavailable'
    | 'other'
    | 'unobserved';
};
/** Acceptance-only ring. It never stores URLs, query strings, bodies or identities. */
export class KnowledgeDiagnostics {
  private phase: KnowledgePhase = 'start';
  private readonly recent: KnowledgeObservation[] = [];
  mark(phase: KnowledgePhase) {
    this.phase = phase;
  }
  observe(
    method: string,
    url: string,
    status: number | 'FAILED',
    elapsed: number | null,
  ): KnowledgeObservation | null {
    const route = knowledgeRoute(method, url);
    if (!route) return null;
    const finiteStatus =
      status === 'FAILED' ||
      (Number.isInteger(status) && status >= 100 && status <= 599)
        ? status
        : 'FAILED';
    const entry: KnowledgeObservation = {
      route,
      status: finiteStatus,
      observedElapsedMs:
        elapsed !== null && Number.isFinite(elapsed) && elapsed >= 0
          ? Math.min(900000, Math.round(elapsed))
          : null,
      code:
        finiteStatus === 'FAILED'
          ? 'TRANSPORT_FAILURE'
          : finiteStatus >= 400
            ? 'UNOBSERVED'
            : 'NONE',
    };
    if (this.recent.length === 32) this.recent.shift();
    this.recent.push(entry);
    return entry;
  }
  failure(ui: KnowledgeUiState) {
    return {
      schemaVersion: 1,
      diagnostic: 'KB_ACCEPTANCE_FAILURE',
      phase: this.phase,
      recent: this.recent.map((value) => ({ ...value })),
      ui,
    };
  }
}

/** Four actual diagnostic reads maximum, even after their observation windows expire.
 * A read is never cancelled and never joined to a business assertion.
 */
export class KnowledgeBodyObservations {
  private pending = 0;
  private disposed = false;
  observe(
    read: () => Promise<Uint8Array>,
    entry: KnowledgeObservation,
  ): boolean {
    if (this.disposed || this.pending >= 4) return false;
    this.pending++;
    let timely = true;
    const timer = setTimeout(() => {
      timely = false;
    }, 200);
    // The microtask also converts a synchronous CDP exception into a rejection.
    void Promise.resolve()
      .then(read)
      .then((bytes) => {
        if (timely && !this.disposed) entry.code = finiteKnowledgeCode(bytes);
      })
      .catch(() => {
        /* CDP eviction must never replace the original test outcome. */
      })
      .finally(() => {
        clearTimeout(timer);
        this.pending--;
      });
    return true;
  }
  dispose(): void {
    this.disposed = true;
  }
}
