/** Framework-free v1 lifecycle encoding. No session, authority or transport dependency. */
export const LIMITS = Object.freeze({ eventBytes: 8192, batchBytes: 32768, batchEvents: 32, queueEvents: 2048, queueBytes: 8388608 });
export const EVENT_CODES = Object.freeze([
  'process.started', 'process.stopped', 'operation.started', 'operation.succeeded',
  'operation.failed', 'operation.cancelled', 'dependency.started', 'dependency.succeeded',
  'dependency.failed', 'build.started', 'build.succeeded', 'build.failed',
  'ui.route.changed', 'ui.identity.changed', 'producer.delivery.degraded', 'producer.events.lost',
]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const NIL = '00000000-0000-0000-0000-000000000000';
const SEVERITIES = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];
const OUTCOMES = ['STARTED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'RETRYING', 'REJECTED', 'UNKNOWN'];
const encoder = new TextEncoder();
const prepared = new WeakSet();
export function bytes(text) { return encoder.encode(text).byteLength; }
export function requireLog(value) { if (!value) throw new TypeError('INVALID_LOG_EVENT'); }
export function uuid(value) { return typeof value === 'string' && UUID.test(value) && value !== NIL; }
function integer(value) { return Number.isSafeInteger(value) && value >= 0; }
function keys(value, required, optional = []) {
  requireLog(value !== null && typeof value === 'object' && !Array.isArray(value));
  const actual = Object.keys(value);
  requireLog(required.every(key => Object.hasOwn(value, key)) && actual.every(key => required.includes(key) || optional.includes(key)));
}
function hex(value, length) { return typeof value === 'string' && new RegExp(`^[0-9a-f]{${length}}$`).test(value); }
function timestamp(value) {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 19) === value.slice(0, 19);
}
export function createWire(identity, vocabulary) {
  keys(identity, ['buildId', 'runId', 'instanceId', 'component', 'gitSha', 'sourceFingerprint']);
  identity = { ...identity }; // Capture primitive inputs once before validation.
  requireLog([identity.buildId, identity.runId, identity.instanceId].every(uuid) && identity.buildId !== identity.runId);
  requireLog(typeof identity.component === 'string' && /^[a-z][a-z0-9.-]{0,95}$/.test(identity.component));
  requireLog(hex(identity.gitSha, 40) && hex(identity.sourceFingerprint, 64));
  keys(vocabulary, ['events', 'phases']);
  let events = vocabulary.events, phases = vocabulary.phases;
  requireLog(Array.isArray(events) && events.length <= EVENT_CODES.length && Array.isArray(phases) && phases.length <= 256);
  events = [...events]; phases = [...phases];
  requireLog(Array.isArray(events) && events.length > 0 && events.length <= EVENT_CODES.length && new Set(events).size === events.length && events.every(code => EVENT_CODES.includes(code)));
  requireLog(Array.isArray(phases) && phases.length > 0 && phases.length <= 256 && new Set(phases).size === phases.length && phases.every(phase => typeof phase === 'string' && /^[A-Za-z0-9_.-]{1,96}$/.test(phase)));
  const execution = Object.freeze({ ...identity });
  const allowedEvents = new Set(events), allowedPhases = new Set(phases);
  return Object.freeze({ execution, prepare(input) {
    keys(input, ['eventId', 'timestamp', 'severity', 'eventName', 'correlationId', 'phase', 'outcome', 'durationMs', 'attempt', 'count', 'cleanupSettled'], ['traceId', 'spanId']);
    input = { ...input }; // Never validate one accessor value then serialize another.
    requireLog(uuid(input.eventId) && uuid(input.correlationId) && timestamp(input.timestamp));
    requireLog(SEVERITIES.includes(input.severity) && allowedEvents.has(input.eventName) && allowedPhases.has(input.phase) && OUTCOMES.includes(input.outcome));
    requireLog([input.durationMs, input.attempt, input.count].every(integer) && typeof input.cleanupSettled === 'boolean');
    requireLog(input.traceId === undefined || (hex(input.traceId, 32) && !/^0+$/.test(input.traceId)));
    requireLog(input.spanId === undefined || (input.traceId !== undefined && hex(input.spanId, 16) && !/^0+$/.test(input.spanId)));
    const event = { eventId: input.eventId, ...execution, timestamp: input.timestamp, severity: input.severity, eventName: input.eventName, correlationId: input.correlationId };
    if (input.traceId !== undefined) event.traceId = input.traceId;
    if (input.spanId !== undefined) event.spanId = input.spanId;
    event.context = Object.freeze({ phase: input.phase, outcome: input.outcome, durationMs: input.durationMs, attempt: input.attempt, attributes: Object.freeze({ count: input.count, cleanupSettled: input.cleanupSettled }) });
    Object.freeze(event);
    const text = JSON.stringify(event), size = bytes(text);
    requireLog(size <= LIMITS.eventBytes);
    const record = Object.freeze({ id: event.eventId, event, text, size });
    prepared.add(record);
    return record;
  } });
}
export function isPrepared(event) { return event !== null && typeof event === 'object' && prepared.has(event); }
export function encodeBatch(events) {
  requireLog(Array.isArray(events) && events.length > 0 && events.length <= LIMITS.batchEvents && events.every(isPrepared));
  requireLog(new Set(events.map(event => event.id)).size === events.length);
  const text = '{"schemaVersion":1,"events":[' + events.map(event => event.text).join(',') + ']}';
  requireLog(bytes(text) <= LIMITS.batchBytes);
  return text;
}
