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

test('HUD inicia o rifle cheio, identifica o gatilho e abre a mira por --spread', () => {
  const html = readFileSync(new URL('../app/src/main/assets/game/index.html', import.meta.url), 'utf8');
  const css = readFileSync(new URL('../app/src/main/assets/game/css/game.css', import.meta.url), 'utf8');
  const bootstrap = readFileSync(new URL('../app/src/main/assets/game/js/bootstrap.js', import.meta.url), 'utf8');

  assert.match(html, /data-ammo-count>30\/30</);
  assert.match(html, /id="attack-button"[^>]*aria-label="Atirar e arrastar a câmera"/);
  assert.match(html, /id="aim-button"[^>]*aria-label="Alternar mira"[^>]*aria-pressed="false"[^>]*>[\s\S]*?<small>MIRAR<\/small>/);
  assert.match(css, /\.attack-button\s*\{[^}]*touch-action:\s*none/s);
  assert.match(css, /\.aim-button[^}]*min-width:\s*48px[^}]*min-height:\s*48px/s);
  assert.match(css, /\.reticle\s*\{[^}]*--spread:\s*0px[^}]*width:\s*calc\(28px \+ var\(--spread\)\)[^}]*height:\s*calc\(28px \+ var\(--spread\)\)/s);
  assert.match(css, /\.reticle\.aimed\s*\{[^}]*border-color:\s*var\(--amber\)/s);
  assert.match(html, /\.\/css\/game\.css\?v=mobile-hud-3/);
  assert.match(html, /\.\/js\/bootstrap\.js\?v=mobile-hud-3/);
  assert.match(bootstrap, /import\('\.\/main\.js\?v=mobile-hud-3'\)/);
});
