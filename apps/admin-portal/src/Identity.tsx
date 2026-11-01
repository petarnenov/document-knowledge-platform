import {
  Alert,
  Anchor,
  Button,
  NativeSelect,
  Paper,
  Title,
} from '@mantine/core';
import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { sessionKey } from './session-key';
import {
  getContext,
  getSession,
  IdentityRequestError,
  type Service,
} from './identity-api';

import { useIdentityCommands } from './features/identity/useIdentityCommands';

const services: { id: Service; label: string }[] = [
  { id: 'dm', label: 'Document Management' },
  { id: 'kb', label: 'Knowledge Base' },
  { id: 'search', label: 'Knowledge Search' },
];

export function Identity() {
  const commands = useIdentityCommands();
  const sessionQuery = useQuery({
    queryKey: sessionKey,
    queryFn: ({ signal }) => getSession(signal),
  });
  // The opaque tag scopes temporary form/navigation selections; session data stays in Query.
  const [selection, setSelection] = useState<{
    scope: unknown;
    tenant: string | null;
    service: Service | null;
  }>({ scope: null, tenant: null, service: null });
  const session = sessionQuery.data;
  const tenantChoice = selection.scope === session ? selection.tenant : null;
  const activeService =
    selection.scope === session && !sessionQuery.error
      ? selection.service
      : null;
  const tenant = tenantChoice ?? session?.selectedTenant ?? '';
  function setTenant(value: string) {
    setSelection((previous) => ({
      scope: session,
      tenant: value,
      service: previous.scope === session ? previous.service : null,
    }));
  }
  function setService(value: Service | null) {
    setSelection((previous) => ({
      scope: session,
      tenant: previous.scope === session ? previous.tenant : null,
      service: value,
    }));
  }
  const contextQuery = useQuery({
    queryKey: [
      'context',
      session?.issuer,
      session?.subject,
      session?.selectedTenant,
      session?.csrfToken,
      activeService,
    ],
    queryFn: ({ signal }) => getContext(activeService!, session!, signal),
    enabled: !!activeService && !!session,
  });
  const context =
    activeService && !contextQuery.error ? contextQuery.data : null;
  const failure = commands.error ?? (activeService ? contextQuery.error : null);
  const error = failure
    ? failure instanceof IdentityRequestError && failure.status === 401
      ? 'Your session has expired. Sign in again to continue.'
      : failure instanceof IdentityRequestError && failure.status === 403
        ? 'This action was denied. Your tenant selection has not changed.'
        : 'Identity services are unavailable. Please try again.'
    : null;
  const busy = commands.busy || (!!activeService && contextQuery.isFetching);
  useEffect(() => {
    if (
      session &&
      activeService &&
      contextQuery.error instanceof IdentityRequestError &&
      contextQuery.error.status === 401
    )
      void commands.expire(session);
  }, [session, activeService, contextQuery.error, commands]);
  const state =
    session && !sessionQuery.error
      ? { kind: 'authenticated' as const, session }
      : {
          kind:
            session === null
              ? ('anonymous' as const)
              : sessionQuery.isPending
                ? ('loading' as const)
                : sessionQuery.error instanceof IdentityRequestError &&
                    sessionQuery.error.status === 401
                  ? ('anonymous' as const)
                  : ('unavailable' as const),
        };
  function retry() {
    commands.reset();
    setService(null);
    void sessionQuery.refetch();
  }

  return (
    <>
      <p className="eyebrow">DKP / IDENTITY</p>
      <Title order={1}>Your workspace access.</Title>
      <p className="lead">
        Sign in and choose a tenant to check your verified service context.
        Metadata-only drafts are available from Documents; knowledge workflows
        remain planned.
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
                onClick={() => {
                  setService(null);
                  commands.execute(state.session, { kind: 'logout' });
                }}
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
                setService(null);
                commands.execute(state.session, { kind: 'tenant', tenant });
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
                  onClick={() => {
                    commands.reset();
                    setService(service.id);
                    if (activeService === service.id)
                      void contextQuery.refetch();
                  }}
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
