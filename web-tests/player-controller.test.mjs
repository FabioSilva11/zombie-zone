import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import RAPIER from '@dimforge/rapier3d-compat';
import { buildFloorColliders, buildWallColliders } from '../app/src/main/assets/game/js/map-collision.js';
import { prepareBundledMap } from '../app/src/main/assets/game/js/map-library.js';
import { floorHeightAt } from '../app/src/main/assets/game/js/phase-layout.js';
import { applyNippleVector, cameraRelativeMovement, createPlayerController, dodgeMovementInput, wallToCuboid } from '../app/src/main/assets/game/js/player-controller.js';

test('zona externa não colide com o DOM interno criado pelo NippleJS', () => {
  const html = readFileSync(new URL('../app/src/main/assets/game/index.html', import.meta.url), 'utf8');
  const css = readFileSync(new URL('../app/src/main/assets/game/css/game.css', import.meta.url), 'utf8');
  const zone = html.match(/<div id="joystick"[\s\S]*?<\/div>/)?.[0] ?? '';

  assert.match(zone, /class="movement-zone"/);
  assert.doesNotMatch(zone, /joystick-(?:ring|knob)/);
  assert.doesNotMatch(css, /(?:^|\n)\.joystick\s*\{/);
});

test('movimento do joystick segue frente e direita da câmera', () => {
  assert.deepEqual(cameraRelativeMovement({ x: 0, y: 1 }, 0, 4, 0.5), { x: 0, y: 0, z: -2 });
  const rightCamera = cameraRelativeMovement({ x: 0, y: 1 }, Math.PI / 2, 4, 0.5);
  assert.ok(Math.abs(rightCamera.x - 2) < 1e-10);
  assert.ok(Math.abs(rightCamera.z) < 1e-10);
});

test('intensidade analógica é linear e não aplicada duas vezes', () => {
  const full = cameraRelativeMovement({ x: 0, y: 1 }, 0, 4, 0.5);
  const half = cameraRelativeMovement({ x: 0, y: 0.5 }, 0, 4, 0.5);
  const quarter = cameraRelativeMovement({ x: 0, y: 0.25 }, 0, 4, 0.5);
  assert.ok(Math.abs(Math.hypot(half.x, half.z) - Math.hypot(full.x, full.z) * 0.5) < 1e-10);
  assert.ok(Math.abs(Math.hypot(quarter.x, quarter.z) - Math.hypot(full.x, full.z) * 0.25) < 1e-10);
});

test('esquiva usa a direção atual ou avança na direção da câmera', () => {
  assert.deepEqual(dodgeMovementInput({ x: 0.3, y: 0.4 }, true), { x: 0.3, y: 0.4 });
  assert.deepEqual(dodgeMovementInput({ x: 0, y: 0 }, true), { x: 0, y: 1 });
  assert.deepEqual(dodgeMovementInput({ x: 0, y: 0 }, false), { x: 0, y: 0 });
});

test('evento intermediário do NippleJS sem vetor zera o movimento sem lançar erro', () => {
  const stick = { x: 1, y: 1 };
  assert.doesNotThrow(() => applyNippleVector(stick));
  assert.deepEqual(stick, { x: 0, y: 0 });
  applyNippleVector(stick, { vector: { x: 0.4, y: -0.7 } });
  assert.deepEqual(stick, { x: 0.4, y: -0.7 });
  applyNippleVector(stick, { data: { vector: { x: -0.25, y: 0.8 } } });
  assert.deepEqual(stick, { x: -0.25, y: 0.8 });
});

test('joystick diagonal mantém velocidade máxima', () => {
  const movement = cameraRelativeMovement({ x: 1, y: 1 }, 0, 4, 0.5);
  assert.ok(Math.abs(Math.hypot(movement.x, movement.z) - 2) < 1e-10);
});

test('paredes do mapa viram cuboides Rapier com centro e meia extensão', () => {
  assert.deepEqual(wallToCuboid({ minX: 2, maxX: 6, minY: 0, maxY: 4, minZ: 8, maxZ: 10 }), {
    center: { x: 4, y: 2, z: 9 },
    half: { x: 2, y: 2, z: 1 },
  });
});

test('evento move do NippleJS 1.0 desloca o personagem e preserva multitouch', () => {
  let moveHandler;
  let endHandler;
  let createOptions;
  const descriptor = { setTranslation() { return this; }, setRotation() { return this; } };
  const character = {
    setSlideEnabled() {}, enableAutostep() {}, enableSnapToGround() {},
    computeColliderMovement(value, desired) { this.desired = desired; },
    computedMovement() { return this.desired; }, free() {},
  };
  const RAPIER = {
    World: class { createCollider() { return {}; } createRigidBody() { return { setNextKinematicTranslation() {} }; } createCharacterController() { return character; } step() {} free() {} },
    ColliderDesc: { cuboid: () => descriptor, capsule: () => descriptor },
    RigidBodyDesc: { kinematicPositionBased: () => descriptor },
  };
  const nipplejs = { create: (options) => { createOptions = options; return { on(name, handler) { if (name === 'move') moveHandler = handler; if (name === 'end') endHandler = handler; }, destroy() {} }; } };
  const position = { x: 10, y: 0.04, z: 10, set(x, y, z) { this.x = x; this.y = y; this.z = z; } };
  const windowRef = new EventTarget();
  const controller = createPlayerController({
    RAPIER, nipplejs, joystickZone: { clientWidth: 0 },
    world: { physicsWalls: [], physicsSurfaces: [], canOccupy: () => true, floorHeightAt: () => 0 },
    player: { speed: 4, root: { position } }, cameraHeading: () => 0, windowRef,
  });
  assert.ok(createOptions.size >= 72, 'o joystick precisa nascer com tamanho útil mesmo enquanto o HUD está oculto');
  moveHandler({ data: { vector: { x: 0, y: 1 } } });
  controller.move(0.1);
  assert.ok(position.z < 9.6 + 1e-9);
  moveHandler({ data: { vector: { x: 0.7, y: 0.4 } } });
  windowRef.dispatchEvent(new Event('pointerup'));
  assert.deepEqual(controller.movement, { x: 0.7, y: 0.4 }, 'soltar câmera/tiro não pode zerar o joystick');
  endHandler();
  assert.deepEqual(controller.movement, { x: 0, y: 0 }, 'o end do próprio joystick deve zerar o movimento');
  controller.dispose();
});

test('controlador aplica a altura interpolada e consegue subir a escada', () => {
  let moveHandler;
  let bodyTranslation = null;
  const descriptor = { setTranslation() { return this; }, setRotation() { return this; } };
  const character = {
    setSlideEnabled() {}, enableAutostep() {}, enableSnapToGround() {},
    computeColliderMovement(value, desired) { this.desired = { ...desired, y: 0 }; },
    computedMovement() { return this.desired; }, free() {},
  };
  const RAPIER = {
    World: class {
      createCollider() { return {}; }
      createRigidBody() { return { setNextKinematicTranslation(value) { bodyTranslation = value; } }; }
      createCharacterController() { return character; }
      step() {}
      free() {}
    },
    ColliderDesc: { cuboid: () => descriptor, capsule: () => descriptor },
    RigidBodyDesc: { kinematicPositionBased: () => descriptor },
  };
  const nipplejs = { create: () => ({ on(name, handler) { if (name === 'move') moveHandler = handler; }, destroy() {} }) };
  const position = { x: 2, y: 0, z: 10, set(x, y, z) { this.x = x; this.y = y; this.z = z; } };
  const controller = createPlayerController({
    RAPIER, nipplejs, joystickZone: { clientWidth: 132 },
    world: {
      physicsWalls: [], physicsSurfaces: [], canOccupy: () => true,
      floorHeightAt: ({ z }) => Math.max(0, (10 - z) * 0.5),
    },
    player: { speed: 4, root: { position } }, cameraHeading: () => 0,
    windowRef: new EventTarget(), documentRef: new EventTarget(),
  });
  moveHandler({ data: { vector: { x: 0, y: 1 } } });
  let moved = null;
  for (let step = 0; step < 5; step += 1) moved = controller.move(0.1);
  assert.ok(Math.abs(position.y - 1) < 1e-10);
  assert.ok(moved.y > 0);
  assert.ok(Math.abs(bodyTranslation.y - (position.y + 1.17)) < 1e-10);
  controller.dispose();
});

test('cápsula Rapier percorre a rampa real do primeiro ao segundo andar', async () => {
  await RAPIER.init();
  let moveHandler;
  const nipplejs = { create: () => ({ on(name, handler) { if (name === 'move') moveHandler = handler; }, destroy() {} }) };
  const layout = {
    cellSize: 4,
    levelHeight: 4.05,
    placements: [{ id: 'stairs-test', pieceId: 'stairs', x: 0, z: 0, level: 0, rotation: 0, edge: false }],
  };
  const start = { x: 2, y: floorHeightAt({ x: 2, y: 0, z: 0.6 }, layout), z: 0.6 };
  const position = { ...start, set(x, y, z) { this.x = x; this.y = y; this.z = z; } };
  const controller = createPlayerController({
    RAPIER, nipplejs, joystickZone: { clientWidth: 132 },
    world: {
      physicsWalls: buildWallColliders(layout),
      physicsSurfaces: buildFloorColliders(layout),
      canOccupy: () => true,
      floorHeightAt: (candidate) => floorHeightAt(candidate, layout),
    },
    player: { speed: 4, root: { position } }, cameraHeading: () => Math.PI,
    windowRef: new EventTarget(), documentRef: new EventTarget(),
  });
  moveHandler({ data: { vector: { x: 0, y: 1 } } });
  for (let frame = 0; frame < 160; frame += 1) controller.move(1 / 60);
  assert.ok(position.z > 7.5, `avanço insuficiente na rampa: z=${position.z}`);
  assert.ok(position.y > 3.8, `altura insuficiente na rampa: y=${position.y}`);
  controller.dispose();
});

test('personagem consegue recuar depois de encostar na saída da sala inicial', async () => {
  await RAPIER.init();
  const source = JSON.parse(readFileSync(new URL('../app/src/main/assets/game/maps/mapa (1).json', import.meta.url), 'utf8'));
  const layout = prepareBundledMap(source, 'mapa (1).json');
  let moveHandler;
  const nipplejs = { create: () => ({ on(name, handler) { if (name === 'move') moveHandler = handler; }, destroy() {} }) };
  const position = { ...layout.start, set(x, y, z) { this.x = x; this.y = y; this.z = z; } };
  const controller = createPlayerController({
    RAPIER, nipplejs, joystickZone: { clientWidth: 132 },
    world: {
      physicsWalls: buildWallColliders(layout),
      physicsSurfaces: buildFloorColliders(layout),
      canOccupy: () => true,
      floorHeightAt: (candidate) => floorHeightAt(candidate, layout),
    },
    player: { speed: 4.35, root: { position } }, cameraHeading: () => 3.108,
    windowRef: new EventTarget(), documentRef: new EventTarget(),
  });

  moveHandler({ data: { vector: { x: 0, y: 1 } } });
  for (let frame = 0; frame < 180; frame += 1) controller.move(1 / 60);
  const blockedZ = position.z;
  moveHandler({ data: { vector: { x: 0, y: -1 } } });
  for (let frame = 0; frame < 60; frame += 1) controller.move(1 / 60);
  assert.ok(position.z < blockedZ - 1, `personagem não conseguiu recuar: ${blockedZ} -> ${position.z}; ${JSON.stringify(controller.physicsState)}`);
  controller.dispose();
});
