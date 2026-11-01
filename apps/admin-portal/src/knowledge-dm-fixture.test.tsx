import { expect, it } from 'vitest';
import {
  dmFixtureBody,
  dmFixtureReceipt,
  requireKbMirrorPath,
} from '../e2e-real/knowledge-dm-fixture';
const id = 'aaaaaaaa-1111-4111-8111-111111111111';
const path = '/api/v1/dm/documents';
const draft = {
  metadata: { title: 'Public synthetic fixture', description: '' },
  classification: 'development-restricted',
  classificationPolicyRevision: 1,
};
const deny = {
  expectedRevision: 0,
  mode: 'RESTRICT',
  changes: [
    {
      operation: 'ADD_DENY',
      principal: { issuer: 'https://issuer.example', subject: 'staff' },
      action: 'document.share-to-collection',
    },
  ],
};
const headers = {
  'content-type': 'application/json;charset=UTF-8',
  'cache-control': 'no-store',
};
const bytes = (value: unknown) =>
  new TextEncoder().encode(JSON.stringify(value));
const receipt = { documentId: id, revision: 0 };

it('keeps KB scope strict and excludes DM, absolute and near-prefix paths', () => {
  for (const value of [
    '/api/v1/kb',
    '/api/v1/kb/collections?limit=1',
    '/api/v1/kb/collections?cursor=%2F..%2Fdm%3Fnext%3Dvalue',
    `/api/v1/kb/collections/${id}`,
  ])
    expect(() => requireKbMirrorPath(value)).not.toThrow();
  for (const value of [
    path,
    '/api/v1/kb-other',
    '/api/v1/kb/../dm/documents',
    '/api/v1/kb/%2e%2e/dm/documents',
    '/api/v1/kb/.%2E/dm/documents',
    '/api/v1/kb/collections/../collections',
    '/api/v1/kb/%2F..%2Fdm/documents',
    '/api/v1/kb/collections#fragment',
    '/api/v1/kb/collections#',
    '//elsewhere.example/api/v1/kb',
    '/api/v1/kb/\\..\\dm/documents',
    '/api/v1/kb/collections\n',
    'https://localhost/api/v1/kb',
    '/api/v1/KB',
    '',
  ])
    expect(() => requireKbMirrorPath(value)).toThrow();
});
it('allows only exact bounded synthetic draft and sharing-denial fixture commands', () => {
  expect(JSON.parse(dmFixtureBody('POST', path, draft))).toEqual(draft);
  expect(JSON.parse(dmFixtureBody('PATCH', `${path}/${id}/acl`, deny))).toEqual(
    deny,
  );
  for (const [method, route] of [
    ['GET', path],
    ['POST', `${path}/`],
    ['POST', `${path}?tenant=x`],
    ['PATCH', `${path}/invalid/acl`],
    ['PATCH', `${path}/${id}/versions`],
    ['PATCH', `${path}/${id.toUpperCase()}/acl`],
  ])
    expect(() =>
      dmFixtureBody(method!, route!, method === 'PATCH' ? deny : draft),
    ).toThrow();
  for (const value of [
    { ...draft, tenant: 'forged' },
    { ...draft, classification: 'public' },
    { ...draft, classificationPolicyRevision: 2 },
    { ...draft, metadata: { ...draft.metadata, title: '' } },
  ])
    expect(() => dmFixtureBody('POST', path, value)).toThrow();
  for (const value of [
    { ...deny, expectedRevision: -1 },
    { ...deny, expectedRevision: 0.5 },
    { ...deny, expectedRevision: Number.MAX_SAFE_INTEGER + 1 },
    { ...deny, mode: 'DELEGATE' },
    { ...deny, changes: [...deny.changes, ...deny.changes] },
    { ...deny, changes: [{ ...deny.changes[0], action: 'document.read' }] },
  ])
    expect(() => dmFixtureBody('PATCH', `${path}/${id}/acl`, value)).toThrow();
});
it('enforces the actual16KiB encoded command limit including Unicode bytes', () => {
  expect(() =>
    dmFixtureBody('POST', path, {
      ...draft,
      metadata: { title: '😀'.repeat(128), description: '😀'.repeat(2048) },
    }),
  ).not.toThrow();
  expect(() =>
    dmFixtureBody('POST', path, {
      ...draft,
      metadata: { title: '😀'.repeat(128), description: '漢'.repeat(4096) },
    }),
  ).not.toThrow();
  expect(() =>
    dmFixtureBody('POST', path, {
      ...draft,
      metadata: {
        title: '😀'.repeat(128),
        description: '😀'.repeat(4096),
      },
    }),
  ).toThrow();
});
it('accepts legacy repeated no-store semantics and exact minimal receipts', () => {
  expect(dmFixtureReceipt(201, 201, headers, bytes(receipt))).toEqual(receipt);
  expect(
    dmFixtureReceipt(
      200,
      200,
      { ...headers, 'cache-control': 'no-store, no-store, no-store' },
      bytes({ ...receipt, revision: 1 }),
    ),
  ).toEqual({ ...receipt, revision: 1 });
  // Legacy DM clients support decoded representations; KB no-encoding checks
  // stay on the KB helper and are not accidentally imposed on this fixture.
  expect(
    dmFixtureReceipt(
      201,
      201,
      { ...headers, 'content-encoding': 'gzip' },
      bytes(receipt),
    ),
  ).toEqual(receipt);
});
it('rejects wrong status, media type, caching, over1MiB and untrusted receipt shape', () => {
  for (const invalid of [
    {},
    { ...headers, 'content-type': 'text/html' },
    { ...headers, 'cache-control': '' },
    { ...headers, 'cache-control': 'no-store, public' },
    { ...headers, 'cache-control': 'max-age=60' },
  ])
    expect(() => dmFixtureReceipt(201, 201, invalid, bytes(receipt))).toThrow();
  expect(() => dmFixtureReceipt(200, 201, headers, bytes(receipt))).toThrow();
  expect(() =>
    dmFixtureReceipt(201, 201, headers, new Uint8Array(1048577)),
  ).toThrow();
  for (const value of [
    { ...receipt, tenant: 'forged' },
    { ...receipt, documentId: 'invalid' },
    { ...receipt, revision: -1 },
    { ...receipt, revision: Number.MAX_SAFE_INTEGER + 1 },
  ])
    expect(() => dmFixtureReceipt(201, 201, headers, bytes(value))).toThrow();
  expect(() =>
    dmFixtureReceipt(201, 201, headers, new TextEncoder().encode('{')),
  ).toThrow();
  expect(() =>
    dmFixtureReceipt(201, 201, headers, new Uint8Array([255])),
  ).toThrow();
});
