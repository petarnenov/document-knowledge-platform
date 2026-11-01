import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import * as api from '../../identity-api';
import { AuthorityChangedError } from '../../request-authority';
import { sessionKey } from '../../session-key';
import {
  beginTransition,
  finishTransition,
  recoverAuthority,
  uncertainTransition,
  useSessionAuthority,
} from './authority';
import { useIdentityCommands } from './useIdentityCommands';

const origin: api.SessionView = {
  issuer: 'https://idp.test',
  subject: 'synthetic',
  displayName: 'Synthetic',
  tenants: ['a', 'b'],
  selectedTenant: 'a',
  csrfToken: 'synthetic-a',
};
const fresh = { ...origin, selectedTenant: 'b', csrfToken: 'synthetic-b' };
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  client.setQueryData(sessionKey, origin);
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const hook = renderHook(
    () => useSessionAuthority(client.getQueryData<api.SessionView>(sessionKey)),
    { wrapper },
  );
  return { client, wrapper, ...hook };
}
afterEach(() => vi.restoreAllMocks());

it('blocks captured query functions and action closures, aborts reads, and rejects their late results', async () => {
  const { client, result } = setup();
  const read = deferred<string>();
  const run = result.current.run;
  let signal!: AbortSignal;
  const started = run(new AbortController().signal, (guard) => {
    signal = guard.signal;
    return read.promise;
  });
  const failed = expect(started).rejects.toBeInstanceOf(AuthorityChangedError);
  const owner = {};
  act(() => {
    expect(beginTransition(client, origin, owner)).toBe(true);
  });
  expect(signal.aborted).toBe(true);
  const io = vi.fn();
  await expect(run(new AbortController().signal, io)).rejects.toBeInstanceOf(
    AuthorityChangedError,
  );
  await expect(
    result.current.run(new AbortController().signal, io),
  ).rejects.toBeInstanceOf(AuthorityChangedError);
  expect(io).not.toHaveBeenCalled();
  read.resolve('obsolete protected title');
  await failed;
  act(() => finishTransition(client, owner));
  await expect(run(new AbortController().signal, io)).rejects.toBeInstanceOf(
    AuthorityChangedError,
  );
  await expect(
    result.current.run(new AbortController().signal, async () => 'current'),
  ).resolves.toBe('current');
});

it('retains uncertainty across initiating-hook unmount and mutation removal; unchanged GET cannot authorize another surface', async () => {
  const command = deferred<api.SessionView>();
  const read = deferred<api.SessionView>();
  const select = vi.spyOn(api, 'selectTenant').mockReturnValue(command.promise);
  vi.spyOn(api, 'getSession').mockReturnValue(read.promise);
  const logout = vi.spyOn(api, 'logout').mockResolvedValue();
  const { client, result, wrapper } = setup();
  const first = renderHook(useIdentityCommands, { wrapper });
  const second = renderHook(useIdentityCommands, { wrapper });
  act(() =>
    first.result.current.execute(origin, { kind: 'tenant', tenant: 'b' }),
  );
  await waitFor(() => expect(select).toHaveBeenCalledOnce());
  first.unmount();
  expect(select.mock.calls[0]![2].aborted).toBe(true);
  expect(
    client.getMutationCache().findAll({ mutationKey: ['identity-command'] }),
  ).toHaveLength(0);
  act(() => second.result.current.execute(origin, { kind: 'logout' }));
  expect(logout).not.toHaveBeenCalled();
  await act(async () => read.resolve({ ...origin }));
  expect(result.current.uncertain).toBe(true);
  expect(second.result.current.busy).toBe(true);
  await act(async () => command.resolve(fresh));
  expect(client.getQueryData(sessionKey)).toEqual(origin);
  expect(result.current.uncertain).toBe(true);
  const protectedRead = vi.fn();
  await expect(
    result.current.run(new AbortController().signal, protectedRead),
  ).rejects.toBeInstanceOf(AuthorityChangedError);
  expect(protectedRead).not.toHaveBeenCalled();
  expect(client.getMutationCache().getAll()).toHaveLength(0);
  vi.spyOn(api, 'getSession').mockResolvedValue(fresh);
  await act(async () => recoverAuthority(client));
  expect(client.getQueryData(sessionKey)).toEqual(fresh);
  expect(result.current.blocked).toBe(false);
});

it.each([401, 503])(
  'keeps recovery failure %s fail closed and never restores protected data',
  async (status) => {
    vi.spyOn(api, 'getSession').mockRejectedValue(
      new api.IdentityRequestError(status),
    );
    const { client, result } = setup();
    client.setQueryData(['documents', 'old'], 'protected title');
    const owner = {};
    act(() => {
      beginTransition(client, origin, owner);
      uncertainTransition(client, owner);
    });
    await waitFor(() => expect(api.getSession).toHaveBeenCalledOnce());
    await waitFor(() =>
      expect(client.getMutationCache().getAll()).toHaveLength(0),
    );
    expect(client.getQueryData(['documents', 'old'])).toBeUndefined();
    expect(client.getQueryData(sessionKey)).toEqual(
      status === 401 ? null : origin,
    );
    expect(result.current.uncertain).toBe(status !== 401);
    await expect(
      result.current.run(new AbortController().signal, async () => 'protected'),
    ).rejects.toBeInstanceOf(AuthorityChangedError);
  },
);

it.each([true, false])(
  'ignores stale recovery success/failure after external fresh authority and preserves new scoped queries (success=%s)',
  async (success) => {
    const read = deferred<api.SessionView>();
    vi.spyOn(api, 'getSession').mockReturnValue(read.promise);
    const { client, result } = setup();
    const owner = {};
    act(() => {
      beginTransition(client, origin, owner);
      uncertainTransition(client, owner);
    });
    await waitFor(() => expect(api.getSession).toHaveBeenCalledOnce());
    const newer = { ...fresh, csrfToken: 'synthetic-c' };
    act(() => {
      client.setQueryData(sessionKey, newer);
      client.setQueryData(
        [
          'documents',
          newer.issuer,
          newer.subject,
          newer.selectedTenant,
          newer.csrfToken,
        ],
        'fresh protected title',
      );
    });
    await act(async () => {
      if (success) read.resolve(fresh);
      else read.reject(new api.IdentityRequestError(401));
    });
    expect(client.getQueryData(sessionKey)).toEqual(newer);
    expect(
      client.getQueryData([
        'documents',
        newer.issuer,
        newer.subject,
        newer.selectedTenant,
        newer.csrfToken,
      ]),
    ).toBe('fresh protected title');
    expect(result.current.blocked).toBe(false);
  },
);

it('rejects an aborted protected request even if the session itself has not changed', async () => {
  const { result } = setup();
  const control = new AbortController();
  const io = vi.fn();
  control.abort();
  await expect(result.current.run(control.signal, io)).rejects.toBeInstanceOf(
    AuthorityChangedError,
  );
  expect(io).not.toHaveBeenCalled();
});

it('does not accept a changed local tenant or another issuer as reconciliation proof', async () => {
  vi.spyOn(api, 'getSession').mockResolvedValue({
    ...origin,
    selectedTenant: 'b',
  });
  const { client, result } = setup();
  const owner = {};
  act(() => {
    beginTransition(client, origin, owner);
    uncertainTransition(client, owner);
  });
  await waitFor(() =>
    expect(client.getMutationCache().getAll()).toHaveLength(0),
  );
  expect(result.current.uncertain).toBe(true);
  vi.mocked(api.getSession).mockResolvedValue({
    ...fresh,
    issuer: 'https://different.test',
  });
  await act(async () => recoverAuthority(client));
  expect(result.current.uncertain).toBe(true);
  expect(client.getQueryData(sessionKey)).toEqual(origin);
});
