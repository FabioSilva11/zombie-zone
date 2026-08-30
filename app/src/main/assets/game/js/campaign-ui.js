import { CAMPAIGN, stageById } from './campaign.js?v=lobby-3';
import { claimRewardedAd, getRewardedAdOffer, purchaseUpgrade, upgradeCost } from './progression.js?v=lobby-3';
import { showRewardedAd } from './ad-rewards.js?v=ads-2';

const SCREEN_IDS = ['home', 'chapter', 'victory', 'defeat', 'compatibility'];

export function createChapterView(campaign, progress) {
  return campaign.stages.map((stage) => {
    const record = progress.stages[stage.id];
    const unlocked = progress.unlockedStageIds.includes(stage.id);
    return {
      ...stage,
      stars: record?.stars ?? 0,
      starTotal: 3,
      collectibles: record?.collectedCrystalIds?.length ?? 0,
      collectibleTotal: stage.collectibleTotal ?? 3,
      status: record?.completed ? 'completed' : unlocked ? 'available' : 'locked',
    };
  });
}

export function createCampaignPath(stages) {
  return {
    nodes: [...stages],
    connectors: stages.slice(0, -1).map((stage, index) => ({ from: stage.id, to: stages[index + 1].id })),
  };
}

export function currentLobbyStage(campaign, progress) {
  const unlocked = new Set(progress.unlockedStageIds ?? []);
  return [...campaign.stages].reverse().find((stage) => unlocked.has(stage.id)) ?? campaign.stages[0];
}

const totalStars = (progress) => Object.values(progress.stages).reduce((sum, stage) => sum + (stage.stars ?? 0), 0);

export function chapterRangeLabel(first, last, total) {
  const safeTotal = Math.max(1, Number(total) || 1);
  const safeFirst = Math.min(safeTotal, Math.max(1, Number(first) || 1));
  const safeLast = Math.min(safeTotal, Math.max(safeFirst, Number(last) || safeFirst));
  return `FASES ${String(safeFirst).padStart(2, '0')}–${String(safeLast).padStart(2, '0')} DE ${safeTotal}`;
}

export function mountCampaignUI({ flow, getProgress, setProgress, onStartMission, onSelectAvatar, getCharacterName = () => 'Kael' }) {
  const queryAll = (selector) => [...document.querySelectorAll(selector)];
  let activeStageId = 'stage1';

  function updateChapterRange() {
    const path = document.querySelector('[data-stage-path]');
    const label = document.querySelector('[data-chapter-range]');
    if (!path || !label) return;
    const viewport = path.getBoundingClientRect();
    const visible = queryAll('.stage-node').filter((node) => {
      const bounds = node.getBoundingClientRect();
      return bounds.right > viewport.left + 8 && bounds.left < viewport.right - 8;
    });
    const first = Number(visible[0]?.querySelector('.stage-number')?.textContent) || 1;
    const last = Number(visible.at(-1)?.querySelector('.stage-number')?.textContent) || Math.min(6, CAMPAIGN.stages.length);
    label.textContent = chapterRangeLabel(first, last, CAMPAIGN.stages.length);
  }

  function hideScreens() {
    for (const id of SCREEN_IDS) document.querySelector(`#${id}-screen`)?.classList.add('is-hidden');
    for (const id of ['pause', 'settings', 'upgrades']) document.querySelector(`#${id}-screen`)?.classList.add('is-hidden');
  }

  function renderResources(progress) {
    queryAll('[data-total-stars]').forEach((element) => { element.textContent = totalStars(progress); });
    queryAll('[data-crystals]').forEach((element) => { element.textContent = progress.crystals; });
    queryAll('[data-owned-heroes]').forEach((element) => { element.textContent = `${progress.unlockedAvatarIds.length}/18`; });
    const playerName = document.querySelector('[data-home-player-name]');
    if (playerName) playerName.textContent = getCharacterName(progress.selectedAvatar ?? 'a');
    const offer = getRewardedAdOffer(progress);
    const adStatus = document.querySelector('[data-home-ad-status]');
    if (adStatus) {
      adStatus.textContent = offer.remainingToday > 0
        ? `Anúncio recompensado: +${offer.crystalsPerAd} diamantes, ${offer.remainingToday}/${offer.dailyCap} hoje.`
        : 'Limite diário de anúncios recompensados atingido. Volte amanhã para mais diamantes.';
    }
    const adButton = document.querySelector('[data-home-rewarded-ad]');
    if (adButton) adButton.disabled = offer.remainingToday <= 0;
  }

  function renderUpgrades(progress) {
    for (const kind of ['vitality', 'power']) {
      const level = progress.upgrades[kind];
      document.querySelector(`[data-${kind}-level]`).textContent = `Nível ${level}/5`;
      const button = document.querySelector(`[data-upgrade="${kind}"]`);
      const cost = level < 5 ? upgradeCost(kind, level) : null;
      button.disabled = cost === null || progress.crystals < cost;
      document.querySelector(`[data-${kind}-cost]`).textContent = cost ?? 'MAX';
    }
  }

  function renderMissionDetail(stage) {
    activeStageId = stage.id;
    document.querySelector('[data-mission-number]').textContent = String(stage.number).padStart(2, '0');
    document.querySelector('[data-mission-title]').textContent = stage.title;
    document.querySelector('[data-mission-objective]').textContent = stage.objective;
    document.querySelector('[data-mission-stars]').textContent = `${stage.stars}/${stage.starTotal}`;
    document.querySelector('[data-mission-collectibles]').textContent = `${stage.collectibles}/${stage.collectibleTotal}`;
    document.querySelector('[data-first-reward]').textContent = stage.firstClearCrystals;
    document.querySelector('[data-map-start]').disabled = stage.status === 'locked';
    queryAll('.stage-node').forEach((node) => node.classList.toggle('is-current', node.dataset.stageId === stage.id));
  }

  function renderChapter(progress) {
    const stages = createChapterView(CAMPAIGN, progress);
    const selected = stages.find((stage) => stage.id === activeStageId && stage.status !== 'locked')
      ?? [...stages].reverse().find((stage) => stage.status !== 'locked')
      ?? stages[0];
    activeStageId = selected.id;
    const path = document.querySelector('[data-stage-path]');
    const children = [];
    stages.forEach((stage, index) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `stage-node ${stage.status}`;
      button.dataset.stageId = stage.id;
      button.disabled = stage.status === 'locked';
      button.innerHTML = `<span class="stage-number">${String(stage.number).padStart(2, '0')}</span><span class="stage-glyph">${stage.status === 'locked' ? '◇' : '✦'}</span><strong></strong><span class="stage-rewards"><small>★ ${stage.stars}/3</small><small>◆ ${stage.collectibles}/3</small></span>`;
      button.querySelector('strong').textContent = stage.title;
      button.addEventListener('click', () => renderMissionDetail(stage));
      children.push(button);
      if (index < stages.length - 1) {
        const connector = document.createElement('span');
        connector.className = `stage-connector ${stage.status === 'completed' ? 'completed' : ''}`;
        connector.dataset.from = stage.id;
        connector.dataset.to = stages[index + 1].id;
        connector.setAttribute('aria-hidden', 'true');
        children.push(connector);
      }
    });
    path.replaceChildren(...children);
    renderMissionDetail(selected);
    window.requestAnimationFrame(() => {
      path.querySelector('.stage-node.is-current')?.scrollIntoView({ block: 'nearest', inline: 'center' });
      updateChapterRange();
    });
  }

  function render(state = flow.state) {
    const progress = getProgress();
    hideScreens();
    renderResources(progress);
    queryAll('[data-camera-sensitivity]').forEach((input) => { input.value = progress.settings.cameraSensitivity; });
    const soundToggle = document.querySelector('[data-sound-enabled]');
    if (soundToggle) soundToggle.checked = progress.settings.soundEnabled;
    if (state.screen === 'gameplay') {
      if (state.overlay === 'pause') document.querySelector('#pause-screen').classList.remove('is-hidden');
      if (state.overlay === 'settings') document.querySelector('#settings-screen').classList.remove('is-hidden');
      return;
    }
    if (state.screen === 'avatar') return;
    document.querySelector(`#${state.screen}-screen`)?.classList.remove('is-hidden');
    if (state.screen === 'home') {
      const completed = Object.values(progress.stages).filter((stage) => stage.completed).length;
      const current = currentLobbyStage(CAMPAIGN, progress);
      document.querySelector('[data-home-current-number]').textContent = String(current.number).padStart(2, '0');
      document.querySelector('[data-home-current-title]').textContent = current.title;
      document.querySelector('[data-home-progress]').textContent = `${completed} de ${CAMPAIGN.stages.length} fases concluídas • ${totalStars(progress)} estrelas`;
      document.querySelector('[data-home-stage-count]').textContent = `${completed}/${CAMPAIGN.stages.length}`;
      if (state.overlay === 'upgrades') {
        renderUpgrades(progress);
        document.querySelector('#upgrades-screen').classList.remove('is-hidden');
      }
    } else if (state.screen === 'chapter') renderChapter(progress);
    else if (state.screen === 'victory') {
      const stars = state.stars ?? 1;
      document.querySelector('[data-result-stars]').textContent = `${'★'.repeat(stars)}${'☆'.repeat(3 - stars)}`;
      document.querySelector('[data-result-crystals]').textContent = state.crystalsAwarded ?? 0;
    } else if (state.screen === 'defeat') document.querySelector('[data-defeat-title]').textContent = state.reason ?? 'Os guardiões venceram';
    else if (state.screen === 'compatibility') document.querySelector('[data-compatibility-message]').textContent = state.message;
    if (state.overlay === 'settings') document.querySelector('#settings-screen').classList.remove('is-hidden');
  }

  document.querySelector('[data-home-continue]').addEventListener('click', () => flow.go('chapter'));
  document.querySelector('[data-home-avatar-button]').addEventListener('click', onSelectAvatar);
  document.querySelector('[data-home-upgrades]').addEventListener('click', () => flow.openOverlay('upgrades'));
  document.querySelector('[data-home-rewarded-ad]').addEventListener('click', async () => {
    const result = await showRewardedAd();
    if (!result.granted) {
      render();
      return;
    }
    const awarded = claimRewardedAd(getProgress());
    setProgress(awarded.progress);
    render();
  });
  document.querySelector('[data-open-settings]').addEventListener('click', () => flow.openOverlay('settings'));
  document.querySelector('[data-settings-close]').addEventListener('click', () => flow.resume());
  document.querySelector('[data-upgrades-close]').addEventListener('click', () => flow.resume());
  document.querySelector('[data-chapter-back]').addEventListener('click', () => flow.go('home'));
  const chapterPath = document.querySelector('[data-stage-path]');
  chapterPath.addEventListener('scroll', updateChapterRange, { passive: true });
  document.querySelector('[data-chapter-next]').addEventListener('click', () => {
    chapterPath.scrollBy({ left: Math.max(280, chapterPath.clientWidth * 0.78), behavior: 'smooth' });
  });
  document.querySelector('[data-map-start]').addEventListener('click', () => onStartMission(stageById(activeStageId)));
  queryAll('[data-upgrade]').forEach((button) => button.addEventListener('click', () => {
    try { setProgress(purchaseUpgrade(getProgress(), button.dataset.upgrade).progress); render(); } catch { render(); }
  }));
  document.querySelector('[data-resume-game]').addEventListener('click', () => flow.resume());
  document.querySelector('[data-return-map]').addEventListener('click', () => flow.go('chapter'));
  document.querySelector('[data-result-map]').addEventListener('click', () => flow.go('chapter'));
  document.querySelector('[data-defeat-map]').addEventListener('click', () => flow.go('chapter'));
  document.querySelector('[data-defeat-upgrades]').addEventListener('click', () => {
    flow.go('home');
    flow.openOverlay('upgrades');
  });
  document.querySelector('[data-retry-stage]').addEventListener('click', () => onStartMission(stageById(activeStageId)));
  document.querySelector('[data-next-stage]').addEventListener('click', () => {
    const next = CAMPAIGN.stages[stageById(activeStageId).number];
    if (next) activeStageId = next.id;
    flow.go('chapter');
  });
  document.querySelector('[data-retry-app]').addEventListener('click', () => window.location.reload());

  const unsubscribe = flow.subscribe(render);
  return {
    render,
    dispose() {
      chapterPath.removeEventListener('scroll', updateChapterRange);
      unsubscribe();
    },
    activeStage: () => stageById(activeStageId),
  };
}
