import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';

afterEach(() => vi.unstubAllGlobals());

function renderShell(path = '/') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe('foundation shell', () => {
  it('shows honest scope, semantic landmarks and active home navigation', () => {
    renderShell();
    expect(
      screen.getByRole('heading', { name: 'Knowledge workspace' }),
    ).toBeTruthy();
    expect(screen.getByRole('main').id).toBe('main-content');
    expect(
      screen.getByRole('navigation', { name: 'Main navigation' }),
    ).toBeTruthy();
    expect(
      screen.getByRole('link', { name: 'Home' }).getAttribute('aria-current'),
    ).toBe('page');
    expect(screen.getAllByText('Workspace total unavailable')).toHaveLength(4);
    expect(screen.getAllByRole('switch')).toHaveLength(1);
    expect(
      document.documentElement.getAttribute('data-mantine-color-scheme'),
    ).toBe('dark');
  });

  it('navigates to foundation information and returns through the brand', async () => {
    const user = userEvent.setup();
    renderShell();
    await user.click(screen.getByRole('link', { name: /^Foundation$/ }));
    expect(
      screen.getByRole('heading', { name: 'Built to grow with care.' }),
    ).toBeTruthy();
    expect(
      screen
        .getByRole('link', { name: /^Foundation$/ })
        .getAttribute('aria-current'),
    ).toBe('page');
    expect(screen.getByText(/Searching, publishing and purging/)).toBeTruthy();
    await user.click(
      screen.getByRole('link', { name: 'Document Knowledge Platform home' }),
    );
    expect(
      screen.getByRole('heading', { name: 'Knowledge workspace' }),
    ).toBeTruthy();
  });

  it('opens foundation directly', () => {
    renderShell('/foundation');
    expect(
      screen.getByRole('heading', { name: 'Guiding principles' }),
    ).toBeTruthy();
  });

  it('recovers an unknown route through its home link', async () => {
    const user = userEvent.setup();
    renderShell('/missing');
    expect(
      screen.getByRole('heading', { name: 'Page not found.' }),
    ).toBeTruthy();
    await user.click(screen.getByRole('link', { name: 'Return to overview' }));
    expect(
      screen.getByRole('heading', { name: 'Knowledge workspace' }),
    ).toBeTruthy();
  });

  it('changes theme in both directions by keyboard', async () => {
    const user = userEvent.setup();
    renderShell();
    const toggle = screen.getByRole('switch', { name: 'Light theme' });
    toggle.focus();
    await user.keyboard(' ');
    expect(toggle.getAttribute('aria-checked')).toBe('true');
    expect(
      document.documentElement.getAttribute('data-mantine-color-scheme'),
    ).toBe('light');
    await user.keyboard(' ');
    expect(toggle.getAttribute('aria-checked')).toBe('false');
    expect(
      document.documentElement.getAttribute('data-mantine-color-scheme'),
    ).toBe('dark');
  });

  it('provides skip navigation as the first keyboard destination', async () => {
    const user = userEvent.setup();
    renderShell();
    await user.tab();
    expect(document.activeElement).toBe(
      screen.getByRole('link', { name: 'Skip to content' }),
    );
    expect(
      screen
        .getByRole('link', { name: 'Skip to content' })
        .getAttribute('href'),
    ).toBe('#main-content');
  });

  it.each([200, 401, 503])(
    'opens the Collections deep link with safe unavailable structure and no KB requests when session verification returns %s',
    async (status) => {
      const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
        expect(path).toBe('/api/v1/session');
        expect(init?.method).toBe('GET');
        return new Response(
          JSON.stringify(
            status === 200
              ? {
                  issuer: 'https://identity.example',
                  subject: 'test-subject',
                  displayName: 'Test account',
                  selectedTenant: 'tenant-a',
                  tenants: ['tenant-a'],
                  csrfToken: 'test-csrf',
                }
              : { code: 'UNAVAILABLE' },
          ),
          { status },
        );
      });
      vi.stubGlobal('fetch', fetcher);
      renderShell('/collections');
      const workspace = screen.getByRole('region', {
        name: 'Collections workspace',
      });
      expect(
        within(workspace).getByRole('heading', {
          name: 'Knowledge Collections',
          level: 1,
        }),
      ).toBeTruthy();
      for (const name of [
        'Accessible collections',
        'Collection details',
        'Collection memberships',
        'Create collection',
        'Audience and policy',
      ])
        expect(within(workspace).getByRole('region', { name })).toBeTruthy();
      expect(
        screen
          .getByRole('link', { name: 'Collections' })
          .getAttribute('aria-current'),
      ).toBe('page');
      for (const button of within(workspace).getAllByRole('button'))
        expect((button as HTMLButtonElement).disabled).toBe(true);
      expect(within(workspace).queryByRole('link')).toBeNull();
      expect(
        within(workspace).queryByRole('button', { name: /Select collection/ }),
      ).toBeNull();
      expect(
        within(workspace).getByText('Content access').nextElementSibling
          ?.textContent,
      ).toBe('Not evaluated');
      await screen.findByText(
        status === 200
          ? 'Test account'
          : status === 401
            ? 'Sign in to select a workspace'
            : 'Workspace unavailable',
      );
      await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
    },
  );

  it('opens Collections through the shared navigation and preserves every other unavailable capability route', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation(() =>
          Promise.resolve(new Response('{}', { status: 401 })),
        ),
    );
    const user = userEvent.setup();
    renderShell('/foundation');
    await user.click(screen.getByRole('link', { name: 'Collections' }));
    expect(
      screen.getByRole('region', { name: 'Collection memberships' }),
    ).toBeTruthy();
    for (const [name, heading] of [
      ['Reviews', 'Review & Publication'],
      ['Search', 'Knowledge Search'],
      ['Operations', 'Processing operations'],
      ['Governance', 'Governance & Compliance'],
      ['Audit', 'Audit History'],
    ] as const) {
      await user.click(screen.getByRole('link', { name }));
      expect(
        screen.getByRole('heading', { name: heading, level: 1 }),
      ).toBeTruthy();
      expect(
        screen.queryByRole('region', { name: 'Collections workspace' }),
      ).toBeNull();
      expect(
        screen.getByText(
          'This capability is unavailable in the current integration. No records or successful operations are simulated.',
        ),
      ).toBeTruthy();
    }
  });
});
