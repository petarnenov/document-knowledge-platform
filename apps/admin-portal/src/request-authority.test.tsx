import { expect, it, vi } from 'vitest';
import { AuthorityChangedError, withAuthority } from './request-authority';

it('rejects a captured obsolete action before any I/O', async () => {
  const io = vi.fn();
  const guard = {
    signal: new AbortController().signal,
    assertCurrent: () => {
      throw new AuthorityChangedError();
    },
  };
  await expect(withAuthority(guard, io)).rejects.toBeInstanceOf(
    AuthorityChangedError,
  );
  expect(io).not.toHaveBeenCalled();
});

it.each([true, false])(
  'suppresses obsolete asynchronous success/failure (success=%s)',
  async (success) => {
    let current = true;
    const guard = {
      signal: new AbortController().signal,
      assertCurrent: () => {
        if (!current) throw new AuthorityChangedError();
      },
    };
    await expect(
      withAuthority(guard, async () => {
        current = false;
        if (!success) throw new Error('obsolete private diagnostic');
        return 'obsolete protected result';
      }),
    ).rejects.toBeInstanceOf(AuthorityChangedError);
  },
);

it('preserves current results, failures and ordinary transport signals', async () => {
  const signal = new AbortController().signal;
  const guard = { signal, assertCurrent: vi.fn() };
  await expect(withAuthority(guard, async (seen) => seen)).resolves.toBe(
    signal,
  );
  expect(guard.assertCurrent).toHaveBeenCalledTimes(2);
  const failure = new Error('bounded failure');
  await expect(
    withAuthority(guard, async () => {
      throw failure;
    }),
  ).rejects.toBe(failure);
  await expect(withAuthority(signal, async () => 1)).resolves.toBe(1);
});
