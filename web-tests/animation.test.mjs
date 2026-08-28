import test from 'node:test';
import assert from 'node:assert/strict';
import { pickClipName } from '../app/src/main/assets/game/js/animation.js';

test('animation picks the first available preferred clip', () => {
  assert.equal(
    pickClipName(['idle', 'walk', 'attack-melee-right'], ['attack-melee-left', 'attack-melee-right', 'idle']),
    'attack-melee-right',
  );
});

test('animation falls back to idle and then to the first clip', () => {
  assert.equal(pickClipName(['walk', 'idle'], ['die']), 'idle');
  assert.equal(pickClipName(['static'], ['die']), 'static');
  assert.equal(pickClipName([], ['die']), null);
});

