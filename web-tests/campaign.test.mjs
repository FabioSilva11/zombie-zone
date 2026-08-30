import test from 'node:test';
import assert from 'node:assert/strict';
import { CAMPAIGN, stageById } from '../app/src/main/assets/game/js/campaign.js';

test('campanha oferece os vinte mapas em ordem sem pular ambientes', () => {
  assert.equal(CAMPAIGN.stages.length, 20);
  assert.deepEqual(CAMPAIGN.stages.map((stage) => stage.number), Array.from({ length: 20 }, (_, index) => index + 1));
  assert.deepEqual(CAMPAIGN.stages.map((stage) => stage.mapId), Array.from({ length: 20 }, (_, index) => `mapa (${index + 1})`));
  for (const stage of CAMPAIGN.stages) {
    assert.ok(stage.id && stage.title && stage.objective);
    assert.ok(stage.targetSeconds > 0 && stage.firstClearCrystals > 0);
    assert.equal(stage.collectibleTotal, 3);
    assert.ok(stage.storyBefore.lines.length > 0 && stage.storyBefore.lines.length <= 3);
    assert.ok(stage.storyAfter.lines.length > 0 && stage.storyAfter.lines.length <= 3);
    assert.equal(stageById(stage.id), stage);
  }
});

test('vinte fases permitem sessenta estrelas e sessenta coletáveis', () => {
  assert.equal(CAMPAIGN.totalStars, 60);
  assert.equal(CAMPAIGN.totalCollectibles, 60);
});

test('fase desconhecida é rejeitada em vez de iniciar conteúdo incorreto', () => {
  assert.throws(() => stageById('desconhecida'), /Fase desconhecida/);
});
