import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createSelectionModel,
  selectCharacter,
} from '../app/src/main/assets/game/js/selection.js';

const sample = [
  { id: 'a', name: 'Kael', model: 'a.glb' },
  { id: 'b', name: 'Mira', model: 'b.glb' },
];

test('selection model marks exactly the requested character', () => {
  const model = createSelectionModel(sample, 'b');
  assert.equal(model.cards.length, 2);
  assert.deepEqual(model.cards.map((card) => card.selected), [false, true]);
  assert.equal(model.selected.name, 'Mira');
});

test('unknown selection safely falls back to the first character', () => {
  const initial = createSelectionModel(sample, 'missing');
  const updated = selectCharacter(initial, 'still-missing');
  assert.equal(updated.selected.id, 'a');
  assert.deepEqual(updated.cards.map((card) => card.selected), [true, false]);
});

