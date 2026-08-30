export function normalizeProgress(label, percent) {
  return {
    label: label?.trim() || 'Preparando aventura',
    percent: Math.min(100, Math.max(0, Math.round(Number(percent) || 0))),
  };
}

export function normalizeStory(story) {
  if (!story || typeof story !== 'object') return { speaker: '', lines: [] };
  return {
    speaker: typeof story.speaker === 'string' ? story.speaker.trim() : '',
    lines: (Array.isArray(story.lines) ? story.lines : [])
      .filter((line) => typeof line === 'string' && line.trim())
      .map((line) => line.trim())
      .slice(0, 3),
  };
}

export function createLoadingController(root) {
  const labelElement = root.querySelector('[data-loading-label]');
  const fillElement = root.querySelector('[data-loading-fill]');
  const percentElement = root.querySelector('[data-loading-percent]');
  const retryButton = root.querySelector('#retry-button');
  const storyElement = root.querySelector('[data-loading-story]');
  const storySpeaker = root.querySelector('[data-loading-speaker]');
  const storyLines = root.querySelector('[data-loading-story-lines]');

  return {
    update(label, percent) {
      const state = normalizeProgress(label, percent);
      labelElement.textContent = state.label;
      percentElement.textContent = `${state.percent}%`;
      fillElement.style.width = `${state.percent}%`;
      retryButton.hidden = true;
    },
    setStory(story) {
      const state = normalizeStory(story);
      if (storySpeaker) storySpeaker.textContent = state.speaker;
      if (storyLines) storyLines.replaceChildren(...state.lines.map((line) => Object.assign(document.createElement('p'), { textContent: line })));
      storyElement?.classList.toggle('is-hidden', state.lines.length === 0);
      return state;
    },
    showError(message) {
      labelElement.textContent = message;
      percentElement.textContent = '!';
      fillElement.style.width = '100%';
      retryButton.hidden = false;
    },
    hide() {
      root.classList.add('is-hidden');
    },
    show() {
      root.classList.remove('is-hidden');
    },
  };
}
