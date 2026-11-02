import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { inputRows, assess } from './conformance.mjs';
const hash = (value) => createHash('sha256').update(value).digest('hex');
const raw = {
  id: 'object',
  json: '{}',
  expected: 'CANONICAL',
  canonical: '{}',
  sha256: hash('{}'),
};
const number = {
  id: 'zero',
  ieee754: '8000000000000000',
  expected: 'CANONICAL',
  canonical: '{"n":0}',
  sha256: hash('{"n":0}'),
};
const invalid = { id: 'bad', utf8Bytes: [255], expected: 'REJECT' };
test('preserves raw bytes and IEEE identities for actual candidate input', () => {
  assert.equal(
    inputRows([raw, number, invalid]),
    'object\tUTF8\te30=\nzero\tIEEE754\t8000000000000000\nbad\tUTF8\t/w==\n',
  );
});
test('compares exact bytes, independent hash, negative rejection and preserves failed candidate evidence', () => {
  const rows = assess(
    [raw, number, invalid],
    'object\te30=\nzero\teyJuIjowfQ==\nbad\t/w==\n',
    JSON.stringify,
  );
  assert.deepEqual(
    rows.map((x) => x.java),
    ['PASS', 'PASS', 'FAIL'],
  );
  assert.deepEqual(
    rows.map((x) => x.reference),
    ['PASS', 'PASS', 'RAW_INPUT_VALIDATION_REQUIRED'],
  );
  assert.equal(
    assess([invalid], 'bad\tREJECT\n', JSON.stringify)[0].java,
    'PASS',
  );
});
test('reference mismatch and digest mismatch are failures', () => {
  assert.equal(
    assess([raw], 'object\te30=\n', () => '{"x":1}')[0].reference,
    'FAIL',
  );
  assert.equal(
    assess(
      [{ ...raw, sha256: '0'.repeat(64) }],
      'object\te30=\n',
      JSON.stringify,
    )[0].reference,
    'FAIL',
  );
});
test('rejects duplicate, malformed, incomplete and substituted identities', () => {
  for (const text of [
    'object\te30=\nobject\te30=\n',
    'object\te30=\textra\n',
    '',
  ])
    assert.throws(() => assess([raw], text, JSON.stringify));
  assert.throws(() => assess([raw, invalid], 'object\te30=\n', JSON.stringify));
  assert.throws(() => assess([raw], 'unknown\te30=\n', JSON.stringify));
});

test('all shared fixture identities and expected UTF8 hashes are independently consistent', async () => {
  const { readFile } = await import('node:fs/promises');
  const { vectors } = JSON.parse(
    await readFile(new URL('./conformance-vectors.json', import.meta.url)),
  );
  assert.equal(vectors.length, 45);
  assert.equal(new Set(vectors.map((x) => x.id)).size, 45);
  assert.equal(vectors.filter((x) => x.expected === 'REJECT').length, 14);
  for (const v of vectors.filter((x) => x.expected === 'CANONICAL'))
    assert.equal(hash(v.canonical), v.sha256);
  assert.equal(inputRows(vectors).trim().split('\n').length, 45);
});
test('recorded raw registry indexes and platform manifests match exact content identities', async () => {
  const { readFile } = await import('node:fs/promises');
  const pins = JSON.parse(
    await readFile(new URL('./candidate-pins.json', import.meta.url)),
  );
  assert.equal(pins.transitiveGraph.length, 53);
  assert.equal(
    pins.transitiveGraph.filter((x) => x.scope === 'production-candidate')
      .length,
    42,
  );
  assert.equal(
    pins.osvPackageMatches.filter((x) => x.vulnerabilities.length).length,
    7,
  );
  assert.equal(
    pins.osvPackageMatches.flatMap((x) => x.vulnerabilities).length,
    24,
  );
  for (const image of pins.images) {
    const stem = image.repository.replace('/', '-');
    const index = await readFile(
      new URL(`./registry/${stem}-index.json`, import.meta.url),
    );
    assert.equal(
      'sha256:' + createHash('sha256').update(index).digest('hex'),
      image.indexDigest,
    );
    const platforms = image.platforms.filter((x) => x.platform.os === 'linux');
    assert.deepEqual(platforms.map((x) => x.platform.architecture).sort(), [
      'amd64',
      'arm64',
    ]);
    for (const platform of platforms) {
      const bytes = await readFile(
        new URL(
          `./registry/${stem}-${platform.platform.architecture}-manifest.json`,
          import.meta.url,
        ),
      );
      assert.equal(
        'sha256:' + createHash('sha256').update(bytes).digest('hex'),
        platform.digest,
      );
      assert.equal(JSON.parse(bytes).config.digest, platform.configDigest);
    }
  }
});
test('observations honestly preserve the failed canonicalizer disposition', async () => {
  const { readFile } = await import('node:fs/promises');
  const report = JSON.parse(
    await readFile(new URL('./observed-results.json', import.meta.url)),
  );
  assert.equal(report.status, 'RESEARCH_COMPLETED_CANDIDATES_BLOCKED');
  assert.equal(report.javaPassed, 38);
  assert.equal(report.vectorCount, 45);
  assert.deepEqual(report.javaFailures, [
    'duplicate-null',
    'duplicate-escaped-null',
    'lone-high-value',
    'lone-low-value',
    'lone-high-key',
    'leading-zero',
    'invalid-utf8',
  ]);
  assert.equal(report.javascriptPositivePassed, 31);
  assert.deepEqual(report.javascriptPositiveFailures, []);
  assert.equal(report.sdkConfiguration, 'PASS_NO_CONNECTION_STARTED');
});
