import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import * as api from '../../identity-api';
import { sessionKey } from '../../session-key';
import { replaceSession, useIdentityCommands } from './useIdentityCommands';

const session: api.SessionView = {
  issuer: 'https://idp.test',
  subject: 'synthetic',
  displayName: 'Synthetic',
  tenants: ['a', 'b'],
  selectedTenant: 'a',
  csrfToken: 'synthetic-csrf-a',
};
const next = { ...session, selectedTenant: 'b', csrfToken: 'synthetic-csrf-b' };
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  client.setQueryData(sessionKey, session);
  const hook = renderHook(useIdentityCommands, {
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  });
  return { ...hook, client };
}
afterEach(() => vi.restoreAllMocks());

it('arbitrates competing commands from separate mounted surfaces in the same turn', async () => {
  const pending = deferred<api.SessionView>();
  const select = vi.spyOn(api, 'selectTenant').mockReturnValue(pending.promise);
  const logout = vi.spyOn(api, 'logout').mockResolvedValue();
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  client.setQueryData(sessionKey, session);
  const { result } = renderHook(
    () => ({ shell: useIdentityCommands(), identity: useIdentityCommands() }),
    {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    },
  );
  act(() => {
    result.current.shell.execute(session, { kind: 'tenant', tenant: 'b' });
    result.current.identity.execute(session, { kind: 'logout' });
  });
  await waitFor(() => expect(select).toHaveBeenCalledOnce());
  expect(logout).not.toHaveBeenCalled();
  expect(result.current.shell.busy).toBe(true);
  expect(result.current.identity.busy).toBe(true);
  expect(client.getMutationCache().getAll()).toHaveLength(1);
  await act(async () => pending.resolve(next));
  await waitFor(() => expect(result.current.identity.busy).toBe(false));
  expect(client.getQueryData(sessionKey)).toEqual(next);
});

it('uses a typed mutation without protected variables/data and commits only after purging captured queries', async () => {
  const pending = deferred<api.SessionView>();
  const select = vi.spyOn(api, 'selectTenant').mockReturnValue(pending.promise);
  const { result, client } = setup();
  client.setQueryData(['documents', 'old'], { title: 'old protected data' });
  act(() => result.current.execute(session, { kind: 'tenant', tenant: 'b' }));
  await waitFor(() => expect(result.current.busy).toBe(true));
  expect(client.getMutationCache().getAll()).toHaveLength(1);
  expect(client.getMutationCache().getAll()[0]?.state.variables).toBe(1);
  expect(client.getMutationCache().getAll()[0]?.state.data).toBeUndefined();
  act(() => result.current.execute(session, { kind: 'logout' }));
  expect(select).toHaveBeenCalledOnce();
  await act(async () => pending.resolve(next));
  await waitFor(() => expect(result.current.busy).toBe(false));
  expect(client.getQueryData(sessionKey)).toEqual(next);
  expect(client.getQueryData(['documents', 'old'])).toBeUndefined();
  expect(select).toHaveBeenCalledWith(session, 'b', expect.any(AbortSignal));
});

it('logs out through the real API boundary and clears scoped queries', async () => {
  const logout = vi.spyOn(api, 'logout').mockResolvedValue();
  const { result, client } = setup();
  client.setQueryData(['context', 'old'], { tenant: 'a' });
  act(() => result.current.execute(session, { kind: 'logout' }));
  await waitFor(() => expect(client.getQueryData(sessionKey)).toBeNull());
  expect(logout).toHaveBeenCalledWith(session, expect.any(AbortSignal));
  expect(client.getQueryData(['context', 'old'])).toBeUndefined();
});

it.each([401, 403, 503])(
  'keeps HTTP %s fail-closed semantics and does not claim a denied logout',
  async (status) => {
    vi.spyOn(api, 'logout').mockRejectedValue(
      new api.IdentityRequestError(status),
    );
    const { result, client } = setup();
    client.setQueryData(['documents', 'old'], { title: 'protected' });
    act(() => result.current.execute(session, { kind: 'logout' }));
    await waitFor(() => expect(result.current.error?.status).toBe(status));
    expect(client.getQueryData(sessionKey)).toEqual(
      status === 401 ? null : session,
    );
    // Starting any identity transition fences and purges old protected state.
    expect(client.getQueryData(['documents', 'old'])).toBeUndefined();
  },
);

it('normalizes unexpected errors without retaining their private text', async () => {
  vi.spyOn(api, 'logout').mockRejectedValue(
    new Error('private response details'),
  );
  const { result } = setup();
  act(() => result.current.execute(session, { kind: 'logout' }));
  await waitFor(() => expect(result.current.error?.status).toBe(503));
  expect(result.current.error?.message).not.toContain('private');
});

it.each(['tenant', 'logout'] as const)(
  'aborts %s on unmount, removes its mutation and ignores a late response',
  async (kind) => {
    vi.spyOn(api, 'getSession').mockResolvedValue(session);
    const pending = deferred<api.SessionView>();
    const select = vi
      .spyOn(api, 'selectTenant')
      .mockReturnValue(pending.promise);
    const logout = vi.spyOn(api, 'logout').mockImplementation(async () => {
      await pending.promise;
    });
    const { result, client, unmount } = setup();
    act(() =>
      result.current.execute(
        session,
        kind === 'tenant' ? { kind, tenant: 'b' } : { kind },
      ),
    );
    await waitFor(() => expect(result.current.busy).toBe(true));
    const signal =
      kind === 'tenant' ? select.mock.calls[0]![2] : logout.mock.calls[0]![1];
    unmount();
    expect(signal.aborted).toBe(true);
    expect(
      client.getMutationCache().findAll({ mutationKey: ['identity-command'] }),
    ).toHaveLength(0);
    for (const entry of client.getMutationCache().getAll()) {
      expect(typeof entry.state.variables).toBe('number');
      expect(entry.state.data).toBeUndefined();
    }
    await act(async () => pending.resolve(next));
    expect(client.getQueryData(sessionKey)).toEqual(session);
    await waitFor(() =>
      expect(client.getMutationCache().getAll()).toHaveLength(0),
    );
  },
);

it.each([true, false])(
  'ignores late success/401 after an external session change (success=%s)',
  async (success) => {
    const pending = deferred<void>();
    let signal!: AbortSignal;
    vi.spyOn(api, 'logout').mockImplementation(
      async (_session, requestSignal) => {
        signal = requestSignal;
        await pending.promise;
        if (!success) throw new api.IdentityRequestError(401);
      },
    );
    const { result, client } = setup();
    act(() => result.current.execute(session, { kind: 'logout' }));
    await waitFor(() => expect(result.current.busy).toBe(true));
    act(() => {
      client.setQueryData(sessionKey, next);
      client.setQueryData(['documents', 'fresh'], { title: 'fresh protected' });
    });
    expect(signal.aborted).toBe(true);
    await act(async () => pending.resolve());
    expect(client.getQueryData(sessionKey)).toEqual(next);
    expect(client.getQueryData(['documents', 'fresh'])).toEqual({
      title: 'fresh protected',
    });
    expect(result.current.error).toBeNull();
    expect(client.getMutationCache().getAll()).toHaveLength(0);
  },
);

it('rechecks session authority after asynchronous query cancellation before deleting anything', async () => {
  const client = new QueryClient();
  client.setQueryData(sessionKey, session);
  client.setQueryData(['documents', 'old'], { title: 'old' });
  const pause = deferred<void>();
  vi.spyOn(client, 'cancelQueries').mockReturnValue(pause.promise);
  const commit = vi.fn();
  const replacing = replaceSession(client, session, null, () => true, commit);
  client.setQueryData(sessionKey, next);
  client.setQueryData(['documents', 'fresh'], { title: 'fresh' });
  pause.resolve();
  await replacing;
  expect(commit).not.toHaveBeenCalled();
  expect(client.getQueryData(['documents', 'fresh'])).toEqual({
    title: 'fresh',
  });
});

it('rejects an obsolete invocation before issuing an API call', async () => {
  const logout = vi.spyOn(api, 'logout');
  const { result, client } = setup();
  act(() => client.setQueryData(sessionKey, next));
  act(() => result.current.execute(session, { kind: 'logout' }));
  expect(logout).not.toHaveBeenCalled();
  expect(client.getMutationCache().getAll()).toHaveLength(0);
  const commit = vi.fn();
  await replaceSession(client, session, null, () => false, commit);
  expect(commit).not.toHaveBeenCalled();
});

it('does not let an obsolete request completion clear a new command or its fresh result', async () => {
  const old = deferred<api.SessionView>();
  const third = { ...next, selectedTenant: 'a', csrfToken: 'synthetic-csrf-c' };
  vi.spyOn(api, 'selectTenant')
    .mockReturnValueOnce(old.promise)
    .mockResolvedValueOnce(third);
  const { result, client } = setup();
  act(() => result.current.execute(session, { kind: 'tenant', tenant: 'b' }));
  await waitFor(() => expect(result.current.busy).toBe(true));
  act(() => client.setQueryData(sessionKey, next));
  act(() =>
    result.current.execute(client.getQueryData<api.SessionView>(sessionKey)!, {
      kind: 'tenant',
      tenant: 'a',
    }),
  );
  await waitFor(() => expect(client.getQueryData(sessionKey)).toEqual(third));
  client.setQueryData(['documents', 'fresh-after-command'], {
    title: 'fresh result',
  });
  await act(async () => old.resolve(next));
  expect(client.getQueryData(sessionKey)).toEqual(third);
  expect(client.getQueryData(['documents', 'fresh-after-command'])).toEqual({
    title: 'fresh result',
  });
  expect(result.current.error).toBeNull();
});

it('does not let queued work adopt a newer request before either mutation function starts', async () => {
  const pending = deferred<api.SessionView>();
  const select = vi.spyOn(api, 'selectTenant').mockReturnValue(pending.promise);
  const { result, client } = setup();
  // One synchronous turn: Mutation.execute has not yet resumed after onMutate.
  act(() => {
    result.current.execute(session, { kind: 'tenant', tenant: 'b' });
    client.setQueryData(sessionKey, next);
    result.current.execute(client.getQueryData<api.SessionView>(sessionKey)!, {
      kind: 'tenant',
      tenant: 'a',
    });
  });
  await waitFor(() => expect(select).toHaveBeenCalledTimes(1));
  expect(select).toHaveBeenCalledWith(next, 'a', expect.any(AbortSignal));
  const third = { ...next, selectedTenant: 'a', csrfToken: 'synthetic-csrf-c' };
  await act(async () => pending.resolve(third));
  await waitFor(() => expect(client.getQueryData(sessionKey)).toEqual(third));
  expect(select).toHaveBeenCalledTimes(1);
});
