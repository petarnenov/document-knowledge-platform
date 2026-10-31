import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { chmodSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseAllDocuments } from 'yaml';

export const scanner = 'ghcr.io/google/osv-scanner:v2.7.0@sha256:4f3b7e2c64b9d38a5febc5a7e4a3dfef1d1362f726dea7a2ef4762b46469fcd0';
const excluded = new Set(['local-data', 'node_modules', 'build', 'dist', 'target']);
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fail = (message) => { throw new Error(message); };
export function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  return value;
}
const json = (value) => JSON.stringify(canonical(value), null, 2) + '\n';

// Copy only public dependency locks. Never bind the source checkout or private runtime tree.
export function inputs(root) {
  const found = [];
  function walk(relative = '') {
    for (const entry of readdirSync(join(root, relative), { withFileTypes: true })) {
      if (entry.name.startsWith('.') || excluded.has(entry.name)) continue;
      const path = relative ? `${relative}/${entry.name}` : entry.name;
      if (entry.isSymbolicLink()) fail(`Symlink in public input tree: ${path}`);
      if (entry.isDirectory()) walk(path);
      else if (entry.name === 'gradle.lockfile' || path === 'settings-gradle.lockfile' || path === 'pnpm-lock.yaml' || path === 'tools/runtime/requirements.txt') {
        const bytes = readFileSync(join(root, path));
        found.push({ path, sha256: sha(bytes), bytes });
      }
    }
  }
  if (!lstatSync(root).isDirectory() || lstatSync(root).isSymbolicLink()) fail('Source must be a real directory');
  walk();
  for (const required of ['pnpm-lock.yaml', 'settings-gradle.lockfile', 'tools/runtime/requirements.txt']) if (!found.some(input => input.path === required)) fail(`Missing input: ${required}`);
  if (!found.some(input => basename(input.path) === 'gradle.lockfile')) fail('No project Gradle locks');
  return found.sort((a, b) => a.path.localeCompare(b.path, 'en'));
}

export function expectedPackages(locks) {
  const packages = new Set();
  for (const { path, bytes } of locks) {
    const text = bytes.toString('utf8');
    if (path.endsWith('gradle.lockfile')) {
      for (const line of text.split('\n')) {
        if (!line || line.startsWith('#') || line.startsWith('empty=')) continue;
        const match = /^([^:=]+):([^:=]+):([^=]+)=.+$/.exec(line);
        if (!match) fail(`Unrecognized Gradle lock entry: ${path}`);
        packages.add(`maven:${match[1]}:${match[2]}@${match[3]}`);
      }
    } else if (path === 'pnpm-lock.yaml') {
      for (const document of parseAllDocuments(text)) {
      if (document.errors.length) fail('Invalid pnpm YAML');
      const lock = document.toJS();
      if (!lock || String(lock.lockfileVersion) !== '9.0' || !lock.packages || typeof lock.packages !== 'object') fail('Unsupported pnpm lock format');
      for (const entry of Object.keys(lock.packages)) {
        const split = entry.lastIndexOf('@');
        if (split <= 0 || !entry.slice(split + 1)) fail('Unpinned npm dependency');
        packages.add(`npm:${entry.slice(0, split)}@${entry.slice(split + 1)}`);
      }
      }
    } else {
      for (const line of text.split('\n').map(line => line.trim()).filter(line => line && !line.startsWith('#'))) {
        const match = /^([a-zA-Z0-9_.-]+)==([a-zA-Z0-9_.+-]+)$/.exec(line);
        if (!match) fail('Python dependencies must be exact pins');
        packages.add(`pypi:${match[1].toLowerCase().replace(/[-_.]+/g, '-')}@${match[2]}`);
      }
    }
  }
  return packages;
}

export function normalize(raw, locks, generatorHash) {
  if (raw.bomFormat !== 'CycloneDX' || raw.specVersion !== '1.6' || raw.version !== 1 || !Array.isArray(raw.components) || !raw.components.length) fail('Scanner did not return a populated CycloneDX 1.6 BOM');
  const expected = expectedPackages(locks), actual = new Set();
  for (const component of raw.components) {
    if (component.type !== 'library' || typeof component.name !== 'string' || typeof component.version !== 'string' || component['bom-ref'] !== component.purl) fail('Invalid scanner component');
    const match = /^pkg:(maven|npm|pypi)\//.exec(component.purl);
    if (!match) fail('Unexpected component ecosystem');
    const key = `${match[1]}:${component.name}@${component.version}`;
    if (actual.has(key)) fail('Duplicate scanner component');
    actual.add(key);
  }
  if (actual.size !== expected.size || [...expected].some(key => !actual.has(key))) fail('SBOM package set does not match every effective lock');
  if (!Array.isArray(raw.vulnerabilities) || raw.vulnerabilities.length) fail('Vulnerability findings require remediation before publication');
  const properties = [
    { name: 'dkp:scope', value: 'source-lock-inventory-including-build-test-and-optional-dependencies;not-container-os-inventory' },
    { name: 'dkp:generator:sha256', value: generatorHash },
    { name: 'dkp:scanner:image', value: scanner },
    ...locks.map(input => ({ name: `dkp:input:sha256:${input.path}`, value: input.sha256 })),
  ];
  return {
    ...raw,
    components: [...raw.components].sort((a, b) => a.purl.localeCompare(b.purl, 'en')),
    metadata: { tools: { components: [{ type: 'application', name: 'osv-scanner', version: '2.7.0' }, { type: 'application', name: 'dkp-bp03-sbom', version: '1' }] }, properties },
  };
}

export function generate(root, output, run = spawnSync) {
  const locks = inputs(resolve(root));
  const stage = mkdtempSync(join(tmpdir(), 'dkp-public-sbom-'));
  try {
    for (const input of locks) {
      // OSV discovers settings locks only under the conventional Gradle basename.
      const path = join(stage, input.path === 'settings-gradle.lockfile' ? 'settings/gradle.lockfile' : input.path);
      mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, input.bytes, { mode: 0o644 });
      // Native Linux preserves host UID/modes. The capability-free scanner cannot
      // traverse mkdtemp's 0700 directory owned by the host runner. These files
      // contain public locks only; normalize below-stage modes even with umask077.
      chmodSync(path, 0o644);
      for (let directory = dirname(path); directory !== dirname(stage); directory = dirname(directory)) chmodSync(directory, 0o755);
    }
    const result = run('docker', ['run', '--rm', '--memory=2g', '--cpus=2', '--cap-drop=ALL', '--security-opt=no-new-privileges', '--mount', `type=bind,src=${stage},dst=/public,readonly`, scanner, 'scan', 'source', '--no-resolve', '--all-packages', '--format', 'cyclonedx-1-6', '--recursive', '/public'], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, timeout: 300_000 });
    if (result.error || result.status !== 0) fail('Pinned OSV SBOM generation failed; no artifact published');
    const generatorHash = sha(readFileSync(fileURLToPath(import.meta.url)));
    const bom = normalize(JSON.parse(result.stdout), locks, generatorHash);
    // Ensure a concurrently changed checkout cannot be attested as the same input.
    if (json(inputs(resolve(root)).map(({ path, sha256 }) => ({ path, sha256 }))) !== json(locks.map(({ path, sha256 }) => ({ path, sha256 })))) fail('Dependency inputs changed during generation');
    mkdirSync(resolve(output), { recursive: true });
    writeFileSync(join(output, 'bp03-source.cdx.json'), json(bom));
    writeFileSync(join(output, 'bp03-source-provenance.json'), json({ scope: 'public-source-locks-only', scanner, generatorSha256: generatorHash, inputs: locks.map(({ path, sha256 }) => ({ path, sha256 })), sbomSha256: sha(json(bom)), components: bom.components.length }));
    return bom;
  } finally { rmSync(stage, { recursive: true, force: true }); }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.length !== 4) fail('Usage: node tools/dependencies/bp03-sbom.mjs SOURCE_DIRECTORY OUTPUT_DIRECTORY');
  generate(process.argv[2], process.argv[3]);
}
