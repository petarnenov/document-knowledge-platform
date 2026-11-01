#!/usr/bin/env node
/** Fixed-endpoint operator probe, not a provider. Never reports socket writes as
 * engine execution or cancellation as remote settlement. No credentials in output. */
import net from 'node:net';
import https from 'node:https';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';

export const BENIGN = Buffer.from(
  'DKP public synthetic benign scan input\n'.repeat(8192),
);
export const EICAR = Buffer.from(
  'X5O!P%@AP[4' + '\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*',
);
const endpoint = { host: 'clamav', port: 3310 };
export function frame(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 1 || bytes.length > 1_000_000)
    throw Error('Bounded probe bytes required');
  const header = Buffer.alloc(4);
  header.writeUInt32BE(bytes.length);
  return Buffer.concat([header, bytes]);
}
function write(socket, bytes) {
  return new Promise((resolve, reject) =>
    socket.write(bytes, (error) =>
      error ? reject(Error('Probe write failed')) : resolve(),
    ),
  );
}
function connection(address, limit, timeout) {
  const socket = net.createConnection(address);
  const ready = new Promise((resolve, reject) => {
    socket.once('connect', resolve);
    socket.once('error', () => reject(Error('Probe connect failed')));
  });
  const chunks = [];
  let size = 0;
  const timer = setTimeout(
    () => socket.destroy(Error('Probe total deadline')),
    timeout,
  );
  const reply = new Promise((resolve, reject) => {
    socket.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) socket.destroy(Error('Probe response bound'));
      else chunks.push(chunk);
    });
    socket.once('end', () => resolve(Buffer.concat(chunks)));
    socket.once('error', () => reject(Error('Probe transport failed')));
    socket.once('close', () => {
      clearTimeout(timer);
      if (!socket.readableEnded) reject(Error('Probe transport closed'));
    });
  });
  // A canceled probe still has an observed rejection; never an unhandled promise.
  void reply.catch(() => {});
  return { socket, ready, reply };
}
export function cleanReply(bytes, expected) {
  const actual = bytes.toString('ascii');
  if (expected === 'CLEAN' && actual === 'stream: OK\0') return 'CLEAN';
  if (
    expected === 'EICAR' &&
    /^stream: [A-Za-z0-9_.-]*Eicar[A-Za-z0-9_.-]* FOUND\0$/i.test(actual)
  )
    return 'EICAR';
  throw Error('Real engine outcome differs from synthetic input');
}
export function poolStats(bytes) {
  const text = bytes.toString('ascii');
  const primary = [...text.matchAll(/^STATE: VALID PRIMARY\s*$/gm)];
  const threads = [
    ...text.matchAll(
      /^THREADS: live (\d+)\s+idle (\d+) max (\d+) idle-timeout (\d+)\s*$/gm,
    ),
  ];
  const queue = [...text.matchAll(/^QUEUE: (\d+) items[^\r\n]*$/gm)];
  if (
    primary.length !== 1 ||
    threads.length !== 1 ||
    queue.length !== 1 ||
    Number(threads[0][3]) !== 1 ||
    Number(threads[0][1]) > 1 ||
    Number(queue[0][1]) > 2
  )
    throw Error('Pinned primary pool bound differs');
  return { maxThreads: 1, observedQueue: Number(queue[0][1]) };
}
async function stats(address, timeout) {
  const c = connection(address, 8192, timeout);
  try {
    await c.ready;
    await write(c.socket, Buffer.from('zSTATS\0'));
    return poolStats(await c.reply);
  } finally {
    c.socket.destroy();
  }
}
/** Tests may inject a loopback protocol fixture; CLI always uses the fixed private provider. */
export async function scannerRound(address = endpoint, timeout = 120_000) {
  const before = await stats(address, timeout);
  const clients = Array.from({ length: 6 }, () =>
    connection(address, 1024, timeout),
  );
  const submitted = new Set();
  try {
    // Barrier is overlapping client connections, not an executing-scan marker.
    await Promise.all(clients.map((c) => c.ready));
    const outcomes = await Promise.all(
      clients.map(async (c, index) => {
        await write(
          c.socket,
          Buffer.concat([
            Buffer.from('zINSTREAM\0'),
            frame(index % 2 ? EICAR : BENIGN),
          ]),
        );
        await write(c.socket, Buffer.alloc(4));
        submitted.add(index);
        if (index === 0) {
          c.socket.destroy();
          return 'CLIENT_CANCELED_AFTER_SUBMISSION';
        }
        return cleanReply(await c.reply, index % 2 ? 'EICAR' : 'CLEAN');
      }),
    );
    if (
      submitted.size !== 6 ||
      outcomes.filter((x) => x === 'CLEAN').length !== 2 ||
      outcomes.filter((x) => x === 'EICAR').length !== 3
    )
      throw Error('Nonvacuous six-client pressure required');
    const after = await stats(address, timeout);
    return {
      schemaVersion: 1,
      phase: 'scanner',
      result: 'PASS',
      submitted: 6,
      canceled: 1,
      clean: 2,
      eicar: 3,
      before,
      after,
      executionObservation: 'CONFIG_AND_PINNED_DISPATCH_PROOF',
      remoteCancellationAcknowledged: false,
    };
  } finally {
    for (const c of clients) c.socket.destroy();
  }
}
export function pdf() {
  let text = '%PDF-1.7\n';
  const offsets = [];
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>',
  ];
  for (const [i, object] of objects.entries()) {
    offsets.push(Buffer.byteLength(text));
    text += `${i + 1} 0 obj\n${object}\nendobj\n`;
  }
  const xref = Buffer.byteLength(text);
  text += 'xref\n0 4\n0000000000 65535 f \n';
  for (const offset of offsets)
    text += `${String(offset).padStart(10, '0')} 00000 n \n`;
  return Buffer.from(
    text + `trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`,
  );
}
function inspection(options, source, partial = false) {
  let request;
  const reply = new Promise((resolve, reject) => {
    request = https.request(
      {
        ...options,
        method: 'POST',
        path: '/internal/v1/inspect',
        headers: {
          Authorization: options.authorization,
          'Content-Type': 'application/octet-stream',
          'X-DKP-Format': 'PDF',
          'Content-Length': String(source.length),
        },
      },
      (response) => {
        const chunks = [];
        let length = 0;
        response.on('data', (bytes) => {
          length += bytes.length;
          if (length > 8192)
            response.destroy(Error('Inspection response bound'));
          else chunks.push(bytes);
        });
        response.once('end', () =>
          resolve({
            status: response.statusCode,
            bytes: Buffer.concat(chunks),
          }),
        );
        response.once('error', () =>
          reject(Error('Inspection response unavailable')),
        );
      },
    );
    request.once('error', () =>
      reject(Error('Inspection transport unavailable')),
    );
  });
  const timer = setTimeout(
    () => request.destroy(Error('Inspection total deadline')),
    60_000,
  );
  void reply.finally(() => clearTimeout(timer)).catch(() => {});
  request.flushHeaders();
  request.write(partial ? source.subarray(0, 1) : source);
  if (!partial) request.end();
  return { request, reply };
}
export async function inspectorRound(options, gate, profile) {
  const source = pdf(),
    first = inspection(options, source, true);
  void first.reply.catch(() => {});
  try {
    await gate('PARTIAL_BODY_SUBMITTED'); // Operator observes exactly one private spool, not parser execution.
    const busy = await inspection(options, source).reply;
    if (
      busy.status !== 503 ||
      busy.bytes.toString() !== '{"code":"UNAVAILABLE"}'
    )
      throw Error('Provider must reject concurrent inspection');
    first.request.destroy();
    await gate('PARTIAL_CLIENT_CANCELED'); // Operator observes spool cleanup, not remote socket ACK.
    const recovered = await inspection(options, source).reply;
    const value = JSON.parse(recovered.bytes.toString());
    if (
      recovered.status !== 200 ||
      Object.keys(value).sort().join(',') !==
        'format,inspectorVersion,mimeType,pageCount,profileDigest,schemaVersion,sha256,sizeBytes' ||
      value.schemaVersion !== 1 ||
      value.format !== 'PDF' ||
      value.mimeType !== 'application/pdf' ||
      value.pageCount !== 1 ||
      value.inspectorVersion !== '1' ||
      value.profileDigest !== profile ||
      value.sizeBytes !== source.length ||
      value.sha256 !== createHash('sha256').update(source).digest('hex')
    )
      throw Error('Real inspection evidence differs');
    return {
      schemaVersion: 1,
      phase: 'inspector',
      result: 'PASS',
      busyStatus: 503,
      recoveryStatus: 200,
      admittedObservation: 'PRIVATE_SPOOL',
      remoteCancellationAcknowledged: false,
    };
  } finally {
    first.request.destroy();
  }
}
export async function main(phase, lines, emit = console.log) {
  if (phase === 'scanner') {
    emit(JSON.stringify(await scannerRound()));
    return;
  }
  if (phase !== 'inspector') throw Error('Closed probe phase required');
  const profile = (await lines.next()).value;
  if (!/^[a-f0-9]{64}$/.test(profile ?? ''))
    throw Error('Reviewed profile required');
  const key = readFileSync('/run/dkp/service.key', 'utf8').trim();
  if (
    Buffer.from(key, 'base64').length !== 32 ||
    Buffer.from(key, 'base64').toString('base64') !== key
  )
    throw Error('Strict private service key required');
  const options = {
    host: 'structure-inspector',
    port: 8443,
    servername: 'structure-inspector',
    ca: readFileSync('/run/dkp/ca.crt'),
    rejectUnauthorized: true,
    authorization: `Bearer ${key}`,
  };
  const gate = async (name) => {
    emit(
      JSON.stringify({
        schemaVersion: 1,
        phase: 'inspector',
        checkpoint: name,
      }),
    );
    if ((await lines.next()).value !== 'CONTINUE')
      throw Error('Closed operator gate required');
  };
  emit(JSON.stringify(await inspectorRound(options, gate, profile)));
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const reader = createInterface({ input: process.stdin });
  try {
    await main(process.argv[2], reader[Symbol.asyncIterator]());
  } catch {
    console.error('Owned provider probe failed closed');
    process.exitCode = 1;
  } finally {
    reader.close();
  }
}
