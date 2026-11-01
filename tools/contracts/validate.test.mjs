import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { root, validateContract } from './lib.mjs';

for (const kind of ['openapi', 'asyncapi']) {
  test(`${kind}: schema-valid fixture is accepted`, async () => {
    await validateContract(resolve(root, `tools/contracts/fixtures/${kind}-valid.yaml`), kind);
  });
  test(`${kind}: malformed contract is rejected`, async () => {
    await assert.rejects(validateContract(resolve(root, `tools/contracts/fixtures/${kind}-invalid.yaml`), kind));
  });
}
test('unknown validator kinds cannot silently skip validation', async () => {
  await assert.rejects(validateContract(resolve(root, 'tools/contracts/fixtures/openapi-valid.yaml'), 'unsupported'), /Unsupported contract kind/);
});

import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { compareGeneratedTrees } from './sdk-tree.mjs';

test('SDK comparison detects changed, removed and added generated files', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'dkp-sdk-tree-test-'));
  const actual = join(temporary, 'actual');
  const expected = join(temporary, 'expected');
  try {
    await mkdir(actual);
    await mkdir(expected);
    await writeFile(join(actual, 'Client.java'), 'expected');
    await writeFile(join(expected, 'Client.java'), 'expected');
    await compareGeneratedTrees(actual, expected);
    await writeFile(join(actual, 'Client.java'), 'modified');
    await assert.rejects(compareGeneratedTrees(actual, expected), /content drift/);
    await rm(join(actual, 'Client.java'));
    await assert.rejects(compareGeneratedTrees(actual, expected), /file set drift/);
    await writeFile(join(actual, 'Client.java'), 'expected');
    await writeFile(join(actual, 'Unexpected.java'), 'extra');
    await assert.rejects(compareGeneratedTrees(actual, expected), /file set drift/);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

import { ensureGenerator, verifyGeneratorBytes, generatorVersion } from './generator.mjs';

test('generator integrity rejects substituted bytes before execution', () => {
  assert.throws(() => verifyGeneratorBytes(Buffer.from('substituted generator')), /checksum mismatch.*No Java code was executed/);
});

test('generator rejects corrupt cached artifacts rather than trusting the cache', async () => {
  const cacheDirectory = await mkdtemp(join(tmpdir(), 'dkp-generator-cache-test-'));
  try {
    await writeFile(join(cacheDirectory, `openapi-generator-cli-${generatorVersion}.jar`), 'corrupt cache');
    await assert.rejects(ensureGenerator({ cacheDirectory, download: async () => { throw new Error('must not download over corrupt cache'); } }), /checksum mismatch/);
  } finally {
    await rm(cacheDirectory, { recursive: true, force: true });
  }
});

test('generator rejects substituted downloads without installing an artifact', async () => {
  const cacheDirectory = await mkdtemp(join(tmpdir(), 'dkp-generator-download-test-'));
  try {
    await assert.rejects(ensureGenerator({ cacheDirectory, download: async () => Buffer.from('substituted download') }), /checksum mismatch/);
    const { readdir } = await import('node:fs/promises');
    assert.deepEqual(await readdir(cacheDirectory), []);
  } finally {
    await rm(cacheDirectory, { recursive: true, force: true });
  }
});

import { parse } from 'yaml';
test('published identity contract preserves independent trust and mutation guards', async () => {
  const contract = parse(await readFile(resolve(root, 'contracts/openapi/v1/identity.yaml'), 'utf8'));
  const api = contract.paths['/api/v1/security/context'].get;
  assert.deepEqual(api.security, [{ BearerAuth: [] }]);
  assert.ok(api.parameters.some(({ name, required }) => name === 'X-DKP-Tenant' && required));
  for (const path of ['/api/v1/session/tenant', '/api/v1/session/logout']) {
    assert.deepEqual(Object.keys(contract.paths[path]), ['post']);
    const operation = contract.paths[path].post;
    assert.deepEqual(operation.security, [{ SessionCookie: [] }]);
    for (const name of ['Origin', 'X-CSRF-TOKEN']) {
      assert.ok(operation.parameters.some((parameter) => parameter.name === name && parameter.required));
    }
  }
  assert.deepEqual(contract.paths['/api/v1/security/context/{service}'].get.parameters[0].schema.enum, ['dm', 'kb', 'search']);
  const session = contract.components.schemas.BrowserSession;
  assert.equal(session.additionalProperties, false);
  assert.deepEqual(session.required.toSorted(), ['issuer', 'subject', 'displayName', 'tenants', 'selectedTenant', 'csrfToken'].toSorted());
  for (const name of ['accessToken', 'idToken', 'refreshToken', 'sessionId']) {
    assert.equal(session.properties[name], undefined);
  }
  assert.equal(contract.components.securitySchemes.SessionCookie.name, '__Host-DKP-SESSION');
});

test('published DM contract preserves approved BP02 semantics and exact additive BP03 freeze', async () => {
  const contract = parse(await readFile(resolve(root, 'contracts/openapi/v1/document-management.yaml'), 'utf8'));
  const metadata = JSON.parse(await readFile(resolve(root, 'openspec/changes/archive/2026-10-31-dkp-003-document-management/contracts/document-management.openapi.json'), 'utf8'));
  const uploads = JSON.parse(await readFile(resolve(root, 'openspec/changes/dkp-004-rustfs-upload/contracts/rustfs-upload.openapi.json'), 'utf8'));
  const expectedPaths = structuredClone(metadata.paths);
  for (const [path, operations] of Object.entries(uploads.paths)) {
    expectedPaths[path] = { ...expectedPaths[path], ...operations };
  }
  assert.deepEqual(contract.paths, expectedPaths, 'Only exact approved BP03 additions/replacements are published');
  assert.deepEqual(contract.components, {
    ...metadata.components,
    schemas: { ...metadata.components.schemas, ...uploads.components.schemas },
  });
  assert.deepEqual(contract.servers, metadata.servers);
  assert.equal(contract.info.version, '0.2.0');
  const semantic = (value) => Array.isArray(value) ? value.map(semantic)
    : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value)
      .filter(([key]) => key !== 'description').map(([key, child]) => [key, semantic(child)])) : value;
  for (const [name, schema] of Object.entries(metadata.components.schemas)) {
    if (name === 'Action') {
      assert.deepEqual(contract.components.schemas.Action.enum, [...schema.enum, 'document.content.review']);
    } else {
      assert.deepEqual(semantic(contract.components.schemas[name]), semantic(schema), `BP02 schema ${name} is preserved`);
    }
  }
  for (const [path, operations] of Object.entries(metadata.paths)) {
    for (const [method, operation] of Object.entries(operations)) {
      const actual = contract.paths[path][method];
      assert.equal(actual.operationId, operation.operationId, `${method} ${path} SDK method`);
      assert.deepEqual(actual.requestBody, operation.requestBody, `${method} ${path} request DTO`);
      for (const [status, response] of Object.entries(operation.responses)) {
        if (status.startsWith('2')) assert.deepEqual(semantic(actual.responses[status]), semantic(response));
      }
      if (!path.endsWith('/versions')) {
        assert.deepEqual(semantic(actual), semantic(operation), `${method} ${path} complete BP02 wire`);
      } else {
        assert.deepEqual(actual, uploads.paths[path][method], 'Append failures are exactly the approved typed UploadError/429 delta');
      }
    }
  }
  const schemas = contract.components.schemas;
  assert.equal(schemas.CommandReceipt.additionalProperties, false);
  assert.deepEqual(Object.keys(schemas.CommandReceipt.properties).toSorted(), ['documentId', 'revision']);
  assert.deepEqual(schemas.CommandReceipt.required.toSorted(), ['documentId', 'revision']);
  assert.equal(schemas.Document.properties.activeVersionId.type, 'null');
  assert.equal(schemas.Retention.properties.retainUntil.type, 'null');
  for (const [path, operations] of Object.entries(contract.paths)) {
    for (const [method, operation] of Object.entries(operations)) {
      assert.equal(operation.responses['202'], undefined, 'BP03 never fabricates processing handoff');
      const browser = path.startsWith('/api/v1/dm/');
      assert.deepEqual(operation.security, browser ? [{ SessionCookie: [] }] : [{ BearerAuth: [] }]);
      if (['post', 'patch', 'put'].includes(method)) {
        assert.ok(operation.parameters.some(({ name, required }) => name === 'Idempotency-Key' && required));
        for (const name of browser ? ['Origin', 'X-CSRF-TOKEN'] : ['X-DKP-Tenant']) {
          assert.ok(operation.parameters.some((parameter) => parameter.name === name && parameter.required));
        }
      }
      if (!browser) {
        const mirrored = contract.paths[path.replace('/api/v1/documents', '/api/v1/dm/documents')][method];
        assert.deepEqual(mirrored.requestBody, operation.requestBody, `${path} browser request parity`);
        assert.deepEqual(mirrored.responses, operation.responses, `${path} browser response parity`);
      }
    }
  }
});
