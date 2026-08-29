import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { DUNGEON_PIECES } from '../src/dungeon-catalog.js';

globalThis.self = globalThis;
globalThis.createImageBitmap = async () => ({ width: 1, height: 1, close() {} });
const originalWarn = console.warn;
console.warn = (...messages) => {
  if (!messages.map(String).join(' ').includes("Couldn't load texture")) originalWarn(...messages);
};

const SCRIPT_ROOT = dirname(fileURLToPath(import.meta.url));
const EDITOR_ROOT = resolve(SCRIPT_ROOT, '..');
const MODEL_ROOT = resolve(EDITOR_ROOT, 'assets/models/dungeon');
const GAME_OUTPUT = resolve(EDITOR_ROOT, '../../app/src/main/assets/game/js/dungeon-collision-catalog.js');
const EDITOR_OUTPUT = resolve(EDITOR_ROOT, 'src/dungeon-collision-catalog.js');
const SAMPLE_STEP = 0.5;
const WALL_FOOTPRINT_Y = 0.2;
const EPSILON = 0.016;

function triangleContains(x, z, [a, b, c]) {
  const first = (a.x - c.x) * (z - c.z) - (a.z - c.z) * (x - c.x);
  const second = (b.x - a.x) * (z - a.z) - (b.z - a.z) * (x - a.x);
  const third = (c.x - b.x) * (z - b.z) - (c.z - b.z) * (x - b.x);
  return (first >= -1e-6 && second >= -1e-6 && third >= -1e-6)
    || (first <= 1e-6 && second <= 1e-6 && third <= 1e-6);
}

function largestRectangle(grid) {
  const rows = grid.length;
  const columns = grid[0].length;
  let best = null;
  for (let top = 0; top < rows; top += 1) {
    const available = Array(columns).fill(true);
    for (let bottom = top; bottom < rows; bottom += 1) {
      for (let column = 0; column < columns; column += 1) {
        available[column] = available[column] && grid[bottom][column];
      }
      let start = 0;
      while (start < columns) {
        while (start < columns && !available[start]) start += 1;
        let end = start;
        while (end < columns && available[end]) end += 1;
        const area = (end - start) * (bottom - top + 1);
        if (area && (!best || area > best.area)) {
          best = { x: start, z: top, width: end - start, height: bottom - top + 1, area };
        }
        start = end + 1;
      }
    }
  }
  return best;
}

function decomposeGrid(grid) {
  const remaining = grid.map((row) => [...row]);
  const rectangles = [];
  while (remaining.some((row) => row.some(Boolean))) {
    const rectangle = largestRectangle(remaining);
    if (!rectangle) break;
    rectangles.push(rectangle);
    for (let z = rectangle.z; z < rectangle.z + rectangle.height; z += 1) {
      for (let x = rectangle.x; x < rectangle.x + rectangle.width; x += 1) remaining[z][x] = false;
    }
  }
  return rectangles;
}

async function loadWallTriangles(definition) {
  const bytes = await readFile(resolve(MODEL_ROOT, definition.file));
  const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  const gltf = await new GLTFLoader().parseAsync(buffer, '');
  gltf.scene.updateMatrixWorld(true);
  const sceneBounds = new THREE.Box3().setFromObject(gltf.scene);
  const center = sceneBounds.getCenter(new THREE.Vector3());
  const triangles = [];
  gltf.scene.traverse((node) => {
    if (!node.isMesh) return;
    const positions = node.geometry.attributes.position;
    const indices = node.geometry.index;
    const triangleCount = indices ? indices.count : positions.count;
    for (let offset = 0; offset < triangleCount; offset += 3) {
      const points = [0, 1, 2].map((index) => {
        const vertexIndex = indices ? indices.getX(offset + index) : offset + index;
        return new THREE.Vector3().fromBufferAttribute(positions, vertexIndex).applyMatrix4(node.matrixWorld);
      });
      const normal = new THREE.Vector3().crossVectors(
        points[1].clone().sub(points[0]),
        points[2].clone().sub(points[0]),
      ).normalize();
      if (normal.y < 0.99 || !points.every(({ y }) => Math.abs(y - WALL_FOOTPRINT_Y) <= EPSILON)) continue;
      triangles.push(points.map((point) => ({ x: point.x - center.x, z: point.z - center.z })));
    }
  });
  return triangles;
}

async function templateFor(definition) {
  const triangles = await loadWallTriangles(definition);
  if (!triangles.length) throw new Error(`Nenhuma seção de parede encontrada em ${definition.file}.`);
  const minX = -definition.footprint.width * 2;
  const minZ = -definition.footprint.height * 2;
  const columns = Math.round(definition.footprint.width * 4 / SAMPLE_STEP);
  const rows = Math.round(definition.footprint.height * 4 / SAMPLE_STEP);
  const grid = Array.from({ length: rows }, (_, z) => Array.from({ length: columns }, (_, x) => (
    triangles.some((triangle) => triangleContains(
      minX + (x + 0.5) * SAMPLE_STEP,
      minZ + (z + 0.5) * SAMPLE_STEP,
      triangle,
    ))
  )));
  return decomposeGrid(grid).map(({ x, z, width, height }) => ({
    minX: minX + x * SAMPLE_STEP,
    maxX: minX + (x + width) * SAMPLE_STEP,
    minZ: minZ + z * SAMPLE_STEP,
    maxZ: minZ + (z + height) * SAMPLE_STEP,
  })).sort((left, right) => left.minZ - right.minZ || left.minX - right.minX);
}

const templates = {};
for (const definition of DUNGEON_PIECES.filter(({ edge }) => !edge)) {
  templates[definition.id] = await templateFor(definition);
  console.log(`${definition.id}: ${templates[definition.id].length} caixas`);
}

const source = `// Gerado por scripts/generate-collision-templates.mjs a partir dos GLBs reais.\n`
  + `// Não edite manualmente: execute npm run generate:collisions.\n`
  + `export const DUNGEON_COLLISION_TEMPLATES = Object.freeze(${JSON.stringify(templates, null, 2)});\n\n`
  + `export function collisionTemplateByPieceId(pieceId) {\n`
  + `  const template = DUNGEON_COLLISION_TEMPLATES[pieceId];\n`
  + `  if (!template) throw new Error(\`Template de colisão desconhecido: \${pieceId}\`);\n`
  + `  return template;\n`
  + `}\n`;

await Promise.all([
  writeFile(EDITOR_OUTPUT, source, 'utf8'),
  writeFile(GAME_OUTPUT, source, 'utf8'),
]);
console.log(`Catálogos gravados em:\n- ${EDITOR_OUTPUT}\n- ${GAME_OUTPUT}`);
