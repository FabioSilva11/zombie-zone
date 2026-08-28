import { DUNGEON_PIECES, pieceById, rotatePiece } from '../app/src/main/assets/game/js/dungeon-catalog.js';
import { generateDungeon, placementBounds, socketWorldPosition } from '../app/src/main/assets/game/js/dungeon-generator.js';

const OPPOSITE = Object.freeze({ N: 'S', S: 'N', E: 'W', W: 'E' });

export function createEditorMap(name = 'Novo mapa') {
  return {
    version: 1,
    name,
    width: 50,
    height: 50,
    cellSize: 4,
    levelHeight: 4.05,
    nextId: 1,
    placements: [],
  };
}

export function generateEditorMap(seed = Date.now()) {
  const generated = generateDungeon(seed);
  const map = createEditorMap(`Mapa aleatório ${generated.seed}`);
  map.nextId = generated.placements.length + 1;
  map.placements = generated.placements.map((placement, index) => ({
    ...placement,
    id: `generated-${index + 1}-${placement.pieceId}`,
  }));
  return map;
}

function occupiedLevels(placement) {
  return placement.pieceId === 'stairs' || placement.pieceId === 'stairsWide'
    ? [placement.level, placement.level + 1]
    : [placement.level];
}

function normalizedCandidate(map, input, id = null) {
  const definition = pieceById(input.pieceId);
  return {
    id: id ?? `manual-${map.nextId}-${definition.id}`,
    pieceId: definition.id,
    x: Math.round(Number(input.x) * 2) / 2,
    z: Math.round(Number(input.z) * 2) / 2,
    level: Math.max(0, Math.min(1, Math.round(Number(input.level) || 0))),
    rotation: ((Math.round(Number(input.rotation) || 0) % 4) + 4) % 4,
    edge: definition.edge,
    role: input.role ?? (definition.edge ? 'manual' : undefined),
  };
}

function overlap(a, b) {
  if (a.edge || b.edge) return false;
  if (!occupiedLevels(a).some((level) => occupiedLevels(b).includes(level))) return false;
  const aa = placementBounds(a);
  const bb = placementBounds(b);
  return aa.minX < bb.maxX && aa.maxX > bb.minX && aa.minZ < bb.maxZ && aa.maxZ > bb.minZ;
}

function placementErrors(map, candidate, ignoreId = null) {
  const errors = [];
  if (candidate.edge) {
    if (candidate.x < 0 || candidate.z < 0 || candidate.x > map.width || candidate.z > map.height) {
      errors.push({ code: 'out-of-bounds', message: 'A porta ficou fora do grid.' });
    }
  } else {
    const bounds = placementBounds(candidate);
    if (bounds.minX < 0 || bounds.minZ < 0 || bounds.maxX > map.width || bounds.maxZ > map.height
      || occupiedLevels(candidate).some((level) => level < 0 || level > 1)) {
      errors.push({ code: 'out-of-bounds', message: 'A peça ultrapassa o grid ou os dois andares.' });
    }
  }
  const collision = map.placements.find((placement) => placement.id !== ignoreId && overlap(candidate, placement));
  if (collision) errors.push({ code: 'overlap', message: `A peça sobrepõe ${collision.pieceId}.`, placementId: collision.id });
  return errors;
}

export function addEditorPiece(map, input) {
  let candidate;
  try {
    candidate = normalizedCandidate(map, input);
  } catch (error) {
    return { ok: false, map, error: { code: 'unknown-piece', message: error.message } };
  }
  const errors = placementErrors(map, candidate);
  if (errors.length) return { ok: false, map, error: errors[0] };
  const nextMap = { ...map, nextId: map.nextId + 1, placements: [...map.placements, candidate] };
  return { ok: true, map: nextMap, placement: candidate };
}

export function moveEditorPiece(map, id, x, z, level = null) {
  const current = map.placements.find((placement) => placement.id === id);
  if (!current) return { ok: false, map, error: { code: 'missing-placement', message: 'Peça não encontrada.' } };
  const candidate = normalizedCandidate(map, { ...current, x, z, level: level ?? current.level }, id);
  const errors = placementErrors(map, candidate, id);
  if (errors.length) return { ok: false, map, error: errors[0] };
  const nextMap = { ...map, placements: map.placements.map((placement) => placement.id === id ? candidate : placement) };
  return { ok: true, map: nextMap, placement: candidate };
}

export function rotateEditorPiece(map, id, turns = 1) {
  const current = map.placements.find((placement) => placement.id === id);
  if (!current) return { ok: false, map, error: { code: 'missing-placement', message: 'Peça não encontrada.' } };
  const candidate = normalizedCandidate(map, { ...current, rotation: current.rotation + turns }, id);
  const errors = placementErrors(map, candidate, id);
  if (errors.length) return { ok: false, map, error: errors[0] };
  const nextMap = { ...map, placements: map.placements.map((placement) => placement.id === id ? candidate : placement) };
  return { ok: true, map: nextMap, placement: candidate };
}

export function removeEditorPiece(map, id) {
  return { ...map, placements: map.placements.filter((placement) => placement.id !== id) };
}

export function duplicateEditorPiece(map, id) {
  const current = map.placements.find((placement) => placement.id === id);
  if (!current) return { ok: false, map, error: { code: 'missing-placement', message: 'Peça não encontrada.' } };
  const definition = rotatePiece(pieceById(current.pieceId), current.rotation);
  return addEditorPiece(map, { ...current, x: current.x + definition.footprint.width + 0.5 });
}

function connectionAnalysis(map) {
  const sockets = [];
  for (const placement of map.placements.filter((entry) => !entry.edge)) {
    const rotated = rotatePiece(pieceById(placement.pieceId), placement.rotation);
    rotated.sockets.forEach((socket, index) => {
      if (socket.state === 'blocked') return;
      sockets.push({
        ...socketWorldPosition(placement, socket),
        side: socket.side,
        width: socket.width,
        placementId: placement.id,
        socketIndex: index,
      });
    });
  }
  const connected = new Set();
  const connections = [];
  for (let first = 0; first < sockets.length; first += 1) {
    for (let second = first + 1; second < sockets.length; second += 1) {
      const a = sockets[first];
      const b = sockets[second];
      if (a.placementId === b.placementId || a.x !== b.x || a.z !== b.z || a.level !== b.level) continue;
      if (a.width !== b.width || OPPOSITE[a.side] !== b.side) continue;
      connected.add(first);
      connected.add(second);
      connections.push({ a: a.placementId, b: b.placementId, width: a.width, level: a.level, x: a.x, z: a.z });
    }
  }
  return { connections, openSockets: sockets.filter((_, index) => !connected.has(index)) };
}

export function validateEditorMap(map) {
  const errors = [];
  for (const placement of map.placements) errors.push(...placementErrors(map, placement, placement.id).map((error) => ({ ...error, placementId: placement.id })));
  for (let first = 0; first < map.placements.length; first += 1) {
    for (let second = first + 1; second < map.placements.length; second += 1) {
      if (overlap(map.placements[first], map.placements[second])) {
        errors.push({ code: 'overlap', message: 'Existem peças sobrepostas.', placementId: map.placements[first].id });
      }
    }
  }
  const present = new Set(map.placements.map((placement) => placement.pieceId));
  const missingPieceIds = DUNGEON_PIECES.filter((piece) => !present.has(piece.id)).map((piece) => piece.id);
  const { connections, openSockets } = connectionAnalysis(map);
  return {
    valid: errors.length === 0 && missingPieceIds.length === 0,
    errors,
    missingPieceIds,
    connections,
    openSockets,
    pieceCount: map.placements.length,
  };
}

export function serializeEditorMap(map) {
  return JSON.stringify({
    version: 1,
    name: map.name,
    width: 50,
    height: 50,
    cellSize: 4,
    levelHeight: 4.05,
    nextId: map.nextId,
    placements: map.placements,
  }, null, 2);
}

export function parseEditorMap(json) {
  const source = typeof json === 'string' ? JSON.parse(json) : json;
  if (!source || !Array.isArray(source.placements)) throw new Error('Arquivo de mapa inválido: placements ausente.');
  const map = createEditorMap(String(source.name || 'Mapa importado'));
  map.nextId = Math.max(Number(source.nextId) || 1, source.placements.length + 1);
  map.placements = source.placements.map((placement) => normalizedCandidate(map, placement, String(placement.id)));
  return map;
}
