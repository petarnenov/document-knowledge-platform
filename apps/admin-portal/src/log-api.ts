import { z } from 'zod';

const storedEvent = z.object({
  producerId: z.uuid(),
  scopeId: z.uuid(),
  receivedAt: z.iso.datetime({ offset: true }),
  scope: z
    .object({
      scopeKind: z.enum([
        'TENANT',
        'OPERATOR_ONLY',
        'UNATTRIBUTED',
        'AUTHENTICATED_UNSELECTED',
      ]),
    })
    .passthrough(),
  event: z
    .object({
      eventId: z.uuid(),
      buildId: z.uuid(),
      runId: z.uuid(),
      component: z.string().min(1),
      timestamp: z.iso.datetime({ offset: true }),
      severity: z.enum(['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL']),
      eventName: z.string().min(1),
      correlationId: z.uuid(),
    })
    .passthrough(),
});
const page = z.object({
  events: z.array(storedEvent).max(200),
  nextCursor: z.string().min(1).optional(),
});
export type StoredLog = z.infer<typeof storedEvent>;
export type LogFilters = {
  from: string;
  to: string;
  buildId: string;
  component: string;
  minSeverity: string;
  cursor?: string;
};
export class LogReadError extends Error {
  constructor(readonly invalidQuery: boolean) {
    super(
      invalidQuery
        ? 'Check the filter values and try again.'
        : 'Log records are unavailable. Try refreshing.',
    );
  }
}
export async function readLogs(filters: LogFilters, signal: AbortSignal) {
  const query = new URLSearchParams({
    from: filters.from,
    to: filters.to,
    limit: '50',
  });
  for (const key of [
    'buildId',
    'component',
    'minSeverity',
    'cursor',
  ] as const) {
    const value = filters[key];
    if (value) query.set(key, value);
  }
  const response = await fetch('/dev/logs/api/v1/events?' + query, {
    method: 'GET',
    credentials: 'omit',
    cache: 'no-store',
    redirect: 'error',
    signal,
  });
  if (!response.ok) throw new LogReadError(response.status === 400);
  const result = page.safeParse(await response.json());
  if (!result.success) throw new LogReadError(false);
  return result.data;
}
