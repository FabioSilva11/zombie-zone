import test from 'node:test';
import assert from 'node:assert/strict';
import {
  CELL_SIZE_METERS,
  PHASE_SIZE_METERS,
  PHASE_LAYOUT,
  canOccupy,
  floorHeightAt,
  layoutIsConnected,
} from '../app/src/main/assets/game/js/phase-layout.js';

test('phase uses a native 50x50 grid without model stretching', () => {
  assert.equal(PHASE_LAYOUT.width, 50);
  assert.equal(PHASE_LAYOUT.height, 50);
  assert.equal(CELL_SIZE_METERS, 4);
  assert.equal(PHASE_SIZE_METERS, 200);
  assert.equal(new Set(PHASE_LAYOUT.placements.map((item) => item.pieceId)).size, 24);
});

test('procedural placement graph is connected across both levels', () => {
  assert.equal(layoutIsConnected(PHASE_LAYOUT), true);
  assert.deepEqual([...new Set(PHASE_LAYOUT.placements.filter((item) => !item.edge).map((item) => item.level))].sort(), [0, 1]);
});

test('occupancy accepts generated floors and rejects empty world cells', () => {
  assert.equal(canOccupy(PHASE_LAYOUT.start, 0.4), true);
  assert.equal(canOccupy({ x: -4, y: 0, z: -4 }, 0.4), false);
  assert.equal(canOccupy({ x: PHASE_SIZE_METERS + 4, y: 0, z: 4 }, 0.4), false);
});

test('floor height follows ordinary levels and interpolates stairs', () => {
  const upper = PHASE_LAYOUT.placements.find((item) => !item.edge && item.level === 1);
  const upperCenter = {
    x: (upper.x + 0.5) * CELL_SIZE_METERS,
    y: PHASE_LAYOUT.levelHeight,
    z: (upper.z + 0.5) * CELL_SIZE_METERS,
  };
  assert.equal(floorHeightAt(upperCenter), PHASE_LAYOUT.levelHeight);

  const stairs = PHASE_LAYOUT.placements.find((item) => item.pieceId === 'stairs');
  const center = {
    x: (stairs.x + (stairs.rotation % 2 ? 1 : 0.5)) * CELL_SIZE_METERS,
    y: PHASE_LAYOUT.levelHeight / 2,
    z: (stairs.z + (stairs.rotation % 2 ? 0.5 : 1)) * CELL_SIZE_METERS,
  };
  assert.ok(Math.abs(floorHeightAt(center) - PHASE_LAYOUT.levelHeight / 2) < 0.01);
});
