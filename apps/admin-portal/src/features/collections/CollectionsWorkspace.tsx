import { useState } from 'react';
import { Button, NativeSelect, Paper, TextInput, Title } from '@mantine/core';
import type { CollectionBoundary, CollectionsView } from './collection-view';

const unavailable = { kind: 'unavailable' } as const;
const boundaryCopy = {
  unavailable: 'Unavailable. This capability is not connected.',
  unknown: 'Unknown. Current access and data could not be verified.',
  denied: 'Unavailable. Access could not be verified for this request.',
  conflict:
    'Changed since it was checked. Refresh authoritative data before continuing.',
} as const;

function BoundaryNotice({
  state,
}: {
  state: Exclude<CollectionBoundary<unknown>, { kind: 'available' }>;
}) {
  return (
    <p className="hint" role="status">
      {boundaryCopy[state.kind]}
    </p>
  );
}

/** No transport, persistence, permissions or mutations live in this component.
 * Read-only view models are an integration seam, not a supported live adapter.
 */
export function CollectionsWorkspace({
  state = unavailable,
}: {
  state?: CollectionsView;
}) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const collections = state.kind === 'available' ? state.data : [];
  const selected =
    collections.find((collection) => collection.collectionId === selectedId) ??
    collections[0];
  const unselected = state.kind === 'available' ? unavailable : state;
  const memberships = selected?.memberships ?? unselected;
  const audience = selected?.audience ?? unselected;
  return (
    <section aria-label="Collections workspace">
      <p className="breadcrumbs">Workspace / Collections</p>
      <Title order={1}>Knowledge Collections</Title>
      <section aria-label="Accessible collections">
        {state.kind !== 'available' ? (
          <Paper className="card">
            <h2>Collections unavailable</h2>
            <BoundaryNotice state={state} />
          </Paper>
        ) : collections.length === 0 ? (
          <Paper className="card">
            <p className="muted">No accessible collections on this page.</p>
          </Paper>
        ) : (
          collections.map((collection) => (
            <Paper
              component="button"
              type="button"
              key={collection.collectionId}
              className="card"
              aria-pressed={selected?.collectionId === collection.collectionId}
              aria-label={`Select collection ${collection.title}`}
              onClick={() => setSelectedId(collection.collectionId)}
              style={{
                display: 'block',
                width: '100%',
                textAlign: 'left',
                color: 'inherit',
                cursor: 'pointer',
                font: 'inherit',
              }}
            >
              <span style={{ fontWeight: 700 }}>
                <span aria-hidden="true">▧ </span>
                {collection.title}
              </span>
              <div className="sub">
                Linked count unavailable · Ceiling{' '}
                {collection.classificationCeiling ?? 'Unknown'}
              </div>
            </Paper>
          ))
        )}
        <p className="hint">
          Authorized page only. Collection and membership totals are
          unavailable.
        </p>
      </section>
      <Paper
        component="section"
        className="card"
        aria-label="Collection details"
      >
        <h2>{selected?.title ?? 'Collection details'}</h2>
        {selected?.description && (
          <p className="small">{selected.description}</p>
        )}
        <section aria-label="Collection memberships">
          {memberships.kind !== 'available' ? (
            <BoundaryNotice state={memberships} />
          ) : memberships.data.length === 0 ? (
            <p className="muted">No accessible memberships on this page.</p>
          ) : (
            memberships.data.map((member) => (
              <div className="rowline" key={member.membershipId}>
                <div className="grow">
                  <span>Document {member.documentId}</span>
                  <div className="sub">{member.state}</div>
                </div>
                <Button disabled variant="default">
                  Remove
                </Button>
              </div>
            ))
          )}
          <NativeSelect
            label="Add existing document"
            disabled
            data={['Unavailable']}
          />
          <div className="toolbar">
            <Button disabled variant="default">
              Add membership
            </Button>
          </div>
          <p className="hint">
            Collection membership does not grant document content access.
            Document authorization and current audience policy require separate
            server verification.
          </p>
        </section>
      </Paper>
      <Paper
        component="section"
        className="card"
        aria-label="Create collection"
      >
        <h2>Create collection</h2>
        <TextInput
          label="Collection name"
          placeholder="Collection name"
          disabled
        />
        <Button disabled variant="filled">
          Create collection
        </Button>
        <p className="hint">Collection changes are unavailable.</p>
      </Paper>
      <Paper
        component="section"
        className="card"
        aria-label="Audience and policy"
      >
        <h2>Audience and policy</h2>
        <div className="detail">
          <span>Collection status</span>
          <span>{selected?.status ?? 'Unknown'}</span>
        </div>
        <div className="detail">
          <span>Classification ceiling</span>
          <span>{selected?.classificationCeiling ?? 'Unknown'}</span>
        </div>
        <div className="detail">
          <span>Classification policy revision</span>
          <span>{selected?.classificationPolicyRevision ?? 'Unknown'}</span>
        </div>
        {audience.kind !== 'available' ? (
          <BoundaryNotice state={audience} />
        ) : (
          <>
            <div className="detail">
              <span>Audience policy revision</span>
              <span>{audience.data.revision}</span>
            </div>
            {audience.data.rules.length === 0 ? (
              <p className="hint">No audience rules returned.</p>
            ) : (
              <ul aria-label="Audience rules">
                {audience.data.rules.map((rule, index) => (
                  <li key={index}>
                    {rule.effect} · {rule.selectorLabel}
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
        <div className="detail">
          <span>Content access</span>
          <span>Not evaluated</span>
        </div>
        <div className="toolbar">
          <Button disabled variant="default">
            Change audience policy
          </Button>
        </div>
        <p className="hint">
          Policy details do not authorize opening, downloading or publishing
          document content.
        </p>
      </Paper>
    </section>
  );
}
