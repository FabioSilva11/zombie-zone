const SIDES = Object.freeze(['N', 'E', 'S', 'W']);

const socket = (side, width = 'narrow', levelDelta = 0, state = 'open') => Object.freeze({
  side,
  width,
  levelDelta,
  state,
});

const piece = ({ id, file, group, width, height, sockets, edge = false, required = true }) => Object.freeze({
  id,
  file,
  url: `./models/dungeon/${file}`,
  group,
  footprint: Object.freeze({ width, height }),
  sockets: Object.freeze(sockets),
  edge,
  required,
});

export const DUNGEON_PIECES = Object.freeze([
  piece({ id: 'corridorCorner', file: 'corridor-corner.glb', group: 'corridor', width: 1, height: 1, sockets: [socket('N'), socket('W')] }),
  piece({ id: 'corridorEnd', file: 'corridor-end.glb', group: 'corridor', width: 1, height: 1, sockets: [socket('E')] }),
  piece({ id: 'corridorIntersection', file: 'corridor-intersection.glb', group: 'corridor', width: 1, height: 1, sockets: [socket('N'), socket('S'), socket('E'), socket('W')] }),
  piece({ id: 'corridorJunction', file: 'corridor-junction.glb', group: 'corridor', width: 1, height: 1, sockets: [socket('N'), socket('E'), socket('W')] }),
  piece({ id: 'corridorTransition', file: 'corridor-transition.glb', group: 'corridor', width: 2, height: 2, sockets: [socket('E', 'wide'), socket('W')] }),
  piece({ id: 'corridorWideCorner', file: 'corridor-wide-corner.glb', group: 'corridor', width: 2, height: 2, sockets: [socket('N', 'wide'), socket('W', 'wide')] }),
  piece({ id: 'corridorWideEnd', file: 'corridor-wide-end.glb', group: 'corridor', width: 2, height: 2, sockets: [socket('E', 'wide')] }),
  piece({ id: 'corridorWideIntersection', file: 'corridor-wide-intersection.glb', group: 'corridor', width: 2, height: 2, sockets: [socket('N', 'wide'), socket('S', 'wide'), socket('E', 'wide'), socket('W', 'wide')] }),
  piece({ id: 'corridorWideJunction', file: 'corridor-wide-junction.glb', group: 'corridor', width: 2, height: 2, sockets: [socket('N', 'wide'), socket('E', 'wide'), socket('W', 'wide')] }),
  piece({ id: 'corridorWide', file: 'corridor-wide.glb', group: 'corridor', width: 2, height: 2, sockets: [socket('E', 'wide'), socket('W', 'wide')] }),
  piece({ id: 'corridor', file: 'corridor.glb', group: 'corridor', width: 1, height: 1, sockets: [socket('E'), socket('W')] }),
  piece({ id: 'gateDoorWindow', file: 'gate-door-window.glb', group: 'gate', width: 1, height: 1, sockets: [socket('N', 'narrow', 0, 'door'), socket('S', 'narrow', 0, 'door')], edge: true }),
  piece({ id: 'gateDoor', file: 'gate-door.glb', group: 'gate', width: 1, height: 1, sockets: [socket('N', 'narrow', 0, 'door'), socket('S', 'narrow', 0, 'door')], edge: true }),
  piece({ id: 'gateMetalBars', file: 'gate-metal-bars.glb', group: 'gate', width: 1, height: 1, sockets: [socket('N', 'narrow', 0, 'blocked'), socket('S', 'narrow', 0, 'blocked')], edge: true }),
  piece({ id: 'gate', file: 'gate.glb', group: 'gate', width: 1, height: 1, sockets: [socket('N'), socket('S')], edge: true }),
  piece({ id: 'roomCorner', file: 'room-corner.glb', group: 'room', width: 3, height: 3, sockets: [socket('N'), socket('W')] }),
  piece({ id: 'roomLargeVariation', file: 'room-large-variation.glb', group: 'room', width: 5, height: 5, sockets: [socket('N'), socket('S'), socket('E'), socket('W')] }),
  piece({ id: 'roomLarge', file: 'room-large.glb', group: 'room', width: 5, height: 5, sockets: [socket('N'), socket('S'), socket('E'), socket('W')] }),
  piece({ id: 'roomSmallVariation', file: 'room-small-variation.glb', group: 'room', width: 3, height: 3, sockets: [socket('N'), socket('S'), socket('E'), socket('W')] }),
  piece({ id: 'roomSmall', file: 'room-small.glb', group: 'room', width: 3, height: 3, sockets: [socket('N'), socket('S'), socket('E'), socket('W')] }),
  piece({ id: 'roomWideVariation', file: 'room-wide-variation.glb', group: 'room', width: 5, height: 3, sockets: [socket('E'), socket('W')] }),
  piece({ id: 'roomWide', file: 'room-wide.glb', group: 'room', width: 5, height: 3, sockets: [socket('N'), socket('S'), socket('E'), socket('W')] }),
  piece({ id: 'stairsWide', file: 'stairs-wide.glb', group: 'stairs', width: 2, height: 2, sockets: [socket('N', 'wide', 0), socket('S', 'wide', 1)] }),
  piece({ id: 'stairs', file: 'stairs.glb', group: 'stairs', width: 1, height: 2, sockets: [socket('N', 'narrow', 0), socket('S', 'narrow', 1)] }),
]);

const PIECES_BY_ID = new Map(DUNGEON_PIECES.map((entry) => [entry.id, entry]));

export const STRUCTURAL_MODEL_URLS = Object.freeze(
  Object.fromEntries(DUNGEON_PIECES.map((entry) => [entry.id, entry.url])),
);

export function pieceById(id) {
  const found = PIECES_BY_ID.get(id);
  if (!found) throw new Error(`Peça estrutural desconhecida: ${id}`);
  return found;
}

export function rotateSide(side, turns) {
  const normalized = ((turns % 4) + 4) % 4;
  return SIDES[(SIDES.indexOf(side) + normalized) % 4];
}

export function rotatePiece(source, turns = 0) {
  const rotation = ((turns % 4) + 4) % 4;
  const swap = rotation % 2 === 1;
  return Object.freeze({
    ...source,
    rotation,
    footprint: Object.freeze({
      width: swap ? source.footprint.height : source.footprint.width,
      height: swap ? source.footprint.width : source.footprint.height,
    }),
    sockets: Object.freeze(source.sockets.map((entry) => Object.freeze({
      ...entry,
      side: rotateSide(entry.side, rotation),
    }))),
  });
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
