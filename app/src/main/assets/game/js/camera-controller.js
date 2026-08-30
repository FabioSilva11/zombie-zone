import { cameraDistanceBeforeWall } from './map-collision.js';

const CAMERA_DISTANCE = 6.2;
const TARGET_HEIGHT = 1.6;
const CAMERA_HEIGHT = 3.0;
const CAMERA_SHOULDER = 0.9;
const CAMERA_AIM_DISTANCE = 24;
const CAMERA_MIN_PITCH = -Math.PI / 6;
const CAMERA_MAX_PITCH = Math.PI * 55 / 180;
const CAMERA_INITIAL_PITCH = Math.PI / 18;
const CAMERA_PITCH_ORBIT_INFLUENCE = 0.3;
const CAMERA_COLLISION_RADIUS = 0.34;
const CAMERA_COLLISION_MIN_DISTANCE = 0.08;
const CAMERA_RELEASE_SMOOTHNESS = 16;
const LOOK_RADIANS_PER_PIXEL = 0.0042;
const AIM_CAMERA_DISTANCE = 3.15;
const AIM_CAMERA_HEIGHT = 2.45;
const AIM_CAMERA_SHOULDER = 0.62;
const AIM_PITCH_ORBIT_INFLUENCE = 0.2;
const CROUCH_CAMERA_OFFSET = -0.42;
const STANCE_SMOOTHNESS = 14;

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

export function configureThirdPersonControls(controls, CameraControls, colliderMeshes, sensitivity = 1) {
  // Mantido para compatibilidade com os testes/utilitários que ainda criam
  // CameraControls diretamente. A câmera principal abaixo não depende dele:
  // ela usa um "PlayerController" de yaw/pitch, como no TPS de referência.
  const { ACTION } = CameraControls;
  controls.minDistance = CAMERA_DISTANCE;
  controls.maxDistance = CAMERA_DISTANCE;
  controls.minPolarAngle = Math.PI / 2 - CAMERA_MAX_PITCH;
  controls.maxPolarAngle = Math.PI / 2 - CAMERA_MIN_PITCH;
  controls.smoothTime = 0.055;
  controls.draggingSmoothTime = 0.045;
  controls.azimuthRotateSpeed = sensitivity;
  controls.polarRotateSpeed = sensitivity * 0.72;
  controls.colliderMeshes = colliderMeshes ?? [];
  controls.mouseButtons.left = ACTION.ROTATE;
  controls.mouseButtons.middle = ACTION.NONE;
  controls.mouseButtons.right = ACTION.NONE;
  controls.mouseButtons.wheel = ACTION.NONE;
  controls.touches.one = ACTION.TOUCH_ROTATE;
  controls.touches.two = ACTION.NONE;
  controls.touches.three = ACTION.NONE;
  return controls;
}

export function cameraFollowAlpha(smoothness, delta) {
  return 1 - Math.exp(-Math.max(0, smoothness) * Math.max(0, delta));
}

export function applyLookDelta(
  rotation,
  deltaX,
  deltaY,
  sensitivity = 1,
  radiansPerPixel = LOOK_RADIANS_PER_PIXEL,
) {
  const scale = Math.max(0, sensitivity) * Math.max(0, radiansPerPixel);
  return {
    yaw: rotation.yaw + deltaX * scale,
    pitch: clamp(
      rotation.pitch + deltaY * scale * 0.72,
      CAMERA_MIN_PITCH,
      CAMERA_MAX_PITCH,
    ),
  };
}

export function thirdPersonComposition(
  position,
  yaw,
  pitch,
  {
    distance = CAMERA_DISTANCE,
    height = CAMERA_HEIGHT,
    shoulder = CAMERA_SHOULDER,
    aimDistance = CAMERA_AIM_DISTANCE,
    pitchOrbitInfluence = CAMERA_PITCH_ORBIT_INFLUENCE,
  } = {},
) {
  const sinYaw = Math.sin(yaw);
  const cosYaw = Math.cos(yaw);
  const cosPitch = Math.cos(pitch);
  const forward = { x: sinYaw * cosPitch, y: -Math.sin(pitch), z: -cosYaw * cosPitch };
  const right = { x: cosYaw, y: 0, z: sinYaw };
  const armPitch = pitch * pitchOrbitInfluence;
  const horizontalDistance = Math.cos(armPitch) * distance;
  const verticalOrbit = Math.sin(armPitch) * distance;
  const camera = {
    x: position.x - sinYaw * horizontalDistance + right.x * shoulder,
    y: position.y + height + verticalOrbit,
    z: position.z + cosYaw * horizontalDistance + right.z * shoulder,
  };
  return {
    camera,
    forward,
    aim: {
      x: camera.x + forward.x * aimDistance,
      y: camera.y + forward.y * aimDistance,
      z: camera.z + forward.z * aimDistance,
    },
  };
}

export function firstPersonComposition(
  position,
  yaw,
  pitch,
  { eyeHeight = 2.05, forwardOffset = 0.18, aimDistance = 45 } = {},
) {
  const cosPitch = Math.cos(pitch);
  const forward = { x: Math.sin(yaw) * cosPitch, y: -Math.sin(pitch), z: -Math.cos(yaw) * cosPitch };
  const camera = {
    x: position.x + forward.x * forwardOffset,
    y: position.y + eyeHeight + forward.y * forwardOffset,
    z: position.z + forward.z * forwardOffset,
  };
  return {
    camera,
    forward,
    aim: {
      x: camera.x + forward.x * aimDistance,
      y: camera.y + forward.y * aimDistance,
      z: camera.z + forward.z * aimDistance,
    },
  };
}

export function aimedThirdPersonComposition(position, yaw, pitch) {
  return thirdPersonComposition(position, yaw, pitch, {
    distance: AIM_CAMERA_DISTANCE,
    height: AIM_CAMERA_HEIGHT,
    shoulder: AIM_CAMERA_SHOULDER,
    aimDistance: CAMERA_AIM_DISTANCE,
    pitchOrbitInfluence: AIM_PITCH_ORBIT_INFLUENCE,
  });
}

export function estimatedCharacterScreenFraction(characterHeight, distance = CAMERA_DISTANCE, verticalFov = Math.PI / 3) {
  return 2 * Math.atan(Math.max(0, characterHeight) / (2 * Math.max(0.01, distance))) / verticalFov;
}

export function resolveSpringArmCamera({
  target,
  desired,
  wallColliders = [],
  currentDistance,
  delta,
  radius = CAMERA_COLLISION_RADIUS,
}) {
  const dx = desired.x - target.x;
  const dy = desired.y - target.y;
  const dz = desired.z - target.z;
  const directDistance = Math.hypot(dx, dy, dz);
  if (directDistance <= 1e-8) {
    return { position: { ...target }, distance: 0, directDistance: 0, collided: false };
  }

  const allowedDistance = wallColliders.length
    ? cameraDistanceBeforeWall(
      target,
      desired,
      wallColliders,
      radius,
      CAMERA_COLLISION_MIN_DISTANCE,
    )
    : directDistance;

  const previous = Number.isFinite(currentDistance) ? currentDistance : directDistance;
  // SpringArm do Unreal: entra imediatamente quando encontra obstáculo e volta
  // de forma controlada quando o espaço atrás do personagem fica livre.
  const distance = allowedDistance < previous
    ? allowedDistance
    : previous + (allowedDistance - previous) * cameraFollowAlpha(CAMERA_RELEASE_SMOOTHNESS, delta);
  const resolvedDistance = Math.min(directDistance, Math.max(0, distance));
  const factor = resolvedDistance / directDistance;
  return {
    position: {
      x: target.x + dx * factor,
      y: target.y + dy * factor,
      z: target.z + dz * factor,
    },
    distance: resolvedDistance,
    directDistance,
    collided: resolvedDistance < directDistance - 1e-4,
  };
}

export function createThirdPersonCamera({
  THREE,
  camera,
  domElement,
  player,
  wallColliders = [],
  sensitivity = 1,
  windowRef = typeof window === 'undefined' ? null : window,
}) {
  // Adaptação do padrão Unreal TPS:
  // - yaw/pitch pertencem ao "controller";
  // - o personagem usa esse heading para interpretar MoveForward/MoveRight;
  // - a câmera renderizada é a única câmera do sistema;
  // - o follow funciona como um SpringArm com retração contra paredes.
  let enabled = true;
  let lookSensitivity = sensitivity;
  let yaw = 0;
  let pitch = CAMERA_INITIAL_PITCH;
  let lookChanged = true;
  let activePointerId = null;
  let lastPointerX = 0;
  let lastPointerY = 0;
  let springArmDistance = null;
  let aimedSpringArmDistance = null;
  let viewBlend = 0;
  let stanceOffset = 0;
  let targetStanceOffset = 0;
  let collisionState = { angle: pitch, distance: CAMERA_DISTANCE, directDistance: CAMERA_DISTANCE, collided: false };
  const aimTarget = new THREE.Vector3();

  const rotateByPixels = (deltaX, deltaY) => {
    if (!enabled) return false;
    const next = applyLookDelta(
      { yaw, pitch },
      Number(deltaX) || 0,
      Number(deltaY) || 0,
      lookSensitivity,
    );
    yaw = next.yaw;
    pitch = next.pitch;
    lookChanged = true;
    return true;
  };

  const pointerIdOf = (event) => event.pointerId ?? 1;
  const releasePointer = (event) => {
    if (activePointerId === null) return;
    if (event && pointerIdOf(event) !== activePointerId) return;
    try { domElement.releasePointerCapture?.(activePointerId); } catch {}
    activePointerId = null;
  };
  const onPointerDown = (event) => {
    if (!enabled || activePointerId !== null) return;
    activePointerId = pointerIdOf(event);
    lastPointerX = event.clientX ?? 0;
    lastPointerY = event.clientY ?? 0;
    try { domElement.setPointerCapture?.(activePointerId); } catch {}
    event.preventDefault?.();
  };
  const onPointerMove = (event) => {
    if (!enabled || activePointerId === null || pointerIdOf(event) !== activePointerId) return;
    const x = event.clientX ?? lastPointerX;
    const y = event.clientY ?? lastPointerY;
    rotateByPixels(x - lastPointerX, y - lastPointerY);
    lastPointerX = x;
    lastPointerY = y;
    event.preventDefault?.();
  };
  const onContextMenu = (event) => event.preventDefault?.();
  const onBlur = () => releasePointer();

  domElement.addEventListener('pointerdown', onPointerDown);
  domElement.addEventListener('pointermove', onPointerMove);
  domElement.addEventListener('pointerup', releasePointer);
  domElement.addEventListener('pointercancel', releasePointer);
  domElement.addEventListener('contextmenu', onContextMenu);
  windowRef?.addEventListener('blur', onBlur);

  const updateFollow = (delta = 0) => {
    stanceOffset += (targetStanceOffset - stanceOffset) * cameraFollowAlpha(STANCE_SMOOTHNESS, delta);
    const followPosition = {
      x: player.root.position.x,
      y: player.root.position.y + stanceOffset,
      z: player.root.position.z,
    };
    const composition = thirdPersonComposition(followPosition, yaw, pitch);
    const target = {
      x: player.root.position.x,
      y: player.root.position.y + TARGET_HEIGHT + stanceOffset,
      z: player.root.position.z,
    };
    const resolved = resolveSpringArmCamera({
      target,
      desired: composition.camera,
      wallColliders,
      currentDistance: springArmDistance,
      delta,
    });
    springArmDistance = resolved.distance;
    const aimedComposition = aimedThirdPersonComposition(followPosition, yaw, pitch);
    const aimedResolved = resolveSpringArmCamera({
      target,
      desired: aimedComposition.camera,
      wallColliders,
      currentDistance: aimedSpringArmDistance,
      delta,
    });
    aimedSpringArmDistance = aimedResolved.distance;
    camera.position.set(
      resolved.position.x + (aimedResolved.position.x - resolved.position.x) * viewBlend,
      resolved.position.y + (aimedResolved.position.y - resolved.position.y) * viewBlend,
      resolved.position.z + (aimedResolved.position.z - resolved.position.z) * viewBlend,
    );
    const blendedForward = composition.forward;
    const forwardLength = Math.hypot(blendedForward.x, blendedForward.y, blendedForward.z) || 1;
    aimTarget.set(
      camera.position.x + blendedForward.x / forwardLength * CAMERA_AIM_DISTANCE,
      camera.position.y + blendedForward.y / forwardLength * CAMERA_AIM_DISTANCE,
      camera.position.z + blendedForward.z / forwardLength * CAMERA_AIM_DISTANCE,
    );
    camera.lookAt(aimTarget);
    collisionState = {
      angle: pitch,
      distance: resolved.distance + (aimedResolved.distance - resolved.distance) * viewBlend,
      directDistance: resolved.directDistance + (aimedResolved.directDistance - resolved.directDistance) * viewBlend,
      collided: resolved.collided || aimedResolved.collided,
    };
    return resolved;
  };

  updateFollow(0);

  return {
    heading() { return yaw; },
    pitch() { return pitch; },
    setCrouched(value) { targetStanceOffset = value ? CROUCH_CAMERA_OFFSET : 0; },
    stanceOffset() { return stanceOffset; },
    setViewBlend(value) { viewBlend = clamp(Number(value) || 0, 0, 1); },
    viewBlend() { return viewBlend; },
    aimPoint() { return { x: aimTarget.x, y: aimTarget.y, z: aimTarget.z }; },
    collision() { return { ...collisionState }; },
    setEnabled(value) {
      enabled = Boolean(value);
      if (!enabled) releasePointer();
    },
    setSensitivity(value) { lookSensitivity = Math.max(0, Number(value) || 0); },
    rotateByPixels,
    updateInput() {
      const changed = lookChanged;
      lookChanged = false;
      return changed;
    },
    updateFollow,
    update(delta) {
      this.updateInput(delta);
      updateFollow(delta);
      return true;
    },
    dispose() {
      releasePointer();
      domElement.removeEventListener('pointerdown', onPointerDown);
      domElement.removeEventListener('pointermove', onPointerMove);
      domElement.removeEventListener('pointerup', releasePointer);
      domElement.removeEventListener('pointercancel', releasePointer);
      domElement.removeEventListener('contextmenu', onContextMenu);
      windowRef?.removeEventListener('blur', onBlur);
    },
  };
}
