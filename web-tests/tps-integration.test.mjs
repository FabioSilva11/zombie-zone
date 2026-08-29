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

test('rifle híbrido automático usa hitscan da câmera e substitui o disparo assistido antigo', () => {
  assert.match(main, /createHybridAim\(/);
  assert.match(main, /createAutomaticFire\(/);
  assert.match(main, /resolveRifleHitscan\(/);
  assert.match(main, /attachRifle\(rifle, sockets\[nextRifleMode\], nextRifleMode\)/);
  assert.match(main, /automaticFire\.update\(delta/);
  assert.match(main, /hybridAim\.readyToFire/);
  assert.match(main, /input\.dispose\(\)/);
  assert.doesNotMatch(main, /combat\.fire\(selectedEnemy\)/);
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

test('frame atualiza mundo, blend e câmera antes do hitscan automático', () => {
  const worldUpdate = main.indexOf('world.update(delta, elapsed)');
  const aimUpdate = main.indexOf('hybridAim.update(delta)', worldUpdate);
  const viewBlend = main.indexOf('cameraController.setViewBlend(hybridAim.blend)', aimUpdate);
  const follow = main.indexOf('cameraController.updateFollow(delta)', viewBlend);
  const automatic = main.indexOf('automaticFire.update(delta', follow);
  const render = main.indexOf('renderer.render(world.scene, camera)', automatic);

  assert.ok(worldUpdate >= 0 && aimUpdate > worldUpdate);
  assert.ok(viewBlend > aimUpdate && follow > viewBlend);
  assert.ok(automatic > follow && render > automatic);
});

test('missão mantém uma câmera e um rifle e restaura o herói em todo cancelamento', () => {
  assert.equal(main.match(/new THREE\.PerspectiveCamera\(/g)?.length, 1);
  assert.equal(main.match(/createRifleModel\(THREE\)/g)?.length, 1);
  assert.match(main, /hybridAim\.blend >= 0\.92/);
  assert.match(main, /onRelease:[\s\S]*?player\.root\.visible = true;/);
  assert.match(main, /setPaused\(value\)[\s\S]*?input\.cancel\(\);[\s\S]*?automaticFire\.cancel\(\);[\s\S]*?hybridAim\.cancel\(\);[\s\S]*?player\.root\.visible = true;/);
  assert.match(main, /input\.dispose\(\); automaticFire\.dispose\(\); weaponAudio\.dispose\(\); effects\.dispose\(\); rifle\.dispose\(\);/);
  assert.ok(main.match(/cancelRifleTrigger\(\);/g)?.length >= 2, 'vitória e derrota precisam cancelar o gatilho');
});

test('recarga e som global têm wiring único e imediato', () => {
  assert.match(main, /if \(!wasReloading && weapon\.reloading\) weaponAudio\.playReload\(\);/);
  assert.match(main, /if \(weapon\.startReload\(\)\) weaponAudio\.playReload\(\);/);
  assert.match(main, /activeGame\?\.setSoundEnabled\(event\.target\.checked\)/);
  assert.match(main, /setSoundEnabled\(value\) \{ weaponAudio\.setEnabled\(value\); \}/);
});
