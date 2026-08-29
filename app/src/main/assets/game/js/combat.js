import { canMeleeHit, canRangedHit } from './game-rules.js';
import { combatYawFromDirection, modelYawFromDirection } from './movement-heading.js';
import {
  alertZombie,
  chooseSteeredStep,
  createZombieBrain,
  updateZombieBrain,
  ZOMBIE_STATES,
} from './zombie-ai.js';

export class CombatSystem {
  constructor({ player, enemies, canMoveTo, hasLineOfSight, onHudChange, onGateOpen, onPlayerDefeated }) {
    this.player = player;
    this.enemies = enemies;
    this.canMoveTo = canMoveTo ?? (() => true);
    this.hasLineOfSight = hasLineOfSight ?? (() => true);
    this.onHudChange = onHudChange;
    this.onGateOpen = onGateOpen;
    this.onPlayerDefeated = onPlayerDefeated;
    this.playerAttackCooldown = 0;
    this.gateAnnounced = false;
    this.enemies.forEach((enemy, index) => {
      enemy.ai = createZombieBrain({
        id: enemy.id,
        position: enemy.root.position,
        config: {
          roamDelay: 1.6 + (index % 3) * 0.45,
          roamSpeed: enemy.roamSpeed ?? 0.62,
          chaseSpeed: enemy.chaseSpeed ?? 1.45,
        },
      });
    });
  }

  attack() {
    if (this.playerAttackCooldown > 0 || this.player.health <= 0) return false;
    this.playerAttackCooldown = 0.62;
    this.player.animations.play(['attack-melee-right', 'attack-melee-left', 'idle'], { once: true, fade: 0.08 });

    for (const enemy of this.enemies) {
      if (enemy.health <= 0) continue;
      const attacker = {
        x: this.player.root.position.x,
        z: this.player.root.position.z,
        yaw: this.player.yaw,
      };
      const target = { x: enemy.root.position.x, z: enemy.root.position.z };
      if (!canMeleeHit(attacker, target)) continue;
      enemy.health = Math.max(0, enemy.health - 35);
      if (enemy.health === 0) {
        enemy.animations.play(['die', 'idle'], { once: true, fade: 0.1 });
      }
    }
    this.notify();
    return true;
  }

  fire(target) {
    if (this.playerAttackCooldown > 0 || this.player.health <= 0) {
      return { fired: false, hit: false, defeated: false, targetId: null };
    }
    this.playerAttackCooldown = 0.45;
    this.player.animations.play(['attack-melee-right', 'attack-melee-left', 'idle'], { once: true, fade: 0.06 });
    if (!target) return { fired: true, hit: false, defeated: false, targetId: null };
    const distance = Math.hypot(
      target.root.position.x - this.player.root.position.x,
      target.root.position.y - this.player.root.position.y,
      target.root.position.z - this.player.root.position.z,
    );
    if (!canRangedHit({ health: target.health, distance })) {
      return { fired: true, hit: false, defeated: false, targetId: target.id ?? null };
    }
    target.health = Math.max(0, target.health - Math.round(28 * (this.player.powerMultiplier ?? 1)));
    if (target.health > 0) alertZombie(target.ai, this.player.root.position);
    const defeated = target.health === 0;
    if (defeated) target.animations.play(['die', 'idle'], { once: true, fade: 0.1 });
    this.notify();
    return { fired: true, hit: true, defeated, targetId: target.id ?? null };
  }

  applyRifleHit(target, { bodyDamage = 18, headshot = false, headshotMultiplier = 1.75 } = {}) {
    if (!target || target.health <= 0 || this.player.health <= 0) {
      return { hit: false, defeated: false, targetId: target?.id ?? null, damage: 0 };
    }
    const damage = Math.round(bodyDamage * (this.player.powerMultiplier ?? 1) * (headshot ? headshotMultiplier : 1));
    target.health = Math.max(0, target.health - damage);
    if (target.health > 0) alertZombie(target.ai, this.player.root.position);
    const defeated = target.health === 0;
    if (defeated) target.animations.play(['die', 'idle'], { once: true, fade: 0.1 });
    this.notify();
    return { hit: true, defeated, targetId: target.id ?? null, damage };
  }

  update(delta) {
    this.playerAttackCooldown = Math.max(0, this.playerAttackCooldown - delta);
    const playerPosition = this.player.root.position;

    for (const enemy of this.enemies) {
      enemy.attackCooldown = Math.max(0, enemy.attackCooldown - delta);
      const action = updateZombieBrain(enemy.ai, {
        health: enemy.health,
        position: enemy.root.position,
        playerPosition,
        hasLineOfSight: this.hasLineOfSight(enemy.root.position, playerPosition),
        canOccupy: this.canMoveTo,
      }, delta);
      enemy.state = action.state;
      if (action.state === ZOMBIE_STATES.DEAD) {
        if (action.shouldHide) enemy.root.visible = false;
        continue;
      }

      if (action.target) {
        const dx = action.target.x - enemy.root.position.x;
        const dz = action.target.z - enemy.root.position.z;
        enemy.yaw = combatYawFromDirection(dx, dz, enemy.yaw);
        enemy.root.rotation.y = modelYawFromDirection(dx, dz, enemy.root.rotation.y);
      }

      if (action.state === ZOMBIE_STATES.CHASE || action.state === ZOMBIE_STATES.ROAM) {
        const next = chooseSteeredStep({
          position: enemy.root.position,
          target: action.target,
          maxStep: action.speed * delta,
          canMoveTo: this.canMoveTo,
        });
        if (next) {
          enemy.root.position.x = next.x;
          enemy.root.position.z = next.z;
          enemy.animations.play(action.state === ZOMBIE_STATES.CHASE
            ? ['sprint', 'walk', 'idle']
            : ['walk', 'idle']);
        } else enemy.animations.play(['idle', 'static']);
      } else if (action.state === ZOMBIE_STATES.ATTACK) {
        if (enemy.attackCooldown <= 0) {
          enemy.attackCooldown = 1.15;
          enemy.animations.play(['attack-melee-left', 'attack-melee-right', 'idle'], { once: true, fade: 0.08 });
          this.player.health = Math.max(0, this.player.health - 8);
          this.notify();
          if (this.player.health === 0) {
            this.player.animations.play(['die', 'idle'], { once: true, fade: 0.1 });
            this.onPlayerDefeated();
          }
        }
      } else {
        enemy.animations.play(['idle', 'static']);
      }
    }

    if (!this.gateAnnounced && this.enemies.every((enemy) => enemy.health <= 0)) {
      this.gateAnnounced = true;
      this.onGateOpen();
      this.notify();
    }
  }

  notify() {
    this.onHudChange({
      playerHealth: this.player.health,
      enemiesRemaining: this.enemies.filter((enemy) => enemy.health > 0).length,
      gateOpen: this.gateAnnounced,
    });
  }
}
