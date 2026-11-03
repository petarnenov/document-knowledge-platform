import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, realpathSync, mkdirSync, copyFileSync, chmodSync, readFileSync, writeFileSync, rmSync, symlinkSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { FILES, packageDistribution } from './log-node-package.mjs';

const helper = fileURLToPath(new URL('./log-node-package.mjs', import.meta.url));
const original = fileURLToPath(new URL('../../sdks/javascript/', import.meta.url));
function fixture(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'dkp-node-package-')));
  t.after(() => rmSync(root, { recursive:true, force:true }));
  const source = join(root, 'source'), output = join(root, 'output');
  mkdirSync(source); mkdirSync(join(source, 'src')); mkdirSync(output);
  for (const name of FILES) {
    const target = join(source, name);
    copyFileSync(join(original, name), target); chmodSync(target, 0o600);
  }
  return { root, source, output };
}
test('real Node checks seven modules and preserves all twelve source files', t => {
  const { source, output } = fixture(t), before = Date.now();
  const record = packageDistribution(source, output);
  assert.equal(record.node, '24.22.1'); assert.equal(record.filesChecked, 7);
  assert.ok(Date.parse(record.startedAt) >= before);
  assert.ok(Date.parse(record.finishedAt) >= Date.parse(record.startedAt));
  assert.ok(record.durationMs >= 0); assert.equal(record.files.length, 12);
  for (const row of record.files) {
    const input = readFileSync(join(source, row.path)), target = join(output, 'sdk', row.path);
    assert.deepEqual(readFileSync(target), input);
    assert.equal(row.sourceSha256, createHash('sha256').update(input).digest('hex'));
    assert.equal(row.distributionSha256, row.sourceSha256); assert.equal(row.bytes, input.length);
    assert.equal(statSync(target).mode & 0o777, 0o600);
  }
  assert.deepEqual(JSON.parse(readFileSync(join(output, 'package-record.json'))), record);
});
for (const mutation of ['occupied', 'extra', 'alias', 'symlink', 'empty', 'large', 'descriptor', 'syntax']) {
  test('rejects ' + mutation + ' without successful record', t => {
    let { root, source, output } = fixture(t);
    if (mutation === 'occupied') writeFileSync(join(output, 'existing'), 'keep');
    if (mutation === 'extra') writeFileSync(join(source, 'src/extra.js'), 'export {};');
    if (mutation === 'alias') { symlinkSync(source, join(root, 'alias')); source = join(root, 'alias'); }
    if (mutation === 'symlink') { rmSync(join(source, 'src/wire.js')); symlinkSync(join(original, 'src/wire.js'), join(source, 'src/wire.js')); }
    if (mutation === 'empty') writeFileSync(join(source, 'src/wire.js'), '');
    if (mutation === 'large') writeFileSync(join(source, 'src/wire.js'), ' '.repeat(65537));
    if (mutation === 'descriptor') writeFileSync(join(source, 'package.json'), '{"type":"commonjs","engines":{"node":"24.22.1"}}');
    if (mutation === 'syntax') writeFileSync(join(source, 'src/wire.js'), 'export const = ;');
    assert.throws(() => packageDistribution(source, output));
    assert.throws(() => readFileSync(join(output, 'package-record.json')));
  });
}
test('CLI emits fixed safe failure and a real success', t => {
  const { source, output } = fixture(t);
  const invalid = spawnSync(process.execPath, [helper, '--unknown', 'secret-canary'], { encoding:'utf8' });
  assert.equal(invalid.status, 1);
  assert.deepEqual(JSON.parse(invalid.stdout), { result:'FAIL', code:'LOG_NODE_PACKAGE_INVALID' });
  assert.equal(invalid.stdout.includes('secret-canary'), false);
  const success = spawnSync(process.execPath, [helper, '--source', source, '--output', output], { encoding:'utf8' });
  assert.equal(success.status, 0); assert.deepEqual(JSON.parse(success.stdout), { result:'PACKAGED', filesChecked:7 });
});
