#!/usr/bin/env node
import { readFileSync, lstatSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { loadKbBrowserState, validateKbBrowserContainers } from './kb-browser-runtime.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const image = 'dkp-identity-e2e:local';

export function kbCliArguments(args) {
  if (!args.some(value => ['--kb-runtime', '--kb-runtime-state'].includes(value))) return {};
  const values = new Map();
  let enabled = false;
  for (let index = 0; index < args.length; index++) {
    const flag = args[index];
    if (flag === '--kb-runtime') {
      if (enabled) throw new Error('Duplicate KB profile');
      enabled = true;
    } else {
      if (!['--kb-runtime-state', '--security-directory', '--runtime-env-file'].includes(flag)
          || values.has(flag) || !args[index + 1] || args[index + 1].startsWith('--')) throw new Error('Closed KB runner arguments required');
      values.set(flag, resolve(args[++index]));
    }
  }
  if (!enabled || values.size !== 3) throw new Error('Complete owned KB runner arguments required');
  return { kbRuntime: true, kbRuntimeState: values.get('--kb-runtime-state') };
}

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

export function faultInput(value) {
  const closed = (object, keys) => object !== null && typeof object === 'object' && !Array.isArray(object) && Object.keys(object).sort().join(',') === keys.sort().join(',');
  if (!closed(value, ['schemaVersion', 'phase', 'service', 'fixture']) || value.schemaVersion !== 1
      || !['seed', 'verify', 'readonly-outage', 'candidate', 'candidate-outage', 'recover', 'consume'].includes(value.phase)
      || ![null, 'pg', 'rustfs', 'scanner', 'inspector', 'dm'].includes(value.service)) throw new Error('Closed finite upload fault input required');
  if (value.phase === 'seed') {
    if (value.fixture !== null || value.service !== null) throw new Error('Fresh seed cannot adopt fixtures');
    return value;
  }
  const fixture = value.fixture;
  if (!closed(fixture, ['tenant', 'sha256', 'sizeBytes', 'consumed', 'unconsumed', 'candidate'])
      || !['tenant-a', 'tenant-b'].includes(fixture.tenant) || !/^[a-f0-9]{64}$/.test(fixture.sha256)
      || !Number.isSafeInteger(fixture.sizeBytes) || fixture.sizeBytes < 1 || fixture.sizeBytes > 100000000) throw new Error('Closed synthetic fixture required');
  for (const [name, artifact] of Object.entries({ consumed: fixture.consumed, unconsumed: fixture.unconsumed, candidate: fixture.candidate })) {
    if (artifact === null && name === 'candidate') continue;
    if (!closed(artifact, ['documentId', 'uploadId', 'receiptId', 'versionId', 'completeKey', 'appendKey'])) throw new Error('Closed synthetic artifact required');
    for (const key of ['documentId', 'uploadId', 'completeKey', 'appendKey']) if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(artifact[key])) throw new Error('Canonical synthetic UUID required');
    for (const key of ['receiptId', 'versionId']) if (artifact[key] !== null && !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(artifact[key])) throw new Error('Canonical synthetic UUID required');
    if (name !== 'candidate' && artifact.receiptId === null) throw new Error('Verified receipt required');
    if (name === 'consumed' && artifact.versionId === null) throw new Error('Consumed version required');
  }
  if (['readonly-outage', 'candidate-outage', 'recover'].includes(value.phase) && value.service === null) throw new Error('Finite fault service required');
  if (['candidate-outage', 'recover'].includes(value.phase) && fixture.candidate === null) throw new Error('Owned candidate required');
  return value;
}

export function runtimeArguments({ portalId, address, certificate, portalPort = 18443, idpPort = 18444, uploadRuntime = false, kbRuntime = false, uploadLimitsProfile, uploadDurabilityFixture = false, uploadFaultInput }) {
  if (typeof kbRuntime !== 'boolean' || kbRuntime && (uploadRuntime || uploadLimitsProfile !== undefined || uploadDurabilityFixture || uploadFaultInput !== undefined || portalPort !== 20443 || idpPort !== 20444)) throw new Error('Separate fixed KB browser profile required');
  if (uploadFaultInput !== undefined) { faultInput(uploadFaultInput); if (!uploadRuntime || uploadDurabilityFixture || uploadLimitsProfile !== undefined) throw new Error('Separate upload fault profile required'); }
  if (typeof uploadRuntime !== 'boolean') throw new Error('Explicit boolean upload profile required');
  if (typeof uploadDurabilityFixture !== 'boolean' || (uploadDurabilityFixture && (!uploadRuntime || uploadLimitsProfile !== undefined))) throw new Error('Explicit durability fixture requires its own upload runtime profile');
  if (uploadLimitsProfile !== undefined && (!['quota', 'expiry'].includes(uploadLimitsProfile) || !uploadRuntime)) throw new Error('Explicit upload limits profile requires upload runtime');
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
    ...(kbRuntime ? ['--env', 'DKP_KB_E2E_ENABLED=true'] : []),
    ...(uploadRuntime ? ['--env', 'DKP_UPLOAD_E2E_ENABLED=true'] : []),
    ...(uploadLimitsProfile ? ['--env', `DKP_UPLOAD_LIMITS_PROFILE=${uploadLimitsProfile}`] : []),
    ...(uploadDurabilityFixture ? ['--env', 'DKP_UPLOAD_DURABILITY_FIXTURE=true'] : []),
    ...(uploadFaultInput !== undefined ? ['--env', `DKP_UPLOAD_FAULT_INPUT=${JSON.stringify(uploadFaultInput)}`] : []),
    image,
    ...(kbRuntime ? ['knowledge.spec.ts'] : []),
    ...(uploadLimitsProfile ? ['upload-limits.spec.ts'] : []),
    ...(uploadDurabilityFixture ? ['upload-durability.spec.ts'] : []),
    ...(uploadFaultInput !== undefined ? ['upload-faults.spec.ts'] : []),
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

export function main({ uploadRuntime = false, kbRuntime = false, kbRuntimeState, uploadLimitsProfile, uploadDurabilityFixture = false, uploadFaultInputFile, securityDirectory, runtimeEnvironmentFile, execute = docker, log = console.log } = {}) {
  if (typeof kbRuntime !== 'boolean' || kbRuntime && (!kbRuntimeState || !securityDirectory || !runtimeEnvironmentFile || uploadRuntime || uploadDurabilityFixture || uploadLimitsProfile !== undefined || uploadFaultInputFile !== undefined) || !kbRuntime && kbRuntimeState !== undefined) throw new Error('Separate owned KB runtime required');
  if (typeof uploadDurabilityFixture !== 'boolean' || (uploadDurabilityFixture && uploadLimitsProfile !== undefined)) throw new Error('Explicit durability fixture requires a separate profile');
  if (uploadDurabilityFixture) uploadRuntime = true;
  if (uploadLimitsProfile !== undefined) {
    if (!['quota', 'expiry'].includes(uploadLimitsProfile)) throw new Error('Invalid upload limits profile');
    uploadRuntime = true;
  }
  let uploadFaultInput;
  if (uploadFaultInputFile !== undefined) {
    if (uploadDurabilityFixture || uploadLimitsProfile !== undefined) throw new Error('Separate upload fault profile required');
    requirePrivateFile(uploadFaultInputFile);
    if (lstatSync(uploadFaultInputFile).size > 16384) throw new Error('Bounded fault input required');
    uploadFaultInput = faultInput(JSON.parse(readFileSync(uploadFaultInputFile, 'utf8')));
    uploadRuntime = true;
  }
  const security = securityDirectory ?? resolve(root, uploadRuntime ? 'local-data/security/bp03-upload/identity' : 'local-data/security');
  const kbState = kbRuntime ? loadKbBrowserState(kbRuntimeState, { root, securityDirectory: security, runtimeEnvironmentFile }) : undefined;
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
  if (kbRuntime) compose.push('-p', kbState.project, '-f', 'infra/docker-compose/compose-kb.yaml');
  if (uploadRuntime) compose.push('-p', uploadProject(source), '-f', 'infra/docker-compose/compose-upload.yaml');
  const portalId = execute([...compose, 'ps', '-q', 'admin-portal'], { quiet: true });
  const providerId = execute([...compose, 'ps', '-q', 'keycloak'], { quiet: true });
  if (!/^[a-f0-9]{12,64}$/.test(portalId) || !/^[a-f0-9]{12,64}$/.test(providerId)) throw new Error('Start the identity Compose runtime first');
  const portal = JSON.parse(execute(['inspect', portalId], { quiet: true }));
  const networks = Object.keys(portal?.[0]?.NetworkSettings?.Networks ?? {});
  if (networks.length !== 1) throw new Error('Identity E2E requires one explicit Compose network');
  const provider = JSON.parse(execute(['inspect', providerId], { quiet: true }));
  if (kbRuntime) validateKbBrowserContainers(kbState, portal[0], provider[0], JSON.parse(execute(['network', 'inspect', kbState.networkId], { quiet: true }))[0]);
  const address = findAddress(provider, networks[0]);
  log('Building isolated Chromium identity verification runtime.');
  execute(['build', '-f', 'infra/docker-compose/identity-e2e.Dockerfile', '-t', image, '.']);
  log('Running strict-HTTPS real-provider browser verification.');
  execute(runtimeArguments({ portalId, address, certificate, portalPort: kbRuntime ? 20443 : uploadRuntime ? 19443 : 18443, idpPort: kbRuntime ? 20444 : uploadRuntime ? 19444 : 18444, uploadRuntime, kbRuntime, uploadLimitsProfile, uploadDurabilityFixture, uploadFaultInput }), {
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
    const limitsIndex = process.argv.indexOf('--upload-limits-profile');
    if (limitsIndex >= 0 && (!process.argv[limitsIndex + 1] || process.argv[limitsIndex + 1].startsWith('--'))) throw new Error('Upload limits profile is required');
    const faultIndex = process.argv.indexOf('--upload-fault-input-file');
    if (faultIndex >= 0 && (!process.argv[faultIndex + 1] || process.argv[faultIndex + 1].startsWith('--'))) throw new Error('Private fault input required');
    main({ ...kbCliArguments(process.argv.slice(2)), ...(faultIndex >= 0 ? { uploadFaultInputFile: resolve(process.argv[faultIndex + 1]) } : {}), uploadRuntime: process.argv.includes('--upload-runtime'), uploadDurabilityFixture: process.argv.includes('--upload-durability-fixture'), ...(limitsIndex >= 0 ? { uploadLimitsProfile: process.argv[limitsIndex + 1] } : {}), ...(index >= 0 ? { securityDirectory: resolve(process.argv[index + 1]) } : {}), ...(environmentIndex >= 0 ? { runtimeEnvironmentFile: resolve(process.argv[environmentIndex + 1]) } : {}) });
  } catch {
    // Never dump captured Docker inspect/env data or credential-bearing errors.
    console.error('Real identity E2E failed; inspect sanitized output and verify the running Compose runtime and owner-only local security material.');
    process.exitCode = 1;
  }
}
