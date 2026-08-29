import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import { createRifleModel } from '../app/src/main/assets/game/js/rifle-model.js';
import { createRifleEffects } from '../app/src/main/assets/game/js/rifle-effects.js';

test('disparo mostra clarão, cria traço e encerra ambos com retorno do recuo', () => {
  const scene = new THREE.Scene();
  const rifle = createRifleModel(THREE);
  scene.add(rifle.root);
  const effects = createRifleEffects({ THREE, scene, recoilGroup: rifle.recoilGroup, muzzle: rifle.muzzle });
  effects.pulseShot(new THREE.Vector3(0, 1, -10));
  assert.equal(effects.flash.visible, true);
  assert.equal(rifle.recoilGroup.position.z, 0.08);
  assert.ok(scene.children.some((child) => child.isLine));
  effects.update(0.08);
  assert.equal(effects.flash.visible, false);
  assert.equal(scene.children.some((child) => child.isLine), false);
  assert.ok(Math.abs(rifle.recoilGroup.position.z) < 0.001);
  effects.dispose();
  rifle.dispose();
});
