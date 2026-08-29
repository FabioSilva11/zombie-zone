import test from 'node:test';
import assert from 'node:assert/strict';
import { createInitialProgress } from '../app/src/main/assets/game/js/progression.js';
import { createScreenFlow } from '../app/src/main/assets/game/js/screen-flow.js';

test('primeiro acesso começa no avatar e jogador recorrente começa em início', () => {
  assert.equal(createScreenFlow(createInitialProgress()).state.screen, 'avatar');
  const returning = createInitialProgress();
  returning.selectedAvatar = 'b';
  returning.hasStarted = true;
  assert.equal(createScreenFlow(returning).state.screen, 'home');
});

test('pausa preserva a tela de jogo e retoma sem perder contexto', () => {
  const flow = createScreenFlow(createInitialProgress());
  flow.go('gameplay', { stageId: 'stage1' });
  flow.pause();
  assert.equal(flow.state.overlay, 'pause');
  assert.equal(flow.state.screen, 'gameplay');
  flow.resume();
  assert.equal(flow.state.overlay, null);
  assert.equal(flow.state.stageId, 'stage1');
});

test('tela ou overlay desconhecido é rejeitado', () => {
  const flow = createScreenFlow(createInitialProgress());
  assert.throws(() => flow.go('loja'), /Tela desconhecida/);
  assert.throws(() => flow.openOverlay('inventário'), /Overlay desconhecido/);
});
