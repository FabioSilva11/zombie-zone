const HALF_GATE_LENGTH = 2.2;
const HALF_GATE_DEPTH = 0.7;

export function gateWorldTransform(gate, layout) {
  return Object.freeze({
    x: gate.x * layout.cellSize,
    y: gate.level * layout.levelHeight,
    z: gate.z * layout.cellSize,
    rotationY: -gate.rotation * Math.PI / 2,
  });
}

function isInsideGate(position, gate, layout, radius) {
  const transform = gateWorldTransform(gate, layout);
  const quarterTurn = gate.rotation % 2 !== 0;
  const halfX = (quarterTurn ? HALF_GATE_DEPTH : HALF_GATE_LENGTH) + radius;
  const halfZ = (quarterTurn ? HALF_GATE_LENGTH : HALF_GATE_DEPTH) + radius;
  const sameLevel = Math.abs((position.y ?? transform.y) - transform.y) <= layout.levelHeight * 0.75;
  return sameLevel
    && Math.abs(position.x - transform.x) <= halfX
    && Math.abs(position.z - transform.z) <= halfZ;
}

export function gateBlocksPosition(position, layout, doorsOpened = false, radius = 0) {
  return layout.placements.some((gate) => {
    if (gate.pieceId === 'gate') return false;
    if (!['gateDoor', 'gateDoorWindow', 'gateMetalBars'].includes(gate.pieceId)) return false;
    if (doorsOpened && gate.pieceId !== 'gateMetalBars') return false;
    return isInsideGate(position, gate, layout, radius);
  });
}
