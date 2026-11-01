import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { Knowledge } from './Knowledge';
import { PortalTheme } from './theme';
import { sessionKey } from './session-key';
const id = '11111111-1111-4111-8111-111111111111';
const session = {
  issuer: 'https://issuer.example',
  subject: 'staff',
  displayName: 'Synthetic',
  tenants: ['tenant-a', 'tenant-b'],
  selectedTenant: 'tenant-a',
  csrfToken: 'csrf',
};
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Cache-Control': 'no-store',
    },
  });
function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  let state = 'INACTIVE',
    revision = 1,
    memberReads = 0,
    detailReads = 0,
    patches = 0;
  let holdDetail = false;
  let releaseDetail: (() => void) | undefined;
  let holdRead = false,
    holdPatch = false;
  let releaseRead: ((response: Response) => void) | undefined;
  let releasePatch: (() => void) | undefined;
  let readSignal: AbortSignal | undefined;
  const collection = () => ({
    collectionId: id,
    revision,
    metadata: { title: 'Scoped collection', description: '' },
    status: 'ACTIVE',
    membershipRevision: 1,
    audiencePolicyRevision: 1,
    classificationCeiling: 'development-restricted',
    classificationPolicyRevision: 1,
  });
  const members = () => ({
    items: [
      {
        membershipId: id,
        collectionId: id,
        documentId: id,
        revision: state === 'ACTIVE' ? 1 : 0,
        state,
        sharingScope: 'SELECTED_TENANT',
        creator: { issuer: session.issuer, subject: session.subject },
        createdAt: '2026-11-01T00:00:00Z',
        reason: 'Synthetic',
      },
    ],
    nextCursor: null,
  });
  const fetcher = vi.fn(
    async (path: string, init?: RequestInit): Promise<Response> => {
      if (path === '/api/v1/session') return json(session);
      if (init?.method === 'PATCH') {
        patches++;
        if (holdPatch)
          await new Promise<void>((resolve) => {
            releasePatch = resolve;
          });
        state = 'ACTIVE';
        revision++;
        return json({ collectionId: id, membershipId: id, revision });
      }
      if (path.includes('/memberships?')) {
        memberReads++;
        if (holdRead) {
          readSignal = init!.signal!;
          return new Promise<Response>((resolve, reject) => {
            releaseRead = resolve;
            readSignal!.addEventListener(
              'abort',
              () =>
                reject(
                  new DOMException(
                    'Synthetic transport canceled',
                    'AbortError',
                  ),
                ),
              { once: true },
            );
          });
        }
        return json(members());
      }
      if (path.includes('/collections?'))
        return json({ items: [collection()], nextCursor: null });
      detailReads++;
      if (holdDetail)
        await new Promise<void>((resolve) => {
          releaseDetail = resolve;
        });
      return json(collection());
    },
  );
  vi.stubGlobal('fetch', fetcher);
  const rendered = render(
    <MemoryRouter>
      <PortalTheme>
        <QueryClientProvider client={client}>
          <Knowledge />
        </QueryClientProvider>
      </PortalTheme>
    </MemoryRouter>,
  );
  const user = userEvent.setup();
  return {
    client,
    user,
    rendered,
    members,
    counts: () => ({ memberReads, detailReads, patches }),
    holdDetail: () => {
      holdDetail = true;
    },
    releaseDetail: () => {
      holdDetail = false;
      releaseDetail?.();
    },
    holdRead: () => {
      holdRead = true;
    },
    holdPatch: () => {
      holdPatch = true;
    },
    signal: () => readSignal,
    releaseRead: (status = 200) => {
      holdRead = false;
      releaseRead?.(
        json(status === 200 ? members() : { code: 'UNAVAILABLE' }, status),
      );
    },
    releasePatch: () => {
      holdPatch = false;
      releasePatch?.();
    },
  };
}
async function open(fixture: ReturnType<typeof setup>) {
  await fixture.user.click(
    await screen.findByRole('button', { name: 'Scoped collection' }),
  );
  await screen.findByRole('region', { name: 'Selected collection' });
  await fixture.user.type(
    screen.getByLabelText('Change reason'),
    'Synthetic change',
  );
  await fixture.user.click(screen.getByRole('button', { name: 'Memberships' }));
  const button = await screen.findByRole('button', {
    name: `Activate membership ${id}`,
  });
  await waitFor(() =>
    expect((button as HTMLButtonElement).disabled).toBe(false),
  );
  return button;
}
const memberQuery = {
  predicate: (q: { queryKey: readonly unknown[] }) =>
    q.queryKey[0] === 'knowledge' && q.queryKey[5] === 'members',
};
afterEach(() => vi.unstubAllGlobals());
it('does not dispatch a cached member action while actual members transport is held; enables after settlement', async () => {
  const f = setup();
  const button = await open(f);
  f.holdRead();
  let pending!: Promise<void>;
  act(() => {
    pending = f.client.refetchQueries(memberQuery);
  });
  await waitFor(() => expect(f.counts().memberReads).toBe(2));
  try {
    expect((button as HTMLButtonElement).disabled).toBe(true);
    await f.user.click(button);
    expect(f.counts().patches).toBe(0);
  } finally {
    await act(async () => {
      f.releaseRead();
      await pending;
    });
  }
  await waitFor(() =>
    expect((button as HTMLButtonElement).disabled).toBe(false),
  );
  await f.user.click(button);
  await screen.findByRole('button', { name: `Deactivate membership ${id}` });
  expect(f.counts().patches).toBe(1);
});
it('checks live query state when a click races the pending-render notification', async () => {
  const f = setup();
  const button = await open(f);
  f.holdRead();
  let pending!: Promise<void>;
  act(() => {
    pending = f.client.refetchQueries(memberQuery);
    fireEvent.click(button);
  });
  try {
    expect(f.counts().patches).toBe(0);
  } finally {
    await act(async () => {
      f.releaseRead();
      await pending;
    });
  }
});
it('waits for current detail revision before allowing a cached membership command', async () => {
  const f = setup();
  const button = await open(f);
  f.holdDetail();
  let pending!: Promise<void>;
  act(() => {
    pending = f.client.refetchQueries({
      predicate: (q) =>
        q.queryKey[0] === 'knowledge' && q.queryKey[5] === 'detail',
    });
  });
  await waitFor(() => expect(f.counts().detailReads).toBe(2));
  try {
    expect((button as HTMLButtonElement).disabled).toBe(true);
    await f.user.click(button);
    expect(f.counts().patches).toBe(0);
  } finally {
    await act(async () => {
      f.releaseDetail();
      await pending;
    });
  }
  await waitFor(() =>
    expect((button as HTMLButtonElement).disabled).toBe(false),
  );
});
it('does not start conflicting members refetch during an actual pending command and refreshes after response', async () => {
  const f = setup();
  const button = await open(f);
  f.holdPatch();
  await f.user.click(button);
  await waitFor(() => expect(f.counts().patches).toBe(1));
  try {
    await act(async () => {
      await f.client.refetchQueries(memberQuery);
    });
    expect(f.counts().memberReads).toBe(1);
  } finally {
    await act(async () => {
      f.releasePatch();
    });
  }
  await screen.findByRole('button', { name: `Deactivate membership ${id}` });
  expect(f.counts().memberReads).toBeGreaterThan(1);
});
it('a genuine members503 still clears protected selection and cache', async () => {
  const f = setup();
  await open(f);
  f.holdRead();
  let pending!: Promise<void>;
  act(() => {
    pending = f.client.refetchQueries(memberQuery);
  });
  await waitFor(() => expect(f.counts().memberReads).toBe(2));
  await act(async () => {
    f.releaseRead(503);
    await pending;
  });
  await waitFor(() =>
    expect(
      screen.queryByRole('region', { name: 'Selected collection' }),
    ).toBeNull(),
  );
  expect(
    f.client
      .getQueryCache()
      .findAll({ predicate: (q) => q.queryKey[0] === 'knowledge' })
      .every((q) => q.state.data === undefined),
  ).toBe(true);
});
it.each(['unmount', 'tenant-change'] as const)(
  'pending transport is canceled on %s without late mutation or old-scope data',
  async (mode) => {
    const f = setup();
    await open(f);
    f.holdRead();
    let pending!: Promise<void>;
    act(() => {
      pending = f.client.refetchQueries(memberQuery);
    });
    await waitFor(() => expect(f.counts().memberReads).toBe(2));
    await act(async () => {
      if (mode === 'unmount') f.rendered.unmount();
      else
        f.client.setQueryData(sessionKey, {
          ...session,
          selectedTenant: 'tenant-b',
        });
      await pending;
    });
    expect(f.signal()?.aborted).toBe(true);
    expect(f.counts().patches).toBe(0);
    expect(
      f.client.getQueryCache().findAll({
        predicate: (q) =>
          q.queryKey[0] === 'knowledge' && q.queryKey[3] === 'tenant-a',
      }),
    ).toHaveLength(0);
    f.releaseRead();
  },
);
