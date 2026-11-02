// Specification-only reproducible checks; no runtime, publication or provider operations.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../../..');
const dependencyRoot = path.resolve(process.argv[2] || root);
const Ajv = require(path.join(dependencyRoot, 'node_modules/.pnpm/ajv@8.20.0/node_modules/ajv/dist/2020.js')).default;
const formats = require(path.join(dependencyRoot, 'node_modules/.pnpm/ajv-formats@2.1.1/node_modules/ajv-formats')).default;
const read = (name) => JSON.parse(fs.readFileSync(path.join(__dirname, name), 'utf8'));
const ajv = new Ajv({ strict: true, allErrors: true });
formats(ajv);
let schemas = 0;
let fixtures = 0;
function compile(schema) { schemas++; return ajv.compile(schema); }
function check(validator, fixture, valid) { fixtures++; assert.equal(validator(fixture), valid, JSON.stringify(validator.errors)); }
const baseline = JSON.parse(fs.readFileSync(path.join(root, 'contracts/openapi/v1/document-management.yaml'), 'utf8'));
const proposed = read('processing.openapi.json');
for (const [name, schema] of Object.entries(baseline.components.schemas)) {
  if (name !== 'Action') assert.deepEqual(proposed.components.schemas[name], schema, `Changed old schema ${name}`);
}
for (const [name, operations] of Object.entries(baseline.paths)) assert.deepEqual(proposed.paths[name], operations, `Changed old path ${name}`);
assert.deepEqual(proposed.components.schemas.Action.enum, [...baseline.components.schemas.Action.enum, 'document.share-to-collection', 'document.processing.request', 'document.processing.inspect', 'document.processing.cancel']);
const sharing = read('bp04-sharing-baseline.json');
for (const [name, operations] of Object.entries(sharing.paths)) assert.deepEqual(proposed.paths[name], operations, `Changed BP04 sharing path ${name}`);
for (const [name, schema] of Object.entries(sharing.schemas)) assert.deepEqual(proposed.components.schemas[name], schema, `Changed BP04 sharing schema ${name}`);
const ids = new Set();
for (const operations of Object.values(proposed.paths)) for (const [method, op] of Object.entries(operations)) {
  if (!['get', 'post', 'put', 'patch', 'delete'].includes(method)) continue;
  assert.ok(!ids.has(op.operationId)); ids.add(op.operationId);
}
const added = Object.entries(proposed.paths).filter(([p]) => !baseline.paths[p] && !sharing.paths[p]);
assert.equal(added.length, 10);
for (const [p, ops] of added.filter(([p]) => p.startsWith('/api/v1/documents/'))) {
  const mirror = proposed.paths[p.replace('/api/v1/documents/', '/api/v1/dm/documents/')];
  for (const [method, op] of Object.entries(ops)) {
    assert.ok(mirror[method]);
    assert.deepEqual(op.requestBody, mirror[method].requestBody);
    assert.deepEqual(op.responses, mirror[method].responses);
  }
}
const newNames = new Set(Object.keys(proposed.components.schemas).filter((name) => !baseline.components.schemas[name]));
const validators = {};
for (const filename of ['processing.openapi.json', 'processing-workload.openapi.json']) {
  const contract = read(filename);
  const defs = JSON.parse(JSON.stringify(contract.components.schemas).replaceAll('#/components/schemas/', '#/$defs/'));
  for (const [name, schema] of Object.entries(defs)) {
    const validate = compile({ ...schema, $defs: defs });
    if (filename === 'processing.openapi.json' && newNames.has(name)) validators[name] = validate;
    if (filename === 'processing-workload.openapi.json') validators['workload.' + name] = validate;
  }
}
for (const filename of ['processing-event.schema.json', 'workflow-input.schema.json', 'original-resolution.schema.json', 'dm-grant-catalog.schema.json']) validators[filename] = compile(read(filename));
const examples = read('examples.json');
for (const [schema, fixture] of [
  ['processing-event.schema.json', examples.event], ['workflow-input.schema.json', examples.workflow],
  ['original-resolution.schema.json', examples.resolution], ['ProcessingAcceptanceReceipt', examples.acceptance],
  ['ProcessingEnrollmentReceipt', examples.acceptance],
  ['workload.AttemptHandoffRequest', examples.handoffRequest], ['workload.AttemptHandoff', examples.handoff],
  ['workload.CheckpointRequest', examples.checkpointRequest], ['VersionProcessing', examples.notScheduled],
]) {
  const validator = validators[schema];
  check(validator, fixture, true);
  check(validator, { ...fixture, fabricatedAuthority: true }, false);
  for (const key of Object.keys(fixture)) { const missing = { ...fixture }; delete missing[key]; check(validator, missing, false); }
}
for (const invalid of [
  { ...examples.workflow, actorRef: { issuer: 'relative', subject: 'staff' } },
  { ...examples.workflow, sourceSha256: 'forged' }, { ...examples.workflow, bearer: 'synthetic-forbidden' },
  { ...examples.workflow, tenantScope: '' }, { ...examples.workflow, imageDigest: 'latest' },
  { ...examples.workflow, processingRunId: '../other' },
]) check(validators['workflow-input.schema.json'], invalid, false);
check(validators['processing-event.schema.json'], { ...examples.event, payload: { ...examples.event.payload, state: 'RUNNING' } }, false);
check(validators['processing-event.schema.json'], { ...examples.event, eventType: 'DocumentProcessingCancellationRequested', payload: { ...examples.event.payload, state: 'CANCEL_REQUESTED' } }, true);
check(validators['original-resolution.schema.json'], { ...examples.resolution, sizeBytes: 100000001 }, false);
check(validators.ProcessingAcceptanceReceipt, { ...examples.acceptance, status: 'PROCESSING' }, false);
check(validators.ProcessingAcceptanceRequest, { expectedRevision: 1, receiptId: 'opaque' }, true);
check(validators.ProcessingAcceptanceRequest, { expectedRevision: 1, receiptId: 'opaque', objectKey: 'foreign' }, false);
check(validators.ProcessingEnrollmentRequest, { expectedRevision: 9007199254740992 }, false);
check(validators.ProcessingError, { code: 'UNAVAILABLE' }, true);
check(validators.ProcessingError, { code: 'RAW_DATABASE_EXCEPTION' }, false);
const asyncapi = read('processing.asyncapi.json');
const event = read('processing-event.schema.json'); delete event.$schema; delete event.$id;
assert.deepEqual(asyncapi.components.messages.ProcessingEvent.payload, event);
const oldCatalog = JSON.parse(fs.readFileSync(path.join(root, 'openspec/changes/dkp-005-kb-policy/contracts/dm-grant-catalog.schema.json'), 'utf8'));
const expectedCatalog = structuredClone(oldCatalog);
function addActions(value) {
  if (!value || typeof value !== 'object') return;
  if (Array.isArray(value.enum) && value.enum.includes('document.version.append')) value.enum.push('document.processing.request', 'document.processing.inspect', 'document.processing.cancel');
  Object.values(value).forEach(addActions);
}
addActions(expectedCatalog);
assert.deepEqual(read('dm-grant-catalog.schema.json'), expectedCatalog);
console.log(JSON.stringify({ result: 'PASS', strictSchemas: schemas, fixtures, preservedLegacyPaths: Object.keys(baseline.paths).length, preservedBp04SharingPaths: Object.keys(sharing.paths).length, addedDirectAndBffPaths: added.length, soleActionDelta: true }));

assert.deepEqual(read('processing-workload.openapi.json').components.schemas.OriginalResolution, Object.fromEntries(Object.entries(read('original-resolution.schema.json')).filter(([key]) => !['$schema', '$id'].includes(key))));
check(validators['original-resolution.schema.json'], { ...examples.resolution, attemptEpoch: 1 }, false);
check(validators['original-resolution.schema.json'], { ...examples.resolution, runRevision: 2 }, false);
check(validators['workload.AttemptHandoffRequest'], { ...examples.handoffRequest, attemptEpoch: 1 }, false);
check(validators['workload.AttemptHandoffRequest'], { ...examples.handoffRequest, actor: examples.workflow.actorRef }, false);
check(validators['workload.CheckpointRequest'], { ...examples.checkpointRequest, observedAt: examples.resolution.observedAt }, false);
check(validators['workload.CheckpointRequest'], { ...examples.checkpointRequest, sourceSha256: 'b'.repeat(64) }, false);
require('./protocol-check.cjs');
console.log(JSON.stringify({ result: 'PASS', finalStrictSchemas: schemas, finalFixtures: fixtures, protocolRecovery: true }));
