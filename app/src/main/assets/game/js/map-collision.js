import { placementBounds, pieceById, rotatePiece } from './dungeon-catalog.js';
import { collisionTemplateByPieceId } from './dungeon-collision-catalog.js';

export function buildWallColliders(layout) {
  if (Array.isArray(layout.colliders)) {
    return Object.freeze(layout.colliders.map((collider) => Object.freeze({ ...collider })));
  }
  const colliders = [];
  for (const placement of layout.placements) {
    if (placement.edge) continue;
    const bounds = placementBounds(placement);
    const centerX = (bounds.minX + bounds.maxX) / 2 * layout.cellSize;
    const centerZ = (bounds.minZ + bounds.maxZ) / 2 * layout.cellSize;
    const minY = placement.level * layout.levelHeight;
    const maxY = minY + layout.levelHeight * (
      placement.pieceId === 'stairs' || placement.pieceId === 'stairsWide' ? 2 : 1
    );
    const scale = layout.cellSize / 4;
    collisionTemplateByPieceId(placement.pieceId).forEach((template, index) => {
      const rotate = (x, z) => {
        if (placement.rotation === 1) return { x: -z, z: x };
        if (placement.rotation === 2) return { x: -x, z: -z };
        if (placement.rotation === 3) return { x: z, z: -x };
        return { x, z };
      };
      const corners = [
        rotate(template.minX * scale, template.minZ * scale),
        rotate(template.maxX * scale, template.minZ * scale),
        rotate(template.minX * scale, template.maxZ * scale),
        rotate(template.maxX * scale, template.maxZ * scale),
      ];
      colliders.push({
        id: `generated:${placement.id}:mesh:${index}`,
        kind: 'wall',
        placementId: placement.id,
        minX: centerX + Math.min(...corners.map(({ x }) => x)),
        maxX: centerX + Math.max(...corners.map(({ x }) => x)),
        minY,
        maxY,
        minZ: centerZ + Math.min(...corners.map(({ z }) => z)),
        maxZ: centerZ + Math.max(...corners.map(({ z }) => z)),
        source: 'generated',
      });
    });
  }
  return Object.freeze(colliders.map((collider) => Object.freeze(collider)));
}

export function cameraColliderBoxes(colliders) {
  return colliders.map((collider) => ({
    center: {
      x: (collider.minX + collider.maxX) / 2,
      y: (collider.minY + collider.maxY) / 2,
      z: (collider.minZ + collider.maxZ) / 2,
    },
    size: {
      x: collider.maxX - collider.minX,
      y: collider.maxY - collider.minY,
      z: collider.maxZ - collider.minZ,
    },
  }));
}

export function buildFloorColliders(layout) {
  const thickness = 0.16;
  return layout.placements.filter((placement) => !placement.edge).map((placement) => {
    const bounds = placementBounds(placement);
    const minX = bounds.minX * layout.cellSize;
    const maxX = bounds.maxX * layout.cellSize;
    const minZ = bounds.minZ * layout.cellSize;
    const maxZ = bounds.maxZ * layout.cellSize;
    const baseY = placement.level * layout.levelHeight;
    const center = { x: (minX + maxX) / 2, y: baseY - thickness / 2, z: (minZ + maxZ) / 2 };
    const flat = {
      kind: 'floor', center,
      half: { x: (maxX - minX) / 2, y: thickness / 2, z: (maxZ - minZ) / 2 },
      rotation: null,
    };
    if (placement.pieceId !== 'stairs' && placement.pieceId !== 'stairsWide') return flat;

    const rotated = rotatePiece(pieceById(placement.pieceId), placement.rotation);
    const lowSide = rotated.sockets.find((socket) => socket.levelDelta === 0)?.side;
    const ascent = lowSide === 'N' ? { x: 0, z: 1 }
      : lowSide === 'S' ? { x: 0, z: -1 }
        : lowSide === 'W' ? { x: 1, z: 0 } : { x: -1, z: 0 };
    const run = ascent.x === 0 ? maxZ - minZ : maxX - minX;
    const cross = ascent.x === 0 ? maxX - minX : maxZ - minZ;
    const slope = Math.atan2(layout.levelHeight, run);
    const yaw = Math.atan2(ascent.x, ascent.z);
    const sinYaw = Math.sin(yaw / 2); const cosYaw = Math.cos(yaw / 2);
    const sinPitch = Math.sin(-slope / 2); const cosPitch = Math.cos(-slope / 2);
    return {
      kind: 'ramp',
      center: { ...center, y: baseY + layout.levelHeight / 2 - thickness / 2 },
      half: { x: cross / 2, y: thickness / 2, z: Math.hypot(run, layout.levelHeight) / 2 },
      rotation: {
        x: cosYaw * sinPitch,
        y: sinYaw * cosPitch,
        z: -sinYaw * sinPitch,
        w: cosYaw * cosPitch,
      },
    };
  });
}

function verticalOverlap(collider, minY, maxY) {
  return minY < collider.maxY && maxY > collider.minY;
}

export function circleHitsWall(position, radius, minY, maxY, colliders) {
  for (const collider of colliders) {
    if (!verticalOverlap(collider, minY, maxY)) continue;
    const nearestX = Math.max(collider.minX, Math.min(position.x, collider.maxX));
    const nearestZ = Math.max(collider.minZ, Math.min(position.z, collider.maxZ));
    if (Math.hypot(position.x - nearestX, position.z - nearestZ) < radius) return true;
  }
  return false;
}

function rayBoxDistance(origin, direction, maxDistance, collider, padding) {
  let near = 0;
  let far = maxDistance;
  for (const axis of ['x', 'y', 'z']) {
    const min = collider[`min${axis.toUpperCase()}`] - padding;
    const max = collider[`max${axis.toUpperCase()}`] + padding;
    const component = direction[axis];
    if (Math.abs(component) < 1e-8) {
      if (origin[axis] < min || origin[axis] > max) return null;
      continue;
    }
    let first = (min - origin[axis]) / component;
    let second = (max - origin[axis]) / component;
    if (first > second) [first, second] = [second, first];
    near = Math.max(near, first);
    far = Math.min(far, second);
    if (near > far) return null;
  }
  return near >= 0 && near <= maxDistance ? near : null;
}

export function cameraDistanceBeforeWall(target, desired, colliders, radius, minimumDistance) {
  const delta = {
    x: desired.x - target.x,
    y: desired.y - target.y,
    z: desired.z - target.z,
  };
  const distance = Math.hypot(delta.x, delta.y, delta.z);
  if (distance <= 1e-8) return 0;
  const direction = {
    x: delta.x / distance,
    y: delta.y / distance,
    z: delta.z / distance,
  };
  let allowed = distance;
  for (const collider of colliders) {
    const hitDistance = rayBoxDistance(target, direction, distance, collider, radius);
    if (hitDistance !== null) allowed = Math.min(allowed, Math.max(minimumDistance, hitDistance));
  }
  return allowed;
}
