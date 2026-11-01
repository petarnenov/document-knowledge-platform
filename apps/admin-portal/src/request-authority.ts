/** Injected by the feature layer; transport code knows no React or query cache. */
export type RequestAuthority = {
  signal: AbortSignal;
  assertCurrent: () => void;
};
export type RequestSignal = AbortSignal | RequestAuthority;

export class AuthorityChangedError extends Error {
  constructor() {
    super('Workspace authority must be verified again.');
  }
}

export async function withAuthority<T>(
  authority: RequestSignal,
  operation: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
  const guard = 'assertCurrent' in authority ? authority : null;
  const signal = guard ? guard.signal : (authority as AbortSignal);
  guard?.assertCurrent();
  try {
    const value = await operation(signal);
    guard?.assertCurrent();
    return value;
  } catch (error) {
    guard?.assertCurrent();
    throw error;
  }
}
