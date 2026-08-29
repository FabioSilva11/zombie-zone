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
  assert.deepEqual(rifle.root.position.toArray(), [0.34, 1.52, -0.2]);
  assert.deepEqual(rifle.root.rotation.toArray(), [0.02, 0.03, -0.06, 'XYZ']);
  assert.deepEqual(rifle.root.scale.toArray(), [1.05, 1.05, 1.05]);
  const tpsTransform = {
    position: rifle.root.position.toArray(),
    rotation: rifle.root.rotation.toArray(),
    scale: rifle.root.scale.toArray(),
  };
  attachRifle(rifle, sockets.fps, 'fps');
  assert.equal(sockets.tps.children.length, 0);
  assert.equal(sockets.fps.children[0], rifle.root);
  assert.equal(rifle.root.parent, sockets.fps);
  assert.deepEqual(rifle.root.position.toArray(), [0.34, -0.28, -0.72]);
  assert.deepEqual(rifle.root.rotation.toArray(), [-0.03, 0.02, -0.02, 'XYZ']);
  assert.deepEqual(rifle.root.scale.toArray(), [0.42, 0.42, 0.42]);
  assert.notDeepEqual(rifle.root.position.toArray(), tpsTransform.position);
  assert.notDeepEqual(rifle.root.rotation.toArray(), tpsTransform.rotation);
  assert.notDeepEqual(rifle.root.scale.toArray(), tpsTransform.scale);
  rifle.dispose();
});
