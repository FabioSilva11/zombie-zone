import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const main = readFileSync(new URL('../app/src/main/assets/game/js/main.js', import.meta.url), 'utf8');

test('loop TPS consome controller yaw antes do movimento e segue o personagem depois', () => {
  const input = main.indexOf('cameraController.updateInput(delta)');
  const move = main.indexOf('playerController.move(delta)');
  const follow = main.indexOf('cameraController.updateFollow(delta)', move);
  assert.ok(input >= 0 && move > input, 'yaw/pitch precisam ser atualizados antes de MoveForward/MoveRight');
  assert.ok(follow > move, 'SpringArm precisa seguir a posição já resolvida pelo movimento');
});

test('disparo não cria um estado temporário que disputa o facing da mira', () => {
  const fireStart = main.indexOf('const fire = (event) =>');
  const fireEnd = main.indexOf('const reload =', fireStart);
  const fireBlock = main.slice(fireStart, fireEnd);
  assert.doesNotMatch(fireBlock, /player\.root\.rotation\.y\s*=/);
  assert.doesNotMatch(fireBlock, /aimFacingRemaining/);
});

test('corpo segue sempre o ControllerYaw e não a direção do deslocamento', () => {
  assert.match(main, /controllerFacingFromYaw\(cameraController\.heading\(\)\)/);
  assert.match(main, /player\.root\.quaternion\.copy\(targetPlayerQuaternion\)/);
  assert.doesNotMatch(main, /modelYawFromDirection\(moved\.x, moved\.z/);
});

test('câmera principal não depende mais de CameraControls', () => {
  assert.doesNotMatch(main, /import CameraControls from 'camera-controls'/);
  assert.match(main, /wallColliders: world\.cameraWallColliders/);
});
