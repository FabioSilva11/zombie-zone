import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const main = readFileSync(new URL('../app/src/main/assets/game/js/main.js', import.meta.url), 'utf8');
const rifleInput = readFileSync(new URL('../app/src/main/assets/game/js/rifle-input.js', import.meta.url), 'utf8');

test('loop TPS consome controller yaw antes do movimento e segue o personagem depois', () => {
  const input = main.indexOf('cameraController.updateInput(delta)');
  const move = main.indexOf('playerController.move(delta)');
  const follow = main.indexOf('cameraController.updateFollow(delta)', move);
  assert.ok(input >= 0 && move > input, 'yaw/pitch precisam ser atualizados antes de MoveForward/MoveRight');
  assert.ok(follow > move, 'SpringArm precisa seguir a posição já resolvida pelo movimento');
});

test('rifle automático usa hitscan da câmera e permanece preso ao personagem TPS', () => {
  assert.match(main, /createHybridAim\(/);
  assert.match(main, /createAutomaticFire\(/);
  assert.match(main, /resolveRifleHitscan\(/);
  assert.match(main, /attachRifle\(rifle, sockets\.tps, 'tps'\)/);
  assert.doesNotMatch(main, /sockets\.fps|nextRifleMode/);
  assert.match(main, /automaticFire\.update\(delta/);
  assert.match(main, /hybridAim\.readyToFire/);
  assert.match(main, /input\.dispose\(\)/);
  assert.doesNotMatch(main, /combat\.fire\(selectedEnemy\)/);
});

test('free-look mantém o corpo parado, acompanha movimento e alinha somente no combate', () => {
  assert.match(main, /resolveFreeLookFacing\(\{/);
  assert.match(main, /movement:\s*movementPhysics\.desired/);
  assert.match(main, /combatActive:\s*rifleState\.aimHeld \|\| rifleState\.fireHeld/);
  assert.match(main, /player\.root\.quaternion\.slerp\(targetPlayerQuaternion/);
  assert.doesNotMatch(main, /player\.root\.quaternion\.copy\(targetPlayerQuaternion\)/);
});

test('câmera principal não depende mais de CameraControls', () => {
  assert.doesNotMatch(main, /import CameraControls from 'camera-controls'/);
  assert.match(main, /wallColliders: world\.cameraWallColliders/);
  assert.match(main, /camera-controller\.js\?v=free-look-1/);
  assert.match(main, /hybrid-aim\.js\?v=mobile-hud-3/);
  assert.match(main, /rifle-input\.js\?v=mobile-hud-3/);
});

test('rifle aparece ao mirar ou atirar e usa as animações holding-right', () => {
  assert.match(main, /resolveRiflePresentation\(rifleIntent\.state\(\)\)/);
  assert.match(main, /rifle\.root\.visible = riflePresentation\.visible/);
  assert.match(main, /player\.animations\.play\(riflePresentation\.animation/);
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

test('missão mantém uma câmera, um rifle e o herói visível durante toda a mira', () => {
  assert.equal(main.match(/new THREE\.PerspectiveCamera\(/g)?.length, 1);
  assert.equal(main.match(/createRifleModel\(THREE\)/g)?.length, 1);
  assert.match(main, /player\.root\.visible = true/);
  assert.doesNotMatch(main, /player\.root\.visible = !/);
  assert.match(main, /const aimInput = bindRifleInput\(/);
  assert.match(main, /button: aimButton/);
  assert.match(main, /createRifleIntent\(/);
  assert.match(rifleInput, /aimHeld \|\| fireHeld/);
  assert.match(main, /setPaused\(value\)[\s\S]*?cancelRifleTrigger\(\);/);
  assert.match(main, /input\.dispose\(\); aimInput\.dispose\(\); rifleIntent\.dispose\(\);/);
  assert.ok(main.match(/cancelRifleTrigger\(\);/g)?.length >= 2, 'vitória e derrota precisam cancelar o gatilho');
});

test('recarga e som global têm wiring único e imediato', () => {
  assert.match(main, /if \(!wasReloading && weapon\.reloading\) weaponAudio\.playReload\(\);/);
  assert.match(main, /if \(weapon\.startReload\(\)\) weaponAudio\.playReload\(\);/);
  assert.match(main, /activeGame\?\.setSoundEnabled\(event\.target\.checked\)/);
  assert.match(main, /setSoundEnabled\(value\) \{ weaponAudio\.setEnabled\(value\); \}/);
});
