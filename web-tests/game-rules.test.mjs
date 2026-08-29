import test from 'node:test';
import assert from 'node:assert/strict';
import {
  canMeleeHit,
  canRangedHit,
  clampToArena,
  nextEnemyState,
} from '../app/src/main/assets/game/js/game-rules.js';

test('arena clamp keeps movement inside the playable rectangle', () => {
  assert.deepEqual(clampToArena({ x: 99, z: -99 }), { x: 11.5, z: -17.5 });
  assert.deepEqual(clampToArena({ x: -99, z: 99 }), { x: -11.5, z: 17.5 });
  assert.deepEqual(clampToArena({ x: 2, z: -3 }), { x: 2, z: -3 });
});

test('tiro exige alcance e alvo vivo, mas não depende do cone corpo a corpo', () => {
  assert.equal(canRangedHit({ health: 50, distance: 17.9 }, 18), true);
  assert.equal(canRangedHit({ health: 50, distance: 18.1 }, 18), false);
  assert.equal(canRangedHit({ health: 0, distance: 2 }, 18), false);
});

test('melee hits a nearby target in front but never one behind', () => {
  const attacker = { x: 0, z: 0, yaw: 0 };
  assert.equal(canMeleeHit(attacker, { x: 0.8, z: -1.2 }), true);
  assert.equal(canMeleeHit(attacker, { x: 0, z: 1.2 }), false);
});

test('melee rejects targets beyond its range', () => {
  assert.equal(
    canMeleeHit({ x: 0, z: 0, yaw: 0 }, { x: 0, z: -2.16 }),
    false,
  );
});

test('enemy state prioritizes death, then attack, chase and idle', () => {
  const player = { x: 0, z: 0 };
  assert.equal(nextEnemyState({ health: 0, x: 0, z: 0 }, player), 'dead');
  assert.equal(nextEnemyState({ health: 10, x: 0, z: 1.5 }, player), 'attack');
  assert.equal(nextEnemyState({ health: 10, x: 0, z: 5 }, player), 'chase');
  assert.equal(nextEnemyState({ health: 10, x: 0, z: 12 }, player), 'idle');
});
