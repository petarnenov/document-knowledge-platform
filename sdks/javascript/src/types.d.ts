export type EventCode = 'process.started' | 'process.stopped' | 'operation.started' | 'operation.succeeded' | 'operation.failed' | 'operation.cancelled' | 'dependency.started' | 'dependency.succeeded' | 'dependency.failed' | 'build.started' | 'build.succeeded' | 'build.failed' | 'ui.route.changed' | 'ui.identity.changed' | 'producer.delivery.degraded' | 'producer.events.lost';
export type Severity = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL';
export type Outcome = 'STARTED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'RETRYING' | 'REJECTED' | 'UNKNOWN';
export interface ExecutionIdentity {
  readonly buildId: string; readonly runId: string; readonly instanceId: string;
  readonly component: string; readonly gitSha: string; readonly sourceFingerprint: string;
}
export interface Vocabulary { readonly events: readonly EventCode[]; readonly phases: readonly string[]; }
export interface LifecycleInput {
  readonly eventId: string; readonly timestamp: string; readonly severity: Severity;
  readonly eventName: EventCode; readonly correlationId: string;
  readonly traceId?: string; readonly spanId?: string;
  readonly phase: string; readonly outcome: Outcome; readonly durationMs: number;
  readonly attempt: number; readonly count: number; readonly cleanupSettled: boolean;
}
export type Admission = 'ACCEPTED' | 'DUPLICATE' | 'REJECTED' | 'DROPPED' | 'CLOSED';
export interface QueueStatus {
  readonly pending: number; readonly pendingBytes: number; readonly unknown: number;
  readonly committed: number; readonly rejected: number; readonly dropped: number;
  readonly discardedUnknown: number; readonly expired: number; readonly closed: boolean;
}
