import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Link, MemoryRouter } from 'react-router-dom';
import { LoggingStatus } from './LoggingStatus';
import { RouteLogging } from './RouteLogging';
import { PortalTheme } from './theme';
import { portalLogging, type PortalLogging } from './portal-logging';

afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); });
describe('portal delivery status and route adapter', () => {
  it.each(['UNCONFIGURED', 'INVALID', 'AWAITING_SESSION', 'CONNECTING', 'READY', 'UNKNOWN', 'CLOSED'] as const)(
    'renders truthful %s status without claiming commit for UNKNOWN delivery', state => {
      const logging = { status: () => ({ state, failures: 1, retiredDiscarded: 2, delivery: null }) };
      render(<PortalTheme><LoggingStatus logging={logging} /></PortalTheme>);
      expect(screen.getByLabelText('Development log delivery')).toBeTruthy();
      expect(screen.getByText('Unknown delivery does not establish whether an event was saved.')).toBeTruthy();
      expect(screen.getByRole('link', { name: 'View shared logs' }).getAttribute('href')).toBe('/dev/logs');
    });
  it('polls separate exact counters and removes its timer on unmount', () => {
    vi.useFakeTimers(); const clear = vi.spyOn(window, 'clearInterval');
    let status: ReturnType<PortalLogging['status']> = { state: 'READY', failures: 3, retiredDiscarded: 2,
      delivery: { pending: 2, unknown: 1, committed: 4, dropped: 5, discardedUnknown: 1, contextReady: true } };
    const logging = { status: () => status };
    const view = render(<PortalTheme><LoggingStatus logging={logging} /></PortalTheme>);
    expect(screen.getByText('Pending: 2; unknown within pending: 1.')).toBeTruthy();
    status = { ...status, state: 'UNKNOWN', delivery: { ...status.delivery!, committed: 6 } };
    act(() => { vi.advanceTimersByTime(1000); });
    expect(screen.getByText('Development logs: delivery unknown')).toBeTruthy();
    expect(screen.getByText('Confirmed in current context: 6.')).toBeTruthy();
    view.unmount(); expect(clear).toHaveBeenCalledOnce();
  });
  it('records navigation changes without URL, query or fragment contents', async () => {
    const record = vi.spyOn(portalLogging, 'record'); const user = userEvent.setup();
    render(<MemoryRouter><RouteLogging /><Link to="/documents?token=private#private">Go</Link></MemoryRouter>);
    await user.click(screen.getByRole('link', { name: 'Go' }));
    expect(record.mock.calls).toEqual([['ROUTE', 'ui.route.changed'], ['ROUTE', 'ui.route.changed']]);
    expect(JSON.stringify(record.mock.calls)).not.toMatch(/private|documents/);
  });
});
