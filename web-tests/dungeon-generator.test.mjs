import test from 'node:test';
import assert from 'node:assert/strict';
import { DUNGEON_PIECES } from '../app/src/main/assets/game/js/dungeon-catalog.js';
import {
  generateDungeon,
  placementBounds,
  validateDungeon,
} from '../app/src/main/assets/game/js/dungeon-generator.js';

test('same seed produces the same procedural dungeon', () => {
  assert.deepEqual(generateDungeon(8128), generateDungeon(8128));
});

test('every generated dungeon contains all 24 structural pieces', () => {
  for (const seed of [7, 41, 99, 8128, 65537]) {
    const layout = generateDungeon(seed);
    const ids = new Set(layout.placements.map((placement) => placement.pieceId));
    for (const piece of DUNGEON_PIECES) assert.equal(ids.has(piece.id), true, `${piece.id} ausente na seed ${seed}`);
  }
});

test('floor placements stay inside 50x50 and never overlap on the same level', () => {
  const layout = generateDungeon(99);
  const floor = layout.placements.filter((placement) => !placement.edge);
  for (const placement of floor) {
    const bounds = placementBounds(placement);
    assert.ok(bounds.minX >= 0 && bounds.minZ >= 0);
    assert.ok(bounds.maxX <= 50 && bounds.maxZ <= 50);
  }
  for (let i = 0; i < floor.length; i += 1) {
    for (let j = i + 1; j < floor.length; j += 1) {
      if (floor[i].level !== floor[j].level) continue;
      const a = placementBounds(floor[i]);
      const b = placementBounds(floor[j]);
      const overlaps = a.minX < b.maxX && a.maxX > b.minX && a.minZ < b.maxZ && a.maxZ > b.minZ;
      assert.equal(overlaps, false, `${floor[i].id} sobrepõe ${floor[j].id}`);
    }
  }
});

test('generator uses both levels and produces a connected validated graph', () => {
  for (const seed of [11, 23, 47, 83, 131]) {
    const layout = generateDungeon(seed);
    assert.deepEqual([...new Set(layout.placements.filter((item) => !item.edge).map((item) => item.level))].sort(), [0, 1]);
    assert.deepEqual(validateDungeon(layout), { valid: true, errors: [] });
  }
});
