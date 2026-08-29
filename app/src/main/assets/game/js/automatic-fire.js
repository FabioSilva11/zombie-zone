export function createAutomaticFire({
  roundsPerMinute = 600,
  minSpread = 0.002,
  maxSpread = 0.026,
  spreadPerShot = 0.003,
  recoverPerSecond = 0.04,
} = {}) {
  const interval = 60 / roundsPerMinute;
  let held = false;
  let untilNext = 0;
  let spread = minSpread;

  return {
    get held() { return held; },
    get spread() { return spread; },
    press() { held = true; untilNext = 0; },
    release() { held = false; untilNext = 0; },
    cancel() { held = false; untilNext = 0; },
    update(delta, { ready = false, fire = () => false } = {}) {
      const elapsed = Math.max(0, delta);
      if (!held || !ready) {
        spread = Math.max(minSpread, spread - recoverPerSecond * elapsed);
        return 0;
      }
      untilNext -= elapsed;
      let fired = 0;
      while (untilNext <= 1e-9 && fired < 4) {
        if (!fire(spread)) { untilNext = interval; break; }
        spread = Math.min(maxSpread, spread + spreadPerShot);
        untilNext += interval;
        fired += 1;
      }
      return fired;
    },
    dispose() { held = false; untilNext = 0; },
  };
}
