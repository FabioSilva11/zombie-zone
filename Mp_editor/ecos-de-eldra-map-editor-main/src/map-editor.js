import { DUNGEON_PIECES, pieceById, rotatePiece } from './dungeon-catalog.js';
import { placementBounds, socketWorldPosition } from './dungeon-generator.js';
import { createMapPreview } from './map-editor-preview.js';
import {
  addEditorCollider,
  addEditorPiece,
  createEditorMap,
  duplicateEditorPiece,
  generateEditorMap,
  moveEditorPiece,
  parseEditorMap,
  regenerateEditorColliders,
  removeEditorCollider,
  removeEditorPiece,
  rotateEditorPiece,
  serializeEditorMap,
  updateEditorCollider,
  validateEditorMap,
} from './map-editor-core.js';

const STORAGE_KEY = 'ecos-de-eldra:map-editor:v1';
const CELL_PIXELS = 20;
const GROUP_LABELS = { room: 'Salas', corridor: 'Corredores', stairs: 'Escadas', gate: 'Portas e grades' };
const GROUP_COLORS = { room: '#d88b5e', corridor: '#54c9dc', stairs: '#9584f2', gate: '#ef7181' };
const LABELS = {
  corridorCorner: 'Curva estreita', corridorEnd: 'Fim de corredor', corridorIntersection: 'Cruzamento estreito',
  corridorJunction: 'Junção em T', corridorTransition: 'Transição estreito/largo', corridorWideCorner: 'Curva larga',
  corridorWideEnd: 'Fim de corredor largo', corridorWideIntersection: 'Cruzamento largo', corridorWideJunction: 'Junção larga em T',
  corridorWide: 'Corredor largo', corridor: 'Corredor reto', gateDoorWindow: 'Porta com janela', gateDoor: 'Porta',
  gateMetalBars: 'Grade de metal', gate: 'Arco aberto', roomCorner: 'Sala de canto', roomLargeVariation: 'Sala grande variação',
  roomLarge: 'Sala grande', roomSmallVariation: 'Sala pequena variação', roomSmall: 'Sala pequena',
  roomWideVariation: 'Sala larga variação', roomWide: 'Sala larga', stairsWide: 'Escada larga', stairs: 'Escada estreita',
};

const canvas = document.querySelector('#map-canvas');
const context = canvas.getContext('2d');
const palette = document.querySelector('#piece-palette');
const toastElement = document.querySelector('#toast');
const preview = createMapPreview(document.querySelector('#map-3d'), (message) => {
  document.querySelector('#preview-status').textContent = message;
}, {
  onSelect(id, options = {}) {
    if (!id) {
      if (!options.additive) clearSelection();
    } else if (options.additive) {
      toggleSelection(id);
    } else {
      selectOnly(id);
    }
    activeTool = null;
    preview.setSelected([...selectedIds]);
    renderInspector();
    updateStatus();
  },
  onMove(id, x, z, level) {
    const moved = applyResult(moveEditorPiece(editorMap, id, x, z, level), 'Peça movida em 3D');
    if (!moved) draw();
  },
});
const selectionPanel = document.querySelector('#selection-panel');
let editorMap = createEditorMap(document.querySelector('#map-name').value);
let activeLevel = 0;
let activeView = '2d';
let activeTool = null;
let collisionEdit = false;
let selectedId = null;
let selectedIds = new Set();
let selectedColliderId = null;
let hoverCell = null;
let dragState = null;
let marqueeState = null;
let collisionDraft = null;
let collisionDragState = null;
let history = [];
let future = [];
let toastTimer = 0;
let palettePointerDrag = null;
let lastPaletteDragEnded = 0;

function selectOnly(id) {
  selectedIds = id ? new Set([id]) : new Set();
  selectedId = id || null;
}

function clearSelection() {
  selectOnly(null);
}

function clearColliderSelection() {
  selectedColliderId = null;
}

function toggleSelection(id) {
  if (selectedIds.has(id)) selectedIds.delete(id);
  else selectedIds.add(id);
  selectedId = selectedIds.has(id) ? id : [...selectedIds].at(-1) ?? null;
}

function removeSelectedPieces() {
  if (!selectedIds.size) return;
  const count = selectedIds.size;
  let nextMap = editorMap;
  for (const id of selectedIds) nextMap = removeEditorPiece(nextMap, id);
  clearSelection();
  commit(nextMap, null);
  toast(`${count} ${count === 1 ? 'peça removida' : 'peças removidas'}`);
}

function cloneMap(map) {
  return parseEditorMap(serializeEditorMap(map));
}

function toast(message, error = false) {
  window.clearTimeout(toastTimer);
  toastElement.textContent = message;
  toastElement.className = `toast show${error ? ' error' : ''}`;
  toastTimer = window.setTimeout(() => { toastElement.className = 'toast'; }, 2400);
}

function commit(nextMap, nextSelection = selectedId, nextColliderSelection = selectedColliderId) {
  history.push(cloneMap(editorMap));
  if (history.length > 80) history.shift();
  future = [];
  editorMap = nextMap;
  document.querySelector('#max-elements').value = String(editorMap.maxElements ?? 24);
  document.querySelector('#max-levels').value = String(editorMap.maxLevels ?? 2);
  selectedId = nextSelection;
  selectedIds = nextSelection ? new Set([nextSelection]) : new Set();
  selectedColliderId = nextColliderSelection;
  document.querySelector('#save-state').textContent = 'Alterações não salvas';
  refresh();
}

function applyResult(result, successMessage) {
  if (!result.ok) {
    toast(result.error.message, true);
    return false;
  }
  if (result.collider) commit(result.map, null, result.collider.id);
  else commit(result.map, result.placement?.id ?? selectedId, null);
  if (successMessage) toast(result.snapped ? `${successMessage} • entrada encaixada` : successMessage);
  return true;
}

function canvasPoint(event) {
  const rect = canvas.getBoundingClientRect();
  return {
    x: (event.clientX - rect.left) * canvas.width / rect.width,
    z: (event.clientY - rect.top) * canvas.height / rect.height,
  };
}

function pointToCell(point, half = false) {
  const step = half ? 0.5 : 1;
  return {
    x: Math.round((point.x / CELL_PIXELS) / step) * step,
    z: Math.round((point.z / CELL_PIXELS) / step) * step,
  };
}

function colliderVisible(collider, level = activeLevel) {
  const minY = level * editorMap.levelHeight;
  const maxY = minY + editorMap.levelHeight;
  return collider.minY < maxY && collider.maxY > minY;
}

function colliderCanvasBounds(collider) {
  const scale = CELL_PIXELS / editorMap.cellSize;
  return {
    left: collider.minX * scale,
    right: collider.maxX * scale,
    top: collider.minZ * scale,
    bottom: collider.maxZ * scale,
  };
}

function colliderHitTest(point) {
  const candidates = editorMap.colliders.filter((collider) => colliderVisible(collider));
  for (let index = candidates.length - 1; index >= 0; index -= 1) {
    const collider = candidates[index];
    const bounds = colliderCanvasBounds(collider);
    const padding = 6;
    if (point.x >= bounds.left - padding && point.x <= bounds.right + padding
      && point.z >= bounds.top - padding && point.z <= bounds.bottom + padding) return collider;
  }
  return null;
}

function snappedWorldDelta(pixelDelta) {
  const world = pixelDelta / CELL_PIXELS * editorMap.cellSize;
  return Math.round(world / 1) * 1;
}

function colliderInputFromDraft(draft) {
  const start = pointToCell(draft.start, true);
  const end = pointToCell(draft.current, true);
  let minX = Math.min(start.x, end.x);
  let maxX = Math.max(start.x, end.x);
  let minZ = Math.min(start.z, end.z);
  let maxZ = Math.max(start.z, end.z);
  if (maxX - minX < 0.25) { minX -= 0.125; maxX += 0.125; }
  if (maxZ - minZ < 0.25) { minZ -= 0.125; maxZ += 0.125; }
  const minY = activeLevel * editorMap.levelHeight;
  return {
    minX: minX * editorMap.cellSize,
    maxX: maxX * editorMap.cellSize,
    minY,
    maxY: minY + editorMap.levelHeight,
    minZ: minZ * editorMap.cellSize,
    maxZ: maxZ * editorMap.cellSize,
  };
}

function levelsFor(placement) {
  return placement.pieceId === 'stairs' || placement.pieceId === 'stairsWide'
    ? [placement.level, placement.level + 1]
    : [placement.level];
}

function placementVisible(placement, level) {
  return levelsFor(placement).includes(level);
}

function hitTest(point) {
  const candidates = editorMap.placements.filter((placement) => placementVisible(placement, activeLevel));
  for (let index = candidates.length - 1; index >= 0; index -= 1) {
    const placement = candidates[index];
    if (placement.edge) {
      const px = placement.x * CELL_PIXELS;
      const pz = placement.z * CELL_PIXELS;
      const distance = placement.rotation % 2
        ? Math.max(Math.abs(point.x - px), Math.max(0, Math.abs(point.z - pz) - CELL_PIXELS * 0.55))
        : Math.max(Math.abs(point.z - pz), Math.max(0, Math.abs(point.x - px) - CELL_PIXELS * 0.55));
      if (distance <= 8) return placement;
      continue;
    }
    const bounds = placementBounds(placement);
    if (point.x >= bounds.minX * CELL_PIXELS && point.x <= bounds.maxX * CELL_PIXELS
      && point.z >= bounds.minZ * CELL_PIXELS && point.z <= bounds.maxZ * CELL_PIXELS) return placement;
  }
  return null;
}

function drawGrid() {
  context.clearRect(0, 0, canvas.width, canvas.height);
  for (let index = 0; index <= 50; index += 1) {
    const position = index * CELL_PIXELS + 0.5;
    context.strokeStyle = index % 5 === 0 ? '#343b53' : '#20263a';
    context.lineWidth = index % 5 === 0 ? 1.4 : 0.7;
    context.beginPath();
    context.moveTo(position, 0);
    context.lineTo(position, canvas.height);
    context.stroke();
    context.beginPath();
    context.moveTo(0, position);
    context.lineTo(canvas.width, position);
    context.stroke();
  }
  context.fillStyle = '#68718b';
  context.font = '10px Inter';
  for (let index = 0; index <= 50; index += 5) {
    context.fillText(String(index), index * CELL_PIXELS + 3, 12);
    if (index > 0) context.fillText(String(index), 3, index * CELL_PIXELS - 3);
  }
}

function drawGate(placement, alpha, selected) {
  const x = placement.x * CELL_PIXELS;
  const z = placement.z * CELL_PIXELS;
  const half = CELL_PIXELS * 0.58;
  context.globalAlpha = alpha;
  context.strokeStyle = selected ? '#ffffff' : GROUP_COLORS.gate;
  context.lineWidth = selected ? 8 : 6;
  context.beginPath();
  if (placement.rotation % 2) {
    context.moveTo(x, z - half);
    context.lineTo(x, z + half);
  } else {
    context.moveTo(x - half, z);
    context.lineTo(x + half, z);
  }
  context.stroke();
  context.fillStyle = '#0f1220';
  context.beginPath();
  context.arc(x, z, 3, 0, Math.PI * 2);
  context.fill();
  context.globalAlpha = 1;
}

function drawFloorPiece(placement, alpha, selected) {
  const definition = pieceById(placement.pieceId);
  const bounds = placementBounds(placement);
  const x = bounds.minX * CELL_PIXELS;
  const z = bounds.minZ * CELL_PIXELS;
  const width = (bounds.maxX - bounds.minX) * CELL_PIXELS;
  const height = (bounds.maxZ - bounds.minZ) * CELL_PIXELS;
  const color = GROUP_COLORS[definition.group];
  context.globalAlpha = alpha;
  context.fillStyle = color;
  context.strokeStyle = selected ? '#ffffff' : color;
  context.lineWidth = selected ? 4 : 2;
  context.fillRect(x + 2, z + 2, width - 4, height - 4);
  context.globalAlpha = alpha * 0.28;
  context.fillStyle = '#0a0c13';
  context.fillRect(x + 7, z + 7, Math.max(0, width - 14), Math.max(0, height - 14));
  context.globalAlpha = alpha;
  context.strokeRect(x + 2, z + 2, width - 4, height - 4);
  context.translate(x + width / 2, z + height / 2);
  context.rotate(placement.rotation * Math.PI / 2);
  context.fillStyle = '#f2f5ff';
  context.font = '700 10px Rajdhani';
  context.textAlign = 'center';
  context.textBaseline = 'middle';
  context.fillText(definition.group === 'stairs' ? '▲' : LABELS[placement.pieceId].slice(0, 12), 0, 0, Math.max(10, width - 10));
  context.setTransform(1, 0, 0, 1, 0, 0);
  context.globalAlpha = 1;
}

function socketKey(socket) {
  return `${socket.placementId}:${socket.socketIndex}`;
}

function drawSockets(validation) {
  const open = new Set(validation.openSockets.map(socketKey));
  for (const placement of editorMap.placements.filter((entry) => !entry.edge && placementVisible(entry, activeLevel))) {
    const rotated = rotatePiece(pieceById(placement.pieceId), placement.rotation);
    rotated.sockets.forEach((socket, socketIndex) => {
      if (socket.state === 'blocked') return;
      const position = socketWorldPosition(placement, socket);
      if (position.level !== activeLevel) return;
      context.fillStyle = open.has(`${placement.id}:${socketIndex}`) ? '#ff7185' : '#63e6d1';
      context.strokeStyle = '#0b0d18';
      context.lineWidth = 2;
      context.beginPath();
      if (socket.width === 'wide') {
        context.rect(position.x * CELL_PIXELS - 5.5, position.z * CELL_PIXELS - 5.5, 11, 11);
      } else {
        context.arc(position.x * CELL_PIXELS, position.z * CELL_PIXELS, 4.5, 0, Math.PI * 2);
      }
      context.fill();
      context.stroke();
    });
  }
}

function drawGhost() {
  if (!hoverCell || !activeTool) return;
  const definition = pieceById(activeTool);
  const placement = { pieceId: activeTool, x: hoverCell.x, z: hoverCell.z, level: activeLevel, rotation: 0, edge: definition.edge };
  context.save();
  if (definition.edge) drawGate(placement, 0.45, false);
  else {
    const bounds = placementBounds(placement);
    context.globalAlpha = 0.75;
    context.strokeStyle = GROUP_COLORS[definition.group];
    context.lineWidth = 3;
    context.strokeRect(bounds.minX * CELL_PIXELS + 2, bounds.minZ * CELL_PIXELS + 2, (bounds.maxX - bounds.minX) * CELL_PIXELS - 4, (bounds.maxZ - bounds.minZ) * CELL_PIXELS - 4);
  }
  context.restore();
}

function drawMarquee() {
  if (!marqueeState) return;
  const left = Math.min(marqueeState.start.x, marqueeState.current.x);
  const top = Math.min(marqueeState.start.z, marqueeState.current.z);
  const width = Math.abs(marqueeState.current.x - marqueeState.start.x);
  const height = Math.abs(marqueeState.current.z - marqueeState.start.z);
  context.fillStyle = 'rgba(99,230,209,.12)';
  context.strokeStyle = '#63e6d1';
  context.lineWidth = 2;
  context.setLineDash([8, 5]);
  context.fillRect(left, top, width, height);
  context.strokeRect(left, top, width, height);
  context.setLineDash([]);
}

function drawColliders() {
  if (!document.querySelector('#show-collisions').checked) return;
  for (const collider of editorMap.colliders.filter((entry) => colliderVisible(entry))) {
    const bounds = colliderCanvasBounds(collider);
    const selected = collider.id === selectedColliderId;
    const width = Math.max(2.5, bounds.right - bounds.left);
    const height = Math.max(2.5, bounds.bottom - bounds.top);
    const left = (bounds.left + bounds.right - width) / 2;
    const top = (bounds.top + bounds.bottom - height) / 2;
    context.fillStyle = selected ? 'rgba(255,210,92,.48)' : 'rgba(255,79,107,.28)';
    context.strokeStyle = selected ? '#ffe08a' : '#ff7185';
    context.lineWidth = selected ? 3 : 1.5;
    context.fillRect(left, top, width, height);
    context.strokeRect(left, top, width, height);
  }
  if (collisionDraft) {
    const left = Math.min(collisionDraft.start.x, collisionDraft.current.x);
    const top = Math.min(collisionDraft.start.z, collisionDraft.current.z);
    const width = Math.abs(collisionDraft.current.x - collisionDraft.start.x);
    const height = Math.abs(collisionDraft.current.z - collisionDraft.start.z);
    context.fillStyle = 'rgba(255,210,92,.22)';
    context.strokeStyle = '#ffe08a';
    context.lineWidth = 2;
    context.setLineDash([7, 4]);
    context.fillRect(left, top, width, height);
    context.strokeRect(left, top, width, height);
    context.setLineDash([]);
  }
}

function draw() {
  drawGrid();
  const validation = validateEditorMap(editorMap);
  for (const placement of editorMap.placements.filter((entry) => placementVisible(entry, activeLevel) && selectedIds.has(entry.id))) {
    if (placement.edge) drawGate(placement, 0.9, true);
    else {
      const bounds = placementBounds(placement);
      context.strokeStyle = '#ffffff';
      context.lineWidth = 4;
      context.strokeRect(bounds.minX * CELL_PIXELS + 2, bounds.minZ * CELL_PIXELS + 2, (bounds.maxX - bounds.minX) * CELL_PIXELS - 4, (bounds.maxZ - bounds.minZ) * CELL_PIXELS - 4);
    }
  }
  if (!collisionEdit) drawSockets(validation);
  drawColliders();
  drawGhost();
  drawMarquee();
  preview.setSelected([...selectedIds]);
  preview.setMap(
    editorMap,
    activeLevel,
    document.querySelector('#show-other-level').checked,
    document.querySelector('#show-collisions').checked,
  );
}

function renderPalette(filter = '') {
  palette.replaceChildren();
  for (const group of ['room', 'corridor', 'stairs', 'gate']) {
    const pieces = DUNGEON_PIECES.filter((piece) => piece.group === group && `${LABELS[piece.id]} ${piece.id}`.toLowerCase().includes(filter.toLowerCase()));
    if (!pieces.length) continue;
    const heading = document.createElement('h3');
    heading.className = 'group-title';
    heading.textContent = GROUP_LABELS[group];
    palette.append(heading);
    for (const piece of pieces) {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = `piece-card${activeTool === piece.id ? ' active' : ''}`;
      card.dataset.pieceId = piece.id;
      card.dataset.group = piece.group;
      card.draggable = false;
      card.innerHTML = `<span class="piece-preview"><img alt="Modelo 3D de ${LABELS[piece.id]}"><span class="piece-shape">${piece.footprint.width}×${piece.footprint.height}</span></span><span class="piece-copy"><strong>${LABELS[piece.id]}</strong><small>${piece.id}</small></span><span class="piece-count">${piece.sockets.length}</span>`;
      preview.thumbnail(piece).then((url) => {
        const image = card.querySelector('img');
        if (image && card.isConnected) image.src = url;
      }).catch(() => {
        const image = card.querySelector('img');
        if (image) image.alt = `Miniatura indisponível: ${LABELS[piece.id]}`;
      });
      card.addEventListener('pointerdown', (event) => {
        if (collisionEdit) setCollisionEdit(false);
        palettePointerDrag = { pieceId: piece.id, x: event.clientX, y: event.clientY, moved: false };
      });
      card.addEventListener('click', () => {
        if (performance.now() - lastPaletteDragEnded < 250) return;
        if (collisionEdit) setCollisionEdit(false);
        activeTool = activeTool === piece.id ? null : piece.id;
        renderPalette(document.querySelector('#piece-search').value);
        updateStatus();
        draw();
      });
      palette.append(card);
    }
  }
}

function renderInspector() {
  if (collisionEdit) {
    const collider = editorMap.colliders.find((entry) => entry.id === selectedColliderId);
    if (!collider) {
      selectionPanel.innerHTML = `
        <h3>Editor de colisões</h3>
        <div class="selection-empty">Arraste no mapa para criar uma caixa de colisão. Clique numa caixa vermelha para selecioná-la e arraste para mover.</div>
        <div class="button-grid">
          <button class="btn" data-action="regenerate-colliders">Recriar automáticas</button>
        </div>
        <div class="collision-note">“Recriar automáticas” substitui as caixas atuais pelas paredes calculadas a partir das peças.</div>`;
      return;
    }
    const fields = [
      ['minX', 'X mínimo'], ['maxX', 'X máximo'],
      ['minY', 'Y mínimo'], ['maxY', 'Y máximo'],
      ['minZ', 'Z mínimo'], ['maxZ', 'Z máximo'],
    ];
    selectionPanel.innerHTML = `
      <h3>Colisor selecionado</h3>
      <h4 class="selected-title">${collider.source === 'generated' ? 'Parede automática' : 'Caixa manual'}</h4>
      <div class="selected-id">${collider.id}</div>
      <div class="collision-fields">${fields.map(([field, label]) => `
        <label>${label}<input type="number" step="0.05" data-collider-field="${field}" value="${collider[field]}"></label>`).join('')}</div>
      <div class="button-grid">
        <button class="btn danger" data-action="delete-collider">Excluir colisor</button>
        <button class="btn" data-action="regenerate-colliders">Recriar automáticas</button>
      </div>
      <div class="collision-note">As medidas estão em unidades do mundo. Uma célula da grade equivale a ${editorMap.cellSize} unidades.</div>`;
    return;
  }
  if (selectedIds.size > 1) {
    selectionPanel.innerHTML = `
      <h3>Seleção múltipla</h3>
      <h4 class="selected-title">${selectedIds.size} blocos selecionados</h4>
      <div class="selection-empty">Use Shift/Ctrl + clique para ajustar a seleção ou Delete para remover todos.</div>
      <div class="button-grid"><button class="btn danger" data-action="delete-selected">Excluir selecionados</button></div>`;
    return;
  }
  const selected = editorMap.placements.find((placement) => placement.id === selectedId);
  if (!selected) {
    selectionPanel.innerHTML = '<h3>Peça selecionada</h3><div class="selection-empty">Selecione uma peça na grade para girar, duplicar, mover entre andares ou excluir.</div>';
    return;
  }
  const piece = pieceById(selected.pieceId);
  selectionPanel.innerHTML = `
    <h3>Peça selecionada</h3>
    <h4 class="selected-title">${LABELS[selected.pieceId]}</h4>
    <div class="selected-id">${selected.id}</div>
    <div class="properties">
      <div class="property"><span>X</span><strong>${selected.x}</strong></div>
      <div class="property"><span>Z</span><strong>${selected.z}</strong></div>
      <div class="property"><span>Andar</span><strong>${selected.level}</strong></div>
      <div class="property"><span>Rotação</span><strong>${selected.rotation * 90}°</strong></div>
      <div class="property"><span>Tamanho</span><strong>${piece.footprint.width}×${piece.footprint.height}</strong></div>
      <div class="property"><span>Entradas</span><strong>${piece.sockets.length}</strong></div>
    </div>
    <div class="button-grid">
      <button class="btn" data-action="rotate-left">↶ Girar</button>
      <button class="btn" data-action="rotate-right">Girar ↷</button>
      <button class="btn" data-action="change-level">Trocar andar</button>
      <button class="btn" data-action="duplicate">Duplicar</button>
      <button class="btn danger" data-action="delete">Excluir</button>
    </div>`;
}

function renderValidation() {
  const validation = validateEditorMap(editorMap);
  const unique = new Set(editorMap.placements.map((placement) => placement.pieceId)).size;
  document.querySelector('#piece-total').textContent = validation.pieceCount;
  document.querySelector('#unique-total').textContent = `${unique}/24`;
  document.querySelector('#connection-total').textContent = validation.connections.length;
  document.querySelector('#open-total').textContent = validation.openSockets.length;
  document.querySelector('#collider-total').textContent = editorMap.colliders.length;
  document.querySelector('#collision-kind').textContent = editorMap.collisionMode === 'generated' ? 'Auto' : 'Manual';
  const box = document.querySelector('#validation-box');
  if (validation.valid) {
    box.className = 'validation good';
    box.innerHTML = `<strong>Mapa válido</strong><br>${validation.pieceCount} de até ${editorMap.maxElements ?? 24} peças, usando ${editorMap.maxLevels ?? 2} andar${(editorMap.maxLevels ?? 2) > 1 ? 'es' : ''}, sem sobreposição.`;
  } else {
    box.className = 'validation';
    const problem = validation.errors[0]?.message ?? `Ainda faltam ${validation.missingPieceIds.length} modelos estruturais.`;
    box.innerHTML = `<strong>Mapa incompleto</strong><br>${problem}<div class="missing-list">${validation.missingPieceIds.map((id) => LABELS[id]).join(' • ')}</div>`;
  }
}

function updateStatus() {
  if (activeLevel >= (editorMap.maxLevels ?? 2)) activeLevel = 0;
  document.querySelector('#active-level').textContent = activeLevel;
  document.querySelector('#active-tool').textContent = collisionEdit
    ? 'Editar colisões'
    : activeView === '3d' ? 'Navegar em 3D' : (activeTool ? LABELS[activeTool] : 'Selecionar');
  document.querySelector('#undo').disabled = history.length === 0;
  document.querySelector('#redo').disabled = future.length === 0;
  document.querySelectorAll('.level-btn').forEach((button) => {
    const level = Number(button.dataset.level);
    button.classList.toggle('active', level === activeLevel);
    button.disabled = level >= (editorMap.maxLevels ?? 2);
  });
}

function setViewMode(mode) {
  activeView = mode === '3d' ? '3d' : '2d';
  if (activeView === '3d') collisionEdit = false;
  const wrap = document.querySelector('#canvas-wrap');
  wrap.classList.toggle('view-3d', activeView === '3d');
  wrap.classList.toggle('collision-edit', collisionEdit);
  document.querySelector('#board-scroll').classList.toggle('view-3d', activeView === '3d');
  document.querySelector('.board-toolbar').classList.toggle('view-3d', activeView === '3d');
  document.querySelector('#view-2d').classList.toggle('active', activeView === '2d');
  document.querySelector('#view-3d').classList.toggle('active', activeView === '3d');
  document.querySelector('#edit-collisions').classList.toggle('active', collisionEdit);
  preview.setSelected([...selectedIds]);
  preview.setViewMode(activeView);
  updateStatus();
  renderInspector();
  draw();
}

function setCollisionEdit(enabled) {
  collisionEdit = Boolean(enabled);
  if (collisionEdit) {
    activeTool = null;
    clearSelection();
    setViewMode('2d');
  } else {
    clearColliderSelection();
  }
  document.querySelector('#canvas-wrap').classList.toggle('collision-edit', collisionEdit);
  document.querySelector('#edit-collisions').classList.toggle('active', collisionEdit);
  refresh();
}

function refresh() {
  document.querySelector('#map-name').value = editorMap.name;
  updateStatus();
  renderInspector();
  renderValidation();
  renderPalette(document.querySelector('#piece-search').value);
  draw();
}

function addAt(pieceId, point) {
  const cell = pointToCell(point, true);
  if (applyResult(addEditorPiece(editorMap, { pieceId, x: cell.x, z: cell.z, level: activeLevel }), `${LABELS[pieceId]} adicionada`)) {
    activeTool = null;
  }
}

canvas.addEventListener('dragover', (event) => {
  if (collisionEdit) return;
  event.preventDefault();
  event.dataTransfer.dropEffect = 'copy';
  const point = canvasPoint(event);
  const pieceId = event.dataTransfer.types.includes('text/plain') ? activeTool : null;
  hoverCell = pointToCell(point, pieceId ? pieceById(pieceId).edge : false);
  draw();
});
canvas.addEventListener('dragleave', () => { hoverCell = null; draw(); });
canvas.addEventListener('drop', (event) => {
  if (collisionEdit) return;
  event.preventDefault();
  const pieceId = event.dataTransfer.getData('text/plain');
  if (pieceId) addAt(pieceId, canvasPoint(event));
  palettePointerDrag = null;
  lastPaletteDragEnded = performance.now();
  hoverCell = null;
  draw();
});

window.addEventListener('pointermove', (event) => {
  if (!palettePointerDrag) return;
  if (Math.hypot(event.clientX - palettePointerDrag.x, event.clientY - palettePointerDrag.y) > 7) {
    palettePointerDrag.moved = true;
  }
});
window.addEventListener('pointerup', (event) => {
  if (!palettePointerDrag?.moved) {
    palettePointerDrag = null;
    return;
  }
  const rect = canvas.getBoundingClientRect();
  if (event.clientX >= rect.left && event.clientX <= rect.right && event.clientY >= rect.top && event.clientY <= rect.bottom) {
    addAt(palettePointerDrag.pieceId, event);
  }
  palettePointerDrag = null;
  lastPaletteDragEnded = performance.now();
  refresh();
});

canvas.addEventListener('pointerdown', (event) => {
  const point = canvasPoint(event);
  if (collisionEdit) {
    const hit = colliderHitTest(point);
    if (hit) {
      selectedColliderId = hit.id;
      collisionDragState = { collider: { ...hit }, start: point, current: point, pointerId: event.pointerId };
    } else {
      selectedColliderId = null;
      collisionDraft = { start: point, current: point, pointerId: event.pointerId };
    }
    canvas.setPointerCapture(event.pointerId);
    refresh();
    return;
  }
  if (activeTool) {
    addAt(activeTool, point);
    refresh();
    return;
  }
  const hit = hitTest(point);
  const additive = event.shiftKey || event.ctrlKey || event.metaKey;
  if (hit) {
    if (additive) toggleSelection(hit.id);
    else selectOnly(hit.id);
    if (selectedIds.has(hit.id)) dragState = { id: hit.id, start: point, originX: hit.x, originZ: hit.z, moved: false };
    canvas.setPointerCapture(event.pointerId);
  } else {
    if (!additive) clearSelection();
    marqueeState = { start: point, current: point, additive, pointerId: event.pointerId };
    canvas.setPointerCapture(event.pointerId);
  }
  refresh();
});
canvas.addEventListener('pointermove', (event) => {
  const point = canvasPoint(event);
  hoverCell = pointToCell(point, true);
  document.querySelector('#cursor-cell').textContent = `${hoverCell.x}, ${hoverCell.z}`;
  if (collisionEdit) {
    if (collisionDraft && event.pointerId === collisionDraft.pointerId) collisionDraft.current = point;
    if (collisionDragState && event.pointerId === collisionDragState.pointerId) collisionDragState.current = point;
    draw();
    return;
  }
  if (dragState && Math.hypot(point.x - dragState.start.x, point.z - dragState.start.z) > 5) dragState.moved = true;
  if (marqueeState && event.pointerId === marqueeState.pointerId) marqueeState.current = point;
  draw();
});
canvas.addEventListener('pointerup', (event) => {
  if (collisionEdit) {
    if (collisionDraft && event.pointerId === collisionDraft.pointerId) {
      collisionDraft.current = canvasPoint(event);
      const draft = collisionDraft;
      collisionDraft = null;
      if (Math.hypot(draft.current.x - draft.start.x, draft.current.z - draft.start.z) < 5) {
        refresh();
        return;
      }
      applyResult(addEditorCollider(editorMap, colliderInputFromDraft(draft)), 'Colisor criado');
      return;
    }
    if (collisionDragState && event.pointerId === collisionDragState.pointerId) {
      const state = collisionDragState;
      collisionDragState = null;
      const deltaX = snappedWorldDelta(state.current.x - state.start.x);
      const deltaZ = snappedWorldDelta(state.current.z - state.start.z);
      if (deltaX || deltaZ) {
        applyResult(updateEditorCollider(editorMap, state.collider.id, {
          minX: state.collider.minX + deltaX,
          maxX: state.collider.maxX + deltaX,
          minZ: state.collider.minZ + deltaZ,
          maxZ: state.collider.maxZ + deltaZ,
        }), 'Colisor movido');
      } else {
        refresh();
      }
      return;
    }
  }
  if (marqueeState && event.pointerId === marqueeState.pointerId) {
    const left = Math.min(marqueeState.start.x, marqueeState.current.x);
    const right = Math.max(marqueeState.start.x, marqueeState.current.x);
    const top = Math.min(marqueeState.start.z, marqueeState.current.z);
    const bottom = Math.max(marqueeState.start.z, marqueeState.current.z);
    if (right - left > 5 || bottom - top > 5) {
      for (const placement of editorMap.placements.filter((entry) => placementVisible(entry, activeLevel))) {
        const bounds = placement.edge
          ? { minX: placement.x - 0.5, maxX: placement.x + 0.5, minZ: placement.z - 0.5, maxZ: placement.z + 0.5 }
          : placementBounds(placement);
        const intersects = bounds.maxX * CELL_PIXELS >= left && bounds.minX * CELL_PIXELS <= right
          && bounds.maxZ * CELL_PIXELS >= top && bounds.minZ * CELL_PIXELS <= bottom;
        if (intersects) selectedIds.add(placement.id);
      }
      selectedId = [...selectedIds].at(-1) ?? null;
    }
    marqueeState = null;
    refresh();
    return;
  }
  if (!dragState) return;
  const point = canvasPoint(event);
  if (dragState.moved) {
    const selected = editorMap.placements.find((placement) => placement.id === dragState.id);
    const deltaX = (point.x - dragState.start.x) / CELL_PIXELS;
    const deltaZ = (point.z - dragState.start.z) / CELL_PIXELS;
    const step = 0.5;
    const x = Math.round((dragState.originX + deltaX) / step) * step;
    const z = Math.round((dragState.originZ + deltaZ) / step) * step;
    applyResult(moveEditorPiece(editorMap, dragState.id, x, z), 'Peça movida');
  }
  dragState = null;
  refresh();
});
canvas.addEventListener('pointercancel', () => {
  dragState = null;
  marqueeState = null;
  collisionDraft = null;
  collisionDragState = null;
});

selectionPanel.addEventListener('click', (event) => {
  const action = event.target.closest('[data-action]')?.dataset.action;
  if (action === 'regenerate-colliders') {
    commit(regenerateEditorColliders(editorMap), null, null);
    toast('Colisões automáticas recriadas');
    return;
  }
  if (action === 'delete-collider' && selectedColliderId) {
    commit(removeEditorCollider(editorMap, selectedColliderId), null, null);
    toast('Colisor excluído');
    return;
  }
  if (action === 'delete-selected') {
    removeSelectedPieces();
    return;
  }
  if (!action || !selectedId) return;
  if (action === 'rotate-left') applyResult(rotateEditorPiece(editorMap, selectedId, -1), 'Peça girada');
  if (action === 'rotate-right') applyResult(rotateEditorPiece(editorMap, selectedId, 1), 'Peça girada');
  if (action === 'duplicate') applyResult(duplicateEditorPiece(editorMap, selectedId), 'Peça duplicada');
  if (action === 'change-level') {
    const selected = editorMap.placements.find((placement) => placement.id === selectedId);
    if (applyResult(moveEditorPiece(editorMap, selectedId, selected.x, selected.z, 1 - selected.level), 'Peça movida para outro andar')) activeLevel = 1 - selected.level;
  }
  if (action === 'delete') {
    removeSelectedPieces();
  }
});

selectionPanel.addEventListener('change', (event) => {
  const field = event.target.dataset.colliderField;
  if (!field || !selectedColliderId) return;
  const value = Number(event.target.value);
  if (!Number.isFinite(value)) {
    toast('Informe um número válido.', true);
    renderInspector();
    return;
  }
  applyResult(updateEditorCollider(editorMap, selectedColliderId, { [field]: value }), 'Medida do colisor atualizada');
});

document.querySelectorAll('.level-btn').forEach((button) => button.addEventListener('click', () => {
  activeLevel = Number(button.dataset.level);
  clearSelection();
  clearColliderSelection();
  refresh();
}));
document.querySelector('#show-other-level').addEventListener('change', draw);
document.querySelector('#view-2d').addEventListener('click', () => setViewMode('2d'));
document.querySelector('#view-3d').addEventListener('click', () => {
  activeTool = null;
  setViewMode('3d');
});
document.querySelector('#edit-collisions').addEventListener('click', () => setCollisionEdit(!collisionEdit));
document.querySelector('#show-collisions').addEventListener('change', draw);
document.querySelector('.camera-3d-controls').addEventListener('click', (event) => {
  const action = event.target.closest('[data-camera]')?.dataset.camera;
  if (action === 'left') preview.panCamera(-1, 0);
  if (action === 'right') preview.panCamera(1, 0);
  if (action === 'forward') preview.panCamera(0, 1);
  if (action === 'back') preview.panCamera(0, -1);
  if (action === 'zoom-in') preview.zoomCamera(0.78);
  if (action === 'zoom-out') preview.zoomCamera(1.28);
  if (action === 'reset') preview.resetCamera();
});
document.querySelector('#piece-search').addEventListener('input', (event) => renderPalette(event.target.value));
document.querySelector('#zoom').addEventListener('input', (event) => {
  const size = `${event.target.value}px`;
  document.querySelector('#canvas-wrap').style.width = size;
  document.querySelector('#canvas-wrap').style.height = size;
});
document.querySelector('#map-name').addEventListener('input', (event) => {
  editorMap.name = event.target.value || 'Mapa sem nome';
  document.querySelector('#save-state').textContent = 'Alterações não salvas';
});

document.querySelector('#undo').addEventListener('click', () => {
  if (!history.length) return;
  future.push(cloneMap(editorMap));
  editorMap = history.pop();
  clearSelection();
  clearColliderSelection();
  refresh();
});
document.querySelector('#redo').addEventListener('click', () => {
  if (!future.length) return;
  history.push(cloneMap(editorMap));
  editorMap = future.pop();
  clearSelection();
  clearColliderSelection();
  refresh();
});
document.querySelector('#new-map').addEventListener('click', () => {
  commit(createEditorMap('Meu mapa modular'), null, null);
  toast('Novo mapa vazio criado');
});
document.querySelector('#generate-map').addEventListener('click', () => {
  const maxElementsInput = document.querySelector('#max-elements');
  const maxElements = Math.max(1, Math.min(200, Math.round(Number(maxElementsInput.value) || 24)));
  const maxLevels = Number(document.querySelector('#max-levels').value) === 1 ? 1 : 2;
  maxElementsInput.value = String(maxElements);
  const generated = generateEditorMap(Date.now(), { maxElements, maxLevels });
  activeLevel = 0;
  commit(generated, null, null);
  document.querySelector('#save-state').textContent = 'Mapa aleatório gerado';
  toast(`${generated.placements.length} peças geradas em até ${maxLevels} andar${maxLevels > 1 ? 'es' : ''}`);
});
document.querySelector('#save-local').addEventListener('click', () => {
  localStorage.setItem(STORAGE_KEY, serializeEditorMap(editorMap));
  document.querySelector('#save-state').textContent = 'Salvo neste navegador';
  toast('Mapa salvo neste navegador');
});
document.querySelector('#download-json').addEventListener('click', () => {
  const blob = new Blob([serializeEditorMap(editorMap)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${editorMap.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'mapa-eldra'}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
  toast('Arquivo JSON preparado');
});
document.querySelector('#open-json').addEventListener('click', () => document.querySelector('#file-input').click());
document.querySelector('#file-input').addEventListener('change', async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  try {
    commit(parseEditorMap(await file.text()), null, null);
    toast('Mapa JSON carregado');
  } catch (error) {
    toast(error.message, true);
  }
  event.target.value = '';
});

async function loadMapLibrary() {
  const select = document.querySelector('#existing-map');
  const button = document.querySelector('#load-existing-map');
  try {
    const response = await fetch('/api/maps', { cache: 'no-store' });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const files = await response.json();
    select.replaceChildren(...files.map((filename) => {
      const option = document.createElement('option');
      option.value = filename;
      option.textContent = filename.replace(/\.json$/i, '');
      return option;
    }));
    button.disabled = files.length === 0;
    if (!files.length) {
      const option = document.createElement('option');
      option.textContent = 'Nenhum mapa encontrado';
      select.append(option);
    }
  } catch (error) {
    select.innerHTML = '<option value="">Use “Abrir JSON”</option>';
    button.disabled = true;
    console.warn('Biblioteca de mapas indisponível.', error);
  }
}

document.querySelector('#load-existing-map').addEventListener('click', async () => {
  const filename = document.querySelector('#existing-map').value;
  if (!filename) return;
  const button = document.querySelector('#load-existing-map');
  button.disabled = true;
  try {
    const response = await fetch(`/map-files/${encodeURIComponent(filename)}`, { cache: 'no-store' });
    if (!response.ok) throw new Error(`Não foi possível carregar ${filename}: HTTP ${response.status}`);
    commit(parseEditorMap(await response.text()), null, null);
    setCollisionEdit(true);
    document.querySelector('#save-state').textContent = `Carregado: ${filename}`;
    toast(`${filename} carregado • colisões prontas para editar`);
  } catch (error) {
    toast(error.message, true);
  } finally {
    button.disabled = false;
  }
});

window.addEventListener('keydown', (event) => {
  const typing = ['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName);
  if (typing) return;
  if (collisionEdit && (event.key === 'Delete' || event.key === 'Backspace') && selectedColliderId) {
    event.preventDefault();
    commit(removeEditorCollider(editorMap, selectedColliderId), null, null);
    toast('Colisor excluído');
    return;
  }
  if (activeView === '3d' && ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) {
    event.preventDefault();
    const selected = editorMap.placements.find((placement) => placement.id === selectedId);
    if (selected) {
      const step = event.shiftKey ? 1 : 0.5;
      const x = selected.x + (event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0);
      const z = selected.z + (event.key === 'ArrowUp' ? -step : event.key === 'ArrowDown' ? step : 0);
      applyResult(moveEditorPiece(editorMap, selected.id, x, z, selected.level), 'Peça movida em 3D');
    } else {
      if (event.key === 'ArrowLeft') preview.panCamera(-1, 0);
      if (event.key === 'ArrowRight') preview.panCamera(1, 0);
      if (event.key === 'ArrowUp') preview.panCamera(0, 1);
      if (event.key === 'ArrowDown') preview.panCamera(0, -1);
    }
    return;
  }
  if (event.key === 'Escape') { activeTool = null; clearSelection(); clearColliderSelection(); refresh(); }
  if ((event.key === 'Delete' || event.key === 'Backspace') && selectedIds.size) removeSelectedPieces();
  if (event.key.toLowerCase() === 'r' && selectedId) applyResult(rotateEditorPiece(editorMap, selectedId, event.shiftKey ? -1 : 1), 'Peça girada');
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') document.querySelector('#undo').click();
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'y') document.querySelector('#redo').click();
});

try {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved) {
    editorMap = parseEditorMap(saved);
    document.querySelector('#save-state').textContent = 'Mapa salvo restaurado';
  }
} catch (error) {
  console.warn('Não foi possível restaurar o mapa salvo.', error);
}

document.querySelector('#max-elements').value = String(editorMap.maxElements ?? 24);
document.querySelector('#max-levels').value = String(editorMap.maxLevels ?? 2);
loadMapLibrary();
refresh();
