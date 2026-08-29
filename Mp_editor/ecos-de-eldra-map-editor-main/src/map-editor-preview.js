import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { pieceById } from './dungeon-catalog.js';
import { placementBounds } from './dungeon-generator.js';

const MODEL_ROOT = './assets/models/dungeon/';

function floorCenter(placement, layout) {
  const bounds = placementBounds(placement);
  return {
    x: ((bounds.minX + bounds.maxX) / 2) * layout.cellSize,
    z: ((bounds.minZ + bounds.maxZ) / 2) * layout.cellSize,
    y: placement.level * layout.levelHeight,
  };
}

function prepare(root, opacity = 1) {
  root.traverse((child) => {
    if (!child.isMesh) return;
    child.castShadow = true;
    child.receiveShadow = true;
    if (opacity < 1) {
      const materials = Array.isArray(child.material) ? child.material : [child.material];
      child.material = materials.map((material) => {
        const clone = material.clone();
        clone.transparent = true;
        clone.opacity = opacity;
        clone.depthWrite = false;
        return clone;
      });
    }
  });
  return root;
}

export function createMapPreview(canvas, onStatus = () => {}, interaction = {}) {
  const renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true, preserveDrawingBuffer: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
  renderer.setSize(1000, 1000, false);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFShadowMap;
  renderer.outputColorSpace = THREE.SRGBColorSpace;

  const scene = new THREE.Scene();
  scene.add(new THREE.HemisphereLight(0xbed0ff, 0x141629, 2.1));
  const sun = new THREE.DirectionalLight(0xffd5aa, 2.8);
  sun.position.set(80, 110, 50);
  sun.castShadow = true;
  scene.add(sun);
  const topCamera = new THREE.OrthographicCamera(-100, 100, 100, -100, 0.1, 500);
  topCamera.up.set(0, 0, -1);
  topCamera.position.set(100, 160, 100);
  topCamera.lookAt(100, 0, 100);
  topCamera.updateProjectionMatrix();
  const orbitCamera = new THREE.PerspectiveCamera(48, 1, 0.1, 800);
  orbitCamera.position.set(180, 145, 180);
  const controls = new OrbitControls(orbitCamera, canvas);
  controls.target.set(100, 0, 100);
  controls.enableDamping = true;
  controls.dampingFactor = 0.075;
  controls.screenSpacePanning = true;
  controls.enablePan = true;
  controls.panSpeed = 1.25;
  controls.rotateSpeed = 0.72;
  controls.zoomSpeed = 1.35;
  controls.zoomToCursor = true;
  controls.minDistance = 8;
  controls.maxDistance = 430;
  controls.maxPolarAngle = Math.PI * 0.495;
  controls.mouseButtons.LEFT = null;
  controls.mouseButtons.RIGHT = THREE.MOUSE.ROTATE;
  controls.mouseButtons.MIDDLE = THREE.MOUSE.PAN;
  controls.enabled = false;
  controls.update();
  let activeCamera = topCamera;
  let viewMode = '2d';
  const mapRoot = new THREE.Group();
  scene.add(mapRoot);
  const colliderRoot = new THREE.Group();
  scene.add(colliderRoot);
  const selectionBoxes = new THREE.Group();
  scene.add(selectionBoxes);
  const previewFloor = new THREE.Mesh(
    new THREE.PlaneGeometry(200, 200),
    new THREE.MeshStandardMaterial({ color: 0x151b2a, roughness: 0.92, metalness: 0 }),
  );
  previewFloor.rotation.x = -Math.PI / 2;
  previewFloor.position.set(100, -0.25, 100);
  previewFloor.receiveShadow = true;
  scene.add(previewFloor);
  renderer.setAnimationLoop(() => {
    if (controls.enabled) controls.update();
    renderer.render(scene, activeCamera);
  });
  const loader = new GLTFLoader();
  const cache = new Map();
  const thumbnailCache = new Map();
  let renderToken = 0;
  let currentLayout = null;
  let thumbnailQueue = Promise.resolve();
  let selectedPlacementIds = new Set();
  let currentShowColliders = true;
  let dragState = null;
  const raycaster = new THREE.Raycaster();
  const pointer = new THREE.Vector2();
  const dragPlane = new THREE.Plane();

  const thumbnailCanvas = document.createElement('canvas');
  thumbnailCanvas.width = 180;
  thumbnailCanvas.height = 130;
  const thumbnailRenderer = new THREE.WebGLRenderer({ canvas: thumbnailCanvas, alpha: true, antialias: true, preserveDrawingBuffer: true });
  thumbnailRenderer.setPixelRatio(1);
  thumbnailRenderer.setSize(180, 130, false);
  thumbnailRenderer.outputColorSpace = THREE.SRGBColorSpace;
  const thumbnailScene = new THREE.Scene();
  thumbnailScene.add(new THREE.HemisphereLight(0xd9e5ff, 0x242035, 3));
  const thumbnailLight = new THREE.DirectionalLight(0xffc997, 3.2);
  thumbnailLight.position.set(4, 7, 3);
  thumbnailScene.add(thumbnailLight);
  const thumbnailRoot = new THREE.Group();
  thumbnailScene.add(thumbnailRoot);
  const thumbnailCamera = new THREE.PerspectiveCamera(34, 180 / 130, 0.01, 100);
  thumbnailCamera.position.set(3.6, 2.8, 3.6);
  thumbnailCamera.lookAt(0, 0, 0);

  function modelUrl(definition) {
    return new URL(`${MODEL_ROOT}${definition.file}`, document.baseURI).href;
  }

  async function load(definition, silent = false) {
    if (!cache.has(definition.id)) {
      if (!silent) onStatus(`Carregando ${definition.id}…`);
      cache.set(definition.id, loader.loadAsync(modelUrl(definition)).then((gltf) => prepare(gltf.scene)));
    }
    return cache.get(definition.id);
  }

  function thumbnail(definition) {
    if (thumbnailCache.has(definition.id)) return thumbnailCache.get(definition.id);
    const task = thumbnailQueue.then(async () => {
      const source = await load(definition, true);
      thumbnailRoot.clear();
      const root = source.clone(true);
      root.rotation.y = -Math.PI / 4;
      root.updateMatrixWorld(true);
      let bounds = new THREE.Box3().setFromObject(root);
      const size = bounds.getSize(new THREE.Vector3());
      const scale = 2.45 / Math.max(size.x, size.y, size.z, 0.01);
      root.scale.setScalar(scale);
      root.updateMatrixWorld(true);
      bounds = new THREE.Box3().setFromObject(root);
      const center = bounds.getCenter(new THREE.Vector3());
      root.position.sub(center);
      thumbnailRoot.add(root);
      thumbnailRenderer.setClearColor(0x000000, 0);
      thumbnailRenderer.render(thumbnailScene, thumbnailCamera);
      return thumbnailCanvas.toDataURL('image/png');
    });
    thumbnailQueue = task.catch(() => undefined);
    thumbnailCache.set(definition.id, task);
    return task;
  }

  function frameOrbitView() {
    const bounds = new THREE.Box3().setFromObject(mapRoot);
    if (bounds.isEmpty()) {
      controls.target.set(100, 0, 100);
      orbitCamera.position.set(180, 145, 180);
    } else {
      const center = bounds.getCenter(new THREE.Vector3());
      const size = bounds.getSize(new THREE.Vector3());
      const radius = Math.max(size.x, size.z, size.y * 2, 18);
      controls.target.set(center.x, Math.max(0, center.y * 0.45), center.z);
      orbitCamera.position.set(center.x + radius * 0.72, center.y + radius * 0.62 + 9, center.z + radius * 0.72);
      orbitCamera.far = Math.max(800, radius * 8);
      orbitCamera.updateProjectionMatrix();
    }
    controls.update();
  }

  function zoomCamera(factor) {
    const offset = orbitCamera.position.clone().sub(controls.target);
    const nextDistance = THREE.MathUtils.clamp(offset.length() * factor, controls.minDistance, controls.maxDistance);
    orbitCamera.position.copy(controls.target).add(offset.setLength(nextDistance));
    controls.update();
  }

  function panCamera(horizontal, vertical) {
    const forward = controls.target.clone().sub(orbitCamera.position);
    forward.y = 0;
    if (forward.lengthSq() < 0.001) forward.set(0, 0, -1);
    forward.normalize();
    const right = new THREE.Vector3().crossVectors(forward, orbitCamera.up).normalize();
    const step = THREE.MathUtils.clamp(orbitCamera.position.distanceTo(controls.target) * 0.075, 2, 18);
    const delta = right.multiplyScalar(horizontal * step).add(forward.multiplyScalar(vertical * step));
    controls.target.add(delta);
    orbitCamera.position.add(delta);
    controls.update();
  }

  function placementRootFromObject(object) {
    let current = object;
    while (current && current !== mapRoot) {
      if (current.userData?.placementId) return current;
      current = current.parent;
    }
    return null;
  }

  function updateSelectionBox() {
    for (const helper of selectionBoxes.children) {
      helper.geometry?.dispose();
      helper.material?.dispose();
    }
    selectionBoxes.clear();
    selectionBoxes.visible = viewMode === '3d';
    if (viewMode !== '3d') return;
    for (const id of selectedPlacementIds) {
      const root = mapRoot.children.find((child) => child.userData.placementId === id);
      if (!root) continue;
      selectionBoxes.add(new THREE.Box3Helper(new THREE.Box3().setFromObject(root), 0x63e6d1));
    }
  }

  function setSelected(ids) {
    selectedPlacementIds = new Set(Array.isArray(ids) ? ids : ids ? [ids] : []);
    updateSelectionBox();
  }

  function pointerForEvent(event) {
    const rect = canvas.getBoundingClientRect();
    pointer.set(
      ((event.clientX - rect.left) / rect.width) * 2 - 1,
      -((event.clientY - rect.top) / rect.height) * 2 + 1,
    );
    raycaster.setFromCamera(pointer, orbitCamera);
  }

  function pointOnDragPlane(event, target = new THREE.Vector3()) {
    pointerForEvent(event);
    return raycaster.ray.intersectPlane(dragPlane, target);
  }

  canvas.addEventListener('pointerdown', (event) => {
    if (viewMode !== '3d' || event.button !== 0) return;
    pointerForEvent(event);
    const hit = raycaster.intersectObjects(mapRoot.children, true)[0];
    const root = hit ? placementRootFromObject(hit.object) : null;
    const additive = event.shiftKey || event.ctrlKey || event.metaKey;
    if (!root) {
      interaction.onSelect?.(null, { additive });
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
    const placement = root.userData.placement;
    interaction.onSelect?.(placement.id, { additive });
    if (additive) return;
    dragPlane.set(new THREE.Vector3(0, 1, 0), -(root.userData.displayLevelY ?? placement.level * currentLayout.levelHeight));
    const startPoint = pointOnDragPlane(event);
    if (!startPoint) return;
    controls.enabled = false;
    dragState = {
      pointerId: event.pointerId,
      root,
      placement,
      startPoint: startPoint.clone(),
      startPosition: root.position.clone(),
      x: placement.x,
      z: placement.z,
      moved: false,
    };
    canvas.setPointerCapture?.(event.pointerId);
  }, true);

  canvas.addEventListener('pointermove', (event) => {
    if (!dragState || event.pointerId !== dragState.pointerId) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    const point = pointOnDragPlane(event);
    if (!point) return;
    const delta = point.sub(dragState.startPoint);
    const cellSize = currentLayout.cellSize;
    dragState.x = Math.round((dragState.placement.x + delta.x / cellSize) * 2) / 2;
    dragState.z = Math.round((dragState.placement.z + delta.z / cellSize) * 2) / 2;
    dragState.moved = dragState.moved || dragState.x !== dragState.placement.x || dragState.z !== dragState.placement.z;
    dragState.root.position.copy(dragState.startPosition).add(new THREE.Vector3(
      (dragState.x - dragState.placement.x) * cellSize,
      0,
      (dragState.z - dragState.placement.z) * cellSize,
    ));
    updateSelectionBox();
  }, true);

  const finishDrag = (event) => {
    if (!dragState || event.pointerId !== dragState.pointerId) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    const finished = dragState;
    dragState = null;
    controls.enabled = viewMode === '3d';
    canvas.releasePointerCapture?.(event.pointerId);
    if (finished.moved) interaction.onMove?.(finished.placement.id, finished.x, finished.z, finished.placement.level);
  };
  canvas.addEventListener('pointerup', finishDrag, true);
  canvas.addEventListener('pointercancel', finishDrag, true);

  function setViewMode(mode) {
    viewMode = mode === '3d' ? '3d' : '2d';
    controls.enabled = viewMode === '3d';
    activeCamera = controls.enabled ? orbitCamera : topCamera;
    colliderRoot.visible = viewMode === '3d' && currentShowColliders;
    updateSelectionBox();
    resize();
    if (controls.enabled) frameOrbitView();
    renderer.render(scene, activeCamera);
  }

  function renderColliders(layout) {
    for (const child of colliderRoot.children) {
      child.geometry?.dispose();
      child.material?.dispose();
    }
    colliderRoot.clear();
    for (const collider of layout.colliders ?? []) {
      const sizeX = collider.maxX - collider.minX;
      const sizeY = collider.maxY - collider.minY;
      const sizeZ = collider.maxZ - collider.minZ;
      if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) continue;
      const mesh = new THREE.Mesh(
        new THREE.BoxGeometry(sizeX, sizeY, sizeZ),
        new THREE.MeshBasicMaterial({ color: 0xff4767, transparent: true, opacity: 0.24, depthWrite: false }),
      );
      mesh.position.set(
        (collider.minX + collider.maxX) / 2,
        (collider.minY + collider.maxY) / 2,
        (collider.minZ + collider.maxZ) / 2,
      );
      const edges = new THREE.LineSegments(
        new THREE.EdgesGeometry(mesh.geometry),
        new THREE.LineBasicMaterial({ color: 0xff8b9d, transparent: true, opacity: 0.78 }),
      );
      mesh.add(edges);
      colliderRoot.add(mesh);
    }
    colliderRoot.visible = viewMode === '3d' && currentShowColliders;
  }

  async function setMap(layout, level, showOther = true, showColliders = true) {
    currentLayout = layout;
    currentShowColliders = showColliders;
    renderColliders(layout);
    renderToken += 1;
    const token = renderToken;
    mapRoot.clear();
    const showEveryLevel = viewMode === '3d' && (layout.maxLevels ?? 2) > 1;
    const visible = layout.placements.filter((placement) => {
      if (showEveryLevel) return true;
      const levels = placement.pieceId === 'stairs' || placement.pieceId === 'stairsWide'
        ? [placement.level, placement.level + 1]
        : [placement.level];
      return levels.includes(level) || (showOther && levels.includes(1 - level));
    });
    const unique = new Set(visible.map((placement) => placement.pieceId));
    onStatus(`Renderizando ${unique.size} modelos Three.js reais…`);
    await Promise.all(visible.map(async (placement) => {
      try {
        const source = await load(pieceById(placement.pieceId));
        if (token !== renderToken) return;
        const isCurrent = showEveryLevel || placement.level === level || placement.pieceId === 'stairs' || placement.pieceId === 'stairsWide';
        const root = prepare(source.clone(true), isCurrent ? 1 : 0.22);
        root.userData.placementId = placement.id;
        root.userData.placement = { ...placement };
        const displayLevelY = placement.level * layout.levelHeight;
        root.userData.displayLevelY = displayLevelY;
        if (placement.edge) {
          if (placement.socketWidth === 'wide') root.scale.x = 2;
          root.rotation.y = -placement.rotation * Math.PI / 2;
          root.position.set(placement.x * layout.cellSize, displayLevelY, placement.z * layout.cellSize);
        } else {
          root.rotation.y = -placement.rotation * Math.PI / 2;
          root.position.set(0, 0, 0);
          root.updateMatrixWorld(true);
          const bounds = new THREE.Box3().setFromObject(root);
          const center = bounds.getCenter(new THREE.Vector3());
          const target = floorCenter(placement, layout);
          root.position.set(target.x - center.x, displayLevelY - bounds.min.y, target.z - center.z);
        }
        mapRoot.add(root);
      } catch (error) {
        console.warn(`Preview indisponível para ${placement.pieceId}`, error);
      }
    }));
    if (token === renderToken) {
      updateSelectionBox();
      if (viewMode === '3d') frameOrbitView();
      onStatus(`${visible.length} peças • modelos reais ativos`);
    }
  }

  function resize() {
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
    const width = viewMode === '3d' ? Math.max(1, Math.round(canvas.clientWidth)) : 1000;
    const height = viewMode === '3d' ? Math.max(1, Math.round(canvas.clientHeight)) : 1000;
    renderer.setSize(width, height, false);
    orbitCamera.aspect = width / height;
    orbitCamera.updateProjectionMatrix();
    if (currentLayout) renderer.render(scene, activeCamera);
  }

  const resizeObserver = new ResizeObserver(() => resize());
  resizeObserver.observe(canvas);

  return {
    setMap,
    setViewMode,
    setSelected,
    zoomCamera,
    panCamera,
    resetCamera: frameOrbitView,
    thumbnail,
    resize,
    destroy: () => {
      renderer.setAnimationLoop(null);
      controls.dispose();
      resizeObserver.disconnect();
      thumbnailRenderer.dispose();
      renderer.dispose();
    },
  };
}
