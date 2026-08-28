import { canMeleeHit, clampToArena, nextEnemyState } from './game-rules.js';

export class CombatSystem {
  constructor({ player, enemies, onHudChange, onGateOpen, onPlayerDefeated }) {
    this.player = player;
    this.enemies = enemies;
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
      enemy.yaw = Math.atan2(dx, -dz);
      enemy.root.rotation.y = enemy.yaw + Math.PI;

      if (state === 'chase') {
        const next = clampToArena({
          x: enemy.root.position.x + (dx / distance) * enemy.speed * delta,
          z: enemy.root.position.z + (dz / distance) * enemy.speed * delta,
        });
        enemy.root.position.x = next.x;
        enemy.root.position.z = next.z;
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
