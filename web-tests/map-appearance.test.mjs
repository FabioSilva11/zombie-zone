import test from 'node:test';
import assert from 'node:assert/strict';
import { access, readFile, stat } from 'node:fs/promises';
import {
  DUNGEON_TEXTURE_VARIANTS,
  dungeonTextureForMap,
} from '../app/src/main/assets/game/js/map-appearance.js';
import { CAMPAIGN } from '../app/src/main/assets/game/js/campaign.js';

const GAME_ROOT = new URL('../app/src/main/assets/game/', import.meta.url);

test('as três texturas originais estão empacotadas', async () => {
  assert.deepEqual(DUNGEON_TEXTURE_VARIANTS.map(({ file }) => file), [
    'colormap.png',
    'variation-a.png',
    'variation-b.png',
  ]);
  for (const { url } of DUNGEON_TEXTURE_VARIANTS) {
    const fileUrl = new URL(url.replace('./', ''), GAME_ROOT);
    await assert.doesNotReject(access(fileUrl));
    assert.ok((await stat(fileUrl)).size > 1_000, `${url} não pode estar vazio`);
    assert.deepEqual([...((await readFile(fileUrl)).subarray(0, 8))], [137, 80, 78, 71, 13, 10, 26, 10]);
  }
});

test('os 20 mapas recebem as três variações de forma equilibrada', () => {
  const counts = new Map(DUNGEON_TEXTURE_VARIANTS.map(({ id }) => [id, 0]));
  for (let map = 1; map <= 20; map += 1) {
    const variant = dungeonTextureForMap(`mapa (${map}).json`);
    counts.set(variant.id, counts.get(variant.id) + 1);
  }
  assert.deepEqual([...counts.values()].sort((a, b) => a - b), [6, 7, 7]);
});

test('as fases jogáveis da campanha usam todas as paletas', () => {
  const ids = new Set(CAMPAIGN.stages.map((stage) => dungeonTextureForMap(stage.mapId).id));
  assert.deepEqual([...ids].sort(), ['original', 'variation-a', 'variation-b']);
});
