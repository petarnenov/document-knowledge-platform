import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getSession,
  IdentityRequestError,
  type SessionView,
} from './identity-api';
import { sessionKey } from './session-key';
import {
  actions,
  command,
  documentError,
  DocumentRequestError,
  inspectAcl,
  inspectDraft,
  inspectHolds,
  listDrafts,
  type AclCommand,
} from './document-api';

export function Documents() {
  const session = useQuery({
    queryKey: sessionKey,
    queryFn: ({ signal }) => getSession(signal),
  });
  if (session.isPending) return <p role="status">Checking your session…</p>;
  if (!session.data || session.error)
    return (
      <section>
        <h1>Documents</h1>
        <p role="alert">
          {session.error instanceof IdentityRequestError &&
          session.error.status !== 401
            ? 'Identity services are unavailable.'
            : 'Sign in and choose a tenant to manage drafts.'}
        </p>
        <Link to="/identity">Workspace access</Link>
      </section>
    );
  if (!session.data.selectedTenant)
    return (
      <section>
        <h1>Documents</h1>
        <p>Choose a tenant before opening documents.</p>
        <Link to="/identity">Workspace access</Link>
      </section>
    );
  return (
    <DraftWorkspace
      key={[
        session.data.issuer,
        session.data.subject,
        session.data.selectedTenant,
        session.data.csrfToken,
      ].join('|')}
      session={session.data}
    />
  );
}
function DraftWorkspace({ session }: { session: SessionView }) {
  const client = useQueryClient();
  const scope = [
    'documents',
    session.issuer,
    session.subject,
    session.selectedTenant,
    session.csrfToken,
  ] as const;
  const scopeText = JSON.stringify(scope);
  useEffect(() => {
    void client.cancelQueries({
      predicate: (q) =>
        q.queryKey[0] === 'documents' &&
        JSON.stringify(q.queryKey.slice(0, 5)) !== scopeText,
    });
    client.removeQueries({
      predicate: (q) =>
        q.queryKey[0] === 'documents' &&
        JSON.stringify(q.queryKey.slice(0, 5)) !== scopeText,
    });
  }, [client, scopeText]);
  const [cursor, setCursor] = useState<string | undefined>();
  const [selected, setSelected] = useState<string | null>(null);
  const [showAcl, setShowAcl] = useState(false);
  const [showHolds, setShowHolds] = useState(false);
  const [receipt, setReceipt] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [reason, setReason] = useState('');
  const [targetIssuer, setTargetIssuer] = useState(session.issuer);
  const [targetSubject, setTargetSubject] = useState(session.subject);
  const [mode, setMode] = useState<AclCommand['mode']>('RESTRICT');
  const [operation, setOperation] =
    useState<AclCommand['changes'][number]['operation']>('REMOVE_ALLOW');
  const [action, setAction] = useState<(typeof actions)[number]>(
    'document.inspect-draft',
  );
  const pendingKey = useRef<{ body: string; key: string } | null>(null);
  const page = useQuery({
    queryKey: [...scope, 'list', cursor ?? ''],
    queryFn: ({ signal }) => listDrafts(session, signal, cursor),
  });
  const detail = useQuery({
    queryKey: [...scope, 'detail', selected],
    queryFn: ({ signal }) => inspectDraft(selected!, session, signal),
    enabled: selected !== null,
  });
  const acl = useQuery({
    queryKey: [...scope, 'acl', selected],
    queryFn: ({ signal }) => inspectAcl(selected!, signal),
    enabled: selected !== null && showAcl,
  });
  const holds = useQuery({
    queryKey: [...scope, 'holds', selected],
    queryFn: ({ signal }) => inspectHolds(selected!, signal),
    enabled: selected !== null && showHolds,
  });
  const mutation = useMutation({
    mutationFn: async ({
      kind,
      body,
    }: {
      kind: 'create' | 'metadata' | 'acl' | 'hold';
      body: object;
    }) => {
      const canonical = JSON.stringify({
        kind,
        selected: kind === 'create' ? null : selected,
        body,
      });
      if (pendingKey.current?.body !== canonical)
        pendingKey.current = { body: canonical, key: crypto.randomUUID() };
      return command(
        session,
        new AbortController().signal,
        kind,
        body,
        kind === 'create' ? undefined : (selected ?? undefined),
        pendingKey.current.key,
      );
    },
    onSuccess: async (result) => {
      setReceipt(
        `Saved document ${result.documentId}, revision ${result.revision}.`,
      );
      pendingKey.current = null;
      // Drop protected values before reauthorization. Only previously successful queries are retried.
      const authorized = client
        .getQueryCache()
        .findAll({ queryKey: scope })
        .filter((q) => q.state.status === 'success')
        .map((q) => q.queryKey);
      await Promise.all(
        authorized.map((queryKey) =>
          client.resetQueries({ queryKey, exact: true }),
        ),
      );
    },
  });
  const failures = [
    page.error,
    detail.error,
    acl.error,
    holds.error,
    mutation.error,
  ];
  const expired = failures.some(
    (error) => error instanceof DocumentRequestError && error.status === 401,
  );
  useEffect(() => {
    if (expired) {
      void client.cancelQueries();
      client.removeQueries({ predicate: (q) => q.queryKey[0] !== 'session' });
      client.setQueryData(sessionKey, null);
    }
  }, [client, expired]);
  if (expired)
    return (
      <section>
        <h1>Documents</h1>
        <p role="alert">Your session has expired. Sign in again.</p>
        <Link to="/identity">Workspace access</Link>
      </section>
    );
  function submit(
    event: FormEvent,
    kind: 'create' | 'metadata' | 'acl' | 'hold',
  ) {
    event.preventDefault();
    setReceipt(null);
    const expectedRevision = detail.data?.revision;
    if (kind !== 'create' && expectedRevision === undefined) return;
    const body =
      kind === 'create'
        ? {
            metadata: { title, description },
            classification: 'development-restricted',
            classificationPolicyRevision: 1,
          }
        : kind === 'metadata'
          ? { expectedRevision, metadata: { title, description } }
          : kind === 'hold'
            ? { expectedRevision, reason }
            : {
                expectedRevision,
                mode,
                changes: [
                  {
                    operation,
                    principal:
                      mode === 'RESTRICT'
                        ? { issuer: session.issuer, subject: session.subject }
                        : { issuer: targetIssuer, subject: targetSubject },
                    action,
                  },
                ],
              };
    mutation.mutate({ kind, body });
  }
  function open(id: string) {
    setSelected(id);
    setShowAcl(false);
    setShowHolds(false);
    setReceipt(null);
    mutation.reset();
  }
  return (
    <section className="documents" aria-busy={mutation.isPending}>
      <p className="eyebrow">DKP / DOCUMENTS</p>
      <h1>Document drafts</h1>
      <p>
        Tenant: {session.selectedTenant}. Metadata only; uploading and
        publication are unavailable.
      </p>
      <p>
        Retention is unresolved; destruction is forbidden. Legal holds have no
        automatic expiry.
      </p>
      {receipt && <p role="status">{receipt}</p>}
      {failures.filter(Boolean).map((error, index) => (
        <p role="alert" key={index}>
          {documentError(error)}
        </p>
      ))}
      <form className="info-card" onSubmit={(event) => submit(event, 'create')}>
        <h2>Create a draft</h2>
        <label htmlFor="draft-title">Title</label>
        <input
          id="draft-title"
          required
          maxLength={256}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
        />
        <label htmlFor="draft-description">Description</label>
        <textarea
          id="draft-description"
          maxLength={4096}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
        />
        <button disabled={mutation.isPending} type="submit">
          Create draft
        </button>
      </form>
      <section className="info-card" aria-labelledby="draft-list">
        <h2 id="draft-list">Accessible drafts</h2>
        {page.isPending && <p role="status">Loading drafts…</p>}
        {page.isSuccess && (
          <>
            <ul>
              {page.data.items.map((draft) => (
                <li key={draft.documentId}>
                  <button type="button" onClick={() => open(draft.documentId)}>
                    {draft.metadata.title}
                  </button>
                </li>
              ))}
            </ul>
            {page.data.items.length === 0 && (
              <p>No accessible drafts on this page.</p>
            )}
            {page.data.nextCursor && (
              <button
                type="button"
                onClick={() => {
                  setCursor(page.data.nextCursor ?? undefined);
                  setSelected(null);
                }}
              >
                Next page
              </button>
            )}
          </>
        )}
        <button
          type="button"
          onClick={() => {
            setCursor(undefined);
            void page.refetch();
          }}
        >
          Refresh drafts
        </button>
      </section>
      {selected && (
        <section className="info-card">
          <h2>Draft detail</h2>
          <button
            type="button"
            onClick={() => {
              void detail.refetch();
            }}
          >
            Refresh document
          </button>
          {detail.isPending && <p role="status">Checking draft access…</p>}
          {detail.isSuccess && (
            <>
              <h3>{detail.data.metadata.title}</h3>
              <p>{detail.data.metadata.description}</p>
              <p>
                Revision {detail.data.revision} · DRAFT · development-restricted
              </p>
              <p>Retention: UNRESOLVED · Destruction forbidden</p>
              <button
                type="button"
                onClick={() => {
                  setTitle(detail.data.metadata.title);
                  setDescription(detail.data.metadata.description);
                }}
              >
                Use current metadata
              </button>
              <form onSubmit={(event) => submit(event, 'metadata')}>
                <h3>Update metadata</h3>
                <p>Uses the Title and Description fields above.</p>
                <button type="submit" disabled={mutation.isPending || !title}>
                  Save metadata
                </button>
              </form>
              <button type="button" onClick={() => setShowAcl(true)}>
                Inspect access rules
              </button>
              {showAcl && acl.isSuccess && (
                <ul aria-label="Access rules">
                  {acl.data.rules.map((rule, index) => (
                    <li key={index}>
                      {rule.principal.issuer} / {rule.principal.subject}:{' '}
                      {rule.action} {rule.effect}
                    </li>
                  ))}
                </ul>
              )}
              <form onSubmit={(event) => submit(event, 'acl')}>
                <h3>Change access</h3>
                <label htmlFor="acl-mode">Access mode</label>
                <select
                  id="acl-mode"
                  value={mode}
                  onChange={(e) => {
                    setMode(e.target.value as AclCommand['mode']);
                    setOperation('REMOVE_ALLOW');
                  }}
                >
                  <option value="RESTRICT">Restrict my access</option>
                  <option value="DELEGATE">Delegate within my ceiling</option>
                </select>
                {mode === 'DELEGATE' && (
                  <>
                    <label htmlFor="target-issuer">Target issuer</label>
                    <input
                      id="target-issuer"
                      type="url"
                      required
                      maxLength={2048}
                      value={targetIssuer}
                      onChange={(e) => setTargetIssuer(e.target.value)}
                    />
                    <label htmlFor="target-subject">Target subject</label>
                    <input
                      id="target-subject"
                      required
                      maxLength={256}
                      value={targetSubject}
                      onChange={(e) => setTargetSubject(e.target.value)}
                    />
                  </>
                )}
                <label htmlFor="acl-operation">Rule operation</label>
                <select
                  id="acl-operation"
                  value={operation}
                  onChange={(e) =>
                    setOperation(e.target.value as typeof operation)
                  }
                >
                  {(mode === 'RESTRICT'
                    ? ['REMOVE_ALLOW', 'ADD_DENY']
                    : ['ADD_ALLOW', 'REMOVE_ALLOW', 'ADD_DENY', 'REMOVE_DENY']
                  ).map((value) => (
                    <option key={value}>{value}</option>
                  ))}
                </select>
                <label htmlFor="acl-action">Document action</label>
                <select
                  id="acl-action"
                  value={action}
                  onChange={(e) => setAction(e.target.value as typeof action)}
                >
                  {actions.map((value) => (
                    <option key={value}>{value}</option>
                  ))}
                </select>
                <button type="submit" disabled={mutation.isPending}>
                  Apply access change
                </button>
              </form>
              <button type="button" onClick={() => setShowHolds(true)}>
                Inspect legal holds
              </button>
              {showHolds && holds.isSuccess && (
                <ul aria-label="Legal holds">
                  {holds.data.items.map((hold) => (
                    <li key={hold.holdId}>
                      {hold.reason} · Active · {hold.placedAt}
                    </li>
                  ))}
                </ul>
              )}
              <form onSubmit={(event) => submit(event, 'hold')}>
                <h3>Place a legal hold</h3>
                <label htmlFor="hold-reason">Hold reason</label>
                <textarea
                  id="hold-reason"
                  required
                  maxLength={1024}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
                <button type="submit" disabled={mutation.isPending}>
                  Place hold
                </button>
              </form>
            </>
          )}
        </section>
      )}
    </section>
  );
}
