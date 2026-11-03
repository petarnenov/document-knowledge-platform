import https from 'node:https';
import { X509Certificate, createHash } from 'node:crypto';
import { LIMITS } from './wire.js';

function unavailable() { return new Error('LOG_HTTP_UNAVAILABLE'); }
/** Fixed two-route direct TLS transport with one owned request and no ambient proxy/credentials. */
export class NodeHttpsTransport {
  #endpoint; #token; #agent; #active = null; #closed = false; #fingerprint;
  constructor(configuration) {
    try {
      const { endpoint, producerToken, ca } = configuration;
      const url = new URL(endpoint);
      if (url.protocol !== 'https:' || url.username || url.password || url.origin + '/api/v1/dev-logs' !== endpoint || !/^[A-Za-z0-9_-]{32,512}$/.test(producerToken) || typeof producerToken !== 'string') throw new Error();
      if (!(typeof ca === 'string' || ca instanceof Uint8Array) || Buffer.byteLength(ca) === 0 || Buffer.byteLength(ca) > 16384) throw new Error();
      const certificate = new X509Certificate(ca);
      this.#agent = new https.Agent({ ca: certificate.toString(), rejectUnauthorized: true, keepAlive: false, maxSockets: 1, maxTotalSockets: 1, maxFreeSockets: 0, maxCachedSessions: 0 });
      this.#endpoint = endpoint; this.#token = producerToken;
      this.#fingerprint = createHash('sha256').update('dkp-log-producer-credential-v1:' + producerToken, 'ascii').digest('hex');
    } catch { throw new TypeError('INVALID_LOG_TRANSPORT_CONFIG'); }
  }
  credentialFingerprint() { return this.#fingerprint; }
  post(route, body) {
    if (this.#closed || this.#active !== null || !['batches', 'receipts'].includes(route) || !(body instanceof Uint8Array) || body.length === 0 || body.length > LIMITS.batchBytes) return Promise.reject(unavailable());
    return new Promise((resolve, reject) => {
      let request, whole, connect, outcome;
      const finish = value => { if (outcome !== undefined) return; outcome = value; request?.destroy(); };
      try {
        request = https.request(this.#endpoint + '/' + route, { method: 'POST', agent: this.#agent, rejectUnauthorized: true, headers: { 'Content-Type': 'application/json', Accept: 'application/json', Authorization: 'Bearer ' + this.#token, 'Content-Length': body.length } }, response => {
          if (response.headers['content-encoding'] !== undefined || (response.statusCode === 200 && !/^application\/json(?:\s*;|$)/i.test(response.headers['content-type'] ?? ''))) { finish(unavailable()); return; }
          const length = response.headers['content-length'];
          if (length !== undefined && (!/^\d+$/.test(length) || Number(length) > LIMITS.batchBytes)) { finish(unavailable()); return; }
          const chunks = []; let size = 0;
          response.on('data', chunk => { if (outcome !== undefined) return; size += chunk.length; if (size > LIMITS.batchBytes) finish(unavailable()); else chunks.push(chunk); });
          response.once('error', () => finish(unavailable()));
          response.once('end', () => { if (outcome === undefined) finish({ status: response.statusCode, body: Buffer.concat(chunks, size) }); });
        });
        this.#active = request;
        whole = setTimeout(() => finish(unavailable()), 5000);
        request.once('socket', socket => {
          connect = setTimeout(() => finish(unavailable()), 1000);
          socket.once('secureConnect', () => clearTimeout(connect));
        });
        request.once('error', () => finish(unavailable()));
        request.once('close', () => {
          clearTimeout(whole); clearTimeout(connect);
          if (this.#active === request) this.#active = null;
          if (outcome && !(outcome instanceof Error)) resolve(outcome); else reject(unavailable());
        });
        request.end(Buffer.from(body));
      } catch {
        clearTimeout(whole); clearTimeout(connect);
        if (this.#active === request) this.#active = null;
        request?.destroy(); reject(unavailable());
      }
    });
  }
  close() { this.#closed = true; this.#active?.destroy(); this.#agent.destroy(); }
}
