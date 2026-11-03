/** Producer-only delivery from one verified original SDK source distribution. */
import { constants } from 'node:fs';
import * as fs from 'node:fs/promises';
import { resolve, isAbsolute, join, dirname } from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';

const FILES = [...['package.json', 'manifest.json'],
  ...['browser', 'node-https', 'node-spool', 'node', 'queue', 'responses', 'wire'].map(name => 'src/' + name + '.js'),
  ...['browser', 'node-spool', 'node', 'types'].map(name => 'src/' + name + '.d.ts')].map(name => 'sdk/' + name).sort();
const MANIFEST = ['schemaVersion','buildId','component','gitSha','sourceFingerprint','compiledAt','toolchainFingerprint',
  'dependencyFingerprint','vocabularySha256','vocabulary','sourceFingerprintAlgorithm','dirty'];
const OBSERVATIONS = ['schemaVersion','component','buildId','runId','instanceId','correlationId','startedEventId',
  'finishedEventId','startedAt','finishedAt','durationMs','filesChecked'];
const CONFIG = ['schemaVersion','manifestSha256','runId','instanceId','endpoint','producerToken','caFile','spoolDirectory'];
const hash = raw => createHash('sha256').update(raw).digest('hex');
function requireLog(value) { if (!value) throw new Error('LOG_NODE_DELIVERY_INVALID'); }
function digest(value) { requireLog(typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)); return value; }
function uuid(value) { return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value); }
function time(value) {
  requireLog(typeof value === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(value)
    && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0,19) === value.slice(0,19));
  return Date.parse(value);
}
function closed(value, fields) {
  requireLog(value !== null && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).length === fields.length && fields.every(name => Object.hasOwn(value, name)));
}
function canonical(value, depth = 0) {
  requireLog(depth <= 16);
  if (value !== null && typeof value === 'object') {
    if (Array.isArray(value)) return '[' + value.map(item => canonical(item, depth + 1)).join(',') + ']';
    return '{' + Object.keys(value).sort().map(key => {
      requireLog(/^[\x00-\x7f]*$/.test(key)); return JSON.stringify(key) + ':' + canonical(value[key], depth + 1);
    }).join(',') + '}';
  }
  requireLog(value === null || typeof value === 'boolean' || typeof value === 'string'
    || (typeof value === 'number' && Number.isSafeInteger(value)));
  const encoded = JSON.stringify(value);
  requireLog(typeof value !== 'string' || Buffer.from(JSON.parse(encoded)).toString() === value);
  return encoded;
}
function parse(raw) {
  const text = new TextDecoder('utf-8', { fatal:true }).decode(raw), value = JSON.parse(text);
  requireLog(canonical(value) === text); // Duplicate keys, alternate encodings and extra whitespace fail closed.
  return value;
}
async function file(path, limit, privateMode = true) {
  requireLog(typeof path === 'string' && isAbsolute(path) && resolve(path) === path && await fs.realpath(path) === path);
  const before = await fs.lstat(path);
  requireLog(before.isFile() && before.size > 0 && before.size <= limit
    && (privateMode ? before.uid === process.getuid() && (before.mode & 0o7777) === 0o600 : (before.mode & 0o022) === 0));
  const handle = await fs.open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const raw = await handle.readFile(), after = await handle.stat();
    requireLog(before.dev === after.dev && before.ino === after.ino && before.size === raw.length
      && after.size === raw.length && before.mtimeMs === after.mtimeMs && raw.length <= limit);
    return raw;
  } finally { await handle.close(); }
}
async function directory(path, names) {
  const info = await fs.lstat(path);
  requireLog(info.isDirectory() && info.uid === process.getuid() && (info.mode & 0o7777) === 0o700
    && await fs.realpath(path) === path);
  requireLog(JSON.stringify((await fs.readdir(path)).sort()) === JSON.stringify([...names].sort()));
}

export async function deliver(options) {
  requireLog(process.version === 'v24.22.1' && process.getuid() > 0);
  const { artifact, artifactSha256, manifestSha256, observations, observationsSha256, configuration, waitSeconds = 10 } = options;
  requireLog(typeof waitSeconds === 'number' && Number.isFinite(waitSeconds) && waitSeconds >= 0 && waitSeconds <= 15);
  const inventoryRaw = await file(artifact, 16384);
  requireLog(hash(inventoryRaw) === digest(artifactSha256)); digest(manifestSha256);
  const inventory = parse(inventoryRaw); closed(inventory, ['schemaVersion','kind','manifestSha256','files']);
  requireLog(inventory.schemaVersion === 1 && inventory.kind === 'dkp-node-sdk-source-distribution-v1'
    && inventory.manifestSha256 === manifestSha256 && Array.isArray(inventory.files) && inventory.files.length === FILES.length);
  const output = join(dirname(artifact), 'package-output');
  await directory(output, ['sdk','package-record.json']); await directory(join(output, 'sdk'), ['src','manifest.json','package.json']);
  await directory(join(output, 'sdk/src'), FILES.filter(name => name.startsWith('sdk/src/')).map(name => name.slice(8)));
  const files = new Map();
  for (let index = 0; index < FILES.length; index++) {
    const row = inventory.files[index]; closed(row, ['path','sha256','bytes']);
    requireLog(row.path === FILES[index] && Number.isSafeInteger(row.bytes) && row.bytes > 0);
    const raw = await file(join(output, row.path), row.path === 'sdk/manifest.json' ? 1048576 : 65536);
    requireLog(raw.length === row.bytes && hash(raw) === digest(row.sha256)); files.set(row.path, raw);
  }
  const manifestRaw = files.get('sdk/manifest.json'); requireLog(hash(manifestRaw) === manifestSha256);
  const manifest = parse(manifestRaw); closed(manifest, MANIFEST);
  requireLog(manifest.schemaVersion === 1 && manifest.component === 'javascript-build'
    && manifest.sourceFingerprintAlgorithm === 'dkp-public-inputs-v1' && typeof manifest.dirty === 'boolean'
    && uuid(manifest.buildId) && typeof manifest.gitSha === 'string' && /^[0-9a-f]{40}$/.test(manifest.gitSha));
  for (const name of ['sourceFingerprint','toolchainFingerprint','dependencyFingerprint','vocabularySha256']) digest(manifest[name]);
  time(manifest.compiledAt);
  closed(manifest.vocabulary, ['events','phases','errorCodes','dependencies','exceptionClasses','frames']);
  requireLog(Object.values(manifest.vocabulary).every(Array.isArray)
    && manifest.vocabulary.phases.length === 1 && manifest.vocabulary.phases[0] === 'BUILD'
    && ['errorCodes','dependencies','exceptionClasses','frames'].every(name => manifest.vocabulary[name].length === 0));
  requireLog(hash(canonical(manifest.vocabulary)) === manifest.vocabularySha256);
  const observedRaw = await file(observations, 8192); requireLog(hash(observedRaw) === digest(observationsSha256));
  const observed = parse(observedRaw), config = parse(await file(configuration, 8192));
  closed(observed, OBSERVATIONS); closed(config, CONFIG);
  requireLog(observed.schemaVersion === 1 && config.schemaVersion === 1
    && observed.component === manifest.component && observed.buildId === manifest.buildId
    && config.manifestSha256 === manifestSha256 && config.runId === observed.runId && config.instanceId === observed.instanceId);
  for (const name of ['runId','instanceId','correlationId','startedEventId','finishedEventId']) requireLog(uuid(observed[name]));
  requireLog(observed.startedEventId !== observed.finishedEventId && observed.filesChecked === 7
    && Number.isSafeInteger(observed.durationMs) && observed.durationMs >= 0
    && time(observed.finishedAt) >= time(observed.startedAt));
  const ca = await file(config.caFile, 16384, false);
  // No SDK import occurs before the entire original distribution and control binding pass validation.
  const load = name => import(pathToFileURL(join(output, 'sdk/src', name + '.js')).href);
  const [{ NodeLogProducer }, { NodeHttpsTransport }, { FileLogSpool }, { receiptStates }, { createWire }] =
    await Promise.all(['node','node-https','node-spool','responses','wire'].map(load));
  const identity = Object.fromEntries(['buildId','component','gitSha','sourceFingerprint'].map(name => [name, manifest[name]]));
  Object.assign(identity, { runId:observed.runId, instanceId:observed.instanceId });
  const vocabulary = { events:manifest.vocabulary.events, phases:manifest.vocabulary.phases };
  const transportConfiguration = { endpoint:config.endpoint, producerToken:config.producerToken, ca };
  const wire = createWire(identity, vocabulary);
  const events = [['started','build.started','STARTED',0,false], ['finished','build.succeeded','SUCCEEDED',observed.durationMs,true]]
    .map(([prefix,eventName,outcome,durationMs,cleanupSettled]) => ({ eventId:observed[prefix + 'EventId'],
      timestamp:observed[prefix + 'At'], severity:'DEBUG', eventName, correlationId:observed.correlationId,
      phase:'BUILD', outcome, durationMs, attempt:1, count:observed.filesChecked, cleanupSettled }));
  for (const event of events) wire.prepare(event);
  const transport = new NodeHttpsTransport(transportConfiguration);
  let spool;
  try { spool = await FileLogSpool.open(config.spoolDirectory, identity, vocabulary, transport.credentialFingerprint()); }
  finally { transport.close(); }
  let producer;
  try { producer = new NodeLogProducer(identity, vocabulary, transportConfiguration, spool); }
  catch (error) { await spool.close(); throw error; }
  let admissions, workerStopped = false;
  try {
    admissions = events.map(event => producer.emit(event));
    const deadline = performance.now() + waitSeconds * 1000;
    while (producer.status().pending && performance.now() < deadline) await pause(25);
  } finally {
    producer.close();
    let timer;
    try { workerStopped = await Promise.race([producer.whenClosed().then(() => true), new Promise(resolve => { timer = setTimeout(() => resolve(false), 5000); })]); }
    finally { clearTimeout(timer); }
  }
  const ids = events.map(event => event.eventId); let states = new Map(ids.map(id => [id,'UNKNOWN']));
  if (workerStopped) {
    const lookup = new NodeHttpsTransport(transportConfiguration);
    try {
      const response = await lookup.post('receipts', Buffer.from(JSON.stringify({ eventIds:ids })));
      if (response.status === 200) states = receiptStates(response.body, ids);
    } catch { /* UNKNOWN, never raw exception text or a fabricated commit. */ }
    finally { lookup.close(); }
  }
  return { result:[...states.values()].every(state => state === 'COMMITTED') ? 'COMMITTED' : 'PENDING',
    buildId:identity.buildId, runId:identity.runId, eventStates:Object.fromEntries(states), admissions,
    status:producer.status(), workerStopped };
}

export async function main(args = process.argv.slice(2)) {
  try {
    const allowed = ['artifact','artifact-sha256','manifest-sha256','observations','observations-sha256','configuration','wait-seconds'];
    const options = {}, seen = new Set(); requireLog(args.length % 2 === 0);
    for (let index = 0; index < args.length; index += 2) {
      const name = args[index].slice(2); requireLog(args[index].startsWith('--') && allowed.includes(name) && !seen.has(name));
      seen.add(name); const key = name.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
      options[key] = name === 'wait-seconds' ? Number(args[index + 1]) : args[index + 1];
    }
    requireLog(allowed.slice(0,6).every(name => seen.has(name)));
    console.log(JSON.stringify(await deliver(options))); return 0;
  } catch { console.log('{"result":"FAIL","code":"LOG_NODE_DELIVERY_INVALID"}'); return 1; }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = await main();
