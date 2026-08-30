export function createRifleIntent({ onAimChange = () => {}, onFireChange = () => {} } = {}) {
  let aimHeld = false;
  let fireHeld = false;
  let aiming = false;
  let firing = false;
  let disposed = false;

  const sync = () => {
    const nextAiming = aimHeld || fireHeld;
    if (nextAiming !== aiming) {
      aiming = nextAiming;
      onAimChange(aiming);
    }
    if (fireHeld !== firing) {
      firing = fireHeld;
      onFireChange(firing);
    }
  };
  const setAimHeld = (value) => {
    if (disposed) return;
    aimHeld = Boolean(value);
    sync();
  };
  const setFireHeld = (value) => {
    if (disposed) return;
    fireHeld = Boolean(value);
    sync();
  };
  const cancel = () => {
    aimHeld = false;
    fireHeld = false;
    sync();
  };

  return {
    pressAim() { setAimHeld(true); },
    releaseAim() { setAimHeld(false); },
    toggleAim() { setAimHeld(!aimHeld); },
    pressFire() { setFireHeld(true); },
    releaseFire() { setFireHeld(false); },
    cancel,
    state() { return { aimHeld, fireHeld, aiming }; },
    dispose() {
      if (disposed) return;
      cancel();
      disposed = true;
    },
  };
}

export function bindRifleInput({
  button,
  windowRef = window,
  onPress,
  onRelease,
  onMove = () => {},
}) {
  let activePointerId = null;
  let lastPointerX = 0;
  let lastPointerY = 0;
  let disposed = false;

  const release = (event = null) => {
    if (activePointerId === null) return false;
    if (event?.pointerId !== undefined && event.pointerId !== activePointerId) return false;
    const pointerId = activePointerId;
    activePointerId = null;
    try { button.releasePointerCapture?.(pointerId); } catch {}
    onRelease();
    return true;
  };

  const onPointerDown = (event) => {
    if (disposed || activePointerId !== null) return;
    activePointerId = event.pointerId;
    lastPointerX = Number(event.clientX) || 0;
    lastPointerY = Number(event.clientY) || 0;
    try { button.setPointerCapture?.(activePointerId); } catch {}
    onPress();
    event.preventDefault?.();
  };
  const onPointerMove = (event) => {
    if (disposed || activePointerId === null || event.pointerId !== activePointerId) return;
    const x = Number.isFinite(event.clientX) ? event.clientX : lastPointerX;
    const y = Number.isFinite(event.clientY) ? event.clientY : lastPointerY;
    const deltaX = x - lastPointerX;
    const deltaY = y - lastPointerY;
    lastPointerX = x;
    lastPointerY = y;
    if (deltaX || deltaY) onMove(deltaX, deltaY, event);
    event.preventDefault?.();
  };
  const onPointerUp = (event) => release(event);
  const onPointerCancel = (event) => release(event);
  const onLostPointerCapture = (event) => release(event);
  const onBlur = () => release();

  button.addEventListener('pointerdown', onPointerDown);
  button.addEventListener('pointermove', onPointerMove);
  button.addEventListener('pointerup', onPointerUp);
  button.addEventListener('pointercancel', onPointerCancel);
  button.addEventListener('lostpointercapture', onLostPointerCapture);
  windowRef.addEventListener('blur', onBlur);

  return {
    cancel() { release(); },
    dispose() {
      if (disposed) return;
      disposed = true;
      button.removeEventListener('pointerdown', onPointerDown);
      button.removeEventListener('pointermove', onPointerMove);
      button.removeEventListener('pointerup', onPointerUp);
      button.removeEventListener('pointercancel', onPointerCancel);
      button.removeEventListener('lostpointercapture', onLostPointerCapture);
      windowRef.removeEventListener('blur', onBlur);
      release();
    },
  };
}
