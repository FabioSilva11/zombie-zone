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
