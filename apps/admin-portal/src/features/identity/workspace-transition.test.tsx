import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { WorkspaceShell } from '../../app-shell/WorkspaceShell';
import { Documents } from '../../Documents';
import { HomeWorkspace } from '../home/HomeWorkspace';
import { PortalTheme } from '../../theme';
import { sessionKey } from '../../session-key';
import { AuthorityChangedError } from '../../request-authority';

const session = {
  issuer: 'https://idp.test',
  subject: 'synthetic',
  displayName: 'Synthetic',
  tenants: ['a', 'b'],
  selectedTenant: 'a',
  csrfToken: 'synthetic-a',
};
const next = { ...session, selectedTenant: 'b', csrfToken: 'synthetic-b' };
const id = '11111111-1111-4111-8111-111111111111';
const draft = (tenant: string, title: string) => ({
  documentId: id,
  tenant,
  creator: { issuer: session.issuer, subject: session.subject },
  createdAt: '2026-11-01T00:00:00Z',
  revision: 0,
  lifecycle: 'DRAFT',
  metadata: { title, description: '' },
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
});

it('rejects a captured document action before I/O and suppresses an earlier command receipt across inline transition', async () => {
  let finishCommand!: (response: Response) => void;
  let finishSelection!: (response: Response) => void;
  let writes = 0;
  const fetcher = vi.fn((path: string, init: RequestInit) => {
    if (path === '/api/v1/session/tenant')
      return new Promise<Response>((resolve) => {
        finishSelection = resolve;
      });
    if (init.method === 'POST') {
      writes++;
      if (writes > 1)
        return Promise.resolve(
          new Response(JSON.stringify({ documentId: id, revision: 2 }), {
            status: 201,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      return new Promise<Response>((resolve) => {
        finishCommand = resolve;
      });
    }
    return Promise.resolve(json({ items: [], nextCursor: null }));
  });
  vi.stubGlobal('fetch', fetcher);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  client.setQueryData(sessionKey, session);
  render(
    <PortalTheme>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/documents']}>
          <WorkspaceShell>
            <Documents />
          </WorkspaceShell>
        </MemoryRouter>
      </QueryClientProvider>
    </PortalTheme>,
  );
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'New document' }));
  await user.type(screen.getByLabelText('Title'), 'Synthetic draft');
  await user.click(screen.getByRole('button', { name: 'Create draft' }));
  await waitFor(() => expect(writes).toBe(1));
  const captured = client.getMutationCache().getAll()[0]!.options.mutationFn!;
  await user.selectOptions(
    screen.getByRole('combobox', { name: 'Workspace (verified membership)' }),
    'b',
  );
  await expect(
    captured({ kind: 'create', body: {} }, { client, meta: undefined }),
  ).rejects.toBeInstanceOf(AuthorityChangedError);
  expect(writes).toBe(1);
  await act(async () =>
    finishCommand(
      new Response(JSON.stringify({ documentId: id, revision: 1 }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  );
  expect(screen.queryByText(`Saved document ${id}, revision 1.`)).toBeNull();
  await act(async () => finishSelection(json(next)));
  await screen.findByText('No accessible drafts on this page.');
  expect(screen.queryByText(`Saved document ${id}, revision 1.`)).toBeNull();
});
const json = (value: unknown) =>
  new Response(JSON.stringify(value), {
    headers: { 'Content-Type': 'application/json' },
  });
afterEach(() => vi.unstubAllGlobals());

it.each(['documents', 'home'] as const)(
  'fences already-mounted %s reads across real inline selection and resumes only the rotated scope',
  async (view) => {
    let finishOld!: (response: Response) => void;
    let finishSelection!: (response: Response) => void;
    let oldSignal!: AbortSignal;
    let reads = 0;
    const fetcher = vi.fn((path: string, init: RequestInit) => {
      if (path === '/api/v1/session/tenant')
        return new Promise<Response>((resolve) => {
          finishSelection = resolve;
        });
      if (path.startsWith('/api/v1/dm/documents?')) {
        reads++;
        if (reads === 1) {
          oldSignal = init.signal!;
          return new Promise<Response>((resolve) => {
            finishOld = resolve;
          });
        }
        return Promise.resolve(
          json({ items: [draft('b', 'Fresh draft')], nextCursor: null }),
        );
      }
      throw new Error('Unexpected test request');
    });
    vi.stubGlobal('fetch', fetcher);
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: Infinity } },
    });
    client.setQueryData(sessionKey, session);
    render(
      <PortalTheme>
        <QueryClientProvider client={client}>
          <MemoryRouter
            initialEntries={[view === 'documents' ? '/documents' : '/']}
          >
            <WorkspaceShell>
              {view === 'documents' ? <Documents /> : <HomeWorkspace />}
            </WorkspaceShell>
          </MemoryRouter>
        </QueryClientProvider>
      </PortalTheme>,
    );
    await waitFor(() => expect(reads).toBe(1));
    await userEvent.setup().selectOptions(
      screen.getByRole('combobox', {
        name: 'Workspace (verified membership)',
      }),
      'b',
    );
    await waitFor(() => expect(finishSelection).toBeTypeOf('function'));
    expect(oldSignal.aborted).toBe(true);
    await act(async () =>
      finishOld(
        json({
          items: [draft('a', 'Obsolete protected title')],
          nextCursor: null,
        }),
      ),
    );
    expect(screen.queryByText('Obsolete protected title')).toBeNull();
    expect(
      client.getQueryData([
        'documents',
        session.issuer,
        session.subject,
        'a',
        session.csrfToken,
        'list',
        '',
      ]),
    ).toBeUndefined();
    expect(reads).toBe(1);
    await act(async () => finishSelection(json(next)));
    await screen.findByRole(view === 'documents' ? 'button' : 'link', {
      name: /Fresh draft/,
    });
    expect(reads).toBe(2);
    expect(screen.queryByText('Obsolete protected title')).toBeNull();
    expect(client.getQueryData(sessionKey)).toEqual(next);
    expect(
      client.getQueryData([
        'documents',
        next.issuer,
        next.subject,
        'b',
        next.csrfToken,
        'list',
        '',
      ]),
    ).toBeTruthy();
    expect(
      screen
        .getByRole('link', {
          name: view === 'documents' ? 'Documents' : 'Home',
        })
        .getAttribute('aria-current'),
    ).toBe('page');
    expect(fetcher).toHaveBeenCalledWith(
      '/api/v1/session/tenant',
      expect.objectContaining({
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': session.csrfToken,
        },
        body: JSON.stringify({ tenantId: 'b' }),
      }),
    );
  },
);
