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
  assert.deepEqual(saved.unlockedAvatarIds, ['a']);
  assert.deepEqual(saved.unlockedStageIds, ['stage1']);
  assert.equal(saved.stages.stage1.stars, 2);
});

test('update valida e persiste um registro completo', () => {
  const storage = new MemoryStorage();
  const store = createSaveStore(storage, 'save');
  const updated = store.update((progress) => ({ ...progress, crystals: 40, selectedAvatar: 'c' }));
  assert.equal(updated.crystals, 40);
  assert.equal(store.load().selectedAvatar, 'c');
  assert.deepEqual(store.load().unlockedAvatarIds, ['a', 'c']);
  assert.equal(JSON.parse(storage.getItem('save')).version, createInitialProgress().version);
});

test('migração preserva como desbloqueado o herói selecionado em uma versão antiga', () => {
  const storage = new MemoryStorage();
  storage.setItem('save', JSON.stringify({ version: 1, selectedAvatar: 'd', hasStarted: true }));
  const migrated = createSaveStore(storage, 'save').load();
  assert.deepEqual(migrated.unlockedAvatarIds, ['a', 'd']);
  assert.equal(migrated.selectedAvatar, 'd');
});

test('json corrompido retorna progresso inicial', () => {
  const storage = new MemoryStorage();
  storage.setItem('save', '{quebrado');
  assert.deepEqual(createSaveStore(storage, 'save').load(), createInitialProgress());
});

test('coletáveis inválidos do save não ultrapassam os três da fase', () => {
  const storage = new MemoryStorage();
  storage.setItem('save', JSON.stringify({
    stages: { stage1: { collectedCrystalIds: ['stage1:crystal-1', 'stage1:crystal-2', 'stage1:crystal-3', 'stage1:crystal-4', 'outra-fase'] } },
  }));
  assert.deepEqual(createSaveStore(storage, 'save').load().stages.stage1.collectedCrystalIds, [
    'stage1:crystal-1', 'stage1:crystal-2', 'stage1:crystal-3',
  ]);
});

test('economia patrocinada é normalizada com cap diário', () => {
  const storage = new MemoryStorage();
  storage.setItem('save', JSON.stringify({
    monetization: { rewardedAdsClaimedToday: 11, rewardedAdsDayKey: '2026-08-29' },
  }));
  const saved = createSaveStore(storage, 'save').load();
  assert.equal(saved.monetization.rewardedAdsClaimedToday, 5);
  assert.equal(saved.monetization.rewardedAdsDayKey, '2026-08-29');
});
