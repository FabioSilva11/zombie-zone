export const ZOMBIE_STATES = Object.freeze({
  IDLE: 'idle',
  ROAM: 'roam',
  CHASE: 'chase',
  ATTACK: 'attack',
  DEAD: 'dead',
});

export const DEFAULT_ZOMBIE_AI_CONFIG = Object.freeze({
  canRoam: true,
  roamRadius: 11,
  roamMinDistance: 4.5,
  roamDelay: 0.9,
  roamSpeed: 0.62,
  chaseSpeed: 1.45,
  sightRadius: 11,
  loseSightRadius: 13.5,
  sightMemory: 4,
  afterChaseDelay: 1.8,
  attackRange: 1.65,
  dyingAnimationLength: 3,
  destroyDelay: 5,
});

const point = (source) => ({ x: Number(source?.x) || 0, y: Number(source?.y) || 0, z: Number(source?.z) || 0 });

function transition(brain, nextState) {
  if (brain.state === nextState) return;
  brain.previousState = brain.state;
  brain.state = nextState;
  brain.stateElapsed = 0;
}

function distance2D(left, right) {
  return Math.hypot(right.x - left.x, right.z - left.z);
}

function chooseRoamTarget(brain, position, canOccupy) {
  for (let attempt = 0; attempt < 14; attempt += 1) {
    const angle = brain.random() * Math.PI * 2;
    const radiusRange = Math.max(0, brain.config.roamRadius - brain.config.roamMinDistance);
    const radius = brain.config.roamMinDistance + Math.sqrt(brain.random()) * radiusRange;
    const candidate = {
      x: position.x + Math.cos(angle) * radius,
      y: position.y,
      z: position.z + Math.sin(angle) * radius,
    };
    if (canOccupy(candidate, 0.9)) return candidate;
  }
  return null;
}

function actionFor(brain) {
  const target = brain.state === ZOMBIE_STATES.ROAM
    ? brain.roamTarget
    : brain.state === ZOMBIE_STATES.CHASE ? brain.lastSeenPosition : null;
  const speed = brain.state === ZOMBIE_STATES.ROAM
    ? brain.config.roamSpeed
    : brain.state === ZOMBIE_STATES.CHASE ? brain.config.chaseSpeed : 0;
  return Object.freeze({
    state: brain.state,
    target: target ? point(target) : null,
    speed,
    shouldAttack: brain.state === ZOMBIE_STATES.ATTACK,
    shouldHide: brain.hidden,
  });
}

export function createZombieBrain({ id, position, config = {}, random = Math.random }) {
  const spawn = point(position);
  return {
    id: String(id ?? 'zombie'),
    state: ZOMBIE_STATES.IDLE,
    previousState: ZOMBIE_STATES.IDLE,
    stateElapsed: 0,
    spawn,
    roamOrigin: point(spawn),
    roamTarget: null,
    roamWait: 0,
    lastSeenPosition: null,
    memoryRemaining: 0,
    afterChaseRemaining: 0,
    deathElapsed: 0,
    hidden: false,
    config: Object.freeze({ ...DEFAULT_ZOMBIE_AI_CONFIG, ...config }),
    random,
  };
}

export function alertZombie(brain, playerPosition) {
  if (!brain || brain.state === ZOMBIE_STATES.DEAD) return;
  brain.lastSeenPosition = point(playerPosition);
  brain.memoryRemaining = brain.config.sightMemory;
  brain.afterChaseRemaining = 0;
  transition(brain, ZOMBIE_STATES.CHASE);
}

export function updateZombieBrain(brain, observation, delta) {
  const dt = Math.min(0.1, Math.max(0, Number(delta) || 0));
  const position = point(observation.position);
  const playerPosition = point(observation.playerPosition);
  const canOccupy = observation.canOccupy ?? (() => true);
  brain.stateElapsed += dt;

  if (Number(observation.health) <= 0) {
    transition(brain, ZOMBIE_STATES.DEAD);
    brain.deathElapsed += dt;
    const hideAfter = brain.config.destroyDelay < 0
      ? Number.POSITIVE_INFINITY
      : brain.config.dyingAnimationLength + brain.config.destroyDelay;
    brain.hidden = brain.deathElapsed >= hideAfter;
    return actionFor(brain);
  }

  const distance = distance2D(position, playerPosition);
  const retainingTarget = brain.state === ZOMBIE_STATES.CHASE || brain.state === ZOMBIE_STATES.ATTACK;
  const sightRadius = retainingTarget ? brain.config.loseSightRadius : brain.config.sightRadius;
  const seesPlayer = observation.hasLineOfSight !== false && distance <= sightRadius;

  if (seesPlayer) {
    brain.lastSeenPosition = point(playerPosition);
    brain.memoryRemaining = brain.config.sightMemory;
    brain.afterChaseRemaining = 0;
    brain.roamTarget = null;
    transition(brain, distance <= brain.config.attackRange ? ZOMBIE_STATES.ATTACK : ZOMBIE_STATES.CHASE);
    return actionFor(brain);
  }

  if (brain.memoryRemaining > 0 && brain.lastSeenPosition) {
    brain.memoryRemaining = Math.max(0, brain.memoryRemaining - dt);
    transition(brain, ZOMBIE_STATES.CHASE);
    return actionFor(brain);
  }

  if (brain.state === ZOMBIE_STATES.CHASE || brain.state === ZOMBIE_STATES.ATTACK) {
    brain.roamOrigin = point(position);
    brain.lastSeenPosition = null;
    brain.afterChaseRemaining = brain.config.afterChaseDelay;
    brain.roamTarget = null;
    transition(brain, ZOMBIE_STATES.IDLE);
  }

  if (brain.afterChaseRemaining > 0) {
    brain.afterChaseRemaining = Math.max(0, brain.afterChaseRemaining - dt);
    transition(brain, ZOMBIE_STATES.IDLE);
    return actionFor(brain);
  }

  if (!brain.config.canRoam) {
    transition(brain, ZOMBIE_STATES.IDLE);
    return actionFor(brain);
  }

  if (observation.movementBlocked && brain.roamTarget) {
    brain.roamOrigin = point(position);
    brain.roamTarget = null;
    brain.roamWait = Math.min(0.25, brain.config.roamDelay);
  }

  if (brain.roamTarget && distance2D(position, brain.roamTarget) <= 0.45) {
    brain.roamOrigin = point(position);
    brain.roamTarget = null;
    brain.roamWait = brain.config.roamDelay * (0.55 + brain.random() * 0.45);
    transition(brain, ZOMBIE_STATES.IDLE);
  }

  if (brain.roamWait > 0) {
    brain.roamWait = Math.max(0, brain.roamWait - dt);
    transition(brain, ZOMBIE_STATES.IDLE);
    return actionFor(brain);
  }

  if (!brain.roamTarget) brain.roamTarget = chooseRoamTarget(brain, position, canOccupy);
  if (brain.roamTarget) transition(brain, ZOMBIE_STATES.ROAM);
  else {
    brain.roamWait = Math.min(0.8, brain.config.roamDelay);
    transition(brain, ZOMBIE_STATES.IDLE);
  }
  return actionFor(brain);
}

export function chooseSteeredStep({ position, target, maxStep, canMoveTo, radius = 0.9 }) {
  const dx = target.x - position.x;
  const dz = target.z - position.z;
  const distance = Math.hypot(dx, dz);
  if (distance <= 1e-6 || maxStep <= 0) return null;
  const step = Math.min(distance, maxStep);
  const heading = Math.atan2(dz, dx);
  for (const offset of [0, Math.PI / 5, -Math.PI / 5, Math.PI / 2.8, -Math.PI / 2.8, Math.PI / 2, -Math.PI / 2]) {
    const angle = heading + offset;
    const candidate = {
      x: position.x + Math.cos(angle) * step,
      y: position.y,
      z: position.z + Math.sin(angle) * step,
    };
    if (canMoveTo(candidate, radius)) return candidate;
  }
  return null;
}
