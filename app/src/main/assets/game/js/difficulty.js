import { placementBounds } from './dungeon-catalog.js';

const clampStage = (value) => Math.min(20, Math.max(1, Math.trunc(Number(value) || 1)));

export function difficultyForStage(stageNumber, upgrades = {}) {
  const stage = clampStage(stageNumber);
  const step = stage - 1;
  const vitality = Math.min(5, Math.max(0, Math.trunc(Number(upgrades.vitality) || 0)));
  return Object.freeze({
    stage,
    zombieCount: 10 + step,
    magazineCapacity: 30 * (2 ** Math.floor(step / 3)),
    playerHealth: 100 + step * 18 + vitality * 15,
    zombieHealth: 70 + step * 16,
    zombieDamage: 8 + Math.floor(step * 0.75),
    zombieSpeedMultiplier: Number((1 + Math.min(0.65, step * 0.028)).toFixed(3)),
  });
}

function placementCenter(placement, layout) {
  const bounds = placementBounds(placement);
  return {
    x: ((bounds.minX + bounds.maxX) / 2) * layout.cellSize,
    y: placement.level * layout.levelHeight,
    z: ((bounds.minZ + bounds.maxZ) / 2) * layout.cellSize,
  };
}

export function createZombieSpawnPlan(layout, requestedCount) {
  const target = Math.min(60, Math.max(1, Math.trunc(Number(requestedCount) || 10)));
  const candidates = [...(layout.zombieSpawns ?? [])];
  const additional = layout.placements
    .filter((placement) => !placement.edge)
    .map((placement) => placementCenter(placement, layout))
    .filter((point) => Math.hypot(point.x - layout.start.x, point.z - layout.start.z) >= 9)
    .sort((left, right) => {
      const leftDistance = Math.hypot(left.x - layout.start.x, left.z - layout.start.z) + Math.abs(left.y - layout.start.y) * 2;
      const rightDistance = Math.hypot(right.x - layout.start.x, right.z - layout.start.z) + Math.abs(right.y - layout.start.y) * 2;
      return rightDistance - leftDistance;
    });

  const unique = [];
  const used = new Set();
  for (const point of [...candidates, ...additional]) {
    const key = `${point.x.toFixed(2)}:${point.y.toFixed(2)}:${point.z.toFixed(2)}`;
    if (used.has(key)) continue;
    used.add(key);
    unique.push(point);
    if (unique.length >= target) break;
  }
  return Object.freeze(unique.map((point, index) => Object.freeze({
    x: point.x,
    y: point.y,
    z: point.z,
    kind: index % 2 ? 'undead' : 'green',
  })));
}
