import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { CAMPAIGN } from '../app/src/main/assets/game/js/campaign.js';
import { createInitialProgress } from '../app/src/main/assets/game/js/progression.js';
import { chapterRangeLabel, createCampaignPath, createChapterView, currentLobbyStage } from '../app/src/main/assets/game/js/campaign-ui.js';

test('html oferece todas as telas e overlays aprovados', async () => {
  const html = await readFile(new URL('../app/src/main/assets/game/index.html', import.meta.url), 'utf8');
  for (const id of [
    'home-screen', 'chapter-screen', 'selection-screen', 'upgrades-screen',
    'pause-screen', 'settings-screen', 'victory-screen', 'defeat-screen', 'compatibility-screen',
  ]) assert.match(html, new RegExp(`id="${id}"`));
  assert.doesNotMatch(html, /id="story-screen"/);
  assert.doesNotMatch(html, /id="preparation-screen"/);
  assert.match(html, /data-sound-enabled/);
  assert.match(html, /data-settings-close/);
  assert.match(html, /id="reload-button"/);
  assert.match(html, /id="dodge-button"/);
  assert.match(html, /data-crystal-feedback/);
});

test('mapa visual distingue fase disponível, concluída e bloqueada', () => {
  const progress = createInitialProgress();
  progress.unlockedStageIds.push('stage2');
  progress.stages.stage1 = { completed: true, stars: 2 };
  const view = createChapterView(CAMPAIGN, progress);
  assert.equal(view[0].status, 'completed');
  assert.equal(view[0].stars, 2);
  assert.equal(view[1].status, 'available');
  assert.equal(view[2].status, 'locked');
});

test('cada card do mapa informa estrelas e coletáveis da própria fase', () => {
  const progress = createInitialProgress();
  progress.stages.stage1 = {
    completed: true,
    stars: 2,
    collectedCrystalIds: ['stage1:crystal-1', 'stage1:crystal-3'],
  };
  const [first] = createChapterView(CAMPAIGN, progress);
  assert.equal(first.stars, 2);
  assert.equal(first.starTotal, 3);
  assert.equal(first.collectibles, 2);
  assert.equal(first.collectibleTotal, 3);
  assert.equal(createChapterView(CAMPAIGN, progress).length, 20);
});

test('caminho de vinte fases usa dezenove conexões entre as bordas dos cards', () => {
  const path = createCampaignPath(createChapterView(CAMPAIGN, createInitialProgress()));
  assert.equal(path.nodes.length, 20);
  assert.equal(path.connectors.length, 19);
  assert.deepEqual(path.connectors[0], { from: 'stage1', to: 'stage2' });
  assert.deepEqual(path.connectors.at(-1), { from: 'stage19', to: 'stage20' });
});

test('indicador da zona deixa explícita a faixa visível dentro das vinte fases', () => {
  assert.equal(chapterRangeLabel(1, 6, 20), 'FASES 01–06 DE 20');
  assert.equal(chapterRangeLabel(19, 30, 20), 'FASES 19–20 DE 20');
});

test('lobby mostra a missão mais avançada que já foi liberada', () => {
  const progress = createInitialProgress();
  progress.unlockedStageIds.push('stage2');
  progress.stages.stage1 = { completed: true, stars: 3, collectedCrystalIds: [] };
  assert.equal(currentLobbyStage(CAMPAIGN, progress).id, 'stage2');
});
