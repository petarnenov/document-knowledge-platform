import { useSyncExternalStore } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import {
  getSession,
  IdentityRequestError,
  type SessionView,
} from '../../identity-api';
import {
  AuthorityChangedError,
  withAuthority,
  type RequestAuthority,
} from '../../request-authority';
import { sessionKey } from '../../session-key';

type Transition = {
  phase: 'idle' | 'pending' | 'uncertain';
  generation: number;
  owner: object | null;
  origin: SessionView | null;
  controller: AbortController;
  listeners: Set<() => void>;
  recovering: number | null;
};

// Transient command coordination only. Authoritative session data remains in Query.
const transitions = new WeakMap<QueryClient, Transition>();
function state(client: QueryClient): Transition {
  let value = transitions.get(client);
  if (value) return value;
  value = {
    phase: 'idle',
    generation: 0,
    owner: null,
    origin: null,
    controller: new AbortController(),
    listeners: new Set(),
    recovering: null,
  };
  transitions.set(client, value);
  client.getQueryCache().subscribe((event) => {
    if (event.type !== 'updated' || event.query.queryKey[0] !== 'session')
      return;
    const control = state(client);
    if (control.phase === 'idle') return;
    const next = client.getQueryData<SessionView | null>(sessionKey);
    if (
      next === null ||
      (next && control.origin && fresh(control.origin, next))
    ) {
      // A session GET already in flight must not later overwrite installed fresh authority.
      change(control, 'idle');
      void client.cancelQueries(
        { queryKey: sessionKey, exact: true },
        { revert: false },
      );
    }
  });
  return value;
}
function fresh(origin: SessionView, next: SessionView) {
  return (
    origin.issuer !== next.issuer ||
    origin.subject !== next.subject ||
    origin.csrfToken !== next.csrfToken
  );
}
function change(control: Transition, phase: Transition['phase']) {
  control.controller.abort();
  control.controller = new AbortController();
  control.generation++;
  control.phase = phase;
  if (phase === 'idle') {
    control.owner = null;
    control.origin = null;
  }
  for (const listener of control.listeners) listener();
}
export function beginTransition(
  client: QueryClient,
  origin: SessionView,
  owner: object,
) {
  const control = state(client);
  if (
    control.phase !== 'idle' ||
    client.isMutating({ mutationKey: ['identity-command'] }) > 0 ||
    client.getQueryData(sessionKey) !== origin ||
    client.getQueryState(sessionKey)?.error
  )
    return false;
  control.owner = owner;
  control.origin = origin;
  change(control, 'pending');
  const obsolete = new Set(
    client.getQueryCache().findAll({
      predicate: (query) => query.queryKey[0] !== 'session',
    }),
  );
  void client.cancelQueries({ predicate: (query) => obsolete.has(query) });
  for (const query of obsolete) client.getQueryCache().remove(query);
  return true;
}
export function ownsTransition(client: QueryClient, owner: object) {
  const control = state(client);
  return control.phase === 'pending' && control.owner === owner;
}
export function isTransitionUncertain(client: QueryClient) {
  return state(client).phase === 'uncertain';
}
export function finishTransition(client: QueryClient, owner: object) {
  if (ownsTransition(client, owner)) change(state(client), 'idle');
}
export function uncertainTransition(client: QueryClient, owner: object) {
  if (!ownsTransition(client, owner)) return;
  change(state(client), 'uncertain');
  void recoverAuthority(client);
}

/** Reuse the canonical session query; reject stale/unchanged results before cache commit. */
export async function recoverAuthority(client: QueryClient) {
  const control = state(client);
  if (
    control.phase !== 'uncertain' ||
    control.recovering === control.generation ||
    !control.origin
  )
    return;
  const generation = control.generation;
  const origin = control.origin;
  const before = client.getQueryData(sessionKey);
  control.recovering = generation;
  const valid = () =>
    state(client).generation === generation &&
    state(client).phase === 'uncertain' &&
    client.getQueryData(sessionKey) === before;
  try {
    await client.cancelQueries(
      { queryKey: sessionKey, exact: true },
      { revert: false },
    );
    if (!valid()) return;
    await client.fetchQuery({
      queryKey: sessionKey,
      retry: false,
      staleTime: 0,
      queryFn: async ({ signal }) => {
        try {
          if (!valid()) throw new AuthorityChangedError();
          const next = await getSession(signal);
          if (!valid()) throw new AuthorityChangedError();
          if (
            next.issuer !== origin.issuer ||
            next.subject !== origin.subject ||
            next.csrfToken === origin.csrfToken
          )
            throw new IdentityRequestError(503);
          return next;
        } catch (error) {
          if (!valid()) throw new AuthorityChangedError();
          throw error instanceof IdentityRequestError
            ? error
            : new IdentityRequestError(503);
        }
      },
    });
  } catch (error) {
    if (
      valid() &&
      error instanceof IdentityRequestError &&
      error.status === 401
    )
      client.setQueryData(sessionKey, null);
    // Every other recovery failure leaves the transition fence closed.
  } finally {
    if (control.recovering === generation) control.recovering = null;
  }
}

export function requestAuthority(
  client: QueryClient,
  session: SessionView | null | undefined,
  generation: number,
  signal: AbortSignal,
): RequestAuthority {
  const control = state(client);
  const boundSignal = AbortSignal.any([signal, control.controller.signal]);
  return {
    signal: boundSignal,
    assertCurrent: () => {
      const current = state(client);
      if (
        boundSignal.aborted ||
        !session ||
        current.phase !== 'idle' ||
        current.generation !== generation ||
        client.getQueryData(sessionKey) !== session ||
        client.getQueryState(sessionKey)?.error
      )
        throw new AuthorityChangedError();
    },
  };
}

export function useSessionAuthority(session?: SessionView | null) {
  const client = useQueryClient();
  const control = state(client);
  const generation = useSyncExternalStore(
    (listener) => {
      control.listeners.add(listener);
      return () => {
        control.listeners.delete(listener);
      };
    },
    () => control.generation,
  );
  return {
    blocked: control.phase !== 'idle',
    uncertain: control.phase === 'uncertain',
    request: (signal: AbortSignal) =>
      requestAuthority(client, session, generation, signal),
    run: <T>(
      signal: AbortSignal,
      operation: (guard: RequestAuthority) => Promise<T>,
    ) => {
      const guard = requestAuthority(client, session, generation, signal);
      return withAuthority(guard, () => operation(guard));
    },
    recover: () => recoverAuthority(client),
  };
}
