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
const open = () =>
  render(
    <MemoryRouter initialEntries={['/documents']}>
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
  await user.type(await screen.findByLabelText('Title'), 'New draft');
  await user.type(screen.getByLabelText('Description'), 'Description');
  await user.click(screen.getByRole('button', { name: 'Create draft' }));
  await screen.findByText(`Saved document ${id}, revision 1.`);
  expect(bodies[0]?.body).toMatchObject({
    classification: 'development-restricted',
    metadata: { title: 'New draft' },
  });
  await user.click(
    await screen.findByRole('button', { name: 'Synthetic draft' }),
  );
  await screen.findByRole('heading', { name: 'Synthetic draft' });
  expect(screen.getByText('Untrusted <script>text</script>')).toBeTruthy();
  expect(document.querySelector('script')).toBeNull();
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
  await user.type(await screen.findByLabelText('Title'), 'Retry draft');
  await user.click(screen.getByRole('button', { name: 'Create draft' }));
  await screen.findByText('Document services are unavailable. Try again.');
  await user.click(screen.getByRole('button', { name: 'Create draft' }));
  await screen.findByText(`Saved document ${id}, revision 0.`);
  expect(keys).toHaveLength(2);
  expect(keys[1]).toBe(keys[0]);
});
