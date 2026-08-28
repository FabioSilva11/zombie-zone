import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeProgress } from '../app/src/main/assets/game/js/loading.js';

test('loading progress rounds and clamps values for the HUD', () => {
  assert.deepEqual(normalizeProgress('Carregando', 44.6), {
    label: 'Carregando',
    percent: 45,
  });
  assert.deepEqual(normalizeProgress('', 400), {
    label: 'Preparando aventura',
    percent: 100,
  });
  assert.deepEqual(normalizeProgress('Rede', -2), {
    label: 'Rede',
    percent: 0,
  });
});
