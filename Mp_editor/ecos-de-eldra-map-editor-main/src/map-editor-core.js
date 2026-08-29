import { DUNGEON_PIECES, pieceById, rotatePiece } from './dungeon-catalog.js';
import { collisionTemplateByPieceId } from './dungeon-collision-catalog.js';
import { generateDungeon, placementBounds, socketWorldPosition } from './dungeon-generator.js';

const OPPOSITE = Object.freeze({ N: 'S', S: 'N', E: 'W', W: 'E' });
const GENERATED_PIECE_LIMIT = 200;
const MIN_COLLIDER_SIZE = 0.05;

function seededRandom(seed) {
  let state = (Number(seed) >>> 0) || 0x9e3779b9;
  return () => {
    state += 0x6d2b79f5;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function originAtSocket(rotated, socket, target) {
  const { width, height } = rotated.footprint;
  const origins = {
    N: { x: target.x - width / 2, z: target.z },
    S: { x: target.x - width / 2, z: target.z - height },
    E: { x: target.x - width, z: target.z - height / 2 },
    W: { x: target.x, z: target.z - height / 2 },
  };
  return origins[socket.side];
}

export function createEditorMap(name = 'Novo mapa') {
  return {
    version: 1,
    name,
    width: 50,
    height: 50,
    cellSize: 4,
    levelHeight: 4.05,
    maxElements: 24,
    maxLevels: 2,
    nextId: 1,
    nextColliderId: 1,
    placements: [],
    colliders: [],
    collisionMode: 'generated',
  };
}

export function generateEditorMap(seed = Date.now(), options = {}) {
  const generated = generateDungeon(seed);
  const maxElements = Math.max(1, Math.min(GENERATED_PIECE_LIMIT, Math.round(Number(options.maxElements) || 24)));
  const maxLevels = Number(options.maxLevels) === 1 ? 1 : 2;
  const allowed = generated.placements.filter((placement) => {
    if (maxLevels === 2) return true;
    if (placement.pieceId === 'stairs' || placement.pieceId === 'stairsWide') return false;
    return placement.level === 0;
  });
  const selected = allowed.slice(0, maxElements);
  let map = createEditorMap(`Mapa aleatório ${generated.seed}`);
  map.maxElements = maxElements;
  map.maxLevels = maxLevels;
  map.nextId = selected.length + 1;
  map.placements = selected.map((placement, index) => ({
    ...placement,
    id: `generated-${index + 1}-${placement.pieceId}`,
  }));
  const random = seededRandom(Number(seed) + 0x51f15e);
  const repeatableIds = new Set([
    'corridor', 'corridorCorner',
    'corridorWide', 'corridorWideCorner',
    'corridorTransition',
  ]);
  const repeatable = DUNGEON_PIECES.filter((piece) => repeatableIds.has(piece.id));
  while (map.placements.length < maxElements) {
    const openSockets = connectionAnalysis(map).openSockets.filter((socket) => socket.level >= 0 && socket.level < maxLevels);
    if (!openSockets.length) break;
    const closurePoints = new Set(openSockets.map((socket) => `${socket.x}:${socket.z}:${socket.level}:${socket.width}`));
    if (map.placements.length + closurePoints.size >= maxElements) break;
    const options = [];
    for (const target of openSockets) {
      for (const definition of repeatable) {
        for (let rotation = 0; rotation < 4; rotation += 1) {
          const rotated = rotatePiece(definition, rotation);
          for (const socket of rotated.sockets) {
            if (socket.state === 'blocked' || socket.width !== target.width || socket.side !== OPPOSITE[target.side]) continue;
            const level = target.level - socket.levelDelta;
            if (level < 0 || level >= maxLevels) continue;
            const origin = originAtSocket(rotated, socket, target);
            const candidate = normalizedCandidate(map, { pieceId: definition.id, ...origin, level, rotation });
            if (placementErrors(map, candidate).length) continue;
            options.push(candidate);
          }
        }
      }
    }
    if (!options.length) break;
    const candidate = options[Math.floor(random() * options.length)];
    map = { ...map, nextId: map.nextId + 1, placements: [...map.placements, candidate] };
  }
  const closures = new Map();
  for (const socket of connectionAnalysis(map).openSockets.filter((entry) => entry.level >= 0 && entry.level < maxLevels)) {
    closures.set(`${socket.x}:${socket.z}:${socket.level}:${socket.width}`, socket);
  }
  for (const socket of closures.values()) {
    const endDefinition = pieceById(socket.width === 'wide' ? 'corridorWideEnd' : 'corridorEnd');
    let cap = null;
    for (let rotation = 0; rotation < 4 && !cap; rotation += 1) {
      const rotated = rotatePiece(endDefinition, rotation);
      const endSocket = rotated.sockets[0];
      if (endSocket.width !== socket.width || endSocket.side !== OPPOSITE[socket.side]) continue;
      const origin = originAtSocket(rotated, endSocket, socket);
      const candidate = normalizedCandidate(map, { pieceId: endDefinition.id, ...origin, level: socket.level, rotation, role: 'generated-closure' });
      if (!placementErrors(map, candidate).length) cap = candidate;
    }
    if (!cap) {
      cap = normalizedCandidate(map, {
        pieceId: 'gateMetalBars',
        x: socket.x,
        z: socket.z,
        level: socket.level,
        rotation: socket.side === 'E' || socket.side === 'W' ? 1 : 0,
        socketWidth: socket.width,
        role: 'generated-closure',
      });
    }
    map = { ...map, nextId: map.nextId + 1, placements: [...map.placements, cap] };
  }
  map.name = `Mapa aleatório ${generated.seed} • ${map.placements.length} peças • ${maxLevels} andar${maxLevels > 1 ? 'es' : ''}`;
  return regenerateEditorColliders(map);
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
    socketWidth: input.socketWidth ?? (definition.edge ? definition.sockets.find((socket) => socket.state !== 'blocked')?.width ?? 'narrow' : undefined),
    role: input.role ?? (definition.edge ? 'manual' : undefined),
  };
}

function normalizedCollider(map, input, id = null) {
  const values = ['minX', 'maxX', 'minY', 'maxY', 'minZ', 'maxZ'];
  const numeric = Object.fromEntries(values.map((key) => [key, Number(input[key])]));
  if (values.some((key) => !Number.isFinite(numeric[key]))) {
    throw new Error('Colisor inválido: informe limites numéricos para X, Y e Z.');
  }
  const collider = {
    id: id ?? `collision-${map.nextColliderId}`,
    kind: String(input.kind || 'wall'),
    minX: Math.min(numeric.minX, numeric.maxX),
    maxX: Math.max(numeric.minX, numeric.maxX),
    minY: Math.min(numeric.minY, numeric.maxY),
    maxY: Math.max(numeric.minY, numeric.maxY),
    minZ: Math.min(numeric.minZ, numeric.maxZ),
    maxZ: Math.max(numeric.minZ, numeric.maxZ),
    source: input.source === 'generated' ? 'generated' : 'manual',
  };
  if (input.placementId) collider.placementId = String(input.placementId);
  if (collider.maxX - collider.minX < MIN_COLLIDER_SIZE
    || collider.maxY - collider.minY < MIN_COLLIDER_SIZE
    || collider.maxZ - collider.minZ < MIN_COLLIDER_SIZE) {
    throw new Error('O colisor precisa ter largura, altura e profundidade maiores que zero.');
  }
  return collider;
}

function rotateTemplatePoint(x, z, rotation) {
  if (rotation === 1) return { x: -z, z: x };
  if (rotation === 2) return { x: -x, z: -z };
  if (rotation === 3) return { x: z, z: -x };
  return { x, z };
}

export function buildGeneratedColliders(map) {
  const colliders = [];
  const cellSize = Number(map.cellSize) || 4;
  const levelHeight = Number(map.levelHeight) || 4.05;
  for (const placement of map.placements) {
    if (placement.edge) continue;
    const bounds = placementBounds(placement);
    const centerX = (bounds.minX + bounds.maxX) / 2 * cellSize;
    const centerZ = (bounds.minZ + bounds.maxZ) / 2 * cellSize;
    const minY = placement.level * levelHeight;
    const maxY = minY + levelHeight * (
      placement.pieceId === 'stairs' || placement.pieceId === 'stairsWide' ? 2 : 1
    );
    const scale = cellSize / 4;
    collisionTemplateByPieceId(placement.pieceId).forEach((template, index) => {
      const corners = [
        rotateTemplatePoint(template.minX * scale, template.minZ * scale, placement.rotation),
        rotateTemplatePoint(template.maxX * scale, template.minZ * scale, placement.rotation),
        rotateTemplatePoint(template.minX * scale, template.maxZ * scale, placement.rotation),
        rotateTemplatePoint(template.maxX * scale, template.maxZ * scale, placement.rotation),
      ];
      colliders.push({
        id: `generated:${placement.id}:mesh:${index}`,
        kind: 'wall',
        placementId: placement.id,
        minX: centerX + Math.min(...corners.map(({ x }) => x)),
        maxX: centerX + Math.max(...corners.map(({ x }) => x)),
        minY,
        maxY,
        minZ: centerZ + Math.min(...corners.map(({ z }) => z)),
        maxZ: centerZ + Math.max(...corners.map(({ z }) => z)),
        source: 'generated',
      });
    });
  }
  return colliders;
}

export function regenerateEditorColliders(map) {
  return { ...map, colliders: buildGeneratedColliders(map), collisionMode: 'generated' };
}

function syncGeneratedColliders(map) {
  return map.collisionMode === 'generated' ? regenerateEditorColliders(map) : map;
}

export function addEditorCollider(map, input) {
  try {
    const collider = normalizedCollider(map, { ...input, source: 'manual' });
    return {
      ok: true,
      collider,
      map: {
        ...map,
        nextColliderId: map.nextColliderId + 1,
        colliders: [...map.colliders, collider],
        collisionMode: 'custom',
      },
    };
  } catch (error) {
    return { ok: false, map, error: { code: 'invalid-collider', message: error.message } };
  }
}

export function updateEditorCollider(map, id, changes) {
  const current = map.colliders.find((collider) => collider.id === id);
  if (!current) return { ok: false, map, error: { code: 'missing-collider', message: 'Colisor não encontrado.' } };
  try {
    const collider = normalizedCollider(map, { ...current, ...changes, source: 'manual' }, id);
    return {
      ok: true,
      collider,
      map: {
        ...map,
        colliders: map.colliders.map((entry) => entry.id === id ? collider : entry),
        collisionMode: 'custom',
      },
    };
  } catch (error) {
    return { ok: false, map, error: { code: 'invalid-collider', message: error.message } };
  }
}

export function removeEditorCollider(map, id) {
  return {
    ...map,
    colliders: map.colliders.filter((collider) => collider.id !== id),
    collisionMode: 'custom',
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

function socketRecords(map, ignoreId = null) {
  const sockets = [];
  for (const placement of map.placements.filter((entry) => !entry.edge && entry.id !== ignoreId)) {
    const rotated = rotatePiece(pieceById(placement.pieceId), placement.rotation);
    rotated.sockets.forEach((socket, index) => {
      if (socket.state === 'blocked') return;
      sockets.push({
        ...socketWorldPosition(placement, socket),
        side: socket.side,
        width: socket.width,
        levelDelta: socket.levelDelta,
        placementId: placement.id,
        socketIndex: index,
      });
    });
  }
  return sockets;
}

/**
 * Move a candidate so one of its open sockets exactly meets a compatible
 * socket already on the map. Positions remain on the editor's half-cell grid.
 */
export function snapEditorPlacement(map, input, ignoreId = null) {
  const candidate = normalizedCandidate(map, input, input.id ?? null);
  if (candidate.edge) return { snapped: false, placement: candidate };

  const base = candidate;
  const rotated = rotatePiece(pieceById(candidate.pieceId), candidate.rotation);
  let best = null;
  for (const target of socketRecords(map, ignoreId)) {
    for (const socket of rotated.sockets) {
      if (socket.state === 'blocked' || socket.width !== target.width || OPPOSITE[target.side] !== socket.side) continue;
      const currentSocket = socketWorldPosition(base, socket);
      const level = target.level - socket.levelDelta;
      const snappedCandidate = normalizedCandidate(map, {
        ...base,
        x: base.x + target.x - currentSocket.x,
        z: base.z + target.z - currentSocket.z,
        level,
      }, base.id);
      if (placementErrors(map, snappedCandidate, ignoreId).length) continue;
      const distance = Math.hypot(snappedCandidate.x - base.x, snappedCandidate.z - base.z);
      if (distance > 1.25) continue;
      if (!best || distance < best.distance) best = { placement: snappedCandidate, distance };
    }
  }
  return best ? { snapped: true, ...best } : { snapped: false, placement: candidate };
}

export function addEditorPiece(map, input) {
  let candidate;
  let snap;
  try {
    snap = snapEditorPlacement(map, input);
    candidate = snap.placement;
  } catch (error) {
    return { ok: false, map, error: { code: 'unknown-piece', message: error.message } };
  }
  const errors = placementErrors(map, candidate);
  if (errors.length) return { ok: false, map, error: errors[0] };
  const nextMap = syncGeneratedColliders({ ...map, nextId: map.nextId + 1, placements: [...map.placements, candidate] });
  return { ok: true, map: nextMap, placement: candidate, snapped: snap.snapped };
}

export function moveEditorPiece(map, id, x, z, level = null) {
  const current = map.placements.find((placement) => placement.id === id);
  if (!current) return { ok: false, map, error: { code: 'missing-placement', message: 'Peça não encontrada.' } };
  const snap = snapEditorPlacement(map, { ...current, x, z, level: level ?? current.level, id }, id);
  const candidate = snap.placement;
  const errors = placementErrors(map, candidate, id);
  if (errors.length) return { ok: false, map, error: errors[0] };
  const nextMap = syncGeneratedColliders({ ...map, placements: map.placements.map((placement) => placement.id === id ? candidate : placement) });
  return { ok: true, map: nextMap, placement: candidate, snapped: snap.snapped };
}

export function rotateEditorPiece(map, id, turns = 1) {
  const current = map.placements.find((placement) => placement.id === id);
  if (!current) return { ok: false, map, error: { code: 'missing-placement', message: 'Peça não encontrada.' } };
  const candidate = normalizedCandidate(map, { ...current, rotation: current.rotation + turns }, id);
  const errors = placementErrors(map, candidate, id);
  if (errors.length) return { ok: false, map, error: errors[0] };
  const nextMap = syncGeneratedColliders({ ...map, placements: map.placements.map((placement) => placement.id === id ? candidate : placement) });
  return { ok: true, map: nextMap, placement: candidate };
}

export function removeEditorPiece(map, id) {
  return syncGeneratedColliders({ ...map, placements: map.placements.filter((placement) => placement.id !== id) });
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
  const edgeClosures = map.placements.filter((entry) => entry.edge);
  sockets.forEach((socket, index) => {
    const closure = edgeClosures.find((edge) => edge.x === socket.x && edge.z === socket.z && edge.level === socket.level
      && (edge.socketWidth ?? 'narrow') === socket.width);
    if (!closure) return;
    connected.add(index);
    connections.push({ a: socket.placementId, b: closure.id, width: socket.width, level: socket.level, x: socket.x, z: socket.z, closure: true });
  });
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
    valid: errors.length === 0,
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
    maxElements: Math.max(1, Math.min(GENERATED_PIECE_LIMIT, Math.round(Number(map.maxElements) || 24))),
    maxLevels: map.maxLevels === 1 ? 1 : 2,
    nextId: map.nextId,
    nextColliderId: map.nextColliderId,
    placements: map.placements,
    collisionMode: map.collisionMode === 'generated' ? 'generated' : 'custom',
    colliders: map.colliders,
  }, null, 2);
}

export function parseEditorMap(json) {
  const source = typeof json === 'string' ? JSON.parse(json) : json;
  if (!source || !Array.isArray(source.placements)) throw new Error('Arquivo de mapa inválido: placements ausente.');
  const map = createEditorMap(String(source.name || 'Mapa importado'));
  map.maxElements = Math.max(1, Math.min(GENERATED_PIECE_LIMIT, Math.round(Number(source.maxElements) || 24)));
  map.maxLevels = Number(source.maxLevels) === 1 ? 1 : 2;
  map.nextId = Math.max(Number(source.nextId) || 1, source.placements.length + 1);
  map.placements = source.placements.map((placement) => normalizedCandidate(map, placement, String(placement.id)));
  if (Object.hasOwn(source, 'colliders')) {
    if (!Array.isArray(source.colliders)) throw new Error('Arquivo de mapa inválido: colliders precisa ser uma lista.');
    map.colliders = source.colliders.map((collider, index) => normalizedCollider(
      map,
      collider,
      String(collider.id ?? `collision-imported-${index + 1}`),
    ));
    map.collisionMode = source.collisionMode === 'generated' ? 'generated' : 'custom';
  } else {
    map.colliders = buildGeneratedColliders(map);
    map.collisionMode = 'generated';
  }
  map.nextColliderId = Math.max(Number(source.nextColliderId) || 1, map.colliders.length + 1);
  return map;
}
