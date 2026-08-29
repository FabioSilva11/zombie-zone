export function selectAssistedTarget({ candidates, maxRange, maxAngle, hasLineOfSight, retainedId = null }) {
  return candidates
    .filter((candidate) => candidate.distance <= maxRange && candidate.angle <= maxAngle && hasLineOfSight(candidate.id))
    .map((candidate) => ({
      ...candidate,
      score: candidate.angle + candidate.distance * 0.001 - (candidate.id === retainedId ? 0.01 : 0),
    }))
    .sort((left, right) => left.score - right.score)[0] ?? null;
}
