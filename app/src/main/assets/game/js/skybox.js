export const SKYBOXES = Object.freeze([
  Object.freeze({ id: 'day', file: 'skybox-day.png', url: './images/skyboxes/skybox-day.png', fog: 0x9db6c7 }),
  Object.freeze({ id: 'morning', file: 'skybox-morning.png', url: './images/skyboxes/skybox-morning.png', fog: 0xcaa17b }),
  Object.freeze({ id: 'alien', file: 'skybox-alien.png', url: './images/skyboxes/skybox-alien.png', fog: 0x687c66 }),
  Object.freeze({ id: 'night', file: 'skybox-night.png', url: './images/skyboxes/skybox-night.png', fog: 0x171f35 }),
  Object.freeze({ id: 'space', file: 'skybox-space.png', url: './images/skyboxes/skybox-space.png', fog: 0x111321 }),
]);

export function mapNumberFromSelection(selection) {
  const match = String(selection ?? '').match(/(\d+)/);
  return match ? Math.max(1, Number(match[1])) : 1;
}

export function skyboxForMap(selection) {
  return SKYBOXES[(mapNumberFromSelection(selection) - 1) % SKYBOXES.length];
}

export function configureSkyboxTexture(texture, THREE) {
  texture.mapping = THREE.EquirectangularReflectionMapping;
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.magFilter = THREE.LinearFilter;
  texture.minFilter = THREE.LinearMipmapLinearFilter;
  texture.needsUpdate = true;
  return texture;
}
