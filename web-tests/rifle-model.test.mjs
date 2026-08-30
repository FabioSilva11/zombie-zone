import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import { attachRifle, createRifleModel, createRifleSockets } from '../app/src/main/assets/game/js/rifle-model.js';
import { RIFLE_TRANSFORMS } from '../app/src/main/assets/game/js/rifle-placement.js';

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
  assert.deepEqual(rifle.root.position.toArray(), [...RIFLE_TRANSFORMS.tps.position]);
  assert.deepEqual(rifle.root.rotation.toArray(), [...RIFLE_TRANSFORMS.tps.rotation, 'XYZ']);
  assert.deepEqual(rifle.root.scale.toArray(), [RIFLE_TRANSFORMS.tps.scale, RIFLE_TRANSFORMS.tps.scale, RIFLE_TRANSFORMS.tps.scale]);
  player.rotation.y = 0.7;
  player.updateMatrixWorld(true);
  const muzzleForward = new THREE.Vector3(0, 0, -1).transformDirection(rifle.muzzle.matrixWorld);
  const playerForward = new THREE.Vector3(0, 0, 1).transformDirection(player.matrixWorld);
  assert.ok(muzzleForward.dot(playerForward) > 0.995, 'cano TPS deve apontar para a frente do personagem');
  const tpsTransform = {
    position: rifle.root.position.toArray(),
    rotation: rifle.root.rotation.toArray(),
    scale: rifle.root.scale.toArray(),
  };
  attachRifle(rifle, sockets.fps, 'fps');
  assert.equal(sockets.tps.children.length, 0);
  assert.equal(sockets.fps.children[0], rifle.root);
  assert.equal(rifle.root.parent, sockets.fps);
  assert.deepEqual(rifle.root.position.toArray(), [...RIFLE_TRANSFORMS.fps.position]);
  assert.deepEqual(rifle.root.rotation.toArray(), [...RIFLE_TRANSFORMS.fps.rotation, 'XYZ']);
  assert.deepEqual(rifle.root.scale.toArray(), [RIFLE_TRANSFORMS.fps.scale, RIFLE_TRANSFORMS.fps.scale, RIFLE_TRANSFORMS.fps.scale]);
  assert.notDeepEqual(rifle.root.position.toArray(), tpsTransform.position);
  assert.notDeepEqual(rifle.root.rotation.toArray(), tpsTransform.rotation);
  assert.notDeepEqual(rifle.root.scale.toArray(), tpsTransform.scale);
  rifle.dispose();
});
