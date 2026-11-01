import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { validateProfile } from './profile-check.mjs';
const profile = JSON.parse(readFileSync(new URL('./profile.json', import.meta.url), 'utf8'));

test('closed candidate has explicit queue, burst and separate emitted-piece accounting', () => {
  assert.deepEqual(validateProfile(profile), { receiveBurstBytes: 8192, conditionalQueuedBytes: 73727, bridgeBytes: 16384, configuredBodyAllowanceBytes: 147456 });
  assert.ok(73727 <= profile.frameworkQueueBytes);
  assert.ok(65535 + 8192 > 65536, 'a64KiB empirical selection cannot cover one whole permitted overrun');
  assert.ok(65535 > 16384, '16KiB cannot contain initial credit when a consumer has not drained it');
});
test('invalid shapes and undeclared fields cannot alter the frozen profile', () => {
  for (const bad of [null, [], 'profile', 1, { ...profile, arbitrary: true }, Object.fromEntries(Object.entries(profile).filter(([key]) => key !== 'metadataBytes'))]) assert.throws(() => validateProfile(bad), /INVALID_PROFILE/);
});
test('numeric/protocol/approval/metadata changes require a new reviewed freeze', () => {
  for (const [key, bad] of [['frameworkQueueBytes', 16384], ['frameworkQueueBytes', 100000000], ['receiveBytes', 65536], ['receiveMessages', 16], ['bridgePieces', 1], ['metadataBytes', 131072], ['binaryMaxBytes', 100000001], ['transferMillis', 310000], ['idleMillis', 31000], ['directTls', true], ['http2', true], ['http3', true], ['decompression', true], ['selectionState', 'APPROVED'], ['micronautHttp', 'latest']]) assert.throws(() => validateProfile({ ...profile, [key]: bad }), /INVALID_PROFILE/);
});
