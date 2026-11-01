import { Anchor, Button, Paper, Title } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getSession } from '../../identity-api';
import { useEffect } from 'react';
import { DocumentRequestError, listDrafts } from '../../document-api';
import { sessionKey } from '../../session-key';
import { useSessionAuthority } from '../identity/authority';

/** Dashboard layout follows v0.5; aggregate metrics require an authoritative API. */
export function HomeWorkspace() {
  const client = useQueryClient();
  const session = useQuery({
    queryKey: sessionKey,
    queryFn: ({ signal }) => getSession(signal),
  });
  const actor = session.error ? null : session.data;
  const authority = useSessionAuthority(actor);
  const recent = useQuery({
    queryKey: [
      'documents',
      actor?.issuer,
      actor?.subject,
      actor?.selectedTenant,
      actor?.csrfToken,
      'list',
      '',
    ],
    queryFn: ({ signal }) =>
      authority.run(signal, (guard) => listDrafts(actor!, guard)),
    enabled: Boolean(actor?.selectedTenant) && !authority.blocked,
  });
  const expired =
    recent.error instanceof DocumentRequestError && recent.error.status === 401;
  useEffect(() => {
    if (!expired) return;
    void client.cancelQueries({
      predicate: (query) => query.queryKey[0] !== 'session',
    });
    client.removeQueries({
      predicate: (query) => query.queryKey[0] !== 'session',
    });
    client.setQueryData(sessionKey, null);
  }, [client, expired]);
  return (
    <>
      <div className="breadcrumbs">Workspace / Home</div>
      <Title order={1}>Knowledge workspace</Title>
      <p className="muted small">
        A single environment for the document lifecycle.
      </p>
      <div className="grid" aria-label="Workspace metrics">
        {['Documents', 'Published', 'Review candidates', 'Legal holds'].map(
          (label) => (
            <Paper className="card" key={label}>
              <div className="muted small">{label}</div>
              <div className="number" aria-label={`${label}: unavailable`}>
                —
              </div>
              <div className="sub">Workspace total unavailable</div>
            </Paper>
          ),
        )}
      </div>
      <Paper
        className="card"
        component="section"
        aria-labelledby="quick-actions"
      >
        <Title order={2} id="quick-actions">
          Quick actions
        </Title>
        <div className="actions">
          <Button component={Link} to="/documents?view=create" variant="filled">
            ＋ New document
          </Button>
          <Button component={Link} to="/reviews" variant="default">
            ✓ Reviews
          </Button>
          <Button component={Link} to="/search" variant="default">
            ⌕ Search
          </Button>
        </div>
      </Paper>
      <Paper
        className="card"
        component="section"
        aria-labelledby="recent-documents"
      >
        <Title order={2} id="recent-documents">
          Recent documents
        </Title>
        {!actor?.selectedTenant ? (
          <p className="muted small">
            Sign in and choose a workspace to see accessible documents.{' '}
            <Anchor component={Link} to="/identity">
              Workspace access
            </Anchor>
          </p>
        ) : recent.isPending ? (
          <p role="status">Loading accessible documents…</p>
        ) : recent.error ? (
          <p role="alert">
            Documents are unavailable. Reopen workspace access or try again.
          </p>
        ) : (
          <>
            <p className="sub">
              Accessible drafts on the first page; recent ordering and workspace
              totals are unavailable.
            </p>
            {recent.data?.items.map((draft) => (
              <Anchor
                className="rowline"
                component={Link}
                key={draft.documentId}
                to={`/documents?documentId=${encodeURIComponent(draft.documentId)}`}
              >
                <span aria-hidden="true">▤</span>
                <div className="grow">
                  <strong>{draft.metadata.title}</strong>
                  <div className="sub">Draft · Revision {draft.revision}</div>
                </div>
                <span className="badge">Draft</span>
              </Anchor>
            ))}
            {recent.data?.items.length === 0 && (
              <p className="muted small">No accessible drafts on this page.</p>
            )}
          </>
        )}
      </Paper>
    </>
  );
}
