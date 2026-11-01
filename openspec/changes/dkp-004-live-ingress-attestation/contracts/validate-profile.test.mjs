import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { validateProfile } from './validate-profile.mjs';

const profile = JSON.parse(readFileSync(new URL('./readback-profile.json', import.meta.url)));
test('exact proposed descriptor is accepted without mutation', () => {
  const before = JSON.stringify(profile);
  assert.equal(validateProfile(profile), true);
  assert.equal(JSON.stringify(profile), before);
});
const mutations = [
  ['version', p => p.artifact.coordinate = 'io.micronaut:micronaut-http-server-netty:5.2.21'],
  ['binary', p => p.artifact.binarySha256 = '0'.repeat(64)],
  ['source', p => p.artifact.handlerSourceSha256 = '0'.repeat(64)],
  ['class', p => p.declaringClass = 'java.lang.Runtime'],
  ['final', p => p.classFinal = false],
  ['layout', p => p.runtimeLayout = 'native-image'],
  ['name', p => p.fields[0].name = '*'],
  ['type', p => p.fields[1].type = 'java.lang.Boolean'],
  ['modifiers', p => p.fields[0].modifiers = ['public']],
  ['body', p => p.fields[0].expectedMaxBodySize = 100000001],
  ['buffer', p => p.fields[0].expectedMaxBufferSize = 131073],
  ['decompression', p => p.fields[1].expectedValue = true],
  ['extra-field', p => p.fields.push({ name: 'inboundHandler' })],
  ['reorder', p => p.fields.reverse()],
  ['private-write', p => p.allowedReflectionOperations.push('set')],
  ['writes', p => p.privateWrites = true],
  ['fallback', p => p.fallback = true],
  ['opens', p => p.jvmOpensOverride = true],
  ['callback-gap', p => p.callbacks.pop()],
  ['event-loop', p => p.eventLoopOnly = false],
  ['adjacency', p => p.adjacentToActualHandler = false],
  ['latch', p => p.latchUnavailable = false],
  ['extra', p => p.unknown = false],
  ['missing', p => delete p.artifact],
];
for (const [name, mutate] of mutations) test(`rejects ${name}`, () => {
  const value = structuredClone(profile); mutate(value);
  assert.throws(() => validateProfile(value), /Exact proposed/);
});
for (const value of [null, [], true]) test(`rejects wrong root ${JSON.stringify(value)}`, () => assert.throws(() => validateProfile(value)));
