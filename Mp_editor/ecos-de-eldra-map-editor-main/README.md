# Ecos de Eldra — Editor de Mapas

Editor visual independente para montar mapas modulares com os 24 modelos estruturais de **Ecos de Eldra**. A interface combina uma grade 2D de 50×50 células com visualização e edição 3D em Three.js.

## Recursos

- Catálogo com 24 modelos GLB reais e miniaturas 3D.
- Grade lógica de 50×50, com células de 4 unidades.
- Mapas com um ou dois andares.
- Geração procedural configurável de 1 até 200 peças.
- Encaixe automático por pontos de conexão.
- Validação separada para entradas estreitas e largas.
- Fechamento automático das entradas restantes.
- Seleção, movimentação, rotação, duplicação e exclusão.
- Seleção múltipla por caixa, `Shift` ou `Ctrl`.
- Visualização 3D navegável e edição direta dos modelos.
- Histórico de desfazer/refazer.
- Salvamento local e importação/exportação em JSON.
- Biblioteca automática dos JSONs existentes em `../mapas_json`.
- Editor visual de caixas de colisão, com criação, movimento, medidas e exclusão.
- Colisões automáticas medidas diretamente nos GLBs, incluindo curvas, recuos, pilares e paredes internas.

## Executar localmente

Requisitos: Node.js 18 ou mais recente.

```bash
npm install
npm run dev
```

Abra [http://localhost:4175](http://localhost:4175).

O servidor do editor lista automaticamente todos os arquivos `.json` da pasta irmã `mapas_json`. Use **Carregar mapa** para abrir um deles sem procurar o arquivo manualmente.

O editor usa módulos ES e precisa ser servido por HTTP. Abrir `index.html` diretamente pelo explorador de arquivos não é suportado.

## Uso rápido

1. Defina o número máximo de peças e a quantidade de andares.
2. Clique em **Gerar mapa** ou comece com **Novo**.
3. No modo 2D, arraste um modelo do catálogo para a grade.
4. Use os pontos de conexão para conferir os encaixes:
   - círculo: entrada estreita;
   - quadrado: entrada larga;
   - verde: conectada;
   - vermelho: aberta.
5. Alterne para **3D navegar** para inspecionar e editar os modelos reais.
6. Salve no navegador ou use **Baixar JSON**.

## Editar colisões

1. Escolha um mapa na lista e clique em **Carregar mapa**.
2. O modo **Colisões** é aberto automaticamente. As caixas vermelhas representam as paredes calculadas.
3. Arraste no espaço vazio para criar uma caixa, ou arraste uma caixa existente para movê-la.
4. Selecione uma caixa para editar os seis limites exatos no Inspector ou excluí-la.
5. Use **Recriar automáticas** para descartar os ajustes e recalcular todas as paredes pelas peças.
6. Use **Baixar JSON**; o arquivo exportado inclui `colliders` e pode substituir o mapa usado pelo jogo.

Mapas antigos sem `colliders` continuam funcionando: o editor e o jogo calculam as paredes automaticamente.

Se os modelos GLB forem alterados, execute `npm run generate:collisions` para reconstruir o catálogo físico usado pelo editor e pelo jogo.

## Controles principais

### Modo 2D

- Clique: selecionar uma peça.
- Arrastar peça: mover.
- Arrastar no espaço vazio: criar uma seleção múltipla.
- `Shift/Ctrl + clique`: adicionar ou remover da seleção.
- `R` / `Shift+R`: girar à direita ou à esquerda.
- `Delete`: excluir a seleção.
- `Ctrl+Z` / `Ctrl+Y`: desfazer/refazer.

### Modo 3D

- Clique esquerdo: selecionar.
- Arrastar com botão esquerdo: mover a peça selecionada.
- `Shift/Ctrl + clique`: seleção múltipla.
- Botão direito: girar a câmera.
- Botão do meio: deslocar a câmera.
- Roda do mouse: zoom.
- Setas: mover a peça selecionada em passos de meia célula.
- Botões da barra 3D: deslocar, aproximar, afastar e centralizar.

## Conexões modulares

As peças só são conectadas quando posição, andar, direção oposta e largura coincidem. Uma entrada larga nunca conecta diretamente a uma estreita. A peça `corridorTransition` é a ponte permitida entre as duas larguras.

As rotações usam passos de 90°. A orientação lógica da grade e a rotação visual dos GLBs são mantidas sincronizadas.

## Estrutura

```text
.
├── index.html
├── src/
│   ├── map-editor.js
│   ├── map-editor-core.js
│   ├── map-editor-preview.js
│   ├── dungeon-catalog.js
│   └── dungeon-generator.js
├── assets/models/dungeon/
│   ├── *.glb (24 modelos)
│   └── Textures/colormap.png
├── docs/
├── package.json
└── README.md
```

Consulte [Arquitetura](docs/ARCHITECTURE.md), [Formato do mapa](docs/MAP-FORMAT.md) e [Guia de contribuição](CONTRIBUTING.md).

## Tecnologias

- HTML5 e CSS.
- JavaScript ES Modules.
- Three.js 0.185.
- GLTFLoader e OrbitControls.
- LocalStorage e arquivos JSON.

## Estado do projeto

Este repositório contém somente o editor e seus modelos estruturais. O jogo principal não é necessário para executar ou desenvolver o editor.
