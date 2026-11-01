import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const schema = JSON.parse(readFileSync(new URL('./profile.schema.json', import.meta.url), 'utf8'));
export function validateProfile(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw Error('INVALID_PROFILE');
  const keys = Object.keys(value).sort();
  if (JSON.stringify(keys) !== JSON.stringify([...schema.required].sort())) throw Error('INVALID_PROFILE');
  for (const key of keys) if (value[key] !== schema.properties[key].const) throw Error('INVALID_PROFILE');
  const burst = value.receiveBytes * value.receiveMessages;
  const queued = value.initialCreditBytes + burst;
  const bridge = value.bridgePieces * value.decoderPieceBytes;
  if (queued > value.frameworkQueueBytes || value.decoderPieceBytes > value.receiveBytes) throw Error('INVALID_ENVELOPE');
  return Object.freeze({ receiveBurstBytes: burst, conditionalQueuedBytes: queued, bridgeBytes: bridge, configuredBodyAllowanceBytes: value.frameworkQueueBytes + bridge });
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const profile = JSON.parse(readFileSync(new URL('./profile.json', import.meta.url), 'utf8'));
  process.stdout.write(JSON.stringify({ result: 'PASS', evidence: 'STATIC_CANDIDATE_ONLY', ...validateProfile(profile) }) + '\n');
}
