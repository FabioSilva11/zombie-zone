import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createSelectionModel,
  selectionAction,
  selectCharacter,
} from '../app/src/main/assets/game/js/selection.js';

const sample = [
  { id: 'a', name: 'Kael', model: 'a.glb', price: 0 },
  { id: 'b', name: 'Mira', model: 'b.glb', price: 150 },
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

test('modelo diferencia herói liberado de skin comprável', () => {
  const model = createSelectionModel(sample, 'b', ['a']);
  assert.deepEqual(model.cards.map((card) => card.owned), [true, false]);
  assert.deepEqual(model.cards.map((card) => card.price), [0, 150]);
  assert.equal(model.selected.id, 'b');
  assert.equal(model.selected.owned, false);
});

test('ação da seleção equipa herói próprio ou cobra pela skin bloqueada', () => {
  const owned = createSelectionModel(sample, 'a', ['a']);
  assert.deepEqual(selectionAction(owned, 0), { kind: 'equip', label: 'USAR HERÓI', disabled: false });
  const locked = createSelectionModel(sample, 'b', ['a']);
  assert.deepEqual(selectionAction(locked, 200), { kind: 'purchase', label: 'DESBLOQUEAR ◆ 150', disabled: false });
  assert.deepEqual(selectionAction(locked, 100), { kind: 'purchase', label: 'DESBLOQUEAR ◆ 150', disabled: true });
});
