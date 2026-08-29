import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('personagens do mundo usam escala compatível com câmera sobre o ombro', () => {
  const source = readFileSync(new URL('../app/src/main/assets/game/js/main.js', import.meta.url), 'utf8');
  assert.match(source, /const CHARACTER_WORLD_SCALE = 0\.72;/);
  assert.match(source, /root\.scale\.setScalar\(CHARACTER_WORLD_SCALE\)/);
  assert.doesNotMatch(source, /root\.scale\.setScalar\(1\.08\)/);
});
