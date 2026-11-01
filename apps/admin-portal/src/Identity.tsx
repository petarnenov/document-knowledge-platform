import {
  Alert,
  Anchor,
  Button,
  NativeSelect,
  Paper,
  Title,
} from '@mantine/core';
import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { sessionKey } from './session-key';
import {
  getContext,
  getSession,
  IdentityRequestError,
  logout,
  selectTenant,
  type Service,
} from './identity-api';

const services: { id: Service; label: string }[] = [
  { id: 'dm', label: 'Document Management' },
  { id: 'kb', label: 'Knowledge Base' },
  { id: 'search', label: 'Knowledge Search' },
];

export function Identity() {
  const client = useQueryClient();
  const sessionQuery = useQuery({
    queryKey: sessionKey,
    queryFn: ({ signal }) => getSession(signal),
  });
  const [tenantChoice, setTenant] = useState<string | null>(null);
  const [signedOut, setSignedOut] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [service, setService] = useState<Service | null>(null);
  const session = sessionQuery.data;
  const tenant = tenantChoice ?? session?.selectedTenant ?? '';
  const contextQuery = useQuery({
    queryKey: [
      'context',
      session?.issuer,
      session?.subject,
      session?.selectedTenant,
      service,
    ],
    queryFn: ({ signal }) => getContext(service!, session!, signal),
    enabled: false,
  });
  const context = service ? contextQuery.data : null;
  const state =
    session && !signedOut && !sessionQuery.error
      ? { kind: 'authenticated' as const, session }
      : {
          kind: signedOut
            ? ('anonymous' as const)
            : sessionQuery.isPending
              ? ('loading' as const)
              : sessionQuery.error instanceof IdentityRequestError &&
                  sessionQuery.error.status === 401
                ? ('anonymous' as const)
                : ('unavailable' as const),
        };
  async function perform(action: (signal: AbortSignal) => Promise<void>) {
    setBusy(true);
    setError(null);
    setService(null);
    try {
      await action(new AbortController().signal);
    } catch (failure: unknown) {
      if (failure instanceof IdentityRequestError && failure.status === 401) {
        await client.cancelQueries();
        client.removeQueries({ predicate: (q) => q.queryKey[0] !== 'session' });
        client.setQueryData(sessionKey, null);
        setSignedOut(true);
        setError('Your session has expired. Sign in again to continue.');
      } else if (
        failure instanceof IdentityRequestError &&
        failure.status === 403
      )
        setError(
          'This action was denied. Your tenant selection has not changed.',
        );
      else setError('Identity services are unavailable. Please try again.');
    } finally {
      setBusy(false);
    }
  }
  function retry() {
    setSignedOut(false);
    setError(null);
    void sessionQuery.refetch();
  }

  return (
    <>
      <p className="eyebrow">DKP / IDENTITY</p>
      <Title order={1}>Your workspace access.</Title>
      <p className="lead">
        Sign in and choose a tenant to check your verified service context.
        Documents and knowledge collections are available after tenant
        selection.
      </p>
      {error && (
        <Alert
          className="identity-error"
          variant="outline"
          color="red"
          role="alert"
        >
          {error}
        </Alert>
      )}
      {state.kind === 'loading' && <p role="status">Checking your session…</p>}
      {state.kind === 'unavailable' && (
        <Paper
          component="section"
          className="info-card"
          aria-labelledby="unavailable-title"
        >
          <h2 id="unavailable-title">Identity services are unavailable.</h2>
          <p>Your access could not be verified. Please try again.</p>
          <Button className="identity-button" type="button" onClick={retry}>
            Retry session
          </Button>
        </Paper>
      )}
      {state.kind === 'anonymous' && (
        <Paper
          component="section"
          className="info-card"
          aria-labelledby="signin-title"
        >
          <h2 id="signin-title">Sign in to your workspace.</h2>
          <p>
            You are signed out. Your identity provider will verify your access.
          </p>
          <Anchor className="primary-link" href="/oauth/login/keycloak">
            Sign in
          </Anchor>
        </Paper>
      )}
      {state.kind === 'authenticated' && (
        <>
          <Paper
            component="section"
            className="info-card"
            aria-labelledby="account-title"
          >
            <div className="section-heading">
              <h2 id="account-title">
                Signed in as {state.session.displayName}
              </h2>
              <Button
                className="identity-button"
                type="button"
                disabled={busy}
                onClick={() =>
                  void perform(async (signal) => {
                    await logout(state.session, signal);
                    if (signal.aborted) return;
                    await client.cancelQueries();
                    client.removeQueries({
                      predicate: (q) => q.queryKey[0] !== 'session',
                    });
                    client.setQueryData(sessionKey, null);
                    setSignedOut(true);
                    setTenant('');
                  })
                }
              >
                Sign out
              </Button>
            </div>
            <dl className="identity-details">
              <dt>Identity provider</dt>
              <dd>{state.session.issuer}</dd>
              <dt>Subject</dt>
              <dd>{state.session.subject}</dd>
              <dt>Selected tenant</dt>
              <dd>{state.session.selectedTenant ?? 'No tenant selected'}</dd>
            </dl>
            <form
              className="tenant-form"
              onSubmit={(event) => {
                event.preventDefault();
                void perform(async (signal) => {
                  const session = await selectTenant(
                    state.session,
                    tenant,
                    signal,
                  );
                  if (signal.aborted) return;
                  await client.cancelQueries();
                  client.removeQueries({
                    predicate: (q) => q.queryKey[0] !== 'session',
                  });
                  client.setQueryData(sessionKey, session);
                  setTenant(session.selectedTenant ?? '');
                });
              }}
            >
              <label htmlFor="tenant-selector">Tenant</label>
              <NativeSelect
                id="tenant-selector"
                value={tenant}
                disabled={busy}
                onChange={(event) => setTenant(event.target.value)}
              >
                <option value="">Choose a tenant</option>
                {state.session.tenants.map((id) => (
                  <option key={id} value={id}>
                    {id}
                  </option>
                ))}
              </NativeSelect>
              <Button
                className="identity-button"
                type="submit"
                disabled={busy || !tenant}
              >
                Use tenant
              </Button>
            </form>
            {state.session.tenants.length === 0 && (
              <p>No tenant access is available.</p>
            )}
          </Paper>
          <Paper
            component="section"
            className="info-card"
            aria-labelledby="context-title"
            aria-busy={busy}
          >
            <h2 id="context-title">Verified service context</h2>
            <p>
              Each service checks your identity and selected tenant
              independently.
            </p>
            {!state.session.selectedTenant && (
              <p>Select a tenant before checking a service.</p>
            )}
            <div className="context-actions">
              {services.map((service) => (
                <Button
                  className="identity-button"
                  key={service.id}
                  type="button"
                  disabled={busy || !state.session.selectedTenant}
                  onClick={() =>
                    void perform(async (signal) => {
                      const verified = await getContext(
                        service.id,
                        state.session,
                        signal,
                      );
                      if (!signal.aborted) {
                        client.setQueryData(
                          [
                            'context',
                            state.session.issuer,
                            state.session.subject,
                            state.session.selectedTenant,
                            service.id,
                          ],
                          verified,
                        );
                        setService(service.id);
                      }
                    })
                  }
                >
                  Check {service.label} context
                </Button>
              ))}
            </div>
            {busy && <p role="status">Verifying access…</p>}
            {context && (
              <dl className="identity-details" aria-label="Verified context">
                <dt>Service</dt>
                <dd>{context.service}</dd>
                <dt>Identity provider</dt>
                <dd>{context.issuer}</dd>
                <dt>Subject</dt>
                <dd>{context.subject}</dd>
                <dt>Tenant</dt>
                <dd>{context.tenant}</dd>
              </dl>
            )}
          </Paper>
        </>
      )}
    </>
  );
}
