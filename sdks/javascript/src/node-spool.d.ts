import type { ExecutionIdentity, Vocabulary } from './types.js';
import type { NodeLogSpool } from './node.js';

export declare class FileLogSpool implements NodeLogSpool {
  private constructor();
  static open(directory: string, identity: ExecutionIdentity, vocabulary: Vocabulary, credentialFingerprint: string): Promise<FileLogSpool>;
  load(identity: ExecutionIdentity, credentialFingerprint: string): readonly string[];
  replace(events: readonly string[]): Promise<void>;
  close(): Promise<void>;
}
