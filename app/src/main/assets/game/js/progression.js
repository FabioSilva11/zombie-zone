import { CAMPAIGN } from './campaign.js?v=lobby-3';
import { CHARACTERS } from './catalog.js?v=lobby-3';

export const SAVE_VERSION = 2;
const UPGRADE_COSTS = Object.freeze([50, 90, 140, 200, 280]);
const UPGRADE_KINDS = new Set(['vitality', 'power']);
const REWARDED_AD_CRYSTALS = 20;
const REWARDED_AD_DAILY_CAP = 5;

export function currentDayKey(now = new Date()) {
  return now.toISOString().slice(0, 10);
}

export function createInitialProgress() {
  return {
    version: SAVE_VERSION,
    selectedAvatar: 'a',
    hasStarted: true,
    crystals: 0,
    unlockedAvatarIds: ['a'],
    unlockedStageIds: ['stage1'],
    stages: {},
    upgrades: { vitality: 0, power: 0 },
    viewedStoryIds: [],
    monetization: { rewardedAdsClaimedToday: 0, rewardedAdsDayKey: currentDayKey() },
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
    unlockedAvatarIds: [...(progress.unlockedAvatarIds ?? ['a'])],
    unlockedStageIds: [...progress.unlockedStageIds],
    stages: Object.fromEntries(Object.entries(progress.stages).map(([id, value]) => [id, {
      ...value,
      collectedCrystalIds: [...(value.collectedCrystalIds ?? [])],
    }])),
    upgrades: { ...progress.upgrades },
    viewedStoryIds: [...progress.viewedStoryIds],
    monetization: { ...progress.monetization },
    settings: { ...progress.settings },
  };
}

function normalizeMonetization(source) {
  const initial = createInitialProgress().monetization;
  const dayKey = typeof source?.rewardedAdsDayKey === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(source.rewardedAdsDayKey)
    ? source.rewardedAdsDayKey
    : initial.rewardedAdsDayKey;
  const rewardedAdsClaimedToday = Number.isInteger(source?.rewardedAdsClaimedToday)
    ? Math.min(REWARDED_AD_DAILY_CAP, Math.max(0, source.rewardedAdsClaimedToday))
    : initial.rewardedAdsClaimedToday;
  return { rewardedAdsClaimedToday, rewardedAdsDayKey: dayKey };
}

export function purchaseAvatar(progress, avatarId) {
  const character = CHARACTERS.find((candidate) => candidate.id === avatarId);
  if (!character) throw new Error('Herói desconhecido.');
  if (progress.unlockedAvatarIds?.includes(avatarId)) {
    return { progress: { ...cloneProgress(progress), selectedAvatar: avatarId }, spent: 0 };
  }
  if (progress.crystals < character.price) throw new Error('Diamantes insuficientes.');
  const next = cloneProgress(progress);
  next.crystals -= character.price;
  next.unlockedAvatarIds.push(avatarId);
  next.selectedAvatar = avatarId;
  return { progress: next, spent: character.price };
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

export function getRewardedAdOffer(progress, now = new Date()) {
  const monetization = progress.monetization ?? createInitialProgress().monetization;
  const dayKey = currentDayKey(now);
  const claimedToday = monetization.rewardedAdsDayKey === dayKey ? monetization.rewardedAdsClaimedToday : 0;
  return {
    dayKey,
    claimedToday,
    remainingToday: Math.max(0, REWARDED_AD_DAILY_CAP - claimedToday),
    crystalsPerAd: REWARDED_AD_CRYSTALS,
    dailyCap: REWARDED_AD_DAILY_CAP,
  };
}

export function claimRewardedAd(progress, now = new Date()) {
  const next = cloneProgress(progress);
  const dayKey = currentDayKey(now);
  if (next.monetization.rewardedAdsDayKey !== dayKey) {
    next.monetization.rewardedAdsDayKey = dayKey;
    next.monetization.rewardedAdsClaimedToday = 0;
  }
  if (next.monetization.rewardedAdsClaimedToday >= REWARDED_AD_DAILY_CAP) {
    return { progress: next, crystalsAwarded: 0, remainingToday: 0, capped: true };
  }
  next.monetization.rewardedAdsClaimedToday += 1;
  next.crystals += REWARDED_AD_CRYSTALS;
  return {
    progress: next,
    crystalsAwarded: REWARDED_AD_CRYSTALS,
    remainingToday: Math.max(0, REWARDED_AD_DAILY_CAP - next.monetization.rewardedAdsClaimedToday),
    capped: false,
  };
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
