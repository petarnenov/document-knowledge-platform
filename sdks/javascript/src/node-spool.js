import * as fs from 'node:fs/promises';
import { constants } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { isAbsolute, resolve, join } from 'node:path';
import { createWire, LIMITS, bytes, requireLog } from './wire.js';

const INTERNAL = Symbol('FileLogSpool');
const SNAPSHOT = 9 * 1024 * 1024;
const NAMES = new Set(['pending.bin', 'pending.next', 'queue.lock']);
function unavailable() { return new Error('LOG_SPOOL_UNAVAILABLE'); }
function binding(identity, fingerprint) {
  requireLog(typeof fingerprint === 'string' && /^[0-9a-f]{64}$/.test(fingerprint));
  const keys = ['buildId', 'runId', 'instanceId', 'component', 'gitSha', 'sourceFingerprint'];
  return createHash('sha256').update(JSON.stringify(['dkp-node-log-spool-v1',
    ...keys.map(key => identity[key]), fingerprint])).digest('hex');
}
function regular(info, limit) {
  requireLog(info.isFile() && info.uid === process.getuid() && (info.mode & 0o7777) === 0o600
    && info.size <= limit);
}
function same(left, right) { return left.dev === right.dev && left.ino === right.ino; }

/** A single graceful lifecycle lease. Interrupted leases are preserved, never guessed stale. */
export class FileLogSpool {
  #directory; #binding; #wire; #initial = []; #lease; #leaseInfo;
  #closed = false; #busy = false;
  constructor(token) { if (token !== INTERNAL) throw new TypeError('USE_FILE_LOG_SPOOL_OPEN'); }
  static async open(directory, identity, vocabulary, credentialFingerprint) {
    // Private construction without exposing a partially initialized filesystem adapter.
    const result = new FileLogSpool(INTERNAL);
    try {
      requireLog(typeof directory === 'string' && isAbsolute(directory) && resolve(directory) === directory);
      result.#directory = directory; result.#wire = createWire(identity, vocabulary);
      result.#binding = binding(result.#wire.execution, credentialFingerprint);
      try { await fs.mkdir(directory, { mode:0o700 }); } catch (error) { if (error.code !== 'EEXIST') throw error; }
      await result.#check();
      const lock = join(directory, 'queue.lock');
      let handle;
      try { handle = await fs.open(lock, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600); }
      catch (error) { if (error.code === 'EEXIST') throw new Error('LOG_SPOOL_LOCKED'); throw error; }
      result.#lease = JSON.stringify({ leaseId:randomUUID(), pid:process.pid });
      try {
        result.#leaseInfo = await handle.stat();
        await handle.writeFile(result.#lease); await handle.sync();
      } finally { await handle.close(); }
      const data = await result.#read('pending.bin', SNAPSHOT, true);
      if (data !== null) {
        const value = JSON.parse(data.toString('utf8'));
        requireLog(value?.schemaVersion === 1 && value.binding === result.#binding);
        result.#validate(value.events);
        requireLog(data.equals(Buffer.from(JSON.stringify({ schemaVersion:1, binding:result.#binding, events:value.events }))));
        result.#initial = [...value.events];
      }
      return result;
    } catch (error) {
      if (result.#leaseInfo) {
        try { await result.close(); } catch { /* Preserve unknown/changed ownership. */ }
      }
      if (error.message === 'LOG_SPOOL_LOCKED') throw new Error('LOG_SPOOL_LOCKED');
      throw unavailable();
    }
  }
  async #check() {
    const info = await fs.lstat(this.#directory);
    requireLog(info.isDirectory() && info.uid === process.getuid() && (info.mode & 0o7777) === 0o700
      && await fs.realpath(this.#directory) === this.#directory);
    for (const name of await fs.readdir(this.#directory)) {
      requireLog(NAMES.has(name));
      regular(await fs.lstat(join(this.#directory, name)), name === 'queue.lock' ? 256 : SNAPSHOT);
    }
  }
  async #read(name, limit, optional = false) {
    const path = join(this.#directory, name);
    let before;
    try { before = await fs.lstat(path); } catch (error) { if (optional && error.code === 'ENOENT') return null; throw error; }
    regular(before, limit);
    const handle = await fs.open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      const buffer = Buffer.alloc(limit + 1);
      let size = 0;
      while (size < buffer.length) {
        const read = await handle.read(buffer, size, buffer.length - size, size);
        if (read.bytesRead === 0) break;
        size += read.bytesRead;
      }
      const after = await handle.stat();
      requireLog(same(before, after) && before.size === size && after.size === size
        && before.mtimeMs === after.mtimeMs && size <= limit);
      return buffer.subarray(0, size);
    } finally { await handle.close(); }
  }
  #validate(events) {
    requireLog(Array.isArray(events) && events.length <= LIMITS.queueEvents);
    let total = 0; const ids = new Set();
    for (const text of events) {
      const event = this.#wire.restore(text);
      requireLog(!ids.has(event.id)); ids.add(event.id); total += bytes(text);
    }
    requireLog(total <= LIMITS.queueBytes);
  }
  load(identity, credentialFingerprint) {
    try {
      requireLog(!this.#closed && binding(identity, credentialFingerprint) === this.#binding);
      return Object.freeze([...this.#initial]);
    } catch { throw unavailable(); }
  }
  async replace(events) {
    let handle, ownedStage, ownsOperation = false;
    try {
      requireLog(!this.#closed && !this.#busy); this.#busy = true; ownsOperation = true;
      events = [...events];
      this.#validate(events);
      const data = Buffer.from(JSON.stringify({ schemaVersion:1, binding:this.#binding, events }));
      requireLog(data.length <= SNAPSHOT && data.length * 2 <= 64 * 1024 * 1024);
      await this.#check();
      requireLog(same(this.#leaseInfo, await fs.lstat(join(this.#directory, 'queue.lock')))
        && (await this.#read('queue.lock', 256)).toString() === this.#lease);
      handle = await fs.open(join(this.#directory, 'pending.next'), constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
      ownedStage = await handle.stat();
      await handle.writeFile(data); await handle.sync(); await handle.close(); handle = null;
      requireLog(same(ownedStage, await fs.lstat(join(this.#directory, 'pending.next'))));
      await fs.rename(join(this.#directory, 'pending.next'), join(this.#directory, 'pending.bin'));
      const directory = await fs.open(this.#directory, constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW);
      try { await directory.sync(); } finally { await directory.close(); }
      this.#initial = [...events];
    } catch { throw unavailable(); }
    finally {
      if (handle) await handle.close().catch(() => {});
      if (ownedStage) {
        try {
          if (same(ownedStage, await fs.lstat(join(this.#directory, 'pending.next')))) await fs.unlink(join(this.#directory, 'pending.next'));
        } catch { /* Never remove foreign or unknown evidence. */ }
      }
      if (ownsOperation) this.#busy = false;
    }
  }
  async close() {
    if (this.#closed) return;
    try {
      requireLog(!this.#busy); await this.#check();
      const path = join(this.#directory, 'queue.lock');
      const info = await fs.lstat(path); regular(info, 256);
      requireLog(same(info, this.#leaseInfo) && (await this.#read('queue.lock', 256)).toString() === this.#lease);
      await fs.unlink(path); this.#closed = true;
    } catch { throw unavailable(); }
  }
}
