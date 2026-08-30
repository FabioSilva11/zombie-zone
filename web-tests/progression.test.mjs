import test from 'node:test';
import assert from 'node:assert/strict';
import { CAMPAIGN } from '../app/src/main/assets/game/js/campaign.js';
import {
  applyStageResult,
  createInitialProgress,
  evaluateStage,
  purchaseAvatar,
  purchaseUpgrade,
  upgradeCost,
} from '../app/src/main/assets/game/js/progression.js';

const resultFor = (stage, overrides = {}) => ({
  completed: true,
  elapsedSeconds: stage.targetSeconds - 1,
  healthRemaining: 50,
  maxHealth: 100,
  collectedCrystalIds: [],
  ...overrides,
});

test('conclusão, vida e tempo concedem três estrelas independentes', () => {
  const stage = CAMPAIGN.stages[0];
  assert.equal(evaluateStage(stage, resultFor(stage)).stars, 3);
  assert.equal(evaluateStage(stage, resultFor(stage, { healthRemaining: 49, elapsedSeconds: stage.targetSeconds + 1 })).stars, 1);
});

test('repetir o mesmo resultado não duplica cristais nem primeira conclusão', () => {
  const stage = CAMPAIGN.stages[0];
  const oneStar = resultFor(stage, { healthRemaining: 1, elapsedSeconds: stage.targetSeconds + 30 });
  const first = applyStageResult(createInitialProgress(), stage, oneStar);
  const replay = applyStageResult(first.progress, stage, oneStar);
  assert.ok(first.crystalsAwarded > 0);
  assert.equal(replay.crystalsAwarded, 0);
  assert.equal(replay.progress.crystals, first.progress.crystals);
});

test('concluir uma fase libera somente a próxima', () => {
  const stage = CAMPAIGN.stages[0];
  const applied = applyStageResult(createInitialProgress(), stage, resultFor(stage));
  assert.deepEqual(applied.progress.unlockedStageIds, ['stage1', 'stage2']);
});

test('melhorias respeitam custo, saldo e limite de cinco níveis', () => {
  const progress = createInitialProgress();
  progress.crystals = 1000;
  let current = progress;
  for (let level = 0; level < 5; level += 1) {
    const cost = upgradeCost('vitality', level);
    const bought = purchaseUpgrade(current, 'vitality');
    assert.equal(bought.spent, cost);
    current = bought.progress;
  }
  assert.throws(() => purchaseUpgrade(current, 'vitality'), /nível máximo/);
  assert.throws(() => purchaseUpgrade(createInitialProgress(), 'power'), /Cristais insuficientes/);
});

test('Kael começa desbloqueado e comprar uma skin desconta diamantes uma única vez', () => {
  const initial = createInitialProgress();
  assert.deepEqual(initial.unlockedAvatarIds, ['a']);
  const funded = { ...initial, crystals: 500 };
  const first = purchaseAvatar(funded, 'b');
  assert.equal(first.spent, 150);
  assert.equal(first.progress.crystals, 350);
  assert.deepEqual(first.progress.unlockedAvatarIds, ['a', 'b']);
  const repeated = purchaseAvatar(first.progress, 'b');
  assert.equal(repeated.spent, 0);
  assert.equal(repeated.progress.crystals, 350);
});

test('skin bloqueada não pode ser comprada sem diamantes suficientes', () => {
  assert.throws(() => purchaseAvatar(createInitialProgress(), 'b'), /Diamantes insuficientes/);
  assert.throws(() => purchaseAvatar(createInitialProgress(), 'desconhecida'), /Herói desconhecido/);
});
