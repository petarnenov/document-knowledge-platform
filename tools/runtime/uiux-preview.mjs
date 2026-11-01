#!/usr/bin/env node
/** Issue440: an explicitly owned, metadata-only manual preview. No default stack. */
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  findAddress,
  readCredentials,
  requirePrivateFile,
  runtimeArguments,
} from './identity-e2e.mjs';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const SERVICES = [
  'document-management',
  'knowledge-base',
  'knowledge-search',
  'browser-bff',
  'admin-portal',
  'keycloak',
  'dm-postgres',
];
const MATERIAL = [
  'ca.crt',
  'ca.key',
  'portal.crt',
  'portal.key',
  'idp.crt',
  'idp.key',
  'truststore.p12',
  'java-security.options',
  'dkp-realm.json',
  'runtime.env',
  'authority/grants.json',
  'authority/cursor.key',
];
const SOURCE_ROOTS = [
  'package.json',
  'pnpm-lock.yaml',
  'pnpm-workspace.yaml',
  '.npmrc',
  '.dockerignore',
  'patches',
  'build.gradle',
  'settings.gradle',
  'settings-gradle.lockfile',
  'gradle.properties',
  'gradlew',
  'gradle',
  'modules',
  'services',
  'apps/admin-portal',
  'contracts',
  'generated-clients/typescript',
  'generated-clients/java',
  'infra/docker-compose',
  'tools/runtime/uiux-preview.mjs',
  'tools/runtime/identity-e2e.mjs',
  'tools/runtime/synthetic-documents.mjs',
];
const EXCLUDED = new Set([
  'node_modules',
  'build',
  'dist',
  'coverage',
  'playwright-report',
  'test-results',
  '__pycache__',
  '.gradle',
]);
const RECEIPT = 'preview-receipt.json';
const fail = () => {
  throw new Error('Owned UI/UX preview validation failed.');
};
const hex = (value) =>
  typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);

export function execute(program, args, options = {}) {
  const result = spawnSync(program, args, {
    cwd: options.cwd ?? ROOT,
    env: options.env ?? process.env,
    encoding: 'utf8',
    timeout: options.timeout ?? 120000,
    maxBuffer: 16 * 1024 * 1024,
  });
  // Neither raw subprocess output nor arguments are safe diagnostics (OAuth/DB credentials).
  if (result.error || result.status !== 0)
    throw new Error(
      'Owned preview command failed; no credentials or raw output were logged.',
    );
  return result.stdout;
}

export function ownedLocation(root, project, directory) {
  if (
    !/^dkp-uiux-440-[a-f0-9]{12}$/.test(project ?? '') ||
    typeof directory !== 'string'
  )
    fail();
  const path = resolve(root, directory);
  if (path !== join(resolve(root), 'local-data/security/uiux-preview', project))
    fail();
  for (let item = path; item !== dirname(item); item = dirname(item)) {
    if (
      existsSync(item) &&
      (lstatSync(item).isSymbolicLink() || !lstatSync(item).isDirectory())
    )
      fail();
  }
  return path;
}

export function sourceFingerprint(root = ROOT) {
  const hash = createHash('sha256');
  let count = 0;
  function visit(path) {
    const info = lstatSync(path);
    if (info.isSymbolicLink()) fail();
    if (info.isDirectory()) {
      for (const name of readdirSync(path).sort())
        if (!EXCLUDED.has(name)) visit(join(path, name));
    } else if (info.isFile()) {
      hash
        .update(relative(root, path))
        .update('\0')
        .update(readFileSync(path))
        .update('\0');
      count++;
    } else fail();
  }
  for (const name of SOURCE_ROOTS)
    if (existsSync(join(root, name))) visit(join(root, name));
  if (count === 0) fail();
  return hash.digest('hex');
}

export function materialFingerprint(directory) {
  const info = lstatSync(directory);
  if (
    !info.isDirectory() ||
    info.isSymbolicLink() ||
    info.mode & 0o077 ||
    info.uid !== process.getuid()
  )
    fail();
  const hash = createHash('sha256');
  for (const name of MATERIAL) {
    const path = join(directory, name);
    requirePrivateFile(path);
    if (
      lstatSync(path).isSymbolicLink() ||
      realpathSync(path) !== path ||
      lstatSync(path).uid !== process.getuid()
    )
      fail();
    hash.update(name).update('\0').update(readFileSync(path)).update('\0');
  }
  const values = Object.fromEntries(
    readFileSync(join(directory, 'runtime.env'), 'utf8')
      .trim()
      .split('\n')
      .map((line) => line.split('=')),
  );
  if (
    values.DKP_PORTAL_PORT !== '24443' ||
    values.DKP_IDP_PORT !== '24444' ||
    !/^[1-9][0-9]*$/.test(values.DKP_LOCAL_UID ?? '') ||
    values.DKP_LOCAL_UID !== String(process.getuid())
  )
    fail();
  return hash.digest('hex');
}

function inspect(run, kind, id, options) {
  const values = JSON.parse(run('docker', [kind, 'inspect', id], options));
  if (!Array.isArray(values) || values.length !== 1) fail();
  return values[0];
}
function labels(item, project) {
  const actual = item.Config?.Labels ?? item.Labels;
  if (
    actual?.['com.docker.compose.project'] !== project ||
    actual?.['dkp.preview.issue'] !== '440' ||
    actual?.['dkp.preview.project'] !== project
  )
    fail();
}
function resourceSnapshot(run, compose, options, project, directory) {
  const containers = {};
  for (const service of SERVICES) {
    const id = run(
      'docker',
      [...compose, 'ps', '-a', '-q', service],
      options,
    ).trim();
    if (!hex(id)) fail();
    const item = inspect(run, 'container', id, options);
    labels(item, project);
    if (
      item.Id !== id ||
      item.Config?.Labels?.['com.docker.compose.service'] !== service ||
      !/^sha256:[a-f0-9]{64}$/.test(item.Image ?? '') ||
      item.State?.Status !== 'running' ||
      item.State?.Health?.Status !== 'healthy'
    )
      fail();
    const expectedNetworks =
      service === 'document-management'
        ? ['default', 'dm-data']
        : service === 'dm-postgres'
          ? ['dm-data']
          : ['default'];
    if (
      Object.keys(item.NetworkSettings?.Networks ?? {})
        .sort()
        .join(',') !==
      expectedNetworks
        .map((name) => `${project}_${name}`)
        .sort()
        .join(',')
    )
      fail();
    const trust = {
      '/run/dkp/truststore.p12': join(directory, 'truststore.p12'),
      '/run/dkp/java-security.options': join(
        directory,
        'java-security.options',
      ),
    };
    const expectedBinds =
      service === 'dm-postgres'
        ? {}
        : service === 'admin-portal'
          ? {
              '/run/dkp/portal.crt': join(directory, 'portal.crt'),
              '/run/dkp/portal.key': join(directory, 'portal.key'),
              '/etc/nginx/conf.d/default.conf': join(
                options.cwd,
                'infra/docker-compose/nginx-uiux-preview.conf',
              ),
            }
          : service === 'keycloak'
            ? {
                '/run/dkp/idp.crt': join(directory, 'idp.crt'),
                '/run/dkp/idp.key': join(directory, 'idp.key'),
                '/opt/keycloak/data/import/dkp-realm.json': join(
                  directory,
                  'dkp-realm.json',
                ),
              }
            : service === 'document-management'
              ? { ...trust, '/run/dkp/authority': join(directory, 'authority') }
              : trust;
    const binds = (item.Mounts ?? []).filter((mount) => mount.Type === 'bind');
    if (
      binds.length !== Object.keys(expectedBinds).length ||
      binds.some(
        (mount) =>
          expectedBinds[mount.Destination] !== mount.Source ||
          mount.RW !== false,
      )
    )
      fail();
    const volumes = (item.Mounts ?? []).filter(
      (mount) => mount.Type === 'volume',
    );
    if (service === 'dm-postgres') {
      if (
        volumes.length !== 1 ||
        volumes[0].Name !== `${project}_dm-postgres-data` ||
        volumes[0].Destination !== '/var/lib/postgresql'
      )
        fail();
    } else if (volumes.length) fail();
    containers[service] = { id, image: item.Image };
  }
  const networks = {};
  for (const name of ['default', 'dm-data']) {
    const item = inspect(run, 'network', `${project}_${name}`, options);
    labels(item, project);
    if (
      !hex(item.Id) ||
      item.Name !== `${project}_${name}` ||
      (name === 'dm-data' && item.Internal !== true)
    )
      fail();
    networks[name] = item.Id;
  }
  const volume = inspect(run, 'volume', `${project}_dm-postgres-data`, options);
  labels(volume, project);
  if (volume.Name !== `${project}_dm-postgres-data`) fail();
  return { containers, networks, volume: volume.Name };
}

export function preview({
  action,
  project,
  directory,
  browser = false,
  root = ROOT,
  run = execute,
}) {
  if (
    !['start', 'verify'].includes(action) ||
    typeof browser !== 'boolean' ||
    (browser && action !== 'verify')
  )
    fail();
  root = resolve(root);
  directory = ownedLocation(root, project, directory);
  // Do not inherit remote Docker context/endpoint selectors or Compose file overrides.
  if (process.env.DOCKER_HOST || process.env.DOCKER_CONTEXT) fail();
  const env = {
    ...process.env,
    DKP_UIUX_PREVIEW_PROJECT: project,
    DKP_UIUX_PREVIEW_SECURITY: directory,
  };
  delete env.COMPOSE_FILE;
  delete env.COMPOSE_PROJECT_NAME;
  delete env.COMPOSE_ENV_FILES;
  delete env.COMPOSE_PROFILES;
  const options = { cwd: root, env };
  const endpoint = JSON.parse(
    run(
      'docker',
      ['context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}'],
      options,
    ),
  );
  if (typeof endpoint !== 'string' || !endpoint.startsWith('unix://')) fail();
  const compose = [
    'compose',
    '-p',
    project,
    '--env-file',
    join(directory, 'runtime.env'),
    '-f',
    join(root, 'infra/docker-compose/compose.yaml'),
    '-f',
    join(root, 'infra/docker-compose/compose-uiux-preview.yaml'),
  ];
  const fingerprint = sourceFingerprint(root);
  const receiptPath = join(directory, RECEIPT);
  if (action === 'start') {
    if (existsSync(directory)) fail();
    // Check all resource kinds, including stopped containers and retained volumes: never adopt.
    for (const kind of ['container', 'network', 'volume']) {
      const list = [kind, 'ls', ...(kind === 'container' ? ['-a'] : []), '-q'];
      for (const filter of [
        `label=com.docker.compose.project=${project}`,
        `name=^${kind === 'container' ? '/' : ''}${project}${kind === 'container' ? '-' : '_'}`,
      ]) {
        if (run('docker', [...list, '--filter', filter], options).trim())
          fail();
      }
    }
    mkdirSync(dirname(directory), { recursive: true, mode: 0o700 });
    run(
      'python3',
      [
        join(root, 'infra/docker-compose/bootstrap-security.py'),
        '--directory',
        directory,
        '--portal-port',
        '24443',
        '--idp-port',
        '24444',
      ],
      options,
    );
    const material = materialFingerprint(directory);
    Object.assign(
      env,
      Object.fromEntries(
        readFileSync(join(directory, 'runtime.env'), 'utf8')
          .trim()
          .split('\n')
          .map((line) => line.split('=')),
      ),
    );
    // Persist ownership before any build/up. Failed starts retain evidence and private state.
    const receipt = {
      schemaVersion: 1,
      project,
      directory,
      sourceFingerprint: fingerprint,
      materialFingerprint: material,
      phase: 'prepared',
      resources: null,
    };
    writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + '\n', {
      mode: 0o600,
      flag: 'wx',
    });
    run('docker', [...compose, 'config', '--quiet'], options);
    run('docker', [...compose, 'build'], { ...options, timeout: 1800000 });
    run(
      'docker',
      [...compose, 'up', '-d', '--no-build', '--wait', '--wait-timeout', '240'],
      { ...options, timeout: 300000 },
    );
    if (sourceFingerprint(root) !== fingerprint) fail();
    receipt.resources = resourceSnapshot(
      run,
      compose,
      options,
      project,
      directory,
    );
    receipt.phase = 'ready';
    writeFileSync(receiptPath, JSON.stringify(receipt, null, 2) + '\n', {
      mode: 0o600,
    });
    return {
      phase: 'ready',
      url: 'https://localhost:24443',
      sourceFingerprint: fingerprint,
      browserVerified: false,
    };
  }
  requirePrivateFile(receiptPath);
  if (realpathSync(receiptPath) !== receiptPath) fail();
  const receipt = JSON.parse(readFileSync(receiptPath, 'utf8'));
  if (
    receipt.schemaVersion !== 1 ||
    receipt.project !== project ||
    receipt.directory !== directory ||
    receipt.phase !== 'ready' ||
    receipt.sourceFingerprint !== fingerprint ||
    receipt.materialFingerprint !== materialFingerprint(directory)
  )
    fail();
  Object.assign(
    env,
    Object.fromEntries(
      readFileSync(join(directory, 'runtime.env'), 'utf8')
        .trim()
        .split('\n')
        .map((line) => line.split('=')),
    ),
  );
  const resources = resourceSnapshot(run, compose, options, project, directory);
  if (JSON.stringify(resources) !== JSON.stringify(receipt.resources)) fail();
  if (browser) {
    const portal = inspect(
      run,
      'container',
      resources.containers['admin-portal'].id,
      options,
    );
    const provider = inspect(
      run,
      'container',
      resources.containers.keycloak.id,
      options,
    );
    const network = `${project}_default`;
    if (
      Object.keys(portal.NetworkSettings?.Networks ?? {}).join(',') !== network
    )
      fail();
    const address = findAddress([provider], network);
    const credentials = readCredentials(
      readFileSync(join(directory, 'runtime.env'), 'utf8'),
    );
    const image = `${project}-identity-e2e:local`;
    run(
      'docker',
      [
        'build',
        '-f',
        join(root, 'infra/docker-compose/identity-e2e.Dockerfile'),
        '-t',
        image,
        root,
      ],
      { ...options, timeout: 1800000 },
    );
    const args = runtimeArguments({
      portalId: resources.containers['admin-portal'].id,
      address,
      certificate: join(directory, 'ca.crt'),
      portalPort: 24443,
      idpPort: 24444,
      uploadRuntime: false,
    });
    args[args.indexOf('dkp-identity-e2e:local')] = image;
    run('docker', args, {
      ...options,
      timeout: 900000,
      env: {
        ...env,
        DKP_IDENTITY_USERNAME: credentials.username,
        DKP_IDENTITY_PASSWORD: credentials.password,
      },
    });
    // Recheck resource/source identity after real browser activity; no teardown or fault injection.
    if (
      sourceFingerprint(root) !== fingerprint ||
      JSON.stringify(
        resourceSnapshot(run, compose, options, project, directory),
      ) !== JSON.stringify(resources)
    )
      fail();
  }
  return {
    phase: 'ready',
    url: 'https://localhost:24443',
    sourceFingerprint: fingerprint,
    browserVerified: browser,
  };
}

export function parseArguments(args) {
  const [action, ...rest] = args;
  const values = {};
  for (let index = 0; index < rest.length; index++) {
    const key = rest[index];
    if (
      !['--project', '--directory', '--browser'].includes(key) ||
      Object.hasOwn(values, key)
    )
      fail();
    if (key === '--browser') values[key] = true;
    else {
      if (!rest[index + 1] || rest[index + 1].startsWith('--')) fail();
      values[key] = rest[++index];
    }
  }
  if (!values['--project'] || !values['--directory']) fail();
  return {
    action,
    project: values['--project'],
    directory: values['--directory'],
    browser: values['--browser'] ?? false,
  };
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  try {
    console.log(JSON.stringify(preview(parseArguments(process.argv.slice(2)))));
  } catch {
    console.error(
      'Owned UI/UX preview failed. Preserve its receipt and private material; inspect only sanitized task evidence.',
    );
    process.exitCode = 1;
  }
}
