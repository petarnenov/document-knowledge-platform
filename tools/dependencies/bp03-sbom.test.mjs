import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, existsSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { canonical, expectedPackages, generate, inputs, normalize, scanner } from './bp03-sbom.mjs';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'dkp-sbom-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const [path, body] of Object.entries({
    'pnpm-lock.yaml': "lockfileVersion: '9.0'\npackages:\n  '@scope/example@2.0.0': {}\n",
    'settings-gradle.lockfile': '# plugin\nexample:plugin:1.1=classpath\n',
    'services/test/gradle.lockfile': '# pinned\nexample:library:1.0=runtimeClasspath\nempty=test\n',
    'tools/runtime/requirements.txt': '# tooling\ncoverage==7.17.0\n',
    'local-data/security/secret.key': 'PRIVATE-SENTINEL',
    'node_modules/ignored/gradle.lockfile': 'not a lock',
    '.git/private': 'PRIVATE-SENTINEL',
  })) { mkdirSync(join(root, path, '..'), { recursive: true }); writeFileSync(join(root, path), body); }
  return root;
}
function raw() {
  return { $schema: 'http://cyclonedx.org/schema/bom-1.6.schema.json', bomFormat: 'CycloneDX', specVersion: '1.6', version: 1, components: [
    ['maven', 'example:library', '1.0'], ['maven', 'example:plugin', '1.1'], ['npm', '@scope/example', '2.0.0'], ['pypi', 'coverage', '7.17.0'],
  ].map(([kind, name, version]) => ({ type: 'library', name, version, purl: `pkg:${kind}/${name}@${version}`, 'bom-ref': `pkg:${kind}/${name}@${version}` })), vulnerabilities: [] };
}
const hash = 'a'.repeat(64);

test('complete effective package parity includes settings plugins, pnpm and exact Python pins', t => {
  const locks = inputs(fixture(t));
  assert.equal(locks.length, 4);
  assert.deepEqual([...expectedPackages(locks)].sort(), ['maven:example:library@1.0', 'maven:example:plugin@1.1', 'npm:@scope/example@2.0.0', 'pypi:coverage@7.17.0']);
  const bom = normalize(raw(), locks, hash);
  assert.equal(bom.specVersion, '1.6');
  assert.equal(bom.metadata.tools.components[0].version, '2.7.0');
  assert.equal(bom.metadata.properties.filter(p => p.name.startsWith('dkp:input:')).length, 4);
  assert.deepEqual(canonical({ z: [{ b: 1, a: null }], a: false }), { a: false, z: [{ a: null, b: 1 }] });
});

test('isolated Docker bind contains only approved public locks, aliases settings and cleans up', t => {
  const root = fixture(t), output = join(root, 'build/sbom'); let staged;
  const bom = generate(root, output, (command, args, options) => {
    assert.equal(command, 'docker'); assert.ok(args.includes(scanner)); assert.ok(args.includes('--all-packages')); assert.ok(args.includes('--no-resolve'));
    assert.equal(options.timeout, 300_000);
    const mount = args[args.indexOf('--mount') + 1]; staged = mount.match(/src=(.*),dst=/)[1];
    assert.notEqual(staged, root); assert.equal(existsSync(join(staged, 'local-data')), false);
    assert.equal(existsSync(join(staged, 'node_modules')), false); assert.equal(existsSync(join(staged, '.git')), false);
    assert.equal(readFileSync(join(staged, 'settings/gradle.lockfile'), 'utf8'), '# plugin\nexample:plugin:1.1=classpath\n');
    return { status: 0, stdout: JSON.stringify(raw()) };
  });
  assert.equal(bom.components.length, 4); assert.equal(existsSync(staged), false);
  const provenance = JSON.parse(readFileSync(join(output, 'bp03-source-provenance.json')));
  assert.equal(provenance.inputs.length, 4); assert.match(provenance.sbomSha256, /^[a-f0-9]{64}$/);
  assert.equal(readFileSync(join(output, 'bp03-source.cdx.json'), 'utf8').includes('PRIVATE-SENTINEL'), false);
  const before = readFileSync(join(output, 'bp03-source.cdx.json'), 'utf8');
  generate(root, output, () => ({ status: 0, stdout: JSON.stringify({ ...raw(), components: raw().components.reverse() }) }));
  assert.equal(readFileSync(join(output, 'bp03-source.cdx.json'), 'utf8'), before);
});

test('missing required lock or all project Gradle locks fails closed', t => {
  for (const path of ['pnpm-lock.yaml', 'settings-gradle.lockfile', 'tools/runtime/requirements.txt', 'services/test/gradle.lockfile']) {
    const root = fixture(t); rmSync(join(root, path)); assert.throws(() => inputs(root), /Missing input|No project Gradle/);
  }
});
test('never follows source or nested symlinks', t => {
  const root = fixture(t); symlinkSync('/tmp', join(root, 'escape')); assert.throws(() => inputs(root), /Symlink/);
  const link = root + '-link'; symlinkSync(root, link); t.after(() => rmSync(link)); assert.throws(() => inputs(link), /real directory/);
  assert.throws(() => inputs(join(root, 'pnpm-lock.yaml')), /real directory/);
});
test('rejects unsupported or ambiguous lock syntax rather than silent omissions', t => {
  const locks = inputs(fixture(t));
  for (const [path, text] of [ ['x/gradle.lockfile', 'bad'], ['pnpm-lock.yaml', 'lockfileVersion: 6\npackages: {}'], ['pnpm-lock.yaml', "lockfileVersion: '9.0'\npackages:\n  bad: {}"], ['pnpm-lock.yaml', 'packages: [bad'], ['tools/runtime/requirements.txt', 'coverage>=7'], ['tools/runtime/requirements.txt', 'git+https://example.test/private'] ]) {
    assert.throws(() => expectedPackages([{ path, bytes: Buffer.from(text) }]), /lock|pin|dependency|YAML/i);
  }
  assert.equal(expectedPackages([{ path: 'pnpm-lock.yaml', bytes: Buffer.from("lockfileVersion: '9.0'\npackages:\n  a@1: {}\n---\nlockfileVersion: '9.0'\npackages:\n  b@2: {}\n") }]).size, 2);
  assert.equal(expectedPackages([...locks, { path: 'tools/runtime/requirements.txt', bytes: Buffer.from('Other_Name==1.0\n') }]).has('pypi:other-name@1.0'), true);
});
test('rejects empty/vulnerability-only, wrong version and malformed standard output', t => {
  const locks = inputs(fixture(t));
  for (const patch of [{ components: [] }, { bomFormat: 'SPDX' }, { specVersion: '1.5' }, { version: 2 }, { components: null }]) assert.throws(() => normalize({ ...raw(), ...patch }, locks, hash), /populated/);
});
test('rejects incomplete, invented, duplicated or malformed components and vulnerabilities', t => {
  const locks = inputs(fixture(t));
  for (const mutate of [b => b.components.pop(), b => b.components.push({ ...b.components[0] }), b => { b.components[0].version = '99'; }, b => { b.components[0].type = 'file'; }, b => { b.components[0].purl = b.components[0]['bom-ref'] = 'pkg:generic/thing@1'; }, b => { b.vulnerabilities = [{}]; }, b => { delete b.vulnerabilities; }]) {
    const b = raw(); mutate(b); assert.throws(() => normalize(b, locks, hash));
  }
});
test('process failures or malformed JSON do not publish partial artifacts and remove staging', t => {
  for (const result of [{ status: 1, stdout: '{}' }, { status: 0, error: Error('timeout') }, { status: 0, stdout: 'not JSON' }]) {
    const root = fixture(t), output = join(root, 'build/sbom'); let stage;
    assert.throws(() => generate(root, output, (_, args) => { stage = args[args.indexOf('--mount') + 1].match(/src=(.*),dst=/)[1]; return result; }));
    assert.equal(existsSync(stage), false); assert.equal(existsSync(output), false);
  }
});
test('concurrent lock change is detected before any artifact publication', t => {
  const root = fixture(t), output = join(root, 'build/sbom');
  assert.throws(() => generate(root, output, () => { writeFileSync(join(root, 'tools/runtime/requirements.txt'), 'coverage==8.0\n'); return { status: 0, stdout: JSON.stringify(raw()) }; }), /changed during/);
  assert.equal(existsSync(output), false);
});
