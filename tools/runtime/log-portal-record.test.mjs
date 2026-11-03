import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, statSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { collectPackages, pnpmVersion, writeRecord } from './log-portal-record.mjs';

test('pnpm version probe executes native entrypoints directly and JS with pinned Node', t => {
  const root = mkdtempSync(join(tmpdir(), 'dkp-pnpm-probe-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const native = join(root, 'pnpm-native');
  writeFileSync(native, '#!/bin/sh\n[ "$1" = "--version" ] || exit 2\nprintf "12.16.1\\n"\n', { mode: 0o700 });
  assert.equal(pnpmVersion(native), '12.16.1');
  const script = join(root, 'pnpm.mjs');
  writeFileSync(script, 'if(process.argv[2]!=="--version")process.exit(2);console.log("12.16.1")');
  assert.equal(pnpmVersion(script), '12.16.1');
  assert.throws(() => pnpmVersion('relative'));
  assert.throws(() => pnpmVersion(join(root, 'missing')));
  writeFileSync(native, '#!/bin/sh\nexit 3\n'); assert.throws(() => pnpmVersion(native));
});

test('resolved package entry works with package.json exports blocked; exact installed pins required', t => {
  const root = mkdtempSync(join(tmpdir(), 'dkp-packages-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const dependencies = { '@example/library': '1.0.0' };
  const devDependencies = { '@vitejs/plugin-react': '6.1.2', typescript: '6.0.3', vite: '8.3.5' };
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies, devDependencies }));
  for (const [name, version] of Object.entries({ ...dependencies, ...devDependencies })) {
    const dir = join(root, 'node_modules', name); mkdirSync(join(dir, 'lib'), { recursive: true });
    writeFileSync(join(dir, 'package.json'), JSON.stringify({ name, version, exports: './lib/index.js' }));
    writeFileSync(join(dir, 'lib/index.js'), '');
  }
  const records = collectPackages(root);
  assert.equal(records.length, 4);
  assert.deepEqual(records.find(row => row.name === 'npm.example.library'),
    { name: 'npm.example.library', version: '1.0.0', vendorBuildProvenance: 'UNKNOWN' });
  const native = join(root, 'pnpm-native');
  writeFileSync(native, '#!/bin/sh\nprintf "12.16.1\\n"\n', { mode: 0o700 });
  const original = process.env.npm_execpath;
  try {
    process.env.npm_execpath = native;
    const output = join(root, 'record.json'); writeRecord(output, root);
    const record = JSON.parse(readFileSync(output));
    assert.equal(record.component, 'admin-portal'); assert.deepEqual(record.dependencies, records);
    assert.equal(record.toolchain.find(row => row.name === 'pnpm').version, '12.16.1');
    assert.equal(statSync(output).mode & 0o077, 0);
    assert.throws(() => writeRecord(output, root), /EEXIST/);
  } finally {
    if (original === undefined) delete process.env.npm_execpath; else process.env.npm_execpath = original;
  }
  const file = join(root, 'node_modules/vite/package.json');
  writeFileSync(file, JSON.stringify({ name: 'vite', version: 'wrong', exports: './lib/index.js' }));
  assert.throws(() => collectPackages(root), /LOG_PORTAL_TOOLCHAIN_INVALID/);
  writeFileSync(file, JSON.stringify({ name: 'different', version: '8.3.5', exports: './lib/index.js' }));
  assert.throws(() => collectPackages(root), /LOG_PORTAL_TOOLCHAIN_INVALID/);
});
