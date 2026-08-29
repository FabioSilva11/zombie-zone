import test from 'node:test';
import assert from 'node:assert/strict';
import { createAutomaticFire } from '../app/src/main/assets/game/js/automatic-fire.js';

test('segurar dispara a 600 RPM somente quando FPS está pronto', () => {
  const trigger = createAutomaticFire();
  let shots = 0;
  trigger.press();
  assert.equal(trigger.update(0.18, { ready: false, fire: () => { shots += 1; return true; } }), 0);
  assert.equal(trigger.update(0, { ready: true, fire: () => { shots += 1; return true; } }), 1);
  assert.equal(trigger.update(0.099, { ready: true, fire: () => { shots += 1; return true; } }), 0);
  assert.equal(trigger.update(0.001, { ready: true, fire: () => { shots += 1; return true; } }), 1);
  assert.equal(shots, 2);
});

test('soltar cancela imediatamente e recupera a dispersão', () => {
  const trigger = createAutomaticFire({ spreadPerShot: 0.01, recoverPerSecond: 0.1 });
  trigger.press();
  trigger.update(0, { ready: true, fire: () => true });
  const expanded = trigger.spread;
  trigger.release();
  trigger.update(1, { ready: true, fire: () => true });
  assert.equal(trigger.held, false);
  assert.ok(trigger.spread < expanded);
});
