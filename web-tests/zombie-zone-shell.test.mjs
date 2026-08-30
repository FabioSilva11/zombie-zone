import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('telas principais usam o mesmo avatar 3D e o sistema Zombie Zone', () => {
  const html = readFileSync(new URL('../app/src/main/assets/game/index.html', import.meta.url), 'utf8');
  for (const screen of ['home', 'avatar', 'victory']) {
    assert.match(html, new RegExp(`data-avatar-preview="${screen}"`));
  }
  assert.doesNotMatch(html, /data-home-avatar(?:=|\s)/);
  for (const id of ['loading-screen', 'home-screen', 'selection-screen', 'chapter-screen', 'upgrades-screen', 'game-hud', 'pause-screen', 'settings-screen', 'victory-screen', 'defeat-screen', 'compatibility-screen']) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
  assert.doesNotMatch(html, /id="story-screen"/);
  assert.doesNotMatch(html, /id="preparation-screen"/);
});

test('lobby concentra progresso, melhorias e heróis sem preparação intermediária', () => {
  const html = readFileSync(new URL('../app/src/main/assets/game/index.html', import.meta.url), 'utf8');
  assert.match(html, /data-home-upgrades/);
  assert.match(html, /data-avatar-back/);
  assert.match(html, /data-map-start/);
  assert.match(html, /data-loading-story/);
  assert.match(html, /data-home-current-title/);
  assert.match(html, /data-home-current-number/);
  assert.match(html, /id="upgrades-screen"/);
  assert.doesNotMatch(html, /A cidade precisa de você!/);
  assert.doesNotMatch(html, /ENTRAR NA ZONA/);
  assert.doesNotMatch(html, />Preparação</);
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
  assert.match(css, /\[hidden\]\s*\{[^}]*display:\s*none\s*!important/s);
  assert.match(html, /\.\/css\/game\.css\?v=lobby-3/);
  assert.match(html, /\.\/js\/bootstrap\.js\?v=free-look-2/);
  assert.match(bootstrap, /import\('\.\/main\.js\?v=free-look-2'\)/);
});

test('HUD oferece ações e editor de posição, tamanho e visibilidade', () => {
  const html = readFileSync(new URL('../app/src/main/assets/game/index.html', import.meta.url), 'utf8');
  assert.match(html, /id="jump-button"/);
  assert.match(html, /id="crouch-button"/);
  assert.match(html, /id="sprint-button"/);
  assert.match(html, /id="controls-editor-screen"/);
  assert.match(html, /class="camera-hint" aria-hidden="true"/);
  assert.doesNotMatch(html, /data-layout-control="camera"/);
  assert.match(html, /data-layout-control="shoot"/);
  assert.match(html, /data-control-size/);
  assert.match(html, /data-control-visibility/);
});
