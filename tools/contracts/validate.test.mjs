import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { root, validateContract } from './lib.mjs';

for (const kind of ['openapi', 'asyncapi']) {
  test(`${kind}: schema-valid fixture is accepted`, async () => {
    await validateContract(resolve(root, `tools/contracts/fixtures/${kind}-valid.yaml`), kind);
  });
  test(`${kind}: malformed contract is rejected`, async () => {
    await assert.rejects(validateContract(resolve(root, `tools/contracts/fixtures/${kind}-invalid.yaml`), kind));
  });
}
test('unknown validator kinds cannot silently skip validation', async () => {
  await assert.rejects(validateContract(resolve(root, 'tools/contracts/fixtures/openapi-valid.yaml'), 'unsupported'), /Unsupported contract kind/);
});

import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { compareGeneratedTrees } from './sdk-tree.mjs';

test('SDK comparison detects changed, removed and added generated files', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'dkp-sdk-tree-test-'));
  const actual = join(temporary, 'actual');
  const expected = join(temporary, 'expected');
  try {
    await mkdir(actual);
    await mkdir(expected);
    await writeFile(join(actual, 'Client.java'), 'expected');
    await writeFile(join(expected, 'Client.java'), 'expected');
    await compareGeneratedTrees(actual, expected);
    await writeFile(join(actual, 'Client.java'), 'modified');
    await assert.rejects(compareGeneratedTrees(actual, expected), /content drift/);
    await rm(join(actual, 'Client.java'));
    await assert.rejects(compareGeneratedTrees(actual, expected), /file set drift/);
    await writeFile(join(actual, 'Client.java'), 'expected');
    await writeFile(join(actual, 'Unexpected.java'), 'extra');
    await assert.rejects(compareGeneratedTrees(actual, expected), /file set drift/);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

import { ensureGenerator, verifyGeneratorBytes, generatorVersion } from './generator.mjs';

test('generator integrity rejects substituted bytes before execution', () => {
  assert.throws(() => verifyGeneratorBytes(Buffer.from('substituted generator')), /checksum mismatch.*No Java code was executed/);
});

test('generator rejects corrupt cached artifacts rather than trusting the cache', async () => {
  const cacheDirectory = await mkdtemp(join(tmpdir(), 'dkp-generator-cache-test-'));
  try {
    await writeFile(join(cacheDirectory, `openapi-generator-cli-${generatorVersion}.jar`), 'corrupt cache');
    await assert.rejects(ensureGenerator({ cacheDirectory, download: async () => { throw new Error('must not download over corrupt cache'); } }), /checksum mismatch/);
  } finally {
    await rm(cacheDirectory, { recursive: true, force: true });
  }
});

test('generator rejects substituted downloads without installing an artifact', async () => {
  const cacheDirectory = await mkdtemp(join(tmpdir(), 'dkp-generator-download-test-'));
  try {
    await assert.rejects(ensureGenerator({ cacheDirectory, download: async () => Buffer.from('substituted download') }), /checksum mismatch/);
    const { readdir } = await import('node:fs/promises');
    assert.deepEqual(await readdir(cacheDirectory), []);
  } finally {
    await rm(cacheDirectory, { recursive: true, force: true });
  }
});
