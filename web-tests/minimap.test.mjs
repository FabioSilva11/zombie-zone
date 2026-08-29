import test from 'node:test';
import assert from 'node:assert/strict';
import {
  cellKey,
  createExplorationState,
  isCellExplored,
  mountMinimap,
  revealAround,
  worldToCell,
} from '../app/src/main/assets/game/js/minimap.js';
import { BUNDLED_MAP } from './bundled-map-fixture.mjs';

test('minimapa usa o grid modular real de 50x50', () => {
  const state = createExplorationState(BUNDLED_MAP);
  assert.equal(state.width, 50);
  assert.equal(state.height, 50);
  assert.equal(state.cellSize, 4);
  assert.deepEqual(worldToCell({ x: 100, y: 0, z: 100 }, state), { x: 25, z: 25, level: 0 });
});

test('dispose remove listeners do minimapa entre replays', () => {
  const events = new Map();
  const closeEvents = new Map();
  const button = {
    addEventListener: (name, handler) => events.set(name, handler),
    removeEventListener: (name, handler) => { if (events.get(name) === handler) events.delete(name); },
  };
  const closeButton = {
    addEventListener: (name, handler) => closeEvents.set(name, handler),
    removeEventListener: (name, handler) => { if (closeEvents.get(name) === handler) closeEvents.delete(name); },
  };
  const overlay = {
    classList: { add() {}, remove() {}, contains: () => true },
    querySelector: () => closeButton,
  };
  const minimap = mountMinimap({ button, overlay, canvas: {}, largeCanvas: {}, layout: BUNDLED_MAP, getState: () => ({}) });
  assert.equal(events.size, 1);
  assert.equal(closeEvents.size, 1);
  minimap.dispose();
  assert.equal(events.size, 0);
  assert.equal(closeEvents.size, 0);
});

test('exploração começa escura e revela somente a área próxima no andar atual', () => {
  const state = createExplorationState(BUNDLED_MAP);
  assert.equal(isCellExplored(state, 25, 25, 0), false);
  revealAround(state, { x: 100, y: 0, z: 100 }, 1);
  assert.equal(isCellExplored(state, 25, 25, 0), true);
  assert.equal(isCellExplored(state, 25, 25, 1), false);
  assert.ok(state.explored.has(cellKey(25, 25, 0)));
});

test('o segundo andar mantém descoberta independente e sem duplicatas', () => {
  const state = createExplorationState(BUNDLED_MAP);
  const upstairs = { x: 100, y: BUNDLED_MAP.levelHeight, z: 100 };
  revealAround(state, upstairs, 1);
  const count = state.explored.size;
  revealAround(state, upstairs, 1);
  assert.equal(state.explored.size, count);
  assert.equal(isCellExplored(state, 25, 25, 1), true);
  assert.equal(isCellExplored(state, 25, 25, 0), false);
});
