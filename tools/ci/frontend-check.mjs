import { spawnSync } from 'node:child_process';
import {
  chmodSync,
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const commands = [
  ['install', ['install', '--frozen-lockfile']],
  ['preview-runner-coverage', ['uiux-preview:test']],
  ['format', ['format:check']],
  ['lint', ['--filter', 'admin-portal', 'lint']],
  ['typecheck', ['--filter', 'admin-portal', 'typecheck']],
  ['build', ['--filter', 'admin-portal', 'build']],
  ['coverage', ['--filter', 'admin-portal', 'test:coverage']],
  [
    'helper-coverage',
    [
      '--filter',
      'admin-portal',
      'exec',
      'vitest',
      'run',
      'src/upload-fault-contract.test.tsx',
      'src/ui-create-receipt.test.tsx',
      '--coverage',
      '--coverage.include=e2e-real/upload-fault-contract.ts',
      '--coverage.include=e2e-real/ui-create-receipt.ts',
      '--coverage.reportsDirectory=coverage/acceptance-helpers',
      '--coverage.thresholds.lines=75',
      '--coverage.thresholds.branches=75',
      '--coverage.thresholds.functions=75',
      '--coverage.thresholds.statements=75',
    ],
  ],
  // Chromium and its OS dependencies are supplied by the immutable Playwright image.
  ['mock-e2e', ['--filter', 'admin-portal', 'test:e2e']],
  ['visual-reference-coverage', ['visual-reference:test']],
];

export function check(root, evidence, run = spawnSync, runtime = process) {
  mkdirSync(evidence, { recursive: true, mode: 0o700 });
  chmodSync(evidence, 0o700);
  const results = [];
  let status = 1;
  const environment = {
    node: runtime.version,
    platform: runtime.platform,
    architecture: runtime.arch,
    pnpm: '12.16.1',
    playwright: '1.64.1',
    source: readFileSync(join(root, 'frontend-source.json'), 'utf8'),
  };
  try {
    if (runtime.version !== 'v24.22.1' || runtime.platform !== 'linux')
      throw new Error('Expected pinned Linux Node 24.22.1');
    const version = run('pnpm', ['--version'], { cwd: root, encoding: 'utf8' });
    if (version.status !== 0 || version.stdout.trim() !== '12.16.1')
      throw new Error('Expected pinned pnpm 12.16.1');
    for (const [name, args] of commands) {
      const result = run('pnpm', args, {
        cwd: root,
        encoding: 'utf8',
        maxBuffer: 64 * 1024 * 1024,
      });
      const code = result.status ?? 1;
      writeFileSync(
        join(evidence, `${name}.log`),
        `${result.stdout ?? ''}${result.stderr ?? ''}`,
        { mode: 0o600 },
      );
      results.push({
        name,
        command: ['pnpm', ...args],
        status: code,
        signal: result.signal ?? null,
      });
      console.log(`${name}: ${code === 0 ? 'PASS' : 'FAIL'}`);
      if (code !== 0) return code;
    }
    status = 0;
    return status;
  } finally {
    for (const name of ['coverage', 'playwright-report', 'test-results']) {
      const source = join(root, 'apps/admin-portal', name);
      if (existsSync(source))
        cpSync(source, join(evidence, name), { recursive: true });
    }
    writeFileSync(
      join(evidence, 'result.json'),
      JSON.stringify({ environment, results, passed: status === 0 }, null, 2),
      { mode: 0o600 },
    );
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  try {
    process.exitCode = check(process.cwd(), '/evidence');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
