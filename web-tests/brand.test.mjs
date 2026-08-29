import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('nome Zombie Zone aparece nas superfícies públicas do jogo', () => {
  const html = read('../app/src/main/assets/game/index.html');
  const strings = read('../app/src/main/res/values/strings.xml');
  const campaign = read('../app/src/main/assets/game/js/campaign.js');
  const packageJson = JSON.parse(read('../package.json'));

  assert.match(html, /<title>Zombie Zone<\/title>/);
  assert.match(html, /aria-label="Mundo de Zombie Zone"/);
  assert.match(strings, /<string name="app_name">Zombie Zone<\/string>/);
  assert.equal(packageJson.name, 'zombie-zone');
  assert.doesNotMatch(html, /Ecos de Eldra/i);
  assert.doesNotMatch(campaign, /Eldra/i);
  assert.match(campaign, /Coração da Zona Z/);
});
