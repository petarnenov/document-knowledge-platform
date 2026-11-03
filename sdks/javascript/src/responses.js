import { LIMITS, uuid } from './wire.js';

export function validTime(value) {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 19) === value.slice(0, 19);
}
export function responseCheck(value) { if (!value) throw new TypeError('INVALID_LOG_RESPONSE'); }
export function closedObject(value, fields) {
  responseCheck(value !== null && typeof value === 'object' && !Array.isArray(value));
  const keys = Object.keys(value);
  responseCheck(keys.length === fields.length && fields.every(key => Object.hasOwn(value, key)));
}
/** Bounded strict JSON, including duplicate keys which JSON.parse would silently overwrite. */
export function parseResponse(data) {
  try {
    responseCheck(data instanceof Uint8Array && data.byteLength > 0 && data.byteLength <= LIMITS.batchBytes);
    const text = new TextDecoder('utf-8', { fatal: true }).decode(data);
    let position = 0;
    function space() { while (/[\x20\t\r\n]/.test(text[position] ?? '') && position < text.length) position++; }
    function string() {
      const start = position++;
      while (position < text.length) {
        const char = text[position++];
        if (char === '\\') { position++; continue; }
        if (char === '"') { const value = JSON.parse(text.slice(start, position)); responseCheck(value.length <= 512); return value; }
      }
      throw new TypeError();
    }
    function value(depth) {
      responseCheck(depth <= 16); space(); const char = text[position];
      if (char === '"') return string();
      if (char === '{' || char === '[') {
        position++; space(); const object = char === '{', result = object ? Object.create(null) : [], names = new Set(), end = object ? '}' : ']';
        if (text[position] === end) { position++; return result; }
        for (;;) {
          space(); let key;
          if (object) { responseCheck(text[position] === '"'); key = string(); responseCheck(!names.has(key)); names.add(key); space(); responseCheck(text[position++] === ':'); }
          const item = value(depth + 1); if (object) result[key] = item; else result.push(item);
          space(); const separator = text[position++]; if (separator === end) return result; responseCheck(separator === ',');
        }
      }
      const token = /^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(text.slice(position));
      responseCheck(token !== null); position += token[0].length; const result = JSON.parse(token[0]);
      responseCheck(typeof result !== 'number' || Number.isSafeInteger(result)); return result;
    }
    const result = value(0); space(); responseCheck(position === text.length); return result;
  } catch { throw new TypeError('INVALID_LOG_RESPONSE'); }
}
export function receiptStates(data, ids, commitOnly = false) {
  const root = parseResponse(data); closedObject(root, ['receipts']);
  responseCheck(Array.isArray(ids) && ids.length > 0 && ids.length <= LIMITS.batchEvents && new Set(ids).size === ids.length && ids.every(uuid));
  responseCheck(Array.isArray(root.receipts) && root.receipts.length === ids.length);
  const wanted = new Set(ids), result = new Map();
  for (const row of root.receipts) {
    responseCheck(row !== null && typeof row === 'object' && !Array.isArray(row));
    responseCheck(uuid(row.eventId) && wanted.delete(row.eventId));
    responseCheck(['COMMITTED', 'EXPIRED', 'UNKNOWN'].includes(row.state) && (!commitOnly || row.state === 'COMMITTED'));
    closedObject(row, row.state === 'COMMITTED' ? ['eventId', 'state', 'receivedAt'] : ['eventId', 'state']);
    if (row.state === 'COMMITTED') responseCheck(validTime(row.receivedAt));
    result.set(row.eventId, row.state);
  }
  responseCheck(wanted.size === 0); return result;
}
