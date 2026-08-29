import test from 'node:test';
import assert from 'node:assert/strict';
import { CAMPAIGN, stageById } from '../app/src/main/assets/game/js/campaign.js';

test('campanha oferece seis fases sequenciais com história curta e metas completas', () => {
  assert.deepEqual(CAMPAIGN.stages.map((stage) => stage.number), [1, 2, 3, 4, 5, 6]);
  for (const stage of CAMPAIGN.stages) {
    assert.ok(stage.id && stage.title && stage.objective);
    assert.ok(stage.targetSeconds > 0 && stage.firstClearCrystals > 0);
    assert.ok(stage.storyBefore.lines.length > 0 && stage.storyBefore.lines.length <= 3);
    assert.ok(stage.storyAfter.lines.length > 0 && stage.storyAfter.lines.length <= 3);
    assert.equal(stageById(stage.id), stage);
  }
});

test('fase desconhecida é rejeitada em vez de iniciar conteúdo incorreto', () => {
  assert.throws(() => stageById('desconhecida'), /Fase desconhecida/);
});
