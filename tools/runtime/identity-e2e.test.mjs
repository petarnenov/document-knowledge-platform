import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, chmodSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { findAddress, main, readCredentials, redact, requirePrivateFile, runtimeArguments, uploadProject } from './identity-e2e.mjs';

test('reads only synthetic browser credentials without evaluating environment text', () => {
  assert.deepEqual(readCredentials('OIDC_CLIENT_SECRET=not-read\nDKP_TEST_USERNAME=tester\nDKP_TEST_PASSWORD=$(never-executed)\n'), { username: 'tester', password: '$(never-executed)' });
  assert.throws(() => readCredentials('DKP_TEST_USERNAME=a\n'), /Missing/);
  assert.throws(() => readCredentials('DKP_TEST_USERNAME=a\nDKP_TEST_USERNAME=b\nDKP_TEST_PASSWORD=x\n'), /Duplicate/);
  assert.throws(() => readCredentials('DKP_TEST_USERNAME=a\nDKP_TEST_PASSWORD=has space\n'), /Invalid/);
});

test('requires private regular credential and CA files', () => {
  const directory = mkdtempSync(join(tmpdir(), 'dkp-e2e-private-'));
  const file = join(directory, 'runtime.env');
  try {
    writeFileSync(file, 'synthetic', { mode: 0o600 });
    requirePrivateFile(file);
    symlinkSync(file, join(directory, 'linked.env'));
    assert.throws(() => requirePrivateFile(join(directory, 'linked.env')), /owner-only/);
    chmodSync(file, 0o644);
    assert.throws(() => requirePrivateFile(file), /owner-only/);
    assert.throws(() => requirePrivateFile(directory), /owner-only/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('only accepts provider addresses on the same explicit Compose network', () => {
  const inspection = [{ NetworkSettings: { Networks: { dkp: { IPAddress: '172.20.0.4' } } } }];
  assert.equal(findAddress(inspection, 'dkp'), '172.20.0.4');
  for (const invalid of ['', '1.2.3.999', 'attacker.example', '127.0.0.1;command']) {
    assert.throws(() => findAddress([{ NetworkSettings: { Networks: { dkp: { IPAddress: invalid } } } }], 'dkp'), /IPv4/);
  }
  assert.throws(() => findAddress(inspection, 'other'), /IPv4/);
});

test('shares only portal network namespace and mounts only the public CA', () => {
  const args = runtimeArguments({ portalId: 'a'.repeat(64), address: '172.20.0.4', certificate: '/work/local-data/security/ca.crt' });
  assert.ok(args.includes(`container:${'a'.repeat(64)}`));
  assert.ok(args.includes('type=bind,src=/work/local-data/security/ca.crt,dst=/run/dkp/ca.crt,readonly'));
  assert.ok(args.includes('DKP_IDENTITY_PASSWORD'));
  assert.ok(args.includes('DKP_IDENTITY_IDP_ADDRESS=172.20.0.4'));
  assert.equal(args.some((argument) => /ca\.key|runtime\.env|privileged|host-gateway|ignore-certificate/.test(argument)), false);
  assert.throws(() => runtimeArguments({ portalId: 'malicious;command', address: '172.20.0.4', certificate: '/ca' }), /identifier/);
  assert.throws(() => runtimeArguments({ portalId: 'a'.repeat(64), address: 'evil', certificate: '/ca' }), /address/);
});

test('limits acceptance uses only an explicit finite profile and exact test file', () => {
  const input = { portalId: 'a'.repeat(64), address: '172.20.0.4', certificate: '/ca', uploadRuntime: true };
  for (const profile of ['quota', 'expiry']) {
    const args = runtimeArguments({ ...input, uploadLimitsProfile: profile });
    assert.ok(args.includes(`DKP_UPLOAD_LIMITS_PROFILE=${profile}`));
    assert.equal(args.at(-1), 'upload-limits.spec.ts');
    assert.equal(args.at(-2), 'dkp-identity-e2e:local');
  }
  assert.equal(runtimeArguments(input).includes('upload-limits.spec.ts'), false);
  for (const profile of ['', 'all', 'expiry;command', true, null]) {
    assert.throws(() => runtimeArguments({ ...input, uploadLimitsProfile: profile }), /limits profile/);
    assert.throws(() => main({ uploadLimitsProfile: profile }), /Invalid upload limits/);
  }
  assert.throws(() => runtimeArguments({ ...input, uploadRuntime: false, uploadLimitsProfile: 'quota' }), /requires upload runtime/);
});

test('durability fixture is explicit, mutually exclusive and selects only its real fixture', () => {
  const input = { portalId: 'a'.repeat(64), address: '172.20.0.4', certificate: '/ca', uploadRuntime: true };
  const args = runtimeArguments({ ...input, uploadDurabilityFixture: true });
  assert.ok(args.includes('DKP_UPLOAD_DURABILITY_FIXTURE=true'));
  assert.equal(args.at(-1), 'upload-durability.spec.ts');
  assert.equal(runtimeArguments(input).includes('DKP_UPLOAD_DURABILITY_FIXTURE=true'), false);
  for (const invalid of ['true', 1, null]) {
    assert.throws(() => runtimeArguments({ ...input, uploadDurabilityFixture: invalid }), /durability fixture/);
    assert.throws(() => main({ uploadDurabilityFixture: invalid }), /durability fixture/);
  }
  assert.throws(() => runtimeArguments({ ...input, uploadRuntime: false, uploadDurabilityFixture: true }), /durability fixture/);
  assert.throws(() => runtimeArguments({ ...input, uploadDurabilityFixture: true, uploadLimitsProfile: 'quota' }), /durability fixture/);
  assert.throws(() => main({ uploadDurabilityFixture: true, uploadLimitsProfile: 'expiry' }), /durability fixture/);
});

test('explicit limits profile selects upload stack even without separate upload flag', () => {
  const directory = mkdtempSync(join(tmpdir(), 'dkp-limits-run-'));
  const calls = [];
  try {
    writeFileSync(join(directory, 'runtime.env'), 'DKP_TEST_USERNAME=tester\nDKP_TEST_PASSWORD=synthetic-password\nDKP_UPLOAD_COMPOSE_PROJECT=dkp-bp03-012345abcdef\n', { mode: 0o600 });
    writeFileSync(join(directory, 'ca.crt'), 'synthetic-public-ca', { mode: 0o600 });
    const execute = (args) => {
      calls.push(args);
      if (args[0] === 'compose') return 'a'.repeat(64);
      if (args[0] === 'inspect') return JSON.stringify([{ NetworkSettings: { Networks: { isolated: { IPAddress: '172.20.0.4' } } } }]);
      return '';
    };
    main({ uploadLimitsProfile: 'expiry', securityDirectory: directory, execute, log: () => {} });
    assert.ok(calls[0].includes('dkp-bp03-012345abcdef'));
    assert.ok(calls[0].includes('infra/docker-compose/compose-upload.yaml'));
    assert.ok(calls.at(-1).includes('DKP_IDENTITY_BASE_URL=https://localhost:19443'));
    assert.ok(calls.at(-1).includes('DKP_UPLOAD_LIMITS_PROFILE=expiry'));
    assert.equal(calls.at(-1).at(-1), 'upload-limits.spec.ts');
    calls.length = 0;
    main({ uploadDurabilityFixture: true, securityDirectory: directory, execute, log: () => {} });
    assert.ok(calls[0].includes('dkp-bp03-012345abcdef'));
    assert.ok(calls.at(-1).includes('DKP_UPLOAD_E2E_ENABLED=true'));
    assert.ok(calls.at(-1).includes('DKP_UPLOAD_DURABILITY_FIXTURE=true'));
    assert.equal(calls.at(-1).at(-1), 'upload-durability.spec.ts');
    const dockerfile = readFileSync(new URL('../../infra/docker-compose/identity-e2e.Dockerfile', import.meta.url), 'utf8');
    assert.ok(dockerfile.includes('test --config playwright.identity.runtime.config.ts "$@"'));
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('redacts every test password occurrence from captured runner output', () => {
  assert.equal(redact('before synthetic-password after synthetic-password', 'synthetic-password'), 'before [REDACTED] after [REDACTED]');
  assert.equal(redact('unchanged', ''), 'unchanged');
  assert.equal(redact('https://localhost/oauth/callback/keycloak?code=temporary-code&state=secret-state', ''), 'https://localhost/oauth/callback/keycloak?code=[REDACTED]&state=[REDACTED]');
  assert.equal(redact('Authorization: Bearer opaque-token', ''), 'Authorization: Bearer [REDACTED]');
  assert.equal(redact('eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature', ''), '[REDACTED-JWT]');
});


test('orchestrates fixed runtime inspection and passes credentials only to the browser process', () => {
  const directory = mkdtempSync(join(tmpdir(), 'dkp-e2e-run-'));
  const portalId = 'a'.repeat(64);
  const providerId = 'b'.repeat(64);
  const calls = [];
  try {
    writeFileSync(join(directory, 'runtime.env'), 'DKP_TEST_USERNAME=tester\nDKP_TEST_PASSWORD=synthetic-password\nOIDC_CLIENT_SECRET=never-pass-this\n', { mode: 0o600 });
    writeFileSync(join(directory, 'ca.crt'), 'synthetic public certificate', { mode: 0o600 });
    const execute = (args, options) => {
      calls.push({ args, options });
      if (args[0] === 'compose') return args.at(-1) === 'admin-portal' ? portalId : providerId;
      if (args[0] === 'inspect') return JSON.stringify([{ NetworkSettings: { Networks: { dkp: { IPAddress: args[1] === portalId ? '172.20.0.2' : '172.20.0.4' } } } }]);
      return '';
    };
    main({ securityDirectory: directory, execute, log: () => {} });
    assert.equal(calls.length, 6);
    assert.equal(calls[4].args[0], 'build');
    assert.equal(calls[4].options, undefined);
    assert.equal(calls[5].options.environment.DKP_IDENTITY_PASSWORD, 'synthetic-password');
    assert.equal(calls[5].options.environment.DKP_IDENTITY_USERNAME, 'tester');
    assert.equal(calls[5].args.includes('synthetic-password'), false);
    assert.equal(calls[5].args.includes('never-pass-this'), false);
    assert.equal(calls[5].options.environment.OIDC_CLIENT_SECRET, process.env.OIDC_CLIENT_SECRET);
    assert.throws(() => main({ securityDirectory: directory, execute: () => '', log: () => {} }), /Start the identity/);
    assert.throws(() => main({ securityDirectory: directory, execute: (args) => args[0] === 'compose' ? portalId : '[{}]', log: () => {} }), /one explicit/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});


test('isolated upload runtime keeps strict origins and avoids the BP02 stack', () => {
  assert.equal(uploadProject('DKP_TEST_USERNAME=tester\n'), 'dkp-bp03');
  assert.equal(uploadProject('DKP_UPLOAD_COMPOSE_PROJECT=dkp-bp03-012345abcdef\n'), 'dkp-bp03-012345abcdef');
  for (const value of ['foreign', 'dkp-bp03-XYZ', 'dkp-bp03-012345abcdef;command']) assert.throws(() => uploadProject('DKP_UPLOAD_COMPOSE_PROJECT=' + value), /Invalid/);
  assert.throws(() => uploadProject('DKP_UPLOAD_COMPOSE_PROJECT=dkp-bp03-012345abcdef\nDKP_UPLOAD_COMPOSE_PROJECT=dkp-bp03-012345abcdef'), /Duplicate/);
  const input = { portalId: 'a'.repeat(64), address: '172.20.0.4', certificate: '/ca' };
  assert.equal(runtimeArguments(input).some((v) => v.startsWith('DKP_UPLOAD_E2E_ENABLED=')), false);
  assert.equal(runtimeArguments({ ...input, uploadRuntime: false }).some((v) => v.startsWith('DKP_UPLOAD_E2E_ENABLED=')), false);
  assert.throws(() => runtimeArguments({ ...input, uploadRuntime: 'true' }), /boolean/);
  const args = runtimeArguments({ ...input, portalPort: 19443, idpPort: 19444, uploadRuntime: true });
  assert.ok(args.includes('DKP_UPLOAD_E2E_ENABLED=true'));
  assert.ok(args.includes('DKP_IDENTITY_BASE_URL=https://localhost:19443'));
  assert.ok(args.includes('DKP_IDENTITY_IDP_BASE_URL=https://idp.localhost:19444'));
  for (const port of [0, 443, 65536, NaN, 1.2, '19443;command']) assert.throws(() => runtimeArguments({ portalId: 'a'.repeat(64), address: '172.20.0.4', certificate: '/ca', portalPort: port }), /port/);
  assert.throws(() => runtimeArguments({ portalId: 'a'.repeat(64), address: '172.20.0.4', certificate: '/ca', portalPort: 19444, idpPort: 19444 }), /Distinct/);
  const directory = mkdtempSync(join(tmpdir(), 'dkp-upload-e2e-'));
  const calls = [];
  try {
    writeFileSync(join(directory, 'runtime.env'), 'DKP_TEST_USERNAME=tester\nDKP_TEST_PASSWORD=synthetic-password\nDKP_UPLOAD_COMPOSE_PROJECT=dkp-bp03-012345abcdef\n', { mode: 0o600 });
    writeFileSync(join(directory, 'ca.crt'), 'synthetic public certificate', { mode: 0o600 });
    const combined = join(directory, 'combined.env');
    writeFileSync(combined, 'DKP_UPLOAD_COMPOSE_PROJECT=dkp-bp03-012345abcdef\nDKP_UPLOAD_STORAGE_ENDPOINT=https://rustfs:9000\n', { mode: 0o600 });
    const execute = (arguments_, options) => {
      calls.push({ args: arguments_, options });
      if (arguments_[0] === 'compose') return arguments_.at(-1) === 'admin-portal' ? 'a'.repeat(64) : 'b'.repeat(64);
      if (arguments_[0] === 'inspect') return JSON.stringify([{ NetworkSettings: { Networks: { dkp: { IPAddress: '172.20.0.4' } } } }]);
      return '';
    };
    main({ uploadRuntime: true, securityDirectory: directory, runtimeEnvironmentFile: combined, execute, log: () => {} });
    assert.ok(calls[0].args.includes(combined));
    assert.ok(calls[0].args.includes('dkp-bp03-012345abcdef'));
    assert.ok(calls[0].args.includes('infra/docker-compose/compose-upload.yaml'));
    assert.ok(calls.at(-1).args.includes('DKP_IDENTITY_BASE_URL=https://localhost:19443'));
    assert.ok(calls.at(-1).args.includes('DKP_UPLOAD_E2E_ENABLED=true'));
    assert.equal(calls.at(-1).args.some((arg) => arg.includes('synthetic-password')), false);
    writeFileSync(combined, 'DKP_UPLOAD_COMPOSE_PROJECT=dkp-bp03-fedcba543210\n');
    assert.throws(() => main({ uploadRuntime: true, securityDirectory: directory, runtimeEnvironmentFile: combined, execute, log: () => {} }), /does not match/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});


test('built runner trust probe uses string URLs on the configured isolated ports', async () => {
  const dockerfile = readFileSync(new URL('../../infra/docker-compose/identity-e2e.Dockerfile', import.meta.url), 'utf8');
  const source = dockerfile.split("<<'TRUST'\n")[1].split('\nTRUST')[0]
    .replace("import { chromium } from '@playwright/test';", 'const chromium = fakeChromium;');
  const visited = [];
  const page = { goto: async (url) => {
    assert.equal(typeof url, 'string', 'Playwright requires a URL string');
    visited.push(url);
    if (url.startsWith('https://wrong-name.localhost:19443/')) throw new Error('ERR_CERT_COMMON_NAME_INVALID');
    return { status: () => 200 };
  } };
  let closed = false;
  const browser = { newContext: async (options) => {
    assert.equal(options.ignoreHTTPSErrors, false);
    return { newPage: async () => page };
  }, close: async () => { closed = true; } };
  const process = { env: { DKP_IDENTITY_BASE_URL: 'https://localhost:19443',
    DKP_IDENTITY_IDP_BASE_URL: 'https://idp.localhost:19444', DKP_IDENTITY_IDP_ADDRESS: '172.20.0.4' } };
  await vm.runInNewContext(`(async () => { ${source} })()`, {
    URL, process, fakeChromium: { launch: async () => browser }, console: { log() {}, error() {} },
  });
  assert.equal(process.exitCode, undefined);
  assert.deepEqual(visited, ['https://localhost:19443/',
    'https://idp.localhost:19444/realms/dkp/.well-known/openid-configuration', 'https://wrong-name.localhost:19443/']);
  assert.equal(closed, true);
  const nodeProbe = dockerfile.match(/node --input-type=module -e '([^']+)'/)[1];
  let fetched;
  await vm.runInNewContext(`(async () => { ${nodeProbe} })()`, {
    process: { ...process, exit: () => { throw new Error('unexpected failure'); } },
    fetch: async (url) => { fetched = url; return { ok: true }; }, console,
  });
  assert.equal(fetched, 'https://localhost:19443');
});
