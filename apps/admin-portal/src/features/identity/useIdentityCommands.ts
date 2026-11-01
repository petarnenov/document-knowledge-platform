import { useEffect, useId, useRef } from 'react';
import {
  useMutation,
  useIsMutating,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';
import {
  IdentityRequestError,
  logout,
  selectTenant,
  type SessionView,
} from '../../identity-api';
import { sessionKey } from '../../session-key';
import {
  beginTransition,
  finishTransition,
  isTransitionUncertain,
  ownsTransition,
  uncertainTransition,
  useSessionAuthority,
} from './authority';

type Command =
  { kind: 'tenant'; tenant: string } | { kind: 'logout' } | { kind: 'expire' };
type Request = {
  invocationId: number;
  command: Command;
  session: SessionView;
  controller: AbortController;
};

function current(client: QueryClient, session: SessionView) {
  return (
    client.getQueryData(sessionKey) === session &&
    !client.getQueryState(sessionKey)?.error
  );
}

/** Cancel a captured set only, then recheck authority before removing or changing data. */
export async function replaceSession(
  client: QueryClient,
  session: SessionView,
  replacement: SessionView | null,
  valid: () => boolean,
  commit: (replacement: SessionView | null) => void,
) {
  if (!valid() || !current(client, session)) return;
  const obsolete = new Set(
    client
      .getQueryCache()
      .findAll({ predicate: (query) => query.queryKey[0] !== 'session' }),
  );
  await client.cancelQueries({ predicate: (query) => obsolete.has(query) });
  if (!valid() || !current(client, session)) return;
  for (const query of obsolete) client.getQueryCache().remove(query);
  commit(replacement);
}

export function useIdentityCommands() {
  const client = useQueryClient();
  const authority = useSessionAuthority();
  const pending = useIsMutating({ mutationKey: ['identity-command'] });
  const id = useId();
  const mounted = useRef(false);
  const active = useRef<Request | null>(null);
  const nextInvocationId = useRef(0);
  const committing = useRef(false);
  const commit = (replacement: SessionView | null) => {
    committing.current = true;
    try {
      client.setQueryData(sessionKey, replacement);
    } finally {
      committing.current = false;
    }
  };
  const mutation = useMutation<void, IdentityRequestError, number>({
    mutationKey: ['identity-command', id],
    gcTime: 0,
    retry: false,
    mutationFn: async (invocationId) => {
      const request = active.current;
      // Mutation.execute yields before mutationFn; bind queued work to its own invocation.
      if (!request || request.invocationId !== invocationId) return;
      const valid = () =>
        mounted.current &&
        active.current === request &&
        !request.controller.signal.aborted &&
        ownsTransition(client, request) &&
        current(client, request.session);
      try {
        if (!valid()) return;
        if (request.command.kind === 'expire')
          throw new IdentityRequestError(401);
        const replacement =
          request.command.kind === 'tenant'
            ? await selectTenant(
                request.session,
                request.command.tenant,
                request.controller.signal,
              )
            : (await logout(request.session, request.controller.signal), null);
        await replaceSession(
          client,
          request.session,
          replacement,
          valid,
          commit,
        );
      } catch (error: unknown) {
        if (!valid()) return;
        const failure =
          error instanceof IdentityRequestError
            ? error
            : new IdentityRequestError(503);
        if (failure.status === 401)
          await replaceSession(client, request.session, null, valid, commit);
        else if (failure.status !== 403 && failure.status !== 400)
          uncertainTransition(client, request);
        throw failure;
      } finally {
        finishTransition(client, request);
        if (active.current === request) active.current = null;
      }
    },
  });
  const { reset } = mutation;
  useEffect(() => {
    mounted.current = true;
    const remove = () => {
      for (const entry of client
        .getMutationCache()
        .findAll({ mutationKey: ['identity-command', id], exact: true }))
        client.getMutationCache().remove(entry);
    };
    const unsubscribe = client.getQueryCache().subscribe((event) => {
      if (
        event.type !== 'updated' ||
        event.query.queryKey[0] !== 'session' ||
        committing.current
      )
        return;
      const request = active.current;
      if (request && current(client, request.session)) return;
      if (!request && isTransitionUncertain(client)) return;
      if (request) uncertainTransition(client, request);
      request?.controller.abort();
      active.current = null;
      reset();
      remove();
    });
    return () => {
      mounted.current = false;
      unsubscribe();
      if (active.current) uncertainTransition(client, active.current);
      active.current?.controller.abort();
      active.current = null;
      reset();
      remove();
    };
  }, [client, id, reset]);
  function execute(session: SessionView, command: Command) {
    if (active.current || !current(client, session)) return;
    const invocationId = ++nextInvocationId.current;
    const request = {
      invocationId,
      session,
      command,
      controller: new AbortController(),
    };
    if (!beginTransition(client, session, request)) return;
    active.current = request;
    mutation.mutate(invocationId);
  }
  return {
    busy: authority.blocked || pending > 0 || mutation.isPending,
    error: mutation.error,
    reset,
    execute,
    expire: (session: SessionView) => execute(session, { kind: 'expire' }),
  };
}
