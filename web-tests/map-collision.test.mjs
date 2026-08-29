import test from 'node:test';
import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import { DUNGEON_PIECES, pieceById, placementBounds, rotatePiece } from '../app/src/main/assets/game/js/dungeon-catalog.js';
import { DUNGEON_COLLISION_TEMPLATES } from '../app/src/main/assets/game/js/dungeon-collision-catalog.js';
import { prepareBundledMap } from '../app/src/main/assets/game/js/map-library.js';
import {
  buildWallColliders,
  buildFloorColliders,
  cameraColliderBoxes,
  cameraDistanceBeforeWall,
  circleHitsWall,
} from '../app/src/main/assets/game/js/map-collision.js';
import { BUNDLED_MAP } from './bundled-map-fixture.mjs';

const walls = buildWallColliders(BUNDLED_MAP);

test('cada GLB estrutural sólido possui caixas medidas no catálogo do editor', () => {
  const solidPieceIds = DUNGEON_PIECES
    .filter(({ edge }) => !edge)
    .map(({ id }) => id)
    .sort();
  assert.deepEqual(Object.keys(DUNGEON_COLLISION_TEMPLATES).sort(), solidPieceIds);
  for (const pieceId of solidPieceIds) {
    const templates = DUNGEON_COLLISION_TEMPLATES[pieceId];
    assert.ok(templates.length > 0, `${pieceId} sem colisão medida`);
    for (const box of templates) {
      assert.ok(box.minX < box.maxX, `${pieceId} possui largura inválida`);
      assert.ok(box.minZ < box.maxZ, `${pieceId} possui profundidade inválida`);
    }
  }
});

test('todos os 20 mapas usam as caixas por GLB ou os ajustes explícitos do editor', async () => {
  const mapsDirectory = new URL('../app/src/main/assets/game/maps/', import.meta.url);
  const files = (await readdir(mapsDirectory)).filter((file) => file.endsWith('.json')).sort();
  assert.equal(files.length, 20);

  for (const file of files) {
    const source = JSON.parse(await readFile(new URL(encodeURIComponent(file), mapsDirectory), 'utf8'));
    const layout = prepareBundledMap(source, file);
    const generated = buildWallColliders(layout);
    if (Array.isArray(source.colliders)) {
      assert.equal(generated.length, source.colliders.length, `${file} ignorou os ajustes explícitos`);
      continue;
    }

    const expectedCount = layout.placements
      .filter(({ edge }) => !edge)
      .reduce((count, placement) => count + DUNGEON_COLLISION_TEMPLATES[placement.pieceId].length, 0);
    assert.equal(generated.length, expectedCount, `${file} não cobriu todas as peças`);
    const coveredPlacements = new Set(generated.map(({ placementId }) => placementId));
    for (const placement of layout.placements.filter(({ edge }) => !edge)) {
      assert.ok(coveredPlacements.has(placement.id), `${file}: ${placement.id} ficou sem colisão`);
    }
    assert.ok(generated.every(({ kind, source }) => kind === 'wall' && source === 'generated'));
  }
});

test('mapa salvo produz colisores leves para as paredes', () => {
  assert.ok(walls.length > 100);
  assert.ok(walls.length < BUNDLED_MAP.placements.length * 8);
});

test('cruzamento aberto ainda preserva os quatro cantos físicos do GLB', () => {
  const layout = {
    cellSize: 4,
    levelHeight: 4.05,
    placements: [{ id: 'cross-1', pieceId: 'corridorIntersection', x: 10, z: 10, level: 0, rotation: 0, edge: false }],
  };
  const crossWalls = buildWallColliders(layout);
  assert.equal(crossWalls.length, 8);
  assert.equal(circleHitsWall({ x: 42, z: 42 }, 0.4, 0.3, 1.7, crossWalls), false);
  assert.equal(circleHitsWall({ x: 40.25, z: 40.25 }, 0.4, 0.3, 1.7, crossWalls), true);
});

test('mapa com colisores manuais usa exatamente as caixas salvas pelo editor', () => {
  const explicit = [{ id: 'manual-1', minX: 1, maxX: 3, minY: 0, maxY: 4, minZ: 5, maxZ: 6 }];
  const result = buildWallColliders({ ...BUNDLED_MAP, colliders: explicit });
  assert.deepEqual(result, explicit);
  assert.notEqual(result, explicit);
});

test('Rapier recebe pisos planos e rampas para escadas', () => {
  const surfaces = buildFloorColliders(BUNDLED_MAP);
  assert.equal(surfaces.length, BUNDLED_MAP.placements.filter((placement) => !placement.edge).length);
  assert.ok(surfaces.some((surface) => surface.kind === 'ramp' && surface.rotation));
  assert.ok(surfaces.some((surface) => surface.kind === 'floor' && surface.half.y > 0));
});

test('corredor central das escadas permanece livre em todas as rotações', () => {
  for (const pieceId of ['stairs', 'stairsWide']) {
    for (let rotation = 0; rotation < 4; rotation += 1) {
      const placement = { id: `${pieceId}-${rotation}`, pieceId, x: 10, z: 10, level: 0, rotation, edge: false };
      const layout = { cellSize: 4, levelHeight: 4.05, placements: [placement] };
      const bounds = placementBounds(placement);
      const minX = bounds.minX * layout.cellSize;
      const maxX = bounds.maxX * layout.cellSize;
      const minZ = bounds.minZ * layout.cellSize;
      const maxZ = bounds.maxZ * layout.cellSize;
      const walls = buildWallColliders(layout);
      const lowSide = rotatePiece(pieceById(pieceId), rotation).sockets.find(({ levelDelta }) => levelDelta === 0).side;
      for (const progress of [0.1, 0.3, 0.5, 0.7, 0.9]) {
        const position = lowSide === 'E' || lowSide === 'W'
          ? { x: minX + (maxX - minX) * progress, z: (minZ + maxZ) / 2 }
          : { x: (minX + maxX) / 2, z: minZ + (maxZ - minZ) * progress };
        assert.equal(circleHitsWall(position, 0.49, 0.28, 1.78, walls), false, `${pieceId} rotação ${rotation}`);
      }
    }
  }
});

test('cápsula circular bloqueia parede e permite o centro da sala inicial', () => {
  assert.equal(circleHitsWall(BUNDLED_MAP.start, 0.5, 0.28, 1.78, walls), false);
  const firstWall = walls[0];
  const wallCenter = {
    x: (firstWall.minX + firstWall.maxX) / 2,
    z: (firstWall.minZ + firstWall.maxZ) / 2,
  };
  assert.equal(circleHitsWall(wallCenter, 0.5, firstWall.minY + 0.2, firstWall.maxY - 0.2, walls), true);
});

test('câmera para antes da parede considerando seu raio', () => {
  const collider = Object.freeze({ minX: -2, maxX: 2, minY: 0, maxY: 4, minZ: 3, maxZ: 3.34 });
  const distance = cameraDistanceBeforeWall(
    { x: 0, y: 1.2, z: 0 },
    { x: 0, y: 2, z: 6 },
    [collider],
    0.34,
    0.72,
  );
  assert.ok(distance > 2 && distance < 3);
});

test('colisão da câmera usa somente caixas de parede medidas', () => {
  assert.deepEqual(
    cameraColliderBoxes([{ minX: 2, maxX: 6, minY: 0, maxY: 4, minZ: 8, maxZ: 10 }]),
    [{ center: { x: 4, y: 2, z: 9 }, size: { x: 4, y: 4, z: 2 } }],
  );
});
