export function pickClipName(availableNames, preferredNames) {
  for (const preferred of preferredNames) {
    if (availableNames.includes(preferred)) return preferred;
  }
  if (availableNames.includes('idle')) return 'idle';
  return availableNames[0] ?? null;
}

export class AnimationController {
  constructor(mixer, clips) {
    this.mixer = mixer;
    this.clips = new Map(clips.map((clip) => [clip.name, clip]));
    this.current = null;
  }

  play(preferredNames, { once = false, fade = 0.16 } = {}) {
    const name = pickClipName([...this.clips.keys()], preferredNames);
    if (!name || (this.current?.name === name && !once)) return this.current;

    const next = this.mixer.clipAction(this.clips.get(name));
    if (this.current && this.current !== next) this.current.fadeOut(fade);
    next.reset().fadeIn(fade);
    next.clampWhenFinished = once;
    if (once) next.setLoop(2200, 1);
    next.play();
    next.name = name;
    this.current = next;
    return next;
  }

  update(delta) {
    this.mixer.update(delta);
  }
}

