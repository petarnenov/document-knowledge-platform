import type { QueryClient } from '@tanstack/react-query';
import { portalLogging, type PortalLogging } from './portal-logging';

/** Inspect cache lifecycle tags only; never keys, results, variables or errors. */
export function attachQueryLogging(client: QueryClient, logging: Pick<PortalLogging, 'begin' | 'record'> = portalLogging) {
  const spans = new WeakMap<object, ReturnType<PortalLogging['begin']>>();
  function update(target: object, phase: 'QUERY' | 'MUTATION', action: string) {
    if (action === 'fetch' || action === 'pending') spans.set(target, logging.begin(phase));
    if (action !== 'success' && action !== 'error') return;
    const finish = spans.get(target); spans.delete(target);
    if (finish) finish(action === 'success' ? 'SUCCEEDED' : 'FAILED');
    // An observer can attach after a request started; do not invent its start/duration.
    else logging.record(phase, action === 'success' ? 'operation.succeeded' : 'operation.failed',
      action === 'success' ? 'DEBUG' : 'ERROR', action === 'success' ? 'SUCCEEDED' : 'FAILED');
  }
  const query = client.getQueryCache().subscribe(event => { if (event.type === 'updated') update(event.query, 'QUERY', event.action.type); });
  const mutation = client.getMutationCache().subscribe(event => { if (event.type === 'updated') update(event.mutation, 'MUTATION', event.action.type); });
  return () => { query(); mutation(); };
}
