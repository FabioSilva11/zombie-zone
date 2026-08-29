import test from 'node:test';
import assert from 'node:assert/strict';
import { selectAssistedTarget } from '../app/src/main/assets/game/js/aim-assist.js';

test('mira escolhe o alvo visível mais central antes do mais próximo', () => {
  const selected = selectAssistedTarget({
    candidates: [
      { id: 'perto-lateral', distance: 4, angle: 0.1 },
      { id: 'centro', distance: 8, angle: 0.02 },
      { id: 'bloqueado', distance: 2, angle: 0.01 },
    ],
    maxRange: 18,
    maxAngle: 0.12,
    hasLineOfSight: (id) => id !== 'bloqueado',
  });
  assert.equal(selected.id, 'centro');
});

test('mira rejeita distância, ângulo e parede', () => {
  const base = { maxRange: 18, maxAngle: 0.12, hasLineOfSight: () => false };
  assert.equal(selectAssistedTarget({ ...base, candidates: [{ id: 'parede', distance: 5, angle: 0.01 }] }), null);
  assert.equal(selectAssistedTarget({ ...base, hasLineOfSight: () => true, candidates: [{ id: 'longe', distance: 19, angle: 0.01 }] }), null);
  assert.equal(selectAssistedTarget({ ...base, hasLineOfSight: () => true, candidates: [{ id: 'lado', distance: 5, angle: 0.13 }] }), null);
});

test('alvo retido recebe bônus pequeno sem vencer um alvo muito mais central', () => {
  const selected = selectAssistedTarget({
    candidates: [{ id: 'retido', distance: 6, angle: 0.05 }, { id: 'novo', distance: 7, angle: 0.01 }],
    maxRange: 18, maxAngle: 0.12, retainedId: 'retido', hasLineOfSight: () => true,
  });
  assert.equal(selected.id, 'novo');
});
