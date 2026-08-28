export function normalizeProgress(label, percent) {
  return {
    label: label?.trim() || 'Preparando aventura',
    percent: Math.min(100, Math.max(0, Math.round(Number(percent) || 0))),
  };
}

export function createLoadingController(root) {
  const labelElement = root.querySelector('[data-loading-label]');
  const fillElement = root.querySelector('[data-loading-fill]');
  const percentElement = root.querySelector('[data-loading-percent]');
  const retryButton = root.querySelector('#retry-button');

  return {
    update(label, percent) {
      const state = normalizeProgress(label, percent);
      labelElement.textContent = state.label;
      percentElement.textContent = `${state.percent}%`;
      fillElement.style.width = `${state.percent}%`;
      retryButton.hidden = true;
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

