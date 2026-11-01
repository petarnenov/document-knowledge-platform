import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
const id = '11111111-1111-4111-8111-111111111111';
const session = {
  issuer: 'https://idp.example',
  subject: 'staff',
  displayName: 'Synthetic',
  selectedTenant: 'tenant-a',
  tenants: ['tenant-a', 'tenant-b'],
  csrfToken: 'csrf',
};
const draft = {
  documentId: id,
  tenant: 'tenant-a',
  creator: { issuer: session.issuer, subject: 'staff' },
  createdAt: '2026-10-31T10:00:00Z',
  revision: 0,
  lifecycle: 'DRAFT',
  metadata: {
    title: 'Synthetic draft',
    description: 'Untrusted <script>text</script>',
  },
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
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
const open = (path = '/documents') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
afterEach(() => vi.unstubAllGlobals());
it('shows anonymous, unavailable and unselected session boundaries', async () => {
  const fetcher = vi.fn().mockResolvedValue(json({ code: 'DENIED' }, 401));
  vi.stubGlobal('fetch', fetcher);
  const first = open();
  expect(
    await screen.findByText('Sign in and choose a tenant to manage drafts.'),
  ).toBeTruthy();
  first.unmount();
  fetcher.mockResolvedValue(json({ code: 'UNAVAILABLE' }, 503));
  const second = open();
  expect(
    await screen.findByText('Identity services are unavailable.'),
  ).toBeTruthy();
  second.unmount();
  fetcher.mockResolvedValue(json({ ...session, selectedTenant: null }));
  open();
  expect(
    await screen.findByText('Choose a tenant before opening documents.'),
  ).toBeTruthy();
});
it('runs real request shapes for create, inspect, metadata, ACL and holds without treating receipts as documents', async () => {
  let revision = 0;
  const bodies: {
    path: string;
    body: Record<string, unknown>;
    init?: RequestInit;
  }[] = [];
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === '/api/v1/session') return json(session);
    if (init?.method !== 'GET') {
      bodies.push({
        path,
        body: JSON.parse(init?.body as string) as Record<string, unknown>,
        ...(init ? { init } : {}),
      });
      return json(
        { documentId: id, revision: ++revision },
        init?.method === 'POST' ? 201 : 200,
      );
    }
    if (path.includes('?'))
      return json({ items: [{ ...draft, revision }], nextCursor: null });
    if (path.endsWith('/acl'))
      return json({
        documentId: id,
        revision,
        rules: [
          {
            principal: draft.creator,
            action: 'document.inspect-draft',
            effect: 'ALLOW',
          },
        ],
      });
    if (path.endsWith('/legal-holds'))
      return json({
        items: [
          {
            holdId: id,
            documentId: id,
            revision,
            active: true,
            reason: 'Synthetic hold',
            placedBy: draft.creator,
            placedAt: draft.createdAt,
          },
        ],
      });
    return json({ ...draft, revision });
  });
  vi.stubGlobal('fetch', fetcher);
  const user = userEvent.setup();
  open();
  await user.click(await screen.findByRole('button', { name: 'New document' }));
  const collection = screen.getByLabelText(
    'Collection (metadata only)',
  ) as HTMLSelectElement;
  expect(collection.disabled).toBe(true);
  expect(collection.selectedOptions[0]?.textContent).toBe('Unavailable');
  const classification = screen.getByLabelText(
    'Classification',
  ) as HTMLInputElement;
  expect(classification.readOnly).toBe(true);
  expect(classification.value).toBe('development-restricted');
  await user.type(classification, 'public');
  expect(classification.value).toBe('development-restricted');
  await user.type(await screen.findByLabelText('Title'), 'New draft');
  await user.type(screen.getByLabelText('Description'), 'Description');
  const beforeTheme = fetcher.mock.calls.length;
  const themeSwitch = screen.getByRole('switch', { name: 'Light theme' });
  await user.click(themeSwitch);
  expect((screen.getByLabelText('Title') as HTMLInputElement).value).toBe(
    'New draft',
  );
  expect(
    (screen.getByLabelText('Description') as HTMLTextAreaElement).value,
  ).toBe('Description');
  expect(document.activeElement).toBe(themeSwitch);
  expect(fetcher.mock.calls.length).toBe(beforeTheme);
  await user.click(screen.getByRole('button', { name: 'Create draft' }));
  await screen.findByText(`Saved document ${id}, revision 1.`);
  expect(bodies[0]?.body).toEqual({
    classification: 'development-restricted',
    classificationPolicyRevision: 1,
    metadata: { title: 'New draft', description: 'Description' },
  });
  await user.click(
    await screen.findByRole('button', { name: 'Synthetic draft' }),
  );
  await screen.findByRole('heading', { name: 'Synthetic draft' });
  expect(screen.getByText('Untrusted <script>text</script>')).toBeTruthy();
  expect(document.querySelector('script')).toBeNull();
  await user.click(screen.getByText('Advanced document controls'));
  await user.click(
    screen.getByRole('button', { name: 'Use current metadata' }),
  );
  await user.click(screen.getByRole('button', { name: 'Save metadata' }));
  await screen.findByText(`Saved document ${id}, revision 2.`);
  await user.click(
    await screen.findByRole('button', { name: 'Inspect access rules' }),
  );
  await screen.findByLabelText('Access rules');
  await user.click(screen.getByRole('button', { name: 'Apply access change' }));
  await screen.findByText(`Saved document ${id}, revision 3.`);
  expect(bodies[2]?.body).toMatchObject({
    mode: 'RESTRICT',
    changes: [{ operation: 'REMOVE_ALLOW', principal: draft.creator }],
  });
  await user.selectOptions(
    await screen.findByLabelText('Access mode'),
    'DELEGATE',
  );
  await user.clear(screen.getByLabelText('Target subject'));
  await user.type(screen.getByLabelText('Target subject'), 'other');
  await user.selectOptions(
    screen.getByLabelText('Rule operation'),
    'ADD_ALLOW',
  );
  await user.selectOptions(
    screen.getByLabelText('Document action'),
    'document.update-metadata',
  );
  const beforeThemeAcl = bodies.length;
  await user.click(themeSwitch);
  expect(
    (screen.getByLabelText('Access mode') as HTMLSelectElement).value,
  ).toBe('DELEGATE');
  expect(
    (screen.getByLabelText('Rule operation') as HTMLSelectElement).value,
  ).toBe('ADD_ALLOW');
  expect(
    (screen.getByLabelText('Document action') as HTMLSelectElement).value,
  ).toBe('document.update-metadata');
  expect(
    (screen.getByLabelText('Target subject') as HTMLInputElement).value,
  ).toBe('other');
  expect(bodies.length).toBe(beforeThemeAcl);
  await user.click(screen.getByRole('button', { name: 'Apply access change' }));
  await screen.findByText(`Saved document ${id}, revision 4.`);
  expect(bodies[3]?.body).toMatchObject({
    mode: 'DELEGATE',
    changes: [
      {
        operation: 'ADD_ALLOW',
        action: 'document.update-metadata',
        principal: { subject: 'other' },
      },
    ],
  });
  await user.click(
    await screen.findByRole('button', { name: 'Inspect legal holds' }),
  );
  expect(await screen.findByLabelText('Legal holds')).toBeTruthy();
  await user.type(screen.getByLabelText('Hold reason'), 'Preserve');
  await user.click(themeSwitch);
  expect(
    (screen.getByLabelText('Hold reason') as HTMLTextAreaElement).value,
  ).toBe('Preserve');
  expect(document.activeElement).toBe(themeSwitch);
  await user.click(screen.getByRole('button', { name: 'Place hold' }));
  await screen.findByText(`Saved document ${id}, revision 5.`);
  expect(bodies[4]?.body).toMatchObject({
    expectedRevision: 4,
    reason: 'Preserve',
  });
});
it('does not render revoked inspection data and presents current denial and retry conflicts', async () => {
  let denied = false;
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === '/api/v1/session') return json(session);
    if (init?.method === 'PATCH') {
      denied = true;
      return json({ documentId: id, revision: 1 });
    }
    if (denied) return json({ code: 'NOT_FOUND' }, 404);
    return path.includes('?')
      ? json({ items: [draft], nextCursor: null })
      : json(draft);
  });
  vi.stubGlobal('fetch', fetcher);
  const user = userEvent.setup();
  open();
  await user.click(
    await screen.findByRole('button', { name: 'Synthetic draft' }),
  );
  await screen.findByRole('heading', { name: 'Synthetic draft' });
  await user.click(screen.getByText('Advanced document controls'));
  await user.click(screen.getByRole('button', { name: 'Apply access change' }));
  await waitFor(() =>
    expect(
      screen.queryByRole('heading', { name: 'Synthetic draft' }),
    ).toBeNull(),
  );
  expect(
    await screen.findAllByText(
      'This document is unavailable or you do not have access.',
    ),
  ).not.toHaveLength(0);
});
it('clears visible document state on session expiry', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string) =>
      path === '/api/v1/session'
        ? json(session)
        : json({ code: 'EXPIRED' }, 401),
    ),
  );
  open();
  expect(
    await screen.findByText('Your session has expired. Sign in again.'),
  ).toBeTruthy();
  expect(screen.queryByText('Synthetic draft')).toBeNull();
});
it('supports bounded next page and an empty result without invented counts', async () => {
  let next = false;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string) => {
      if (path === '/api/v1/session') return json(session);
      if (path.includes('cursor=')) {
        next = true;
        return json({ items: [], nextCursor: null });
      }
      return json({ items: [draft], nextCursor: 'opaque' });
    }),
  );
  const user = userEvent.setup();
  open();
  await user.click(await screen.findByRole('button', { name: 'Next page' }));
  expect(
    await screen.findByText('No accessible drafts on this page.'),
  ).toBeTruthy();
  expect(next).toBe(true);
  await user.click(screen.getByRole('button', { name: 'Refresh drafts' }));
  expect(
    await screen.findByRole('button', { name: 'Synthetic draft' }),
  ).toBeTruthy();
});

it('removes prior tenant draft caches before rendering a newly selected tenant', async () => {
  let current = { ...session };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init?: RequestInit) => {
      if (path.endsWith('/session/tenant')) {
        current = {
          ...session,
          selectedTenant: 'tenant-b',
          csrfToken: 'rotated',
        };
        return json(current);
      }
      if (path === '/api/v1/session') return json(current);
      if (init?.method === 'GET' && path.includes('?'))
        return json({
          items: [
            {
              ...draft,
              tenant: current.selectedTenant,
              metadata: {
                title:
                  current.selectedTenant === 'tenant-a'
                    ? 'Private tenant A'
                    : 'Private tenant B',
                description: '',
              },
            },
          ],
          nextCursor: null,
        });
      return json({ code: 'DENIED' }, 404);
    }),
  );
  const user = userEvent.setup();
  open();
  await screen.findByRole('button', { name: 'Private tenant A' });
  await user.click(screen.getByRole('link', { name: 'Workspace access' }));
  await screen.findByRole('heading', { name: 'Signed in as Synthetic' });
  await user.selectOptions(
    screen.getByLabelText('Tenant', { exact: true }),
    'tenant-b',
  );
  await user.click(screen.getByRole('button', { name: 'Use tenant' }));
  await screen.findByText('tenant-b', { selector: 'dd' });
  await user.click(screen.getByRole('link', { name: 'Documents' }));
  expect(screen.queryByRole('button', { name: 'Private tenant A' })).toBeNull();
  await screen.findByRole('button', { name: 'Private tenant B' });
  expect(screen.queryByText('Private tenant A')).toBeNull();
});

it('retries an uncertain mutation with its original idempotency key', async () => {
  const keys: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/v1/session') return json(session);
      if (init?.method === 'POST') {
        keys.push((init.headers as Record<string, string>)['Idempotency-Key']!);
        return keys.length === 1
          ? json({ code: 'UNAVAILABLE' }, 503)
          : json({ documentId: id, revision: 0 }, 201);
      }
      return json({ items: [], nextCursor: null });
    }),
  );
  const user = userEvent.setup();
  open();
  await user.click(await screen.findByRole('button', { name: 'New document' }));
  await user.type(await screen.findByLabelText('Title'), 'Retry draft');
  await user.click(screen.getByRole('button', { name: 'Create draft' }));
  await screen.findByText('Document services are unavailable. Try again.');
  await user.click(screen.getByRole('button', { name: 'Create draft' }));
  await screen.findByText(`Saved document ${id}, revision 0.`);
  expect(keys).toHaveLength(2);
  expect(keys[1]).toBe(keys[0]);
});

it('starts with record rows and filters only the authorized page without additional requests', async () => {
  const fetcher = vi.fn(async (path: string) =>
    path === '/api/v1/session'
      ? json(session)
      : json({
          items: [
            draft,
            {
              ...draft,
              documentId: '22222222-2222-4222-8222-222222222222',
              metadata: { title: 'Other policy', description: '' },
            },
          ],
          nextCursor: 'opaque',
        }),
  );
  vi.stubGlobal('fetch', fetcher);
  const user = userEvent.setup();
  open();
  await screen.findByRole('button', { name: 'Synthetic draft' });
  expect(screen.getByRole('table', { name: 'Document library' })).toBeTruthy();
  expect(screen.queryByLabelText('Title')).toBeNull();
  const before = fetcher.mock.calls.length;
  await user.type(
    screen.getByRole('textbox', { name: 'Search documents on this page' }),
    'other',
  );
  expect(screen.queryByRole('button', { name: 'Synthetic draft' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Other policy' })).toBeTruthy();
  expect(
    screen.getByText(/Search applies only to accessible drafts on this page/),
  ).toBeTruthy();
  expect(fetcher.mock.calls).toHaveLength(before);
  await user.clear(
    screen.getByRole('textbox', { name: 'Search documents on this page' }),
  );
  await user.type(
    screen.getByRole('textbox', { name: 'Search documents on this page' }),
    'absent',
  );
  expect(screen.getByText('No matching documents on this page.')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Next page' })).toBeTruthy();
});

it('reauthorizes a linked document before showing details and exposes upload as a separate task', async () => {
  const fetcher = vi.fn(async (path: string) =>
    path === '/api/v1/session'
      ? json(session)
      : path.includes('?')
        ? json({ items: [], nextCursor: null })
        : json(draft),
  );
  vi.stubGlobal('fetch', fetcher);
  const user = userEvent.setup();
  open(`/documents?documentId=${id}`);
  await screen.findByRole('heading', { name: 'Synthetic draft' });
  expect(
    fetcher.mock.calls.some(([path]) => path === `/api/v1/dm/documents/${id}`),
  ).toBe(true);
  expect(screen.getByText('Latest version')).toBeTruthy();
  expect(screen.getByText('Unknown')).toBeTruthy();
  expect(screen.getByText('Active published version')).toBeTruthy();
  expect(screen.queryByLabelText('Choose document file')).toBeNull();
  await user.click(screen.getByRole('button', { name: 'Upload version' }));
  expect(
    screen.getByRole('heading', { name: 'Add immutable version' }),
  ).toBeTruthy();
  expect(screen.getByLabelText('Choose document file')).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Back to document' }));
  expect(screen.queryByLabelText('Choose document file')).toBeNull();
  await user.click(screen.getByRole('button', { name: 'Back to library' }));
  expect(screen.getByRole('button', { name: 'New document' })).toBeTruthy();
});

it.each(['not-a-uuid', id])(
  'does not disclose a forbidden or malformed linked document (%s)',
  async (documentId) => {
    const fetcher = vi.fn(async (path: string) =>
      path === '/api/v1/session'
        ? json(session)
        : path.includes('?')
          ? json({ items: [], nextCursor: null })
          : json({ code: 'NOT_FOUND' }, 404),
    );
    vi.stubGlobal('fetch', fetcher);
    open(`/documents?documentId=${documentId}`);
    expect(
      await screen.findByText(
        'This document is unavailable or you do not have access.',
      ),
    ).toBeTruthy();
    expect(screen.queryByText('Synthetic draft')).toBeNull();
    expect(screen.queryByText('Active published version')).toBeNull();
    if (documentId === 'not-a-uuid')
      expect(fetcher.mock.calls.some(([path]) => path.includes('/dm/'))).toBe(
        false,
      );
  },
);

it('cancels metadata-only draft entry without posting or assuming an uploaded file', async () => {
  const fetcher = vi.fn(async (path: string) =>
    path === '/api/v1/session'
      ? json(session)
      : json({ items: [], nextCursor: null }),
  );
  vi.stubGlobal('fetch', fetcher);
  const user = userEvent.setup();
  open();
  await user.click(await screen.findByRole('button', { name: 'New document' }));
  expect(
    screen.getByRole('heading', { name: 'Create document draft' }),
  ).toBeTruthy();
  expect(screen.queryByLabelText('Choose document file')).toBeNull();
  await user.type(screen.getByLabelText('Title'), 'Unsaved draft');
  await user.click(screen.getByRole('button', { name: 'Back to library' }));
  expect(screen.queryByLabelText('Title')).toBeNull();
  expect(
    fetcher.mock.calls.every((call) => !call[0].includes('/uploads')),
  ).toBe(true);
  expect(screen.queryByText('Unsaved draft')).toBeNull();
});

it('opens the draft form from the Home New document action without creating server state', async () => {
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    expect(init?.method ?? 'GET').toBe('GET');
    return path === '/api/v1/session'
      ? json(session)
      : json({ items: [], nextCursor: null });
  });
  vi.stubGlobal('fetch', fetcher);
  const user = userEvent.setup();
  open('/');
  await user.click(await screen.findByRole('link', { name: /New document/ }));
  expect(
    await screen.findByRole('heading', { name: 'Create document draft' }),
  ).toBeTruthy();
  expect(screen.getByLabelText('Title')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Create draft' })).toBeTruthy();
  expect(screen.queryByLabelText('Choose document file')).toBeNull();
});
