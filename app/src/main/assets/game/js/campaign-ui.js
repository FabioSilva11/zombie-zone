import { CAMPAIGN, stageById } from './campaign.js?v=zombie-zone-16';
import { purchaseUpgrade, upgradeCost } from './progression.js';

const SCREEN_IDS = ['home', 'chapter', 'story', 'preparation', 'victory', 'defeat', 'compatibility'];

export function createChapterView(campaign, progress) {
  return campaign.stages.map((stage) => {
    const record = progress.stages[stage.id];
    const unlocked = progress.unlockedStageIds.includes(stage.id);
    return { ...stage, stars: record?.stars ?? 0, status: record?.completed ? 'completed' : unlocked ? 'available' : 'locked' };
  });
}

const totalStars = (progress) => Object.values(progress.stages).reduce((sum, stage) => sum + (stage.stars ?? 0), 0);
const formatTime = (seconds) => `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;

export function mountCampaignUI({ flow, getProgress, setProgress, onStartMission, onSelectAvatar }) {
  const queryAll = (selector) => [...document.querySelectorAll(selector)];
  let activeStageId = 'stage1';
  let storyPosition = 'before';
  let pendingVictory = {};

  function hideScreens() {
    for (const id of SCREEN_IDS) document.querySelector(`#${id}-screen`)?.classList.add('is-hidden');
    document.querySelector('#pause-screen')?.classList.add('is-hidden');
    document.querySelector('#settings-screen')?.classList.add('is-hidden');
  }

  function renderResources(progress) {
    queryAll('[data-total-stars]').forEach((element) => { element.textContent = totalStars(progress); });
    queryAll('[data-crystals]').forEach((element) => { element.textContent = progress.crystals; });
  }

  function renderChapter(progress) {
    const path = document.querySelector('[data-stage-path]');
    path.replaceChildren(...createChapterView(CAMPAIGN, progress).map((stage) => {
      const button = document.createElement('button');
      button.type = 'button'; button.className = `stage-node ${stage.status}`; button.disabled = stage.status === 'locked';
      button.innerHTML = `<span class="stage-number">${String(stage.number).padStart(2, '0')}</span><span class="stage-glyph">${stage.status === 'locked' ? '◇' : '✦'}</span><strong></strong><small>${'★'.repeat(stage.stars)}${'☆'.repeat(3 - stage.stars)}</small>`;
      button.querySelector('strong').textContent = stage.title;
      button.addEventListener('click', () => {
        activeStageId = stage.id; storyPosition = 'before';
        const viewed = progress.viewedStoryIds.includes(`${stage.id}:before`);
        flow.go(viewed && progress.settings.skipViewedStory ? 'preparation' : 'story', { stageId: stage.id, storyPosition });
      });
      return button;
    }));
  }

  function renderStory(progress, state) {
    const stage = stageById(state.stageId ?? activeStageId);
    activeStageId = stage.id; storyPosition = state.storyPosition ?? storyPosition;
    if (storyPosition === 'after') pendingVictory = state.resultSummary ?? pendingVictory;
    const content = storyPosition === 'after' ? stage.storyAfter : stage.storyBefore;
    document.querySelector('[data-story-label]').textContent = storyPosition === 'after' ? 'DEPOIS DA MISSÃO' : 'ANTES DA MISSÃO';
    document.querySelector('[data-story-speaker]').textContent = content.speaker;
    document.querySelector('[data-story-lines]').replaceChildren(...content.lines.map((line) => Object.assign(document.createElement('p'), { textContent: line })));
    const storyId = `${stage.id}:${storyPosition}`;
    if (!progress.viewedStoryIds.includes(storyId)) setProgress({ ...progress, viewedStoryIds: [...progress.viewedStoryIds, storyId] });
  }

  function renderPreparation(progress) {
    const stage = stageById(activeStageId);
    document.querySelector('[data-preparation-title]').textContent = stage.title;
    document.querySelector('[data-mission-number]').textContent = String(stage.number).padStart(2, '0');
    document.querySelector('[data-mission-objective]').textContent = stage.objective;
    document.querySelector('[data-target-time]').textContent = formatTime(stage.targetSeconds);
    document.querySelector('[data-first-reward]').textContent = stage.firstClearCrystals;
    for (const kind of ['vitality', 'power']) {
      const level = progress.upgrades[kind];
      document.querySelector(`[data-${kind}-level]`).textContent = `Nível ${level}/5`;
      const button = document.querySelector(`[data-upgrade="${kind}"]`);
      const cost = level < 5 ? upgradeCost(kind, level) : null;
      button.disabled = cost === null || progress.crystals < cost;
      document.querySelector(`[data-${kind}-cost]`).textContent = cost ?? 'MAX';
    }
  }

  function render(state = flow.state) {
    const progress = getProgress(); hideScreens(); renderResources(progress);
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
      document.querySelector('[data-home-progress]').textContent = `${completed} de ${CAMPAIGN.stages.length} fases concluídas • ${totalStars(progress)} estrelas`;
    } else if (state.screen === 'chapter') renderChapter(progress);
    else if (state.screen === 'story') renderStory(progress, state);
    else if (state.screen === 'preparation') renderPreparation(progress);
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
  document.querySelector('[data-open-settings]').addEventListener('click', () => flow.openOverlay('settings'));
  document.querySelector('[data-settings-close]').addEventListener('click', () => flow.resume());
  document.querySelector('[data-chapter-back]').addEventListener('click', () => flow.go('home'));
  const continueStory = () => flow.go(storyPosition === 'after' ? 'victory' : 'preparation', { stageId: activeStageId, ...pendingVictory });
  document.querySelector('[data-story-continue]').addEventListener('click', continueStory);
  document.querySelector('[data-story-skip]').addEventListener('click', continueStory);
  document.querySelector('[data-preparation-back]').addEventListener('click', () => flow.go('chapter'));
  document.querySelector('[data-start-mission]').addEventListener('click', () => onStartMission(stageById(activeStageId)));
  queryAll('[data-upgrade]').forEach((button) => button.addEventListener('click', () => {
    try { setProgress(purchaseUpgrade(getProgress(), button.dataset.upgrade).progress); render(); } catch { render(); }
  }));
  document.querySelector('[data-resume-game]').addEventListener('click', () => flow.resume());
  document.querySelector('[data-return-map]').addEventListener('click', () => flow.go('chapter'));
  document.querySelector('[data-result-map]').addEventListener('click', () => flow.go('chapter'));
  document.querySelector('[data-defeat-map]').addEventListener('click', () => flow.go('chapter'));
  document.querySelector('[data-defeat-preparation]').addEventListener('click', () => flow.go('preparation', { stageId: activeStageId }));
  document.querySelector('[data-retry-stage]').addEventListener('click', () => onStartMission(stageById(activeStageId)));
  document.querySelector('[data-next-stage]').addEventListener('click', () => {
    const next = CAMPAIGN.stages[stageById(activeStageId).number];
    if (!next) return flow.go('chapter');
    activeStageId = next.id; storyPosition = 'before'; flow.go('story', { stageId: next.id, storyPosition });
  });
  document.querySelector('[data-retry-app]').addEventListener('click', () => window.location.reload());

  const unsubscribe = flow.subscribe(render);
  return { render, dispose: unsubscribe, activeStage: () => stageById(activeStageId) };
}
