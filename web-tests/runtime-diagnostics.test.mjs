import test from 'node:test';
import assert from 'node:assert/strict';
import { angularDistance, installRuntimeDiagnostics } from '../app/src/main/assets/game/js/runtime-diagnostics.js';

test('distância angular trata corretamente a volta entre PI e -PI', () => {
  assert.ok(angularDistance(Math.PI - 0.01, -Math.PI + 0.01) < 0.021);
});

test('diagnóstico registra amostra e detecta dessincronização do facing', () => {
  let now = 100;
  const windowRef = new EventTarget();
  const logs = [];
  const diagnostics = installRuntimeDiagnostics({
    windowRef,
    consoleRef: { log: (...args) => logs.push(args), warn: (...args) => logs.push(args), error: (...args) => logs.push(args) },
    performanceRef: { now: () => now },
    sampleIntervalMs: 10,
  });
  diagnostics.clear();
  now = 120;
  diagnostics.sampleFrame({
    nowSeconds: 1,
    delta: 1 / 60,
    stick: { x: 1, y: 0 },
    moved: { x: 0.05, y: 0, z: 0 },
    position: { x: 1, y: 0, z: 2 },
    playerYaw: 0,
    cameraYaw: 0.25,
  });
  assert.equal(diagnostics.errors()[0].type, 'facing-desync');
  assert.ok(diagnostics.snapshot().some((entry) => entry.type === 'movement-sample'));
  assert.ok(logs.length >= 2);
  diagnostics.dispose();
});

test('diagnóstico aceita câmera livre parada e valida a direção durante movimento', () => {
  const diagnostics = installRuntimeDiagnostics({
    windowRef: new EventTarget(),
    consoleRef: { log() {}, warn() {}, error() {} },
    performanceRef: { now: () => 100 },
    sampleIntervalMs: 10,
  });
  diagnostics.clear();
  diagnostics.sampleFrame({
    nowSeconds: 1, delta: 1 / 60, stick: { x: 0, y: 0 }, moved: { x: 0, y: 0, z: 0 },
    position: { x: 1, y: 0, z: 2 }, playerYaw: 0.4, cameraYaw: 1.8, facingSource: 'locked',
  });
  diagnostics.sampleFrame({
    nowSeconds: 2, delta: 1 / 60, stick: { x: 1, y: 0 }, moved: { x: 0.1, y: 0, z: 0 },
    position: { x: 1.1, y: 0, z: 2 }, playerYaw: Math.PI / 2, cameraYaw: 0, facingSource: 'movement',
  });
  assert.deepEqual(diagnostics.errors(), []);
  diagnostics.dispose();
});
