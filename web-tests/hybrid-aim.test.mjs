import test from 'node:test';
import assert from 'node:assert/strict';
import { createHybridAim } from '../app/src/main/assets/game/js/hybrid-aim.js';

test('pressão entra em FPS em 180 ms e soltura volta a TPS', () => {
  const aim = createHybridAim();
  aim.press();
  aim.update(0.09);
  assert.equal(aim.state, 'entering-first-person');
  assert.ok(aim.blend > 0.49 && aim.blend < 0.51);
  assert.equal(aim.readyToFire, false);
  aim.update(0.09);
  assert.equal(aim.state, 'first-person');
  assert.equal(aim.readyToFire, true);
  aim.release();
  aim.update(0.18);
  assert.equal(aim.state, 'third-person');
  assert.equal(aim.blend, 0);
});

test('cancel volta imediatamente ao destino TPS e bloqueia tiro', () => {
  const aim = createHybridAim();
  aim.press();
  aim.update(0.18);
  aim.cancel();
  assert.equal(aim.readyToFire, false);
  assert.equal(aim.state, 'returning-third-person');
});
