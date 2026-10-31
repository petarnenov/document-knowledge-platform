import { describe, expect, it } from 'vitest';
import {
  acceptsFaultUnavailable,
  type FaultRoute,
  type FaultService,
} from '../e2e-real/upload-fault-contract';

const bytes = (value: unknown) =>
  new TextEncoder().encode(JSON.stringify(value));

describe('genuine fault response acceptance contract', () => {
  it.each([
    ['dm', 'legacy-detail', 'UPSTREAM_UNAVAILABLE'],
    ['pg', 'legacy-detail', 'UPSTREAM_UNAVAILABLE'],
    ['pg', 'legacy-detail', 'UNAVAILABLE'],
    ...(['pg', 'rustfs', 'scanner', 'inspector', 'dm'] as const).map(
      (service) => [service, 'upload', 'UNAVAILABLE'] as const,
    ),
  ] as const)(
    'accepts only the finite %s %s outcome %s',
    (service, route, code) => {
      expect(
        acceptsFaultUnavailable(503, bytes({ code }), service, route),
      ).toBe(true);
    },
  );

  it.each([
    ['dm', 'legacy-detail', 'UNAVAILABLE'],
    ['rustfs', 'legacy-detail', 'UPSTREAM_UNAVAILABLE'],
    ['scanner', 'legacy-detail', 'UNAVAILABLE'],
    ['inspector', 'legacy-detail', 'UNAVAILABLE'],
    ['pg', 'upload', 'UPSTREAM_UNAVAILABLE'],
    ['dm', 'upload', 'UPSTREAM_UNAVAILABLE'],
    ['rustfs', 'upload', 'UPSTREAM_UNAVAILABLE'],
    [null, 'upload', 'UNAVAILABLE'],
  ] as const)('rejects wrong-domain %s %s code %s', (service, route, code) => {
    expect(acceptsFaultUnavailable(503, bytes({ code }), service, route)).toBe(
      false,
    );
  });

  it.each([200, 400, 401, 403, 404, 500, 502, 504])(
    'rejects status%s even with the otherwise exact DM-stopped body',
    (status) => {
      expect(
        acceptsFaultUnavailable(
          status,
          bytes({ code: 'UPSTREAM_UNAVAILABLE' }),
          'dm',
          'legacy-detail',
        ),
      ).toBe(false);
    },
  );

  it.each([
    null,
    [],
    'UNAVAILABLE',
    {},
    { code: 'UNAVAILABLE', message: 'proxy diagnostic' },
    { code: 'FORBIDDEN' },
    { code: 'UNAUTHENTICATED' },
    { code: 'UPSTREAM_UNAVAILABLE' },
    { code: true },
  ])('rejects malformed or unexpected upload payload %j', (payload) => {
    expect(acceptsFaultUnavailable(503, bytes(payload), 'pg', 'upload')).toBe(
      false,
    );
  });

  it('rejects proxy HTML, invalid JSON/UTF8, empty and oversized bodies', () => {
    for (const body of [
      new TextEncoder().encode('<html>Bad gateway</html>'),
      new TextEncoder().encode('{'),
      new Uint8Array([0xff]),
      new Uint8Array(),
      new TextEncoder().encode(
        JSON.stringify({ code: 'UNAVAILABLE' }) + ' '.repeat(4096),
      ),
    ])
      expect(acceptsFaultUnavailable(503, body, 'pg', 'upload')).toBe(false);
  });

  it('accepts the exact byte boundary but never a one-byte oversized response', () => {
    const json = JSON.stringify({ code: 'UNAVAILABLE' });
    const boundary = new TextEncoder().encode(
      json + ' '.repeat(4096 - json.length),
    );
    expect(acceptsFaultUnavailable(503, boundary, 'pg', 'upload')).toBe(true);
    expect(
      acceptsFaultUnavailable(
        503,
        new Uint8Array([...boundary, 32]),
        'pg',
        'upload',
      ),
    ).toBe(false);
  });

  it('never accepts arbitrary bodies for a DM-only outage on either route', () => {
    for (const route of ['legacy-detail', 'upload'] satisfies FaultRoute[])
      for (const status of [502, 503, 504])
        expect(
          acceptsFaultUnavailable(
            status,
            new TextEncoder().encode('arbitrary proxy failure'),
            'dm' satisfies FaultService,
            route,
          ),
        ).toBe(false);
  });
});
