import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { PortalTheme } from './theme';
import { Uploads } from './Uploads';
const id = '11111111-1111-4111-8111-111111111111',
  version = '22222222-2222-4222-8222-222222222222';
const session = {
  issuer: 'https://issuer.example',
  subject: 'user',
  displayName: 'Synthetic',
  tenants: ['tenant-a'],
  selectedTenant: 'tenant-a',
  csrfToken: 'csrf',
};
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
const mount = () =>
  render(
    <PortalTheme>
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: {
              queries: { retry: false },
              mutations: { retry: false },
            },
          })
        }
      >
        <Uploads documentId={id} revision={0} session={session} />
      </QueryClientProvider>
    </PortalTheme>,
  );
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
it('shows each verified stage and downloads through current-authority endpoint without persistent links', async () => {
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.endsWith('/status'))
      return json({
        uploadId: id,
        state: 'CONSUMED',
        expiresAt: '2026-11-01T00:00:00Z',
        failureCode: null,
      });
    if (path.endsWith('/version'))
      return json({ documentId: id, versionId: version });
    if (path.endsWith('/versions'))
      return json({ documentId: id, revision: 1 }, 201);
    if (path.endsWith('/complete'))
      return json({ uploadId: id, receiptId: id });
    if (init?.method === 'PUT') return json({ uploadId: id });
    if (path.endsWith('/content'))
      return new Response('data', {
        headers: {
          'Content-Type': 'application/pdf',
          'Content-Length': '4',
          'Content-Disposition': `attachment; filename="${version}.pdf"`,
          'Cache-Control': 'no-store',
          'X-Content-Type-Options': 'nosniff',
        },
      });
    return json({ uploadId: id }, 201);
  });
  vi.stubGlobal('fetch', fetcher);
  const create = vi.fn(() => 'blob:private'),
    revoke = vi.fn();
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    value: create,
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    value: revoke,
  });
  const clicked = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(() => {});
  const user = userEvent.setup();
  mount();
  const input = document.querySelector('input[type=file]') as HTMLInputElement;
  await user.upload(
    input,
    new File(['data'], 'synthetic.pdf', { type: 'application/pdf' }),
  );
  await user.click(screen.getByRole('button', { name: 'Upload and append' }));
  await screen.findByText(`Version ${version}`);
  expect(screen.getByRole('status').textContent).toContain(
    'Publication and processing have not started',
  );
  expect(screen.queryByRole('link')).toBeNull();
  await user.click(
    screen.getByRole('button', { name: 'Download verified content' }),
  );
  await waitFor(() => expect(revoke).toHaveBeenCalledWith('blob:private'));
  expect(clicked).toHaveBeenCalledTimes(1);
  expect(create).toHaveBeenCalledTimes(1);
  expect(
    fetcher.mock.calls.some(([path]) =>
      path.endsWith(`/versions/${version}/content`),
    ),
  ).toBe(true);
  await user.click(screen.getByRole('button', { name: 'Cancel and clear' }));
  expect(screen.queryByText(`Version ${version}`)).toBeNull();
});
it('retries the same command keys and preserves completed receipt consumption while discovery is denied', async () => {
  let deny = true;
  const keys: string[] = [];
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.endsWith('/status'))
      return json({
        uploadId: id,
        state: 'CONSUMED',
        expiresAt: '2026-11-01T00:00:00Z',
        failureCode: null,
      });
    if (path.endsWith('/version'))
      return deny
        ? json({ code: 'NOT_FOUND' }, 404)
        : json({ documentId: id, versionId: version });
    if (init?.method)
      keys.push((init.headers as Record<string, string>)['Idempotency-Key']!);
    if (path.endsWith('/versions'))
      return json({ documentId: id, revision: 1 }, 201);
    if (path.endsWith('/complete'))
      return json({ uploadId: id, receiptId: id });
    return json({ uploadId: id }, path.endsWith('/uploads') ? 201 : 200);
  });
  vi.stubGlobal('fetch', fetcher);
  const user = userEvent.setup();
  mount();
  await user.upload(
    document.querySelector('input[type=file]') as HTMLInputElement,
    new File(['data'], 'synthetic.pdf', { type: 'application/pdf' }),
  );
  await user.selectOptions(screen.getByLabelText('Document format'), 'DOCX');
  await user.click(screen.getByRole('button', { name: 'Upload and append' }));
  await screen.findByRole('alert');
  expect(
    screen.queryByRole('button', { name: 'Download verified content' }),
  ).toBeNull();
  expect(keys).toHaveLength(4);
  deny = false;
  await user.click(screen.getByRole('button', { name: 'Retry upload' }));
  await screen.findByText(`Version ${version}`);
  expect(keys).toHaveLength(4);
});
it('aborts an in-flight transfer when leaving the tenant workspace and never starts verification', async () => {
  let transferSignal: AbortSignal | undefined;
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.endsWith('/status'))
      return json({
        uploadId: id,
        state: 'RECEIVING',
        expiresAt: '2026-11-01T00:00:00Z',
        failureCode: null,
      });
    if (init?.method === 'PUT') {
      transferSignal = init.signal as AbortSignal;
      return new Promise<Response>((_resolve, reject) =>
        transferSignal?.addEventListener('abort', () =>
          reject(new Error('aborted')),
        ),
      );
    }
    return json({ uploadId: id }, 201);
  });
  vi.stubGlobal('fetch', fetcher);
  const user = userEvent.setup();
  const view = mount();
  await user.upload(
    document.querySelector('input[type=file]') as HTMLInputElement,
    new File(['data'], 'synthetic.pdf', { type: 'application/pdf' }),
  );
  await user.click(screen.getByRole('button', { name: 'Upload and append' }));
  await waitFor(() => expect(transferSignal).toBeDefined());
  view.unmount();
  await act(async () => {});
  expect(transferSignal?.aborted).toBe(true);
  expect(fetcher.mock.calls.some(([path]) => path.endsWith('/complete'))).toBe(
    false,
  );
});
