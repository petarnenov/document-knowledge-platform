import test from 'node:test';
import { createRequire } from 'node:module';
import { referenceMeasurements } from './capture-reference.mjs';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const helper = join(root, 'tools/visual/capture-reference.mjs');
function temporary(t) {
  const directory = mkdtempSync(join(tmpdir(), 'dkp-visual-cli-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}
function capture(args) {
  // Preserve NODE_V8_COVERAGE supplied by node:test so the executed helper,
  // rather than only this test harness, contributes real source coverage.
  const result = spawnSync(process.execPath, [helper, ...args], {
    cwd: root,
    env: { ...process.env },
    encoding: 'utf8',
    timeout: 120_000,
    maxBuffer: 8 * 1024 * 1024,
  });
  assert.equal(result.error, undefined, result.error?.message);
  assert.equal(result.signal, null);
  return result;
}

test('CLI requires an explicit evidence destination', () => {
  const result = capture([]);
  assert.notEqual(result.status, 0);
  assert.match(
    result.stderr,
    /Usage: node tools\/visual\/capture-reference\.mjs/,
  );
});

test('CLI refuses to overwrite existing evidence', (t) => {
  const output = temporary(t);
  const sentinel = join(output, 'retained-evidence.txt');
  writeFileSync(sentinel, 'Previously reviewed evidence');
  const result = capture([output]);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /EEXIST/);
  assert.deepEqual(readdirSync(output), ['retained-evidence.txt']);
  assert.equal(readFileSync(sentinel, 'utf8'), 'Previously reviewed evidence');
});

test('CLI captures every reference state with source-bound private evidence', (t) => {
  const output = join(temporary(t), 'fresh-reference');
  const result = capture([output]);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(result.stdout), { captures: 108, output });
  assert.equal(statSync(output).mode & 0o777, 0o700);
  const inventoryFile = join(output, 'inventory.json');
  assert.equal(statSync(inventoryFile).mode & 0o777, 0o600);
  const inventory = JSON.parse(readFileSync(inventoryFile, 'utf8'));
  assert.equal(
    inventory.prototypeSHA256,
    createHash('sha256')
      .update(
        readFileSync(
          join(root, 'docs/prototypes/DKP_Interactive_Design_v0.5.html'),
        ),
      )
      .digest('hex'),
  );
  assert.ok(inventory.browser);
  assert.equal(inventory.captureEnvironment.node, process.version);
  const states = [
    'home',
    'documents',
    'collections',
    'reviews',
    'search',
    'operations',
    'governance',
    'audit',
    'detail',
    'upload',
    'upload-blocked',
    'processing',
    'draft',
    'review-conflict',
    'source',
    'governance-confirm',
    'audit-event',
    'workspace-switch',
  ];
  const expected = states.flatMap((name) =>
    ['dark', 'light'].flatMap((theme) =>
      [1440, 768, 390].map((width) => `${name}-${theme}-${width}.png`),
    ),
  );
  assert.equal(inventory.captures.length, 108);
  assert.deepEqual(
    inventory.captures.map((capture) => capture.file).sort(),
    expected.sort(),
  );
  assert.deepEqual(
    readdirSync(output).sort(),
    [...expected, 'inventory.json'].sort(),
  );
  for (const entry of inventory.captures) {
    assert.ok(entry.heading);
    assert.ok(states.includes(entry.name));
    assert.ok(['dark', 'light'].includes(entry.theme));
    assert.equal(
      entry.file,
      `${entry.name}-${entry.theme}-${entry.viewport.width}.png`,
    );
    assert.equal(
      entry.measurements.header.y,
      0,
      'Every capture resets scroll position',
    );
    assert.equal(
      entry.measurements.aside.width,
      entry.viewport.width === 390 ? 62 : 215,
    );
    const png = readFileSync(join(output, entry.file));
    assert.deepEqual(
      png.subarray(0, 8),
      Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    );
    assert.equal(png.readUInt32BE(16), entry.viewport.width);
    assert.ok(png.readUInt32BE(20) >= entry.viewport.height);
  }
});

test('DOM measurements preserve computed styles and report absent controls as null', (t) => {
  const require = createRequire(join(root, 'apps/admin-portal/package.json'));
  const { JSDOM } = require('jsdom');
  const dom = new JSDOM(
    '<header style="padding:16px 22px;background:rgb(25,31,41);color:rgb(237,241,247);border-radius:9px"></header>',
  );
  const previousDocument = Object.getOwnPropertyDescriptor(
    globalThis,
    'document',
  );
  const previousStyles = Object.getOwnPropertyDescriptor(
    globalThis,
    'getComputedStyle',
  );
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: dom.window.document,
  });
  Object.defineProperty(globalThis, 'getComputedStyle', {
    configurable: true,
    value: dom.window.getComputedStyle.bind(dom.window),
  });
  t.after(() => {
    if (previousDocument)
      Object.defineProperty(globalThis, 'document', previousDocument);
    else delete globalThis.document;
    if (previousStyles)
      Object.defineProperty(globalThis, 'getComputedStyle', previousStyles);
    else delete globalThis.getComputedStyle;
    dom.window.close();
  });
  const header = dom.window.document.querySelector('header');
  t.mock.method(header, 'getBoundingClientRect', () => ({
    x: 10.25,
    y: 0,
    width: 1418,
    height: 71,
  }));
  const measurements = referenceMeasurements();
  assert.equal(measurements.header.x, 10.25);
  assert.equal(measurements.header.width, 1418);
  assert.equal(measurements.header.height, 71);
  assert.equal(measurements.header.padding, '16px 22px');
  assert.equal(measurements.header.background, 'rgb(25, 31, 41)');
  assert.equal(measurements.header.color, 'rgb(237, 241, 247)');
  assert.equal(measurements.header.borderRadius, '9px');
  assert.equal(measurements.input, null);
  assert.equal(measurements.select, null);
  assert.equal(measurements['.card'], null);
});
