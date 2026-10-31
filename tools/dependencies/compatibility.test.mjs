import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { test } from 'node:test';

const rootRequire = createRequire(import.meta.url);
const parserRequire = createRequire(rootRequire.resolve('@asyncapi/parser'));
const spectralEntry = parserRequire.resolve('@stoplight/spectral-core');
const { minimatch } = rootRequire(join(dirname(spectralEntry), 'ruleset/utils/minimatch.js'));

test('Spectral retains source-pattern matchBase behavior with the patched named minimatch API', () => {
  assert.equal(minimatch('docs/synthetic.yaml', '*.yaml'), true);
  assert.equal(minimatch('docs/synthetic.json', '*.yaml'), false);
  assert.equal(minimatch('docs/nested/synthetic.yaml', 'docs/**/*.yaml'), true);
  assert.equal(minimatch('other/synthetic.yaml', 'docs/**/*.yaml'), false);
});
