export function createWeaponState(capacity = 6, reloadSeconds = 1.15) {
  let ammo = capacity;
  let reloadRemaining = 0;
  return {
    get ammo() { return ammo; },
    get capacity() { return capacity; },
    get reloading() { return reloadRemaining > 0; },
    consumeShot() {
      if (reloadRemaining > 0 || ammo <= 0) return false;
      ammo -= 1;
      return true;
    },
    startReload() {
      if (reloadRemaining > 0 || ammo >= capacity) return false;
      reloadRemaining = reloadSeconds;
      return true;
    },
    update(delta) {
      if (reloadRemaining <= 0) return false;
      reloadRemaining = Math.max(0, reloadRemaining - Math.max(0, delta));
      if (reloadRemaining === 0) ammo = capacity;
      return true;
    },
  };
}
