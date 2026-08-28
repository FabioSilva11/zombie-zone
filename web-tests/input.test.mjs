import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeStick } from '../app/src/main/assets/game/js/input.js';

test('joystick preserves small movement and limits large movement to one', () => {
  assert.deepEqual(normalizeStick(10, -20, 40), { x: 0.25, y: -0.5 });
  const large = normalizeStick(100, 100, 40);
  assert.ok(Math.hypot(large.x, large.y) <= 1.000001);
  assert.ok(Math.abs(large.x - Math.SQRT1_2) < 0.000001);
});

test('joystick handles zero radius without invalid numbers', () => {
  assert.deepEqual(normalizeStick(10, 10, 0), { x: 0, y: 0 });
});
