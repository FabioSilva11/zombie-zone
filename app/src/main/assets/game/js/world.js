import * as THREE from 'three';
import { DUNGEON_ASSETS } from './catalog.js';

function prepareModel(root) {
  root.traverse((child) => {
    if (!child.isMesh) return;
    child.castShadow = true;
    child.receiveShadow = true;
    child.frustumCulled = true;
  });
  return root;
}

async function loadPlaced(loader, definition, onProgress) {
  try {
    const gltf = await loader.loadAsync(definition.url);
    const root = prepareModel(gltf.scene);
    root.position.set(...definition.position);
    root.rotation.y = definition.rotation ?? 0;
    root.scale.setScalar(definition.scale ?? 1);
    onProgress?.(definition.label, true);
    return { root, animations: gltf.animations };
  } catch (error) {
    console.warn(`Falha ao carregar ${definition.label}`, error);
    onProgress?.(definition.label, false);
    return null;
  }
}

function addFallbackArchitecture(scene) {
  const floorMaterial = new THREE.MeshStandardMaterial({ color: 0x4d3554, roughness: 0.9 });
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(25, 37), floorMaterial);
  floor.rotation.x = -Math.PI / 2;
  floor.receiveShadow = true;
  scene.add(floor);

  const wallMaterial = new THREE.MeshStandardMaterial({ color: 0x625a91, roughness: 0.82 });
  const wallDefinitions = [
    [0, 1.3, -18, 25, 2.6, 0.7],
    [0, 1.3, 18, 25, 2.6, 0.7],
    [-12, 1.3, 0, 0.7, 2.6, 37],
    [12, 1.3, 0, 0.7, 2.6, 37],
  ];
  for (const [x, y, z, width, height, depth] of wallDefinitions) {
    const wall = new THREE.Mesh(new THREE.BoxGeometry(width, height, depth), wallMaterial);
    wall.position.set(x, y, z);
    wall.castShadow = true;
    wall.receiveShadow = true;
    scene.add(wall);
  }

  const pillarMaterial = new THREE.MeshStandardMaterial({ color: 0x776ea6, roughness: 0.72 });
  for (const z of [-13, -5, 4, 13]) {
    for (const x of [-9.8, 9.8]) {
      const pillar = new THREE.Mesh(new THREE.CylinderGeometry(0.65, 0.78, 3.5, 8), pillarMaterial);
      pillar.position.set(x, 1.75, z);
      pillar.castShadow = true;
      pillar.receiveShadow = true;
      scene.add(pillar);
    }
  }
}

function addAtmosphere(scene) {
  const geometry = new THREE.BufferGeometry();
  const points = [];
  for (let index = 0; index < 120; index += 1) {
    points.push(
      (Math.random() - 0.5) * 24,
      0.7 + Math.random() * 6,
      (Math.random() - 0.5) * 35,
    );
  }
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(points, 3));
  const material = new THREE.PointsMaterial({
    color: 0x9fe7df,
    size: 0.045,
    transparent: true,
    opacity: 0.58,
    depthWrite: false,
  });
  const motes = new THREE.Points(geometry, material);
  scene.add(motes);
  return motes;
}

export async function createWorld(loader, onAssetProgress) {
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0x111426);
  scene.fog = new THREE.FogExp2(0x16172c, 0.026);

  scene.add(new THREE.HemisphereLight(0xa6b9ff, 0x301f32, 1.25));
  const sun = new THREE.DirectionalLight(0xffd6a0, 2.35);
  sun.position.set(7, 13, 9);
  sun.castShadow = true;
  sun.shadow.mapSize.set(1024, 1024);
  sun.shadow.camera.left = -18;
  sun.shadow.camera.right = 18;
  sun.shadow.camera.top = 20;
  sun.shadow.camera.bottom = -20;
  scene.add(sun);

  const cyanLight = new THREE.PointLight(0x54e1d0, 25, 15, 2);
  cyanLight.position.set(-7, 2.4, -6);
  scene.add(cyanLight);
  const amberLight = new THREE.PointLight(0xff9d55, 22, 14, 2);
  amberLight.position.set(7, 2.2, 7);
  scene.add(amberLight);

  addFallbackArchitecture(scene);
  const motes = addAtmosphere(scene);

  const pieces = [
    { label: 'Salão ancestral', url: DUNGEON_ASSETS.roomLarge, position: [0, 0.02, 7], rotation: 0, scale: 1 },
    { label: 'Galeria central', url: DUNGEON_ASSETS.roomWide, position: [0, 0.02, -4], rotation: Math.PI, scale: 1 },
    { label: 'Câmara do portal', url: DUNGEON_ASSETS.roomSmall, position: [0, 0.02, -13], rotation: 0, scale: 1 },
  ];
  const loadedPieces = await Promise.all(pieces.map((piece) => loadPlaced(loader, piece, onAssetProgress)));
  for (const piece of loadedPieces) if (piece) scene.add(piece.root);

  const gateData = await loadPlaced(loader, {
    label: 'Portão dos ecos',
    url: DUNGEON_ASSETS.gateDoor,
    position: [0, 0.05, -9.4],
    rotation: 0,
    scale: 1.15,
  }, onAssetProgress);

  let gateMixer = null;
  let gateAction = null;
  if (gateData) {
    scene.add(gateData.root);
    gateMixer = new THREE.AnimationMixer(gateData.root);
    const openClip = gateData.animations.find((clip) => clip.name === 'open') ?? gateData.animations[0];
    if (openClip) {
      gateAction = gateMixer.clipAction(openClip);
      gateAction.setLoop(THREE.LoopOnce, 1);
      gateAction.clampWhenFinished = true;
    }
  }

  const portal = new THREE.Group();
  portal.position.set(0, 1.65, -14.5);
  const portalRing = new THREE.Mesh(
    new THREE.TorusGeometry(1.35, 0.12, 12, 48),
    new THREE.MeshStandardMaterial({ color: 0x71f2de, emissive: 0x276d74, emissiveIntensity: 2.4 }),
  );
  const portalCore = new THREE.Mesh(
    new THREE.CircleGeometry(1.18, 48),
    new THREE.MeshBasicMaterial({ color: 0x766fe4, transparent: true, opacity: 0.28, side: THREE.DoubleSide }),
  );
  portal.add(portalRing, portalCore);
  portal.visible = false;
  scene.add(portal);

  let gateOpened = false;
  return {
    scene,
    gateOpened: () => gateOpened,
    openGate() {
      if (gateOpened) return;
      gateOpened = true;
      portal.visible = true;
      gateAction?.reset().play();
    },
    isAtExit(position) {
      return gateOpened && Math.hypot(position.x - portal.position.x, position.z - portal.position.z) < 2.1;
    },
    update(delta, elapsed) {
      gateMixer?.update(delta);
      motes.rotation.y = elapsed * 0.015;
      portal.rotation.y = Math.sin(elapsed * 0.7) * 0.18;
      portalRing.rotation.z = elapsed * 0.45;
    },
  };
}

