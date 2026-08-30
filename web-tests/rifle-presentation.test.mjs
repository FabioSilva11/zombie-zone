import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveRiflePresentation } from '../app/src/main/assets/game/js/rifle-presentation.js';

test('rifle fica oculto enquanto não está sendo usado', () => {
  assert.deepEqual(resolveRiflePresentation(), { visible: false, animation: null });
});

test('mirar mostra o rifle na pose holding-right', () => {
  const state = resolveRiflePresentation({ aimHeld: true });
  assert.equal(state.visible, true);
  assert.equal(state.animation[0], 'holding-right');
});

test('atirar mostra o rifle e prioriza holding-right-shoot', () => {
  const state = resolveRiflePresentation({ fireHeld: true });
  assert.equal(state.visible, true);
  assert.deepEqual(state.animation.slice(0, 2), ['holding-right-shoot', 'holding-right']);
});
