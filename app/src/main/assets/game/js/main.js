import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { AnimationController } from './animation.js';
import { characterById, CHARACTERS } from './catalog.js';
import { CombatSystem } from './combat.js';
import { clampToArena } from './game-rules.js';
import { TouchControls } from './input.js';
import { mountSelection } from './selection.js';
import { createWorld } from './world.js';

function assertWebGL() {
  const canvas = document.createElement('canvas');
  if (!canvas.getContext('webgl2') && !canvas.getContext('webgl')) {
    throw new Error('Este aparelho não oferece suporte a WebGL.');
  }
}

function loadCharacter(loader, id, position) {
  const definition = characterById(id);
  return loader.loadAsync(definition.model).then((gltf) => {
    const root = gltf.scene;
    root.position.set(position.x, 0.04, position.z);
    root.scale.setScalar(1.08);
    root.traverse((child) => {
      if (!child.isMesh) return;
      child.castShadow = true;
      child.receiveShadow = true;
    });
    const mixer = new THREE.AnimationMixer(root);
    const animations = new AnimationController(mixer, gltf.animations);
    animations.play(['idle', 'static']);
    return { definition, root, animations, mixer };
  });
}

function configureRenderer(root) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFShadowMap;
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.16;
  root.replaceChildren(renderer.domElement);
  return renderer;
}

export async function startApp(loading) {
  assertWebGL();
  loading.update('Abrindo o portal', 35);

  const selectionScreen = document.querySelector('#selection-screen');
  const hud = document.querySelector('#game-hud');
  const completion = document.querySelector('#completion-screen');
  let activeGame = null;

  mountSelection(selectionScreen, CHARACTERS, async (characterId) => {
    selectionScreen.classList.add('is-hidden');
    loading.show();
    loading.update('Erguendo as ruínas', 12);
    try {
      activeGame?.dispose();
      activeGame = await createGame(characterId, loading, completion);
      loading.hide();
      hud.classList.remove('is-hidden');
    } catch (error) {
      console.error(error);
      hud.classList.add('is-hidden');
      loading.showError('Não foi possível abrir a masmorra. Toque para tentar novamente.');
    }
  });

  document.querySelector('#play-again-button').addEventListener('click', () => window.location.reload());
  loading.update('Escolha seu viajante', 100);
  window.setTimeout(() => {
    loading.hide();
    selectionScreen.classList.remove('is-hidden');
  }, 320);
}

async function createGame(characterId, loading, completion) {
  const root = document.querySelector('#game-root');
  const renderer = configureRenderer(root);
  const camera = new THREE.PerspectiveCamera(58, window.innerWidth / window.innerHeight, 0.08, 120);
  const loader = new GLTFLoader();
  let loadedWorldPieces = 0;
  const world = await createWorld(loader, (label) => {
    loadedWorldPieces += 1;
    loading.update(label, 18 + loadedWorldPieces * 9);
  });

  loading.update('Invocando seu viajante', 61);
  const playerData = await loadCharacter(loader, characterId, { x: 0, z: 11 });
  world.scene.add(playerData.root);
  const player = {
    ...playerData,
    health: 100,
    yaw: 0,
    speed: 4.35,
  };
  player.root.rotation.y = Math.PI;

  loading.update('Despertando os guardiões', 75);
  const enemyData = await Promise.all([
    loadCharacter(loader, 'l', { x: -3.2, z: 3.5 }),
    loadCharacter(loader, 'o', { x: 3.4, z: -3.5 }),
  ]);
  const enemies = enemyData.map((data, index) => ({
    ...data,
    health: 70,
    yaw: Math.PI,
    speed: 0.28 + index * 0.04,
    attackCooldown: 0.5 + index * 0.3,
  }));
  for (const enemy of enemies) world.scene.add(enemy.root);

  const healthFill = document.querySelector('[data-health-fill]');
  const objective = document.querySelector('[data-objective]');
  document.querySelector('[data-player-name]').textContent = player.definition.name;
  healthFill.style.width = '100%';
  objective.textContent = 'Derrote os 2 guardiões';

  let defeated = false;
  const combat = new CombatSystem({
    player,
    enemies,
    onHudChange: ({ playerHealth, enemiesRemaining, gateOpen }) => {
      healthFill.style.width = `${playerHealth}%`;
      objective.textContent = gateOpen
        ? 'Atravesse o portal aberto'
        : `Derrote os guardiões • ${enemiesRemaining} restantes`;
    },
    onGateOpen: () => world.openGate(),
    onPlayerDefeated: () => {
      if (defeated) return;
      defeated = true;
      objective.textContent = 'Os guardiões venceram • renascendo…';
      window.setTimeout(() => {
        player.health = 100;
        player.root.position.set(0, 0.04, 11);
        player.animations.play(['idle', 'static']);
        defeated = false;
        healthFill.style.width = '100%';
      }, 1500);
    },
  });

  const controls = new TouchControls({
    joystick: document.querySelector('#joystick'),
    cameraZone: document.querySelector('#camera-zone'),
    attackButton: document.querySelector('#attack-button'),
    onAttack: () => combat.attack(),
  });

  let cameraYaw = 0;
  let cameraPitch = 0.42;
  let disposed = false;
  let completed = false;
  let lastFrameTime = performance.now() * 0.001;
  let elapsed = 0;
  const desiredCamera = new THREE.Vector3();
  const lookTarget = new THREE.Vector3();

  const resize = () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
  };
  window.addEventListener('resize', resize);

  const animate = () => {
    if (disposed) return;
    const now = performance.now() * 0.001;
    const delta = Math.min(Math.max(0, now - lastFrameTime), 0.05);
    lastFrameTime = now;
    elapsed += delta;
    const look = controls.consumeLook();
    cameraYaw -= look.x * 0.0052;
    cameraPitch = Math.min(0.68, Math.max(0.2, cameraPitch + look.y * 0.0025));

    const input = controls.movement;
    const inputStrength = Math.min(1, Math.hypot(input.x, input.y));
    if (!defeated && inputStrength > 0.04) {
      const forwardAmount = -input.y;
      const directionX = Math.cos(cameraYaw) * input.x + Math.sin(cameraYaw) * forwardAmount;
      const directionZ = Math.sin(cameraYaw) * input.x - Math.cos(cameraYaw) * forwardAmount;
      const length = Math.hypot(directionX, directionZ) || 1;
      const next = clampToArena({
        x: player.root.position.x + (directionX / length) * player.speed * inputStrength * delta,
        z: player.root.position.z + (directionZ / length) * player.speed * inputStrength * delta,
      });
      player.root.position.x = next.x;
      player.root.position.z = next.z;
      player.yaw = Math.atan2(directionX, -directionZ);
      player.root.rotation.y = player.yaw + Math.PI;
      if (combat.playerAttackCooldown <= 0.25) player.animations.play(['walk', 'sprint', 'idle']);
    } else if (!defeated && combat.playerAttackCooldown <= 0.25) {
      player.animations.play(['idle', 'static']);
    }

    combat.update(delta);
    player.animations.update(delta);
    for (const enemy of enemies) enemy.animations.update(delta);
    world.update(delta, elapsed);

    const distance = 8.8;
    const horizontal = Math.cos(cameraPitch) * distance;
    desiredCamera.set(
      player.root.position.x - Math.sin(cameraYaw) * horizontal,
      player.root.position.y + 1.1 + Math.sin(cameraPitch) * distance,
      player.root.position.z + Math.cos(cameraYaw) * horizontal,
    );
    camera.position.lerp(desiredCamera, 1 - Math.exp(-delta * 7));
    lookTarget.set(player.root.position.x, player.root.position.y + 1.1, player.root.position.z);
    camera.lookAt(lookTarget);

    if (!completed && world.isAtExit(player.root.position)) {
      completed = true;
      completion.classList.remove('is-hidden');
      document.querySelector('#game-hud').classList.add('is-hidden');
    }
    renderer.render(world.scene, camera);
  };

  loading.update('A aventura começou', 100);
  renderer.setAnimationLoop(animate);
  return {
    dispose() {
      disposed = true;
      controls.dispose();
      window.removeEventListener('resize', resize);
      renderer.setAnimationLoop(null);
      renderer.dispose();
      root.replaceChildren();
    },
  };
}
