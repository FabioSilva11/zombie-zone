import test from 'node:test';
import assert from 'node:assert/strict';
import { completeMission, routeBootFailure, shouldDisposeMission } from '../app/src/main/assets/game/js/mission-lifecycle.js';
import { createInitialProgress } from '../app/src/main/assets/game/js/progression.js';

test('a missão é liberada ao sair do gameplay, mas não ao apenas pausar', () => {
  assert.equal(shouldDisposeMission({ screen: 'gameplay' }, { screen: 'chapter' }), true);
  assert.equal(shouldDisposeMission({ screen: 'gameplay' }, { screen: 'gameplay', overlay: 'pause' }), false);
  assert.equal(shouldDisposeMission({ screen: 'chapter' }, { screen: 'home' }), false);
});

test('conclusão aplica a progressão uma vez e encaminha para vitória', () => {
  const result = completeMission(createInitialProgress(), 'stage1', {
    completed: true,
    elapsedSeconds: 30,
    healthRemaining: 100,
    maxHealth: 100,
    collectedCrystalIds: [],
  });
  assert.equal(result.nextScreen, 'victory');
  assert.equal(result.progress.stages.stage1.stars, 3);
  assert.ok(result.award.crystalsAwarded > 0);
});

test('falhas de WebGL e WebAssembly seguem para compatibilidade', () => {
  assert.equal(routeBootFailure(new WebAssembly.CompileError('unsupported')), 'compatibility');
  assert.equal(routeBootFailure(new Error('WebGL indisponível neste aparelho.')), 'compatibility');
  assert.equal(routeBootFailure(new Error('modelo ausente')), 'retry');
});
