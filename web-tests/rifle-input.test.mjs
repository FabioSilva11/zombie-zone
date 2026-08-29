import test from 'node:test';
import assert from 'node:assert/strict';

import { bindRifleInput } from '../app/src/main/assets/game/js/rifle-input.js';

function pointerEvent(type, pointerId = 7) {
  const event = new Event(type, { cancelable: true });
  Object.defineProperty(event, 'pointerId', { value: pointerId });
  return event;
}

function createHarness() {
  const button = new EventTarget();
  const windowRef = new EventTarget();
  const captured = [];
  const released = [];
  const calls = [];
  button.setPointerCapture = (pointerId) => captured.push(pointerId);
  button.releasePointerCapture = (pointerId) => released.push(pointerId);
  const input = bindRifleInput({
    button,
    windowRef,
    onPress: () => calls.push('press'),
    onRelease: () => calls.push('release'),
  });
  return { button, windowRef, captured, released, calls, input };
}

test('pointerdown captura um único ponteiro e chama press exatamente uma vez', () => {
  const { button, captured, calls, input } = createHarness();
  const down = pointerEvent('pointerdown');
  button.dispatchEvent(down);
  button.dispatchEvent(pointerEvent('pointerdown', 8));

  assert.deepEqual(captured, [7]);
  assert.deepEqual(calls, ['press']);
  assert.equal(down.defaultPrevented, true);
  input.dispose();
});
test('pointerup do ponteiro ativo chama release uma vez', () => {
  const { button, released, calls, input } = createHarness();
  button.dispatchEvent(pointerEvent('pointerdown'));
  button.dispatchEvent(pointerEvent('pointerup'));
  button.dispatchEvent(pointerEvent('pointerup'));

  assert.deepEqual(released, [7]);
  assert.deepEqual(calls, ['press', 'release']);
  input.dispose();
});

test('pointercancel e blur cancelam o gatilho de forma idempotente', () => {
  const { button, windowRef, calls, input } = createHarness();
  button.dispatchEvent(pointerEvent('pointerdown'));
  button.dispatchEvent(pointerEvent('pointercancel'));
  windowRef.dispatchEvent(new Event('blur'));
  button.dispatchEvent(pointerEvent('pointerdown', 9));
  windowRef.dispatchEvent(new Event('blur'));

  assert.deepEqual(calls, ['press', 'release', 'press', 'release']);
  input.dispose();
});

test('lostpointercapture libera e dispose remove listeners sem deixar gatilho preso', () => {
  const { button, windowRef, calls, input } = createHarness();
  button.dispatchEvent(pointerEvent('pointerdown'));
  button.dispatchEvent(pointerEvent('lostpointercapture'));
  input.dispose();
  input.dispose();
  button.dispatchEvent(pointerEvent('pointerdown'));
  button.dispatchEvent(pointerEvent('pointerup'));
  windowRef.dispatchEvent(new Event('blur'));

  assert.deepEqual(calls, ['press', 'release']);
});
