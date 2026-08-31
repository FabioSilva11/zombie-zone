import test from 'node:test';
import assert from 'node:assert/strict';
import {
  addEditorCollider,
  buildGeneratedColliders,
  parseEditorMap,
  removeEditorCollider,
  serializeEditorMap,
  updateEditorCollider,
  generateEditorMap,
} from '../src/map-editor-core.js';
import { DUNGEON_PIECES } from '../src/dungeon-catalog.js';

const source = {
  version: 1,
  name: 'Teste',
  width: 50,
  height: 50,
  cellSize: 4,
  levelHeight: 4.05,
  placements: [{ id: 'room-1', pieceId: 'roomSmall', x: 10, z: 10, level: 0, rotation: 0 }],
};

test('mapa antigo recebe colisores de parede editáveis ao ser importado', () => {
  const map = parseEditorMap(source);
  assert.equal(map.collisionMode, 'generated');
  assert.deepEqual(map.colliders, buildGeneratedColliders(map));
  assert.ok(map.colliders.length > 0);
});

test('colisor pode ser criado, movido, redimensionado e removido', () => {
  const map = parseEditorMap(source);
  const added = addEditorCollider(map, { minX: 2, maxX: 8, minY: 0, maxY: 4, minZ: 5, maxZ: 6 });
  assert.equal(added.ok, true);
  assert.equal(added.map.collisionMode, 'custom');
  const updated = updateEditorCollider(added.map, added.collider.id, { minX: 3, maxZ: 7 });
  assert.equal(updated.ok, true);
  assert.equal(updated.collider.minX, 3);
  assert.equal(updated.collider.maxZ, 7);
  assert.equal(removeEditorCollider(updated.map, added.collider.id).colliders.some(({ id }) => id === added.collider.id), false);
});

test('serialização preserva colisores personalizados', () => {
  const original = parseEditorMap({
    ...source,
    collisionMode: 'custom',
    colliders: [{ id: 'custom-1', minX: 1, maxX: 2, minY: 0, maxY: 4, minZ: 3, maxZ: 4 }],
  });
  const restored = parseEditorMap(serializeEditorMap(original));
  assert.deepEqual(restored.colliders, original.colliders);
  assert.equal(restored.collisionMode, 'custom');
});

test('geração respeita os blocos selecionados', () => {
  const allowedPieceIds = DUNGEON_PIECES.map((piece) => piece.id).filter((id) => id !== 'stairsWide');
  const map = generateEditorMap(13579, { maxElements: 40, maxLevels: 2, allowedPieceIds });
  assert.ok(map.placements.every((placement) => allowedPieceIds.includes(placement.pieceId)));
  assert.equal(map.placements.some((placement) => placement.pieceId === 'stairsWide'), false);
});

test('geração usa a quantidade pedida como meta sem ultrapassá-la', () => {
  const target = 100;
  const map = generateEditorMap(410810572, { maxElements: target, maxLevels: 1 });
  assert.equal(map.placements.length, target);
  assert.equal(map.maxElements, target);
});
