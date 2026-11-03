import type { ExecutionIdentity, Vocabulary, LifecycleInput, Admission, QueueStatus } from './types.js';
export type { ExecutionIdentity, Vocabulary, LifecycleInput, Admission, QueueStatus } from './types.js';
export interface NodeConfiguration { endpoint: string; producerToken: string; ca: string | Uint8Array; }
export interface NodeStatus extends QueueStatus {
  readonly invalid: number; readonly deliveryFailures: number; readonly requestActive: boolean;
  readonly blocked: boolean; readonly lastHttpStatus: number;
}
export declare class NodeLogProducer {
  constructor(identity: ExecutionIdentity, vocabulary: Vocabulary, configuration: NodeConfiguration);
  emit(input: LifecycleInput): Admission;
  status(): Readonly<NodeStatus>;
  close(): void;
  whenClosed(): Promise<void>;
}
