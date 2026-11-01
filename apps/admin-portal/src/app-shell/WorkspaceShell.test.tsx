import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { PortalTheme } from '../theme';
import { sessionKey } from '../session-key';
import { WorkspaceShell } from './WorkspaceShell';
import * as api from '../identity-api';

const session = {
  issuer: 'https://identity.test',
  subject: 'subject',
  displayName: 'Test account',
  tenants: ['workspace-a', 'workspace-b'],
  selectedTenant: 'workspace-a',
  csrfToken: 'not-a-real-token',
};
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function renderShell(data: unknown = session) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  if (data !== undefined) client.setQueryData(sessionKey, data);
  render(
    <PortalTheme>
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <WorkspaceShell>
            <h1>Workspace content</h1>
          </WorkspaceShell>
        </MemoryRouter>
      </QueryClientProvider>
    </PortalTheme>,
  );
  return client;
}

it('uses current session context and removes old account/workspace on session transitions', async () => {
  const client = renderShell();
  const context = screen.getByLabelText('Current workspace');
  expect(within(context).getByText('workspace-a')).toBeTruthy();
  expect(within(context).getByText('Test account')).toBeTruthy();
  client.setQueryData(sessionKey, {
    ...session,
    selectedTenant: 'workspace-b',
  });
  await waitFor(() =>
    expect(within(context).getByText('workspace-b')).toBeTruthy(),
  );
  expect(
    (
      within(context).getByRole('combobox', {
        name: 'Workspace (verified membership)',
      }) as HTMLSelectElement
    ).value,
  ).toBe('workspace-b');
  client.setQueryData(sessionKey, { ...session, selectedTenant: null });
  await waitFor(() =>
    expect(within(context).getByText('No workspace selected')).toBeTruthy(),
  );
  client.setQueryData(sessionKey, null);
  await waitFor(() =>
    expect(within(context).getByText('Signed out')).toBeTruthy(),
  );
  expect(within(context).queryByText('Test account')).toBeNull();
});

it('shows only the actual account in a disabled native context control without inferring roles or issuing commands', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  const accountSession = {
    ...session,
    displayName: 'Administrator <script>untrusted</script>',
  };
  const client = renderShell(accountSession);
  const account = screen.getByRole('combobox', {
    name: 'Authenticated account context',
  }) as HTMLSelectElement;
  expect(account.tagName).toBe('SELECT');
  expect(account.disabled).toBe(true);
  expect(account.value).toBe(accountSession.displayName);
  const options = within(account).getAllByRole('option');
  expect(options).toHaveLength(1);
  expect(options[0]?.textContent).toBe(accountSession.displayName);
  expect(account.querySelector('script')).toBeNull();
  await userEvent.setup().click(account);
  expect(fetcher).not.toHaveBeenCalled();
  expect(client.getQueryData(sessionKey)).toEqual(accountSession);
  expect(screen.queryByRole('option', { name: /^Administrator$/ })).toBeNull();
});

it('selects only real memberships without optimistic authority and withholds protected content until rotation', async () => {
  let finish!: (value: api.SessionView) => void;
  const select = vi.spyOn(api, 'selectTenant').mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const client = renderShell();
  const control = screen.getByRole('combobox', {
    name: 'Workspace (verified membership)',
  }) as HTMLSelectElement;
  expect(Array.from(control.options, (option) => option.value)).toEqual(
    session.tenants,
  );
  await userEvent.setup().selectOptions(control, 'workspace-a');
  expect(select).not.toHaveBeenCalled();
  await userEvent.setup().selectOptions(control, 'workspace-b');
  await waitFor(() => expect(select).toHaveBeenCalledOnce());
  expect(control.disabled).toBe(true);
  expect(control.value).toBe('workspace-a');
  expect(client.getQueryData(sessionKey)).toEqual(session);
  expect(
    screen.queryByRole('heading', { name: 'Workspace content' }),
  ).toBeNull();
  expect(screen.getByRole('status').textContent).toContain(
    'Updating workspace access',
  );
  expect(select).toHaveBeenCalledWith(
    session,
    'workspace-b',
    expect.any(AbortSignal),
  );
  await act(async () =>
    finish({
      ...session,
      selectedTenant: 'workspace-b',
      csrfToken: 'rotated-synthetic',
    }),
  );
  await waitFor(() => expect(control.value).toBe('workspace-b'));
  expect(control.disabled).toBe(false);
  expect(
    screen.getByRole('heading', { name: 'Workspace content' }),
  ).toBeTruthy();
  expect(
    screen.getByRole('link', { name: 'Home' }).getAttribute('aria-current'),
  ).toBe('page');
});

it('retains the real selected workspace and exposes a bounded denied state', async () => {
  vi.spyOn(api, 'selectTenant').mockRejectedValue(
    new api.IdentityRequestError(403),
  );
  renderShell();
  const control = screen.getByRole('combobox', {
    name: 'Workspace (verified membership)',
  }) as HTMLSelectElement;
  await userEvent.setup().selectOptions(control, 'workspace-b');
  expect((await screen.findByRole('alert')).textContent).toContain(
    'Workspace selection was denied',
  );
  expect(control.value).toBe('workspace-a');
  expect(control.disabled).toBe(false);
  expect(
    screen.getByRole('heading', { name: 'Workspace content' }),
  ).toBeTruthy();
});

it('offers explicit existing sign-in recovery after an uncertain response instead of restoring protected content from an unchanged session', async () => {
  vi.spyOn(api, 'selectTenant').mockRejectedValue(
    new api.IdentityRequestError(503),
  );
  vi.spyOn(api, 'getSession').mockResolvedValue(session);
  renderShell();
  await userEvent
    .setup()
    .selectOptions(
      screen.getByRole('combobox', { name: 'Workspace (verified membership)' }),
      'workspace-b',
    );
  expect((await screen.findByRole('alert')).textContent).toContain(
    'workspace change could not be confirmed',
  );
  await waitFor(() => expect(api.getSession).toHaveBeenCalledOnce());
  expect(
    screen.queryByRole('heading', { name: 'Workspace content' }),
  ).toBeNull();
  expect(
    screen.getByRole('link', { name: 'Sign in again' }).getAttribute('href'),
  ).toBe('/oauth/login/keycloak');
  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: 'Retry session verification' }));
  await waitFor(() => expect(api.getSession).toHaveBeenCalledTimes(2));
  expect(
    screen.queryByRole('heading', { name: 'Workspace content' }),
  ).toBeNull();
});

it.each([null, { ...session, tenants: [], selectedTenant: null }])(
  'disables inline selection without authenticated membership (%j)',
  (data) => {
    renderShell(data);
    const control = screen.getByRole('combobox', {
      name: 'Workspace (verified membership)',
    }) as HTMLSelectElement;
    expect(control.disabled).toBe(true);
    expect(control.options).toHaveLength(1);
    expect(control.value).toBe('');
  },
);

it('provides all eight reference navigation destinations and separate access/support links', () => {
  renderShell();
  const nav = screen.getByRole('navigation', { name: 'Main navigation' });
  const entries = [
    ['Home', '/'],
    ['Documents', '/documents'],
    ['Collections', '/collections'],
    ['Reviews', '/reviews'],
    ['Search', '/search'],
    ['Operations', '/operations'],
    ['Governance', '/governance'],
    ['Audit', '/audit'],
  ] as const;
  expect(within(nav).getAllByRole('link')).toHaveLength(8);
  for (const [label, path] of entries)
    expect(
      within(nav).getByRole('link', { name: label }).getAttribute('href'),
    ).toBe(path);
  expect(
    within(nav)
      .getByRole('link', { name: 'Home' })
      .getAttribute('aria-current'),
  ).toBe('page');
  expect(screen.queryByRole('button', { name: 'Open navigation' })).toBeNull();
  const support = screen.getByRole('navigation', { name: 'Workspace support' });
  expect(
    within(support)
      .getByRole('link', { name: 'Workspace access' })
      .getAttribute('href'),
  ).toBe('/identity');
  expect(
    within(support)
      .getByRole('link', { name: 'Foundation' })
      .getAttribute('href'),
  ).toBe('/foundation');
});

it('keeps the navigation keyboard-operable and transfers focus to content when choosing a destination', async () => {
  renderShell();
  const user = userEvent.setup();
  const documents = screen.getByRole('link', { name: 'Documents' });
  documents.focus();
  await user.keyboard('{Enter}');
  expect(document.activeElement).toBe(screen.getByRole('main'));
  expect(documents.getAttribute('aria-current')).toBe('page');
  expect(
    screen.getByRole('link', { name: 'Home' }).getAttribute('aria-current'),
  ).toBeNull();
});

it('skip navigation moves keyboard focus into the content', async () => {
  renderShell();
  await userEvent
    .setup()
    .click(screen.getByRole('link', { name: 'Skip to content' }));
  expect(document.activeElement).toBe(screen.getByRole('main'));
});

it.each([401, 503])(
  'does not show stale context when session verification fails (%s)',
  async (status) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('{}', { status })),
    );
    const client = renderShell();
    await client.invalidateQueries({ queryKey: sessionKey });
    await waitFor(() =>
      expect(
        screen.getByText(
          status === 401
            ? 'Sign in to select a workspace'
            : 'Workspace unavailable',
        ),
      ).toBeTruthy(),
    );
    expect(screen.queryByText('Test account')).toBeNull();
    expect(screen.queryByText('workspace-a')).toBeNull();
  },
);

it.each([401, 503])(
  'cancels and removes protected cached and in-flight queries when session verification fails (%s)',
  async (status) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('{}', { status })),
    );
    const client = renderShell();
    const scope = [
      'documents',
      session.issuer,
      session.subject,
      session.selectedTenant,
      session.csrfToken,
    ];
    client.setQueryData([...scope, 'list'], {
      title: 'Protected cached title',
    });
    let readSignal: AbortSignal | undefined;
    let finish: ((value: string) => void) | undefined;
    const pending = client
      .fetchQuery({
        queryKey: [...scope, 'detail'],
        queryFn: ({ signal }) => {
          readSignal = signal;
          return new Promise<string>((resolve) => {
            finish = resolve;
          });
        },
      })
      .catch(() => undefined);
    await client.invalidateQueries({ queryKey: sessionKey });
    await waitFor(() => expect(readSignal?.aborted).toBe(true));
    expect(client.getQueriesData({ queryKey: ['documents'] })).toEqual([]);
    finish?.('Late protected title');
    await pending;
    expect(client.getQueriesData({ queryKey: ['documents'] })).toEqual([]);
  },
);

it.each([
  { selectedTenant: 'workspace-b' },
  { subject: 'replacement-user' },
  { issuer: 'https://replacement.test' },
  { csrfToken: 'rotated-session-token' },
])(
  'removes obsolete document scopes while retaining fresh authorized queries after %j',
  async (change) => {
    const client = renderShell();
    const next = { ...session, ...change };
    const oldKey = [
      'documents',
      session.issuer,
      session.subject,
      session.selectedTenant,
      session.csrfToken,
      'list',
    ];
    const freshKey = [
      'documents',
      next.issuer,
      next.subject,
      next.selectedTenant,
      next.csrfToken,
      'list',
    ];
    client.setQueryData(oldKey, 'Old protected data');
    client.setQueryData(freshKey, 'Fresh authorized data');
    client.setQueryData(sessionKey, next);
    await waitFor(() => expect(client.getQueryData(oldKey)).toBeUndefined());
    expect(client.getQueryData(freshKey)).toBe('Fresh authorized data');
  },
);

it('removes old service context while preserving freshly scoped context on a tenant switch', async () => {
  const client = renderShell();
  const oldKey = [
    'context',
    session.issuer,
    session.subject,
    'workspace-a',
    'dm',
  ];
  const freshKey = [
    'context',
    session.issuer,
    session.subject,
    'workspace-b',
    'dm',
  ];
  client.setQueryData(oldKey, 'Old context');
  client.setQueryData(freshKey, 'Fresh context');
  client.setQueryData(sessionKey, {
    ...session,
    selectedTenant: 'workspace-b',
  });
  await waitFor(() => expect(client.getQueryData(oldKey)).toBeUndefined());
  expect(client.getQueryData(freshKey)).toBe('Fresh context');
});

it('discards legacy service context without a session epoch when the session token rotates', async () => {
  const client = renderShell();
  const key = [
    'context',
    session.issuer,
    session.subject,
    session.selectedTenant,
    'dm',
  ];
  client.setQueryData(key, 'Prior session context');
  client.setQueryData(sessionKey, { ...session, csrfToken: 'rotated' });
  await waitFor(() => expect(client.getQueryData(key)).toBeUndefined());
});

it('fails closed for an unknown protected query scope during a context transition', async () => {
  const client = renderShell();
  client.setQueryData(
    ['unscoped-protected-feature'],
    'Cannot establish ownership',
  );
  client.setQueryData(sessionKey, {
    ...session,
    selectedTenant: 'workspace-b',
  });
  await waitFor(() =>
    expect(client.getQueryData(['unscoped-protected-feature'])).toBeUndefined(),
  );
  expect(client.getQueryData(sessionKey)).toMatchObject({
    selectedTenant: 'workspace-b',
  });
});

it('keeps a fresh session-scoped context and discards old-token context during rotation', async () => {
  const client = renderShell();
  const prefix = [
    'context',
    session.issuer,
    session.subject,
    session.selectedTenant,
  ];
  const oldKey = [...prefix, session.csrfToken, 'dm'];
  const freshKey = [...prefix, 'rotated', 'dm'];
  client.setQueryData(oldKey, 'Old session context');
  client.setQueryData(freshKey, 'Fresh session context');
  client.setQueryData(sessionKey, { ...session, csrfToken: 'rotated' });
  await waitFor(() => expect(client.getQueryData(oldKey)).toBeUndefined());
  expect(client.getQueryData(freshKey)).toBe('Fresh session context');
});
