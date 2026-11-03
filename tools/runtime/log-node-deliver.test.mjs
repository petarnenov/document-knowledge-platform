import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import { mkdtempSync, realpathSync, mkdirSync, copyFileSync, chmodSync, readFileSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { packageDistribution, FILES } from './log-node-package.mjs';
import { deliver } from './log-node-deliver.mjs';

const original = fileURLToPath(new URL('../../sdks/javascript/', import.meta.url));
const helper = fileURLToPath(new URL('./log-node-deliver.mjs', import.meta.url));
const hash = value => createHash('sha256').update(value).digest('hex');
const canonical = value => Array.isArray(value) ? '[' + value.map(canonical).join(',') + ']'
  : value !== null && typeof value === 'object' ? '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}' : JSON.stringify(value);
const save = (path, value) => { const raw = canonical(value); writeFileSync(path, raw, { mode:0o600 }); return hash(raw); };
let tlsRoot, pfx, ca; const password = randomUUID();
before(() => {
  tlsRoot = realpathSync(mkdtempSync(join(tmpdir(), 'dkp-node-delivery-tls-')));
  const store = join(tlsRoot, 'server.p12'), certificate = join(tlsRoot, 'server.crt');
  execFileSync(process.env.DKP_TEST_KEYTOOL ?? 'keytool', ['-genkeypair','-alias','fixture','-keyalg','RSA','-keysize','2048',
    '-dname','CN=localhost','-ext','SAN=dns:localhost','-ext','BC=ca:true','-storetype','PKCS12','-keystore',store,
    '-storepass',password,'-keypass',password,'-validity','1','-noprompt'], { stdio:'ignore', timeout:20000 });
  execFileSync(process.env.DKP_TEST_KEYTOOL ?? 'keytool', ['-exportcert','-rfc','-alias','fixture','-keystore',store,
    '-storepass',password,'-file',certificate], { stdio:'ignore', timeout:10000 });
  pfx = readFileSync(store); ca = readFileSync(certificate);
});
after(() => rmSync(tlsRoot, { recursive:true, force:true }));

async function fixture(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'dkp-node-delivery-')));
  t.after(() => rmSync(root, { recursive:true, force:true }));
  const source = join(root, 'source'), output = join(root, 'package-output');
  mkdirSync(source, { mode:0o700 }); mkdirSync(join(source, 'src'), { mode:0o700 }); mkdirSync(output, { mode:0o700 });
  for (const name of FILES) { copyFileSync(join(original, name), join(source, name)); chmodSync(join(source, name), 0o600); }
  const record = packageDistribution(source, output);
  const vocabulary = { events:['build.started','build.succeeded'], phases:['BUILD'], errorCodes:[], dependencies:[], exceptionClasses:[], frames:[] };
  // These are isolated unit-fixture identities, never claimed as an actual shared build/adoption.
  const manifest = { schemaVersion:1, buildId:randomUUID(), component:'javascript-build', gitSha:'a'.repeat(40),
    sourceFingerprint:'b'.repeat(64), compiledAt:record.finishedAt, toolchainFingerprint:'c'.repeat(64),
    dependencyFingerprint:'d'.repeat(64), vocabularySha256:hash(canonical(vocabulary)), vocabulary,
    sourceFingerprintAlgorithm:'dkp-public-inputs-v1', dirty:false };
  const manifestSha256 = save(join(output, 'sdk/manifest.json'), manifest);
  const inventory = { schemaVersion:1, kind:'dkp-node-sdk-source-distribution-v1', manifestSha256,
    files:[...FILES.map(name => 'sdk/' + name), 'sdk/manifest.json'].sort().map(path => {
      const raw = readFileSync(join(output, path)); return { path, sha256:hash(raw), bytes:raw.length };
    }) };
  const artifact = join(root, 'artifact.json'), artifactSha256 = save(artifact, inventory);
  const observed = { schemaVersion:1, component:manifest.component, buildId:manifest.buildId,
    runId:randomUUID(), instanceId:randomUUID(), correlationId:randomUUID(), startedEventId:randomUUID(), finishedEventId:randomUUID(),
    startedAt:record.startedAt, finishedAt:record.finishedAt, durationMs:record.durationMs, filesChecked:7 };
  const observations = join(root, 'observations.json'), observationsSha256 = save(observations, observed);
  const received = new Map(), requests = []; let outage = false, ambiguous = false;
  const server = https.createServer({ pfx, passphrase:password }, async (request, response) => {
    try {
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks)); requests.push({ path:request.url, body });
      if (outage) { response.writeHead(503); response.end(); return; }
      if (request.url.endsWith('/batches')) for (const event of body.events) received.set(event.eventId, event);
      const ids = body.eventIds ?? body.events.map(event => event.eventId);
      const receipts = ids.map(eventId => received.has(eventId)
        ? { eventId, state:'COMMITTED', receivedAt:new Date().toISOString() } : { eventId, state:'UNKNOWN' });
      response.writeHead(200, { 'Content-Type':'application/json' });
      response.end(ambiguous ? '{"receipts":[],"receipts":[]}' : JSON.stringify({ receipts }));
    } catch { response.destroy(); }
  });
  await new Promise(resolve => server.listen(0, 'localhost', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const caFile = join(root, 'ca.pem'); writeFileSync(caFile, ca, { mode:0o600 });
  const config = { schemaVersion:1, manifestSha256, runId:observed.runId, instanceId:observed.instanceId,
    endpoint:`https://localhost:${server.address().port}/api/v1/dev-logs`, producerToken:randomUUID().replaceAll('-',''),
    caFile, spoolDirectory:join(root, 'spool') };
  const configuration = join(root, 'configuration.json'); save(configuration, config);
  return { root, output, manifest, inventory, observed, config, received, requests,
    setOutage:value => { outage = value; }, setAmbiguous:value => { ambiguous = value; },
    options:{ artifact, artifactSha256, manifestSha256, observations, observationsSha256, configuration, waitSeconds:1 } };
}
function child(options, extra = []) {
  const args = Object.entries(options).flatMap(([key,value]) => ['--' + key.replace(/[A-Z]/g, letter => '-' + letter.toLowerCase()), String(value)]);
  return new Promise((resolve, reject) => {
    const process = spawn(globalThis.process.execPath, [helper,...args,...extra], { stdio:['ignore','pipe','pipe'] });
    const stdout = [], stderr = []; let timer = setTimeout(() => { process.kill(); reject(new Error('Child timeout')); }, 15000);
    process.stdout.on('data', chunk => stdout.push(chunk)); process.stderr.on('data', chunk => stderr.push(chunk));
    process.once('error', reject); process.once('exit', code => { clearTimeout(timer); resolve({ code, stdout:Buffer.concat(stdout).toString(), stderr:Buffer.concat(stderr).toString() }); });
  });
}
test('original verified distribution emits real HTTPS events with exact scoped commit receipts', async t => {
  const f = await fixture(t), result = await deliver(f.options);
  assert.equal(result.result, 'COMMITTED'); assert.equal(result.workerStopped, true);
  assert.equal(result.status.leaseReleased, true); assert.equal(result.buildId, f.manifest.buildId);
  assert.deepEqual(Object.keys(result.eventStates).sort(), [f.observed.startedEventId,f.observed.finishedEventId].sort());
  assert.deepEqual(result.admissions, ['ACCEPTED','ACCEPTED']); assert.equal(f.received.size, 2);
  const finish = f.received.get(f.observed.finishedEventId);
  assert.equal(finish.eventName, 'build.succeeded'); assert.equal(finish.context.durationMs, f.observed.durationMs);
  assert.equal(finish.runId, f.observed.runId); assert.equal(finish.timestamp, f.observed.finishedAt);
  assert.equal(JSON.stringify(result).includes(f.config.producerToken), false);
});
test('distinct processes resume the same durable original queue after real TLS service outage', async t => {
  const f = await fixture(t); f.setOutage(true);
  const first = await child({ ...f.options, waitSeconds:0.25 }); assert.equal(first.code, 0, first.stderr);
  const pending = JSON.parse(first.stdout); assert.equal(pending.result, 'PENDING'); assert.equal(pending.workerStopped, true);
  assert.equal(pending.status.spooled, 2); assert.equal(pending.status.unspooled, 0); assert.equal(f.received.size, 0);
  const originalToken = f.config.producerToken, requestCount = f.requests.length;
  f.config.producerToken = randomUUID().replaceAll('-', ''); save(f.options.configuration, f.config);
  const changedCredential = await child(f.options); assert.equal(changedCredential.code, 1);
  assert.equal(f.requests.length, requestCount);
  f.config.producerToken = originalToken; save(f.options.configuration, f.config);
  f.setOutage(false);
  const second = await child(f.options); assert.equal(second.code, 0, second.stderr);
  const resumed = JSON.parse(second.stdout); assert.equal(resumed.result, 'COMMITTED');
  assert.equal(resumed.buildId, pending.buildId); assert.equal(resumed.runId, pending.runId);
  assert.equal(f.received.size, 2); assert.ok(f.requests.some(row => row.path.endsWith('/receipts')));
});
test('ambiguous receipts remain UNKNOWN even after the fixture received the events', async t => {
  const f = await fixture(t); f.setAmbiguous(true);
  const result = await deliver({ ...f.options, waitSeconds:0.1 });
  assert.equal(result.result, 'PENDING'); assert.deepEqual(Object.values(result.eventStates), ['UNKNOWN','UNKNOWN']);
});
test('self-consistent manifest hashes cannot authorize a malformed original-build catalog', async t => {
  const f = await fixture(t);
  f.manifest.vocabulary.frames = 'invalid';
  f.manifest.vocabularySha256 = hash(canonical(f.manifest.vocabulary));
  const path = join(f.output, 'sdk/manifest.json');
  f.options.manifestSha256 = save(path, f.manifest);
  f.inventory.manifestSha256 = f.options.manifestSha256;
  const row = f.inventory.files.find(row => row.path === 'sdk/manifest.json');
  row.sha256 = f.options.manifestSha256; row.bytes = readFileSync(path).length;
  f.options.artifactSha256 = save(f.options.artifact, f.inventory);
  f.config.manifestSha256 = f.options.manifestSha256; save(f.options.configuration, f.config);
  await assert.rejects(deliver(f.options)); assert.equal(f.requests.length, 0);
});
for (const mutation of ['artifactHash','manifestHash','file','symlink','mode','inventory','observationsHash','observations','binding','configUnknown','configDuplicate','time','ca']) {
  test('refuses ' + mutation + ' before sending', async t => {
    const f = await fixture(t), options = { ...f.options };
    if (mutation === 'artifactHash') options.artifactSha256 = '0'.repeat(64);
    if (mutation === 'manifestHash') options.manifestSha256 = '0'.repeat(64);
    if (mutation === 'file') writeFileSync(join(f.output, 'sdk/src/wire.js'), 'throw new Error("must not import");');
    if (mutation === 'symlink') { const path = join(f.output, 'sdk/src/wire.js'); rmSync(path); symlinkSync(join(original, 'src/wire.js'), path); }
    if (mutation === 'mode') chmodSync(options.configuration, 0o644);
    if (mutation === 'inventory') { f.inventory.files[0].bytes = true; options.artifactSha256 = save(options.artifact, f.inventory); }
    if (mutation === 'observationsHash') options.observationsSha256 = '0'.repeat(64);
    if (mutation === 'observations') { f.observed.filesChecked = 8; options.observationsSha256 = save(options.observations, f.observed); }
    if (mutation === 'binding') { f.config.runId = randomUUID(); save(options.configuration, f.config); }
    if (mutation === 'configUnknown') save(options.configuration, { ...f.config, registrarToken:'secret-canary' });
    if (mutation === 'configDuplicate') writeFileSync(options.configuration, '{"schemaVersion":1,"schemaVersion":1}');
    if (mutation === 'time') { f.observed.finishedAt = 'not-a-time'; options.observationsSha256 = save(options.observations, f.observed); }
    if (mutation === 'ca') chmodSync(f.config.caFile, 0o666);
    await assert.rejects(deliver(options)); assert.equal(f.requests.length, 0);
  });
}
test('invalid finite wait and CLI duplicate/unknown flags fail safely', async t => {
  const f = await fixture(t);
  for (const waitSeconds of [-1,16,NaN]) await assert.rejects(deliver({ ...f.options, waitSeconds }));
  for (const extra of [['--configuration','secret-canary'],['--unknown','secret-canary'],['--artifact']]) {
    const result = await child(f.options, extra); assert.equal(result.code, 1);
    assert.deepEqual(JSON.parse(result.stdout), { result:'FAIL', code:'LOG_NODE_DELIVERY_INVALID' });
    assert.equal(result.stdout.includes('secret-canary'), false);
  }
  assert.equal((await child({})).code, 1);
});
