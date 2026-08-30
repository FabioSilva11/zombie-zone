import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createZombieSpawnPlan, difficultyForStage } from '../app/src/main/assets/game/js/difficulty.js';
import { prepareBundledMap } from '../app/src/main/assets/game/js/map-library.js';

test('fase 1 começa com dez zumbis e carregador de trinta', () => {
  assert.deepEqual(difficultyForStage(1), {
    stage: 1, zombieCount: 10, magazineCapacity: 30, playerHealth: 100,
    zombieHealth: 70, zombieDamage: 8, zombieSpeedMultiplier: 1,
  });
});

test('zumbis e jogador escalam por fase e munição dobra a cada três', () => {
  assert.equal(difficultyForStage(3).magazineCapacity, 30);
  assert.equal(difficultyForStage(4).magazineCapacity, 60);
  assert.equal(difficultyForStage(7).magazineCapacity, 120);
  const final = difficultyForStage(20, { vitality: 5 });
  assert.equal(final.zombieCount, 29);
  assert.equal(final.magazineCapacity, 1920);
  assert.equal(final.playerHealth, 517);
  assert.equal(final.zombieHealth, 374);
  assert.ok(final.zombieDamage > 8);
  assert.ok(final.zombieSpeedMultiplier > 1);
});

test('biblioteca de mapa fornece pontos únicos para a horda progressiva', () => {
  const source = JSON.parse(readFileSync(new URL('../app/src/main/assets/game/maps/mapa (1).json', import.meta.url), 'utf8'));
  const layout = prepareBundledMap(source, 'mapa (1).json');
  const plan = createZombieSpawnPlan(layout, 29);
  assert.equal(plan.length, 29);
  assert.equal(new Set(plan.map((point) => `${point.x}:${point.y}:${point.z}`)).size, 29);
  assert.ok(plan.every((point) => Math.hypot(point.x - layout.start.x, point.z - layout.start.z) >= 9));
});
