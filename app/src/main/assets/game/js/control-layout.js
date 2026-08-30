const CONTROL_IDS = Object.freeze(['joystick', 'reload', 'sprint', 'dodge', 'crouch', 'jump', 'aim', 'shoot']);

export const DEFAULT_CONTROL_LAYOUT = Object.freeze({
  joystick: Object.freeze({ x: 10, y: 78, size: 1, visible: true }),
  reload: Object.freeze({ x: 72, y: 91, size: 1, visible: true }),
  sprint: Object.freeze({ x: 66, y: 77, size: 1, visible: true }),
  dodge: Object.freeze({ x: 78, y: 77, size: 1, visible: true }),
  crouch: Object.freeze({ x: 73, y: 61, size: 1, visible: true }),
  jump: Object.freeze({ x: 84, y: 66, size: 1, visible: true }),
  aim: Object.freeze({ x: 90, y: 49, size: 1, visible: true }),
  shoot: Object.freeze({ x: 94, y: 79, size: 1, visible: true }),
});

const clamp = (value, min = 4, max = 96) => Math.min(max, Math.max(min, Number(value)));
const clampSize = (value) => clamp(value, 0.55, 1.8);

export function normalizeControlLayout(source) {
  return Object.fromEntries(CONTROL_IDS.map((id) => {
    const fallback = DEFAULT_CONTROL_LAYOUT[id];
    const candidate = source?.[id];
    const x = Number.isFinite(Number(candidate?.x)) ? clamp(candidate.x) : fallback.x;
    const y = Number.isFinite(Number(candidate?.y)) ? clamp(candidate.y) : fallback.y;
    const size = Number.isFinite(Number(candidate?.size)) ? clampSize(candidate.size) : fallback.size;
    const visible = candidate?.visible !== false;
    return [id, { x: Number(x.toFixed(2)), y: Number(y.toFixed(2)), size: Number(size.toFixed(2)), visible }];
  }));
}

export function applyControlLayout(hud, source) {
  const layout = normalizeControlLayout(source);
  hud.classList.add('layout-customized');
  for (const [id, position] of Object.entries(layout)) {
    const control = hud.querySelector(`[data-control-id="${id}"]`);
    if (!control) continue;
    control.style.setProperty('--control-x', `${position.x}%`);
    control.style.setProperty('--control-y', `${position.y}%`);
    control.style.setProperty('--control-scale', String(position.size));
    control.classList.toggle('is-control-hidden', !position.visible);
  }
  return layout;
}

export function mountControlLayoutEditor({ root, onSave, onClose = () => {} }) {
  const stage = root.querySelector('[data-controls-editor-stage]');
  const controls = [...root.querySelectorAll('[data-layout-control]')];
  const sizeInput = root.querySelector('[data-control-size]');
  const sizeOutput = root.querySelector('[data-control-size-output]');
  const visibilityButton = root.querySelector('[data-control-visibility]');
  const selectedLabel = root.querySelector('[data-control-selected]');
  let layout = normalizeControlLayout();
  let dragging = null;
  let selectedId = controls[0]?.dataset.layoutControl ?? 'joystick';

  const selectedControl = () => controls.find((control) => control.dataset.layoutControl === selectedId);

  const syncTools = () => {
    const position = layout[selectedId];
    const control = selectedControl();
    if (!position || !control) return;
    sizeInput.value = String(Math.round(position.size * 100));
    sizeOutput.textContent = `${Math.round(position.size * 100)}%`;
    visibilityButton.textContent = position.visible ? 'OCULTAR CONTROLE' : 'MOSTRAR CONTROLE';
    visibilityButton.setAttribute('aria-pressed', String(!position.visible));
    selectedLabel.textContent = control.dataset.layoutLabel ?? selectedId.toUpperCase();
  };

  const paint = () => {
    controls.forEach((control) => {
      const position = layout[control.dataset.layoutControl];
      control.style.left = `${position.x}%`;
      control.style.top = `${position.y}%`;
      control.style.setProperty('--editor-scale', String(position.size));
      control.classList.toggle('is-layout-hidden', !position.visible);
      control.classList.toggle('is-selected', control.dataset.layoutControl === selectedId);
    });
    syncTools();
  };

  const select = (id) => {
    selectedId = id;
    paint();
  };

  const updateDrag = (event) => {
    if (!dragging || event.pointerId !== dragging.pointerId) return;
    const bounds = stage.getBoundingClientRect();
    const x = ((event.clientX - dragging.offsetX - bounds.left) / Math.max(1, bounds.width)) * 100;
    const y = ((event.clientY - dragging.offsetY - bounds.top) / Math.max(1, bounds.height)) * 100;
    layout[dragging.id] = { ...layout[dragging.id], x: Number(clamp(x).toFixed(2)), y: Number(clamp(y).toFixed(2)) };
    paint();
    event.preventDefault();
  };

  const stopDrag = (event) => {
    if (!dragging || event.pointerId !== dragging.pointerId) return;
    dragging.control.classList.remove('is-dragging');
    dragging = null;
  };

  controls.forEach((control) => {
    control.addEventListener('pointerdown', (event) => {
      select(control.dataset.layoutControl);
      const bounds = control.getBoundingClientRect();
      dragging = {
        id: control.dataset.layoutControl,
        control,
        pointerId: event.pointerId,
        offsetX: event.clientX - (bounds.left + bounds.width / 2),
        offsetY: event.clientY - (bounds.top + bounds.height / 2),
      };
      control.classList.add('is-dragging');
      control.setPointerCapture?.(event.pointerId);
      event.preventDefault();
    });
    control.addEventListener('pointermove', updateDrag);
    control.addEventListener('pointerup', stopDrag);
    control.addEventListener('pointercancel', stopDrag);
  });

  sizeInput.addEventListener('input', () => {
    const size = clampSize(Number(sizeInput.value) / 100);
    layout[selectedId] = { ...layout[selectedId], size: Number(size.toFixed(2)) };
    paint();
  });
  visibilityButton.addEventListener('click', () => {
    layout[selectedId] = { ...layout[selectedId], visible: !layout[selectedId].visible };
    paint();
  });

  root.querySelector('[data-controls-reset]').addEventListener('click', () => {
    layout = normalizeControlLayout();
    selectedId = 'joystick';
    paint();
  });
  root.querySelectorAll('[data-controls-cancel]').forEach((button) => button.addEventListener('click', () => {
      root.classList.add('is-hidden');
      onClose();
    }));
  root.querySelector('[data-controls-save]').addEventListener('click', () => {
    const saved = normalizeControlLayout(layout);
    onSave(saved);
    root.classList.add('is-hidden');
    onClose();
  });

  return {
    open(source) {
      layout = normalizeControlLayout(source);
      selectedId = controls.some((control) => control.dataset.layoutControl === selectedId) ? selectedId : 'joystick';
      paint();
      root.classList.remove('is-hidden');
    },
    current: () => normalizeControlLayout(layout),
  };
}
