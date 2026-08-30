import test from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_CONTROL_LAYOUT, normalizeControlLayout } from '../app/src/main/assets/game/js/control-layout.js';

test('layout padrão inclui movimento, combate e novas ações', () => {
  assert.deepEqual(Object.keys(DEFAULT_CONTROL_LAYOUT), ['joystick', 'reload', 'sprint', 'dodge', 'crouch', 'jump', 'aim', 'shoot']);
});

test('layout personalizado preserva posição, tamanho e visibilidade dentro dos limites', () => {
  const layout = normalizeControlLayout({
    shoot: { x: 130, y: -20, size: 8, visible: false },
    jump: { x: 42.25, y: 67.75, size: 0.75 },
  });
  assert.deepEqual(layout.shoot, { x: 96, y: 4, size: 1.8, visible: false });
  assert.deepEqual(layout.jump, { x: 42.25, y: 67.75, size: 0.75, visible: true });
  assert.deepEqual(layout.joystick, DEFAULT_CONTROL_LAYOUT.joystick);
});

test('save antigo recebe tamanho e visibilidade sem perder posições', () => {
  const layout = normalizeControlLayout({ joystick: { x: 22, y: 70 } });
  assert.deepEqual(layout.joystick, { x: 22, y: 70, size: 1, visible: true });
});
