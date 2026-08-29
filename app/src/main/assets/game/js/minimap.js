import { placementBounds } from './dungeon-catalog.js';

export function cellKey(x, z, level = 0) {
  return `${level}:${x}:${z}`;
}

export function createExplorationState(layout) {
  return {
    width: layout.width,
    height: layout.height,
    cellSize: layout.cellSize,
    levelHeight: layout.levelHeight,
    explored: new Set(),
  };
}

export function worldToCell(position, state) {
  const level = Math.max(0, Math.round((position.y ?? 0) / state.levelHeight));
  return {
    x: Math.min(state.width - 1, Math.max(0, Math.floor(position.x / state.cellSize))),
    z: Math.min(state.height - 1, Math.max(0, Math.floor(position.z / state.cellSize))),
    level,
  };
}

export function revealAround(state, position, radiusCells = 2) {
  const center = worldToCell(position, state);
  for (let z = center.z - radiusCells; z <= center.z + radiusCells; z += 1) {
    for (let x = center.x - radiusCells; x <= center.x + radiusCells; x += 1) {
      if (x < 0 || z < 0 || x >= state.width || z >= state.height) continue;
      if (Math.hypot(x - center.x, z - center.z) <= radiusCells + 0.25) {
        state.explored.add(cellKey(x, z, center.level));
      }
    }
  }
  return state;
}

export function isCellExplored(state, x, z, level = 0) {
  return state.explored.has(cellKey(x, z, level));
}

function floorPlacements(layout) {
  return layout.placements.filter((placement) => !placement.edge);
}

function placementLevels(placement) {
  return placement.pieceId === 'stairs' || placement.pieceId === 'stairsWide'
    ? [placement.level, placement.level + 1]
    : [placement.level];
}

function exploredPlacement(state, placement, level) {
  const bounds = placementBounds(placement);
  for (let z = Math.floor(bounds.minZ); z < Math.ceil(bounds.maxZ); z += 1) {
    for (let x = Math.floor(bounds.minX); x < Math.ceil(bounds.maxX); x += 1) {
      if (isCellExplored(state, x, z, level)) return true;
    }
  }
  return false;
}

function mapViewport(layout) {
  const placements = floorPlacements(layout);
  const bounds = placements.reduce((result, placement) => {
    const current = placementBounds(placement);
    return {
      minX: Math.min(result.minX, current.minX),
      maxX: Math.max(result.maxX, current.maxX),
      minZ: Math.min(result.minZ, current.minZ),
      maxZ: Math.max(result.maxZ, current.maxZ),
    };
  }, { minX: Infinity, maxX: -Infinity, minZ: Infinity, maxZ: -Infinity });
  const padding = 1.5;
  const viewSize = Math.max(bounds.maxX - bounds.minX, bounds.maxZ - bounds.minZ) + padding * 2;
  return {
    minX: bounds.minX - (viewSize - (bounds.maxX - bounds.minX)) / 2,
    minZ: bounds.minZ - (viewSize - (bounds.maxZ - bounds.minZ)) / 2,
    viewSize,
  };
}

function drawPlacement(context, placement, level, explored, currentLevel, viewport, scale, size) {
  const bounds = placementBounds(placement);
  const active = level === currentLevel;
  context.globalAlpha = active ? 1 : 0.32;
  context.fillStyle = explored ? (active ? '#c4875d' : '#58678f') : (active ? '#343549' : '#242634');
  context.strokeStyle = explored ? (active ? '#e0a273' : '#7888b2') : '#565974';
  context.lineWidth = Math.max(1, size / 420);
  context.beginPath();
  context.rect(
    (bounds.minX - viewport.minX) * scale,
    (bounds.minZ - viewport.minZ) * scale,
    (bounds.maxX - bounds.minX) * scale,
    (bounds.maxZ - bounds.minZ) * scale,
  );
  context.fill();
  context.stroke();
  context.globalAlpha = 1;
}

function drawGate(context, gate, state, currentLevel, viewport, scale, size) {
  const cellX = Math.min(state.width - 1, Math.max(0, Math.floor(gate.x)));
  const cellZ = Math.min(state.height - 1, Math.max(0, Math.floor(gate.z)));
  if (!isCellExplored(state, cellX, cellZ, gate.level)) return;
  context.globalAlpha = gate.level === currentLevel ? 1 : 0.3;
  context.strokeStyle = gate.pieceId === 'gateMetalBars' ? '#ef7181' : '#64d8c9';
  context.lineWidth = Math.max(2, size / 180);
  const x = (gate.x - viewport.minX) * scale;
  const z = (gate.z - viewport.minZ) * scale;
  const half = scale * 0.45;
  context.beginPath();
  if (gate.rotation % 2) {
    context.moveTo(x, z - half);
    context.lineTo(x, z + half);
  } else {
    context.moveTo(x - half, z);
    context.lineTo(x + half, z);
  }
  context.stroke();
  context.globalAlpha = 1;
}

function drawMap(context, state, layout, player, enemies, size) {
  context.clearRect(0, 0, size, size);
  context.fillStyle = '#111522';
  context.fillRect(0, 0, size, size);
  const viewport = mapViewport(layout);
  const scale = size / viewport.viewSize;
  const currentLevel = worldToCell(player, state).level;
  const placements = floorPlacements(layout);

  for (const requestedLevel of [1 - currentLevel, currentLevel]) {
    for (const placement of placements) {
      if (!placementLevels(placement).includes(requestedLevel)) continue;
      drawPlacement(
        context,
        placement,
        requestedLevel,
        exploredPlacement(state, placement, requestedLevel),
        currentLevel,
        viewport,
        scale,
        size,
      );
    }
  }
  for (const gate of layout.placements.filter((placement) => placement.edge)) {
    drawGate(context, gate, state, currentLevel, viewport, scale, size);
  }

  const drawMarker = (position, color, radius) => {
    context.fillStyle = color;
    context.beginPath();
    context.arc(
      (position.x / layout.cellSize - viewport.minX) * scale,
      (position.z / layout.cellSize - viewport.minZ) * scale,
      radius,
      0,
      Math.PI * 2,
    );
    context.fill();
  };
  for (const enemy of enemies ?? []) {
    if (enemy.health <= 0) continue;
    const position = enemy.root?.position ?? enemy;
    const cell = worldToCell(position, state);
    if (cell.level === currentLevel && isCellExplored(state, cell.x, cell.z, cell.level)) {
      drawMarker(position, '#ff6f7f', Math.max(3, size / 135));
    }
  }
  drawMarker(player, '#54e1d0', Math.max(4, size / 110));
}

export function mountMinimap({ button, overlay, canvas, largeCanvas, layout, getState, onOpen = () => {}, onClose = () => {} }) {
  const paint = () => {
    const snapshot = getState();
    drawMap(canvas.getContext('2d'), snapshot.exploration, layout, snapshot.player, snapshot.enemies, canvas.width);
    if (!overlay.classList.contains('is-hidden')) {
      drawMap(largeCanvas.getContext('2d'), snapshot.exploration, layout, snapshot.player, snapshot.enemies, largeCanvas.width);
    }
  };
  const closeButton = overlay.querySelector('[data-close-map]');
  const open = () => { overlay.classList.remove('is-hidden'); onOpen(); paint(); };
  const close = () => { overlay.classList.add('is-hidden'); onClose(); };
  button.addEventListener('click', open);
  closeButton.addEventListener('click', close);
  return {
    paint,
    dispose() {
      button.removeEventListener('click', open);
      closeButton.removeEventListener('click', close);
      overlay.classList.add('is-hidden');
    },
  };
}
