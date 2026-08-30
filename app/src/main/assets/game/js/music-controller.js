export const MUSIC_MODES = Object.freeze({ MENU: 'menu', GAMEPLAY: 'gameplay' });

export function createMusicController({
  menuUrl,
  gameplayUrl,
  enabled = true,
  volume = 0.34,
  audioFactory = (url) => new Audio(url),
}) {
  const tracks = {
    menu: audioFactory(menuUrl),
    gameplay: audioFactory(gameplayUrl),
  };
  let mode = MUSIC_MODES.MENU;
  let soundEnabled = Boolean(enabled);
  let appActive = true;
  let disposed = false;

  for (const track of Object.values(tracks)) {
    track.loop = true;
    track.preload = 'auto';
    track.volume = volume;
  }

  function pauseAll(except = null) {
    for (const track of Object.values(tracks)) {
      if (track !== except) track.pause();
    }
  }

  function sync() {
    if (disposed || !soundEnabled || !appActive) {
      pauseAll();
      return Promise.resolve(false);
    }
    const target = tracks[mode];
    pauseAll(target);
    if (target.paused === false) return Promise.resolve(true);
    try {
      return Promise.resolve(target.play()).then(() => true, () => false);
    } catch {
      return Promise.resolve(false);
    }
  }

  function onUserActivation() {
    sync();
  }

  return {
    setMode(nextMode) {
      if (!Object.hasOwn(tracks, nextMode)) throw new Error(`Modo de música desconhecido: ${nextMode}`);
      mode = nextMode;
      return sync();
    },
    setEnabled(nextEnabled) {
      soundEnabled = Boolean(nextEnabled);
      return sync();
    },
    setAppActive(nextActive) {
      appActive = Boolean(nextActive);
      return sync();
    },
    bindUserActivation(target) {
      target.addEventListener('pointerdown', onUserActivation, { passive: true });
      target.addEventListener('keydown', onUserActivation);
      return () => {
        target.removeEventListener('pointerdown', onUserActivation);
        target.removeEventListener('keydown', onUserActivation);
      };
    },
    state() { return { mode, enabled: soundEnabled, appActive }; },
    dispose() {
      disposed = true;
      pauseAll();
      for (const track of Object.values(tracks)) {
        track.removeAttribute?.('src');
        track.load?.();
      }
    },
  };
}
