import type { ExecutionIdentity, Vocabulary, LifecycleInput, Admission, QueueStatus } from './types.js';
export type { ExecutionIdentity, Vocabulary, LifecycleInput, Admission, QueueStatus } from './types.js';
export interface NodeConfiguration { endpoint: string; producerToken: string; ca: string | Uint8Array; }
/** Preopened trusted bounded port. load performs no I/O; replace/close have one lifecycle owner. */
export interface NodeLogSpool {
  load(identity: ExecutionIdentity, credentialFingerprint: string): readonly string[];
  replace(events: readonly string[]): Promise<void>;
  close(): Promise<void>;
}
export interface NodeStatus extends QueueStatus {
  readonly invalid: number; readonly deliveryFailures: number; readonly requestActive: boolean;
  readonly blocked: boolean; readonly lastHttpStatus: number;
  readonly dispatcherAlive: boolean; readonly spooled: number; readonly unspooled: number;
  readonly spoolDegraded: boolean; readonly leaseReleased: boolean;
}
export declare class NodeLogProducer {
  constructor(identity: ExecutionIdentity, vocabulary: Vocabulary, configuration: NodeConfiguration, spool?: NodeLogSpool | null);
  emit(input: LifecycleInput): Admission;
  status(): Readonly<NodeStatus>;
  close(): void;
  whenClosed(): Promise<void>;
}
