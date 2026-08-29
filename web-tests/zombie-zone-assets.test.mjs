import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';

const imageUrl = (name) => new URL(`../app/src/main/assets/game/images/${name}`, import.meta.url);

test('fundos Zombie Zone são locais, completos e usados pelo tema', () => {
  const files = [
    'zombie-zone-city-bg.png',
    'zombie-zone-campaign-bg.png',
    'zombie-zone-lab-bg.png',
  ];
  const css = readFileSync(new URL('../app/src/main/assets/game/css/game.css', import.meta.url), 'utf8');

  for (const file of files) {
    assert.ok(statSync(imageUrl(file)).size > 100_000, `${file} precisa conter a arte final`);
    assert.match(css, new RegExp(file.replace('.', '\\.')));
  }
  assert.doesNotMatch(css, /url\(['"]?https?:/i);
});

test('sons de rifle MIT e aviso de licença estão empacotados', () => {
  for (const file of ['rifle-fire.mp3', 'rifle-reload.mp3']) {
    assert.ok(statSync(new URL(`../app/src/main/assets/game/audio/${file}`, import.meta.url)).size > 10_000);
  }
  const notice = readFileSync(new URL('../app/src/main/assets/game/notices/ThreeJS_FPS_2.0-MIT.txt', import.meta.url), 'utf8');
  assert.match(notice, /Copyright \(c\) 2025 FootPrint Arts/);
  assert.match(notice, /THE SOFTWARE IS PROVIDED "AS IS"/);
});
