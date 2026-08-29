import { pieceById, placementBounds, rotatePiece } from './dungeon-catalog.js';

export const GRID_WIDTH = 50;
export const GRID_HEIGHT = 50;
export const CELL_SIZE_METERS = 4;
export const LEVEL_HEIGHT_METERS = 4.05;
export const PHASE_SIZE_METERS = GRID_WIDTH * CELL_SIZE_METERS;

function worldBounds(placement) {
  const bounds = placementBounds(placement);
  return {
    minX: bounds.minX * CELL_SIZE_METERS,
    minZ: bounds.minZ * CELL_SIZE_METERS,
    maxX: bounds.maxX * CELL_SIZE_METERS,
    maxZ: bounds.maxZ * CELL_SIZE_METERS,
  };
}

export function allRegions(layout) {
  return layout.placements.filter((placement) => !placement.edge).map((placement) => ({
    ...worldBounds(placement),
    id: placement.id,
    model: placement.pieceId,
    level: placement.level,
    rotation: placement.rotation,
    placement,
  }));
}

function containsWorldPoint(placement, position, inset = 0) {
  const bounds = worldBounds(placement);
  return position.x >= bounds.minX + inset && position.x <= bounds.maxX - inset
    && position.z >= bounds.minZ + inset && position.z <= bounds.maxZ - inset;
}

function stairProgress(placement, position) {
  const bounds = worldBounds(placement);
  const rotated = rotatePiece(pieceById(placement.pieceId), placement.rotation);
  const low = rotated.sockets.find((socket) => socket.levelDelta === 0);
  if (!low) return 0;
  if (low.side === 'N') return (position.z - bounds.minZ) / Math.max(bounds.maxZ - bounds.minZ, 0.01);
  if (low.side === 'S') return (bounds.maxZ - position.z) / Math.max(bounds.maxZ - bounds.minZ, 0.01);
  if (low.side === 'W') return (position.x - bounds.minX) / Math.max(bounds.maxX - bounds.minX, 0.01);
  return (bounds.maxX - position.x) / Math.max(bounds.maxX - bounds.minX, 0.01);
}

function candidateFloorHeight(placement, position, layout) {
  const base = placement.level * layout.levelHeight;
  if (placement.pieceId !== 'stairs' && placement.pieceId !== 'stairsWide') return base;
  const progress = Math.min(1, Math.max(0, stairProgress(placement, position)));
  return base + progress * layout.levelHeight;
}

export function floorHeightAt(position, layout) {
  const candidates = layout.placements
    .filter((placement) => !placement.edge && containsWorldPoint(placement, position))
    .map((placement) => candidateFloorHeight(placement, position, layout));
  if (!candidates.length) return null;
  const requestedY = Number.isFinite(position.y) ? position.y : 0;
  return candidates.reduce((best, value) => (
    Math.abs(value - requestedY) < Math.abs(best - requestedY) ? value : best
  ));
}

export function pointInLayout(position, layout) {
  return floorHeightAt(position, layout) !== null;
}

export function canOccupy(position, radius = 1.2, layout) {
  const centerHeight = floorHeightAt(position, layout);
  if (centerHeight === null) return false;
  const samples = [
    position,
    { ...position, x: position.x - radius },
    { ...position, x: position.x + radius },
    { ...position, z: position.z - radius },
    { ...position, z: position.z + radius },
  ];
  return samples.every((sample) => {
    const height = floorHeightAt({ ...sample, y: centerHeight }, layout);
    return height !== null && Math.abs(height - centerHeight) <= Math.max(0.75, radius);
  });
}

export function layoutIsConnected(layout) {
  const floor = layout.placements.filter((placement) => !placement.edge);
  const adjacency = new Map(floor.map((placement) => [placement.id, []]));
  for (const connection of layout.connections) {
    adjacency.get(connection.a)?.push(connection.b);
    adjacency.get(connection.b)?.push(connection.a);
  }
  const visited = new Set();
  const queue = [layout.start?.placementId];
  while (queue.length) {
    const id = queue.shift();
    if (!id || visited.has(id)) continue;
    visited.add(id);
    queue.push(...(adjacency.get(id) ?? []));
  }
  return visited.size === floor.length;
}

export function levelAt(position, layout) {
  const height = floorHeightAt(position, layout);
  return height === null ? null : Math.round(height / layout.levelHeight);
}
