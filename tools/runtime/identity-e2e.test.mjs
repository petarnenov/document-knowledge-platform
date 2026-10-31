import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, chmodSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { findAddress, main, readCredentials, redact, requirePrivateFile, runtimeArguments } from './identity-e2e.mjs';

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
