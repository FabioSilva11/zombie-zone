let nextRewardId = 1;

function createCallbackRegistry() {
  if (window.__rewardedAdBridge) return window.__rewardedAdBridge;
  const pending = new Map();
  window.__rewardedAdBridge = {
    resolve(id, granted, reason) {
      const entry = pending.get(id);
      if (!entry) return;
      pending.delete(id);
      entry.resolve({ granted: Boolean(granted), reason: reason ?? '' });
    },
  };
  return {
    pending,
    resolve: window.__rewardedAdBridge.resolve,
  };
}

export async function showRewardedAd() {
  const registry = createCallbackRegistry();
  if (!window.AndroidAds?.showRewarded) {
    return { granted: false, reason: 'bridge-unavailable' };
  }
  const id = `reward-${nextRewardId++}`;
  const result = await new Promise((resolve) => {
    registry.pending.set(id, { resolve });
    try {
      window.AndroidAds.showRewarded(id);
    } catch (error) {
      registry.pending.delete(id);
      resolve({ granted: false, reason: error?.message ?? 'bridge-error' });
    }
  });
  return result;
}
