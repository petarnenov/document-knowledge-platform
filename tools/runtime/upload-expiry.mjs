#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

export function command(args) {
  const [profile, ...options] = args;
  if (profile === 'fast' && options.length === 0) {
    return [
      resolve(root, 'gradlew'),
      '--no-daemon',
      ':services:document-management:application:test',
    ];
  }
  if (profile !== 'acceptance')
    throw new Error(
      'Choose fast or acceptance; fast accepts no runtime options.',
    );
  const values = new Map();
  for (let index = 0; index < options.length; index += 2) {
    const key = options[index];
    const value = options[index + 1];
    if (
      !['--security-directory', '--runtime-env-file'].includes(key) ||
      values.has(key) ||
      !value ||
      value.startsWith('--')
    ) {
      throw new Error('Acceptance requires explicit, unique runtime paths.');
    }
    values.set(key, resolve(value));
  }
  if (values.size !== 2)
    throw new Error(
      'Acceptance requires both --security-directory and --runtime-env-file.',
    );
  return [
    process.execPath,
    resolve(root, 'tools/runtime/identity-e2e.mjs'),
    '--upload-limits-profile',
    'expiry',
    '--security-directory',
    values.get('--security-directory'),
    '--runtime-env-file',
    values.get('--runtime-env-file'),
  ];
}

export function main(args, execute = spawnSync) {
  const [program, ...arguments_] = command(args);
  const result = execute(program, arguments_, {
    cwd: root,
    stdio: 'inherit',
    shell: false,
  });
  if (result.error || result.signal || !Number.isInteger(result.status))
    return 1;
  return result.status;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch {
    console.error(
      'Choose fast, or acceptance with --security-directory and --runtime-env-file. Acceptance waits the real 60-minute lifetime.',
    );
    process.exitCode = 1;
  }
}
