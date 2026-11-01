import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, chmodSync, rmSync, symlinkSync, copyFileSync, existsSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { spawnSync } from 'node:child_process';
import { loadKbBrowserState, validateKbBrowserContainers } from './kb-browser-runtime.mjs';
import { kbCliArguments, main, runtimeArguments } from './identity-e2e.mjs';

function fixture(root) {
  mkdirSync(join(root, 'local-data/security'), { recursive: true, mode: 0o700 });
  const run = mkdtempSync(join(root, 'local-data/security/kb-browser-unit-'));
  const securityDirectory = join(run, 'security/identity');
  mkdirSync(securityDirectory, { recursive: true, mode: 0o700 });
  const runtimeEnvironmentFile = join(run, 'security/runtime.env');
  const credentials = 'DKP_TEST_USERNAME=tester\nDKP_TEST_PASSWORD=synthetic-unit-only\n';
  for (const [file, content] of [[runtimeEnvironmentFile, credentials], [join(securityDirectory, 'runtime.env'), credentials], [join(securityDirectory, 'ca.crt'), 'synthetic-unit-only']]) writeFileSync(file, content, { mode: 0o600 });
  const state = { schemaVersion: 1, profile: 'dkp-bp04', project: 'dkp-bp04-012345abcdef', runId: 'a'.repeat(32), securityDirectory,
    runtimeEnvironmentFile, portalId: '1'.repeat(64), keycloakId: '2'.repeat(64), networkName: 'dkp-bp04-012345abcdef_default',
    networkId: '3'.repeat(64), portalImageId: 'sha256:' + '4'.repeat(64), keycloakImageId: 'sha256:' + '5'.repeat(64) };
  const file = join(run, 'browser-state.json');
  const save = (value = state) => writeFileSync(file, JSON.stringify(value), { mode: 0o600 });
  save();
  const options = { root, securityDirectory, runtimeEnvironmentFile };
  const labels = { 'com.docker.compose.project': state.project, 'dkp.run-id': state.runId, 'dkp.profile': state.profile };
  const container = (service, id, image) => ({ Id: id, Image: image, State: { Running: true, Health: { Status: 'healthy' } },
    Config: { Labels: { ...labels, 'com.docker.compose.service': service } },
    NetworkSettings: { Networks: { [state.networkName]: { NetworkID: state.networkId, IPAddress: '172.20.0.4' } } } });
  return { run, file, state, options, save, portal: container('admin-portal', state.portalId, state.portalImageId),
    provider: container('keycloak', state.keycloakId, state.keycloakImageId), network: { Id: state.networkId, Name: state.networkName, Labels: labels } };
}

test('private closed KB runtime record rejects foreign paths, schemas, owners and unsafe files', () => {
  const root = mkdtempSync(join(tmpdir(), 'dkp-kb-browser-test-')); const f = fixture(root);
  try {
    assert.deepEqual(loadKbBrowserState(f.file, f.options), f.state);
    for (const change of [{ extra: true }, { schemaVersion: 2 }, { profile: 'other' }, { project: 'dkp-bp03' }, { runId: 'x' },
      { securityDirectory: '/foreign' }, { runtimeEnvironmentFile: '/foreign' }, { portalId: 'bad' }, { keycloakId: f.state.portalId },
      { networkName: 'other' }, { networkId: 'bad' }, { portalImageId: 'tag:latest' }, { keycloakImageId: 'bad' }]) {
      f.save({ ...f.state, ...change }); assert.throws(() => loadKbBrowserState(f.file, f.options));
    }
    f.save();
    assert.throws(() => loadKbBrowserState(undefined, f.options));
    assert.throws(() => loadKbBrowserState('relative.json', f.options));
    assert.throws(() => loadKbBrowserState(f.file, { ...f.options, root: join(root, 'foreign') }));
    assert.throws(() => loadKbBrowserState(f.file, { ...f.options, securityDirectory: '/foreign' }));
    chmodSync(f.file, 0o644); assert.throws(() => loadKbBrowserState(f.file, f.options)); chmodSync(f.file, 0o600);
    chmodSync(f.run, 0o770); assert.throws(() => loadKbBrowserState(f.file, f.options)); chmodSync(f.run, 0o700);
    const ca = join(f.options.securityDirectory, 'ca.crt'); rmSync(ca); symlinkSync(f.file, ca);
    assert.throws(() => loadKbBrowserState(f.file, f.options));
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('actual container, image, network, ownership and health must match the recorded KB runtime', () => {
  const root = mkdtempSync(join(tmpdir(), 'dkp-kb-inspect-test-')); const f = fixture(root);
  try {
    assert.equal(validateKbBrowserContainers(f.state, f.portal, f.provider, f.network), '172.20.0.4');
    for (const mutate of [x => x.Id = '6'.repeat(64), x => x.Image = 'sha256:' + '7'.repeat(64),
      x => x.State.Running = false, x => x.State.Health.Status = 'unhealthy',
      x => x.Config.Labels['dkp.run-id'] = 'b'.repeat(32), x => x.Config.Labels['dkp.profile'] = 'other',
      x => x.Config.Labels['com.docker.compose.project'] = 'other', x => x.Config.Labels['com.docker.compose.service'] = 'other',
      x => x.NetworkSettings.Networks[f.state.networkName].NetworkID = '8'.repeat(64)]) {
      const altered = structuredClone(f.portal); mutate(altered); assert.throws(() => validateKbBrowserContainers(f.state, altered, f.provider, f.network));
    }
    for (const change of [{ Id: 'bad' }, { Name: 'other' }, { Labels: {} }]) assert.throws(() => validateKbBrowserContainers(f.state, f.portal, f.provider, { ...f.network, ...change }));
    const extra = structuredClone(f.portal); extra.NetworkSettings.Networks.other = {};
    assert.throws(() => validateKbBrowserContainers(f.state, extra, f.provider, f.network));
    assert.throws(() => validateKbBrowserContainers(f.state, undefined, f.provider, f.network));
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('KB selector is explicit, fixed-port and exclusive of every upload profile', () => {
  const input = { portalId: '1'.repeat(64), address: '172.20.0.4', certificate: '/ca', kbRuntime: true, portalPort: 20443, idpPort: 20444 };
  const args = runtimeArguments(input);
  assert.equal(args.at(-1), 'knowledge.spec.ts'); assert.ok(args.includes('DKP_KB_E2E_ENABLED=true'));
  assert.ok(args.includes('DKP_IDENTITY_BASE_URL=https://localhost:20443'));
  assert.equal(args.some(x => x.startsWith('DKP_UPLOAD_')), false);
  for (const change of [{ kbRuntime: 'true' }, { uploadRuntime: true }, { uploadLimitsProfile: 'expiry' }, { uploadDurabilityFixture: true }, { portalPort: 19443 }, { idpPort: 19444 }]) assert.throws(() => runtimeArguments({ ...input, ...change }));
  assert.throws(() => main({ kbRuntime: true }));
  assert.throws(() => main({ kbRuntimeState: '/foreign' }));
});

test('KB command line rejects duplicate, incomplete, unknown and mixed-profile flags', () => {
  const args = ['--kb-runtime', '--kb-runtime-state', '/owned/browser-state.json', '--security-directory', '/owned/security/identity', '--runtime-env-file', '/owned/security/runtime.env'];
  assert.deepEqual(kbCliArguments(args), { kbRuntime: true, kbRuntimeState: '/owned/browser-state.json' });
  assert.deepEqual(kbCliArguments(['--upload-runtime']), {});
  for (const invalid of [[...args, '--kb-runtime'], [...args, '--upload-runtime'], [...args, '--host', 'foreign'],
    [...args, '--kb-runtime-state', '/other'], args.slice(1), args.slice(0, -1), ['--kb-runtime']]) assert.throws(() => kbCliArguments(invalid));
});

function exerciseMain(root, main) {
  const f = fixture(root); const calls = [];
  try {
    const execute = args => {
      calls.push(args);
      if (args[0] === 'compose') return args.at(-1) === 'admin-portal' ? f.state.portalId : f.state.keycloakId;
      if (args[0] === 'inspect') return JSON.stringify([args[1] === f.state.portalId ? f.portal : f.provider]);
      if (args[0] === 'network') return JSON.stringify([f.network]);
      return '';
    };
    main({ kbRuntime: true, kbRuntimeState: f.file, securityDirectory: f.options.securityDirectory, runtimeEnvironmentFile: f.options.runtimeEnvironmentFile, execute, log() {} });
    assert.ok(calls[0].includes('infra/docker-compose/compose-kb.yaml'));
    assert.ok(calls[0].includes(f.state.project)); assert.equal(calls.at(-1).at(-1), 'knowledge.spec.ts');
    f.portal.Config.Labels['dkp.run-id'] = 'b'.repeat(32); calls.length = 0;
    assert.throws(() => main({ kbRuntime: true, kbRuntimeState: f.file, securityDirectory: f.options.securityDirectory, runtimeEnvironmentFile: f.options.runtimeEnvironmentFile, execute, log() {} }));
    assert.equal(calls.some(x => x[0] === 'build' || x[0] === 'run'), false);
  } finally { rmSync(f.run, { recursive: true, force: true }); }
}

test('KB main verifies owned inspection in an isolated checkout without baseline pollution', () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'dkp-kb-main-test-')));
  try {
    const tools = join(root, 'tools/runtime');
    mkdirSync(tools, { recursive: true, mode: 0o700 });
    for (const name of ['identity-e2e.mjs', 'kb-browser-runtime.mjs']) {
      copyFileSync(fileURLToPath(new URL(name, import.meta.url)), join(tools, name));
    }
    const script = `import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { main } from ${JSON.stringify(pathToFileURL(join(tools, 'identity-e2e.mjs')).href)};
${fixture.toString()}
${exerciseMain.toString()}
exerciseMain(${JSON.stringify(root)}, main);`;
    // Run the unmodified runner from a disposable checkout so its real module-root
    // lookup is tested without creating authority in the source checkout.
    const child = spawnSync(process.execPath, ['--input-type=module'], {
      input: script, encoding: 'utf8', timeout: 10_000,
    });
    assert.equal(child.status, 0, 'Isolated real-root runner fixture failed');
    assert.equal(existsSync(join(root, 'local-data/security')), true);
  } finally { rmSync(root, { recursive: true, force: true }); }
  assert.equal(existsSync(root), false);
});
