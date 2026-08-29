# Formato JSON do mapa

## Estrutura principal

```json
{
  "version": 1,
  "name": "Mapa modular",
  "width": 50,
  "height": 50,
  "cellSize": 4,
  "levelHeight": 4.05,
  "maxElements": 200,
  "maxLevels": 2,
  "nextId": 121,
  "nextColliderId": 8,
  "placements": [],
  "collisionMode": "custom",
  "colliders": []
}
```

## Placement estrutural

```json
{
  "id": "manual-12-corridorCorner",
  "pieceId": "corridorCorner",
  "x": 24.5,
  "z": 18,
  "level": 0,
  "rotation": 1,
  "edge": false
}
```

| Campo | Tipo | Descrição |
| --- | --- | --- |
| `id` | string | Identificador único da instância. |
| `pieceId` | string | ID presente no catálogo. |
| `x`, `z` | number | Origem lógica na grade; aceita passos de 0,5. |
| `level` | 0 ou 1 | Andar da peça. |
| `rotation` | 0–3 | Rotação em quartos de volta. |
| `edge` | boolean | Indica porta/grade posicionada em uma borda. |
| `role` | string opcional | Uso especial, como fechamento gerado. |
| `socketWidth` | string opcional | Largura de uma porta/grade: `narrow` ou `wide`. |

## Compatibilidade

O importador normaliza rotações, posições e metadados. IDs de peças desconhecidos causam erro para impedir que o mapa seja carregado com modelos ausentes.

Ao integrar o arquivo em outro projeto, preserve `cellSize`, `levelHeight`, `pieceId`, posição, andar e rotação.

## Colisores explícitos

Quando `colliders` não existe, as colisões de parede são calculadas automaticamente a partir das peças. Quando o campo existe — inclusive como lista vazia — ele passa a ser a fonte explícita usada pelo jogo.

```json
{
  "id": "collision-7",
  "kind": "wall",
  "minX": 40,
  "maxX": 48,
  "minY": 0,
  "maxY": 4.05,
  "minZ": 59.83,
  "maxZ": 60.17,
  "source": "manual"
}
```

Os limites usam unidades do mundo, não células. Neste projeto uma célula equivale a `cellSize` (normalmente 4 unidades), e um andar tem `levelHeight` (normalmente 4,05 unidades). `collisionMode` vale `generated` enquanto as caixas seguem as peças e muda para `custom` após qualquer ajuste manual.
