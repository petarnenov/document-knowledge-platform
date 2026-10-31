/** Test-only acceptance contract shared by the genuine browser journey and unit probes. */
export type FaultService =
  'pg' | 'rustfs' | 'scanner' | 'inspector' | 'dm' | null;
export type FaultRoute = 'legacy-detail' | 'upload';

export function acceptsFaultUnavailable(
  status: number,
  body: Uint8Array,
  service: FaultService,
  route: FaultRoute,
): boolean {
  // The edge and BFF stay running in these faults. Their fixed gateways return
  // bounded503 JSON; arbitrary502/504 or proxy HTML is never acceptance evidence.
  if (status !== 503 || body.length === 0 || body.length > 4096 || !service)
    return false;
  let value: unknown;
  try {
    value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body));
  } catch {
    return false;
  }
  if (
    value === null ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).length !== 1 ||
    !('code' in value)
  )
    return false;
  if (route === 'upload') return value.code === 'UNAVAILABLE';
  // Existing metadata delegation has its own finite timeout code. PostgreSQL
  // may respond before the3-second gateway deadline or lose that bounded race.
  if (service === 'dm') return value.code === 'UPSTREAM_UNAVAILABLE';
  return (
    service === 'pg' &&
    (value.code === 'UNAVAILABLE' || value.code === 'UPSTREAM_UNAVAILABLE')
  );
}
