import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { isAbsolute } from 'node:path';
import { command, main } from './upload-expiry.mjs';

test('fast runs application regression tests without Docker, browser acceptance or runtime options', () => {
  const [program, ...args] = command(['fast']);
  assert.ok(program.endsWith('/gradlew'));
  assert.deepEqual(args, [
    '--no-daemon',
    ':services:document-management:application:test',
  ]);
  let calls = 0;
  assert.equal(
    main(['fast'], (executable, arguments_, options) => {
      calls++;
      assert.equal(executable, program);
      assert.deepEqual(arguments_, args);
      assert.equal(options.shell, false);
      assert.equal(options.stdio, 'inherit');
      assert.ok(isAbsolute(options.cwd));
      return { status: 0 };
    }),
    0,
  );
  assert.equal(calls, 1);
});

test('acceptance explicitly selects genuine expiry and requires both owned runtime paths', () => {
  const result = command([
    'acceptance',
    '--security-directory',
    '/tmp/owned identity',
    '--runtime-env-file',
    '/tmp/owned env',
  ]);
  assert.equal(result[0], process.execPath);
  assert.ok(result[1].endsWith('/tools/runtime/identity-e2e.mjs'));
  assert.deepEqual(result.slice(2), [
    '--upload-limits-profile',
    'expiry',
    '--security-directory',
    '/tmp/owned identity',
    '--runtime-env-file',
    '/tmp/owned env',
  ]);
  assert.deepEqual(
    command([
      'acceptance',
      '--runtime-env-file',
      '/tmp/owned env',
      '--security-directory',
      '/tmp/owned identity',
    ]),
    result,
  );
});

test('invalid selection fails before launching any subprocess', () => {
  for (const args of [
    [],
    ['all'],
    ['fast', '--upload-limits-profile', 'expiry'],
    ['acceptance'],
    ['acceptance', '--security-directory', '/tmp/a'],
    ['acceptance', '--security-directory'],
    ['acceptance', '--security-directory', '--runtime-env-file'],
    [
      'acceptance',
      '--security-directory',
      '/tmp/a',
      '--security-directory',
      '/tmp/b',
    ],
    ['acceptance', '--unknown', '/tmp/a'],
    ['acceptance', '--security-directory', ''],
  ]) {
    assert.throws(() =>
      main(args, () => {
        assert.fail('Must not launch');
      }),
    );
  }
});

test('subprocess failure, signals and launch failures cannot report success', () => {
  for (const [result, expected] of [
    [{ status: 3 }, 3],
    [{ status: 0, signal: 'SIGTERM' }, 1],
    [{ status: null }, 1],
    [{ error: new Error('launch failed') }, 1],
  ]) {
    assert.equal(
      main(['fast'], () => result),
      expected,
    );
  }
});

test('CLI invalid selection exits nonzero with no runtime execution', () => {
  const result = spawnSync(
    process.execPath,
    [new URL('./upload-expiry.mjs', import.meta.url).pathname, 'invalid'],
    { encoding: 'utf8' },
  );
  assert.equal(result.status, 1);
  assert.match(result.stderr, /real 60-minute lifetime/);
});
