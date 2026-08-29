import { stageById } from './campaign.js';
import { applyStageResult } from './progression.js';

export function completeMission(progress, stageId, result) {
  const stage = stageById(stageId);
  const applied = applyStageResult(progress, stage, result);
  return {
    progress: applied.progress,
    award: { stars: applied.starsEarned, crystalsAwarded: applied.crystalsAwarded },
    nextScreen: 'victory',
  };
}

export function routeBootFailure(error) {
  const message = String(error?.message ?? error ?? '');
  if (error instanceof WebAssembly.CompileError || /webgl|webassembly|wasm/i.test(message)) return 'compatibility';
  return 'retry';
}

export function shouldDisposeMission(previousState, nextState) {
  return previousState?.screen === 'gameplay' && nextState?.screen !== 'gameplay';
}
