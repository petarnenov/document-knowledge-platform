import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { assess } from './assess.mjs';
const load = (relative) => JSON.parse(readFileSync(new URL(relative, import.meta.url)));
const original = load('../research-379/advisory-matches.json');
const candidate = load('./candidate-graph.json');
const triage = load('./advisory-triage.json');
const copy = (x) => structuredClone(x);
test('all 24 actual advisories bind exact resolved fixed candidates; zero feed matches is not approval', () => {
  assert.deepEqual(assess(original, candidate, triage), {
    triaged: 24, affectedModules: 7, candidateArtifacts: 54, candidateFeedMatches: 0,
    selection: 'BLOCKED_PENDING_INDEPENDENT_REVIEW_AND_REMAINING_GATES',
  });
  assert.equal(candidate.graph.filter((x) => x.scope === 'production-candidate').length, 43);
  assert.equal(triage.rows.filter((x) => x.scope === 'testing-only').length, 1);
});
test('missing, duplicate and substituted advisory identities cannot be treated as complete', () => {
  for (const rows of [triage.rows.slice(1), [...triage.rows, triage.rows[0]], triage.rows.map((x, i) => i ? x : { ...x, id: 'substituted' })])
    assert.throws(() => assess(original, candidate, { ...triage, rows }));
});
test('duplicate module, bad coordinate and artifact/license uncertainty fail closed', () => {
  const duplicate = copy(candidate); duplicate.graph.push(duplicate.graph[0]);
  assert.throws(() => assess(original, duplicate, triage));
  for (const patch of [{ coordinate: 'invalid' }, { jarSha256: 'unverified' }, { effectiveDeclaredLicenses: [] }]) {
    const changed = copy(candidate); Object.assign(changed.graph[0], patch);
    assert.throws(() => assess(original, changed, triage));
  }
});
test('OSV coverage must match the exact complete resolved graph', () => {
  for (const matches of [candidate.osvQuery.matches.slice(1), candidate.osvQuery.matches.map((x, i) => i ? x : { ...x, coordinate: 'substituted' })])
    assert.throws(() => assess(original, { ...candidate, osvQuery: { ...candidate.osvQuery, matches } }, triage));
});
test('new feed matches remain visible and prevent any clean-scan inference', () => {
  const changed = copy(candidate); changed.osvQuery.matches[0].vulnerabilities.push({ id: 'new-advisory' });
  assert.equal(assess(original, changed, triage).candidateFeedMatches, 1);
  assert.match(assess(original, changed, triage).selection, /^BLOCKED/);
});
test('remediation cannot silently target an absent/different resolved version or unverified source', () => {
  for (const patch of [{ candidateCoordinate: 'not:resolved:1' }, { primarySource: { httpVerified: false } }, { affected: [] }]) {
    const changed = copy(triage); Object.assign(changed.rows[0], patch);
    assert.throws(() => assess(original, candidate, changed));
  }
});
test('baseline affected modules replaced; direct SDK/testing/broker versions and test scope preserved', () => {
  const coordinates = candidate.graph.map((x) => x.coordinate);
  for (const direct of candidate.directCoordinates) assert.ok(coordinates.includes(direct));
  for (const row of triage.rows) assert.ok(!coordinates.includes(row.originalCoordinate));
  assert.equal(candidate.graph.find((x) => x.coordinate === 'io.temporal:temporal-testing:1.40.0').scope, 'test-only-candidate');
  assert.ok(coordinates.includes('com.fasterxml.jackson.core:jackson-annotations:2.22'));
  assert.ok(coordinates.includes('io.micrometer:micrometer-observation:1.17.2'));
});
