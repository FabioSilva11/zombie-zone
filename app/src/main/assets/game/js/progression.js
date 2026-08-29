import { CAMPAIGN } from './campaign.js';

export const SAVE_VERSION = 1;
const UPGRADE_COSTS = Object.freeze([50, 90, 140, 200, 280]);
const UPGRADE_KINDS = new Set(['vitality', 'power']);

export function createInitialProgress() {
  return {
    version: SAVE_VERSION,
    selectedAvatar: null,
    hasStarted: false,
    crystals: 0,
    unlockedStageIds: ['stage1'],
    stages: {},
    upgrades: { vitality: 0, power: 0 },
    viewedStoryIds: [],
    settings: { cameraSensitivity: 1, soundEnabled: true, skipViewedStory: true },
  };
}

export function evaluateStage(stage, result) {
  if (!result?.completed) return { stars: 0, conditions: [false, false, false] };
  const healthRatio = Number(result.healthRemaining) / Math.max(1, Number(result.maxHealth));
  const conditions = [true, healthRatio >= 0.5, Number(result.elapsedSeconds) <= stage.targetSeconds];
  return { stars: conditions.filter(Boolean).length, conditions };
}

function cloneProgress(progress) {
  return {
    ...progress,
    unlockedStageIds: [...progress.unlockedStageIds],
    stages: Object.fromEntries(Object.entries(progress.stages).map(([id, value]) => [id, {
      ...value,
      collectedCrystalIds: [...(value.collectedCrystalIds ?? [])],
    }])),
    upgrades: { ...progress.upgrades },
    viewedStoryIds: [...progress.viewedStoryIds],
    settings: { ...progress.settings },
  };
}

export function applyStageResult(progress, stage, result) {
  const next = cloneProgress(progress);
  const previous = next.stages[stage.id] ?? {
    completed: false,
    stars: 0,
    bestTime: null,
    bestHealthRatio: 0,
    collectedCrystalIds: [],
  };
  const evaluation = evaluateStage(stage, result);
  const collectedBefore = new Set(previous.collectedCrystalIds);
  const newlyCollected = [...new Set(result.collectedCrystalIds ?? [])]
    .filter((id) => !collectedBefore.has(id));
  const firstClearAward = result.completed && !previous.completed ? stage.firstClearCrystals : 0;
  const newStarAward = Math.max(0, evaluation.stars - previous.stars) * 15;
  const pickupAward = newlyCollected.length * 5;
  const crystalsAwarded = firstClearAward + newStarAward + pickupAward;

  next.crystals += crystalsAwarded;
  next.stages[stage.id] = {
    completed: previous.completed || Boolean(result.completed),
    stars: Math.max(previous.stars, evaluation.stars),
    bestTime: result.completed
      ? Math.min(previous.bestTime ?? Number.POSITIVE_INFINITY, Number(result.elapsedSeconds))
      : previous.bestTime,
    bestHealthRatio: Math.max(
      previous.bestHealthRatio,
      Number(result.healthRemaining) / Math.max(1, Number(result.maxHealth)),
    ),
    collectedCrystalIds: [...collectedBefore, ...newlyCollected],
  };

  if (result.completed) {
    const following = CAMPAIGN.stages[stage.number];
    if (following && !next.unlockedStageIds.includes(following.id)) next.unlockedStageIds.push(following.id);
  }
  return { progress: next, crystalsAwarded, starsEarned: evaluation.stars };
}

export function upgradeCost(kind, level) {
  if (!UPGRADE_KINDS.has(kind)) throw new Error(`Melhoria desconhecida: ${kind}`);
  if (!Number.isInteger(level) || level < 0 || level >= UPGRADE_COSTS.length) {
    throw new Error('Melhoria no nível máximo.');
  }
  return UPGRADE_COSTS[level];
}

export function purchaseUpgrade(progress, kind) {
  const level = progress.upgrades[kind];
  const cost = upgradeCost(kind, level);
  if (progress.crystals < cost) throw new Error('Cristais insuficientes.');
  const next = cloneProgress(progress);
  next.crystals -= cost;
  next.upgrades[kind] += 1;
  return { progress: next, spent: cost };
}
