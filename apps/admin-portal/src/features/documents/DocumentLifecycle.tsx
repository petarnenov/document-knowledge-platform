import type { ReactNode } from 'react';
import type { Draft } from '../../document-api';

export function DocumentLifecycle({
  draft,
  actions,
}: {
  draft: Draft;
  actions?: ReactNode;
}) {
  return (
    <>
      <div className="chips">
        <span className="badge">Metadata draft</span>
        <span className="badge">{draft.classification}</span>
        <span className="badge">Revision {draft.revision}</span>
      </div>
      <div className="grid document-version-grid">
        <div className="card">
          <div className="muted small">Latest version</div>
          <div className="number" style={{ fontSynthesis: 'weight' }}>
            Unknown
          </div>
          <div className="sub">Version history is unavailable.</div>
        </div>
        <div className="card">
          <div className="muted small">Active published version</div>
          <div className="number" style={{ fontSynthesis: 'weight' }}>
            None
          </div>
          <div className="sub">No active published version.</div>
        </div>
      </div>
      {actions}
      <section className="card" aria-labelledby="document-facts">
        <h2 id="document-facts">Document details</h2>
        {[
          ['Lifecycle', 'Metadata draft'],
          ['Description', draft.metadata.description || 'No description'],
          ['Processing', 'Unavailable'],
          ['Approval', 'Unavailable'],
          ['Retention', draft.retention.resolution],
          ['Destruction', 'Forbidden'],
        ].map(([label, value]) => (
          <div className="detail document-detail-row" key={label}>
            <span className="muted">{label}</span>
            <span>{value}</span>
          </div>
        ))}
      </section>
      <p className="hint">
        Uploading a file and recording a version do not publish this document.
        Processing, review, publication and rendered preview are not available.
        Downloading a verified original requires a fresh access check.
      </p>
    </>
  );
}
