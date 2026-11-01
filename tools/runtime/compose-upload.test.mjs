import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

// Render the actual Compose merge with synthetic inputs only. No runtime.env,
// credentials, containers or private files are read or changed by these tests.
const files = ['infra/docker-compose/compose.yaml', 'infra/docker-compose/compose-upload.yaml'];
const environment = { PATH: process.env.PATH, HOME: process.env.HOME };
for (const file of files) {
  for (const match of readFileSync(file, 'utf8').matchAll(/\$\{([A-Z0-9_]+)/g)) {
    environment[match[1]] = match[1].includes('DIRECTORY') ? '/tmp/dkp-synthetic/' + match[1] : 'synthetic';
  }
}
Object.assign(environment, { DKP_LOCAL_UID: '10001', DKP_LOCAL_GID: '10001', DKP_PORTAL_PORT: '19443',
  DKP_IDP_PORT: '19444', DKP_UPLOAD_STORAGE_ENDPOINT: 'https://rustfs:9000',
  DKP_UPLOAD_STORAGE_REGION: 'us-east-1', DKP_UPLOAD_SCANNER_HOST: 'clamav', DKP_UPLOAD_SCANNER_PORT: '3310',
  DKP_UPLOAD_INSPECTOR_ENDPOINT: 'https://structure-inspector:8443/internal/v1/inspect',
  DKP_UPLOAD_INSPECTOR_CONTAINER_IMAGE: 'dkp-bp03-structure-inspector:local' });
for (const name of Object.keys(environment)) if (name.endsWith('_FILE')) environment[name] = '/tmp/dkp-synthetic/' + name;
const result = spawnSync('docker', ['compose', '-p', 'dkp-bp03', ...files.flatMap((f) => ['-f', f]),
  'config', '--format', 'json'], { env: environment, encoding: 'utf8' });
assert.equal(result.status, 0, 'Synthetic Compose rendering must succeed');
const config = JSON.parse(result.stdout);

test('isolated callback, portal and exact issuer agree across the merged runtime', () => {
  const bff = config.services['browser-bff'].environment;
  assert.equal(bff.DKP_PORTAL_ORIGIN, 'https://localhost:19443');
  assert.equal(bff.OIDC_CALLBACK_URI, bff.DKP_PORTAL_ORIGIN + '/oauth/callback/keycloak');
  for (const service of ['browser-bff', 'document-management', 'knowledge-base', 'knowledge-search']) {
    const env = config.services[service].environment;
    assert.equal(env.OIDC_ISSUER, 'https://idp.localhost:19444/realms/dkp');
    assert.equal(env.OIDC_STANDALONE_ISSUER, env.OIDC_ISSUER);
    assert.equal(env.OIDC_JWKS_URL, env.OIDC_ISSUER + '/protocol/openid-connect/certs');
  }
  assert.equal(config.services.keycloak.environment.KC_HTTPS_PORT, '19444');
  assert.equal(config.services['admin-portal'].ports[0].published, '19443');
  assert.equal(config.services['admin-portal'].ports[0].target, 19443);
});

test('object store and scanner remain private and DM receives application authority only', () => {
  assert.equal(config.networks['upload-data'].internal, true);
  for (const provider of ['rustfs', 'clamav']) {
    assert.equal(config.services[provider].ports, undefined);
    assert.deepEqual(Object.keys(config.services[provider].networks), ['upload-data']);
    assert.equal(config.services[provider].read_only, true);
    assert.deepEqual(config.services[provider].cap_drop, ['ALL']);
  }
  const dm = config.services['document-management'];
  assert.equal(dm.environment.DKP_DM_UPLOAD_ENABLED, 'true');
  assert.equal(dm.environment.DKP_DM_STORAGE_ENDPOINT, 'https://rustfs:9000');
  assert.deepEqual(dm.volumes.filter((v) => v.target.startsWith('/run/dkp/storage/'))
    .map((v) => [v.target, v.read_only]), [
    ['/run/dkp/storage/access.key', true], ['/run/dkp/storage/secret.key', true], ['/run/dkp/storage/access.key.role', true], ['/run/dkp/storage/ca.crt', true] ]);
  assert.deepEqual(dm.volumes.filter((v) => v.target.startsWith('/run/dkp/cleanup/')).map((v) => [v.target, v.read_only]), [
    ['/run/dkp/cleanup/access.key', true], ['/run/dkp/cleanup/secret.key', true], ['/run/dkp/cleanup/access.key.role', true] ]);
  assert.equal(dm.environment.DKP_DM_CLEANUP_ACCESS_KEY_PATH, '/run/dkp/cleanup/access.key');
  for (const service of ['browser-bff', 'admin-portal', 'structure-inspector', 'knowledge-base', 'knowledge-search']) {
    assert.equal(config.services[service].volumes.some((v) => v.target.startsWith('/run/dkp/cleanup/') || v.target.startsWith('/run/dkp/storage/')), false);
  }
  assert.equal(dm.volumes.some((v) => /root-access|root-secret|ca\.key|server\.p12|keystore\.password/.test(v.source)), false);
  assert.deepEqual(config.services.clamav.healthcheck.test,
    ['CMD', 'clamdscan', '--config-file=/run/dkp/clamd.conf', '--ping=1']);
});

test('parser has only firewall namespace, bounded resources and worker-only authority', () => {
  const worker = config.services['structure-inspector'];
  assert.equal(worker.network_mode, 'service:inspector-network');
  assert.equal(worker.networks, undefined);
  assert.equal(worker.user, '10001:10001');
  assert.equal(worker.read_only, true);
  assert.deepEqual(worker.cap_drop, ['ALL']);
  assert.equal(Number(worker.mem_limit), 805306368);
  assert.equal(worker.cpus, 1);
  assert.equal(worker.pids_limit, 64);
  assert.equal(worker.volumes.length, 1);
  assert.equal(worker.volumes[0].target, '/run/dkp/inspector');
  assert.equal(worker.volumes[0].read_only, true);
  assert.deepEqual(config.services['inspector-network'].cap_add, ['NET_ADMIN']);
});


test('new upload routes emit one canonical pair of security headers without altering generic proxy behavior', () => {
  const source = readFileSync('infra/docker-compose/nginx-upload.conf', 'utf8');
  const proxyBlocks = source.split('    location ').slice(1).filter((block) => block.includes('proxy_pass'));
  assert.equal(proxyBlocks.length, 4);
  for (const block of proxyBlocks.slice(0, 3)) {
    assert.equal((block.match(/proxy_hide_header Cache-Control;/g) ?? []).length, 1);
    assert.equal((block.match(/proxy_hide_header X-Content-Type-Options;/g) ?? []).length, 1);
    assert.equal((block.match(/add_header Cache-Control no-store always;/g) ?? []).length, 1);
    assert.equal((block.match(/add_header X-Content-Type-Options nosniff always;/g) ?? []).length, 1);
  }
  assert.equal(proxyBlocks[3].includes('proxy_hide_header'), false);
});
