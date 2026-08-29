export function resolveRifleHitscan({
  THREE,
  camera,
  raycaster,
  wallMeshes = [],
  enemies = [],
  spread = 0,
  random = Math.random,
  maxRange = 45,
}) {
  const x = (random() * 2 - 1) * spread;
  const y = (random() * 2 - 1) * spread;
  raycaster.far = maxRange;
  raycaster.setFromCamera({ x, y }, camera);

  const direction = raycaster.ray.direction.clone().normalize();
  const rangeEnd = raycaster.ray.origin.clone().addScaledVector(direction, maxRange);
  const wallHit = raycaster.intersectObjects(wallMeshes, false)[0] ?? null;
  let nearestEnemyHit = null;
  let nearestEnemy = null;

  for (const enemy of enemies) {
    if (!enemy || enemy.health <= 0 || !enemy.root) continue;
    const hit = raycaster.intersectObject(enemy.root, true)[0] ?? null;
    if (!hit || (wallHit && hit.distance >= wallHit.distance)) continue;
    if (!nearestEnemyHit || hit.distance < nearestEnemyHit.distance) {
      nearestEnemyHit = hit;
      nearestEnemy = enemy;
    }
  }

  if (nearestEnemyHit) {
    return {
      target: nearestEnemy,
      zone: nearestEnemyHit.point.y - nearestEnemy.root.position.y >= 1.7 ? 'head' : 'body',
      point: nearestEnemyHit.point.clone(),
      direction,
      distance: nearestEnemyHit.distance,
    };
  }

  if (wallHit) {
    return {
      target: null,
      zone: null,
      point: wallHit.point.clone(),
      direction,
      distance: wallHit.distance,
    };
  }

  return {
    target: null,
    zone: null,
    point: rangeEnd,
    direction,
    distance: maxRange,
  };
}
