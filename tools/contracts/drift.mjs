import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { root } from './lib.mjs';
import { compareGeneratedTrees } from './sdk-tree.mjs';
import { generate } from './generate.mjs';

const scratch = await mkdtemp(join(tmpdir(), 'dkp-sdk-drift-'));
try {
  await generate(scratch);
  for (const language of ['java', 'typescript']) {
    const actual = resolve(root, 'generated-clients', language, 'src');
    const expected = join(scratch, language, 'src');
    await compareGeneratedTrees(actual, expected);
  }
  console.log('Generated SDK drift check passed.');
} finally {
  await rm(scratch, { recursive: true, force: true });
}
