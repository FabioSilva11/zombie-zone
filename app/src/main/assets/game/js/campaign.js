const story = (speaker, lines) => Object.freeze({ speaker, lines: Object.freeze(lines) });

const missions = [
  ['Primeiro Alarme', 'Afaste os zumbis e libere a primeira passagem.'],
  ['Beco Sem Saída', 'Limpe os becos e encontre a rota de evacuação.'],
  ['Mercado Infestado', 'Recupere os suprimentos cercados pela horda.'],
  ['Metrô da Névoa', 'Elimine os zumbis escondidos na estação.'],
  ['Túneis Perdidos', 'Atravesse os túneis e restabeleça a energia.'],
  ['Estação Fantasma', 'Abra a plataforma bloqueada antes da próxima horda.'],
  ['Telhados Infectados', 'Suba os prédios e recupere a antena.'],
  ['Pontes do Caos', 'Proteja as passarelas que ligam os quarteirões.'],
  ['Antena Sombria', 'Ative o sinal e localize o núcleo da invasão.'],
  ['Parque Assombrado', 'Abra os portões antes da próxima horda.'],
  ['Carrossel Maldito', 'Desligue as máquinas que atraem os infectados.'],
  ['Portões do Parque', 'Defenda a saída enquanto os portões se abrem.'],
  ['Distrito Industrial', 'Retome as fábricas dominadas pela névoa.'],
  ['Laboratório Mutante', 'Desative os tanques que alimentam a névoa.'],
  ['Tanques da Névoa', 'Destrua as válvulas que espalham a contaminação.'],
  ['Câmara de Testes', 'Interrompa os experimentos e liberte a passagem.'],
  ['Ruínas do Centro', 'Reconquiste a avenida tomada pelos mutantes.'],
  ['Torre da Zona', 'Suba a torre e desligue o transmissor inimigo.'],
  ['Última Barreira', 'Rompa a defesa final da horda.'],
  ['Coração da Zona Z', 'Vença a última horda e salve a cidade.'],
];

const stages = missions.map(([title, objective], index) => {
  const number = index + 1;
  const speaker = number % 2 ? 'Nyra' : 'Kael';
  const finalStage = number === missions.length;
  return Object.freeze({
    id: `stage${number}`,
    number,
    title,
    objective,
    targetSeconds: 140 + number * 7,
    mapId: `mapa (${number})`,
    collectibleTotal: 3,
    firstClearCrystals: 40 + number * 10,
    storyBefore: story(speaker, [`A rota para ${title} está aberta.`, objective]),
    storyAfter: story(speaker, finalStage
      ? ['O coração da Zona Z caiu.', 'A cidade está segura novamente!']
      : [`${title} foi liberado.`, 'A equipe já pode avançar para a próxima área.']),
  });
});

export const CAMPAIGN = Object.freeze({
  id: 'chapter1',
  title: 'Capítulo I • A Invasão Começa',
  stages: Object.freeze(stages),
  totalStars: stages.length * 3,
  totalCollectibles: stages.reduce((sum, stage) => sum + stage.collectibleTotal, 0),
});

export function stageById(id) {
  const stage = CAMPAIGN.stages.find((candidate) => candidate.id === id);
  if (!stage) throw new Error(`Fase desconhecida: ${id}`);
  return stage;
}
