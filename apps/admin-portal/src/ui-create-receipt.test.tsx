import { describe, expect, it } from 'vitest';
import type { APIRequestContext, Response } from '@playwright/test';
import {
  confirmSingleCreate,
  createReceipt,
  createReplay,
  replayUiCreate,
  type CapturedCreate,
} from '../e2e-real/ui-create-receipt';

const id = '00000000-0000-4000-8000-000000000001';
const other = '00000000-0000-4000-8000-000000000002';
const intended = {
  origin: 'https://localhost:19443',
  title: 'Synthetic unique title',
  description: 'Synthetic description',
};
const command = {
  metadata: { title: intended.title, description: intended.description },
  classification: 'development-restricted',
  classificationPolicyRevision: 1,
};
const encode = (value: unknown) =>
  new TextEncoder().encode(JSON.stringify(value));
function capture(): CapturedCreate {
  return {
    status: 201,
    method: 'POST',
    url: intended.origin + '/api/v1/dm/documents',
    body: JSON.stringify(command),
    headers: [
      { name: 'Origin', value: intended.origin },
      { name: 'X-CSRF-TOKEN', value: '.synthetic-csrf' },
      { name: 'Idempotency-Key', value: 'synthetic-original-key' },
      { name: 'Content-Type', value: 'application/json' },
      { name: 'Cookie', value: 'never-forwarded' },
      { name: 'Authorization', value: 'never-forwarded' },
    ],
  };
}
const receipt = { documentId: id, revision: 0 };
const item = (documentId = id) => ({
  documentId,
  metadata: { title: intended.title },
});

describe('original UI create observation without CDP response body', () => {
  it('retains exact original body/key/guards and never forwards captured credentials', () => {
    const input = capture();
    input.body = '  ' + input.body + '\n';
    const plan = createReplay(input, intended);
    expect(plan.body).toBe(input.body);
    expect(plan.url).toBe(input.url);
    expect(plan.headers).toEqual({
      Origin: intended.origin,
      'X-CSRF-TOKEN': '.synthetic-csrf',
      'Idempotency-Key': 'synthetic-original-key',
      'Content-Type': 'application/json',
    });
  });
  it.each([
    { status: 200 },
    { status: 503 },
    { method: 'GET' },
    { url: 'https://foreign.invalid/api/v1/dm/documents' },
    { url: intended.origin + '/api/v1/dm/documents?tenant=foreign' },
    { url: intended.origin + '/api/v1/dm/documents#x' },
    { url: 'https://user:secret@localhost:19443/api/v1/dm/documents' },
    { url: intended.origin + '/api/v1/dm/documents/other' },
    { body: null },
    { body: ' '.repeat(16385) },
    { body: 'é'.repeat(8193) },
    { body: '{}' },
    { body: '{' },
    { body: JSON.stringify({ ...command, actor: 'forged' }) },
    { body: JSON.stringify({ ...command, classification: 'public' }) },
    {
      body: JSON.stringify({
        ...command,
        metadata: { ...command.metadata, title: 'another' },
      }),
    },
    {
      body: JSON.stringify({
        ...command,
        metadata: { ...command.metadata, description: 'another' },
      }),
    },
  ])('rejects unsafe original request %j', (change) => {
    expect(() => createReplay({ ...capture(), ...change }, intended)).toThrow();
  });
  it('requires a pure HTTPS intended origin', () => {
    for (const origin of ['http://localhost:19443', intended.origin + '/path'])
      expect(() => createReplay(capture(), { ...intended, origin })).toThrow();
  });
  it('rejects missing/duplicate/malformed guards before any replay', () => {
    for (const name of [
      'origin',
      'x-csrf-token',
      'idempotency-key',
      'content-type',
    ]) {
      const missing = capture();
      missing.headers = missing.headers.filter(
        (h) => h.name.toLowerCase() !== name,
      );
      expect(() => createReplay(missing, intended)).toThrow();
      const duplicate = capture();
      duplicate.headers.push(
        duplicate.headers.find((h) => h.name.toLowerCase() === name)!,
      );
      expect(() => createReplay(duplicate, intended)).toThrow();
    }
    for (const [name, value] of [
      ['Origin', 'https://foreign.invalid'],
      ['X-CSRF-TOKEN', ''],
      ['X-CSRF-TOKEN', 'a'.repeat(513)],
      ['Idempotency-Key', 'a'.repeat(129)],
      ['Idempotency-Key', 'newline\n'],
      ['Content-Type', 'text/plain'],
    ]) {
      const input = capture();
      input.headers.find((h) => h.name === name)!.value = value!;
      expect(() => createReplay(input, intended)).toThrow();
    }
  });
  it('parses only a bounded closed canonical zero-revision receipt', () => {
    expect(createReceipt(encode(receipt))).toEqual(receipt);
    for (const value of [
      { ...receipt, revision: 1 },
      { ...receipt, revision: null },
      { ...receipt, extra: true },
      { ...receipt, documentId: 'foreign' },
      { ...receipt, documentId: 'ABCDEF00-0000-4000-8000-000000000001' },
      null,
      [],
    ])
      expect(() => createReceipt(encode(value))).toThrow();
    for (const body of [
      new Uint8Array(),
      new Uint8Array([255]),
      new TextEncoder().encode('{'),
      new Uint8Array(1025),
    ])
      expect(() => createReceipt(body)).toThrow();
  });
  it('always executes the same authorized replay and never reads the original response body', async () => {
    const c = capture(),
      calls: unknown[] = [];
    const original = {
      status: () => c.status,
      request: () => ({
        method: () => c.method,
        url: () => c.url,
        postData: () => c.body,
        headersArray: async () => c.headers,
      }),
    } as unknown as Response;
    let status = 201,
      type = 'application/json';
    const api = {
      post: async (url: string, options: unknown) => {
        calls.push({ url, options });
        return {
          status: () => status,
          headers: () => ({ 'content-type': type }),
          body: async () => encode(receipt),
        };
      },
    } as unknown as APIRequestContext;
    expect(await replayUiCreate(api, original, intended)).toEqual(receipt);
    expect(calls).toEqual([
      {
        url: c.url,
        options: { data: c.body, headers: createReplay(c, intended).headers },
      },
    ]);
    status = 403;
    await expect(replayUiCreate(api, original, intended)).rejects.toThrow(
      'Current-authorized',
    );
    status = 201;
    type = 'text/html';
    await expect(replayUiCreate(api, original, intended)).rejects.toThrow(
      'Current-authorized',
    );
  });
  it('checks every real discovery page and fails incomplete or duplicate creation evidence', async () => {
    const calls: string[] = [];
    let pages: unknown[] = [
        { items: [], nextCursor: 'opaque+/=' },
        { items: [item()], nextCursor: null },
      ],
      status = 200;
    const api = {
      get: async (url: string) => {
        calls.push(url);
        return { status: () => status, json: async () => pages.shift() };
      },
    } as unknown as APIRequestContext;
    await confirmSingleCreate(api, intended, id);
    expect(calls[1]).toBe(
      intended.origin + '/api/v1/dm/documents?limit=50&cursor=opaque%2B%2F%3D',
    );
    for (const page of [
      { items: [], nextCursor: null },
      { items: [item(other)], nextCursor: null },
      { items: [item(), item()], nextCursor: null },
      { items: [], nextCursor: '' },
      { items: new Array(51), nextCursor: null },
      { items: null, nextCursor: null },
    ]) {
      pages = [page];
      await expect(confirmSingleCreate(api, intended, id)).rejects.toThrow();
    }
    pages = [
      {
        items: [
          { documentId: other, metadata: { title: 'unrelated' } },
          item(),
        ],
        nextCursor: null,
      },
    ];
    await confirmSingleCreate(api, intended, id);
    pages = [
      { items: [], nextCursor: 'same' },
      { items: [], nextCursor: 'same' },
    ];
    await expect(confirmSingleCreate(api, intended, id)).rejects.toThrow(
      'repeated',
    );
    pages = Array.from({ length: 20 }, (_, i) => ({
      items: [],
      nextCursor: String(i),
    }));
    await expect(confirmSingleCreate(api, intended, id)).rejects.toThrow(
      'budget',
    );
    status = 503;
    await expect(confirmSingleCreate(api, intended, id)).rejects.toThrow(
      'authorized',
    );
  });
});
