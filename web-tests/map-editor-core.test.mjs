import test from 'node:test';
import assert from 'node:assert/strict';
import {
  addEditorPiece,
  createEditorMap,
  generateEditorMap,
  parseEditorMap,
  rotateEditorPiece,
  serializeEditorMap,
  validateEditorMap,
} from '../output/map-editor-core.js';

test('editor starts with the same 50x50 two-level contract as the game', () => {
  const map = createEditorMap('Minha fase');
  assert.equal(map.name, 'Minha fase');
  assert.equal(map.width, 50);
  assert.equal(map.height, 50);
  assert.equal(map.cellSize, 4);
  assert.equal(map.levelHeight, 4.05);
  assert.deepEqual(map.placements, []);
});

test('random editor maps contain all 24 pieces and remain editable', () => {
  const map = generateEditorMap(12345);
  assert.equal(map.width, 50);
  assert.equal(map.placements.length, 24);
  assert.equal(new Set(map.placements.map((placement) => placement.pieceId)).size, 24);
  assert.ok(map.placements.every((placement) => Number.isInteger(placement.rotation)));
  assert.equal(generateEditorMap(12345).name, map.name);
});

test('placing a room rejects overlap but allows a different level', () => {
  let map = createEditorMap();
  ({ map } = addEditorPiece(map, { pieceId: 'roomSmall', x: 10, z: 10, level: 0 }));
  const overlap = addEditorPiece(map, { pieceId: 'corridor', x: 11, z: 11, level: 0 });
  assert.equal(overlap.ok, false);
  const upstairs = addEditorPiece(map, { pieceId: 'corridor', x: 11, z: 11, level: 1 });
  assert.equal(upstairs.ok, true);
});

test('stairs reserve both levels and rotation swaps their footprint', () => {
  let map = createEditorMap();
  const stairs = addEditorPiece(map, { pieceId: 'stairs', x: 20, z: 20, level: 0 });
  assert.equal(stairs.ok, true);
  map = stairs.map;
  assert.equal(addEditorPiece(map, { pieceId: 'corridor', x: 20, z: 20, level: 1 }).ok, false);
  const rotated = rotateEditorPiece(map, stairs.placement.id, 1);
  assert.equal(rotated.ok, true);
  assert.equal(rotated.placement.rotation, 1);
});

test('JSON round trip preserves placement ids, half cells and metadata', () => {
  const initial = createEditorMap('Ruína manual');
  const { map } = addEditorPiece(initial, { pieceId: 'gateDoor', x: 12.5, z: 8, level: 1, rotation: 1 });
  const restored = parseEditorMap(serializeEditorMap(map));
  assert.equal(restored.name, 'Ruína manual');
  assert.deepEqual(restored.placements, map.placements);
});

test('validation reports missing structural pieces and invalid bounds', () => {
  const map = createEditorMap();
  map.placements.push({ id: 'bad', pieceId: 'roomLarge', x: 48, z: 48, level: 0, rotation: 0, edge: false });
  const validation = validateEditorMap(map);
  assert.equal(validation.valid, false);
  assert.ok(validation.errors.some((error) => error.code === 'out-of-bounds'));
  assert.equal(validation.missingPieceIds.length, 23);
});
