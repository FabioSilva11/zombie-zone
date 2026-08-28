import test from 'node:test';
import assert from 'node:assert/strict';
import { CHARACTERS } from '../app/src/main/assets/game/js/catalog.js';

test('catalog exposes every supplied blocky character once', () => {
  assert.equal(CHARACTERS.length, 18);
  assert.deepEqual(
    CHARACTERS.map((character) => character.id),
    'abcdefghijklmnopqr'.split(''),
  );
  assert.equal(new Set(CHARACTERS.map((character) => character.model)).size, 18);
});

test('every character points to its matching local GLB and texture', () => {
  for (const character of CHARACTERS) {
    assert.equal(character.model, `./models/characters/character-${character.id}.glb`);
    assert.equal(character.texture, `./models/characters/Textures/texture-${character.id}.png`);
  }
});

