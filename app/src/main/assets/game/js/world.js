import * as THREE from 'three';
import { DUNGEON_PIECES, placementBounds } from './dungeon-catalog.js';
import { loadBundledMap } from './map-library.js';
import { buildFloorColliders, buildWallColliders, cameraColliderBoxes, circleHitsWall, hasLineOfSight } from './map-collision.js?v=zombie-ai-1';
import { canOccupy as phaseCanOccupy, floorHeightAt as phaseFloorHeightAt } from './phase-layout.js';
import { gateBlocksPosition, gateWorldTransform } from './world-navigation.js';
import { applyDungeonTexture, configureDungeonTexture, dungeonTextureForMap } from './map-appearance.js';
import { configureSkyboxTexture, skyboxForMap } from './skybox.js?v=skyboxes-1';

const ACTOR_CAPSULE_HEIGHT = 1.78;
const ACTOR_CAPSULE_RADIUS = 0.5;
const ACTOR_STEP_HEIGHT = 0.28;

function actorCollisionRadius(requestedRadius) {
  return Math.min(ACTOR_CAPSULE_RADIUS, Math.max(0.36, requestedRadius * 0.42));
}

function prepareModel(root) {
  root.traverse((child) => {
    if (!child.isMesh) return;
    child.castShadow = true;
    child.receiveShadow = true;
    child.frustumCulled = true;
  });
  return root;
}

async function loadPiece(loader, definition, onProgress, dungeonTexture) {
  const gltf = await loader.loadAsync(definition.url);
  onProgress?.(definition.id, true);
  return { root: prepareModel(applyDungeonTexture(gltf.scene, dungeonTexture)), animations: gltf.animations };
}

function floorWorldCenter(placement, layout) {
  const bounds = placementBounds(placement);
  return {
    x: ((bounds.minX + bounds.maxX) / 2) * layout.cellSize,
    z: ((bounds.minZ + bounds.maxZ) / 2) * layout.cellSize,
    y: placement.level * layout.levelHeight,
  };
}

function placeFloorPiece(asset, placement, layout, scene) {
  const root = asset.root;
  root.rotation.y = -placement.rotation * Math.PI / 2;
  root.position.set(0, 0, 0);
  root.updateMatrixWorld(true);
  const bounds = new THREE.Box3().setFromObject(root);
  const center = bounds.getCenter(new THREE.Vector3());
  const target = floorWorldCenter(placement, layout);
  root.position.set(target.x - center.x, target.y - bounds.min.y, target.z - center.z);
  root.updateMatrixWorld(true);
  scene.add(root);
  return root;
}

function placeGate(asset, placement, layout, scene) {
  const root = asset.root;
  const target = gateWorldTransform(placement, layout);
  if (placement.socketWidth === 'wide') root.scale.x = 2;
  root.rotation.y = target.rotationY;
  root.position.set(target.x, target.y, target.z);
  root.updateMatrixWorld(true);
  const bounds = new THREE.Box3().setFromObject(root);
  root.position.y += target.y - bounds.min.y;
  root.updateMatrixWorld(true);
  scene.add(root);
  return root;
}

function createGateAction(root, animations) {
  if (!animations?.length) return null;
  const mixer = new THREE.AnimationMixer(root);
  const clip = animations.find((candidate) => /open/i.test(candidate.name)) ?? animations[0];
  const action = mixer.clipAction(clip);
  action.setLoop(THREE.LoopOnce, 1);
  action.clampWhenFinished = true;
  return { mixer, action };
}

function addAtmosphere(scene, layout) {
  const geometry = new THREE.BufferGeometry();
  const points = [];
  for (let index = 0; index < 360; index += 1) {
    points.push(Math.random() * layout.size, 0.7 + Math.random() * 8, Math.random() * layout.size);
  }
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(points, 3));
  const material = new THREE.PointsMaterial({ color: 0x9fe7df, size: 0.18, transparent: true, opacity: 0.3, depthWrite: false });
  const motes = new THREE.Points(geometry, material);
  scene.add(motes);
  return motes;
}

function addPortal(scene, layout) {
  const portal = new THREE.Group();
  portal.position.set(layout.exit.x, layout.exit.y + 2.2, layout.exit.z);
  const portalRing = new THREE.Mesh(
    new THREE.TorusGeometry(1.65, 0.18, 12, 40),
    new THREE.MeshStandardMaterial({ color: 0x71f2de, emissive: 0x276d74, emissiveIntensity: 2.4 }),
  );
  const portalCore = new THREE.Mesh(
    new THREE.CircleGeometry(1.48, 32),
    new THREE.MeshBasicMaterial({ color: 0x766fe4, transparent: true, opacity: 0.3, side: THREE.DoubleSide }),
  );
  portal.add(portalRing, portalCore);
  portal.visible = false;
  scene.add(portal);
  return { portal, portalRing };
}

export async function createWorld(loader, onAssetProgress, mapSelection, maxAnisotropy = 4) {
  const layout = await loadBundledMap(mapSelection);
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x181715);
  scene.fog = new THREE.Fog(0x24211d, 70, 260);
  scene.add(new THREE.HemisphereLight(0xfffbf2, 0x6d6254, 2));

  const sun = new THREE.DirectionalLight(0xfff1da, 2.35);
  sun.position.set(layout.size * 0.5, 70, layout.size * 0.35);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.camera.left = -110;
  sun.shadow.camera.right = 110;
  sun.shadow.camera.top = 110;
  sun.shadow.camera.bottom = -110;
  sun.shadow.camera.far = 260;
  scene.add(sun);

  const textureVariant = dungeonTextureForMap(layout.sourceFile);
  const skyboxVariant = skyboxForMap(mapSelection);
  const textureLoader = new THREE.TextureLoader();
  let skyboxTexture = null;
  try {
    skyboxTexture = configureSkyboxTexture(await textureLoader.loadAsync(skyboxVariant.url), THREE);
    scene.background = skyboxTexture;
    scene.fog.color.setHex(skyboxVariant.fog);
  } catch (error) {
    console.warn(`Falha ao carregar o céu ${skyboxVariant.file}; usando fundo de segurança.`, error);
  }
  let dungeonTexture;
  try {
    dungeonTexture = configureDungeonTexture(
      await textureLoader.loadAsync(textureVariant.url),
      THREE,
      maxAnisotropy,
    );
  } catch (error) {
    console.error(`Falha ao carregar a textura ${textureVariant.file}`, error);
    throw new Error(`A textura ${textureVariant.file} não pôde ser carregada.`, { cause: error });
  }

  const motes = addAtmosphere(scene, layout);
  const staticCollisionRoot = new THREE.Group();
  staticCollisionRoot.name = 'static-map-collision';
  const dynamicGateRoot = new THREE.Group();
  dynamicGateRoot.name = 'dynamic-gates';
  scene.add(staticCollisionRoot, dynamicGateRoot);
  const placementsByPiece = new Map();
  for (const placement of layout.placements) {
    const entries = placementsByPiece.get(placement.pieceId) ?? [];
    entries.push(placement);
    placementsByPiece.set(placement.pieceId, entries);
  }
  const gateAnimations = [];

  for (const definition of DUNGEON_PIECES) {
    const placements = placementsByPiece.get(definition.id) ?? [];
    if (!placements.length) continue;
    try {
      const asset = await loadPiece(loader, definition, onAssetProgress, dungeonTexture);
      for (const placement of placements) {
        const instance = { ...asset, root: prepareModel(asset.root.clone(true)) };
        const root = placement.edge
          ? placeGate(instance, placement, layout, dynamicGateRoot)
          : placeFloorPiece(instance, placement, layout, staticCollisionRoot);
        if (definition.id === 'gateDoor' || definition.id === 'gateDoorWindow') {
          const animation = createGateAction(root, asset.animations);
          if (animation) gateAnimations.push(animation);
        }
      }
    } catch (error) {
      console.error(`Falha ao carregar a peça estrutural ${definition.id}`, error);
      onAssetProgress?.(definition.id, false);
      throw new Error(`A peça estrutural ${definition.id} não pôde ser carregada.`, { cause: error });
    }
  }

  const wallColliders = buildWallColliders(layout);
  const cameraCollisionMaterial = new THREE.MeshBasicMaterial({ transparent: true, opacity: 0, depthWrite: false, colorWrite: false });
  const cameraColliderMeshes = cameraColliderBoxes(wallColliders).map(({ center, size }, index) => {
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(size.x, size.y, size.z), cameraCollisionMaterial);
    mesh.name = `camera-wall-${index + 1}`;
    mesh.position.set(center.x, center.y, center.z);
    scene.add(mesh);
    return mesh;
  });
  const { portal, portalRing } = addPortal(scene, layout);
  let gateOpened = false;

  function staticGeometryAllows(position, requestedRadius) {
    const floorHeight = phaseFloorHeightAt(position, layout);
    if (floorHeight === null) return false;
    const radius = actorCollisionRadius(requestedRadius);
    return !circleHitsWall(
      position,
      radius,
      floorHeight + ACTOR_STEP_HEIGHT,
      floorHeight + ACTOR_CAPSULE_HEIGHT,
      wallColliders,
    );
  }

  return {
    scene,
    layout,
    textureVariant,
    skyboxVariant,
    physicsWalls: wallColliders,
    physicsSurfaces: buildFloorColliders(layout),
    cameraColliderMeshes,
    cameraWallColliders: wallColliders,
    hasLineOfSight(from, to) {
      return hasLineOfSight(
        { x: from.x, y: (from.y ?? 0) + 1.15, z: from.z },
        { x: to.x, y: (to.y ?? 0) + 1.15, z: to.z },
        wallColliders,
      );
    },
    canOccupy(position, radius = 1.2) {
      const collisionRadius = actorCollisionRadius(radius);
      return phaseCanOccupy(position, radius, layout)
        && staticGeometryAllows(position, radius)
        && !gateBlocksPosition(position, layout, gateOpened, collisionRadius);
    },
    floorHeightAt(position) { return phaseFloorHeightAt(position, layout); },
    gateOpened: () => gateOpened,
    openGate() {
      if (gateOpened) return;
      gateOpened = true;
      portal.visible = true;
      for (const { action } of gateAnimations) action.reset().play();
    },
    isAtExit(position) {
      return gateOpened
        && Math.abs((position.y ?? layout.exit.y) - layout.exit.y) < layout.levelHeight
        && Math.hypot(position.x - portal.position.x, position.z - portal.position.z) < 3.2;
    },
    update(delta, elapsed) {
      for (const { mixer } of gateAnimations) mixer.update(delta);
      motes.rotation.y = elapsed * 0.01;
      portal.rotation.y = Math.sin(elapsed * 0.7) * 0.12;
      portalRing.rotation.z = elapsed * 0.45;
    },
  };
}
