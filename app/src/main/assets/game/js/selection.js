export function createSelectionModel(characters, selectedId = characters[0]?.id, unlockedAvatarIds = ['a']) {
  if (!characters.length) throw new Error('Nenhum personagem disponível.');
  const owned = new Set(unlockedAvatarIds);
  const cards = characters.map((character) => ({
    ...character,
    owned: owned.has(character.id),
    selected: character.id === selectedId,
  }));
  const selected = cards.find((character) => character.id === selectedId) ?? cards[0];
  if (!cards.some((character) => character.selected)) cards[0].selected = true;
  return {
    selected,
    cards,
  };
}

export function selectCharacter(model, selectedId) {
  return createSelectionModel(model.cards, selectedId, model.cards.filter((card) => card.owned).map((card) => card.id));
}

export function selectionAction(model, crystals) {
  if (model.selected.owned) return { kind: 'equip', label: 'USAR HERÓI', disabled: false };
  const canAfford = Math.max(0, Number(crystals) || 0) >= model.selected.price;
  return { kind: 'purchase', label: `DESBLOQUEAR ◆ ${model.selected.price}`, disabled: !canAfford };
}

export function mountSelection(root, characters, onPlay, {
  selectedId,
  onSelect,
  onPurchase = () => false,
  onBack = () => {},
  getCrystals = () => 0,
  getUnlockedAvatarIds = () => ['a'],
} = {}) {
  let model = createSelectionModel(characters, selectedId, getUnlockedAvatarIds());
  const grid = root.querySelector('[data-character-grid]');
  const heroName = root.querySelector('[data-selected-name]');
  const playButton = root.querySelector('[data-play-button]');
  const playLabel = playButton.querySelector('span');

  const render = () => {
    heroName.textContent = model.selected.name;
    const action = selectionAction(model, getCrystals());
    playLabel.textContent = action.label;
    playButton.disabled = action.disabled;
    playButton.dataset.action = action.kind;
    grid.replaceChildren(...model.cards.map((character) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `character-card${character.selected ? ' is-selected' : ''}${character.owned ? ' is-owned' : ' is-locked'}`;
      button.dataset.characterId = character.id;
      button.setAttribute('aria-label', `${character.owned ? 'Escolher' : 'Visualizar skin bloqueada'} ${character.name}`);
      button.innerHTML = `
        <img src="./images/characters/character-${character.id}.png" alt="" draggable="false">
        <span>${character.name}</span><small>${character.owned ? 'LIBERADO' : `◆ ${character.price}`}</small>
      `;
      button.addEventListener('click', () => {
        model = selectCharacter(model, character.id);
        render();
        onSelect?.(model.selected.id);
      });
      return button;
    }));
  };

  playButton.addEventListener('click', () => {
    const action = selectionAction(model, getCrystals());
    if (action.disabled) return;
    if (action.kind === 'equip') onPlay(model.selected.id);
    else if (onPurchase(model.selected.id) !== false) {
      model = createSelectionModel(characters, model.selected.id, getUnlockedAvatarIds());
      render();
      onSelect?.(model.selected.id);
    }
  });
  root.querySelector('[data-avatar-back]').addEventListener('click', onBack);
  render();
  return {
    getSelected: () => model.selected,
    refresh(nextSelectedId = model.selected.id) {
      model = createSelectionModel(characters, nextSelectedId, getUnlockedAvatarIds());
      render();
    },
  };
}
