import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import {
  execute,
  materialFingerprint,
  ownedLocation,
  parseArguments,
  preview,
  ROOT,
  SERVICES,
  sourceFingerprint,
} from './uiux-preview.mjs';
const require = createRequire(import.meta.url);
const { parseDocument } = require('yaml');
const project = 'dkp-uiux-440-0123456789ab';
const secretNames = [
  'ca.crt',
  'ca.key',
  'portal.crt',
  'portal.key',
  'idp.crt',
  'idp.key',
  'truststore.p12',
  'java-security.options',
  'dkp-realm.json',
  'authority/grants.json',
  'authority/cursor.key',
];
function fixture(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'dkp440-test-')));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  writeFileSync(join(root, 'package.json'), '{"private":true}');
  const directory = join(root, 'local-data/security/uiux-preview', project);
  const calls = [];
  const state = {
    badLabels: false,
    unhealthy: false,
    changedId: false,
    externalBind: false,
    collision: false,
    remote: false,
    extraPortalNetwork: false,
  };
  const ids = Object.fromEntries(
    SERVICES.map((name, i) => [name, (i + 1).toString(16).padStart(64, '0')]),
  );
  function material() {
    mkdirSync(join(directory, 'authority'), { recursive: true, mode: 0o700 });
    for (const name of secretNames)
      writeFileSync(join(directory, name), name, { mode: 0o600 });
    writeFileSync(
      join(directory, 'runtime.env'),
      `DKP_PORTAL_PORT=24443\nDKP_IDP_PORT=24444\nDKP_LOCAL_UID=${process.getuid()}\nDKP_TEST_USERNAME=dkp-tester\nDKP_TEST_PASSWORD=private-fixture-password\n`,
      { mode: 0o600 },
    );
    chmodSync(directory, 0o700);
  }
  const labels = (service) => ({
    'com.docker.compose.project': project,
    'dkp.preview.issue': state.badLabels ? 'other' : '440',
    'dkp.preview.project': project,
    ...(service ? { 'com.docker.compose.service': service } : {}),
  });
  function run(command, args, options) {
    calls.push({ command, args, options });
    if (command === 'python3') {
      material();
      return '';
    }
    assert.equal(command, 'docker');
    if (args[0] === 'context')
      return JSON.stringify(
        state.remote ? 'tcp://remote:2375' : 'unix:///var/run/docker.sock',
      );
    if (args[1] === 'ls') return state.collision ? 'existing-resource' : '';
    if (args[0] === 'compose') {
      if (args.includes('ps')) return ids[args.at(-1)];
      return '';
    }
    if (args[1] === 'inspect') {
      if (args[0] === 'container') {
        const service = SERVICES.find((name) => ids[name] === args[2]);
        const trust = {
          '/run/dkp/truststore.p12': 'truststore.p12',
          '/run/dkp/java-security.options': 'java-security.options',
        };
        const files =
          service === 'dm-postgres'
            ? {}
            : service === 'admin-portal'
              ? {
                  '/run/dkp/portal.crt': 'portal.crt',
                  '/run/dkp/portal.key': 'portal.key',
                  '/etc/nginx/conf.d/default.conf': null,
                }
              : service === 'keycloak'
                ? {
                    '/run/dkp/idp.crt': 'idp.crt',
                    '/run/dkp/idp.key': 'idp.key',
                    '/opt/keycloak/data/import/dkp-realm.json':
                      'dkp-realm.json',
                  }
                : service === 'document-management'
                  ? { ...trust, '/run/dkp/authority': 'authority' }
                  : trust;
        const mounts = Object.entries(files).map(([Destination, file]) => ({
          Type: 'bind',
          Destination,
          Source:
            file === null
              ? join(root, 'infra/docker-compose/nginx-uiux-preview.conf')
              : join(state.externalBind ? root : directory, file),
          RW: false,
        }));
        if (service === 'dm-postgres')
          mounts.push({
            Type: 'volume',
            Name: `${project}_dm-postgres-data`,
            Destination: '/var/lib/postgresql',
            RW: true,
          });
        const networkNames =
          service === 'document-management'
            ? ['default', 'dm-data']
            : service === 'dm-postgres'
              ? ['dm-data']
              : ['default'];
        return JSON.stringify([
          {
            Id: state.changedId ? 'f'.repeat(64) : ids[service],
            Image: 'sha256:' + 'a'.repeat(64),
            Config: { Labels: labels(service) },
            State: {
              Status: 'running',
              Health: { Status: state.unhealthy ? 'starting' : 'healthy' },
            },
            Mounts: mounts,
            NetworkSettings: {
              Networks: {
                ...Object.fromEntries(
                  networkNames.map((name) => [
                    `${project}_${name}`,
                    { IPAddress: '172.21.0.4' },
                  ]),
                ),
                ...(state.extraPortalNetwork && service === 'admin-portal'
                  ? { other: { IPAddress: '172.22.0.4' } }
                  : {}),
              },
            },
          },
        ]);
      }
      if (args[0] === 'network')
        return JSON.stringify([
          {
            Id: (args[2].endsWith('dm-data') ? 'c' : 'b').repeat(64),
            Name: args[2],
            Internal: args[2].endsWith('dm-data'),
            Labels: labels(),
          },
        ]);
      if (args[0] === 'volume')
        return JSON.stringify([{ Name: args[2], Labels: labels() }]);
    }
    if (args[0] === 'build' || args[0] === 'run') return '';
    throw new Error('Unexpected stub command');
  }
  return { root, project, directory, calls, state, run, material };
}
function start(f) {
  return preview({ ...f, action: 'start' });
}
function verify(f, browser = false) {
  return preview({ ...f, action: 'verify', browser });
}

test('CLI requires explicit owned project/path and disallows unknown, duplicate or incomplete flags', () => {
  assert.deepEqual(
    parseArguments([
      'verify',
      '--project',
      project,
      '--directory',
      'owned',
      '--browser',
    ]),
    { action: 'verify', project, directory: 'owned', browser: true },
  );
  for (const args of [
    [],
    ['start', '--project'],
    ['verify', '--project', project],
    ['verify', '--project', project, '--project', project],
    ['verify', '--other'],
    ['verify', '--directory', '--browser'],
  ])
    assert.throws(() => parseArguments(args));
});
test('owned location rejects another stack, traversal, arbitrary projects and symlink ancestors', (t) => {
  const f = fixture(t);
  assert.equal(ownedLocation(f.root, project, f.directory), f.directory);
  for (const name of [
    'dkp-foundation',
    'dkp-bp03',
    'dkp-bp04',
    project + ';echo',
  ])
    assert.throws(() => ownedLocation(f.root, name, f.directory));
  for (const path of ['local-data/security', '/tmp/unowned', '../other'])
    assert.throws(() => ownedLocation(f.root, project, path));
  mkdirSync(join(f.root, 'local-data'));
  symlinkSync(tmpdir(), join(f.root, 'local-data/security'));
  assert.throws(() => ownedLocation(f.root, project, f.directory));
});
test('source identity is deterministic, changes with application source, and excludes build/dependency output', (t) => {
  const f = fixture(t);
  const before = sourceFingerprint(f.root);
  mkdirSync(join(f.root, 'apps/admin-portal/node_modules'), {
    recursive: true,
  });
  writeFileSync(
    join(f.root, 'apps/admin-portal/node_modules/private'),
    'ignored',
  );
  assert.equal(sourceFingerprint(f.root), before);
  writeFileSync(join(f.root, 'apps/admin-portal/actual.ts'), 'real source');
  assert.notEqual(sourceFingerprint(f.root), before);
  symlinkSync(
    join(f.root, 'package.json'),
    join(f.root, 'apps/admin-portal/linked.ts'),
  );
  assert.throws(() => sourceFingerprint(f.root));
});
test('fresh start records material/source/resource identity with no default-port or destructive commands', (t) => {
  const f = fixture(t);
  const result = start(f);
  assert.equal(result.url, 'https://localhost:24443');
  assert.equal(result.browserVerified, false);
  const receiptPath = join(f.directory, 'preview-receipt.json');
  const receipt = JSON.parse(readFileSync(receiptPath, 'utf8'));
  assert.equal(receipt.phase, 'ready');
  assert.equal(receipt.resources.volume, `${project}_dm-postgres-data`);
  assert.equal(Object.keys(receipt.resources.containers).length, 7);
  assert.equal(receipt.sourceFingerprint, sourceFingerprint(f.root));
  assert.equal(receipt.materialFingerprint, materialFingerprint(f.directory));
  assert.equal(verify(f).phase, 'ready');
  const commands = JSON.stringify(f.calls.map(({ args }) => args));
  assert.doesNotMatch(commands, /18443|19443|20443|down|prune|--volumes/);
  assert.ok(
    f.calls.some(
      ({ args }) => args.includes('--portal-port') && args.includes('24443'),
    ),
  );
  assert.ok(
    f.calls
      .filter(({ args }) => args[0] === 'compose')
      .every(
        ({ args }) =>
          args.includes(project) &&
          args.includes(join(f.directory, 'runtime.env')),
      ),
  );
  assert.throws(() => start(f));
});
test('start refuses existing labeled resources and remote Docker endpoints before bootstrap', (t) => {
  const f = fixture(t);
  f.state.collision = true;
  assert.throws(() => start(f));
  assert.equal(existsSync(f.directory), false);
  f.state.remote = true;
  assert.throws(() => start(f));
  assert.equal(
    f.calls.some(({ command }) => command === 'python3'),
    false,
  );
  assert.throws(() => preview({ ...f, action: 'stop' }));
  assert.throws(() => preview({ ...f, action: 'start', browser: true }));
});
test('verification refuses changed source, tampered material, unsafe permissions and altered ownership/health', (t) => {
  const f = fixture(t);
  start(f);
  for (const flag of ['badLabels', 'unhealthy', 'changedId', 'externalBind']) {
    f.state[flag] = true;
    assert.throws(() => verify(f), flag);
    f.state[flag] = false;
  }
  const receipt = join(f.directory, 'preview-receipt.json');
  chmodSync(receipt, 0o644);
  assert.throws(() => verify(f));
  chmodSync(receipt, 0o600);
  writeFileSync(join(f.root, 'package.json'), '{}');
  assert.throws(() => verify(f));
  writeFileSync(join(f.root, 'package.json'), '{"private":true}');
  writeFileSync(join(f.directory, 'ca.crt'), 'changed authority');
  assert.throws(() => verify(f));
});
test('browser check targets exact owned container IDs with strict fresh CA and project-specific image', (t) => {
  const f = fixture(t);
  start(f);
  assert.equal(verify(f, true).browserVerified, true);
  const browser = f.calls.find(({ args }) => args[0] === 'run');
  assert.ok(
    browser.args.includes('DKP_IDENTITY_BASE_URL=https://localhost:24443'),
  );
  assert.ok(
    browser.args.includes(
      'DKP_IDENTITY_IDP_BASE_URL=https://idp.localhost:24444',
    ),
  );
  assert.ok(browser.args.includes(`${project}-identity-e2e:local`));
  assert.equal(
    browser.options.env.DKP_IDENTITY_PASSWORD,
    'private-fixture-password',
  );
  assert.doesNotMatch(
    JSON.stringify(browser.args),
    /private-fixture-password|ignore-certificate|dkp-identity-e2e:local/,
  );
  f.state.extraPortalNetwork = true;
  assert.throws(() => verify(f, true));
});
test('failed start retains a private prepared receipt and never attempts teardown', (t) => {
  const f = fixture(t);
  const run = (program, args, options) => {
    if (args.includes('up')) throw new Error('private diagnostic');
    return f.run(program, args, options);
  };
  assert.throws(() => start({ ...f, run }));
  const receipt = JSON.parse(
    readFileSync(join(f.directory, 'preview-receipt.json'), 'utf8'),
  );
  assert.equal(receipt.phase, 'prepared');
  assert.equal(receipt.resources, null);
  assert.throws(() => verify(f));
  assert.equal(
    f.calls.some(({ args }) => args.includes('down')),
    false,
  );
});
test('private material rejects wrong ports, group-readable secrets and symlink files', (t) => {
  const f = fixture(t);
  f.material();
  assert.equal(materialFingerprint(f.directory).length, 64);
  chmodSync(join(f.directory, 'portal.key'), 0o644);
  assert.throws(() => materialFingerprint(f.directory));
  chmodSync(join(f.directory, 'portal.key'), 0o600);
  writeFileSync(
    join(f.directory, 'runtime.env'),
    'DKP_PORTAL_PORT=18443\nDKP_IDP_PORT=18444\n',
    { mode: 0o600 },
  );
  assert.throws(() => materialFingerprint(f.directory));
});
test('execution failure never discloses subprocess stderr or arguments', () => {
  assert.throws(
    () =>
      execute(process.execPath, [
        '-e',
        'console.error("SECRET_DIAGNOSTIC");process.exit(1)',
      ]),
    (error) => !error.message.includes('SECRET_DIAGNOSTIC'),
  );
  assert.equal(
    execute(process.execPath, ['-e', 'process.stdout.write("safe")']),
    'safe',
  );
  const result = spawnSync(
    process.execPath,
    [join(ROOT, 'tools/runtime/uiux-preview.mjs')],
    { encoding: 'utf8' },
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Owned UI\/UX preview failed/);
  assert.doesNotMatch(result.stderr, /at file:|Error:/);
});
test('YAML overrides every external port/security mount/issuer without weakening base hardening', () => {
  const text = readFileSync(
    join(ROOT, 'infra/docker-compose/compose-uiux-preview.yaml'),
    'utf8',
  );
  const doc = parseDocument(text, {
    merge: true,
    customTags: [
      { tag: '!override', collection: 'seq', resolve: (value) => value },
    ],
  });
  assert.equal(doc.errors.length, 0);
  const value = doc.toJS();
  assert.equal(Object.keys(value.services).length, 7);
  for (const service of SERVICES) {
    const item = value.services[service];
    assert.equal(item.labels['dkp.preview.issue'], '440');
    assert.equal(item.privileged, undefined);
    assert.equal(item.network_mode, undefined);
    if (
      ['document-management', 'knowledge-base', 'knowledge-search'].includes(
        service,
      )
    )
      assert.deepEqual(item.ports, []);
    if (
      service !== 'dm-postgres' &&
      service !== 'keycloak' &&
      service !== 'admin-portal'
    ) {
      assert.equal(
        item.environment.OIDC_ISSUER,
        'https://idp.localhost:24444/realms/dkp',
      );
      assert.equal(
        item.environment.OIDC_JWKS_URL,
        'https://idp.localhost:24444/realms/dkp/protocol/openid-connect/certs',
      );
    }
    for (const mount of item.volumes ?? [])
      assert.ok(
        mount.endsWith(':ro') &&
          (mount.startsWith('${DKP_UIUX_PREVIEW_SECURITY:') ||
            mount.startsWith('./nginx-uiux-preview.conf:')),
      );
  }
  assert.deepEqual(value.services['admin-portal'].ports, [
    '127.0.0.1:24443:24443',
  ]);
  assert.deepEqual(value.services.keycloak.ports, ['127.0.0.1:24444:24444']);
  assert.equal(
    value.services['document-management'].environment.DKP_DM_UPLOAD_ENABLED,
    'false',
  );
  assert.equal(
    value.services['browser-bff'].environment.OIDC_CALLBACK_URI,
    'https://localhost:24443/oauth/callback/keycloak',
  );
  assert.equal(value.services.keycloak.environment.KC_HTTPS_PORT, '24444');
  assert.doesNotMatch(text, /18443|19443|20443|external:\s*true/);
  const nginx = readFileSync(
    join(ROOT, 'infra/docker-compose/nginx-uiux-preview.conf'),
    'utf8',
  );
  assert.match(nginx, /listen 24443 ssl/);
  assert.match(nginx, /proxy_set_header X-Forwarded-Port 24443/);
  assert.match(nginx, /access_log off/);
  assert.match(nginx, /error_log \/dev\/null/);
  assert.match(nginx, /ssl_protocols TLSv1.2 TLSv1.3/);
  assert.match(nginx, /client_max_body_size 4k/);
  const normalized = (value) =>
    value.replace(/#.*$/gm, '').replace(/\s+/g, ' ').trim();
  assert.equal(
    normalized(nginx),
    normalized(
      readFileSync(
        join(ROOT, 'infra/docker-compose/nginx.conf'),
        'utf8',
      ).replaceAll('18443', '24443'),
    ),
  );
});

test('source identity covers raw contracts and generated Java used by Gradle', (t) => {
  const f = fixture(t);
  let before = sourceFingerprint(f.root);
  for (const name of [
    'contracts/openapi/v1/identity.yaml',
    'generated-clients/java/src/main/java/SyntheticContract.java',
  ]) {
    const file = join(f.root, name);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, 'original boundary');
    const introduced = sourceFingerprint(f.root);
    assert.notEqual(introduced, before);
    writeFileSync(file, 'changed boundary');
    const changed = sourceFingerprint(f.root);
    assert.notEqual(changed, introduced);
    before = changed;
  }
});
