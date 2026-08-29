# Contribuindo

## Preparação

```bash
npm install
npm run dev
```

## Alterações no catálogo

Ao adicionar ou corrigir um módulo:

1. coloque o GLB em `assets/models/dungeon/`;
2. atualize `src/dungeon-catalog.js`;
3. confirme footprint e entradas na orientação nativa;
4. verifique as quatro rotações;
5. confirme que entradas largas e estreitas não conectam diretamente.

## Convenções

- Preserve o formato JSON versão 1 ou documente uma migração.
- Mantenha regras de mapa em `map-editor-core.js`.
- Mantenha código Three.js em `map-editor-preview.js`.
- Não introduza dependência do jogo principal.
- Evite colocar lógica de conexão diretamente na interface.

## Checklist manual

- Gerar mapas com um e dois andares.
- Alternar entre 2D e 3D.
- Mover e girar curvas e transições.
- Confirmar círculos estreitos e quadrados largos.
- Usar seleção múltipla e desfazer exclusão.
- Exportar e reabrir o JSON.

