import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { allowed, execute, verify } from './frontend.mjs';
import { check, commands } from './frontend-check.mjs';

const runtime = { version: 'v24.22.1', platform: 'linux', arch: 'x64' };
function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'frontend-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  writeFileSync(join(root, 'frontend-source.json'), '{"sha256":"synthetic"}');
  return root;
}
const ok = { status: 0, stdout: '', stderr: '' };

test('context allows only build sources and excludes private/generated local outputs', () => {
  for (const path of [
    'package.json',
    '.npmrc',
    'pnpm-lock.yaml',
    'pnpm-workspace.yaml',
    '.prettierrc.json',
    'docs/prototypes/DKP_Interactive_Design_v0.5.html',
    'tools/visual/capture-reference.mjs',
    'tools/visual/capture-reference.test.mjs',
    'patches/example.patch',
    'apps/admin-portal/src/new.tsx',
    'tools/ci/frontend.Dockerfile',
    'generated-clients/typescript/model.ts',
  ])
    assert.equal(allowed(path), true, path);
  for (const path of [
    'local-data/security/runtime.env',
    '.git/config',
    'services/domain.java',
    'docs/prototypes/other.html',
    'tools/visual/private.mjs',
    'docs/prototypes/DKP_Interactive_Design_v0.5.html/private',
    'docs/architecture/private.md',
    'apps/admin-portal/.env.production',
    'apps/admin-portal/credentials.json',
    'apps/admin-portal/test.key',
    'apps/admin-portal/node_modules/a',
    'apps/admin-portal/coverage/a',
    'apps/admin-portal/secrets/a',
    'apps/admin-portal/dist/a',
    'apps/admin-portal/test-results/a',
    'apps/admin-portal/playwright-report/a',
    'apps/admin-portal/../secret',
  ])
    assert.equal(allowed(path), false, path);
});

test('preserves every gate and helper coverage before mock E2E', (t) => {
  const root = fixture(t);
  for (const name of ['coverage', 'playwright-report', 'test-results']) {
    mkdirSync(join(root, 'apps/admin-portal', name), { recursive: true });
    writeFileSync(
      join(root, 'apps/admin-portal', name, 'synthetic.txt'),
      'fixture',
    );
  }
  const seen = [];
  const run = (cmd, args) => {
    seen.push([cmd, ...args]);
    return args[0] === '--version' ? { ...ok, stdout: '12.16.1\n' } : ok;
  };
  assert.equal(check(root, join(root, 'evidence'), run, runtime), 0);
  assert.deepEqual(
    seen.slice(1),
    commands.map(([, args]) => ['pnpm', ...args]),
  );
  assert.deepEqual(
    commands.map(([name]) => name),
    [
      'install',
      'preview-runner-coverage',
      'format',
      'lint',
      'typecheck',
      'build',
      'coverage',
      'helper-coverage',
      'mock-e2e',
      'visual-reference-coverage',
    ],
  );
  const helper = commands.find(([name]) => name === 'helper-coverage')[1];
  assert(
    helper.includes('--coverage.include=e2e-real/upload-fault-contract.ts'),
  );
  assert(helper.includes('--coverage.include=e2e-real/ui-create-receipt.ts'));
  for (const metric of ['lines', 'branches', 'functions', 'statements'])
    assert(helper.includes(`--coverage.thresholds.${metric}=75`));
  assert.equal(
    JSON.parse(readFileSync(join(root, 'evidence/result.json'))).passed,
    true,
  );
  assert(existsSync(join(root, 'evidence/test-results/synthetic.txt')));
});

test('a failed gate preserves reports, returns its status and stops later commands', (t) => {
  const root = fixture(t);
  const seen = [];
  const run = (_, args) => {
    seen.push(args);
    return args[0] === '--version'
      ? { ...ok, stdout: '12.16.1' }
      : args.includes('lint')
        ? { status: 7, stderr: 'synthetic failure' }
        : ok;
  };
  assert.equal(check(root, join(root, 'evidence'), run, runtime), 7);
  assert.equal(seen.length, 5);
  const result = JSON.parse(readFileSync(join(root, 'evidence/result.json')));
  assert.equal(result.passed, false);
  assert.equal(result.results.at(-1).status, 7);
});

test('signal/spawn failures cannot report success', (t) => {
  const root = fixture(t);
  assert.equal(
    check(
      root,
      join(root, 'evidence'),
      (_, args) =>
        args[0] === '--version'
          ? { ...ok, stdout: '12.16.1' }
          : { status: null, signal: 'SIGTERM' },
      runtime,
    ),
    1,
  );
});

test('wrong runtime or package manager fails closed with recorded result', (t) => {
  const root = fixture(t);
  assert.throws(
    () =>
      check(root, join(root, 'wrong-node'), () => assert.fail(), {
        ...runtime,
        version: 'v22.0.0',
      }),
    /pinned Linux/,
  );
  assert.throws(
    () =>
      check(root, join(root, 'wrong-os'), () => assert.fail(), {
        ...runtime,
        platform: 'darwin',
      }),
    /pinned Linux/,
  );
  assert.throws(
    () =>
      check(
        root,
        join(root, 'wrong-pnpm'),
        () => ({ ...ok, stdout: '11.0.0' }),
        runtime,
      ),
    /pinned pnpm/,
  );
  assert.equal(
    JSON.parse(readFileSync(join(root, 'wrong-pnpm/result.json'))).passed,
    false,
  );
});

function dockerFixture(t, failure) {
  const root = fixture(t);
  writeFileSync(join(root, 'package.json'), '{}');
  const calls = [];
  let context;
  const run = (cmd, args) => {
    calls.push([cmd, ...args]);
    if (cmd === 'git')
      return { ...ok, stdout: 'package.json\0local-data/secret\0' };
    if (args[0] === 'build') {
      context = args.at(-1);
      assert(existsSync(join(context, 'package.json')));
      assert(!existsSync(join(context, 'local-data')));
      assert.match(
        JSON.parse(readFileSync(join(context, 'frontend-source.json'))).sha256,
        /^[a-f0-9]{64}$/,
      );
    }
    if (args[0] === failure) return { status: 9 };
    if (args[0] === 'image' && args[1] === 'inspect')
      return { ...ok, stdout: `sha256:${'a'.repeat(64)}\n` };
    return ok;
  };
  return { root, calls, run, context: () => context };
}

test('local runner creates its own isolated container, copies evidence, and cleans up', (t) => {
  const f = dockerFixture(t);
  const output = join(f.root, 'evidence');
  assert.equal(verify(f.root, output, f.run), 0);
  assert.deepEqual(
    f.calls.map((call) => call.slice(0, 2).join(' ')),
    [
      'git ls-files',
      'docker build',
      'docker image',
      'docker create',
      'docker start',
      'docker cp',
      'docker rm',
      'docker image',
    ],
  );
  const creation = f.calls.find((call) => call[1] === 'create');
  assert(creation.includes('--cap-drop=ALL'));
  for (const limit of ['--memory=4g', '--cpus=2', '--pids-limit=768'])
    assert(creation.includes(limit));
  assert.equal(creation.at(-1), `sha256:${'a'.repeat(64)}`);
  assert.equal(
    JSON.parse(readFileSync(join(output, 'runner.json'))).imageId,
    `sha256:${'a'.repeat(64)}`,
  );
  assert(creation.includes('--security-opt=no-new-privileges'));
  assert(
    !creation.some(
      (arg) =>
        arg === '-v' ||
        arg.startsWith('--mount') ||
        arg.includes('docker.sock'),
    ),
  );
  assert(!existsSync(f.context()));
  assert.equal(JSON.parse(readFileSync(join(output, 'runner.json'))).status, 0);
  assert.throws(() => verify(f.root, output, f.run), /must be new/);
});

test('container failure is nonzero but still copies evidence and cleans up', (t) => {
  const f = dockerFixture(t, 'start');
  assert.equal(verify(f.root, join(f.root, 'evidence'), f.run), 9);
  assert(f.calls.some((call) => call[1] === 'cp'));
  assert(f.calls.some((call) => call[1] === 'rm'));
});

test('build/create/copy failures clean only their own resources and fail', (t) => {
  for (const failure of ['build', 'create', 'cp']) {
    const f = dockerFixture(t, failure);
    assert.throws(
      () => verify(f.root, join(f.root, 'evidence'), f.run),
      /failed/,
    );
    assert(!existsSync(f.context()));
    assert.equal(
      f.calls.some((call) => call[1] === 'rm'),
      failure === 'cp',
    );
  }
});

test('symlinks are rejected instead of importing data outside source tree', (t) => {
  const root = fixture(t);
  symlinkSync(join(root, 'frontend-source.json'), join(root, 'package.json'));
  assert.throws(
    () =>
      verify(root, join(root, 'evidence'), (cmd) =>
        cmd === 'git' ? { ...ok, stdout: 'package.json\0' } : ok,
      ),
    /regular file/,
  );
});

test('command wrapper rejects signalled or nonzero commands', () => {
  assert.throws(
    () =>
      execute('synthetic', [], {}, () => ({ status: null, signal: 'SIGKILL' })),
    /SIGKILL/,
  );
  assert.throws(
    () => execute('synthetic', [], {}, () => ({ status: null })),
    /spawn error/,
  );
});

test('cleanup failures never turn into a successful run', (t) => {
  const f = dockerFixture(t, 'rm');
  assert.equal(verify(f.root, join(f.root, 'evidence'), f.run), 1);
  assert.equal(
    JSON.parse(readFileSync(join(f.root, 'evidence/runner.json'))).status,
    1,
  );
});

test('copy failure records a failed overall status even after successful checks', (t) => {
  const f = dockerFixture(t, 'cp');
  assert.throws(
    () => verify(f.root, join(f.root, 'evidence'), f.run),
    /failed/,
  );
  assert.equal(
    JSON.parse(readFileSync(join(f.root, 'evidence/runner.json'))).status,
    1,
  );
});

test('invalid immutable image ID blocks container creation', (t) => {
  const f = dockerFixture(t);
  const run = (cmd, args, options) =>
    args[0] === 'image' && args[1] === 'inspect'
      ? { ...ok, stdout: 'mutable:tag' }
      : f.run(cmd, args, options);
  assert.throws(
    () => verify(f.root, join(f.root, 'evidence'), run),
    /Invalid built image ID/,
  );
  assert(!f.calls.some((call) => call[1] === 'create'));
});

const previewInputs = [
  'tools/runtime/uiux-preview.mjs',
  'tools/runtime/uiux-preview.test.mjs',
  'tools/runtime/identity-e2e.mjs',
  'infra/docker-compose/compose-uiux-preview.yaml',
  'infra/docker-compose/nginx-uiux-preview.conf',
  'infra/docker-compose/nginx.conf',
];
test('preview coverage inputs are exact public files, with no broader runtime or Compose access', () => {
  for (const path of previewInputs) assert.equal(allowed(path), true, path);
  for (const path of [
    ...previewInputs.map((name) => name + '.private'),
    'tools/runtime/other-runner.mjs',
    'tools/runtime/bootstrap-security.py',
    'tools/runtime/uiux-preview.private.log',
    'tools/runtime/uiux-preview.mjs/../credentials.json',
    'infra/docker-compose/compose.yaml',
    'infra/docker-compose/compose-upload.yaml',
    'infra/docker-compose/compose-uiux-preview.yaml.backup',
    'infra/docker-compose/portal.key',
    'local-data/security/uiux-preview/runtime.env',
    'local-data/security/uiux-preview/ca.crt',
    'tools/runtime/.env',
    'tools/runtime/credentials.json',
  ])
    assert.equal(allowed(path), false, path);
});

test('preview gate uses the same pinned script after frozen installation and enforces all Node coverage metrics', () => {
  const script = JSON.parse(
    readFileSync(new URL('../../package.json', import.meta.url)),
  ).scripts['uiux-preview:test'];
  assert.match(script, /^node --experimental-test-coverage /);
  assert(
    script.includes('--test-coverage-include=tools/runtime/uiux-preview.mjs'),
  );
  assert(script.endsWith('--test tools/runtime/uiux-preview.test.mjs'));
  for (const metric of ['lines', 'branches', 'functions'])
    assert(script.includes(`--test-coverage-${metric}=75`));
  assert.deepEqual(commands.slice(0, 2), [
    ['install', ['install', '--frozen-lockfile']],
    ['preview-runner-coverage', ['uiux-preview:test']],
  ]);
});

test('failed preview runner coverage records failure and prevents later frontend gates', (t) => {
  const root = fixture(t);
  const seen = [];
  const run = (_, args) => {
    seen.push(args);
    return args[0] === '--version'
      ? { ...ok, stdout: '12.16.1' }
      : args[0] === 'uiux-preview:test'
        ? { status: 9, stderr: 'synthetic coverage failure' }
        : ok;
  };
  assert.equal(check(root, join(root, 'evidence'), run, runtime), 9);
  assert.deepEqual(seen, [
    ['--version'],
    ['install', '--frozen-lockfile'],
    ['uiux-preview:test'],
  ]);
  const result = JSON.parse(readFileSync(join(root, 'evidence/result.json')));
  assert.equal(result.passed, false);
  assert.equal(result.results.at(-1).name, 'preview-runner-coverage');
  assert.equal(result.results.at(-1).status, 9);
  assert.match(
    readFileSync(join(root, 'evidence/preview-runner-coverage.log'), 'utf8'),
    /synthetic coverage failure/,
  );
});

test('isolated context copies exactly the new preview public inputs while excluding nearby private files', (t) => {
  const f = dockerFixture(t);
  const forbidden = [
    'tools/runtime/uiux-preview.private.log',
    'infra/docker-compose/runtime.env',
  ];
  for (const file of [...previewInputs, ...forbidden]) {
    mkdirSync(join(f.root, file, '..'), { recursive: true });
    writeFileSync(join(f.root, file), 'public fixture');
  }
  const run = (command, args, options) => {
    if (command === 'git')
      return {
        ...ok,
        stdout:
          ['package.json', ...previewInputs, ...forbidden].join('\0') + '\0',
      };
    if (args[0] === 'build') {
      const context = args.at(-1);
      for (const file of previewInputs)
        assert(existsSync(join(context, file)), file);
      for (const file of forbidden)
        assert(!existsSync(join(context, file)), file);
    }
    return f.run(command, args, options);
  };
  assert.equal(verify(f.root, join(f.root, 'evidence'), run), 0);
  const source = JSON.parse(readFileSync(join(f.root, 'evidence/source.json')));
  assert.deepEqual(source.files, ['package.json', ...previewInputs].sort());
});

test('visual reference capture uses the shared command with nonzero coverage floors', () => {
  const script = JSON.parse(
    readFileSync(new URL('../../package.json', import.meta.url)),
  ).scripts['visual-reference:test'];
  for (const metric of ['lines', 'branches', 'functions'])
    assert(script.includes(`--test-coverage-${metric}=75`));
  assert(
    script.includes(
      '--test-coverage-include=tools/visual/capture-reference.mjs',
    ),
  );
  assert.deepEqual(commands.at(-1), [
    'visual-reference-coverage',
    ['visual-reference:test'],
  ]);
});
