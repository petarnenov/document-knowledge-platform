import { useEffect, useRef, useState } from 'react';
import {
  getContext,
  getSession,
  IdentityRequestError,
  logout,
  selectTenant,
  type SecurityContext,
  type Service,
  type SessionView,
} from './identity-api';

type IdentityState =
  | { kind: 'loading' | 'anonymous' | 'unavailable' }
  | { kind: 'authenticated'; session: SessionView };

const services: { id: Service; label: string }[] = [
  { id: 'dm', label: 'Document Management' },
  { id: 'kb', label: 'Knowledge Base' },
  { id: 'search', label: 'Knowledge Search' },
];

export function Identity() {
  const [state, setState] = useState<IdentityState>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [tenant, setTenant] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [context, setContext] = useState<SecurityContext | null>(null);
  const controller = useRef<AbortController | null>(null);

  useEffect(() => {
    const active = new AbortController();
    controller.current = active;
    getSession(active.signal)
      .then((session) => {
        if (active.signal.aborted) return;
        setState({ kind: 'authenticated', session });
        setTenant(session.selectedTenant ?? '');
      })
      .catch((failure: unknown) => {
        if (active.signal.aborted) return;
        setState({
          kind:
            failure instanceof IdentityRequestError && failure.status === 401
              ? 'anonymous'
              : 'unavailable',
        });
      });
    return () => active.abort();
  }, [attempt]);

  async function perform(action: (signal: AbortSignal) => Promise<void>) {
    const active = controller.current;
    if (!active || active.signal.aborted) return;
    setBusy(true);
    setError(null);
    setContext(null);
    try {
      await action(active.signal);
    } catch (failure: unknown) {
      if (active.signal.aborted) return;
      if (failure instanceof IdentityRequestError && failure.status === 401) {
        setState({ kind: 'anonymous' });
        setTenant('');
        setError('Your session has expired. Sign in again to continue.');
      } else if (
        failure instanceof IdentityRequestError &&
        failure.status === 403
      ) {
        setError(
          'This action was denied. Your tenant selection has not changed.',
        );
      } else {
        setError('Identity services are unavailable. Please try again.');
      }
    } finally {
      if (!active.signal.aborted) setBusy(false);
    }
  }

  function retry() {
    setError(null);
    setState({ kind: 'loading' });
    setAttempt((value) => value + 1);
  }

  return (
    <>
      <p className="eyebrow">DKP / IDENTITY</p>
      <h1>Your workspace access.</h1>
      <p className="lead">
        Sign in and choose a tenant to check your verified service context.
        Document and knowledge actions are not yet available.
      </p>
      {error && (
        <p className="identity-error" role="alert">
          {error}
        </p>
      )}
      {state.kind === 'loading' && <p role="status">Checking your session…</p>}
      {state.kind === 'unavailable' && (
        <section className="info-card" aria-labelledby="unavailable-title">
          <h2 id="unavailable-title">Identity services are unavailable.</h2>
          <p>Your access could not be verified. Please try again.</p>
          <button className="identity-button" type="button" onClick={retry}>
            Retry session
          </button>
        </section>
      )}
      {state.kind === 'anonymous' && (
        <section className="info-card" aria-labelledby="signin-title">
          <h2 id="signin-title">Sign in to your workspace.</h2>
          <p>
            You are signed out. Your identity provider will verify your access.
          </p>
          <a className="primary-link" href="/oauth/login/keycloak">
            Sign in
          </a>
        </section>
      )}
      {state.kind === 'authenticated' && (
        <>
          <section className="info-card" aria-labelledby="account-title">
            <div className="section-heading">
              <h2 id="account-title">
                Signed in as {state.session.displayName}
              </h2>
              <button
                className="identity-button"
                type="button"
                disabled={busy}
                onClick={() =>
                  void perform(async (signal) => {
                    await logout(state.session, signal);
                    if (signal.aborted) return;
                    setState({ kind: 'anonymous' });
                    setTenant('');
                  })
                }
              >
                Sign out
              </button>
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
                  setState({ kind: 'authenticated', session });
                  setTenant(session.selectedTenant ?? '');
                });
              }}
            >
              <label htmlFor="tenant-selector">Tenant</label>
              <select
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
              </select>
              <button
                className="identity-button"
                type="submit"
                disabled={busy || !tenant}
              >
                Use tenant
              </button>
            </form>
            {state.session.tenants.length === 0 && (
              <p>No tenant access is available.</p>
            )}
          </section>
          <section
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
                <button
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
                      if (!signal.aborted) setContext(verified);
                    })
                  }
                >
                  Check {service.label} context
                </button>
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
          </section>
        </>
      )}
    </>
  );
}
