import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { App } from './App';
const id = '11111111-1111-4111-8111-111111111111';
const session = {
  issuer: 'https://issuer.example',
  subject: 'staff',
  displayName: 'Synthetic',
  tenants: ['tenant-a', 'tenant-b'],
  selectedTenant: 'tenant-a',
  csrfToken: 'csrf',
};
const collection = {
  collectionId: id,
  revision: 1,
  metadata: {
    title: 'Synthetic knowledge',
    description: 'Untrusted <script>metadata</script>',
  },
  status: 'ACTIVE',
  membershipRevision: 1,
  audiencePolicyRevision: 1,
  classificationCeiling: 'development-restricted',
  classificationPolicyRevision: 1,
};
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Cache-Control': 'no-store',
    },
  });
const open = () =>
  render(
    <MemoryRouter initialEntries={['/knowledge']}>
      <App />
    </MemoryRouter>,
  );
afterEach(() => vi.unstubAllGlobals());
it('requires current session and selected tenant', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValue(json({ code: 'UNAUTHENTICATED' }, 401));
  vi.stubGlobal('fetch', fetcher);
  const first = open();
  await screen.findByText('Sign in and choose a tenant to manage knowledge.');
  first.unmount();
  fetcher.mockResolvedValue(json({ ...session, selectedTenant: null }));
  open();
  await screen.findByText('Choose a tenant before opening collections.');
  expect(fetcher).toHaveBeenCalledTimes(2);
});
it.each(['dark', 'light'])(
  'runs every knowledge workflow in %s theme',
  async (theme) => {
    window.localStorage.setItem('mantine-color-scheme', theme);
    const user = userEvent.setup();
    let revision = 1;
    let status = 'ACTIVE';
    let state = 'ACTIVE';
    const commands: {
      path: string;
      body: Record<string, unknown>;
      headers: Record<string, string>;
    }[] = [];
    const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/v1/session') return json(session);
      if (init?.method !== 'GET') {
        const body = JSON.parse(init?.body as string) as Record<
          string,
          unknown
        >;
        commands.push({
          path,
          body,
          headers: init?.headers as Record<string, string>,
        });
        if (path.endsWith('audience-evaluations'))
          return json({
            collectionId: id,
            audiencePolicyRevision: 1,
            decision: 'ALLOW',
          });
        if (path.endsWith('policy-snapshots'))
          return json(
            {
              snapshotToken: 'a'.repeat(43),
              documentId: id,
              documentRevision: 1,
              classificationPolicyRevision: 1,
              decision: 'COMPATIBLE',
            },
            201,
          );
        if (path.endsWith('policy-validations'))
          return json({
            documentId: id,
            documentRevision: 1,
            classificationPolicyRevision: 1,
            decision: 'COMPATIBLE',
          });
        if (path.endsWith('/status')) status = body.status as string;
        if (path.includes('/memberships')) {
          if (body.state) state = body.state as string;
          return json(
            { collectionId: id, membershipId: id, revision: ++revision },
            init?.method === 'POST' ? 201 : 200,
          );
        }
        return json(
          { collectionId: id, revision: ++revision },
          init?.method === 'POST' ? 201 : 200,
        );
      }
      if (path.endsWith('/acl'))
        return json({ collectionId: id, revision, rules: [] });
      if (path.endsWith('/audience'))
        return json({
          collectionId: id,
          revision: 1,
          classificationCeiling: 'development-restricted',
          classificationPolicyRevision: 1,
          rules: [],
          effectiveAt: '2026-11-01T00:00:00Z',
        });
      if (path.includes('/memberships?'))
        return json({
          items: [
            {
              membershipId: id,
              collectionId: id,
              documentId: id,
              revision: 1,
              state,
              sharingScope: 'SELECTED_TENANT',
              creator: { issuer: session.issuer, subject: session.subject },
              createdAt: '2026-11-01T00:00:00Z',
              reason: 'Synthetic',
            },
          ],
          nextCursor: path.includes('cursor=') ? null : 'members-next',
        });
      if (path.includes('/collections?'))
        return json({
          items: [{ ...collection, revision, status }],
          nextCursor: path.includes('cursor=') ? null : 'collections-next',
        });
      return json({ ...collection, revision, status });
    });
    vi.stubGlobal('fetch', fetcher);
    open();
    await screen.findByRole('button', { name: 'Synthetic knowledge' });
    await user.type(
      screen.getByLabelText(/New collection title/),
      'New knowledge',
    );
    await user.click(screen.getByRole('button', { name: 'Create collection' }));
    await screen.findByText(
      'The command was accepted. Access is checked again when viewing results.',
    );
    await user.click(
      screen.getByRole('button', { name: 'Synthetic knowledge' }),
    );
    await screen.findByRole('region', { name: 'Selected collection' });
    await user.click(
      screen.getByRole('button', { name: 'Load current metadata' }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Save collection metadata' }),
    );
    await waitFor(() =>
      expect(
        commands.some((c) => c.path === `/api/v1/kb/collections/${id}`),
      ).toBe(true),
    );
    await user.type(screen.getByLabelText('Change reason'), 'Synthetic reason');
    await user.click(
      screen.getByRole('button', { name: 'Archive collection' }),
    );
    await screen.findByRole('button', { name: 'Unarchive collection' });
    await user.click(
      screen.getByRole('button', { name: 'Unarchive collection' }),
    );
    await screen.findByRole('button', { name: 'Archive collection' });
    await user.click(screen.getByRole('button', { name: 'Collection ACL' }));
    await screen.findByRole('heading', { name: 'Current collection ACL' });
    await user.selectOptions(
      screen.getByLabelText('ACL change mode'),
      'DELEGATE',
    );
    for (const operation of [
      'ADD_ALLOW',
      'REMOVE_ALLOW',
      'ADD_DENY',
      'REMOVE_DENY',
    ]) {
      await user.selectOptions(
        screen.getByLabelText('ACL operation'),
        operation,
      );
      await user.click(screen.getByRole('button', { name: 'Add ACL change' }));
    }
    await user.click(
      screen.getByRole('button', { name: 'Remove ACL change 4' }),
    );
    await user.click(screen.getByRole('button', { name: 'Add ACL change' }));
    await user.click(screen.getByRole('button', { name: 'Apply ACL changes' }));
    await waitFor(() =>
      expect(commands.some((c) => c.path.endsWith('/acl'))).toBe(true),
    );
    await user.click(screen.getByRole('button', { name: 'Audience policy' }));
    await screen.findByText(/Policy revision 1/);
    await user.click(
      screen.getByRole('button', { name: 'Edit current audience rules' }),
    );
    for (const kind of ['PRINCIPAL', 'TENANT', 'FIRM', 'RESOURCE_SCOPE']) {
      await user.selectOptions(
        screen.getByLabelText('Audience selector kind'),
        kind,
      );
      const field = screen.getByLabelText(
        kind === 'PRINCIPAL'
          ? 'Audience subject'
          : kind === 'TENANT'
            ? 'Audience tenant'
            : kind === 'FIRM'
              ? 'Audience firm ID'
              : 'Audience resource scope ID',
      );
      await user.clear(field);
      await user.type(field, kind === 'TENANT' ? 'tenant-a' : 'synthetic');
      await user.click(
        screen.getByRole('button', { name: 'Add audience rule' }),
      );
    }
    await user.selectOptions(
      screen.getByLabelText('Audience rule effect'),
      'DENY',
    );
    await user.click(screen.getByRole('button', { name: 'Add audience rule' }));
    await user.click(
      screen.getByRole('button', { name: 'Remove audience rule 5' }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Replace audience policy' }),
    );
    await waitFor(() =>
      expect(commands.some((c) => c.path.endsWith('/audience'))).toBe(true),
    );
    await user.click(
      screen.getByRole('button', { name: 'Evaluate my audience access' }),
    );
    await screen.findByText(/Your audience decision is ALLOW/);
    await user.click(screen.getByRole('button', { name: 'Memberships' }));
    await screen.findByRole('button', { name: `Deactivate membership ${id}` });
    await user.clear(screen.getByLabelText('Current document revision'));
    await user.type(screen.getByLabelText('Current document revision'), '1');
    await user.click(
      screen.getByRole('button', { name: `Deactivate membership ${id}` }),
    );
    await screen.findByRole('button', { name: `Activate membership ${id}` });
    await user.click(
      screen.getByRole('button', { name: `Activate membership ${id}` }),
    );
    await screen.findByRole('button', { name: `Deactivate membership ${id}` });
    await user.type(screen.getByLabelText('Document ID to attach'), id);
    await user.click(screen.getByRole('button', { name: 'Attach document' }));
    await waitFor(() =>
      expect(commands.some((c) => c.path.endsWith('/memberships'))).toBe(true),
    );
    await user.click(
      screen.getByRole('button', { name: 'Next memberships page' }),
    );
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([p]) => p.includes('cursor=members-next')),
      ).toBe(true),
    );
    await user.click(
      screen.getByRole('button', { name: 'First memberships page' }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Next collections page' }),
    );
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([p]) => p.includes('cursor=collections-next')),
      ).toBe(true),
    );
    await user.click(
      screen.getByRole('button', { name: 'First collections page' }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Create policy snapshot' }),
    );
    await screen.findByText(/Complete policy decision: COMPATIBLE/);
    await user.click(
      screen.getByRole('button', { name: 'Validate current policy snapshot' }),
    );
    await waitFor(() =>
      expect(commands.some((c) => c.path.endsWith('/policy-validations'))).toBe(
        true,
      ),
    );
    expect(screen.queryByText('a'.repeat(43))).toBeNull();
    expect(commands.every((c) => c.headers['X-CSRF-TOKEN'] === 'csrf')).toBe(
      true,
    );
    expect(
      commands
        .filter(
          (c) =>
            !c.path.endsWith('evaluations') && !c.path.endsWith('validations'),
        )
        .every((c) => Boolean(c.headers['Idempotency-Key'])),
    ).toBe(true);
    expect(commands.some((c) => 'actor' in c.body || 'tenant' in c.body)).toBe(
      false,
    );
  },
  // This case exercises the complete command sequence with real DOM events in
  // each theme. Allow coverage instrumentation time without changing API deadlines.
  20_000,
);
it('clears protected data on denied refresh and reopens only with fresh authorization', async () => {
  const user = userEvent.setup();
  let denied = false;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string) =>
      path === '/api/v1/session'
        ? json(session)
        : denied
          ? json({ code: 'NOT_FOUND' }, 404)
          : path.includes('?')
            ? json({ items: [collection], nextCursor: null })
            : json(collection),
    ),
  );
  open();
  await user.click(
    await screen.findByRole('button', { name: 'Synthetic knowledge' }),
  );
  await screen.findByRole('region', { name: 'Selected collection' });
  denied = true;
  await user.click(screen.getByRole('button', { name: 'Refresh knowledge' }));
  await screen.findByText(
    'This collection or policy is unavailable or you do not have access.',
  );
  await waitFor(() =>
    expect(
      screen.queryByRole('region', { name: 'Selected collection' }),
    ).toBeNull(),
  );
  expect(
    screen.queryByRole('button', { name: 'Synthetic knowledge' }),
  ).toBeNull();
  denied = false;
  await user.click(screen.getByRole('button', { name: 'Refresh knowledge' }));
  await screen.findByRole('button', { name: 'Synthetic knowledge' });
});
it('reconciles an ambiguous command with the identical key and body before reopening protected views', async () => {
  const user = userEvent.setup();
  let uncertain = true;
  const writes: { key: string; body: string }[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/v1/session') return json(session);
      if (init?.method === 'POST') {
        writes.push({
          key: (init.headers as Record<string, string>)['Idempotency-Key']!,
          body: init.body as string,
        });
        if (uncertain) return json({ code: 'UNAVAILABLE' }, 503);
        return json({ collectionId: id, revision: 1 }, 201);
      }
      return json({ items: [], nextCursor: null });
    }),
  );
  open();
  await screen.findByText('No accessible collections on this page.');
  await user.type(
    screen.getByLabelText(/New collection title/),
    'Uncertain create',
  );
  await user.click(screen.getByRole('button', { name: 'Create collection' }));
  await screen.findByRole('button', {
    name: 'Retry uncertain command with original key',
  });
  uncertain = false;
  await user.click(
    screen.getByRole('button', {
      name: 'Retry uncertain command with original key',
    }),
  );
  await screen.findByText(
    'The original command was confirmed. Refresh to check current access.',
  );
  expect(writes).toHaveLength(2);
  expect(writes[1]).toEqual(writes[0]);
  expect(
    screen.queryByRole('region', { name: 'Selected collection' }),
  ).toBeNull();
});
it.each(['collections', 'memberships'])(
  'refreshes stale %s pages after commands and known409 without resending old cursors or intents',
  async (kind) => {
    const user = userEvent.setup();
    let afterCommand = false;
    let writes = 0;
    const requests: string[] = [];
    const bodies: unknown[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (path: string, init?: RequestInit) => {
        if (path === '/api/v1/session') return json(session);
        requests.push(path);
        if (init?.method !== 'GET') {
          writes++;
          bodies.push(JSON.parse(init?.body as string));
          afterCommand = true;
          return json({ collectionId: id, revision: 2 });
        }
        const members = path.includes('/memberships?');
        const page = path.includes('/collections?') || members;
        if (page) {
          if (path.includes('cursor=') && afterCommand)
            return json({ code: 'STALE_AUTHORITY' }, 409);
          return members
            ? json({
                items: [],
                nextCursor: path.includes('cursor=') ? null : 'members-second',
              })
            : json({
                items: [{ ...collection, revision: afterCommand ? 2 : 1 }],
                nextCursor: path.includes('cursor=')
                  ? null
                  : 'collections-second',
              });
        }
        return json({ ...collection, revision: afterCommand ? 2 : 1 });
      }),
    );
    open();
    await user.click(
      await screen.findByRole('button', { name: 'Synthetic knowledge' }),
    );
    await screen.findByRole('region', { name: 'Selected collection' });
    const next =
      kind === 'collections'
        ? 'Next collections page'
        : 'Next memberships page';
    const first =
      kind === 'collections'
        ? 'First collections page'
        : 'First memberships page';
    const marker =
      kind === 'collections' ? 'collections-second' : 'members-second';
    if (kind === 'memberships') {
      await user.click(screen.getByRole('button', { name: 'Memberships' }));
      await screen.findByText('No accessible memberships on this page.');
    }
    await user.click(screen.getByRole('button', { name: next }));
    await waitFor(() =>
      expect(requests.some((path) => path.includes(`cursor=${marker}`))).toBe(
        true,
      ),
    );
    const before = requests.filter((path) =>
      path.includes(`cursor=${marker}`),
    ).length;
    await user.click(
      screen.getByRole('button', { name: 'Collection settings' }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Load current metadata' }),
    );
    await user.click(
      screen.getByRole('button', { name: 'Save collection metadata' }),
    );
    await waitFor(() => expect(writes).toBe(1));
    await screen.findByText(
      'The command was accepted. Access is checked again when viewing results.',
    );
    await waitFor(() =>
      expect(
        requests.filter((path) => path.includes(`cursor=${marker}`)),
      ).toHaveLength(before),
    );
    expect(bodies[0]).toEqual({
      expectedRevision: 1,
      metadata: collection.metadata,
    });
    if (kind === 'memberships')
      await user.click(screen.getByRole('button', { name: 'Memberships' }));
    await waitFor(() =>
      expect(
        (screen.getByRole('button', { name: first }) as HTMLButtonElement)
          .disabled,
      ).toBe(true),
    );
    // The server now rejects that prior view. A fresh page failure must be recoverable.
    await user.click(screen.getByRole('button', { name: next }));
    await screen.findByText(
      'The knowledge state changed. Refresh before trying again.',
    );
    const rejectedCount = requests.filter((path) =>
      path.includes(`cursor=${marker}`),
    ).length;
    await user.click(screen.getByRole('button', { name: 'Refresh knowledge' }));
    await screen.findByRole('button', { name: 'Synthetic knowledge' });
    expect(
      requests.filter((path) => path.includes(`cursor=${marker}`)),
    ).toHaveLength(rejectedCount);
    expect(writes).toBe(1);
    await user.click(
      screen.getByRole('button', { name: 'Synthetic knowledge' }),
    );
    await screen.findByRole('region', { name: 'Selected collection' });
    if (kind === 'memberships') {
      await user.click(screen.getByRole('button', { name: 'Memberships' }));
      await screen.findByText('No accessible memberships on this page.');
    }
    expect(
      (screen.getByRole('button', { name: first }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(screen.queryByRole('alert')).toBeNull();
  },
);
