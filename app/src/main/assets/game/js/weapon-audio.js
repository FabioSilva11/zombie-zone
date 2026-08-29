export function createWeaponAudio({
  fireUrl,
  reloadUrl,
  enabled = true,
  volume = 1,
  poolSize = 4,
  audioFactory = (url) => new Audio(url),
}) {
  const requestedPoolSize = Number(poolSize);
  const normalizedPoolSize = Number.isFinite(requestedPoolSize)
    ? Math.max(1, Math.floor(requestedPoolSize))
    : 4;
  const fireTracks = Array.from({ length: normalizedPoolSize }, () => audioFactory(fireUrl));
  const reloadTrack = audioFactory(reloadUrl);
  const tracks = [...fireTracks, reloadTrack];
  let fireIndex = 0;
  let soundEnabled = Boolean(enabled);
  let disposed = false;

  for (const track of fireTracks) {
    track.preload = 'auto';
    track.volume = volume;
  }
  reloadTrack.preload = 'auto';
  reloadTrack.volume = volume * 0.8;

  function pauseAll() {
    for (const track of tracks) track.pause();
  }

  function play(track) {
    if (disposed || !soundEnabled) return Promise.resolve(false);
    track.currentTime = 0;
    try {
      return Promise.resolve(track.play()).then(() => true).catch(() => false);
    } catch {
      return Promise.resolve(false);
    }
  }

  return {
    playFire() {
      const track = fireTracks[fireIndex];
      fireIndex = (fireIndex + 1) % fireTracks.length;
      return play(track);
    },
    playReload() {
      return play(reloadTrack);
    },
    setEnabled(value) {
      soundEnabled = Boolean(value);
      if (!soundEnabled) pauseAll();
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      pauseAll();
      for (const track of tracks) {
        track.removeAttribute?.('src');
        track.load?.();
      }
    },
  };
}
