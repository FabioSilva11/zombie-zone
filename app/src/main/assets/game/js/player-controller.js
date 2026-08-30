const PLAYER_RADIUS = 0.5;
const PLAYER_HALF_HEIGHT = 0.64;
const CHARACTER_OFFSET = 0.03;
const JOYSTICK_DEADZONE = 0.04;
const JUMP_VELOCITY = 5.8;
const JUMP_GRAVITY = 15.5;
const SPRINT_SPEED_MULTIPLIER = 1.9;

export function cameraRelativeMovement(stick, cameraYaw, speed, delta) {
  const magnitude = Math.hypot(stick.x, stick.y);
  if (magnitude <= JOYSTICK_DEADZONE) return { x: 0, y: 0, z: 0 };

  // Unreal-style MoveForward / MoveRight: a direção é normalizada e a
  // intensidade analógica entra UMA única vez. O código anterior aplicava a
  // magnitude no vetor e novamente na distância, deixando 50% do analógico
  // com apenas 25% da velocidade.
  const directionX = stick.x / magnitude;
  const directionY = stick.y / magnitude;
  const strength = Math.min(1, magnitude);
  const distance = speed * delta * strength;
  const sinYaw = Math.sin(cameraYaw);
  const cosYaw = Math.cos(cameraYaw);
  return {
    x: (cosYaw * directionX + sinYaw * directionY) * distance,
    y: 0,
    z: (sinYaw * directionX - cosYaw * directionY) * distance,
  };
}

export function wallToCuboid(wall) {
  return {
    center: { x: (wall.minX + wall.maxX) / 2, y: (wall.minY + wall.maxY) / 2, z: (wall.minZ + wall.maxZ) / 2 },
    half: { x: (wall.maxX - wall.minX) / 2, y: (wall.maxY - wall.minY) / 2, z: (wall.maxZ - wall.minZ) / 2 },
  };
}

export function applyNippleVector(stick, data) {
  const payload = data?.data ?? data;
  stick.x = payload?.vector?.x ?? 0;
  stick.y = payload?.vector?.y ?? 0;
  return stick;
}

export function dodgeMovementInput(stick, active) {
  if (!active) return { x: stick.x, y: stick.y };
  return Math.hypot(stick.x, stick.y) > JOYSTICK_DEADZONE ? { x: stick.x, y: stick.y } : { x: 0, y: 1 };
}

export function sprintMovementInput(stick, active) {
  if (!active || Math.hypot(stick.x, stick.y) > JOYSTICK_DEADZONE) return { x: stick.x, y: stick.y };
  return { x: 0, y: 1 };
}

export function createPlayerController({
  RAPIER,
  nipplejs,
  joystickZone,
  world,
  player,
  cameraHeading,
  windowRef = typeof window === 'undefined' ? null : window,
  documentRef = typeof document === 'undefined' ? null : document,
}) {
  const physics = new RAPIER.World({ x: 0, y: 0, z: 0 });
  const colliderLabels = new Map();
  for (const wall of world.physicsWalls) {
    const { center, half } = wallToCuboid(wall);
    const created = physics.createCollider(RAPIER.ColliderDesc.cuboid(half.x, half.y, half.z).setTranslation(center.x, center.y, center.z));
    colliderLabels.set(created.handle, wall.id ?? 'wall');
  }
  for (const [index, surface] of (world.physicsSurfaces ?? []).entries()) {
    let descriptor = RAPIER.ColliderDesc.cuboid(surface.half.x, surface.half.y, surface.half.z)
      .setTranslation(surface.center.x, surface.center.y, surface.center.z);
    if (surface.rotation) descriptor = descriptor.setRotation(surface.rotation);
    const created = physics.createCollider(descriptor);
    colliderLabels.set(created.handle, `${surface.kind ?? 'surface'}-${index + 1}`);
  }
  const body = physics.createRigidBody(RAPIER.RigidBodyDesc.kinematicPositionBased()
    .setTranslation(player.root.position.x, player.root.position.y + PLAYER_HALF_HEIGHT + PLAYER_RADIUS + CHARACTER_OFFSET, player.root.position.z));
  const collider = physics.createCollider(RAPIER.ColliderDesc.capsule(PLAYER_HALF_HEIGHT, PLAYER_RADIUS), body);
  const character = physics.createCharacterController(CHARACTER_OFFSET);
  character.setSlideEnabled(true);
  character.enableAutostep(0.32, 0.18, false);
  character.enableSnapToGround(0.4);

  const stick = { x: 0, y: 0 };
  const joystickZoneWidth = Math.max(96, joystickZone.clientWidth || 0);
  const joystick = nipplejs.create({ zone: joystickZone, mode: 'static', position: { left: '50%', top: '50%' }, size: joystickZoneWidth * 0.76, color: '#8d75d6', restOpacity: 0.7 });
  const resetStick = () => { stick.x = 0; stick.y = 0; };
  joystick.on('move', (event) => {
    applyNippleVector(stick, event);
    if (Math.hypot(stick.x, stick.y) > JOYSTICK_DEADZONE) sprinting = false;
  });
  joystick.on('end', resetStick);

  // Não escute pointerup/touchend globais: em multitouch, soltar o dedo da
  // câmera ou do tiro não pode cancelar o dedo que continua no joystick.
  // O NippleJS já emite "end" somente para o ponteiro que possui o analógico.
  const lifecycleReleaseEvents = ['blur', 'pagehide'];
  lifecycleReleaseEvents.forEach((name) => windowRef?.addEventListener(name, resetStick));
  const resetWhenHidden = () => { if (documentRef?.hidden) resetStick(); };
  documentRef?.addEventListener('visibilitychange', resetWhenHidden);
  let enabled = true;
  let dodgeRemaining = 0;
  let dodgeCooldown = 0;
  let verticalOffset = 0;
  let verticalVelocity = 0;
  let airborne = false;
  let crouched = false;
  let sprinting = false;
  let lastMovement = { x: 0, y: 0, z: 0 };
  let lastPhysicsState = { reason: 'idle', desired: { x: 0, y: 0, z: 0 }, resolved: { x: 0, y: 0, z: 0 }, collisions: [] };

  const physicsCollisions = () => {
    const count = character.numComputedCollisions?.() ?? 0;
    return Array.from({ length: count }, (_, index) => {
      const collision = character.computedCollision?.(index);
      const handle = collision?.collider?.handle;
      return {
        collider: colliderLabels.get(handle) ?? `collider-${handle ?? 'unknown'}`,
        toi: collision?.toi ?? null,
        normal: collision?.normal1 ? { x: collision.normal1.x, y: collision.normal1.y, z: collision.normal1.z } : null,
      };
    });
  };

  return {
    get movement() { return { ...stick }; },
    get moved() { return { ...lastMovement }; },
    get physicsState() { return structuredClone(lastPhysicsState); },
    get dodgeReady() { return dodgeCooldown <= 0; },
    get jumpReady() { return !airborne; },
    get isAirborne() { return airborne; },
    get crouched() { return crouched; },
    get sprinting() { return sprinting; },
    get speedMultiplier() { return crouched ? 0.52 : sprinting ? SPRINT_SPEED_MULTIPLIER : 1; },
    dodge() {
      if (!enabled || dodgeCooldown > 0) return false;
      dodgeRemaining = 0.2;
      dodgeCooldown = 1.05;
      return true;
    },
    jump() {
      if (!enabled || airborne) return false;
      crouched = false;
      verticalVelocity = JUMP_VELOCITY;
      airborne = true;
      return true;
    },
    toggleCrouch() {
      if (!enabled || airborne) return crouched;
      crouched = !crouched;
      if (crouched) sprinting = false;
      return crouched;
    },
    setSprinting(value) {
      sprinting = enabled && Boolean(value);
      if (sprinting) crouched = false;
      return sprinting;
    },
    setEnabled(value) {
      enabled = Boolean(value);
      if (!enabled) { resetStick(); sprinting = false; }
    },
    move(delta) {
      if (!enabled) return { x: 0, y: 0, z: 0 };
      dodgeCooldown = Math.max(0, dodgeCooldown - delta);
      dodgeRemaining = Math.max(0, dodgeRemaining - delta);
      const dodgeActive = dodgeRemaining > 0;
      const movementInput = sprintMovementInput(dodgeMovementInput(stick, dodgeActive), sprinting && !dodgeActive);
      const speedMultiplier = dodgeActive ? 2.45 : crouched ? 0.52 : sprinting ? SPRINT_SPEED_MULTIPLIER : 1;
      const desired = cameraRelativeMovement(movementInput, cameraHeading(), player.speed * speedMultiplier, delta);
      desired.y = -Math.min(0.08, 4.905 * delta * delta);
      character.computeColliderMovement(collider, desired);
      const resolved = character.computedMovement();
      const collisions = physicsCollisions();
      const baseY = player.root.position.y - verticalOffset;
      const previousJumpState = { verticalOffset, verticalVelocity, airborne };
      if (airborne) {
        verticalVelocity -= JUMP_GRAVITY * delta;
        verticalOffset = Math.max(0, verticalOffset + verticalVelocity * delta);
        if (verticalOffset === 0 && verticalVelocity <= 0) {
          verticalVelocity = 0;
          airborne = false;
        }
      }
      const candidate = {
        x: player.root.position.x + resolved.x,
        y: baseY,
        z: player.root.position.z + resolved.z,
      };
      const floor = world.floorHeightAt(candidate);
      if (floor === null) {
        ({ verticalOffset, verticalVelocity, airborne } = previousJumpState);
        lastPhysicsState = { reason: 'no-floor', desired: { ...desired }, resolved: { ...resolved }, collisions };
        lastMovement = { x: 0, y: 0, z: 0 }; return lastMovement;
      }
      candidate.y = floor + verticalOffset;
      if (!world.canOccupy({ ...candidate, y: floor }, 1.2)) {
        ({ verticalOffset, verticalVelocity, airborne } = previousJumpState);
        lastPhysicsState = { reason: 'world-blocked', desired: { ...desired }, resolved: { ...resolved }, collisions };
        lastMovement = { x: 0, y: 0, z: 0 }; return lastMovement;
      }
      lastMovement = { x: candidate.x - player.root.position.x, y: candidate.y - player.root.position.y, z: candidate.z - player.root.position.z };
      lastPhysicsState = {
        reason: Math.hypot(desired.x, desired.z) > JOYSTICK_DEADZONE && Math.hypot(lastMovement.x, lastMovement.z) < 1e-7 ? 'physics-blocked' : 'moved',
        desired: { ...desired }, resolved: { ...resolved }, collisions,
      };
      player.root.position.set(candidate.x, candidate.y, candidate.z);
      body.setNextKinematicTranslation({
        x: candidate.x,
        y: candidate.y + PLAYER_HALF_HEIGHT + PLAYER_RADIUS + CHARACTER_OFFSET,
        z: candidate.z,
      });
      physics.step();
      return { ...lastMovement };
    },
    dispose() {
      lifecycleReleaseEvents.forEach((name) => windowRef?.removeEventListener(name, resetStick));
      documentRef?.removeEventListener('visibilitychange', resetWhenHidden);
      joystick.destroy();
      character.free?.();
      physics.free?.();
    },
  };
}
