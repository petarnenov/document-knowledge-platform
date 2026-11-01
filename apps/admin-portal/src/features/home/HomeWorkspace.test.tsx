import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { PortalTheme } from '../../theme';
import { getSession } from '../../identity-api';
import {
  listDrafts,
  DocumentRequestError,
  type Draft,
} from '../../document-api';
import { sessionKey } from '../../session-key';
import { HomeWorkspace } from './HomeWorkspace';
import { CapabilityPage } from './CapabilityPage';
vi.mock('../../identity-api', () => ({ getSession: vi.fn() }));
vi.mock('../../document-api', async (original) => ({
  ...(await original<typeof import('../../document-api')>()),
  listDrafts: vi.fn(),
}));
const session = {
  issuer: 'https://identity.invalid',
  subject: 'reader',
  displayName: 'Reader',
  tenants: ['tenant-a', 'tenant-b'],
  selectedTenant: 'tenant-a',
  csrfToken: 'csrf-a',
};
const pageKey = [
  'documents',
  session.issuer,
  session.subject,
  session.selectedTenant,
  session.csrfToken,
  'list',
  '',
];
function open(children = <HomeWorkspace />) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <MemoryRouter>
      <PortalTheme>
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      </PortalTheme>
    </MemoryRouter>,
  );
  return client;
}
afterEach(() => vi.resetAllMocks());
it('keeps total metrics unavailable and does not load documents without a selected workspace', async () => {
  vi.mocked(getSession).mockResolvedValue({ ...session, selectedTenant: null });
  open();
  expect(
    await screen.findByText(/Sign in and choose a workspace/),
  ).toBeTruthy();
  expect(screen.getAllByText('Workspace total unavailable')).toHaveLength(4);
  expect(listDrafts).not.toHaveBeenCalled();
});
it('uses a scoped query for authorized drafts without claiming global totals or recency', async () => {
  vi.mocked(getSession).mockResolvedValue(session);
  vi.mocked(listDrafts).mockResolvedValue({
    items: [
      {
        documentId: '11111111-1111-4111-8111-111111111111',
        metadata: { title: '<script>untrusted</script>' },
        revision: 7,
      } as Draft,
    ],
    nextCursor: 'another-page',
  });
  const client = open();
  const link = await screen.findByRole('link', { name: /<script>untrusted/ });
  expect(link.getAttribute('href')).toBe(
    '/documents?documentId=11111111-1111-4111-8111-111111111111',
  );
  expect(link.querySelector('script')).toBeNull();
  expect(
    screen.getByText(/recent ordering and workspace totals are unavailable/),
  ).toBeTruthy();
  expect(client.getQueryData(pageKey)).toBeTruthy();
  expect(screen.getAllByText('Workspace total unavailable')).toHaveLength(4);
});
it('shows an empty authorized page separately from unavailable totals', async () => {
  vi.mocked(getSession).mockResolvedValue(session);
  vi.mocked(listDrafts).mockResolvedValue({ items: [], nextCursor: null });
  open();
  expect(
    await screen.findByText('No accessible drafts on this page.'),
  ).toBeTruthy();
});
it('shows loading and then a generic unavailable result without upstream diagnostics', async () => {
  vi.mocked(getSession).mockResolvedValue(session);
  let reject!: (reason: Error) => void;
  vi.mocked(listDrafts).mockImplementation(
    () =>
      new Promise((_, failure) => {
        reject = failure;
      }),
  );
  open();
  expect(await screen.findByRole('status')).toBeTruthy();
  reject(new Error('private upstream details'));
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(screen.queryByText(/private upstream/)).toBeNull();
});
it('clears protected query state and session when document access reports expiry', async () => {
  vi.mocked(getSession).mockResolvedValue(session);
  vi.mocked(listDrafts).mockRejectedValue(new DocumentRequestError(401));
  const client = open();
  await waitFor(() => expect(client.getQueryData(sessionKey)).toBeNull());
  expect(client.getQueryData(pageKey)).toBeUndefined();
});
it.each([
  'collections',
  'reviews',
  'search',
  'operations',
  'governance',
  'audit',
])(
  'retains the %s destination without simulated records or mutations',
  (capability) => {
    open(<CapabilityPage capability={capability} />);
    expect(screen.getByRole('status').textContent).toContain('unavailable');
    expect(screen.getAllByRole('heading').length).toBeGreaterThan(1);
    expect(
      screen.getByRole('link', { name: 'Open documents' }).getAttribute('href'),
    ).toBe('/documents');
    expect(listDrafts).not.toHaveBeenCalled();
  },
);

it('keeps an unknown capability unavailable without an invented operation', () => {
  open(<CapabilityPage capability="missing" />);
  expect(
    screen.getByRole('heading', { name: 'Capability unavailable' }),
  ).toBeTruthy();
  expect(screen.getByRole('status').textContent).toContain('unavailable');
});
