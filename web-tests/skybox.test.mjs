import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { SKYBOXES, skyboxForMap } from '../app/src/main/assets/game/js/skybox.js';

test('cinco céus Kenney estão empacotados no jogo', () => {
  assert.equal(SKYBOXES.length, 5);
  for (const skybox of SKYBOXES) {
    assert.equal(existsSync(new URL(`../app/src/main/assets/game/images/skyboxes/${skybox.file}`, import.meta.url)), true, skybox.file);
  }
});

test('céu troca por fase e repete a sequência nos vinte mapas', () => {
  assert.equal(skyboxForMap('mapa (1)').id, 'day');
  assert.equal(skyboxForMap('mapa (2)').id, 'morning');
  assert.equal(skyboxForMap('mapa (5)').id, 'space');
  assert.equal(skyboxForMap('mapa (6)').id, 'day');
  assert.equal(skyboxForMap('mapa (20)').id, 'space');
});
