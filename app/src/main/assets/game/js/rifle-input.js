export function bindRifleInput({ button, windowRef = window, onPress, onRelease }) {
  let activePointerId = null;
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
    try { button.setPointerCapture?.(activePointerId); } catch {}
    onPress();
    event.preventDefault?.();
  };
  const onPointerUp = (event) => release(event);
  const onPointerCancel = (event) => release(event);
  const onLostPointerCapture = (event) => release(event);
  const onBlur = () => release();

  button.addEventListener('pointerdown', onPointerDown);
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
      button.removeEventListener('pointerup', onPointerUp);
      button.removeEventListener('pointercancel', onPointerCancel);
      button.removeEventListener('lostpointercapture', onLostPointerCapture);
      windowRef.removeEventListener('blur', onBlur);
      release();
    },
  };
}
