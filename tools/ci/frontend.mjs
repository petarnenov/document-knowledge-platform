import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import {
  chmodSync,
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Only public build inputs. Never send the repository root as Docker context.
export function allowed(path) {
  if (
    path
      .split('/')
      .some((part) =>
        [
          '..',
          'local-data',
          'node_modules',
          'coverage',
          'playwright-report',
          'test-results',
          'dist',
          'secrets',
          '.git',
        ].includes(part),
      )
  )
    return false;
  if (
    /(^|\/)(\.env(?:\..*)?|credentials\.json)$|\.(pem|key|p12|pfx)$/i.test(path)
  )
    return false;
  return (
    [
      'package.json',
      'pnpm-lock.yaml',
      'pnpm-workspace.yaml',
      '.npmrc',
      '.prettierrc.json',
      'docs/prototypes/DKP_Interactive_Design_v0.5.html',
      'tools/visual/capture-reference.mjs',
      'tools/visual/capture-reference.test.mjs',
      'tools/runtime/uiux-preview.mjs',
      'tools/runtime/uiux-preview.test.mjs',
      'tools/runtime/identity-e2e.mjs',
      'infra/docker-compose/compose-uiux-preview.yaml',
      'infra/docker-compose/nginx-uiux-preview.conf',
      'infra/docker-compose/nginx.conf',
    ].includes(path) ||
    [
      'apps/admin-portal/',
      'generated-clients/typescript/',
      'patches/',
      'tools/ci/frontend',
    ].some((prefix) => path.startsWith(prefix))
  );
}

export function execute(command, args, options = {}, run = spawnSync) {
  const result = run(command, args, { stdio: 'inherit', ...options });
  if (result.status !== 0)
    throw new Error(
      `${command} failed (${result.status ?? result.signal ?? 'spawn error'})`,
    );
  return result;
}

export function verify(root, output, run = spawnSync) {
  if (existsSync(output)) throw new Error('Evidence directory must be new');
  mkdirSync(output, { recursive: true, mode: 0o700 });
  chmodSync(output, 0o700);
  const context = mkdtempSync(join(tmpdir(), 'dkp-frontend-context-'));
  const name = `dkp-frontend-${randomUUID()}`;
  const image = `${name}:local`;
  let created = false;
  let imageId = null;
  let code = 1;
  const commands = [];
  const invoke = (command, args, options = {}) => {
    const result = run(command, args, {
      ...options,
      stdio: 'pipe',
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
    });
    commands.push({
      command: [command, ...args],
      status: result.status ?? 1,
      signal: result.signal ?? null,
    });
    writeFileSync(
      join(output, `runner-${commands.length}.log`),
      `${result.stdout ?? ''}${result.stderr ?? ''}`,
      { mode: 0o600 },
    );
    console.log(
      `${command} ${args[0]}: ${result.status === 0 ? 'PASS' : 'FAIL'}`,
    );
    return result;
  };
  try {
    const listing = execute(
      'git',
      ['ls-files', '-z', '--cached', '--others', '--exclude-standard'],
      { cwd: root, encoding: 'utf8', stdio: 'pipe' },
      invoke,
    );
    const files = [
      ...new Set(listing.stdout.split('\0').filter(Boolean).filter(allowed)),
    ].sort();
    const hash = createHash('sha256');
    for (const path of files) {
      const source = join(root, path);
      if (!lstatSync(source).isFile())
        throw new Error('Build input must be a regular file');
      const bytes = readFileSync(source);
      hash.update(`${path}\0${bytes.length}\0`);
      hash.update(bytes);
      const target = join(context, path);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, bytes);
    }
    const fingerprint = { sha256: hash.digest('hex'), files };
    writeFileSync(
      join(context, 'frontend-source.json'),
      JSON.stringify(fingerprint, null, 2),
    );
    writeFileSync(
      join(output, 'source.json'),
      JSON.stringify(fingerprint, null, 2),
      { mode: 0o600 },
    );
    execute(
      'docker',
      [
        'build',
        '--platform=linux/amd64',
        '-f',
        join(context, 'tools/ci/frontend.Dockerfile'),
        '-t',
        image,
        context,
      ],
      {},
      invoke,
    );
    imageId = execute(
      'docker',
      ['image', 'inspect', '--format={{.Id}}', image],
      {},
      invoke,
    ).stdout.trim();
    if (!/^sha256:[a-f0-9]{64}$/.test(imageId))
      throw new Error('Invalid built image ID');
    execute(
      'docker',
      [
        'create',
        '--platform=linux/amd64',
        '--name',
        name,
        '--init',
        '--cap-drop=ALL',
        '--security-opt=no-new-privileges',
        '--shm-size=1g',
        '--memory=4g',
        '--cpus=2',
        '--pids-limit=768',
        imageId,
      ],
      {},
      invoke,
    );
    created = true;
    const result = invoke('docker', ['start', '--attach', name]);
    // Copy out after completion; no source, caches, secrets or Docker socket are mounted.
    execute('docker', ['cp', `${name}:/evidence/.`, output], {}, invoke);
    code = result.status ?? 1;
  } finally {
    if (created && invoke('docker', ['rm', '--force', name]).status !== 0)
      code = 1;
    const removed = invoke('docker', ['image', 'rm', image]);
    if (removed.status !== 0) code = 1;
    rmSync(context, { recursive: true, force: true });
    writeFileSync(
      join(output, 'runner.json'),
      JSON.stringify(
        {
          image,
          imageId,
          container: name,
          status: code,
          platform: 'linux/amd64',
          commands,
        },
        null,
        2,
      ),
      { mode: 0o600 },
    );
  }
  return code;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const output = resolve(
    process.argv[2] ?? join(root, 'local-data/frontend', randomUUID()),
  );
  try {
    process.exitCode = verify(root, output);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
  console.log(`Private frontend evidence: ${output}`);
}
