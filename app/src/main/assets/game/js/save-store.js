import { CAMPAIGN } from './campaign.js?v=lobby-3';
import { createInitialProgress, SAVE_VERSION } from './progression.js?v=lobby-3';

const STAGE_IDS = new Set(CAMPAIGN.stages.map((stage) => stage.id));
const AVATAR_IDS = new Set('abcdefghijklmnopqr'.split(''));

const finiteOr = (value, fallback) => Number.isFinite(Number(value)) ? Number(value) : fallback;
const clampInteger = (value, min, max, fallback = min) => {
  const numeric = Number(value);
  return Number.isInteger(numeric) ? Math.min(max, Math.max(min, numeric)) : fallback;
};

export function normalizeProgress(source) {
  const initial = createInitialProgress();
  if (!source || typeof source !== 'object') return initial;
  const unlocked = [...new Set(Array.isArray(source.unlockedStageIds) ? source.unlockedStageIds : [])]
    .filter((id) => STAGE_IDS.has(id));
  if (!unlocked.includes('stage1')) unlocked.unshift('stage1');
  const selectedAvatar = AVATAR_IDS.has(source.selectedAvatar) ? source.selectedAvatar : null;
  const unlockedAvatarIds = [...new Set(Array.isArray(source.unlockedAvatarIds) ? source.unlockedAvatarIds : [])]
    .filter((id) => AVATAR_IDS.has(id));
  if (!unlockedAvatarIds.includes('a')) unlockedAvatarIds.unshift('a');
  if (selectedAvatar && !unlockedAvatarIds.includes(selectedAvatar)) unlockedAvatarIds.push(selectedAvatar);

  const stages = {};
  for (const [id, value] of Object.entries(source.stages ?? {})) {
    if (!STAGE_IDS.has(id) || !value || typeof value !== 'object') continue;
    stages[id] = {
      completed: Boolean(value.completed),
      stars: clampInteger(value.stars, 0, 3),
      bestTime: Number.isFinite(Number(value.bestTime)) ? Math.max(0, Number(value.bestTime)) : null,
      bestHealthRatio: Math.min(1, Math.max(0, finiteOr(value.bestHealthRatio, 0))),
      collectedCrystalIds: [...new Set((Array.isArray(value.collectedCrystalIds) ? value.collectedCrystalIds : [])
        .filter((idValue) => /^stage\d+:crystal-[1-3]$/.test(idValue) && idValue.startsWith(`${id}:`)))],
    };
  }

  return {
    version: SAVE_VERSION,
    selectedAvatar,
    hasStarted: Boolean(source.hasStarted),
    crystals: clampInteger(source.crystals, 0, Number.MAX_SAFE_INTEGER, 0),
    unlockedAvatarIds,
    unlockedStageIds: unlocked,
    stages,
    upgrades: {
      vitality: clampInteger(source.upgrades?.vitality, 0, 5),
      power: clampInteger(source.upgrades?.power, 0, 5),
    },
    viewedStoryIds: [...new Set((Array.isArray(source.viewedStoryIds) ? source.viewedStoryIds : [])
      .filter((id) => typeof id === 'string'))],
    monetization: {
      rewardedAdsClaimedToday: clampInteger(source.monetization?.rewardedAdsClaimedToday, 0, 5),
      rewardedAdsDayKey: typeof source.monetization?.rewardedAdsDayKey === 'string'
        && /^\d{4}-\d{2}-\d{2}$/.test(source.monetization.rewardedAdsDayKey)
        ? source.monetization.rewardedAdsDayKey
        : initial.monetization.rewardedAdsDayKey,
    },
    settings: {
      cameraSensitivity: Math.min(2, Math.max(0.5, finiteOr(source.settings?.cameraSensitivity, 1))),
      soundEnabled: source.settings?.soundEnabled !== false,
      skipViewedStory: source.settings?.skipViewedStory !== false,
    },
  };
}

export function createSaveStore(storage, key = 'ecos-de-eldra.save') {
  return {
    load() {
      try {
        return normalizeProgress(JSON.parse(storage.getItem(key) ?? 'null'));
      } catch {
        return createInitialProgress();
      }
    },
    save(progress) {
      const normalized = normalizeProgress(progress);
      storage.setItem(key, JSON.stringify(normalized));
      return normalized;
    },
    update(updater) {
      return this.save(updater(this.load()));
    },
  };
}
