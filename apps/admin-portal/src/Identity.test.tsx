import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PortalTheme } from './theme';
import { Identity } from './Identity';
import { sessionKey } from './session-key';

const session = {
  issuer: 'https://idp.example/realms/dkp',
  subject: 'user-1',
  displayName: 'Test User',
  tenants: ['tenant-a', 'tenant-b'],
  selectedTenant: null,
  csrfToken: 'csrf-1',
};
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status });
const open = () =>
  render(
    <MemoryRouter initialEntries={['/identity']}>
      <App />
    </MemoryRouter>,
  );
afterEach(() => vi.unstubAllGlobals());

describe('workspace identity', () => {
  it('shows a real login entry for an anonymous browser', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(json({ code: 'unauthenticated' }, 401)),
    );
    open();
    expect(screen.getByRole('status').textContent).toBe(
      'Checking your session…',
    );
    expect(
      (await screen.findByRole('link', { name: 'Sign in' })).getAttribute(
        'href',
      ),
    ).toBe('/oauth/login/keycloak');
    expect(screen.queryByRole('combobox', { name: 'Tenant' })).toBeNull();
    const account = screen.getByRole('combobox', {
      name: 'Authenticated account context',
    }) as HTMLSelectElement;
    expect(account.disabled).toBe(true);
    expect(Array.from(account.options, (option) => option.text)).toEqual([
      'Access not verified',
    ]);
  });

  it('retries an unavailable session without pretending the user is authenticated', async () => {
    const fetcher = vi
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(json(session));
    vi.stubGlobal('fetch', fetcher);
    const user = userEvent.setup();
    open();
    expect(
      await screen.findByRole('heading', {
        name: 'Identity services are unavailable.',
      }),
    ).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Sign out' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Retry session' }));
    expect(
      await screen.findByRole('heading', { name: 'Signed in as Test User' }),
    ).toBeTruthy();
  });

  it('selects a tenant, checks all service contexts and logs out with rotated CSRF', async () => {
    const selected = {
      ...session,
      selectedTenant: 'tenant-b',
      csrfToken: 'csrf-2',
    };
    const fetcher = vi.fn(async (path: string) => {
      if (path.endsWith('/tenant')) return json(selected);
      if (path.endsWith('/logout')) return new Response(null, { status: 204 });
      if (path.includes('/context/'))
        return json({
          service: path.split('/').at(-1),
          issuer: session.issuer,
          subject: session.subject,
          tenant: 'tenant-b',
        });
      return json(session);
    });
    vi.stubGlobal('fetch', fetcher);
    const user = userEvent.setup();
    open();
    const selector = await screen.findByRole('combobox', { name: 'Tenant' });
    expect(screen.getByText('No tenant selected')).toBeTruthy();
    expect(
      (
        screen.getByRole('button', {
          name: 'Check Document Management context',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    await user.selectOptions(selector, 'tenant-b');
    await user.click(screen.getByRole('button', { name: 'Use tenant' }));
    await waitFor(() =>
      expect(screen.queryByText('No tenant selected')).toBeNull(),
    );
    for (const [service, label] of [
      ['dm', 'Document Management'],
      ['kb', 'Knowledge Base'],
      ['search', 'Knowledge Search'],
    ] as const) {
      await user.click(
        screen.getByRole('button', { name: `Check ${label} context` }),
      );
      await waitFor(() =>
        expect(
          within(screen.getByLabelText('Verified context')).getByText(service),
        ).toBeTruthy(),
      );
    }
    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeTruthy();
    expect(screen.queryByLabelText('Verified context')).toBeNull();
    expect(fetcher).toHaveBeenCalledWith(
      '/api/v1/session/logout',
      expect.objectContaining({
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': 'csrf-2',
        },
      }),
    );
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('retains the verified tenant after a denied tenant selection and clears stale context', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(json({ ...session, selectedTenant: 'tenant-a' }))
      .mockResolvedValueOnce(json({ code: 'denied' }, 403));
    vi.stubGlobal('fetch', fetcher);
    const user = userEvent.setup();
    open();
    await user.selectOptions(
      await screen.findByRole('combobox', { name: 'Tenant' }),
      'tenant-b',
    );
    await user.click(screen.getByRole('button', { name: 'Use tenant' }));
    expect((await screen.findByRole('alert')).textContent).toContain(
      'This action was denied',
    );
    expect(screen.getByText('tenant-a', { selector: 'dd' })).toBeTruthy();
    expect(screen.queryByLabelText('Verified context')).toBeNull();
  });

  it.each([401, 503])(
    'handles a context failure with HTTP %s without invented access',
    async (status) => {
      vi.stubGlobal(
        'fetch',
        vi
          .fn()
          .mockResolvedValueOnce(
            json({ ...session, selectedTenant: 'tenant-a' }),
          )
          .mockResolvedValueOnce(json({ code: 'failed' }, status)),
      );
      const user = userEvent.setup();
      open();
      await user.click(
        await screen.findByRole('button', {
          name: 'Check Document Management context',
        }),
      );
      const alert = await screen.findByRole('alert');
      expect(alert.textContent).toContain(
        status === 401
          ? 'Your session has expired'
          : 'Identity services are unavailable',
      );
      expect(screen.queryByLabelText('Verified context')).toBeNull();
      if (status === 401)
        expect(screen.getByRole('link', { name: 'Sign in' })).toBeTruthy();
      else
        expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy();
    },
  );

  it('does not claim logout succeeded if the request is denied', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(json(session))
        .mockResolvedValueOnce(json({ code: 'csrf_invalid' }, 403)),
    );
    const user = userEvent.setup();
    open();
    await user.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(
      screen.getByRole('heading', { name: 'Signed in as Test User' }),
    ).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Sign in' })).toBeNull();
  });

  it('shows an empty verified membership without selectable access', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(json({ ...session, tenants: [] })),
    );
    open();
    expect(
      await screen.findByText('No tenant access is available.'),
    ).toBeTruthy();
    expect(
      (screen.getByRole('button', { name: 'Use tenant' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });

  it('aborts a pending session request when leaving the identity route', async () => {
    let resolve!: (value: Response) => void;
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(
        () =>
          new Promise<Response>((done) => {
            resolve = done;
          }),
      ),
    );
    const user = userEvent.setup();
    open();
    await user.click(screen.getByRole('link', { name: 'Home' }));
    resolve(json(session));
    await waitFor(() =>
      expect(
        screen.queryByRole('heading', { name: 'Signed in as Test User' }),
      ).toBeNull(),
    );
  });
});

it('cancels a context read on scope change and ignores its late 401 without signing out the fresh workspace', async () => {
  let finish!: (value: Response) => void;
  let contextSignal!: AbortSignal;
  const initial = { ...session, selectedTenant: 'tenant-a' };
  const fresh = {
    ...session,
    selectedTenant: 'tenant-b',
    csrfToken: 'new-session-csrf',
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, options: RequestInit) => {
      if (path.includes('/context/')) {
        contextSignal = options.signal!;
        return new Promise<Response>((resolve) => {
          finish = resolve;
        });
      }
      return json(initial);
    }),
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  client.setQueryData(sessionKey, initial);
  render(
    <PortalTheme>
      <QueryClientProvider client={client}>
        <Identity />
      </QueryClientProvider>
    </PortalTheme>,
  );
  const user = userEvent.setup();
  await user.click(
    screen.getByRole('button', { name: 'Check Document Management context' }),
  );
  await waitFor(() => expect(contextSignal).toBeTruthy());
  act(() => client.setQueryData(sessionKey, fresh));
  await waitFor(() => expect(contextSignal.aborted).toBe(true));
  await act(async () => finish(json({ code: 'unauthenticated' }, 401)));
  expect(client.getQueryData(sessionKey)).toEqual(fresh);
  expect(screen.getByText('tenant-b', { selector: 'dd' })).toBeTruthy();
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.queryByLabelText('Verified context')).toBeNull();
  expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe(
    'tenant-b',
  );
});
