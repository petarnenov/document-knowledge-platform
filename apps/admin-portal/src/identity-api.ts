import { withAuthority, type RequestSignal } from './request-authority';
import { z } from 'zod';
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

const bounded = (max: number) => z.string().min(1).max(max);
const sessionSchema = z
  .object({
    issuer: bounded(2048),
    subject: bounded(256),
    displayName: bounded(256),
    tenants: z.array(bounded(256)).max(100),
    csrfToken: bounded(512),
    selectedTenant: bounded(256).nullable(),
  })
  .refine(
    (value) =>
      new Set(value.tenants).size === value.tenants.length &&
      (value.selectedTenant === null ||
        value.tenants.includes(value.selectedTenant)),
  );
function parseSession(value: unknown): SessionView {
  const parsed = sessionSchema.safeParse(value);
  if (!parsed.success) throw new IdentityRequestError(503);
  return parsed.data;
}

async function request(
  path: string,
  signal: RequestSignal,
  mutation?: { csrfToken: string; body: object },
): Promise<unknown> {
  return withAuthority(signal, async (signal) => {
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
  });
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
  signal: RequestSignal,
): Promise<SecurityContext> {
  const value = await request(`/api/v1/security/context/${service}`, signal);
  const parsed = z
    .object({
      service: z.enum(['dm', 'kb', 'search']),
      issuer: bounded(2048),
      subject: bounded(256),
      tenant: bounded(256),
    })
    .safeParse(value);
  if (
    !parsed.success ||
    parsed.data.service !== service ||
    parsed.data.issuer !== session.issuer ||
    parsed.data.subject !== session.subject ||
    parsed.data.tenant !== session.selectedTenant
  )
    throw new IdentityRequestError(503);

  return {
    service,
    issuer: session.issuer,
    subject: session.subject,
    tenant: parsed.data.tenant,
  };
}
