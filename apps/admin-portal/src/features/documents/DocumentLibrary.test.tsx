import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { PortalTheme } from '../../theme';
import type { Draft } from '../../document-api';
import { DocumentLibrary } from './DocumentLibrary';
import { DocumentLifecycle } from './DocumentLifecycle';
const draft: Draft = {
  documentId: '11111111-1111-4111-8111-111111111111',
  tenant: 'tenant-a',
  creator: { issuer: 'https://issuer.example', subject: 'staff' },
  createdAt: '2026-11-01T00:00:00Z',
  revision: 2,
  lifecycle: 'DRAFT',
  metadata: { title: 'Untrusted <script>title</script>', description: '' },
  classification: 'development-restricted',
  classificationPolicyRevision: 1,
  activeVersionId: null,
  retention: {
    policyRef: 'development-unresolved',
    policyRevision: 1,
    resolution: 'UNRESOLVED',
    retainUntil: null,
    destructiveEligible: false,
  },
};
it('opens the exact authorized row without interpreting untrusted title markup', async () => {
  const onOpen = vi.fn();
  render(
    <PortalTheme>
      <DocumentLibrary
        drafts={[draft]}
        selected={draft.documentId}
        onOpen={onOpen}
      />
    </PortalTheme>,
  );
  expect(screen.getByRole('table', { name: 'Document library' })).toBeTruthy();
  const button = screen.getByRole('button', { name: draft.metadata.title });
  expect(button.getAttribute('aria-pressed')).toBe('true');
  await userEvent.setup().click(button);
  expect(onOpen).toHaveBeenCalledExactlyOnceWith(draft.documentId);
  expect(document.querySelector('script')).toBeNull();
  expect(screen.getByText('Draft')).toBeTruthy();
});
it('shows an unselected row and bounded empty state without a total count', () => {
  const view = render(
    <PortalTheme>
      <DocumentLibrary drafts={[draft]} selected={null} onOpen={vi.fn()} />
    </PortalTheme>,
  );
  expect(screen.getByRole('button').getAttribute('aria-pressed')).toBe('false');
  view.rerender(
    <PortalTheme>
      <DocumentLibrary drafts={[]} selected={null} onOpen={vi.fn()} />
    </PortalTheme>,
  );
  expect(screen.getByText('No accessible drafts on this page.')).toBeTruthy();
  expect(screen.queryByRole('table')).toBeNull();
});
it('keeps draft, version, publication, processing and retention distinct', () => {
  render(
    <PortalTheme>
      <DocumentLifecycle draft={draft} />
    </PortalTheme>,
  );
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getAllByText('Metadata draft')).toHaveLength(2);
  expect(screen.getByText('Revision 2')).toBeTruthy();
  expect(screen.getByText('Active published version')).toBeTruthy();
  expect(screen.getByText('Latest version')).toBeTruthy();
  expect(screen.getByText('Unknown')).toBeTruthy();
  expect(screen.getByText('None')).toBeTruthy();
  expect(screen.getByText(/Version history is unavailable/)).toBeTruthy();
  expect(
    screen.getByText(
      /Processing, review, publication and rendered preview are not available/,
    ),
  ).toBeTruthy();
  expect(screen.getByText('UNRESOLVED')).toBeTruthy();
  expect(screen.getByText('Forbidden')).toBeTruthy();
});
