import { pieceById, placementBounds, rotatePiece } from './dungeon-catalog.js';

export const BUNDLED_MAP_FILES = Object.freeze(
  Array.from({ length: 20 }, (_, index) => `mapa (${index + 1}).json`),
);

const OPPOSITE = Object.freeze({ N: 'S', S: 'N', E: 'W', W: 'E' });

function stableNumber(value) {
  const numeric = Number(value);
  if (Number.isFinite(numeric)) return Math.abs(Math.trunc(numeric));
  return [...String(value ?? '')].reduce(
    (hash, character) => Math.imul(hash ^ character.charCodeAt(0), 16777619) >>> 0,
    2166136261,
  );
}

function selectedMapIndex(selection) {
  if (selection !== undefined && selection !== null && selection !== '') {
    const directMatch = String(selection).match(/(?:mapa\s*\()?\s*(\d{1,2})\s*\)?/i);
    if (directMatch) {
      const requested = Number(directMatch[1]);
      if (requested >= 1 && requested <= BUNDLED_MAP_FILES.length) return requested - 1;
    }
    return stableNumber(selection) % BUNDLED_MAP_FILES.length;
  }
  return Math.floor(Math.random() * BUNDLED_MAP_FILES.length);
}

function mapSelectionFromUrl() {
  const parameters = new URLSearchParams(globalThis.location?.search ?? '');
  return parameters.get('map') ?? parameters.get('seed') ?? undefined;
}

function placementCenter(placement, layout) {
  const bounds = placementBounds(placement);
  return {
    x: ((bounds.minX + bounds.maxX) / 2) * layout.cellSize,
    y: placement.level * layout.levelHeight,
    z: ((bounds.minZ + bounds.maxZ) / 2) * layout.cellSize,
    level: placement.level,
    placementId: placement.id,
  };
}

function socketPosition(placement, socket) {
  const piece = rotatePiece(pieceById(placement.pieceId), placement.rotation);
  const positions = {
    N: { x: placement.x + piece.footprint.width / 2, z: placement.z },
    S: { x: placement.x + piece.footprint.width / 2, z: placement.z + piece.footprint.height },
    E: { x: placement.x + piece.footprint.width, z: placement.z + piece.footprint.height / 2 },
    W: { x: placement.x, z: placement.z + piece.footprint.height / 2 },
  };
  return { ...positions[socket.side], level: placement.level + socket.levelDelta };
}

function connectionKey(socket, position) {
  return `${Math.round(position.x * 2)}:${Math.round(position.z * 2)}:${position.level}:${socket.width}`;
}

function buildConnections(placements) {
  const socketsByPosition = new Map();
  for (const placement of placements.filter((entry) => !entry.edge)) {
    const piece = rotatePiece(pieceById(placement.pieceId), placement.rotation);
    piece.sockets.forEach((socket, socketIndex) => {
      if (socket.state === 'blocked') return;
      const position = socketPosition(placement, socket);
      const key = connectionKey(socket, position);
      const entries = socketsByPosition.get(key) ?? [];
      entries.push({ placement, socket, socketIndex, position });
      socketsByPosition.set(key, entries);
    });
  }

  const connections = [];
  for (const entries of socketsByPosition.values()) {
    const used = new Set();
    for (let firstIndex = 0; firstIndex < entries.length; firstIndex += 1) {
      if (used.has(firstIndex)) continue;
      const first = entries[firstIndex];
      const secondIndex = entries.findIndex((candidate, index) => (
        index > firstIndex
        && !used.has(index)
        && candidate.placement.id !== first.placement.id
        && candidate.socket.side === OPPOSITE[first.socket.side]
      ));
      if (secondIndex < 0) continue;
      const second = entries[secondIndex];
      used.add(firstIndex);
      used.add(secondIndex);
      connections.push({
        id: `map-connection-${connections.length + 1}`,
        a: first.placement.id,
        b: second.placement.id,
        aSocket: first.socketIndex,
        bSocket: second.socketIndex,
        width: first.socket.width,
        level: first.position.level,
      });
    }
  }
  return connections;
}

function farthestFloorFrom(start, floor, layout) {
  return floor.reduce((best, candidate) => {
    const center = placementCenter(candidate, layout);
    const distance = Math.hypot(center.x - start.x, center.z - start.z)
      + Math.abs(center.y - start.y) * 2;
    return !best || distance > best.distance ? { placement: candidate, center, distance } : best;
  }, null);
}

function chooseStart(floor) {
  return floor.find((placement) => pieceById(placement.pieceId).group === 'room') ?? floor[0];
}

function chooseExit(placements, floor, start, layout) {
  const exitGate = placements.find((placement) => placement.edge && placement.role === 'final')
    ?? placements.find((placement) => (
      placement.edge
      && placement.role !== 'generated-closure'
      && ['gateDoor', 'gateDoorWindow', 'gate'].includes(placement.pieceId)
    ));
  if (exitGate) {
    return {
      x: exitGate.x * layout.cellSize,
      y: exitGate.level * layout.levelHeight,
      z: exitGate.z * layout.cellSize,
      level: exitGate.level,
      placementId: exitGate.anchorPlacementId ?? exitGate.id,
    };
  }
  return farthestFloorFrom(start, floor, layout).center;
}

function chooseZombieSpawns(floor, startPlacement, start, layout) {
  const candidates = floor
    .filter((placement) => placement.id !== startPlacement.id)
    .map((placement) => ({
      placement,
      center: placementCenter(placement, layout),
      room: pieceById(placement.pieceId).group === 'room',
    }))
    .sort((left, right) => {
      if (left.room !== right.room) return left.room ? -1 : 1;
      const leftDistance = Math.hypot(left.center.x - start.x, left.center.z - start.z);
      const rightDistance = Math.hypot(right.center.x - start.x, right.center.z - start.z);
      return rightDistance - leftDistance;
    });
  return candidates.slice(0, 6).map(({ center }, index) => ({
    ...center,
    kind: index % 2 ? 'undead' : 'green',
  }));
}

function normalizeExplicitColliders(source, sourceFile) {
  if (!Object.hasOwn(source, 'colliders')) return undefined;
  if (!Array.isArray(source.colliders)) throw new Error(`Colisores inválidos: ${sourceFile}`);
  return Object.freeze(source.colliders.map((collider, index) => {
    const normalized = {
      id: String(collider.id ?? `${sourceFile}-collider-${index + 1}`),
      kind: String(collider.kind || 'wall'),
      minX: Number(collider.minX), maxX: Number(collider.maxX),
      minY: Number(collider.minY), maxY: Number(collider.maxY),
      minZ: Number(collider.minZ), maxZ: Number(collider.maxZ),
    };
    if (collider.placementId) normalized.placementId = String(collider.placementId);
    if (Object.entries(normalized).some(([key, value]) => key.startsWith('min') || key.startsWith('max') ? !Number.isFinite(value) : false)
      || normalized.minX >= normalized.maxX || normalized.minY >= normalized.maxY || normalized.minZ >= normalized.maxZ) {
      throw new Error(`Colisor inválido em ${sourceFile}: ${normalized.id}`);
    }
    return Object.freeze(normalized);
  }));
}

export function prepareBundledMap(source, sourceFile = 'mapa.json') {
  if (!source || !Array.isArray(source.placements) || source.placements.length === 0) {
    throw new Error(`Mapa inválido: ${sourceFile}`);
  }
  const width = Number(source.width) || 50;
  const height = Number(source.height) || 50;
  const cellSize = Number(source.cellSize) || 4;
  const levelHeight = Number(source.levelHeight) || 4.05;
  const placements = source.placements.map((placement, index) => {
    pieceById(placement.pieceId);
    return {
      ...placement,
      id: String(placement.id ?? `${sourceFile}-${index + 1}`),
      x: Number(placement.x),
      z: Number(placement.z),
      level: Number(placement.level) || 0,
      rotation: ((Number(placement.rotation) || 0) % 4 + 4) % 4,
      edge: Boolean(placement.edge),
    };
  });
  const floor = placements.filter((placement) => !placement.edge);
  if (!floor.length) throw new Error(`Mapa sem piso: ${sourceFile}`);

  const layout = {
    version: source.version ?? 1,
    name: source.name ?? sourceFile.replace(/\.json$/i, ''),
    sourceFile,
    width,
    height,
    cellSize,
    levelHeight,
    size: Math.max(width, height) * cellSize,
    placements,
    colliders: normalizeExplicitColliders(source, sourceFile),
    connections: buildConnections(placements),
  };
  const startPlacement = chooseStart(floor);
  layout.start = placementCenter(startPlacement, layout);
  layout.exit = chooseExit(placements, floor, layout.start, layout);
  layout.zombieSpawns = chooseZombieSpawns(floor, startPlacement, layout.start, layout);
  layout.zombieQuota = layout.zombieSpawns.length;
  return Object.freeze(layout);
}

export async function loadBundledMap(selection = mapSelectionFromUrl()) {
  const sourceFile = BUNDLED_MAP_FILES[selectedMapIndex(selection)];
  const url = new URL(`../maps/${encodeURIComponent(sourceFile)}`, import.meta.url);
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Falha ao carregar ${sourceFile}: HTTP ${response.status}`);
  return prepareBundledMap(await response.json(), sourceFile);
}
