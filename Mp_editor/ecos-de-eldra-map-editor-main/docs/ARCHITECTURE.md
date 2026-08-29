# Arquitetura

## Visão geral

O editor é uma aplicação estática formada por duas camadas visuais sincronizadas:

1. um canvas 2D para grade, seleção e pontos de conexão;
2. um canvas WebGL para renderizar os modelos GLB reais.

O estado do mapa é mantido como um objeto JavaScript serializável. Toda alteração cria um novo estado para permitir desfazer e refazer.

## Módulos

### `src/map-editor.js`

Controla a interface, eventos de mouse/teclado, histórico, seleção simples e múltipla, importação/exportação e sincronização entre os modos 2D e 3D.

### `src/map-editor-core.js`

Contém as operações independentes da interface:

- criar, gerar e validar mapas;
- adicionar, mover, girar, duplicar e remover peças;
- detectar colisões;
- alinhar conexões próximas;
- analisar conexões abertas/fechadas;
- serializar e interpretar JSON.

### `src/map-editor-preview.js`

Gerencia Three.js, carregamento/cache dos GLBs, miniaturas, câmeras, OrbitControls, raycasting, seleção 3D e movimentação sobre o plano do andar.

### `src/dungeon-catalog.js`

Define os 24 módulos estruturais. Cada definição registra arquivo, grupo, footprint, entradas, largura das entradas e comportamento especial.

### `src/dungeon-generator.js`

Gera o layout procedural inicial e fornece cálculos compartilhados de limites e posição mundial das entradas.

## Coordenadas

- A grade possui 50×50 células.
- Cada célula representa 4 unidades no mundo 3D.
- X cresce para a direita.
- Z cresce para baixo na grade.
- Os lados lógicos são N, E, S e W.
- Rotações são inteiros de 0 a 3.
- A altura padrão entre andares é 4,05 unidades.

## Regras de conexão

Duas entradas conectam quando:

- pertencem a peças diferentes;
- ocupam exatamente o mesmo X, Z e andar;
- apontam para direções opostas;
- possuem a mesma largura (`narrow` ou `wide`).

Portas e grades são peças de borda. Fechamentos largos registram `socketWidth: "wide"` e são escalados visualmente para cobrir a abertura.

## Geração procedural

O gerador começa com o layout estrutural do catálogo e expande somente a partir de entradas abertas. Cada candidato é testado contra limites, andares, largura e colisão antes de entrar no mapa.

O valor informado pelo usuário é um limite máximo, não uma obrigação. A geração pode terminar antes se não existir mais encaixe conectado válido. Não são criados blocos isolados para preencher artificialmente a contagem.

## Persistência

- **Salvar** grava o JSON no LocalStorage.
- **Baixar JSON** cria um arquivo portátil.
- **Abrir JSON** restaura um arquivo existente.
- O histórico de desfazer/refazer existe apenas durante a sessão atual.

