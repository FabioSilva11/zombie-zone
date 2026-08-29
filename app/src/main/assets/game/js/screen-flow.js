const SCREENS = new Set([
  'loading', 'home', 'avatar', 'chapter', 'story', 'preparation',
  'gameplay', 'victory', 'defeat', 'compatibility',
]);
const OVERLAYS = new Set(['pause', 'map', 'settings']);

export function createScreenFlow(progress) {
  const listeners = new Set();
  const flow = {
    state: {
      screen: progress.hasStarted && progress.selectedAvatar ? 'home' : 'avatar',
      overlay: null,
    },
    go(screen, payload = {}) {
      if (!SCREENS.has(screen)) throw new Error(`Tela desconhecida: ${screen}`);
      this.state = { screen, overlay: null, ...payload };
      for (const listener of listeners) listener(this.state);
      return this.state;
    },
    openOverlay(overlay) {
      if (!OVERLAYS.has(overlay)) throw new Error(`Overlay desconhecido: ${overlay}`);
      this.state = { ...this.state, overlay };
      for (const listener of listeners) listener(this.state);
      return this.state;
    },
    pause() { return this.openOverlay('pause'); },
    resume() {
      this.state = { ...this.state, overlay: null };
      for (const listener of listeners) listener(this.state);
      return this.state;
    },
    subscribe(listener) {
      listeners.add(listener);
      listener(this.state);
      return () => listeners.delete(listener);
    },
  };
  return flow;
}
