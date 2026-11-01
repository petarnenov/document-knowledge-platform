import { Uploads } from './Uploads';
import { DocumentLibrary } from './features/documents/DocumentLibrary';
import { DocumentLifecycle } from './features/documents/DocumentLifecycle';
import {
  Alert,
  Anchor,
  Button,
  NativeSelect,
  Paper,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { z } from 'zod';
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
  const [params] = useSearchParams();
  const initialDocumentId = params.get('documentId');
  const initialCreate = params.get('view') === 'create';
  const session = useQuery({
    queryKey: sessionKey,
    queryFn: ({ signal }) => getSession(signal),
  });
  if (session.isPending) return <p role="status">Checking your session…</p>;
  if (!session.data || session.error)
    return (
      <section>
        <Title order={1}>Documents</Title>
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
        <Title order={1}>Documents</Title>
        <p>Choose a tenant before opening documents.</p>
        <Link to="/identity">Workspace access</Link>
      </section>
    );
  if (
    initialDocumentId !== null &&
    !z.uuid().safeParse(initialDocumentId).success
  )
    return (
      <section>
        <Title order={1}>Documents</Title>
        <p role="alert">
          This document is unavailable or you do not have access.
        </p>
        <Link to="/documents">Back to library</Link>
      </section>
    );
  return (
    <DraftWorkspace
      key={[
        session.data.issuer,
        session.data.subject,
        session.data.selectedTenant,
        session.data.csrfToken,
        initialDocumentId,
        initialCreate,
      ].join('|')}
      session={session.data}
      initialDocumentId={initialDocumentId}
      initialCreate={initialCreate}
    />
  );
}
function DraftWorkspace({
  session,
  initialDocumentId,
  initialCreate,
}: {
  session: SessionView;
  initialDocumentId: string | null;
  initialCreate: boolean;
}) {
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
  const [selected, setSelected] = useState<string | null>(initialDocumentId);
  const [view, setView] = useState<'library' | 'create' | 'detail' | 'upload'>(
    initialDocumentId ? 'detail' : initialCreate ? 'create' : 'library',
  );
  const [filter, setFilter] = useState('');
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
    onSuccess: async (result, variables) => {
      if (variables.kind === 'create') {
        setView('library');
        setFilter('');
      }
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
        <Title order={1}>Documents</Title>
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
    setView('detail');
    setShowAcl(false);
    setShowHolds(false);
    setReceipt(null);
    mutation.reset();
  }
  const visible =
    page.data?.items.filter((draft) =>
      `${draft.metadata.title} ${draft.metadata.description} ${draft.documentId}`
        .toLowerCase()
        .includes(filter.toLowerCase()),
    ) ?? [];
  return (
    <section className="documents" aria-busy={mutation.isPending}>
      <div className="breadcrumbs">
        {view === 'library' ? (
          'Workspace / Documents'
        ) : (
          <>
            <Anchor
              component="button"
              size="xs"
              aria-label="Back to library"
              onClick={() => setView('library')}
            >
              Documents
            </Anchor>{' '}
            /{' '}
            {view === 'create'
              ? 'Create'
              : view === 'upload'
                ? 'Upload'
                : detail.isSuccess
                  ? detail.data.documentId
                  : 'Draft detail'}
          </>
        )}
      </div>
      <Title order={1}>
        {view === 'library'
          ? 'Document Library'
          : view === 'create'
            ? 'Create document draft'
            : view === 'upload'
              ? 'Add immutable version'
              : detail.isSuccess
                ? detail.data.metadata.title
                : 'Draft detail'}
      </Title>
      {receipt && <p role="status">{receipt}</p>}
      {failures.filter(Boolean).map((error, index) => (
        <Alert role="alert" key={index} variant="outline" color="red">
          {documentError(error)}
        </Alert>
      ))}
      {view === 'library' && (
        <>
          <div className="topline document-toolbar">
            <div className="grow">
              <TextInput
                aria-label="Search documents on this page"
                placeholder="Search documents"
                value={filter}
                onChange={(event) => setFilter(event.currentTarget.value)}
              />
            </div>
            <Button
              variant="filled"
              onClick={() => {
                setView('create');
                setSelected(null);
                setTitle('');
                setDescription('');
                mutation.reset();
                setReceipt(null);
              }}
            >
              <span aria-hidden="true" style={{ whiteSpace: 'pre' }}>
                ＋{' '}
              </span>
              New document
            </Button>
          </div>
          <section
            className="card document-library-card"
            aria-label="Accessible drafts"
          >
            {page.isPending && <p role="status">Loading drafts…</p>}
            {page.isSuccess &&
              (filter && !visible.length ? (
                <p className="muted">No matching documents on this page.</p>
              ) : (
                <DocumentLibrary
                  drafts={visible}
                  selected={selected}
                  onOpen={open}
                />
              ))}
          </section>
          <p className="hint">
            Search applies only to accessible drafts on this page. Workspace:{' '}
            {session.selectedTenant}.
          </p>
          <div className="actions">
            {page.data?.nextCursor && (
              <Button
                variant="default"
                onClick={() => {
                  setCursor(page.data?.nextCursor ?? undefined);
                  setSelected(null);
                  setFilter('');
                }}
              >
                Next page
              </Button>
            )}
            <Button
              variant="default"
              onClick={() => {
                setCursor(undefined);
                setFilter('');
                void page.refetch();
              }}
            >
              Refresh drafts
            </Button>
          </div>
        </>
      )}
      {view === 'create' && (
        <Paper
          component="form"
          className="card"
          onSubmit={(event) => submit(event, 'create')}
        >
          <label htmlFor="draft-title">Title</label>
          <TextInput
            id="draft-title"
            required
            maxLength={256}
            value={title}
            placeholder="Enter document title"
            onChange={(event) => setTitle(event.currentTarget.value)}
          />
          <label htmlFor="draft-collection">Collection (metadata only)</label>
          <NativeSelect
            id="draft-collection"
            disabled
            data={['Unavailable']}
            aria-describedby="draft-collection-hint"
          />
          <p id="draft-collection-hint" className="hint">
            Collection metadata is unavailable in the current draft contract. No
            collection membership or access is granted.
          </p>
          <label htmlFor="draft-classification">Classification</label>
          <TextInput
            id="draft-classification"
            readOnly
            value="development-restricted"
            aria-describedby="draft-classification-hint"
          />
          <p id="draft-classification-hint" className="hint">
            Fixed by the current contract at classification policy revision 1.
            Creating a draft does not grant policy permissions.
          </p>
          <label htmlFor="draft-description">Description</label>
          <Textarea
            id="draft-description"
            maxLength={4096}
            value={description}
            onChange={(event) => setDescription(event.currentTarget.value)}
          />
          <p className="hint">
            Save metadata first. A draft has no file until a verified version is
            recorded.
          </p>
          <Button variant="filled" type="submit" disabled={mutation.isPending}>
            Create draft
          </Button>
        </Paper>
      )}
      {(view === 'detail' || view === 'upload') && (
        <>
          {detail.isPending && <p role="status">Checking draft access…</p>}
          {detail.isSuccess && (
            <>
              {view === 'detail' ? (
                <>
                  <DocumentLifecycle
                    draft={detail.data}
                    actions={
                      <div className="toolbar">
                        <Button
                          variant="filled"
                          onClick={() => setView('upload')}
                        >
                          Upload version
                        </Button>
                        <Button variant="default" disabled>
                          Processing
                        </Button>
                        <Button
                          variant="default"
                          disabled
                          title="Governance is unavailable."
                        >
                          Governance
                        </Button>
                        <Button variant="default" disabled>
                          Preview
                        </Button>
                        <Button
                          variant="default"
                          onClick={() => {
                            void detail.refetch();
                          }}
                        >
                          Refresh document
                        </Button>
                      </div>
                    }
                  />
                  <details className="card">
                    <summary>Advanced document controls</summary>
                    <p className="hint">
                      Access changes and legal holds are validated by the
                      server. Retention is unresolved; destruction is forbidden.
                      Legal holds have no automatic expiry.
                    </p>
                    <Button
                      type="button"
                      onClick={() => {
                        setTitle(detail.data.metadata.title);
                        setDescription(detail.data.metadata.description);
                      }}
                    >
                      Use current metadata
                    </Button>
                    <form onSubmit={(event) => submit(event, 'metadata')}>
                      <h3>Update metadata</h3>
                      <label htmlFor="metadata-title">Title</label>
                      <TextInput
                        id="metadata-title"
                        required
                        maxLength={256}
                        value={title}
                        onChange={(e) => setTitle(e.target.value)}
                      />
                      <label htmlFor="metadata-description">Description</label>
                      <Textarea
                        id="metadata-description"
                        maxLength={4096}
                        value={description}
                        onChange={(e) => setDescription(e.target.value)}
                      />
                      <Button
                        type="submit"
                        disabled={mutation.isPending || !title}
                      >
                        Save metadata
                      </Button>
                    </form>
                    <Button type="button" onClick={() => setShowAcl(true)}>
                      Inspect access rules
                    </Button>
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
                      <NativeSelect
                        id="acl-mode"
                        value={mode}
                        onChange={(e) => {
                          setMode(e.target.value as AclCommand['mode']);
                          setOperation('REMOVE_ALLOW');
                        }}
                      >
                        <option value="RESTRICT">Restrict my access</option>
                        <option value="DELEGATE">
                          Delegate within my ceiling
                        </option>
                      </NativeSelect>
                      {mode === 'DELEGATE' && (
                        <>
                          <label htmlFor="target-issuer">Target issuer</label>
                          <TextInput
                            id="target-issuer"
                            type="url"
                            required
                            maxLength={2048}
                            value={targetIssuer}
                            onChange={(e) => setTargetIssuer(e.target.value)}
                          />
                          <label htmlFor="target-subject">Target subject</label>
                          <TextInput
                            id="target-subject"
                            required
                            maxLength={256}
                            value={targetSubject}
                            onChange={(e) => setTargetSubject(e.target.value)}
                          />
                        </>
                      )}
                      <label htmlFor="acl-operation">Rule operation</label>
                      <NativeSelect
                        id="acl-operation"
                        value={operation}
                        onChange={(e) =>
                          setOperation(e.target.value as typeof operation)
                        }
                      >
                        {(mode === 'RESTRICT'
                          ? ['REMOVE_ALLOW', 'ADD_DENY']
                          : [
                              'ADD_ALLOW',
                              'REMOVE_ALLOW',
                              'ADD_DENY',
                              'REMOVE_DENY',
                            ]
                        ).map((value) => (
                          <option key={value}>{value}</option>
                        ))}
                      </NativeSelect>
                      <label htmlFor="acl-action">Document action</label>
                      <NativeSelect
                        id="acl-action"
                        value={action}
                        onChange={(e) =>
                          setAction(e.target.value as typeof action)
                        }
                      >
                        {actions.map((value) => (
                          <option key={value}>{value}</option>
                        ))}
                      </NativeSelect>
                      <Button type="submit" disabled={mutation.isPending}>
                        Apply access change
                      </Button>
                    </form>
                    <Button type="button" onClick={() => setShowHolds(true)}>
                      Inspect legal holds
                    </Button>
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
                      <Textarea
                        id="hold-reason"
                        required
                        maxLength={1024}
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                      />
                      <Button type="submit" disabled={mutation.isPending}>
                        Place hold
                      </Button>
                    </form>
                  </details>
                </>
              ) : (
                <>
                  <p className="sub">{detail.data.metadata.title}</p>
                  <Anchor
                    component="button"
                    size="sm"
                    onClick={() => setView('detail')}
                  >
                    Back to document
                  </Anchor>
                  <Uploads
                    key={selected!}
                    documentId={selected!}
                    revision={detail.data.revision}
                    session={session}
                  />
                </>
              )}
            </>
          )}
        </>
      )}
    </section>
  );
}
