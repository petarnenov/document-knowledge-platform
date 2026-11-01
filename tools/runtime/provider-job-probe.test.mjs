import { test } from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import https from 'node:https';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { once } from 'node:events';
import { createHash } from 'node:crypto';
import {
  frame,
  cleanReply,
  poolStats,
  scannerRound,
  inspectorRound,
  pdf,
  EICAR,
  main,
} from './provider-job-probe.mjs';
const stats =
  'STATE: VALID PRIMARY\nTHREADS: live 1  idle 0 max 1 idle-timeout 30\nQUEUE: 0 items\nEND\0';
test('closed framing, finite engine replies and primary pool parsing reject widening', () => {
  assert.equal(frame(Buffer.from('abc')).readUInt32BE(), 3);
  for (const b of [Buffer.alloc(0), Buffer.alloc(1_000_001), 'not bytes'])
    assert.throws(() => frame(b));
  assert.equal(cleanReply(Buffer.from('stream: OK\0'), 'CLEAN'), 'CLEAN');
  assert.equal(
    cleanReply(Buffer.from('stream: Win.Test.EICAR_HDB-1 FOUND\0'), 'EICAR'),
    'EICAR',
  );
  for (const b of [
    'stream: OK',
    'stream: unexpected FOUND\0',
    'stream: OK\0extra',
  ])
    assert.throws(() => cleanReply(Buffer.from(b), 'EICAR'));
  assert.deepEqual(poolStats(Buffer.from(stats)), {
    maxThreads: 1,
    observedQueue: 0,
  });
  for (const value of [
    stats.replace('max 1', 'max 2'),
    stats.replace('live 1', 'live 2'),
    stats.replace('QUEUE: 0', 'QUEUE: 3'),
    stats + stats,
    stats.replace('PRIMARY', 'SECONDARY'),
    '',
  ])
    assert.throws(() => poolStats(Buffer.from(value)));
  assert.ok(pdf().toString().endsWith('%%EOF\n'));
  assert.equal(EICAR.length, 68);
});
async function scanner(mode = 'valid') {
  const sockets = new Set();
  let submissions = 0;
  const server = net.createServer((socket) => {
    sockets.add(socket);
    socket.on('error', () => {});
    socket.once('close', () => sockets.delete(socket));
    let data = Buffer.alloc(0);
    socket.on('data', (chunk) => {
      data = Buffer.concat([data, chunk]);
      if (data.toString().startsWith('zSTATS\0')) {
        if (mode !== 'stall') socket.end(stats);
        return;
      }
      if (data.length < 13 || data.subarray(0, 10).toString() !== 'zINSTREAM\0')
        return;
      const length = data.readUInt32BE(10);
      if (data.length < length + 18) return;
      submissions++;
      const body = data.subarray(14, 14 + length);
      socket.end(
        mode === 'wrong'
          ? 'stream: wrong\0'
          : mode === 'oversize'
            ? 'x'.repeat(1025)
            : body.equals(EICAR)
              ? 'stream: Win.Test.EICAR_HDB-1 FOUND\0'
              : 'stream: OK\0',
      );
    });
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return {
    address: { host: '127.0.0.1', port: server.address().port },
    count: () => submissions,
    close: async () => {
      for (const s of sockets) s.destroy();
      server.close();
      await once(server, 'close');
    },
  };
}
test('six real loopback protocol connections submit concurrently, cancel one and require genuine typed results', async () => {
  const server = await scanner();
  try {
    const result = await scannerRound(server.address, 2000);
    assert.equal(result.submitted, 6);
    assert.equal(result.canceled, 1);
    assert.equal(result.clean, 2);
    assert.equal(result.eicar, 3);
    assert.equal(result.remoteCancellationAcknowledged, false);
    assert.equal(server.count(), 6);
  } finally {
    await server.close();
  }
});
test('wrong, oversized and stalled provider responses cannot become acceptance', async () => {
  for (const mode of ['wrong', 'oversize', 'stall']) {
    const server = await scanner(mode);
    try {
      await assert.rejects(
        scannerRound(server.address, mode === 'stall' ? 20 : 2000),
      );
    } finally {
      await server.close();
    }
  }
  await assert.rejects(scannerRound({ host: '127.0.0.1', port: 1 }, 100));
  await assert.rejects(main('unknown', [][Symbol.iterator]()));
  await assert.rejects(main('inspector', ['invalid'][Symbol.iterator]()));
});
async function inspector(mode) {
  const dir = mkdtempSync(join(tmpdir(), 'dkp305-tls-'));
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-days',
      '1',
      '-subj',
      '/CN=localhost',
      '-addext',
      'subjectAltName=DNS:localhost',
      '-keyout',
      join(dir, 'key'),
      '-out',
      join(dir, 'cert'),
    ],
    { stdio: 'ignore' },
  );
  let admitted = false,
    partial;
  const server = https.createServer(
    {
      key: readFileSync(join(dir, 'key')),
      cert: readFileSync(join(dir, 'cert')),
    },
    (request, response) => {
      if (admitted) {
        response.writeHead(mode === 'bad-busy' ? 200 : 503);
        response.end('{"code":"UNAVAILABLE"}');
        return;
      }
      admitted = true;
      let count = 0;
      const chunks = [];
      request.on('data', (bytes) => {
        count += bytes.length;
        chunks.push(bytes);
      });
      request.once('aborted', () => {
        admitted = false;
      });
      request.on('end', () => {
        admitted = false;
        const bytes = Buffer.concat(chunks);
        const value = {
          schemaVersion: 1,
          format: 'PDF',
          mimeType: 'application/pdf',
          sizeBytes: bytes.length,
          sha256: createHash('sha256').update(bytes).digest('hex'),
          pageCount: 1,
          inspectorVersion: '1',
          profileDigest: 'a'.repeat(64),
        };
        response.writeHead(200);
        response.end(
          JSON.stringify(
            mode === 'bad-evidence' ? { ...value, pageCount: 2 } : value,
          ),
        );
      });
      partial = request;
    },
  );
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return {
    options: {
      host: '127.0.0.1',
      port: server.address().port,
      servername: 'localhost',
      ca: readFileSync(join(dir, 'cert')),
      rejectUnauthorized: true,
      authorization: 'Bearer synthetic',
    },
    gate: async (name) => {
      if (name === 'PARTIAL_BODY_SUBMITTED') {
        while (!partial || !admitted)
          await new Promise((resolve) => setImmediate(resolve));
      } else if (admitted) await once(partial, 'aborted');
    },
    close: async () => {
      server.closeAllConnections();
      server.close();
      await once(server, 'close');
      rmSync(dir, { recursive: true });
    },
  };
}
test('real trusted TLS partial body owns admission, busy503, cancellation then exact real response validation', async () => {
  for (const mode of ['valid', 'bad-busy', 'bad-evidence']) {
    const server = await inspector(mode);
    try {
      if (mode === 'valid') {
        const result = await inspectorRound(
          server.options,
          server.gate,
          'a'.repeat(64),
        );
        assert.equal(result.busyStatus, 503);
        assert.equal(result.recoveryStatus, 200);
      } else
        await assert.rejects(
          inspectorRound(server.options, server.gate, 'a'.repeat(64)),
        );
    } finally {
      await server.close();
    }
  }
});
