import { RIFLE_TRANSFORMS } from './rifle-placement.js';

export function createRifleModel(THREE) {
  const materials = [
    new THREE.MeshStandardMaterial({ color: 0x27313a, roughness: 0.5, metalness: 0.55 }),
    new THREE.MeshStandardMaterial({ color: 0x151b20, roughness: 0.72, metalness: 0.25 }),
    new THREE.MeshStandardMaterial({ color: 0x8fd62f, roughness: 0.62, metalness: 0.08 }),
  ];
  const root = new THREE.Group();
  root.name = 'rifle-root';
  const recoilGroup = new THREE.Group();
  recoilGroup.name = 'rifle-recoil';
  root.add(recoilGroup);

  const addBox = (size, position, material, rotation = []) => {
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(...size), materials[material]);
    mesh.position.set(...position);
    if (rotation.length) mesh.rotation.set(...rotation);
    recoilGroup.add(mesh);
    return mesh;
  };
  const addCylinder = (radiusTop, radiusBottom, length, position, material) => {
    const mesh = new THREE.Mesh(new THREE.CylinderGeometry(radiusTop, radiusBottom, length, 8), materials[material]);
    mesh.rotation.x = Math.PI / 2;
    mesh.position.set(...position);
    recoilGroup.add(mesh);
    return mesh;
  };

  addBox([0.34, 0.25, 0.92], [0, 0, -0.28], 0); // receiver
  addBox([0.28, 0.21, 0.82], [0, -0.01, -0.95], 1); // handguard
  addBox([0.3, 0.2, 0.58], [0, 0.01, 0.5], 1); // stock
  addBox([0.19, 0.42, 0.34], [0, -0.28, -0.1], 0, [0.16, 0, 0]); // magazine
  addBox([0.08, 0.13, 0.22], [0, 0.2, -0.12], 2); // sight
  addBox([0.18, 0.16, 0.26], [0, -0.17, -0.47], 1, [-0.48, 0, 0]); // grip
  addCylinder(0.075, 0.075, 0.96, [0, 0.02, -1.48], 1);
  addCylinder(0.11, 0.09, 0.18, [0, 0.02, -1.96], 0);

  const muzzle = new THREE.Object3D();
  muzzle.name = 'rifle-muzzle';
  muzzle.position.set(0, 0.04, -1.72);
  recoilGroup.add(muzzle);

  let disposed = false;
  return {
    root,
    recoilGroup,
    muzzle,
    dispose() {
      if (disposed) return;
      disposed = true;
      const geometries = new Set();
      root.traverse((node) => { if (node.isMesh) geometries.add(node.geometry); });
      geometries.forEach((geometry) => geometry.dispose());
      materials.forEach((material) => material.dispose());
      root.removeFromParent();
    },
  };
}

export function createRifleSockets({ THREE, playerRoot, camera }) {
  const tps = new THREE.Object3D();
  tps.name = 'rifle-socket-tps';
  playerRoot.add(tps);
  const fps = new THREE.Object3D();
  fps.name = 'rifle-socket-fps';
  camera.add(fps);
  return { tps, fps };
}

export function attachRifle(rifle, socket, mode) {
  const transform = RIFLE_TRANSFORMS[mode];
  if (!transform) throw new Error(`Unknown rifle mode: ${mode}`);
  socket.add(rifle.root);
  rifle.root.position.set(...transform.position);
  rifle.root.rotation.set(...transform.rotation);
  rifle.root.scale.setScalar(transform.scale);
}
