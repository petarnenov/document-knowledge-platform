import type { ExecutionIdentity, Vocabulary, LifecycleInput, Admission, QueueStatus } from './types.js';
export type { ExecutionIdentity, Vocabulary, LifecycleInput, Admission, QueueStatus } from './types.js';
export interface BrowserOptions {
  environment?: Pick<typeof globalThis, 'location' | 'navigator' | 'fetch' | 'document' | 'addEventListener' | 'removeEventListener'>;
  now?: () => number;
}
export interface BrowserStatus extends QueueStatus {
  readonly invalid: number; readonly beaconRejected: number; readonly requestFailures: number;
  readonly captureExpired: number; readonly contextReady: boolean; readonly requestActive: boolean;
}
export declare class BrowserLogProducer {
  constructor(identity: ExecutionIdentity, vocabulary: Vocabulary, options?: BrowserOptions);
  connect(csrfToken: string): Promise<'CLOSED' | 'BUSY' | 'REJECTED' | 'READY' | 'RETIRED' | 'UNKNOWN'>;
  emit(input: LifecycleInput): Admission | 'NO_CONTEXT';
  flush(): 'NO_CONTEXT' | 'EMPTY' | 'UNKNOWN' | 'REJECTED';
  reconcile(): Promise<'NO_CONTEXT' | 'BUSY' | 'EMPTY' | 'RETIRED' | 'COMMITTED' | 'UNKNOWN'>;
  retire(): void;
  status(): Readonly<BrowserStatus>;
  close(): void;
}
