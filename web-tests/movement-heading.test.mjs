import test from 'node:test';
import assert from 'node:assert/strict';
import {
  combatYawFromDirection,
  controllerFacingFromYaw,
  frameIndependentSmoothing,
  modelYawFromDirection,
  resolveFreeLookFacing,
} from '../app/src/main/assets/game/js/movement-heading.js';

const EPSILON = 1e-10;
const directions = [
  ['norte', 0, -1, 0, Math.PI],
  ['nordeste', 1, -1, Math.PI / 4, Math.PI * 3 / 4],
  ['leste', 1, 0, Math.PI / 2, Math.PI / 2],
  ['sudeste', 1, 1, Math.PI * 3 / 4, Math.PI / 4],
  ['sul', 0, 1, Math.PI, 0],
  ['sudoeste', -1, 1, -Math.PI * 3 / 4, -Math.PI / 4],
  ['oeste', -1, 0, -Math.PI / 2, -Math.PI / 2],
  ['noroeste', -1, -1, -Math.PI / 4, -Math.PI * 3 / 4],
];

for (const [name, x, z, combatYaw, modelYaw] of directions) {
  test(`orientação consistente para ${name}`, () => {
    assert.ok(Math.abs(combatYawFromDirection(x, z) - combatYaw) < EPSILON);
    assert.ok(Math.abs(modelYawFromDirection(x, z) - modelYaw) < EPSILON);
  });
}

test('direção nula não inventa um novo ângulo', () => {
  assert.equal(combatYawFromDirection(0, 0, 1.25), 1.25);
  assert.equal(modelYawFromDirection(0, 0, -0.75), -0.75);
});

test('ControllerYaw mantém o corpo apontado para a mira', () => {
  const north = controllerFacingFromYaw(0);
  assert.ok(Math.abs(north.x) < EPSILON);
  assert.ok(Math.abs(north.z + 1) < EPSILON);
  assert.ok(Math.abs(north.combatYaw) < EPSILON);
  assert.ok(Math.abs(north.modelYaw - Math.PI) < EPSILON);

  const east = controllerFacingFromYaw(Math.PI / 2);
  assert.ok(Math.abs(east.x - 1) < EPSILON);
  assert.ok(Math.abs(east.z) < EPSILON);
  assert.ok(Math.abs(east.combatYaw - Math.PI / 2) < EPSILON);
  assert.ok(Math.abs(east.modelYaw - Math.PI / 2) < EPSILON);
});

test('rotação suave do herói usa delta e não muda com a taxa de quadros', () => {
  const oneFrame = frameIndependentSmoothing(12, 1 / 60);
  const twoHalfFrames = 1 - ((1 - frameIndependentSmoothing(12, 1 / 120)) ** 2);
  assert.ok(Math.abs(oneFrame - twoHalfFrames) < EPSILON);
  assert.equal(frameIndependentSmoothing(12, 0), 0);
});

test('câmera livre não gira o personagem parado', () => {
  const facing = resolveFreeLookFacing({
    movement: { x: 0, z: 0 }, cameraYaw: 1.8,
    currentCombatYaw: 0.4, currentModelYaw: -0.7,
  });
  assert.equal(facing.source, 'locked');
  assert.equal(facing.combatYaw, 0.4);
  assert.equal(facing.modelYaw, -0.7);
});

test('movimento sempre vence a mira e personagem parado em combate segue a câmera', () => {
  const moving = resolveFreeLookFacing({ movement: { x: 1, z: 0 }, cameraYaw: 0 });
  assert.equal(moving.source, 'movement');
  assert.ok(Math.abs(moving.modelYaw - Math.PI / 2) < EPSILON);

  const movingWhileAiming = resolveFreeLookFacing({ movement: { x: 0, z: 1 }, cameraYaw: 0, combatActive: true });
  assert.equal(movingWhileAiming.source, 'movement');
  assert.ok(Math.abs(movingWhileAiming.combatYaw - Math.PI) < EPSILON);

  const aiming = resolveFreeLookFacing({ movement: { x: 0, z: 0 }, cameraYaw: Math.PI, combatActive: true });
  assert.equal(aiming.source, 'camera');
  assert.ok(Math.abs(Math.abs(aiming.combatYaw) - Math.PI) < EPSILON);
});
