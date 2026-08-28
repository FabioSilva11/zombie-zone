import { DUNGEON_PIECES, pieceById, rotatePiece } from './dungeon-catalog.js';

const OPPOSITE = Object.freeze({ N: 'S', S: 'N', E: 'W', W: 'E' });
const GRID_SIZE = 50;
const CELL_SIZE = 4;
const LEVEL_HEIGHT = 4.05;
const FLOOR_IDS = DUNGEON_PIECES.filter((entry) => !entry.edge).map((entry) => entry.id);

function randomSource(seed) {
  let state = (Number(seed) >>> 0) || 0x9e3779b9;
  return () => {
    state += 0x6d2b79f5;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function shuffled(values, random) {
  const copy = [...values];
  for (let index = copy.length - 1; index > 0; index -= 1) {
    const other = Math.floor(random() * (index + 1));
    [copy[index], copy[other]] = [copy[other], copy[index]];
  }
  return copy;
}

export function placementBounds(placement) {
  const rotated = rotatePiece(pieceById(placement.pieceId), placement.rotation);
  return {
    minX: placement.x,
    minZ: placement.z,
    maxX: placement.x + rotated.footprint.width,
    maxZ: placement.z + rotated.footprint.height,
  };
}

export function socketWorldPosition(placement, socket) {
  const rotated = rotatePiece(pieceById(placement.pieceId), placement.rotation);
  const { width, height } = rotated.footprint;
  const points = {
    N: { x: placement.x + width / 2, z: placement.z },
    S: { x: placement.x + width / 2, z: placement.z + height },
    E: { x: placement.x + width, z: placement.z + height / 2 },
    W: { x: placement.x, z: placement.z + height / 2 },
  };
  return { ...points[socket.side], level: placement.level + socket.levelDelta };
}

function occupiedLevels(placement) {
  return placement.pieceId === 'stairs' || placement.pieceId === 'stairsWide'
    ? [placement.level, placement.level + 1]
    : [placement.level];
}

function overlapsPlacement(candidate, placements) {
  const a = placementBounds(candidate);
  const levels = new Set(occupiedLevels(candidate));
  return placements.some((other) => {
    if (other.edge || !occupiedLevels(other).some((level) => levels.has(level))) return false;
    const b = placementBounds(other);
    return a.minX < b.maxX && a.maxX > b.minX && a.minZ < b.maxZ && a.maxZ > b.minZ;
  });
}

function inBounds(placement) {
  const bounds = placementBounds(placement);
  const levels = occupiedLevels(placement);
  return bounds.minX >= 0 && bounds.minZ >= 0 && bounds.maxX <= GRID_SIZE && bounds.maxZ <= GRID_SIZE
    && levels.every((level) => level >= 0 && level <= 1);
}

function candidateFromSocket(pieceId, rotation, candidateSocketIndex, target) {
  const rotated = rotatePiece(pieceById(pieceId), rotation);
  const socket = rotated.sockets[candidateSocketIndex];
  const level = target.level - socket.levelDelta;
  const { width, height } = rotated.footprint;
  const origins = {
    N: { x: target.x - width / 2, z: target.z },
    S: { x: target.x - width / 2, z: target.z - height },
    E: { x: target.x - width, z: target.z - height / 2 },
    W: { x: target.x, z: target.z - height / 2 },
  };
  return {
    id: '',
    pieceId,
    x: origins[socket.side].x,
    z: origins[socket.side].z,
    level,
    rotation,
    edge: false,
  };
}

function socketsForPlacement(placement, usedSocket = -1) {
  const rotated = rotatePiece(pieceById(placement.pieceId), placement.rotation);
  return rotated.sockets.map((entry, index) => ({
    ...entry,
    ...socketWorldPosition(placement, entry),
    placementId: placement.id,
    socketIndex: index,
  })).filter((_, index) => index !== usedSocket && rotated.sockets[index].state !== 'blocked');
}

function addAttachedPiece(state, pieceId, random, preferredLevel = null) {
  const piece = pieceById(pieceId);
  const options = [];
  for (const target of state.openSockets) {
    if (preferredLevel !== null && target.level !== preferredLevel) continue;
    for (const rotation of shuffled([0, 1, 2, 3], random)) {
      const rotated = rotatePiece(piece, rotation);
      rotated.sockets.forEach((candidateSocket, candidateSocketIndex) => {
        if (candidateSocket.state === 'blocked') return;
        if (candidateSocket.side !== OPPOSITE[target.side] || candidateSocket.width !== target.width) return;
        const candidate = candidateFromSocket(pieceId, rotation, candidateSocketIndex, target);
        if (!inBounds(candidate) || overlapsPlacement(candidate, state.placements)) return;
        options.push({ candidate, target, candidateSocketIndex, candidateSocket });
      });
    }
  }
  if (!options.length && preferredLevel !== null) return addAttachedPiece(state, pieceId, random, null);
  if (!options.length) return false;
  const option = shuffled(options, random)[0];
  option.candidate.id = `p${state.nextId++}-${pieceId}`;
  state.placements.push(option.candidate);
  state.openSockets = state.openSockets.filter((entry) => !(entry.placementId === option.target.placementId && entry.socketIndex === option.target.socketIndex));
  state.openSockets.push(...socketsForPlacement(option.candidate, option.candidateSocketIndex));
  state.connections.push({
    id: `c${state.connections.length}`,
    a: option.target.placementId,
    b: option.candidate.id,
    aSocket: option.target.socketIndex,
    bSocket: option.candidateSocketIndex,
    width: option.target.width,
    level: option.target.level,
  });
  return true;
}

function centerOf(placement) {
  const bounds = placementBounds(placement);
  return {
    x: ((bounds.minX + bounds.maxX) / 2) * CELL_SIZE,
    z: ((bounds.minZ + bounds.maxZ) / 2) * CELL_SIZE,
    y: placement.level * LEVEL_HEIGHT,
    level: placement.level,
    placementId: placement.id,
  };
}

function gateAtSocket(pieceId, id, source, role = 'decoration') {
  return {
    id,
    pieceId,
    x: source.x,
    z: source.z,
    level: source.level,
    rotation: source.side === 'E' || source.side === 'W' ? 1 : 0,
    edge: true,
    role,
    anchorPlacementId: source.placementId,
  };
}

function decorateWithGates(state, random) {
  const narrowConnections = state.connections.filter((entry) => entry.width === 'narrow');
  const corridorEnd = state.placements.find((entry) => entry.pieceId === 'corridorEnd');
  const finalConnection = state.connections.find((entry) => entry.b === corridorEnd?.id) ?? narrowConnections.at(-1);
  const connectionSocket = (connection) => {
    const placement = state.placements.find((entry) => entry.id === connection.a);
    const rotated = rotatePiece(pieceById(placement.pieceId), placement.rotation);
    const socketEntry = rotated.sockets[connection.aSocket];
    return { ...socketWorldPosition(placement, socketEntry), side: socketEntry.side, placementId: placement.id };
  };
  const remaining = shuffled(narrowConnections.filter((entry) => entry !== finalConnection), random);
  const gateDefinitions = [
    ['gateDoor', finalConnection, 'final'],
    ['gateDoorWindow', remaining[0] ?? finalConnection, 'door'],
    ['gate', remaining[1] ?? finalConnection, 'open'],
  ];
  for (const [pieceId, connection, role] of gateDefinitions) {
    const gate = gateAtSocket(pieceId, `p${state.nextId++}-${pieceId}`, connectionSocket(connection), role);
    state.placements.push(gate);
    connection.gateId = gate.id;
  }
  const blockedTarget = shuffled(state.openSockets.filter((entry) => entry.width === 'narrow' && entry.level === 0), random)[0]
    ?? state.openSockets[0];
  state.placements.push(gateAtSocket('gateMetalBars', `p${state.nextId++}-gateMetalBars`, blockedTarget, 'blocked'));
  return { finalConnection, corridorEnd };
}

function buildAttempt(seed) {
  const random = randomSource(seed);
  const startPiece = rotatePiece(pieceById('roomLarge'), Math.floor(random() * 4));
  const start = {
    id: 'p0-roomLarge', pieceId: 'roomLarge', x: 25 - startPiece.footprint.width / 2,
    z: 25 - startPiece.footprint.height / 2, level: 0, rotation: startPiece.rotation, edge: false,
  };
  const state = {
    nextId: 1,
    placements: [start],
    connections: [],
    openSockets: socketsForPlacement(start),
  };
  const narrowPrefix = ['corridorIntersection', 'roomLargeVariation', 'stairs', 'roomSmallVariation'];
  for (const pieceId of narrowPrefix) {
    const preferredLevel = pieceId === 'roomSmallVariation' ? 1 : 0;
    if (!addAttachedPiece(state, pieceId, random, preferredLevel)) return null;
  }
  const remainingNarrow = shuffled([
    'corridorCorner', 'corridorEnd', 'corridorJunction', 'corridor', 'roomCorner',
    'roomSmall', 'roomWideVariation', 'roomWide',
  ], random);
  for (const pieceId of remainingNarrow) if (!addAttachedPiece(state, pieceId, random)) return null;
  if (!addAttachedPiece(state, 'corridorTransition', random, 0)) return null;
  if (!addAttachedPiece(state, 'corridorWideIntersection', random, 0)) return null;
  if (!addAttachedPiece(state, 'stairsWide', random, 0)) return null;
  if (!addAttachedPiece(state, 'corridorWideJunction', random, 1)) return null;
  for (const pieceId of shuffled(['corridorWideCorner', 'corridorWideEnd', 'corridorWide'], random)) {
    if (!addAttachedPiece(state, pieceId, random)) return null;
  }
  const { corridorEnd } = decorateWithGates(state, random);
  const rooms = shuffled(state.placements.filter((entry) => !entry.edge && pieceById(entry.pieceId).group === 'room' && entry !== start), random);
  const spawnSources = [...rooms, ...state.placements.filter((entry) => !entry.edge && entry !== start)].slice(0, 6);
  const layout = {
    seed: Number(seed) >>> 0,
    width: GRID_SIZE,
    height: GRID_SIZE,
    cellSize: CELL_SIZE,
    levelHeight: LEVEL_HEIGHT,
    size: GRID_SIZE * CELL_SIZE,
    placements: state.placements,
    connections: state.connections,
    start: centerOf(start),
    exit: centerOf(corridorEnd ?? state.placements.at(-1)),
    zombieQuota: 6,
    zombieSpawns: spawnSources.map((entry, index) => ({ ...centerOf(entry), kind: index % 2 ? 'undead' : 'green' })),
  };
  return validateDungeon(layout).valid ? layout : null;
}

export function validateDungeon(layout) {
  const errors = [];
  const ids = new Set(layout.placements.map((entry) => entry.pieceId));
  for (const piece of DUNGEON_PIECES) if (!ids.has(piece.id)) errors.push(`missing:${piece.id}`);
  const floor = layout.placements.filter((entry) => !entry.edge);
  for (const placement of floor) if (!inBounds(placement)) errors.push(`bounds:${placement.id}`);
  for (let i = 0; i < floor.length; i += 1) {
    for (let j = i + 1; j < floor.length; j += 1) {
      if (overlapsPlacement(floor[i], [floor[j]])) errors.push(`overlap:${floor[i].id}:${floor[j].id}`);
    }
  }
  const adjacency = new Map(floor.map((entry) => [entry.id, []]));
  for (const connection of layout.connections) {
    adjacency.get(connection.a)?.push(connection.b);
    adjacency.get(connection.b)?.push(connection.a);
  }
  const visited = new Set();
  const queue = [layout.start?.placementId];
  while (queue.length) {
    const id = queue.shift();
    if (!id || visited.has(id)) continue;
    visited.add(id);
    queue.push(...(adjacency.get(id) ?? []));
  }
  if (visited.size !== floor.length) errors.push(`disconnected:${visited.size}/${floor.length}`);
  if (!floor.some((entry) => entry.level === 0) || !floor.some((entry) => entry.level === 1)) errors.push('levels');
  return { valid: errors.length === 0, errors };
}

export function generateDungeon(seed = Date.now()) {
  const base = Number(seed) >>> 0;
  for (let attempt = 0; attempt < 80; attempt += 1) {
    const candidate = buildAttempt(base + attempt * 2654435761);
    if (candidate) return candidate;
  }
  const fallback = buildAttempt(8128);
  if (fallback) return fallback;
  throw new Error('Não foi possível gerar uma masmorra modular válida.');
}

export const DUNGEON_CONSTANTS = Object.freeze({
  width: GRID_SIZE,
  height: GRID_SIZE,
  cellSize: CELL_SIZE,
  levelHeight: LEVEL_HEIGHT,
  floorPieceIds: Object.freeze(FLOOR_IDS),
});
