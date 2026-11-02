import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { LogViewer } from './LogViewer';
import { readLogs, type LogFilters } from './log-api';

const build = '11111111-1111-4111-8111-111111111111';
const run = '22222222-2222-4222-8222-222222222222';
function row(severity = 'DEBUG', index = 1) {
  return {
    producerId: run,
    scopeId: run,
    scope: { scopeKind: 'OPERATOR_ONLY' },
    receivedAt: '2026-11-02T19:00:00Z',
    event: {
      eventId: `33333333-3333-4333-8333-${String(index).padStart(12, '0')}`,
      buildId: build,
      runId: run,
      component: 'runtime-tools',
      timestamp: '2026-11-02T18:59:59Z',
      severity,
      eventName: 'runtime.started',
      correlationId: run,
      context: { phase: '<img src=x onerror=alert(1)>' },
    },
  };
}
function response(body: unknown, status = 200) {
  return { ok: status === 200, status, json: async () => body } as Response;
}
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it('reads real response data without a login/credential, shows times and text details, and switches theme', async () => {
  let complete: ((value: Response) => void) | undefined;
  const fetcher = vi.fn(
    () =>
      new Promise<Response>((resolve) => {
        complete = resolve;
      }),
  );
  vi.stubGlobal('fetch', fetcher);
  render(<LogViewer />);
  expect(screen.getByRole('status').textContent).toContain('Loading');
  complete!(
    response({
      events: [
        row('DEBUG', 1),
        row('WARN', 2),
        row('ERROR', 3),
        row('FATAL', 4),
      ],
    }),
  );
  expect(await screen.findByText('Connected')).toBeTruthy();
  expect(
    screen.getByRole('heading', { name: 'Development logs' }),
  ).toBeTruthy();
  expect(screen.getAllByText('runtime-tools')).toHaveLength(4);
  expect(screen.getByText('FATAL', { selector: 'span' })).toBeTruthy();
  expect(screen.getByText('Received at')).toBeTruthy();
  fireEvent.click(screen.getAllByText('Details')[0]!);
  expect(screen.getAllByText(/Correlation:/)).toHaveLength(4);
  expect(document.querySelector('.log-details')?.textContent).toContain(
    '<img src=x onerror=alert(1)>',
  );
  expect(document.querySelector('img')).toBeNull();
  expect(document.querySelector('input[type="password"]')).toBeNull();
  const request = fetcher.mock.calls[0] as unknown as [string, RequestInit];
  expect(request[0]).toMatch(/^\/dev\/logs\/api\/v1\/events\?/);
  expect(request[1].credentials).toBe('omit');
  expect(request[1].headers).toBeUndefined();
  expect(request[1].redirect).toBe('error');
  const url = new URL(request[0], 'https://localhost:18443');
  expect(url.searchParams.get('limit')).toBe('50');
  expect(
    Date.parse(url.searchParams.get('to')!) -
      Date.parse(url.searchParams.get('from')!),
  ).toBe(86400000);
  await userEvent.click(screen.getByRole('switch', { name: 'Light theme' }));
  expect(
    document.documentElement.getAttribute('data-mantine-color-scheme'),
  ).toBe('light');
  await userEvent.click(screen.getByRole('switch', { name: 'Light theme' }));
  expect(
    document.documentElement.getAttribute('data-mantine-color-scheme'),
  ).toBe('dark');
});

it('applies build/component/severity to the server and keeps pagination in the same time window', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(response({ events: [row()], nextCursor: 'opaque' }))
    .mockResolvedValueOnce(response({ events: [row('INFO')] }))
    .mockResolvedValue(response({ events: [] }));
  vi.stubGlobal('fetch', fetcher);
  render(<LogViewer />);
  await screen.findByText('Connected');
  const first = new URL(
    fetcher.mock.calls[0]![0] as string,
    'https://localhost:18443',
  );
  await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  await screen.findByText('INFO', { selector: 'span' });
  const next = new URL(
    fetcher.mock.calls[1]![0] as string,
    'https://localhost:18443',
  );
  expect(next.searchParams.get('cursor')).toBe('opaque');
  expect(next.searchParams.get('from')).toBe(first.searchParams.get('from'));
  await userEvent.type(screen.getByLabelText('Build ID'), build);
  await userEvent.type(screen.getByLabelText('Component'), 'runtime-tools');
  await userEvent.selectOptions(
    screen.getByLabelText('Minimum severity'),
    'WARN',
  );
  await userEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
  await screen.findByRole('heading', { name: 'No records in this window' });
  const filtered = new URL(
    fetcher.mock.calls[2]![0] as string,
    'https://localhost:18443',
  );
  expect(filtered.searchParams.get('buildId')).toBe(build);
  expect(filtered.searchParams.get('component')).toBe('runtime-tools');
  expect(filtered.searchParams.get('minSeverity')).toBe('WARN');
  expect(filtered.searchParams.has('cursor')).toBe(false);
  await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(4));
});

it('distinguishes invalid filters and unavailable/malformed responses without showing private error text', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(response({}, 400))
    .mockResolvedValueOnce(response({}, 503))
    .mockResolvedValueOnce(response({ events: [{ private: 'server-detail' }] }))
    .mockRejectedValueOnce(new Error('private-token-detail'));
  vi.stubGlobal('fetch', fetcher);
  render(<LogViewer />);
  expect(await screen.findByText('Invalid filters')).toBeTruthy();
  await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
  expect(await screen.findByText('Unable to read logs')).toBeTruthy();
  await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3));
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: 'Refresh' }).hasAttribute('disabled'),
    ).toBe(false),
  );
  await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(4));
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: 'Refresh' }).hasAttribute('disabled'),
    ).toBe(false),
  );
  expect(document.body.textContent).not.toContain('private-token-detail');
  expect(document.body.textContent).not.toContain('server-detail');
  expect(
    screen.queryByRole('heading', { name: 'No records in this window' }),
  ).toBeNull();
});

it('forwards abort signal and rejects broken JSON rather than creating a fake empty page', async () => {
  const controller = new AbortController();
  const filters: LogFilters = {
    from: '2026-11-02T18:00:00Z',
    to: '2026-11-02T19:00:00Z',
    buildId: '',
    component: '',
    minSeverity: '',
  };
  const fetcher = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => {
      throw new Error('invalid-json');
    },
  });
  vi.stubGlobal('fetch', fetcher);
  await expect(readLogs(filters, controller.signal)).rejects.toThrow(
    'invalid-json',
  );
  expect(fetcher.mock.calls[0]![1].signal).toBe(controller.signal);
});

it('keeps equal producer/event IDs in different scopes as separate React rows', async () => {
  const first = row();
  const second = { ...row(), scopeId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' };
  const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(response({ events: [first, second] })),
  );
  render(<LogViewer />);
  await screen.findByText('Connected');
  expect(document.querySelectorAll('tbody tr')).toHaveLength(2);
  expect(document.body.textContent).toContain(second.scopeId);
  expect(
    errors.mock.calls.some((args) =>
      args.some((value) => /same key|unique.*key/i.test(String(value))),
    ),
  ).toBe(false);
});
