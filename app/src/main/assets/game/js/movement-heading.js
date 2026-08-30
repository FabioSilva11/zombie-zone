const MOVEMENT_EPSILON = 1e-8;

export function frameIndependentSmoothing(rate, delta) {
  return 1 - Math.exp(-Math.max(0, rate) * Math.max(0, delta));
}

function hasDirection(x, z) {
  return Math.hypot(x, z) > MOVEMENT_EPSILON;
}

// O combate usa zero apontando para -Z.
export function combatYawFromDirection(x, z, fallback = 0) {
  return hasDirection(x, z) ? Math.atan2(x, -z) : fallback;
}

// Os personagens do pacote têm a frente nativa apontando para +Z.
export function modelYawFromDirection(x, z, fallback = 0) {
  return hasDirection(x, z) ? Math.atan2(x, z) : fallback;
}

// Equivalente ao ControllerYaw do TPS do Unreal. A mira define a frente do
// personagem; o joystick escolhe apenas se ele avanca, recua ou faz strafe.
export function controllerFacingFromYaw(yaw = 0) {
  const safeYaw = Number.isFinite(yaw) ? yaw : 0;
  const x = Math.sin(safeYaw);
  const z = -Math.cos(safeYaw);
  return {
    x,
    z,
    combatYaw: combatYawFromDirection(x, z, safeYaw),
    modelYaw: modelYawFromDirection(x, z),
  };
}

export function resolveFreeLookFacing({
  movement,
  cameraYaw = 0,
  combatActive = false,
  currentCombatYaw = 0,
  currentModelYaw = Math.PI,
} = {}) {
  const x = Number(movement?.x) || 0;
  const z = Number(movement?.z) || 0;
  if (hasDirection(x, z)) {
    return {
      x,
      z,
      combatYaw: combatYawFromDirection(x, z, currentCombatYaw),
      modelYaw: modelYawFromDirection(x, z, currentModelYaw),
      source: 'movement',
    };
  }
  if (combatActive) return { ...controllerFacingFromYaw(cameraYaw), source: 'camera' };
  return { x: 0, z: 0, combatYaw: currentCombatYaw, modelYaw: currentModelYaw, source: 'locked' };
}
