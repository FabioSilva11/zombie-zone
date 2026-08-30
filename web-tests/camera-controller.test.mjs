import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import * as cameraModule from '../app/src/main/assets/game/js/camera-controller.js';

const {
  applyLookDelta,
  configureThirdPersonControls,
  createThirdPersonCamera,
  resolveSpringArmCamera,
} = cameraModule;

function pointerEvent(type, pointerId, clientX, clientY) {
  const event = new Event(type, { cancelable: true });
  Object.defineProperties(event, {
    pointerId: { value: pointerId },
    clientX: { value: clientX },
    clientY: { value: clientY },
  });
  return event;
}

test('compatibilidade CameraControls mantém colisores e sensibilidade configurados', () => {
  const ACTION = { NONE: 0, ROTATE: 3, TOUCH_ROTATE: 9 };
  const controls = { mouseButtons: {}, touches: {} };
  const colliders = [{ name: 'wall' }];
  configureThirdPersonControls(controls, { ACTION }, colliders, 1.25);
  assert.equal(controls.mouseButtons.left, ACTION.ROTATE);
  assert.equal(controls.touches.one, ACTION.TOUCH_ROTATE);
  assert.equal(controls.touches.two, ACTION.NONE);
  assert.equal(controls.colliderMeshes, colliders);
  assert.equal(controls.azimuthRotateSpeed, 1.25);
  assert.ok(controls.minPolarAngle < controls.maxPolarAngle);
});

test('PlayerController de câmera aplica yaw/pitch diretamente e limita o pitch', () => {
  const first = applyLookDelta({ yaw: 0, pitch: 0 }, 100, -50, 1);
  assert.ok(first.yaw > 0, 'arrastar para a direita deve virar a câmera para a direita');
  assert.ok(first.pitch < 0, 'arrastar para cima deve olhar para cima');
  const extremeDown = applyLookDelta(first, 0, 100000, 1);
  const extremeUp = applyLookDelta(first, 0, -100000, 1);
  assert.ok(extremeDown.pitch <= Math.PI * 55 / 180 + 1e-12);
  assert.ok(extremeUp.pitch >= -Math.PI / 6 - 1e-12);
});

test('câmera renderizada é a única câmera e cada ponteiro mantém ownership próprio', () => {
  const domElement = new EventTarget();
  domElement.setPointerCapture = () => {};
  domElement.releasePointerCapture = () => {};
  const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 100);
  const player = { root: { position: new THREE.Vector3(10, 0, 20) } };
  const controller = createThirdPersonCamera({
    THREE,
    camera,
    domElement,
    player,
    wallColliders: [],
    sensitivity: 1,
    windowRef: new EventTarget(),
  });

  const initial = controller.heading();
  domElement.dispatchEvent(pointerEvent('pointerdown', 11, 100, 100));
  domElement.dispatchEvent(pointerEvent('pointermove', 22, 180, 100));
  assert.equal(controller.heading(), initial, 'outro dedo não pode controlar a câmera');
  domElement.dispatchEvent(pointerEvent('pointermove', 11, 150, 100));
  const afterOwnedMove = controller.heading();
  assert.ok(afterOwnedMove > initial);

  domElement.dispatchEvent(pointerEvent('pointerup', 22, 180, 100));
  domElement.dispatchEvent(pointerEvent('pointermove', 11, 180, 100));
  assert.ok(controller.heading() > afterOwnedMove, 'soltar outro dedo não deve cancelar a câmera');
  const beforeRelease = controller.heading();
  domElement.dispatchEvent(pointerEvent('pointerup', 11, 180, 100));
  domElement.dispatchEvent(pointerEvent('pointermove', 11, 240, 100));
  assert.equal(controller.heading(), beforeRelease);

  const source = createThirdPersonCamera.toString();
  assert.doesNotMatch(source, /camera\.clone\(\)/, 'não deve existir uma segunda câmera lógica');
  controller.dispose();
});

test('controle aceita deltas externos para o gesto de atirar e arrastar', () => {
  const domElement = new EventTarget();
  const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 100);
  const player = { root: { position: new THREE.Vector3(10, 0, 20) } };
  const controller = createThirdPersonCamera({
    THREE, camera, domElement, player, wallColliders: [], windowRef: new EventTarget(),
  });

  const yawBefore = controller.heading();
  const pitchBefore = controller.pitch();
  assert.equal(controller.rotateByPixels(30, -12), true);
  assert.notEqual(controller.heading(), yawBefore);
  assert.notEqual(controller.pitch(), pitchBefore);

  controller.setEnabled(false);
  const disabledYaw = controller.heading();
  assert.equal(controller.rotateByPixels(30, 0), false);
  assert.equal(controller.heading(), disabledYaw);
  controller.dispose();
});

test('follow acompanha a translação do personagem sem atraso espacial', () => {
  const domElement = new EventTarget();
  const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 100);
  const player = { root: { position: new THREE.Vector3(4, 0, 8) } };
  const controller = createThirdPersonCamera({
    THREE, camera, domElement, player, wallColliders: [], windowRef: new EventTarget(),
  });
  const before = camera.position.clone().sub(player.root.position);
  player.root.position.add(new THREE.Vector3(3, 0, -2));
  controller.updateFollow(1 / 60);
  const after = camera.position.clone().sub(player.root.position);
  assert.ok(before.distanceTo(after) < 1e-10, `offset mudou ${before.distanceTo(after)}`);
  controller.dispose();
});

test('agachamento abaixa a câmera suavemente sem alterar yaw ou pitch', () => {
  const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 100);
  const player = { root: { position: new THREE.Vector3(4, 0, 8) } };
  const controller = createThirdPersonCamera({
    THREE, camera, domElement: new EventTarget(), player, wallColliders: [], windowRef: new EventTarget(),
  });
  const standingY = camera.position.y;
  const heading = controller.heading();
  const pitch = controller.pitch();
  controller.setCrouched(true);
  for (let frame = 0; frame < 30; frame += 1) controller.updateFollow(1 / 60);
  assert.ok(camera.position.y < standingY - 0.35, `câmera agachada não abaixou o suficiente: ${standingY} -> ${camera.position.y}`);
  assert.equal(controller.heading(), heading);
  assert.equal(controller.pitch(), pitch);
  controller.dispose();
});

test('mira aproxima a câmera sobre o ombro sem entrar em primeira pessoa', () => {
  const domElement = new EventTarget();
  const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 100);
  const player = { root: { position: new THREE.Vector3(4, 0, 8) } };
  const controller = createThirdPersonCamera({
    THREE, camera, domElement, player, wallColliders: [], windowRef: new EventTarget(),
  });
  const heading = controller.heading();
  const pitch = controller.pitch();

  controller.setViewBlend(1);
  controller.updateFollow(0);

  const playerDistance = camera.position.distanceTo(player.root.position);
  assert.ok(playerDistance > 2.5 && playerDistance < 4.5, `TPS mirado deve ficar próximo sem entrar no corpo: ${playerDistance}`);
  assert.ok(camera.position.x > player.root.position.x, 'a câmera mirada permanece deslocada sobre o ombro direito');
  assert.ok(camera.position.z > player.root.position.z, 'a câmera mirada permanece atrás do personagem');
  assert.equal(controller.heading(), heading);
  assert.equal(controller.pitch(), pitch);
  assert.equal(controller.viewBlend(), 1);
  controller.dispose();
});

test('blend parcial preserva a direção de mira para yaw e pitch não nulos', () => {
  const domElement = new EventTarget();
  domElement.setPointerCapture = () => {};
  domElement.releasePointerCapture = () => {};
  const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 100);
  const player = { root: { position: new THREE.Vector3(4, 0, 8) } };
  const controller = createThirdPersonCamera({
    THREE, camera, domElement, player, wallColliders: [], windowRef: new EventTarget(),
  });
  domElement.dispatchEvent(pointerEvent('pointerdown', 7, 100, 100));
  domElement.dispatchEvent(pointerEvent('pointermove', 7, 200, 150));
  domElement.dispatchEvent(pointerEvent('pointerup', 7, 200, 150));
  controller.setViewBlend(0.5);
  controller.updateFollow(0);

  const yaw = controller.heading();
  const pitch = controller.pitch();
  const expected = new THREE.Vector3(
    Math.sin(yaw) * Math.cos(pitch),
    -Math.sin(pitch),
    -Math.cos(yaw) * Math.cos(pitch),
  );
  const aimDirection = new THREE.Vector3().subVectors(controller.aimPoint(), camera.position).normalize();
  const renderedDirection = camera.getWorldDirection(new THREE.Vector3());
  assert.ok(aimDirection.distanceTo(expected) < 1e-10, `mira desviou: ${aimDirection.toArray()}`);
  assert.ok(renderedDirection.distanceTo(expected) < 1e-10, `câmera desviou: ${renderedDirection.toArray()}`);
  assert.notEqual(yaw, 0);
  assert.notEqual(pitch, Math.PI / 18);
  controller.dispose();
});

test('SpringArm resolve a posição TPS antes do blend e a transição não cruza a parede', () => {
  const domElement = new EventTarget();
  const camera = new THREE.PerspectiveCamera(60, 1, 0.1, 100);
  const player = { root: { position: new THREE.Vector3(0, 0, 0) } };
  const wall = { minX: -2, maxX: 2, minY: 0, maxY: 5, minZ: 3, maxZ: 3.4 };
  const controller = createThirdPersonCamera({
    THREE, camera, domElement, player, wallColliders: [wall], windowRef: new EventTarget(),
  });

  controller.setViewBlend(0);
  const resolved = controller.updateFollow(0);
  assert.equal(resolved.collided, true);
  assert.ok(camera.position.distanceTo(new THREE.Vector3(resolved.position.x, resolved.position.y, resolved.position.z)) < 1e-10);
  assert.ok(camera.position.z < wall.minZ, `TPS atravessou a parede: z=${camera.position.z}`);

  controller.setViewBlend(0.5);
  controller.updateFollow(0);
  assert.ok(camera.position.z < wall.minZ, `transição atravessou a parede: z=${camera.position.z}`);
  assert.equal(controller.collision().collided, true);
  controller.dispose();
});

test('SpringArm retrai antes da parede e não atravessa o colisor', () => {
  const wall = { minX: -2, maxX: 2, minY: 0, maxY: 5, minZ: 3, maxZ: 3.4 };
  const result = resolveSpringArmCamera({
    target: { x: 0, y: 1.6, z: 0 },
    desired: { x: 0, y: 2.8, z: 6.2 },
    wallColliders: [wall],
    currentDistance: 6.3,
    delta: 1 / 60,
  });
  assert.equal(result.collided, true);
  assert.ok(result.position.z < wall.minZ, `câmera atravessou a parede: z=${result.position.z}`);
  assert.ok(result.distance < result.directDistance);
});

test('composição de tiro deixa a mira à frente e o herói abaixo à esquerda', () => {
  assert.equal(typeof cameraModule.thirdPersonComposition, 'function');
  const frame = cameraModule.thirdPersonComposition({ x: 10, y: 0, z: 20 }, 0, Math.PI / 18);
  assert.ok(frame.camera.x > 10, 'a câmera no ombro direito coloca o herói à esquerda da tela');
  assert.ok(frame.camera.z > 20, 'a câmera precisa ficar atrás do herói');
  assert.ok(frame.aim.z < 20, 'a mira central precisa apontar à frente do herói');

  const depthRatio = (20 - frame.camera.z) / (frame.aim.z - frame.camera.z);
  const reticleYAtPlayer = frame.camera.y + (frame.aim.y - frame.camera.y) * depthRatio;
  assert.ok(reticleYAtPlayer > 2, 'no plano do herói, a mira deve ficar acima da cabeça e não no corpo');
});

test('tamanho aparente do herói segue a proporção das referências', () => {
  assert.equal(typeof cameraModule.estimatedCharacterScreenFraction, 'function');
  const fraction = cameraModule.estimatedCharacterScreenFraction(2.6);
  assert.ok(fraction >= 0.34 && fraction <= 0.42, `o herói deve ocupar 34% a 42% da tela, recebeu ${fraction}`);
});
