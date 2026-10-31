#!/usr/bin/env node
import { readFileSync, lstatSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const image = 'dkp-identity-e2e:local';

export function readCredentials(source) {
  const allowed = new Set(['DKP_TEST_USERNAME', 'DKP_TEST_PASSWORD']);
  const values = {};
  for (const line of source.split('\n')) {
    const separator = line.indexOf('=');
    const key = line.slice(0, separator);
    if (separator < 1 || !allowed.has(key)) continue;
    if (Object.hasOwn(values, key)) throw new Error('Duplicate development identity setting');
    const value = line.slice(separator + 1);
    if (!/^[\x21-\x7e]{1,256}$/.test(value)) throw new Error('Invalid development identity setting');
    values[key] = value;
  }
  if (!values.DKP_TEST_USERNAME || !values.DKP_TEST_PASSWORD) throw new Error('Missing development identity settings');
  return { username: values.DKP_TEST_USERNAME, password: values.DKP_TEST_PASSWORD };
}

export function uploadProject(source) {
  const values = source.split('\n').filter((line) => line.startsWith('DKP_UPLOAD_COMPOSE_PROJECT='));
  if (values.length === 0) return 'dkp-bp03'; // Existing fixed isolated development stack.
  if (values.length !== 1) throw new Error('Duplicate isolated upload project');
  const value = values[0].slice('DKP_UPLOAD_COMPOSE_PROJECT='.length);
  if (!/^dkp-bp03-[a-f0-9]{12}$/.test(value)) throw new Error('Invalid isolated upload project');
  return value;
}

export function requirePrivateFile(file) {
  const information = lstatSync(file);
  if (!information.isFile() || (information.mode & 0o077) !== 0) throw new Error('Development identity file must be owner-only');
}

export function findAddress(inspection, network) {
  const address = inspection?.[0]?.NetworkSettings?.Networks?.[network]?.IPAddress;
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(address ?? '') || address.split('.').some((value) => Number(value) > 255)) {
    throw new Error('Identity provider needs a matching IPv4 Compose network');
  }
  return address;
}

export function runtimeArguments({ portalId, address, certificate, portalPort = 18443, idpPort = 18444, uploadRuntime = false }) {
  if (typeof uploadRuntime !== 'boolean') throw new Error('Explicit boolean upload profile required');
  for (const port of [portalPort, idpPort]) if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid isolated HTTPS port');
  if (portalPort === idpPort) throw new Error('Distinct isolated HTTPS ports required');
  if (!/^[a-f0-9]{12,64}$/.test(portalId)) throw new Error('Invalid portal container identifier');
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(address) || address.split('.').some((value) => Number(value) > 255)) throw new Error('Invalid identity provider address');
  return [
    'run', '--rm', '--init', '--network', `container:${portalId}`,
    '--security-opt', 'no-new-privileges:true',
    '--cap-drop', 'ALL', '--cap-add', 'CHOWN', '--cap-add', 'SETUID', '--cap-add', 'SETGID', '--cap-add', 'DAC_OVERRIDE',
    '--shm-size', '1g', '--tmpfs', '/tmp:rw,nosuid,nodev,size=1g',
    '--mount', `type=bind,src=${certificate},dst=/run/dkp/ca.crt,readonly`,
    '--env', 'DKP_IDENTITY_USERNAME', '--env', 'DKP_IDENTITY_PASSWORD',
    '--env', `DKP_IDENTITY_BASE_URL=https://localhost:${portalPort}`,
    '--env', `DKP_IDENTITY_IDP_BASE_URL=https://idp.localhost:${idpPort}`,
    '--env', `DKP_IDENTITY_IDP_ADDRESS=${address}`,
    ...(uploadRuntime ? ['--env', 'DKP_UPLOAD_E2E_ENABLED=true'] : []),
    image,
  ];
}

export function redact(output, password) {
  const safe = password ? output.split(password).join('[REDACTED]') : output;
  return safe
    .replace(/([?&](?:code|access_token|id_token|refresh_token|session_state|state|nonce)=)[^&\s"'<>]+/gi, '$1[REDACTED]')
    .replace(/Bearer\s+[^\s"'<>]+/gi, 'Bearer [REDACTED]')
    .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[REDACTED-JWT]');
}

function docker(args, { environment = process.env, password, quiet = false } = {}) {
  const result = spawnSync('docker', args, { cwd: root, env: environment, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
  if (!quiet) {
    if (result.stdout) process.stdout.write(redact(result.stdout, password));
    if (result.stderr) process.stderr.write(redact(result.stderr, password));
  }
  if (result.error || result.status !== 0) throw new Error('Docker identity verification failed');
  return result.stdout.trim();
}

export function main({ uploadRuntime = false, securityDirectory = resolve(root, uploadRuntime ? 'local-data/security/bp03-upload/identity' : 'local-data/security'), runtimeEnvironmentFile, execute = docker, log = console.log } = {}) {
  const security = securityDirectory;
  const credentialsFile = resolve(security, 'runtime.env');
  const certificate = resolve(security, 'ca.crt');
  requirePrivateFile(credentialsFile);
  requirePrivateFile(certificate);
  const source = readFileSync(credentialsFile, 'utf8');
  const credentials = readCredentials(source);
  const environmentFile = runtimeEnvironmentFile ?? credentialsFile;
  requirePrivateFile(environmentFile);
  if (uploadRuntime && uploadProject(readFileSync(environmentFile, 'utf8')) !== uploadProject(source)) throw new Error('Isolated runtime project does not match identity');
  const compose = ['compose', '--env-file', environmentFile, '-f', 'infra/docker-compose/compose.yaml'];
  if (uploadRuntime) compose.push('-p', uploadProject(source), '-f', 'infra/docker-compose/compose-upload.yaml');
  const portalId = execute([...compose, 'ps', '-q', 'admin-portal'], { quiet: true });
  const providerId = execute([...compose, 'ps', '-q', 'keycloak'], { quiet: true });
  if (!/^[a-f0-9]{12,64}$/.test(portalId) || !/^[a-f0-9]{12,64}$/.test(providerId)) throw new Error('Start the identity Compose runtime first');
  const portal = JSON.parse(execute(['inspect', portalId], { quiet: true }));
  const networks = Object.keys(portal?.[0]?.NetworkSettings?.Networks ?? {});
  if (networks.length !== 1) throw new Error('Identity E2E requires one explicit Compose network');
  const address = findAddress(JSON.parse(execute(['inspect', providerId], { quiet: true })), networks[0]);
  log('Building isolated Chromium identity verification runtime.');
  execute(['build', '-f', 'infra/docker-compose/identity-e2e.Dockerfile', '-t', image, '.']);
  log('Running strict-HTTPS real-provider browser verification.');
  execute(runtimeArguments({ portalId, address, certificate, portalPort: uploadRuntime ? 19443 : 18443, idpPort: uploadRuntime ? 19444 : 18444, uploadRuntime }), {
    environment: { ...process.env, DKP_IDENTITY_USERNAME: credentials.username, DKP_IDENTITY_PASSWORD: credentials.password },
    password: credentials.password,
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const index = process.argv.indexOf('--security-directory');
    if (index >= 0 && (!process.argv[index + 1] || process.argv[index + 1].startsWith('--'))) throw new Error('Security directory is required');
    const environmentIndex = process.argv.indexOf('--runtime-env-file');
    if (environmentIndex >= 0 && (!process.argv[environmentIndex + 1] || process.argv[environmentIndex + 1].startsWith('--'))) throw new Error('Runtime environment file is required');
    main({ uploadRuntime: process.argv.includes('--upload-runtime'), ...(index >= 0 ? { securityDirectory: resolve(process.argv[index + 1]) } : {}), ...(environmentIndex >= 0 ? { runtimeEnvironmentFile: resolve(process.argv[environmentIndex + 1]) } : {}) });
  } catch {
    // Never dump captured Docker inspect/env data or credential-bearing errors.
    console.error('Real identity E2E failed; inspect sanitized output and verify the running Compose runtime and owner-only local security material.');
    process.exitCode = 1;
  }
}
