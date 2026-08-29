import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import { attachRifle, createRifleModel, createRifleSockets } from '../app/src/main/assets/game/js/rifle-model.js';

test('rifle procedural possui grupos nomeados, meshes e materiais explícitos', () => {
  const rifle = createRifleModel(THREE);
  assert.equal(rifle.root.name, 'rifle-root');
  assert.equal(rifle.recoilGroup.name, 'rifle-recoil');
  assert.equal(rifle.muzzle.name, 'rifle-muzzle');
  const meshes = [];
  rifle.root.traverse((node) => { if (node.isMesh) meshes.push(node); });
  assert.ok(meshes.length >= 6);
  assert.ok(meshes.every((mesh) => mesh.material instanceof THREE.MeshStandardMaterial));
  rifle.dispose();
});

test('attachRifle reanexa a única árvore entre os sockets TPS e FPS', () => {
  const rifle = createRifleModel(THREE);
  const player = new THREE.Group();
  const camera = new THREE.PerspectiveCamera();
  const sockets = createRifleSockets({ THREE, playerRoot: player, camera });
  attachRifle(rifle, sockets.tps, 'tps');
  assert.equal(sockets.tps.children[0], rifle.root);
  attachRifle(rifle, sockets.fps, 'fps');
  assert.equal(sockets.tps.children.length, 0);
  assert.equal(sockets.fps.children[0], rifle.root);
  assert.equal(rifle.root.parent, sockets.fps);
  rifle.dispose();
});
