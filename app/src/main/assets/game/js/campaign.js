const story = (speaker, lines) => Object.freeze({ speaker, lines: Object.freeze(lines) });

const stages = [
  ['Primeiro Alarme', 'Afaste os zumbis e libere a primeira passagem.', 150, 'mapa (1)', 'Nyra', ['As sirenes acordaram a cidade inteira.', 'Vamos limpar esta rua antes que a névoa aumente!'], ['Primeira área liberada!', 'A equipe da Zona Z já pode avançar.']],
  ['Metrô da Névoa', 'Elimine os zumbis escondidos na estação.', 165, 'mapa (4)', 'Kael', ['O metrô está cheio de pegadas verdes.', 'Fique perto das luzes e não pare de se mover.'], ['A estação voltou a brilhar.', 'Mas a horda fugiu para os telhados.']],
  ['Telhados Infectados', 'Suba os prédios e recupere a antena.', 180, 'mapa (7)', 'Nyra', ['A antena pode mostrar onde a horda se esconde.', 'Cuidado com as passarelas estreitas!'], ['Sinal recuperado!', 'O parque abandonado apareceu no radar.']],
  ['Parque Assombrado', 'Abra os portões antes da próxima horda.', 195, 'mapa (10)', 'Kael', ['Os brinquedos estão ligados sem eletricidade.', 'Parece que os zumbis prepararam uma surpresa.'], ['Os portões estão abertos.', 'Encontramos a entrada do laboratório.']],
  ['Laboratório Mutante', 'Desative os tanques que alimentam a névoa.', 210, 'mapa (14)', 'Nyra', ['É daqui que vem toda aquela fumaça verde.', 'Desligue os tanques e mantenha distância das bolhas.'], ['A névoa está desaparecendo.', 'Só falta enfrentar o centro da Zona Z.']],
  ['Coração da Zona Z', 'Vença a última horda e salve a cidade.', 225, 'mapa (20)', 'Kael', ['Chegamos ao ponto onde a invasão começou.', 'Equipe pronta? Esta é a grande missão!'], ['A cidade está segura novamente.', 'Mas novas zonas podem surgir em breve...']],
].map(([title, objective, targetSeconds, mapId, speaker, before, after], index) => Object.freeze({
  id: `stage${index + 1}`,
  number: index + 1,
  title,
  objective,
  targetSeconds,
  mapId,
  firstClearCrystals: 40 + (index + 1) * 10,
  storyBefore: story(speaker, before),
  storyAfter: story(speaker, after),
}));

export const CAMPAIGN = Object.freeze({
  id: 'chapter1',
  title: 'Capítulo I • A Invasão Começa',
  stages: Object.freeze(stages),
});

export function stageById(id) {
  const stage = CAMPAIGN.stages.find((candidate) => candidate.id === id);
  if (!stage) throw new Error(`Fase desconhecida: ${id}`);
  return stage;
}
