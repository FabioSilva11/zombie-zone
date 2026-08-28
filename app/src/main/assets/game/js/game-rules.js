export const ARENA_BOUNDS = Object.freeze({
  minX: -11.5,
  maxX: 11.5,
  minZ: -17.5,
  maxZ: 17.5,
});

export function clampToArena(position, bounds = ARENA_BOUNDS) {
  return {
    x: Math.min(bounds.maxX, Math.max(bounds.minX, position.x)),
    z: Math.min(bounds.maxZ, Math.max(bounds.minZ, position.z)),
  };
}

export function canMeleeHit(attacker, target, range = 2.15, minDot = 0.25) {
  const dx = target.x - attacker.x;
  const dz = target.z - attacker.z;
  const distance = Math.hypot(dx, dz);
  if (distance === 0 || distance > range) return false;

  const forwardX = Math.sin(attacker.yaw);
  const forwardZ = -Math.cos(attacker.yaw);
  const dot = (forwardX * dx + forwardZ * dz) / distance;
  return dot >= minDot;
}

export function nextEnemyState(enemy, player) {
  if (enemy.health <= 0) return 'dead';
  const distance = Math.hypot(player.x - enemy.x, player.z - enemy.z);
  if (distance <= 1.65) return 'attack';
  if (distance <= 9) return 'chase';
  return 'idle';
}
