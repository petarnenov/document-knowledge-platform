import type { BrowserSession } from '../../../generated-clients/typescript/types/models/BrowserSession';
import type { SecurityContext } from '../../../generated-clients/typescript/types/models/SecurityContext';

// JSON represents the contract's unique tenant collection as an array.
export type SessionView = Omit<BrowserSession, 'tenants'> & {
  tenants: string[];
};
export type Service = SecurityContext['service'];
export type { SecurityContext };

export class IdentityRequestError extends Error {
  constructor(public readonly status: number) {
    super('Identity request failed');
  }
}

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function text(value: unknown, maximum = 256): value is string {
  return (
    typeof value === 'string' && value.length > 0 && value.length <= maximum
  );
}

function parseSession(value: unknown): SessionView {
  if (
    !object(value) ||
    !text(value.issuer, 2048) ||
    !text(value.subject) ||
    !text(value.displayName) ||
    !Array.isArray(value.tenants) ||
    value.tenants.length > 100 ||
    !value.tenants.every((tenant: unknown) => text(tenant, 256)) ||
    new Set(value.tenants).size !== value.tenants.length ||
    !text(value.csrfToken, 512) ||
    (value.selectedTenant !== null &&
      (!text(value.selectedTenant, 256) ||
        !value.tenants.includes(value.selectedTenant)))
  ) {
    throw new IdentityRequestError(503);
  }
  return {
    issuer: value.issuer,
    subject: value.subject,
    displayName: value.displayName,
    tenants: value.tenants as string[],
    selectedTenant: value.selectedTenant,
    csrfToken: value.csrfToken,
  };
}

async function request(
  path: string,
  signal: AbortSignal,
  mutation?: { csrfToken: string; body: object },
): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: mutation ? 'POST' : 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      signal: AbortSignal.any([signal, AbortSignal.timeout(10_000)]),
      headers: mutation
        ? {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': mutation.csrfToken,
          }
        : { Accept: 'application/json' },
      ...(mutation ? { body: JSON.stringify(mutation.body) } : {}),
    });
  } catch {
    throw new IdentityRequestError(503);
  }
  if (!response.ok) throw new IdentityRequestError(response.status);
  if (response.status === 204) return undefined;
  try {
    return (await response.json()) as unknown;
  } catch {
    throw new IdentityRequestError(503);
  }
}

export async function getSession(signal: AbortSignal): Promise<SessionView> {
  return parseSession(await request('/api/v1/session', signal));
}

export async function selectTenant(
  session: SessionView,
  tenantId: string,
  signal: AbortSignal,
): Promise<SessionView> {
  return parseSession(
    await request('/api/v1/session/tenant', signal, {
      csrfToken: session.csrfToken,
      body: { tenantId },
    }),
  );
}

export async function logout(
  session: SessionView,
  signal: AbortSignal,
): Promise<void> {
  await request('/api/v1/session/logout', signal, {
    csrfToken: session.csrfToken,
    body: {},
  });
}

export async function getContext(
  service: Service,
  session: SessionView,
  signal: AbortSignal,
): Promise<SecurityContext> {
  const value = await request(`/api/v1/security/context/${service}`, signal);
  if (
    !object(value) ||
    value.service !== service ||
    value.issuer !== session.issuer ||
    value.subject !== session.subject ||
    value.tenant !== session.selectedTenant ||
    !text(value.tenant, 256)
  ) {
    throw new IdentityRequestError(503);
  }
  return {
    service,
    issuer: session.issuer,
    subject: session.subject,
    tenant: value.tenant,
  };
}
