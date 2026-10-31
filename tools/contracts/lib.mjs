import { readdir, readFile } from 'node:fs/promises';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { parse } from 'yaml';
import { Parser } from '@asyncapi/parser';

export const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} failed (${result.status}):\n${result.stdout ?? ''}${result.stderr ?? ''}`);
  }
  return result.stdout;
}
export async function filesUnder(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.sort((a, b) => a.name.localeCompare(b.name)).map(async (entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? filesUnder(path) : [path];
  }));
  return nested.flat();
}
export async function validateContract(file, kind) {
  const source = await readFile(file, 'utf8');
  const document = parse(source, { uniqueKeys: true });
  if (kind === 'openapi') {
    if (!document?.openapi?.startsWith('3.1.')) throw new Error(`${file}: expected OpenAPI 3.1`);
    run(resolve(root, 'node_modules/.bin/redocly'), ['lint', file, '--config', resolve(root, 'tools/contracts/redocly.yaml')]);
  } else if (kind === 'asyncapi') {
    const { document: parsed, diagnostics } = await new Parser().parse(source);
    const errors = diagnostics.filter((item) => item.severity === 0);
    if (!parsed || errors.length > 0) throw new Error(`${file}: ${errors.map((item) => item.message).join('; ') || 'invalid AsyncAPI'}`);
  } else {
    throw new Error(`Unsupported contract kind: ${kind}`);
  }
}
