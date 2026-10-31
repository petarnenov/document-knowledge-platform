import { readdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

// The pinned typescript-fetch template references Null.js for OAS 3.1 type:null,
// but emits no such model. Preserve the strict wire type instead of widening it.
export const nullModelSource = `// Deterministic OAS 3.1 null-only model supplied by the SDK generation pipeline.
export type Null = null;
export function instanceOfNull(value: unknown): value is Null { return value === null; }
export function NullFromJSON(value: unknown): Null {
    if (value !== null) throw new TypeError('Expected null-only API field');
    return null;
}
export function NullFromJSONTyped(value: unknown, _ignoreDiscriminator: boolean): Null {
    return NullFromJSON(value);
}
export function NullToJSON(value: Null): null { return NullFromJSON(value); }
export function NullToJSONTyped(value: Null, _ignoreDiscriminator?: boolean): null {
    return NullFromJSON(value);
}
`;

export async function installNullModel(sourceRoot) {
  const directory = join(sourceRoot, 'models');
  const files = (await readdir(directory)).filter((name) => name.endsWith('.ts') && name !== 'Null.ts');
  const sources = await Promise.all(files.map((name) => readFile(join(directory, name), 'utf8')));
  if (!sources.some((source) => /from ['"]\.\/Null\.js['"]/.test(source))) return false;
  await writeFile(join(directory, 'Null.ts'), nullModelSource);
  const index = join(directory, 'index.ts');
  const exports = await readFile(index, 'utf8');
  if (!exports.includes("export * from './Null.js';")) {
    await writeFile(index, exports.trimEnd() + "\nexport * from './Null.js';\n");
  }
  return true;
}
