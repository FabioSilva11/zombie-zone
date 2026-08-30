export function resolveRiflePresentation({ aimHeld = false, fireHeld = false } = {}) {
  if (fireHeld) {
    return Object.freeze({
      visible: true,
      animation: Object.freeze(['holding-right-shoot', 'holding-right', 'idle']),
    });
  }
  if (aimHeld) {
    return Object.freeze({
      visible: true,
      animation: Object.freeze(['holding-right', 'holding-right-shoot', 'idle']),
    });
  }
  return Object.freeze({ visible: false, animation: null });
}
