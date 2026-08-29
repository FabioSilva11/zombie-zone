import test from 'node:test';
import assert from 'node:assert/strict';
import { CombatSystem } from '../app/src/main/assets/game/js/combat.js';

const rootAt = (x, z) => ({ position: { x, y: 0, z }, rotation: { y: 0 }, visible: true });
const animations = () => ({ calls: [], play(names) { this.calls.push(names); } });

function fixture({ enemyX = 5, lineOfSight = true } = {}) {
  const player = { root: rootAt(0, 0), health: 100, yaw: 0, animations: animations() };
  const enemy = {
    id: 'enemy-1', root: rootAt(enemyX, 0), health: 70, yaw: 0,
    roamSpeed: 0.5, chaseSpeed: 1.5, attackCooldown: 0, animations: animations(),
  };
  const combat = new CombatSystem({
    player, enemies: [enemy], canMoveTo: () => true, hasLineOfSight: () => lineOfSight,
    onHudChange() {}, onGateOpen() {}, onPlayerDefeated() {},
  });
  return { combat, player, enemy };
}

test('CombatSystem usa percepção para perseguir e mover o zumbi', () => {
  const { combat, enemy } = fixture();
  combat.update(0.1);
  assert.equal(enemy.state, 'chase');
  assert.ok(enemy.root.position.x < 5);
  assert.ok(enemy.animations.calls.some((names) => names.includes('sprint')));
});

test('zumbi atrás da parede não entra em perseguição apenas pela distância', () => {
  const { combat, enemy } = fixture({ lineOfSight: false });
  combat.update(0.1);
  assert.notEqual(enemy.state, 'chase');
  assert.notEqual(enemy.state, 'attack');
});

test('sobreposição de ataque aplica dano respeitando o cooldown', () => {
  const { combat, player, enemy } = fixture({ enemyX: 1.2 });
  combat.update(0.1);
  assert.equal(enemy.state, 'attack');
  assert.equal(player.health, 92);
  combat.update(0.1);
  assert.equal(player.health, 92);
});
