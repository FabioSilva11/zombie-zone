import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { CAMPAIGN } from '../app/src/main/assets/game/js/campaign.js';
import { createInitialProgress } from '../app/src/main/assets/game/js/progression.js';
import { createChapterView } from '../app/src/main/assets/game/js/campaign-ui.js';

test('html oferece todas as telas e overlays aprovados', async () => {
  const html = await readFile(new URL('../app/src/main/assets/game/index.html', import.meta.url), 'utf8');
  for (const id of [
    'home-screen', 'chapter-screen', 'story-screen', 'preparation-screen',
    'pause-screen', 'settings-screen', 'victory-screen', 'defeat-screen', 'compatibility-screen',
  ]) assert.match(html, new RegExp(`id="${id}"`));
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
