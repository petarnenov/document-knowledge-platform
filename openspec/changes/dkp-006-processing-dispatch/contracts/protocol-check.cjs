// Specification-only finite protocol examples, not production/provider proof.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const cases = JSON.parse(fs.readFileSync(require('node:path').join(__dirname, 'protocol-fixtures.json')));
function handoff(x) {
  if (!x.authorized) return '404';
  if (!x.known) return '503';
  if (!x.prepared || !x.executionMatches || x.cancelled) return '409';
  return '200_CURRENT_ATTEMPT'; // Does not depend on dispatcher acknowledgement.
}
function checkpoint(x) {
  if (!x.authorized) return '404';
  if (!x.known) return '503';
  if (x.cancelled || !x.compatible || !x.currentEpoch) return '409';
  if (x.exists) return x.exactResolution ? '200_ORIGINAL_NO_EFFECT' : '409';
  return x.currentRevision ? '200_INSERT_ONCE' : '409';
}
function event(x) {
  if (x.seenId) return x.sameHash ? 'DUPLICATE_NO_EFFECT' : 'CONFLICT';
  if (x.sequenceOccupied) return 'CONFLICT';
  if (x.sequence === x.lastSequence + 1) return 'APPLY_OWN_RUN';
  return x.sequence > x.lastSequence + 1 ? 'BUFFER_RECONCILE_OWN_RUN' : 'RECONCILE_NO_EFFECT';
}
for (const fixture of cases) {
  assert.equal(({ handoff, checkpoint, event })[fixture.kind](fixture.input), fixture.expected, fixture.name);
}
assert.ok(cases.length >= 16);
console.log(`PASS ${cases.length} finite protocol recovery examples (runtime races remain mandatory)`);

const examples = JSON.parse(fs.readFileSync(require('node:path').join(__dirname, 'examples.json')));
function envelopeBinding(value) { return value.aggregateId === value.payload.processingRunId; }
assert.equal(envelopeBinding(examples.event), true);
assert.equal(envelopeBinding({ ...examples.event, aggregateId: examples.event.payload.documentId }), false);
