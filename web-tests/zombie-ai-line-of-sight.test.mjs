import test from 'node:test';
import assert from 'node:assert/strict';
import { hasLineOfSight } from '../app/src/main/assets/game/js/map-collision.js';

const wall = { minX: 2, maxX: 3, minY: 0, maxY: 3, minZ: -1, maxZ: 1 };

test('parede bloqueia a percepção entre o zumbi e o jogador', () => {
  assert.equal(hasLineOfSight({ x: 0, y: 1, z: 0 }, { x: 5, y: 1, z: 0 }, [wall]), false);
});

test('percepção permanece livre no mesmo lado ou acima da parede', () => {
  assert.equal(hasLineOfSight({ x: 0, y: 1, z: 0 }, { x: 1.5, y: 1, z: 0 }, [wall]), true);
  assert.equal(hasLineOfSight({ x: 0, y: 4, z: 0 }, { x: 5, y: 4, z: 0 }, [wall]), true);
});
