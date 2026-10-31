import { readFile } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { filesUnder } from './lib.mjs';

export async function compareGeneratedTrees(actual, expected) {
  const actualFiles = (await filesUnder(actual)).map((file) => relative(actual, file));
  const expectedFiles = (await filesUnder(expected)).map((file) => relative(expected, file));
  if (JSON.stringify(actualFiles) !== JSON.stringify(expectedFiles)) throw new Error('Generated file set drift; run pnpm contracts:generate');
  for (const file of expectedFiles) {
    if (!(await readFile(join(actual, file))).equals(await readFile(join(expected, file)))) throw new Error(`${file}: generated content drift; run pnpm contracts:generate`);
  }
}
