import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { PortalTheme } from '../theme';
import { AsyncState } from './AsyncState';
import { PageHeader } from './PageHeader';

it('announces loading and exposes informative unavailable and empty sections without recovery actions', () => {
  const view = render(
    <PortalTheme>
      <AsyncState kind="loading" title="Loading documents">
        Checking your workspace.
      </AsyncState>
    </PortalTheme>,
  );
  expect(screen.getByRole('status').getAttribute('aria-busy')).toBe('true');
  for (const kind of ['empty', 'unavailable'] as const) {
    view.rerender(
      <PortalTheme>
        <AsyncState kind={kind} title="No records">
          No result has been returned.
        </AsyncState>
      </PortalTheme>,
    );
    expect(screen.getByRole('region', { name: 'No records' })).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByRole('status')).toBeNull();
  }
});

it('announces errors/conflicts and offers recovery only when supplied by the caller', async () => {
  const retry = vi.fn();
  const user = userEvent.setup();
  const view = render(
    <PortalTheme>
      <AsyncState kind="error" title="Request unavailable" onRetry={retry}>
        Try reloading.
      </AsyncState>
    </PortalTheme>,
  );
  expect(
    screen.getByRole('alert', { name: 'Request unavailable' }),
  ).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Try again' }));
  expect(retry).toHaveBeenCalledOnce();
  view.rerender(
    <PortalTheme>
      <AsyncState
        kind="conflict"
        title="Record changed"
        onRetry={retry}
        retryLabel="Reload record"
      />
    </PortalTheme>,
  );
  expect(screen.getByRole('alert', { name: 'Record changed' })).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Reload record' }));
  expect(retry).toHaveBeenCalledTimes(2);
});

it('renders a page heading with optional description and caller-provided action', () => {
  const view = render(
    <PortalTheme>
      <PageHeader
        title="Documents"
        description="Authorized workspace documents"
        actions={<button>Create draft</button>}
      />
    </PortalTheme>,
  );
  expect(
    screen.getByRole('heading', { level: 1, name: 'Documents' }),
  ).toBeTruthy();
  expect(screen.getByText('Authorized workspace documents')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Create draft' })).toBeTruthy();
  view.rerender(
    <PortalTheme>
      <PageHeader title="Library" />
    </PortalTheme>,
  );
  expect(screen.queryByRole('button')).toBeNull();
});
