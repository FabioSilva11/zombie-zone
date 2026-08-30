import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import RAPIER from '@dimforge/rapier3d-compat';
import nipplejs from 'nipplejs';
import { AnimationController } from './animation.js';
import { selectAssistedTarget } from './aim-assist.js';
import { createAutomaticFire } from './automatic-fire.js?v=rifle-1';
import { createAvatarPreview } from './avatar-preview.js?v=zombie-zone-16';
import { createThirdPersonCamera } from './camera-controller.js?v=mobile-hud-3';
import { CAMPAIGN } from './campaign.js?v=zombie-zone-16';
import { mountCampaignUI } from './campaign-ui.js?v=zombie-zone-16';
import { characterById, CHARACTERS } from './catalog.js';
import { CombatSystem } from './combat.js?v=zombie-ai-1';
import { createHybridAim } from './hybrid-aim.js?v=mobile-hud-3';
import { createExplorationState, mountMinimap, revealAround } from './minimap.js?v=campaign-3';
import { completeMission, routeBootFailure, shouldDisposeMission } from './mission-lifecycle.js?v=campaign-3';
import { controllerFacingFromYaw, modelYawFromDirection } from './movement-heading.js?v=zombie-zone-18';
import { createPlayerController } from './player-controller.js?v=tps-unreal-3';
import { createRifleEffects } from './rifle-effects.js?v=rifle-1';
import { resolveRifleHitscan } from './rifle-hitscan.js?v=rifle-1';
import { bindRifleInput, createRifleIntent } from './rifle-input.js?v=mobile-hud-3';
import { attachRifle, createRifleModel, createRifleSockets } from './rifle-model.js?v=rifle-aim-1';
import { createSaveStore } from './save-store.js';
import { createScreenFlow } from './screen-flow.js';
import { mountSelection } from './selection.js?v=zombie-zone-13';
import { createWeaponAudio } from './weapon-audio.js?v=rifle-1';
import { createWorld } from './world.js?v=zombie-zone-7';
import { createWeaponState } from './weapon-state.js?v=rifle-1';
import { createMusicController, MUSIC_MODES } from './music-controller.js';

const CHARACTER_WORLD_SCALE = 0.72;

function assertWebGL() {
  const canvas = document.createElement('canvas');
  if (!canvas.getContext('webgl2') && !canvas.getContext('webgl')) throw new Error('WebGL indisponível neste aparelho.');
}

function loadCharacter(loader, id, position) {
  const definition = characterById(id);
  return loader.loadAsync(definition.model).then((gltf) => {
    const root = gltf.scene;
    root.position.set(position.x, (position.y ?? 0) + 0.04, position.z);
    root.scale.setScalar(CHARACTER_WORLD_SCALE);
    root.traverse((child) => { if (child.isMesh) { child.castShadow = true; child.receiveShadow = true; } });
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
  renderer.toneMapping = THREE.NoToneMapping;
  renderer.toneMappingExposure = 1;
  root.replaceChildren(renderer.domElement);
  return renderer;
}

export async function startApp(loading, diagnostics = null) {
  const store = createSaveStore(window.localStorage);
  let progress = store.load();
  const flow = createScreenFlow(progress);
  const selectionScreen = document.querySelector('#selection-screen');
  const hud = document.querySelector('#game-hud');
  const music = createMusicController({
    menuUrl: './audio/musica-menu-tps-ambient.mp3',
    gameplayUrl: './audio/trilha-tps-dark-loop.mp3',
    enabled: progress.settings.soundEnabled,
  });
  const detachMusicActivation = music.bindUserActivation(document);
  const syncMusicForState = async (state) => {
    const mode = state.screen === 'gameplay' ? MUSIC_MODES.GAMEPLAY : MUSIC_MODES.MENU;
    const playing = await music.setMode(mode);
    document.documentElement.dataset.musicMode = mode;
    document.documentElement.dataset.musicPlaying = String(playing);
  };
  let activeGame = null;
  let ui = null;
  let avatarPreview = null;

  const syncAvatarPreview = (state = flow.state, characterId = progress.selectedAvatar ?? 'a') => {
    if (!avatarPreview) return;
    if (['home', 'avatar', 'preparation', 'victory'].includes(state.screen)) {
      avatarPreview.show(state.screen, characterId).catch((error) => console.error('Falha ao mostrar avatar 3D.', error));
    } else {
      avatarPreview.hide();
    }
  };

  const setProgress = (next) => { progress = store.save(next); ui?.render(); return progress; };
  const showAvatar = () => { flow.go('avatar'); selectionScreen.classList.remove('is-hidden'); hud.classList.add('is-hidden'); };

  const finishVictory = (stage, result) => {
    const completed = completeMission(progress, stage.id, result);
    setProgress(completed.progress);
    activeGame?.dispose(); activeGame = null; hud.classList.add('is-hidden');
    flow.go('story', { stageId: stage.id, storyPosition: 'after', resultSummary: completed.award });
  };
  const finishDefeat = (stage, reason) => {
    activeGame?.dispose(); activeGame = null; hud.classList.add('is-hidden');
    flow.go('defeat', { stageId: stage.id, reason });
  };

  const startMission = async (stage) => {
    activeGame?.dispose(); activeGame = null;
    flow.go('gameplay', { stageId: stage.id });
    loading.show(); loading.update('Abrindo a missão', 8); hud.classList.add('is-hidden');
    try {
      activeGame = await createGame({
        characterId: progress.selectedAvatar ?? 'a', stage, upgrades: progress.upgrades, settings: progress.settings,
        collectedCrystalIds: progress.stages[stage.id]?.collectedCrystalIds ?? [], loading, diagnostics,
        onVictory: (result) => window.setTimeout(() => finishVictory(stage, result), 0),
        onDefeat: (reason) => window.setTimeout(() => finishDefeat(stage, reason), 0),
        onMapOpen: () => flow.openOverlay('map'),
        onMapClose: () => flow.resume(),
      });
      loading.hide(); hud.classList.remove('is-hidden');
    } catch (error) {
      diagnostics?.capture('mission-start-failure', error, { stageId: stage.id });
      console.error(error); hud.classList.add('is-hidden');
      loading.showError('Não foi possível abrir esta missão. Toque para tentar novamente.');
    }
  };

  ui = mountCampaignUI({ flow, getProgress: () => progress, setProgress, onStartMission: startMission, onSelectAvatar: showAvatar });
  mountSelection(selectionScreen, CHARACTERS, (characterId) => {
    setProgress({ ...progress, selectedAvatar: characterId, hasStarted: true });
    selectionScreen.classList.add('is-hidden'); flow.go('home');
  }, {
    selectedId: progress.selectedAvatar ?? 'a',
    onSelect: (characterId) => syncAvatarPreview({ screen: 'avatar' }, characterId),
  });

  let previousFlowState = flow.state;
  flow.subscribe((state) => {
    if (activeGame && shouldDisposeMission(previousFlowState, state)) {
      activeGame.dispose();
      activeGame = null;
    }
    selectionScreen.classList.toggle('is-hidden', state.screen !== 'avatar');
    const paused = state.screen !== 'gameplay' || state.overlay === 'pause' || state.overlay === 'map';
    activeGame?.setPaused(paused);
    if (state.screen !== 'gameplay') hud.classList.add('is-hidden');
    syncAvatarPreview(state);
    syncMusicForState(state);
    previousFlowState = state;
  });
  syncMusicForState(flow.state);
  document.querySelector('#pause-button').addEventListener('click', () => flow.pause());
  document.querySelectorAll('[data-camera-sensitivity]').forEach((input) => input.addEventListener('input', (event) => {
      const value = Number(event.target.value);
      setProgress({ ...progress, settings: { ...progress.settings, cameraSensitivity: value } });
      activeGame?.setSensitivity(value);
    }));
  document.querySelector('[data-sound-enabled]').addEventListener('change', (event) => {
    setProgress({ ...progress, settings: { ...progress.settings, soundEnabled: event.target.checked } });
    activeGame?.setSoundEnabled(event.target.checked);
    music.setEnabled(event.target.checked).then((playing) => {
      document.documentElement.dataset.musicPlaying = String(playing);
    });
  });
  document.querySelector('[data-restart-stage]').addEventListener('click', () => startMission(ui.activeStage()));

  loading.update('Iniciando controles locais', 45);
  try {
    assertWebGL();
    await RAPIER.init();
    avatarPreview = createAvatarPreview({
      THREE,
      GLTFLoader,
      characterById,
      hosts: Object.fromEntries(['home', 'avatar', 'preparation', 'victory'].map((screen) => [
        screen,
        document.querySelector(`[data-avatar-preview="${screen}"]`),
      ])),
    });
    syncAvatarPreview();
    window.addEventListener('pagehide', () => {
      detachMusicActivation();
      music.dispose();
      avatarPreview?.dispose();
    }, { once: true });
  } catch (error) {
    console.error(error); loading.hide(); selectionScreen.classList.add('is-hidden');
    const destination = routeBootFailure(error);
    if (destination === 'compatibility') {
      flow.go('compatibility', { message: 'Este aparelho não conseguiu iniciar WebGL ou WebAssembly.' });
    } else {
      loading.showError('Não foi possível iniciar os controles locais. Tente novamente.');
    }
    return;
  }
  loading.update('A aventura está pronta', 100);
  window.setTimeout(() => { loading.hide(); ui.render(); }, 240);
}

async function createGame({ characterId, stage, upgrades, settings, collectedCrystalIds, loading, diagnostics, onVictory, onDefeat, onMapOpen, onMapClose }) {
  const root = document.querySelector('#game-root');
  const renderer = configureRenderer(root);
  const detachCanvasDiagnostics = diagnostics?.attachCanvas(renderer.domElement);
  const camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.12, 320);
  const loader = new GLTFLoader();
  let loadedWorldPieces = 0;
  const world = await createWorld(loader, (label) => {
    loadedWorldPieces += 1; loading.update(label, Math.min(59, 18 + loadedWorldPieces * 1.7));
  }, stage.mapId, renderer.capabilities.getMaxAnisotropy());
  world.scene.add(camera);
  document.querySelector('#game-hud').dataset.mapTexture = world.textureVariant.id;

  loading.update('Invocando seu viajante', 61);
  const playerData = await loadCharacter(loader, characterId, world.layout.start);
  world.scene.add(playerData.root);
  const maxHealth = 100 + upgrades.vitality * 15;
  const player = { ...playerData, health: maxHealth, maxHealth, yaw: 0, speed: 4.35, powerMultiplier: 1 + upgrades.power * 0.18 };
  player.root.rotation.y = modelYawFromDirection(0, -1);

  loading.update('Despertando os guardiões', 75);
  const enemyData = await Promise.all(world.layout.zombieSpawns.map((spawn) => loadCharacter(loader, spawn.kind === 'green' ? 'l' : 'o', spawn)));
  const enemies = enemyData.map((data, index) => ({
    id: `enemy-${index + 1}`,
    ...data,
    health: 70,
    maxHealth: 70,
    yaw: Math.PI,
    roamSpeed: 0.58 + index * 0.025,
    chaseSpeed: 1.35 + index * 0.055,
    attackCooldown: 0.5 + index * 0.3,
  }));
  for (const enemy of enemies) world.scene.add(enemy.root);

  const collectedCrystals = new Set(collectedCrystalIds);
  const crystalGeometry = new THREE.OctahedronGeometry(0.34, 0);
  const crystalMaterial = new THREE.MeshStandardMaterial({ color: 0x71f2de, emissive: 0x287f83, emissiveIntensity: 2.2 });
  const crystalPickups = world.layout.zombieSpawns.slice(0, 3).map((spawn, index) => {
    const id = `${stage.id}:crystal-${index + 1}`;
    const mesh = new THREE.Mesh(crystalGeometry, crystalMaterial);
    mesh.position.set(spawn.x, spawn.y + 1.05, spawn.z);
    mesh.visible = !collectedCrystals.has(id);
    world.scene.add(mesh);
    return { id, mesh, collected: collectedCrystals.has(id) };
  });

  const healthFill = document.querySelector('[data-health-fill]');
  const gameHud = document.querySelector('#game-hud');
  const objective = document.querySelector('[data-objective]');
  const reticle = document.querySelector('[data-reticle]');
  const targetHealth = document.querySelector('[data-target-health]');
  const damageVignette = document.querySelector('[data-damage-vignette]');
  const crystalFeedback = document.querySelector('[data-crystal-feedback] b');
  crystalFeedback.textContent = String(collectedCrystals.size);
  document.querySelector('[data-player-name]').textContent = player.definition.name;
  healthFill.style.width = '100%'; objective.textContent = stage.objective;

  const exploration = createExplorationState(world.layout);
  const minimap = mountMinimap({
    button: document.querySelector('#minimap-button'), overlay: document.querySelector('#map-overlay'),
    canvas: document.querySelector('#minimap-canvas'), largeCanvas: document.querySelector('#large-map-canvas'),
    layout: world.layout, getState: () => ({ exploration, player: player.root.position, enemies }),
    onOpen: onMapOpen, onClose: onMapClose,
  });
  let minimapFrame = 0;
  let defeated = false;
  let displayedHealth = maxHealth;
  let cancelRifleTrigger = () => {};
  const combat = new CombatSystem({
    player, enemies, canMoveTo: (position, radius) => world.canOccupy(position, radius),
    hasLineOfSight: (from, to) => world.hasLineOfSight(from, to),
    onHudChange: ({ playerHealth, enemiesRemaining, gateOpen }) => {
      healthFill.style.width = `${Math.max(0, playerHealth / maxHealth * 100)}%`;
      if (playerHealth < displayedHealth) {
        damageVignette.classList.add('hit');
        window.setTimeout(() => damageVignette.classList.remove('hit'), 180);
      }
      displayedHealth = playerHealth;
      damageVignette.classList.toggle('low', playerHealth / maxHealth <= 0.25);
      objective.textContent = gateOpen ? 'Atravesse o portal aberto' : `${stage.objective} • ${enemiesRemaining} restantes`;
    },
    onGateOpen: () => world.openGate(),
    onPlayerDefeated: () => {
      if (!defeated) {
        defeated = true;
        cancelRifleTrigger();
        onDefeat('Os guardiões venceram');
      }
    },
  });

  const cameraController = createThirdPersonCamera({
    THREE,
    camera,
    domElement: document.querySelector('#camera-zone'),
    player,
    wallColliders: world.cameraWallColliders,
    sensitivity: settings.cameraSensitivity,
  });
  const playerController = createPlayerController({
    RAPIER, nipplejs, joystickZone: document.querySelector('#joystick'), world, player,
    cameraHeading: () => cameraController.heading(),
  });
  diagnostics?.record('info', 'mission-ready', { stageId: stage.id, characterId });

  const raycaster = new THREE.Raycaster();
  const cameraForward = new THREE.Vector3();
  const targetDirection = new THREE.Vector3();
  let selectedEnemy = null;
  function updateAim() {
    camera.getWorldDirection(cameraForward).normalize();
    const living = enemies.filter((enemy) => enemy.health > 0);
    const candidates = living.map((enemy) => {
      targetDirection.copy(enemy.root.position).add(new THREE.Vector3(0, 1, 0)).sub(camera.position);
      const distance = targetDirection.length(); targetDirection.normalize();
      return { id: enemy.id, distance, angle: Math.acos(THREE.MathUtils.clamp(cameraForward.dot(targetDirection), -1, 1)) };
    });
    const byId = new Map(living.map((enemy) => [enemy.id, enemy]));
    const selected = selectAssistedTarget({
      candidates, maxRange: 18, maxAngle: 0.12, retainedId: selectedEnemy?.id,
      hasLineOfSight: (id) => {
        const enemy = byId.get(id); if (!enemy) return false;
        targetDirection.copy(enemy.root.position).add(new THREE.Vector3(0, 1, 0)).sub(camera.position);
        const distance = targetDirection.length(); raycaster.set(camera.position, targetDirection.normalize());
        const hit = raycaster.intersectObjects(world.cameraColliderMeshes, false)[0];
        return !hit || hit.distance >= distance - 0.6;
      },
    });
    selectedEnemy = selected ? byId.get(selected.id) : null;
    reticle.classList.toggle('locked', Boolean(selectedEnemy));
    targetHealth.textContent = selectedEnemy ? `${Math.ceil(selectedEnemy.health / selectedEnemy.maxHealth * 100)}%` : '';
  }

  const attackButton = document.querySelector('#attack-button');
  const aimButton = document.querySelector('#aim-button');
  const reloadButton = document.querySelector('#reload-button');
  const dodgeButton = document.querySelector('#dodge-button');
  const ammoCount = document.querySelector('[data-ammo-count]');
  const hybridAim = createHybridAim();
  const automaticFire = createAutomaticFire();
  const weapon = createWeaponState();
  const rifle = createRifleModel(THREE);
  const sockets = createRifleSockets({ THREE, playerRoot: player.root, camera });
  attachRifle(rifle, sockets.tps, 'tps');
  const effects = createRifleEffects({
    THREE,
    scene: world.scene,
    recoilGroup: rifle.recoilGroup,
    muzzle: rifle.muzzle,
  });
  const weaponAudio = createWeaponAudio({
    fireUrl: './audio/rifle-fire.mp3',
    reloadUrl: './audio/rifle-reload.mp3',
    enabled: settings.soundEnabled,
  });
  const paintWeapon = () => {
    ammoCount.textContent = weapon.reloading ? '...' : `${weapon.ammo}/${weapon.capacity}`;
    reloadButton.disabled = weapon.reloading || weapon.ammo === weapon.capacity;
    attackButton.disabled = weapon.reloading || weapon.ammo === 0;
  };
  paintWeapon();
  const performRifleShot = (spread) => {
    const wasReloading = weapon.reloading;
    if (!weapon.consumeShot()) return false;
    const shot = resolveRifleHitscan({
      THREE, camera, raycaster, wallMeshes: world.cameraColliderMeshes,
      enemies, spread, maxRange: 45,
    });
    if (shot.target) {
      const result = combat.applyRifleHit(shot.target, { headshot: shot.zone === 'head' });
      if (result.hit) {
        reticle.classList.add('hit');
        window.setTimeout(() => reticle.classList.remove('hit'), 110);
      }
    }
    effects.pulseShot(shot.point);
    weaponAudio.playFire();
    if (!wasReloading && weapon.reloading) weaponAudio.playReload();
    paintWeapon();
    return true;
  };
  const rifleIntent = createRifleIntent({
    onAimChange: (held) => {
      if (held) hybridAim.press();
      else {
        hybridAim.release();
        player.root.visible = true;
      }
    },
    onFireChange: (held) => {
      if (held) automaticFire.press();
      else automaticFire.release();
      attackButton.classList.toggle('is-active', held);
    },
  });
  const input = bindRifleInput({
    button: attackButton,
    windowRef: window,
    onPress: () => rifleIntent.pressFire(),
    onRelease: () => rifleIntent.releaseFire(),
    onMove: (deltaX, deltaY) => cameraController.rotateByPixels(deltaX, deltaY),
  });
  const aimInput = bindRifleInput({
    button: aimButton,
    windowRef: window,
    onPress: () => {
      rifleIntent.toggleAim();
      const active = rifleIntent.state().aimHeld;
      aimButton.classList.toggle('is-active', active);
      aimButton.setAttribute('aria-pressed', String(active));
    },
    onRelease: () => {},
  });
  cancelRifleTrigger = () => {
    input.cancel();
    aimInput.cancel();
    rifleIntent.cancel();
    automaticFire.cancel();
    hybridAim.cancel();
    attackButton.classList.remove('is-active');
    aimButton.classList.remove('is-active');
    aimButton.setAttribute('aria-pressed', 'false');
    player.root.visible = true;
  };
  const reload = () => {
    if (weapon.startReload()) weaponAudio.playReload();
    paintWeapon();
  };
  const dodge = () => { if (playerController.dodge()) dodgeButton.disabled = true; };
  reloadButton.addEventListener('click', reload);
  dodgeButton.addEventListener('click', dodge);

  let disposed = false; let completed = false; let paused = false;
  const playerTurnAxis = new THREE.Vector3(0, 1, 0);
  const targetPlayerQuaternion = new THREE.Quaternion();
  let lastFrameTime = performance.now() * 0.001; let elapsed = 0;
  const resize = () => { camera.aspect = window.innerWidth / window.innerHeight; camera.updateProjectionMatrix(); renderer.setSize(window.innerWidth, window.innerHeight); renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5)); };
  window.addEventListener('resize', resize);

  const animate = () => {
    if (disposed) return;
    const now = performance.now() * 0.001;
    const delta = paused ? 0 : Math.min(Math.max(0, now - lastFrameTime), 0.05);
    lastFrameTime = now;
    if (!paused && !defeated) {
      elapsed += delta;
      // Primeiro consome a rotação do "PlayerController"; só depois calcula
      // MoveForward/MoveRight. Assim movimento e câmera usam o mesmo heading
      // no mesmo frame, sem o atraso de uma atualização.
      const lookChanged = cameraController.updateInput(delta);
      const moved = playerController.move(delta);
      gameHud.dataset.playerPosition = `${player.root.position.x.toFixed(2)},${player.root.position.y.toFixed(2)},${player.root.position.z.toFixed(2)}`;
      const isMoving = Math.hypot(moved.x, moved.z) > 0.0001;
      // bUseControllerRotationYaw do TPS: o corpo acompanha a mira em todos os
      // frames. O joystick continua camera-relative, portanto esquerda/direita
      // viram strafe e para baixo vira recuo, sem o heroi abandonar a mira.
      const controllerFacing = controllerFacingFromYaw(cameraController.heading());
      player.yaw = controllerFacing.combatYaw;
      targetPlayerQuaternion.setFromAxisAngle(playerTurnAxis, controllerFacing.modelYaw);
      player.root.quaternion.copy(targetPlayerQuaternion);
      gameHud.dataset.playerYaw = player.yaw.toFixed(3);
      gameHud.dataset.cameraYaw = cameraController.heading().toFixed(3);
      if (isMoving && combat.playerAttackCooldown <= 0.25) {
        player.animations.play(['walk', 'sprint', 'idle']);
      } else if (combat.playerAttackCooldown <= 0.25) {
        player.animations.play(['idle', 'static']);
      }
      combat.update(delta);
      gameHud.dataset.enemyStates = enemies.map((enemy) => enemy.state ?? 'idle').join(',');
      weapon.update(delta); paintWeapon();
      dodgeButton.disabled = !playerController.dodgeReady;
      for (const pickup of crystalPickups) {
        if (pickup.collected) continue;
        pickup.mesh.rotation.y += delta * 2.2;
        pickup.mesh.position.y += Math.sin(elapsed * 2.4 + pickup.mesh.position.x) * delta * 0.025;
        if (pickup.mesh.position.distanceTo(player.root.position) < 1.55) {
          pickup.collected = true; pickup.mesh.visible = false; collectedCrystals.add(pickup.id);
          crystalFeedback.textContent = String(collectedCrystals.size);
        }
      }
      for (const enemy of enemies) {
        const enemyFloor = world.floorHeightAt(enemy.root.position);
        if (enemyFloor !== null) enemy.root.position.y = enemyFloor + 0.04;
      }
      player.animations.update(delta); for (const enemy of enemies) enemy.animations.update(delta);
      world.update(delta, elapsed); revealAround(exploration, player.root.position, 1);
      minimapFrame = (minimapFrame + 1) % 6; if (minimapFrame === 0) minimap.paint();
      hybridAim.update(delta);
      cameraController.setViewBlend(hybridAim.blend);
      reticle.classList.toggle('aimed', hybridAim.blend >= 0.5);
      player.root.visible = true;
      cameraController.updateFollow(delta);
      automaticFire.update(delta, { ready: hybridAim.readyToFire, fire: performRifleShot });
      effects.update(delta);
      reticle.style.setProperty('--spread', `${automaticFire.spread * 1000}px`);
      gameHud.dataset.cameraPosition = `${camera.position.x.toFixed(2)},${camera.position.y.toFixed(2)},${camera.position.z.toFixed(2)}`;
      const cameraAim = cameraController.aimPoint();
      gameHud.dataset.cameraAim = `${cameraAim.x.toFixed(2)},${cameraAim.y.toFixed(2)},${cameraAim.z.toFixed(2)}`;
      gameHud.dataset.cameraDistance = Math.hypot(
        camera.position.x - player.root.position.x,
        camera.position.y - (player.root.position.y + 1.2),
        camera.position.z - player.root.position.z,
      ).toFixed(2);
      const cameraCollision = cameraController.collision();
      gameHud.dataset.cameraCollision = `${cameraCollision.angle.toFixed(2)},${cameraCollision.directDistance.toFixed(2)},${cameraCollision.distance.toFixed(2)}`;
      diagnostics?.sampleFrame({
        nowSeconds: now,
        delta,
        stick: playerController.movement,
        moved,
        position: player.root.position,
        playerYaw: player.yaw,
        cameraYaw: cameraController.heading(),
        lookChanged,
        cameraCollision,
        physicsState: playerController.physicsState,
      });
      updateAim();
      if (!completed && world.isAtExit(player.root.position)) {
        completed = true;
        cancelRifleTrigger();
        onVictory({ completed: true, elapsedSeconds: elapsed, healthRemaining: player.health, maxHealth, collectedCrystalIds: [...collectedCrystals] });
      }
    }
    renderer.render(world.scene, camera);
  };

  loading.update('A missão começou', 100); renderer.setAnimationLoop(animate);
  return {
    setPaused(value) {
      paused = Boolean(value);
      if (paused) cancelRifleTrigger();
      playerController.setEnabled(!paused);
      cameraController.setEnabled(!paused);
    },
    setSensitivity(value) { cameraController.setSensitivity(value); },
    setSoundEnabled(value) { weaponAudio.setEnabled(value); },
    dispose() {
      if (disposed) return; disposed = true;
      diagnostics?.record('info', 'mission-dispose', { stageId: stage.id });
      detachCanvasDiagnostics?.();
      input.dispose(); aimInput.dispose(); rifleIntent.dispose();
      automaticFire.dispose(); weaponAudio.dispose(); effects.dispose(); rifle.dispose();
      hybridAim.cancel(); attackButton.classList.remove('is-active'); aimButton.classList.remove('is-active'); player.root.visible = true;
      reloadButton.removeEventListener('click', reload); dodgeButton.removeEventListener('click', dodge);
      minimap.dispose(); playerController.dispose(); cameraController.dispose(); crystalGeometry.dispose(); crystalMaterial.dispose();
      world.scene.remove(camera);
      window.removeEventListener('resize', resize); renderer.setAnimationLoop(null); renderer.dispose(); root.replaceChildren();
    },
  };
}

export { CAMPAIGN };
