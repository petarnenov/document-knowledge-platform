// Research evidence consistency only. Never declares a graph safe or approved.
export function assess(original, candidate, triage) {
  const oldIds = original.advisories.map((x) => x.id).sort();
  const rows = triage.rows;
  if (JSON.stringify(rows.map((x) => x.id).sort()) !== JSON.stringify(oldIds))
    throw new Error('Incomplete or substituted advisory triage');
  const modules = new Map();
  for (const artifact of candidate.graph) {
    const parts = artifact.coordinate.split(':');
    if (parts.length !== 3 || !parts.every(Boolean)) throw new Error('Invalid coordinate');
    const module = parts.slice(0, 2).join(':');
    if (modules.has(module)) throw new Error('Unconverged module');
    if (!/^[a-f0-9]{64}$/.test(artifact.jarSha256) || !artifact.effectiveDeclaredLicenses.length)
      throw new Error('Missing artifact identity or declared license');
    modules.set(module, artifact.coordinate);
  }
  const queried = candidate.osvQuery.matches;
  if (JSON.stringify(queried.map((x) => x.coordinate).sort()) !==
      JSON.stringify(candidate.graph.map((x) => x.coordinate).sort()))
    throw new Error('Incomplete or substituted OSV query');
  for (const row of rows) {
    const module = row.originalCoordinate.split(':').slice(0, 2).join(':');
    if (modules.get(module) !== row.candidateCoordinate || !row.primarySource.httpVerified || !row.affected.length)
      throw new Error('Unbound advisory remediation');
  }
  return {
    triaged: rows.length,
    affectedModules: new Set(rows.map((x) => x.originalCoordinate)).size,
    candidateArtifacts: candidate.graph.length,
    candidateFeedMatches: queried.flatMap((x) => x.vulnerabilities).length,
    selection: 'BLOCKED_PENDING_INDEPENDENT_REVIEW_AND_REMAINING_GATES',
  };
}
