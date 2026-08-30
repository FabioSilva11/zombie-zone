export function createHybridAim({ transitionSeconds = 0.18 } = {}) {
  let blend = 0;
  let target = 0;
  let state = 'third-person';
  const speed = 1 / transitionSeconds;
  return {
    get blend() { return blend; },
    get state() { return state; },
    get readyToFire() { return target === 1 && blend >= 0.98; },
    press() { target = 1; state = blend >= 0.98 ? 'aimed-third-person' : 'entering-aimed-third-person'; },
    release() { target = 0; state = blend <= 0.02 ? 'third-person' : 'returning-third-person'; },
    cancel() { target = 0; state = blend <= 0.02 ? 'third-person' : 'returning-third-person'; },
    update(delta) {
      const step = Math.max(0, delta) * speed;
      blend = target > blend ? Math.min(target, blend + step) : Math.max(target, blend - step);
      if (Math.abs(target - blend) < 1e-12) blend = target;
      if (blend >= 1) state = 'aimed-third-person';
      else if (blend <= 0) state = 'third-person';
      return blend;
    },
  };
}
