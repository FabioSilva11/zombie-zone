import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('telas principais usam o mesmo avatar 3D e o sistema Zombie Zone', () => {
  const html = readFileSync(new URL('../app/src/main/assets/game/index.html', import.meta.url), 'utf8');
  for (const screen of ['home', 'avatar', 'preparation', 'victory']) {
    assert.match(html, new RegExp(`data-avatar-preview="${screen}"`));
  }
  assert.doesNotMatch(html, /data-home-avatar(?:=|\s)/);
  for (const id of ['loading-screen', 'home-screen', 'selection-screen', 'chapter-screen', 'story-screen', 'preparation-screen', 'game-hud', 'pause-screen', 'settings-screen', 'victory-screen', 'defeat-screen', 'compatibility-screen']) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
});
