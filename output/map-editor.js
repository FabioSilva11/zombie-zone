import { DUNGEON_PIECES, pieceById, rotatePiece } from '../app/src/main/assets/game/js/dungeon-catalog.js';
import { placementBounds, socketWorldPosition } from '../app/src/main/assets/game/js/dungeon-generator.js';
import { PHASE_LAYOUT } from '../app/src/main/assets/game/js/phase-layout.js';
import {
  addEditorPiece,
  createEditorMap,
  duplicateEditorPiece,
  generateEditorMap,
  moveEditorPiece,
  parseEditorMap,
  removeEditorPiece,
  rotateEditorPiece,
  serializeEditorMap,
  validateEditorMap,
} from './map-editor-core.js';

const STORAGE_KEY = 'ecos-de-eldra:map-editor:v1';
const CELL_PIXELS = 20;
const GROUP_LABELS = { room: 'Salas', corridor: 'Corredores', stairs: 'Escadas', gate: 'Portas e grades' };
const GROUP_COLORS = { room: '#d88b5e', corridor: '#54c9dc', stairs: '#9584f2', gate: '#ef7181' };
const LABELS = {
  corridorCorner: 'Corredor em L', corridorEnd: 'Fim de corredor', corridorIntersection: 'Cruzamento estreito',
  corridorJunction: 'Junção em T', corridorTransition: 'Transição estreito/largo', corridorWideCorner: 'Corredor largo em L',
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
const selectionPanel = document.querySelector('#selection-panel');
let editorMap = createEditorMap(document.querySelector('#map-name').value);
let activeLevel = 0;
let activeTool = null;
let selectedId = null;
let hoverCell = null;
let dragState = null;
let history = [];
let future = [];
let toastTimer = 0;
let palettePointerDrag = null;
let lastPaletteDragEnded = 0;

function cloneMap(map) {
  return parseEditorMap(serializeEditorMap(map));
}

function toast(message, error = false) {
  window.clearTimeout(toastTimer);
  toastElement.textContent = message;
  toastElement.className = `toast show${error ? ' error' : ''}`;
  toastTimer = window.setTimeout(() => { toastElement.className = 'toast'; }, 2400);
}

function commit(nextMap, nextSelection = selectedId) {
  history.push(cloneMap(editorMap));
  if (history.length > 80) history.shift();
  future = [];
  editorMap = nextMap;
  selectedId = nextSelection;
  document.querySelector('#save-state').textContent = 'Alterações não salvas';
  refresh();
}

function applyResult(result, successMessage) {
  if (!result.ok) {
    toast(result.error.message, true);
    return false;
  }
  commit(result.map, result.placement?.id ?? selectedId);
  if (successMessage) toast(successMessage);
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
  context.fillStyle = '#101420';
  context.fillRect(0, 0, canvas.width, canvas.height);
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
      context.arc(position.x * CELL_PIXELS, position.z * CELL_PIXELS, socket.width === 'wide' ? 6 : 4.5, 0, Math.PI * 2);
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
  else drawFloorPiece(placement, 0.35, false);
  context.restore();
}

function draw() {
  drawGrid();
  const showOther = document.querySelector('#show-other-level').checked;
  if (showOther) {
    for (const placement of editorMap.placements.filter((entry) => placementVisible(entry, 1 - activeLevel))) {
      if (placement.edge) drawGate(placement, 0.16, false);
      else drawFloorPiece(placement, 0.16, false);
    }
  }
  for (const placement of editorMap.placements.filter((entry) => placementVisible(entry, activeLevel))) {
    if (placement.edge) drawGate(placement, 0.88, placement.id === selectedId);
    else drawFloorPiece(placement, 0.82, placement.id === selectedId);
  }
  drawSockets(validateEditorMap(editorMap));
  drawGhost();
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
      card.innerHTML = `<span class="piece-shape">${piece.footprint.width}×${piece.footprint.height}</span><span class="piece-copy"><strong>${LABELS[piece.id]}</strong><small>${piece.id}</small></span><span class="piece-count">${piece.sockets.length}</span>`;
      card.addEventListener('pointerdown', (event) => {
        palettePointerDrag = { pieceId: piece.id, x: event.clientX, y: event.clientY, moved: false };
      });
      card.addEventListener('click', () => {
        if (performance.now() - lastPaletteDragEnded < 250) return;
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
  const box = document.querySelector('#validation-box');
  if (validation.valid) {
    box.className = 'validation good';
    box.innerHTML = '<strong>Mapa estrutural completo</strong><br>As 24 peças estão presentes e dentro do grid.';
  } else {
    box.className = 'validation';
    const problem = validation.errors[0]?.message ?? `Ainda faltam ${validation.missingPieceIds.length} modelos estruturais.`;
    box.innerHTML = `<strong>Mapa incompleto</strong><br>${problem}<div class="missing-list">${validation.missingPieceIds.map((id) => LABELS[id]).join(' • ')}</div>`;
  }
}

function updateStatus() {
  document.querySelector('#active-level').textContent = activeLevel;
  document.querySelector('#active-tool').textContent = activeTool ? LABELS[activeTool] : 'Selecionar';
  document.querySelector('#undo').disabled = history.length === 0;
  document.querySelector('#redo').disabled = future.length === 0;
  document.querySelectorAll('.level-btn').forEach((button) => button.classList.toggle('active', Number(button.dataset.level) === activeLevel));
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
  const definition = pieceById(pieceId);
  const cell = pointToCell(point, definition.edge);
  if (applyResult(addEditorPiece(editorMap, { pieceId, x: cell.x, z: cell.z, level: activeLevel }), `${LABELS[pieceId]} adicionada`)) {
    activeTool = null;
  }
}

canvas.addEventListener('dragover', (event) => {
  event.preventDefault();
  event.dataTransfer.dropEffect = 'copy';
  const point = canvasPoint(event);
  const pieceId = event.dataTransfer.types.includes('text/plain') ? activeTool : null;
  hoverCell = pointToCell(point, pieceId ? pieceById(pieceId).edge : false);
  draw();
});
canvas.addEventListener('dragleave', () => { hoverCell = null; draw(); });
canvas.addEventListener('drop', (event) => {
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
  if (activeTool) {
    addAt(activeTool, point);
    refresh();
    return;
  }
  const hit = hitTest(point);
  selectedId = hit?.id ?? null;
  if (hit) {
    dragState = { id: hit.id, start: point, originX: hit.x, originZ: hit.z, moved: false };
    canvas.setPointerCapture(event.pointerId);
  }
  refresh();
});
canvas.addEventListener('pointermove', (event) => {
  const point = canvasPoint(event);
  const selected = editorMap.placements.find((placement) => placement.id === selectedId);
  hoverCell = pointToCell(point, selected?.edge ?? (activeTool ? pieceById(activeTool).edge : false));
  document.querySelector('#cursor-cell').textContent = `${hoverCell.x}, ${hoverCell.z}`;
  if (dragState && Math.hypot(point.x - dragState.start.x, point.z - dragState.start.z) > 5) dragState.moved = true;
  draw();
});
canvas.addEventListener('pointerup', (event) => {
  if (!dragState) return;
  const point = canvasPoint(event);
  if (dragState.moved) {
    const selected = editorMap.placements.find((placement) => placement.id === dragState.id);
    const deltaX = (point.x - dragState.start.x) / CELL_PIXELS;
    const deltaZ = (point.z - dragState.start.z) / CELL_PIXELS;
    const step = selected.edge ? 0.5 : 1;
    const x = Math.round((dragState.originX + deltaX) / step) * step;
    const z = Math.round((dragState.originZ + deltaZ) / step) * step;
    applyResult(moveEditorPiece(editorMap, dragState.id, x, z), 'Peça movida');
  }
  dragState = null;
  refresh();
});
canvas.addEventListener('pointercancel', () => { dragState = null; });

selectionPanel.addEventListener('click', (event) => {
  const action = event.target.closest('[data-action]')?.dataset.action;
  if (!action || !selectedId) return;
  if (action === 'rotate-left') applyResult(rotateEditorPiece(editorMap, selectedId, -1), 'Peça girada');
  if (action === 'rotate-right') applyResult(rotateEditorPiece(editorMap, selectedId, 1), 'Peça girada');
  if (action === 'duplicate') applyResult(duplicateEditorPiece(editorMap, selectedId), 'Peça duplicada');
  if (action === 'change-level') {
    const selected = editorMap.placements.find((placement) => placement.id === selectedId);
    if (applyResult(moveEditorPiece(editorMap, selectedId, selected.x, selected.z, 1 - selected.level), 'Peça movida para outro andar')) activeLevel = 1 - selected.level;
  }
  if (action === 'delete') {
    commit(removeEditorPiece(editorMap, selectedId), null);
    toast('Peça removida');
  }
});

document.querySelectorAll('.level-btn').forEach((button) => button.addEventListener('click', () => {
  activeLevel = Number(button.dataset.level);
  selectedId = null;
  refresh();
}));
document.querySelector('#show-other-level').addEventListener('change', draw);
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
  selectedId = null;
  refresh();
});
document.querySelector('#redo').addEventListener('click', () => {
  if (!future.length) return;
  history.push(cloneMap(editorMap));
  editorMap = future.pop();
  selectedId = null;
  refresh();
});
document.querySelector('#new-map').addEventListener('click', () => {
  commit(createEditorMap('Meu mapa modular'), null);
  toast('Novo mapa vazio criado');
});
document.querySelector('#generate-map').addEventListener('click', () => {
  const generated = generateEditorMap(Date.now());
  commit(generated, null);
  document.querySelector('#save-state').textContent = 'Mapa aleatório gerado';
  toast(`${generated.name} pronto para editar`);
});
document.querySelector('#load-current').addEventListener('click', () => {
  const map = createEditorMap(`Mapa procedural ${PHASE_LAYOUT.seed}`);
  map.nextId = PHASE_LAYOUT.placements.length + 1;
  map.placements = PHASE_LAYOUT.placements.map((placement, index) => ({ ...placement, id: `current-${index + 1}-${placement.pieceId}` }));
  commit(map, null);
  toast('Mapa atual carregado para edição');
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
    commit(parseEditorMap(await file.text()), null);
    toast('Mapa JSON carregado');
  } catch (error) {
    toast(error.message, true);
  }
  event.target.value = '';
});

window.addEventListener('keydown', (event) => {
  const typing = ['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName);
  if (typing) return;
  if (event.key === 'Escape') { activeTool = null; selectedId = null; refresh(); }
  if ((event.key === 'Delete' || event.key === 'Backspace') && selectedId) {
    commit(removeEditorPiece(editorMap, selectedId), null);
    toast('Peça removida');
  }
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

refresh();
