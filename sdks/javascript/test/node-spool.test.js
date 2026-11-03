import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createWire } from '../src/wire.js';
import { FileLogSpool } from '../src/node-spool.js';

const id = n => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const identity = { buildId:id(1), runId:id(2), instanceId:id(3), component:'node-tools', gitSha:'a'.repeat(40), sourceFingerprint:'b'.repeat(64) };
const vocabulary = { events:['build.started'], phases:['BUILD'] };
const fingerprint = 'c'.repeat(64);
const wire = createWire(identity, vocabulary);
const input = n => ({ eventId:id(n), timestamp:'2026-11-03T13:00:00Z', severity:'DEBUG', eventName:'build.started',
  correlationId:id(4), phase:'BUILD', outcome:'STARTED', durationMs:0, attempt:1, count:5, cleanupSettled:false });
const event = n => wire.prepare(input(n)).text;
async function fixture(t) {
  const directory = await fs.realpath(await fs.mkdtemp(join(tmpdir(), 'dkp-node-spool-')));
  await fs.chmod(directory, 0o700);
  t.after(() => fs.rm(directory, { recursive:true, force:true }));
  return directory;
}
const open = directory => FileLogSpool.open(directory, identity, vocabulary, fingerprint);

test('canonical restore retains exact original bytes and rejects changed authority or raw data', () => {
  const original = event(10);
  assert.equal(wire.restore(original).text, original);
  for (const changed of [original + ' ', original.replace(identity.buildId, id(99)),
    original.replace('"severity":"DEBUG"', '"severity":"DEBUG","secret":"raw-content"'),
    original.replace('"severity":"DEBUG"', '"severity":"TRACE","severity":"DEBUG"'),
    '{"context":null}', '{}', 'private-untrusted-body', null]) {
    assert.throws(() => wire.restore(changed), { message:'INVALID_LOG_EVENT' });
  }
  const traced = wire.prepare({ ...input(11), traceId:'d'.repeat(32), spanId:'e'.repeat(16) });
  assert.equal(wire.restore(traced.text).text, traced.text);
});

test('real atomic private snapshot survives graceful close and reopening same original execution', async t => {
  const parent = await fixture(t), directory = join(parent, 'queue');
  assert.throws(() => new FileLogSpool(), /USE_FILE_LOG_SPOOL_OPEN/);
  const spool = await open(directory);
  assert.deepEqual(spool.load(identity, fingerprint), []);
  const records = [event(10), event(11)];
  await spool.replace(records);
  const raw = await fs.readFile(join(directory, 'pending.bin'));
  assert.equal((await fs.stat(directory)).mode & 0o777, 0o700);
  for (const name of ['pending.bin', 'queue.lock']) assert.equal((await fs.stat(join(directory, name))).mode & 0o777, 0o600);
  assert.ok(records.every(text => raw.includes(Buffer.from(JSON.stringify(text)))));
  await spool.close(); await spool.close();
  const recovered = await open(directory);
  assert.deepEqual(recovered.load(identity, fingerprint), records);
  await recovered.replace([]); await recovered.close();
  await assert.rejects(spool.replace(records), /LOG_SPOOL_UNAVAILABLE/);
  assert.throws(() => spool.load(identity, fingerprint), /LOG_SPOOL_UNAVAILABLE/);
});

test('second opener never steals a current or interrupted lease', async t => {
  const directory = await fixture(t), spool = await open(directory);
  const lease = await fs.readFile(join(directory, 'queue.lock'));
  await assert.rejects(open(directory), { message:'LOG_SPOOL_LOCKED' });
  assert.deepEqual(await fs.readFile(join(directory, 'queue.lock')), lease);
  await spool.close();
  const stale = JSON.stringify({ leaseId:id(90), pid:2147483647 });
  await fs.writeFile(join(directory, 'queue.lock'), stale, { mode:0o600 });
  await assert.rejects(open(directory), { message:'LOG_SPOOL_LOCKED' });
  assert.equal(await fs.readFile(join(directory, 'queue.lock'), 'utf8'), stale);
});

test('build run instance source and credential changes refuse original snapshot adoption', async t => {
  const directory = await fixture(t), spool = await open(directory);
  await spool.replace([event(10)]); await spool.close();
  const original = await fs.readFile(join(directory, 'pending.bin'));
  for (const change of [{ buildId:id(70) }, { runId:id(71) }, { instanceId:id(72) }, { gitSha:'d'.repeat(40) }, { sourceFingerprint:'e'.repeat(64) }]) {
    await assert.rejects(FileLogSpool.open(directory, { ...identity, ...change }, vocabulary, fingerprint), /LOG_SPOOL_UNAVAILABLE/);
  }
  await assert.rejects(FileLogSpool.open(directory, identity, vocabulary, 'f'.repeat(64)), /LOG_SPOOL_UNAVAILABLE/);
  assert.deepEqual(await fs.readFile(join(directory, 'pending.bin')), original);
  const recovered = await open(directory);
  assert.throws(() => recovered.load({ ...identity, runId:id(71) }, fingerprint), /LOG_SPOOL_UNAVAILABLE/);
  await recovered.close();
});

test('invalid records and failed replacement preserve last known snapshot and foreign staging evidence', async t => {
  const directory = await fixture(t), spool = await open(directory);
  await spool.replace([event(10)]);
  const path = join(directory, 'pending.bin'), original = await fs.readFile(path);
  for (const records of [[event(10), event(10)], ['raw-private-message'], Array(2049).fill(event(10)), null]) {
    await assert.rejects(spool.replace(records), /LOG_SPOOL_UNAVAILABLE/);
    assert.deepEqual(await fs.readFile(path), original);
  }
  await fs.writeFile(join(directory, 'pending.next'), 'owned-by-an-unknown-operation', { mode:0o600 });
  await assert.rejects(spool.replace([event(11)]), /LOG_SPOOL_UNAVAILABLE/);
  assert.deepEqual(await fs.readFile(path), original);
  assert.equal(await fs.readFile(join(directory, 'pending.next'), 'utf8'), 'owned-by-an-unknown-operation');
  await spool.close();
});

test('malformed duplicate-key unknown-schema and oversized disk snapshots fail closed without erasing evidence', async t => {
  const directory = await fixture(t), spool = await open(directory);
  await spool.replace([event(10)]); await spool.close();
  const path = join(directory, 'pending.bin'), original = await fs.readFile(path);
  const valid = JSON.parse(original);
  for (const data of [Buffer.from('not-json'), Buffer.from(JSON.stringify({ ...valid, unknown:true })),
    Buffer.from(JSON.stringify({ ...valid, schemaVersion:2 })),
    Buffer.from(original.toString().replace('"schemaVersion":1', '"schemaVersion":0,"schemaVersion":1')),
    Buffer.from(JSON.stringify({ ...valid, events:[event(10), event(10)] })), Buffer.alloc(9 * 1024 * 1024 + 1)]) {
    await fs.writeFile(path, data, { mode:0o600 });
    await assert.rejects(open(directory), /LOG_SPOOL_UNAVAILABLE/);
    assert.deepEqual(await fs.readFile(path), data);
  }
});

test('directory aliases permissions symlinks and unknown files are refused without external mutation', async t => {
  const parent = await fixture(t), directory = join(parent, 'queue'); await fs.mkdir(directory, { mode:0o700 });
  const alias = join(parent, 'alias'); await fs.symlink(directory, alias);
  await assert.rejects(open(alias), /LOG_SPOOL_UNAVAILABLE/);
  await assert.rejects(open('relative-path'), /LOG_SPOOL_UNAVAILABLE/);
  await fs.chmod(directory, 0o755); await assert.rejects(open(directory), /LOG_SPOOL_UNAVAILABLE/); await fs.chmod(directory, 0o700);
  const external = join(parent, 'external'); await fs.writeFile(external, 'retained', { mode:0o600 });
  await fs.symlink(external, join(directory, 'pending.bin')); await assert.rejects(open(directory), /LOG_SPOOL_UNAVAILABLE/);
  assert.equal(await fs.readFile(external, 'utf8'), 'retained'); await fs.unlink(join(directory, 'pending.bin'));
  await fs.writeFile(join(directory, 'unknown'), 'retained', { mode:0o600 }); await assert.rejects(open(directory), /LOG_SPOOL_UNAVAILABLE/);
});

test('close refuses changed lease ownership and never removes a foreign lock', async t => {
  const directory = await fixture(t), spool = await open(directory), lock = join(directory, 'queue.lock');
  await fs.unlink(lock); await fs.writeFile(lock, 'foreign-lease', { mode:0o600 });
  await assert.rejects(spool.close(), /LOG_SPOOL_UNAVAILABLE/);
  assert.equal(await fs.readFile(lock, 'utf8'), 'foreign-lease');
});

test('private file and directory modes reject both shared access and special permission bits', async t => {
  const directory = await fixture(t), spool = await open(directory);
  await spool.replace([event(10)]); await spool.close();
  const path = join(directory, 'pending.bin');
  for (const mode of [0o644, 0o4600]) {
    await fs.chmod(path, mode);
    await assert.rejects(open(directory), /LOG_SPOOL_UNAVAILABLE/);
  }
  await fs.chmod(path, 0o600); await fs.chmod(directory, 0o1700);
  await assert.rejects(open(directory), /LOG_SPOOL_UNAVAILABLE/);
});
