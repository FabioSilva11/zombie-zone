export function createSelectionModel(characters, selectedId = characters[0]?.id) {
  if (!characters.length) throw new Error('Nenhum personagem disponível.');
  const selected = characters.find((character) => character.id === selectedId) ?? characters[0];
  return {
    selected,
    cards: characters.map((character) => ({
      ...character,
      selected: character.id === selected.id,
    })),
  };
}

export function selectCharacter(model, selectedId) {
  return createSelectionModel(model.cards, selectedId);
}

export function mountSelection(root, characters, onPlay, { selectedId, onSelect } = {}) {
  let model = createSelectionModel(characters, selectedId);
  const grid = root.querySelector('[data-character-grid]');
  const heroName = root.querySelector('[data-selected-name]');
  const playButton = root.querySelector('[data-play-button]');

  const render = () => {
    heroName.textContent = model.selected.name;
    grid.replaceChildren(...model.cards.map((character) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = `character-card${character.selected ? ' is-selected' : ''}`;
      button.dataset.characterId = character.id;
      button.setAttribute('aria-label', `Escolher ${character.name}`);
      button.innerHTML = `
        <img src="./images/characters/character-${character.id}.png" alt="" draggable="false">
        <span>${character.name}</span>
      `;
      button.addEventListener('click', () => {
        model = selectCharacter(model, character.id);
        render();
        onSelect?.(model.selected.id);
      });
      return button;
    }));
  };

  playButton.addEventListener('click', () => onPlay(model.selected.id));
  render();
  return { getSelected: () => model.selected };
}
