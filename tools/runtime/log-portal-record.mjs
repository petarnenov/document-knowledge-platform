/** Actual compiler/direct-package records for the trusted original Vite build adapter. */
import { createRequire } from 'node:module';
import { readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { dirname, resolve, join, isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';

export function collectPackages(root) {
const require = createRequire(resolve(root, 'package.json'));
const declared = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'));
const names = [...Object.keys(declared.dependencies), '@vitejs/plugin-react', 'typescript', 'vite'].sort();
const records = names.map(name => {
  let directory = dirname(realpathSync(require.resolve(name)));
  for (let depth = 0; depth < 32; depth++) {
    try {
      const record = JSON.parse(readFileSync(join(directory, 'package.json'), 'utf8'));
      if (record.name === name) {
        const expected = declared.dependencies[name] ?? declared.devDependencies[name];
        if (record.version !== expected) throw new Error('LOG_PORTAL_TOOLCHAIN_INVALID');
        return { name: 'npm.' + name.replace(/^@/, '').replaceAll('/', '.'), version: record.version, vendorBuildProvenance: 'UNKNOWN' };
      }
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
    const parent = dirname(directory); if (parent === directory) break; directory = parent;
  }
  throw new Error('LOG_PORTAL_TOOLCHAIN_INVALID');
});
return records;
}

export function pnpmVersion(entrypoint = process.env.npm_execpath) {
  if (typeof entrypoint !== 'string' || !isAbsolute(entrypoint)) throw new Error('LOG_PORTAL_TOOLCHAIN_INVALID');
  const file = realpathSync(entrypoint);
  // pnpm12 uses a native executable; Corepack can also supply its JS bootstrap.
  const script = /\.[cm]?js$/.test(file);
  return execFileSync(script ? process.execPath : file, script ? [file, '--version'] : ['--version'],
    { encoding: 'utf8', timeout: 30000, maxBuffer: 4096, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

export function writeRecord(output, root = process.cwd()) {
const records = collectPackages(root);
const version = name => records.find(row => row.name === 'npm.' + name).version;
const pnpm = pnpmVersion();
const toolchain = [
  ['node', process.versions.node], ['pnpm', pnpm], ['typescript', version('typescript')], ['vite', version('vite')],
].map(([name, version]) => ({ name, version, vendorBuildProvenance: 'UNKNOWN' }));
writeFileSync(output, JSON.stringify({ schemaVersion: 1, component: 'admin-portal', toolchain, dependencies: records }), { flag: 'wx', mode: 0o600 });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) writeRecord(process.argv[2]);
