import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { build } from 'vite';
import { canonicalPublic, loggingManifest, readPortalManifest } from '../tooling/logging-manifest';

const roots: string[] = [];
afterEach(() => roots.splice(0).forEach(root => rmSync(root, { recursive: true, force: true })));
function fixture() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'dkp-portal-manifest-'))); roots.push(root);
  const id = 'c5a4b091-298a-44cb-82dc-ff32dd3989da';
  const directory = join(root, 'build/generated/dkp-logging/admin-portal', id);
  mkdirSync(directory, { recursive: true });
  const vocabulary = { events: ['process.started'], phases: ['</script><script>bad()</script>'],
    errorCodes: [], dependencies: [], exceptionClasses: [], frames: [] };
  const record: Record<string, unknown> = { schemaVersion: 1, buildId: id, component: 'admin-portal',
    gitSha: 'a'.repeat(40), sourceFingerprint: 'b'.repeat(64), dirty: false,
    sourceFingerprintAlgorithm: 'dkp-public-inputs-v1', compiledAt: '2026-11-03T05:00:00Z',
    toolchainFingerprint: 'c'.repeat(64), dependencyFingerprint: 'd'.repeat(64), vocabulary,
    vocabularySha256: createHash('sha256').update(canonicalPublic(vocabulary)).digest('hex') };
  const file = join(directory, 'manifest.json');
  const save = () => writeFileSync(file, canonicalPublic(record)); save();
  return { root, file, record, save };
}

describe('original portal manifest adapter', () => {
  it('reads exact original public bytes and preserves canonical primitive metadata', () => {
    const f = fixture(); expect(readPortalManifest(f.root, f.file)).toBe(canonicalPublic(f.record));
    expect(canonicalPublic([null, true, 1, 'x'])).toBe('[null,true,1,"x"]');
    expect(() => canonicalPublic(1.5)).toThrow('LOG_PORTAL_MANIFEST_INVALID');
    expect(() => canonicalPublic({ '\u00e9': 1 })).toThrow('LOG_PORTAL_MANIFEST_INVALID');
  });
  it.each([
    ['schemaVersion', 2], ['buildId', '00000000-0000-0000-0000-000000000000'],
    ['component', 'other'], ['sourceFingerprintAlgorithm', 'other'], ['dirty', 'false'],
    ['gitSha', 'wrong'], ['sourceFingerprint', false], ['dependencyFingerprint', 'wrong'],
    ['compiledAt', 'wrong'], ['vocabulary', null], ['vocabulary', []], ['vocabularySha256', 'a'.repeat(64)],
    ['extra', 'unexpected'],
  ])('rejects invalid %s', (key, value) => {
    const f = fixture(); f.record[key] = value; f.save();
    expect(() => readPortalManifest(f.root, f.file)).toThrow();
  });
  it.each(['{}', 'null', '[]', '{"buildId":"first","buildId":"second"}', '\ufffd', ' '.repeat(1024 * 1024 + 1)])(
    'rejects noncanonical, malformed or oversized input %#', raw => {
      const f = fixture(); writeFileSync(f.file, raw);
      expect(() => readPortalManifest(f.root, f.file)).toThrow();
    });
  it('rejects relative, symlinked, misplaced and empty inputs', () => {
    const f = fixture();
    expect(() => readPortalManifest(f.root, 'manifest.json')).toThrow();
    const alias = join(f.root, 'alias.json'); symlinkSync(f.file, alias);
    expect(() => readPortalManifest(f.root, alias)).toThrow();
    const moved = join(f.root, 'manifest.json'); writeFileSync(moved, canonicalPublic(f.record));
    expect(() => readPortalManifest(f.root, moved)).toThrow();
    writeFileSync(f.file, ''); expect(() => readPortalManifest(f.root, f.file)).toThrow();
    writeFileSync(f.file, Buffer.from([0xff])); expect(() => readPortalManifest(f.root, f.file)).toThrow();
  });
  it('embeds safely in both real Vite HTML outputs and emits identical manifest bytes', async () => {
    const f = fixture();
    for (const name of ['index.html', 'logs.html']) writeFileSync(join(f.root, name), '<html><head></head><body>Portal</body></html>');
    const result = await build({ configFile: false, root: f.root, logLevel: 'silent', plugins: [loggingManifest(f.root, f.file)],
      build: { write: false, rolldownOptions: { input: { portal: join(f.root, 'index.html'), logs: join(f.root, 'logs.html') } } } });
    const outputs = (Array.isArray(result) ? result : [result]).flatMap(item => 'output' in item ? item.output : []);
    const asset = outputs.find(item => item.fileName === 'dkp-logging-manifest.json');
    expect(asset && asset.type === 'asset' && asset.source).toBe(canonicalPublic(f.record));
    for (const name of ['index.html', 'logs.html']) {
      const html = outputs.find(item => item.fileName === name);
      expect(html && html.type === 'asset' && html.source).toContain('id="dkp-log-build-manifest"');
      expect(html && html.type === 'asset' && html.source).toContain('\\u003c/script>');
      expect(html && html.type === 'asset' && html.source).not.toContain('</script><script>bad()');
    }
  });
  it('leaves unconfigured compilation without invented provenance', async () => {
    const f = fixture(); writeFileSync(join(f.root, 'index.html'), '<html><head></head><body>Portal</body></html>');
    const result = await build({ configFile: false, root: f.root, logLevel: 'silent', plugins: [loggingManifest(f.root, undefined)], build: { write: false } });
    const outputs = (Array.isArray(result) ? result : [result]).flatMap(item => 'output' in item ? item.output : []);
    expect(outputs.some(item => item.fileName === 'dkp-logging-manifest.json')).toBe(false);
    const html = outputs.find(item => item.fileName === 'index.html');
    expect(html && html.type === 'asset' && html.source).not.toContain('dkp-log-build-manifest');
  });
});
