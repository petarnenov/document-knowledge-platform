import { portalLogging } from './portal-logging';

/** Explicit business HTTP adapter: never intercept the logging SDK's own fetch. */
export async function portalFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const finish = portalLogging.begin('HTTP');
  try {
    const response = await fetch(input, init);
    finish(response.ok ? 'SUCCEEDED' : 'FAILED');
    return response;
  } catch (error) {
    finish(init?.signal?.aborted ? 'CANCELLED' : 'FAILED');
    throw error;
  }
}
