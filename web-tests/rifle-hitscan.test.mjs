import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import { resolveRifleHitscan } from '../app/src/main/assets/game/js/rifle-hitscan.js';

function createCamera() {
  const camera = new THREE.PerspectiveCamera(70, 1, 0.1, 100);
  camera.position.set(0, 1, 0);
  camera.lookAt(0, 1, -1);
  camera.updateMatrixWorld(true);
  return camera;
}

function createEnemy({ id = 'enemy-1', z = -10, meshY = 1 } = {}) {
  const root = new THREE.Group();
  root.position.set(0, 0, z);
  root.add(new THREE.Mesh(new THREE.BoxGeometry(1.2, 2, 1.2), new THREE.MeshBasicMaterial()));
  root.children[0].position.y = meshY;
  root.updateMatrixWorld(true);
  return { id, root, health: 70 };
}

function resolve({ wallMeshes = [], enemies = [], camera = createCamera() } = {}) {
  return resolveRifleHitscan({
    THREE,
    camera,
    raycaster: new THREE.Raycaster(),
    wallMeshes,
    enemies,
    spread: 0,
    random: () => 0.5,
    maxRange: 45,
  });
}

test('parede a 5 m bloqueia zumbi a 10 m', () => {
  const wall = new THREE.Mesh(new THREE.BoxGeometry(4, 4, 0.2), new THREE.MeshBasicMaterial());
  wall.position.set(0, 1, -5);
  wall.updateMatrixWorld(true);
  const enemy = createEnemy();

  const hit = resolve({ wallMeshes: [wall], enemies: [enemy] });

  assert.equal(hit.target, null);
  assert.equal(hit.zone, null);
  assert.ok(Math.abs(hit.distance - 4.9) < 1e-6);
  assert.ok(Math.abs(hit.point.z + 4.9) < 1e-6);
});

test('sem parede, retorna o inimigo atingido', () => {
  const enemy = createEnemy();

  const hit = resolve({ enemies: [enemy] });

  assert.equal(hit.target, enemy);
  assert.equal(hit.zone, 'body');
  assert.ok(Math.abs(hit.distance - 9.4) < 1e-6);
});

test('impacto a partir de 1,7 m acima da raiz do inimigo é cabeça', () => {
  const camera = createCamera();
  camera.position.y = 1.8;
  camera.lookAt(0, 1.8, -1);
  camera.updateMatrixWorld(true);
  const enemy = createEnemy({ meshY: 1.8 });

  const hit = resolve({ camera, enemies: [enemy] });

  assert.equal(hit.target, enemy);
  assert.equal(hit.zone, 'head');
});

test('miss termina exatamente no alcance de 45 m', () => {
  const hit = resolve();

  assert.equal(hit.target, null);
  assert.equal(hit.distance, 45);
  assert.ok(Math.abs(hit.point.distanceTo(new THREE.Vector3(0, 1, 0)) - 45) < 1e-9);
  assert.deepEqual(hit.point.toArray(), [0, 1, -45]);
});
