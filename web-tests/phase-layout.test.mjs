import test from 'node:test';
import assert from 'node:assert/strict';
import {
  CELL_SIZE_METERS,
  PHASE_SIZE_METERS,
  canOccupy,
  floorHeightAt,
  layoutIsConnected,
} from '../app/src/main/assets/game/js/phase-layout.js';
import { BUNDLED_MAP } from './bundled-map-fixture.mjs';

test('phase uses a native 50x50 grid without model stretching', () => {
  assert.equal(BUNDLED_MAP.width, 50);
  assert.equal(BUNDLED_MAP.height, 50);
  assert.equal(CELL_SIZE_METERS, 4);
  assert.equal(PHASE_SIZE_METERS, 200);
  assert.equal(new Set(BUNDLED_MAP.placements.map((item) => item.pieceId)).size, 24);
});

test('bundled placement graph is connected across both levels', () => {
  assert.equal(layoutIsConnected(BUNDLED_MAP), true);
  assert.deepEqual([...new Set(BUNDLED_MAP.placements.filter((item) => !item.edge).map((item) => item.level))].sort(), [0, 1]);
});

test('occupancy accepts bundled floors and rejects empty world cells', () => {
  assert.equal(canOccupy(BUNDLED_MAP.start, 0.4, BUNDLED_MAP), true);
  assert.equal(canOccupy({ x: -4, y: 0, z: -4 }, 0.4, BUNDLED_MAP), false);
  assert.equal(canOccupy({ x: PHASE_SIZE_METERS + 4, y: 0, z: 4 }, 0.4, BUNDLED_MAP), false);
});

test('floor height follows ordinary levels and interpolates stairs', () => {
  const upper = BUNDLED_MAP.placements.find((item) => !item.edge && item.level === 1);
  const upperCenter = {
    x: (upper.x + 0.5) * CELL_SIZE_METERS,
    y: BUNDLED_MAP.levelHeight,
    z: (upper.z + 0.5) * CELL_SIZE_METERS,
  };
  assert.equal(floorHeightAt(upperCenter, BUNDLED_MAP), BUNDLED_MAP.levelHeight);

  const stairs = BUNDLED_MAP.placements.find((item) => item.pieceId === 'stairs');
  const center = {
    x: (stairs.x + (stairs.rotation % 2 ? 1 : 0.5)) * CELL_SIZE_METERS,
    y: BUNDLED_MAP.levelHeight / 2,
    z: (stairs.z + (stairs.rotation % 2 ? 0.5 : 1)) * CELL_SIZE_METERS,
  };
  assert.ok(Math.abs(floorHeightAt(center, BUNDLED_MAP) - BUNDLED_MAP.levelHeight / 2) < 0.01);
});
