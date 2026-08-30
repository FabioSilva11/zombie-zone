import test from 'node:test';
import assert from 'node:assert/strict';
import {
  alertZombie,
  chooseSteeredStep,
  createZombieBrain,
  updateZombieBrain,
  ZOMBIE_STATES,
} from '../app/src/main/assets/game/js/zombie-ai.js';

const position = (x, z, y = 0) => ({ x, y, z });

test('zumbi escolhe um destino exploratório quando não percebe o jogador', () => {
  const brain = createZombieBrain({ id: 'z1', position: position(4, 7), random: () => 0.25 });
  const action = updateZombieBrain(brain, {
    health: 70,
    position: position(4, 7),
    playerPosition: position(40, 40),
    hasLineOfSight: false,
    canOccupy: () => true,
  }, 0.05);
  assert.equal(action.state, ZOMBIE_STATES.ROAM);
  assert.ok(Math.hypot(action.target.x - 4, action.target.z - 7) <= brain.config.roamRadius);
  assert.ok(Math.hypot(action.target.x - 4, action.target.z - 7) >= brain.config.roamMinDistance);
});

test('destinos sucessivos avançam a exploração em vez de prender o zumbi ao nascimento', () => {
  const randomValues = [0, 1, 0, 1];
  const brain = createZombieBrain({
    id: 'explorer', position: position(0, 0),
    config: { roamRadius: 10, roamMinDistance: 10, roamDelay: 0 },
    random: () => randomValues.shift() ?? 0,
  });
  let action = updateZombieBrain(brain, {
    health: 70, position: position(0, 0), playerPosition: position(100, 100),
    hasLineOfSight: false, canOccupy: () => true,
  }, 0.05);
  assert.deepEqual(action.target, position(10, 0));
  action = updateZombieBrain(brain, {
    health: 70, position: position(10, 0), playerPosition: position(100, 100),
    hasLineOfSight: false, canOccupy: () => true,
  }, 0.05);
  assert.equal(brain.roamOrigin.x, 10);
  assert.ok(action.target.x > 10, 'o próximo destino deve partir da posição alcançada');
});

test('zumbi abandona destino bloqueado e escolhe uma nova rota', () => {
  const brain = createZombieBrain({ id: 'blocked', position: position(2, 2), random: () => 0 });
  brain.roamTarget = position(9, 2);
  brain.state = ZOMBIE_STATES.ROAM;
  const action = updateZombieBrain(brain, {
    health: 70, position: position(2, 2), playerPosition: position(100, 100),
    hasLineOfSight: false, canOccupy: () => true, movementBlocked: true,
  }, 0.05);
  assert.equal(action.state, ZOMBIE_STATES.IDLE);
  assert.equal(action.target, null);
});

test('visão alterna entre perseguição e ataque usando o raio de dano', () => {
  const brain = createZombieBrain({ id: 'z2', position: position(0, 0) });
  let action = updateZombieBrain(brain, {
    health: 70, position: position(0, 0), playerPosition: position(5, 0), hasLineOfSight: true,
  }, 0.05);
  assert.equal(action.state, ZOMBIE_STATES.CHASE);
  action = updateZombieBrain(brain, {
    health: 70, position: position(0, 0), playerPosition: position(1.4, 0), hasLineOfSight: true,
  }, 0.05);
  assert.equal(action.state, ZOMBIE_STATES.ATTACK);
  assert.equal(action.shouldAttack, true);
});

test('ao perder a visão o zumbi lembra a última posição e só depois volta à patrulha', () => {
  const brain = createZombieBrain({
    id: 'z3', position: position(0, 0), random: () => 0.5,
    config: { sightMemory: 0.15, afterChaseDelay: 0.1 },
  });
  updateZombieBrain(brain, {
    health: 70, position: position(0, 0), playerPosition: position(5, 0), hasLineOfSight: true,
  }, 0.05);
  let action = updateZombieBrain(brain, {
    health: 70, position: position(1, 0), playerPosition: position(9, 0), hasLineOfSight: false,
  }, 0.1);
  assert.equal(action.state, ZOMBIE_STATES.CHASE);
  assert.deepEqual(action.target, position(5, 0));
  for (let index = 0; index < 4; index += 1) {
    action = updateZombieBrain(brain, {
      health: 70, position: position(1, 0), playerPosition: position(9, 0), hasLineOfSight: false,
      canOccupy: () => true,
    }, 0.1);
  }
  assert.ok([ZOMBIE_STATES.IDLE, ZOMBIE_STATES.ROAM].includes(action.state));
  assert.deepEqual(brain.roamOrigin, position(1, 0));
});

test('um tiro alerta o zumbi mesmo antes de ele adquirir visão', () => {
  const brain = createZombieBrain({ id: 'z4', position: position(0, 0) });
  alertZombie(brain, position(8, 3));
  assert.equal(brain.state, ZOMBIE_STATES.CHASE);
  assert.deepEqual(brain.lastSeenPosition, position(8, 3));
});

test('morte desliga a IA e oculta o corpo somente após a animação e atraso', () => {
  const brain = createZombieBrain({
    id: 'z5', position: position(0, 0), config: { dyingAnimationLength: 0.2, destroyDelay: 0.3 },
  });
  let action;
  for (let index = 0; index < 4; index += 1) {
    action = updateZombieBrain(brain, {
      health: 0, position: position(0, 0), playerPosition: position(1, 0), hasLineOfSight: true,
    }, 0.1);
  }
  assert.equal(action.state, ZOMBIE_STATES.DEAD);
  assert.equal(action.shouldHide, false);
  action = updateZombieBrain(brain, {
    health: 0, position: position(0, 0), playerPosition: position(1, 0), hasLineOfSight: true,
  }, 0.1);
  assert.equal(action.shouldHide, true);
});

test('desvio tenta direções laterais quando o passo direto encontra uma parede', () => {
  const next = chooseSteeredStep({
    position: position(0, 0), target: position(5, 0), maxStep: 1,
    canMoveTo: (candidate) => Math.abs(candidate.z) > 0.2,
  });
  assert.ok(next);
  assert.ok(next.x > 0);
  assert.ok(Math.abs(next.z) > 0.2);
});
