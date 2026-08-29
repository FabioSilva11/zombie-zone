import test from 'node:test';
import assert from 'node:assert/strict';
import { createSaveStore } from '../app/src/main/assets/game/js/save-store.js';
import { createInitialProgress } from '../app/src/main/assets/game/js/progression.js';

class MemoryStorage {
  constructor() { this.values = new Map(); }
  getItem(key) { return this.values.get(key) ?? null; }
  setItem(key, value) { this.values.set(key, String(value)); }
  removeItem(key) { this.values.delete(key); }
}

test('save inválido recupera campos válidos sem apagar progresso', () => {
  const storage = new MemoryStorage();
  storage.setItem('save', JSON.stringify({
    version: 1,
    crystals: 95,
    selectedAvatar: 99,
    unlockedStageIds: ['stage1', 'fantasma'],
    stages: { stage1: { completed: true, stars: 2, bestTime: 90 } },
  }));
  const saved = createSaveStore(storage, 'save').load();
  assert.equal(saved.crystals, 95);
  assert.equal(saved.selectedAvatar, null);
  assert.deepEqual(saved.unlockedStageIds, ['stage1']);
  assert.equal(saved.stages.stage1.stars, 2);
});

test('update valida e persiste um registro completo', () => {
  const storage = new MemoryStorage();
  const store = createSaveStore(storage, 'save');
  const updated = store.update((progress) => ({ ...progress, crystals: 40, selectedAvatar: 'c' }));
  assert.equal(updated.crystals, 40);
  assert.equal(store.load().selectedAvatar, 'c');
  assert.equal(JSON.parse(storage.getItem('save')).version, createInitialProgress().version);
});

test('json corrompido retorna progresso inicial', () => {
  const storage = new MemoryStorage();
  storage.setItem('save', '{quebrado');
  assert.deepEqual(createSaveStore(storage, 'save').load(), createInitialProgress());
});
