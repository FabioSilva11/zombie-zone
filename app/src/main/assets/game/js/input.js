export function normalizeStick(dx, dy, radius) {
  if (!Number.isFinite(radius) || radius <= 0) return { x: 0, y: 0 };
  const rawX = dx / radius;
  const rawY = dy / radius;
  const length = Math.hypot(rawX, rawY);
  if (length <= 1) return { x: rawX, y: rawY };
  return { x: rawX / length, y: rawY / length };
}

export class TouchControls {
  constructor({ joystick, cameraZone, attackButton, onAttack }) {
    this.movement = { x: 0, y: 0 };
    this.look = { x: 0, y: 0 };
    this.joystick = joystick;
    this.knob = joystick.querySelector('.joystick-knob');
    this.cameraZone = cameraZone;
    this.attackButton = attackButton;
    this.onAttack = onAttack;
    this.joystickPointer = null;
    this.cameraPointer = null;
    this.lastCameraPoint = null;
    this.listeners = [];
    this.bind();
  }

  on(element, type, handler) {
    element.addEventListener(type, handler, { passive: false });
    this.listeners.push([element, type, handler]);
  }

  bind() {
    this.on(this.joystick, 'pointerdown', (event) => {
      event.preventDefault();
      this.joystickPointer = event.pointerId;
      this.joystick.setPointerCapture(event.pointerId);
      this.updateJoystick(event);
    });
    this.on(this.joystick, 'pointermove', (event) => {
      if (event.pointerId === this.joystickPointer) this.updateJoystick(event);
    });
    const releaseJoystick = (event) => {
      if (event.pointerId !== this.joystickPointer) return;
      this.joystickPointer = null;
      this.movement = { x: 0, y: 0 };
      this.knob.style.transform = 'translate(0px, 0px)';
    };
    this.on(this.joystick, 'pointerup', releaseJoystick);
    this.on(this.joystick, 'pointercancel', releaseJoystick);

    this.on(this.cameraZone, 'pointerdown', (event) => {
      event.preventDefault();
      this.cameraPointer = event.pointerId;
      this.lastCameraPoint = { x: event.clientX, y: event.clientY };
      this.cameraZone.setPointerCapture(event.pointerId);
    });
    this.on(this.cameraZone, 'pointermove', (event) => {
      if (event.pointerId !== this.cameraPointer || !this.lastCameraPoint) return;
      this.look.x += event.clientX - this.lastCameraPoint.x;
      this.look.y += event.clientY - this.lastCameraPoint.y;
      this.lastCameraPoint = { x: event.clientX, y: event.clientY };
    });
    const releaseCamera = (event) => {
      if (event.pointerId !== this.cameraPointer) return;
      this.cameraPointer = null;
      this.lastCameraPoint = null;
    };
    this.on(this.cameraZone, 'pointerup', releaseCamera);
    this.on(this.cameraZone, 'pointercancel', releaseCamera);

    this.on(this.attackButton, 'pointerdown', (event) => {
      event.preventDefault();
      this.attackButton.classList.add('is-active');
      this.onAttack();
    });
    const releaseAttack = () => this.attackButton.classList.remove('is-active');
    this.on(this.attackButton, 'pointerup', releaseAttack);
    this.on(this.attackButton, 'pointercancel', releaseAttack);
  }

  updateJoystick(event) {
    const rect = this.joystick.getBoundingClientRect();
    const centerX = rect.left + rect.width / 2;
    const centerY = rect.top + rect.height / 2;
    const radius = rect.width * 0.36;
    this.movement = normalizeStick(event.clientX - centerX, event.clientY - centerY, radius);
    this.knob.style.transform = `translate(${this.movement.x * radius}px, ${this.movement.y * radius}px)`;
  }

  consumeLook() {
    const look = this.look;
    this.look = { x: 0, y: 0 };
    return look;
  }

  dispose() {
    for (const [element, type, handler] of this.listeners) element.removeEventListener(type, handler);
    this.listeners = [];
  }
}
