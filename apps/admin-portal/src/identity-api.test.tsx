import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  getContext,
  getSession,
  IdentityRequestError,
  logout,
  selectTenant,
  type SessionView,
} from './identity-api';

const session: SessionView = {
  issuer: 'https://idp.example/realms/dkp',
  subject: 'user-1',
  displayName: 'Test User',
  tenants: ['tenant-a', 'tenant-b'],
  selectedTenant: 'tenant-a',
  csrfToken: 'csrf-1',
};
const signal = () => new AbortController().signal;
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status });

afterEach(() => vi.unstubAllGlobals());

describe('same-origin identity client', () => {
  it('keeps only safe session fields and never caches or follows redirects', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(json({ ...session, access_token: 'must-not-retain' }));
    vi.stubGlobal('fetch', fetcher);
    expect(await getSession(signal())).toEqual(session);
    expect(fetcher).toHaveBeenCalledWith(
      '/api/v1/session',
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        headers: { Accept: 'application/json' },
      }),
    );
    expect(fetcher.mock.calls[0]?.[1]).not.toHaveProperty('body');
  });

  it('posts tenant selection and logout with the current CSRF token', async () => {
    const selected = {
      ...session,
      selectedTenant: 'tenant-b',
      csrfToken: 'csrf-2',
    };
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(json(selected))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetcher);
    const rotated = await selectTenant(session, 'tenant-b', signal());
    expect(rotated).toEqual(selected);
    await logout(rotated, signal());
    expect(fetcher).toHaveBeenNthCalledWith(
      1,
      '/api/v1/session/tenant',
      expect.objectContaining({
        method: 'POST',
        body: '{"tenantId":"tenant-b"}',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': 'csrf-1',
        },
      }),
    );
    expect(fetcher).toHaveBeenNthCalledWith(
      2,
      '/api/v1/session/logout',
      expect.objectContaining({
        method: 'POST',
        body: '{}',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': 'csrf-2',
        },
      }),
    );
  });

  it('accepts independently verified context only for the current session actor and tenant', async () => {
    const context = {
      service: 'dm',
      issuer: session.issuer,
      subject: session.subject,
      tenant: 'tenant-a',
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(context)));
    expect(await getContext('dm', session, signal())).toEqual(context);
  });

  it.each([
    null,
    [],
    {},
    { ...session, issuer: '' },
    { ...session, issuer: 'x'.repeat(2049) },
    { ...session, subject: 12 },
    { ...session, displayName: '' },
    { ...session, tenants: 'tenant-a' },
    {
      ...session,
      tenants: Array.from({ length: 101 }, (_, index) => String(index)),
    },
    { ...session, tenants: [''] },
    { ...session, tenants: ['a', 'a'] },
    { ...session, tenants: ['x'.repeat(257)] },
    { ...session, csrfToken: '' },
    { ...session, csrfToken: 'x'.repeat(513) },
    { ...session, selectedTenant: 'unknown' },
    { ...session, selectedTenant: 8 },
  ])('rejects malformed session data: case %#', async (value) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(value)));
    await expect(getSession(signal())).rejects.toMatchObject({ status: 503 });
  });

  it('accepts the contract tenant boundary in both session and API context', async () => {
    const tenant = 'x'.repeat(256);
    const bounded = { ...session, tenants: [tenant], selectedTenant: tenant };
    const context = {
      service: 'dm',
      issuer: session.issuer,
      subject: session.subject,
      tenant,
    };
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(json(bounded))
        .mockResolvedValueOnce(json(context)),
    );
    expect(await getSession(signal())).toEqual(bounded);
    expect(await getContext('dm', bounded, signal())).toEqual(context);
  });

  it('accepts an explicit absence of selection and an empty membership list', async () => {
    const unselected = { ...session, selectedTenant: null, tenants: [] };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(unselected)));
    expect(await getSession(signal())).toEqual(unselected);
  });

  it.each([
    null,
    [],
    {
      service: 'kb',
      issuer: session.issuer,
      subject: session.subject,
      tenant: 'tenant-a',
    },
    {
      service: 'dm',
      issuer: 'wrong',
      subject: session.subject,
      tenant: 'tenant-a',
    },
    {
      service: 'dm',
      issuer: session.issuer,
      subject: 'wrong',
      tenant: 'tenant-a',
    },
    {
      service: 'dm',
      issuer: session.issuer,
      subject: session.subject,
      tenant: 'tenant-b',
    },
    {
      service: 'dm',
      issuer: session.issuer,
      subject: session.subject,
      tenant: null,
    },
  ])(
    'rejects malformed or mismatched service context: case %#',
    async (value) => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(value)));
      await expect(getContext('dm', session, signal())).rejects.toMatchObject({
        status: 503,
      });
    },
  );

  it.each([401, 403, 503])(
    'preserves HTTP %s for safe user-facing failure handling',
    async (status) => {
      vi.stubGlobal(
        'fetch',
        vi
          .fn()
          .mockResolvedValue(
            json({ code: 'private-provider-message' }, status),
          ),
      );
      await expect(getSession(signal())).rejects.toMatchObject({
        status,
        message: 'Identity request failed',
      });
    },
  );

  it('fails safely when the network or response JSON is unavailable', async () => {
    const fetcher = vi
      .fn()
      .mockRejectedValueOnce(new Error('sensitive diagnostics'))
      .mockResolvedValueOnce(new Response('<html>not JSON</html>'));
    vi.stubGlobal('fetch', fetcher);
    await expect(getSession(signal())).rejects.toEqual(
      new IdentityRequestError(503),
    );
    await expect(getSession(signal())).rejects.toEqual(
      new IdentityRequestError(503),
    );
  });
});
