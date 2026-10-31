import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import ts from 'typescript';
import { installNullModel, nullModelSource } from './null-model.mjs';

test('null-only serialization and deserialization reject absent and non-null values', async () => {
  const { outputText } = ts.transpileModule(nullModelSource, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2023 },
  });
  const model = await import('data:text/javascript;base64,' + Buffer.from(outputText).toString('base64'));
  for (const value of [null, undefined, false, 0, '', 'null', {}, []]) {
    assert.equal(model.instanceOfNull(value), value === null);
    for (const name of ['NullFromJSON', 'NullFromJSONTyped', 'NullToJSON', 'NullToJSONTyped']) {
      if (value === null) assert.equal(model[name](value, false), null);
      else assert.throws(() => model[name](value, false), /Expected null-only API field/);
    }
  }
});

test('generation installs missing null-only model deterministically only when referenced', async () => {
  const root = await mkdtemp(join(tmpdir(), 'dkp-null-model-'));
  try {
    await mkdir(join(root, 'models'));
    const index = join(root, 'models', 'index.ts');
    await writeFile(index, "export * from './Document.js';\n");
    await writeFile(join(root, 'models', 'Document.ts'), 'export interface Document { id: string }\n');
    await writeFile(join(root, 'models', 'ignored.json'), '{}');
    assert.equal(await installNullModel(root), false);
    assert.equal((await readdir(join(root, 'models'))).includes('Null.ts'), false);
    await writeFile(join(root, 'models', 'Document.ts'), "import type { Null } from './Null.js';\nexport interface Document { activeVersionId: Null }\n");
    assert.equal(await installNullModel(root), true);
    assert.equal(await readFile(join(root, 'models', 'Null.ts'), 'utf8'), nullModelSource);
    const before = await readFile(index, 'utf8');
    assert.equal(await installNullModel(root), true);
    assert.equal(await readFile(index, 'utf8'), before);
    assert.equal(before.match(/export \* from '\.\/Null\.js';/g).length, 1);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
