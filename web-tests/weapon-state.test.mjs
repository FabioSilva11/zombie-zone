import test from 'node:test';
import assert from 'node:assert/strict';
import { createWeaponState } from '../app/src/main/assets/game/js/weapon-state.js';

test('disparo consome munição somente quando disponível', () => {
  const weapon = createWeaponState(2, 0.5);
  assert.equal(weapon.consumeShot(), true);
  assert.equal(weapon.consumeShot(), true);
  assert.equal(weapon.consumeShot(), false);
  assert.equal(weapon.ammo, 0);
});

test('recarga bloqueia disparo e completa após o tempo configurado', () => {
  const weapon = createWeaponState(2, 0.5);
  weapon.consumeShot();
  assert.equal(weapon.startReload(), true);
  assert.equal(weapon.consumeShot(), false);
  weapon.update(0.49);
  assert.equal(weapon.ammo, 1);
  weapon.update(0.02);
  assert.equal(weapon.ammo, 2);
  assert.equal(weapon.reloading, false);
});
