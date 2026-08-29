import { canMeleeHit, canRangedHit, nextEnemyState } from './game-rules.js';
import { combatYawFromDirection, modelYawFromDirection } from './movement-heading.js';

export class CombatSystem {
  constructor({ player, enemies, canMoveTo, onHudChange, onGateOpen, onPlayerDefeated }) {
    this.player = player;
    this.enemies = enemies;
    this.canMoveTo = canMoveTo ?? (() => true);
    this.onHudChange = onHudChange;
    this.onGateOpen = onGateOpen;
    this.onPlayerDefeated = onPlayerDefeated;
    this.playerAttackCooldown = 0;
    this.gateAnnounced = false;
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
    const defeated = target.health === 0;
    if (defeated) target.animations.play(['die', 'idle'], { once: true, fade: 0.1 });
    this.notify();
    return { fired: true, hit: true, defeated, targetId: target.id ?? null };
  }

  update(delta) {
    this.playerAttackCooldown = Math.max(0, this.playerAttackCooldown - delta);
    const playerPosition = this.player.root.position;

    for (const enemy of this.enemies) {
      enemy.attackCooldown = Math.max(0, enemy.attackCooldown - delta);
      const state = nextEnemyState(
        { health: enemy.health, x: enemy.root.position.x, z: enemy.root.position.z },
        { x: playerPosition.x, z: playerPosition.z },
      );
      if (state === 'dead') continue;

      const dx = playerPosition.x - enemy.root.position.x;
      const dz = playerPosition.z - enemy.root.position.z;
      const distance = Math.hypot(dx, dz) || 1;
      enemy.yaw = combatYawFromDirection(dx, dz, enemy.yaw);
      enemy.root.rotation.y = modelYawFromDirection(dx, dz, enemy.root.rotation.y);

      if (state === 'chase') {
        const next = {
          x: enemy.root.position.x + (dx / distance) * enemy.speed * delta,
          y: enemy.root.position.y,
          z: enemy.root.position.z + (dz / distance) * enemy.speed * delta,
        };
        if (this.canMoveTo(next, 1.2)) {
          enemy.root.position.x = next.x;
          enemy.root.position.z = next.z;
        } else {
          enemy.animations.play(['idle', 'static']);
        }
        enemy.animations.play(['walk', 'sprint', 'idle']);
      } else if (state === 'attack') {
        enemy.animations.play(['attack-melee-left', 'attack-melee-right', 'idle'], { once: true, fade: 0.08 });
        if (enemy.attackCooldown <= 0) {
          enemy.attackCooldown = 1.15;
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
