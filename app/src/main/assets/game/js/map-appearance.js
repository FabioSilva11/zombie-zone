export const DUNGEON_TEXTURE_VARIANTS = Object.freeze([
  Object.freeze({ id: 'original', file: 'colormap.png', url: './models/dungeon/Textures/colormap.png' }),
  Object.freeze({ id: 'variation-a', file: 'variation-a.png', url: './models/dungeon/Textures/variation-a.png' }),
  Object.freeze({ id: 'variation-b', file: 'variation-b.png', url: './models/dungeon/Textures/variation-b.png' }),
]);

function mapNumber(selection) {
  const match = String(selection ?? '').match(/(?:mapa\s*\()?\s*(\d{1,2})\s*\)?/i);
  if (!match) return 1;
  return Math.min(20, Math.max(1, Number(match[1])));
}

export function dungeonTextureForMap(selection) {
  const zeroBasedMap = mapNumber(selection) - 1;
  // A cada grupo de três mapas a rotação muda um passo. Isso mantém a
  // distribuição equilibrada e evita que as seis fases da campanha caiam
  // sempre na mesma textura por usarem mapas espaçados de três em três.
  const variantIndex = (zeroBasedMap + Math.floor(zeroBasedMap / 3))
    % DUNGEON_TEXTURE_VARIANTS.length;
  return DUNGEON_TEXTURE_VARIANTS[variantIndex];
}

export function configureDungeonTexture(texture, THREE, maxAnisotropy = 1) {
  texture.colorSpace = THREE.SRGBColorSpace;
  texture.flipY = false;
  texture.anisotropy = Math.max(1, Math.min(8, Number(maxAnisotropy) || 1));
  texture.needsUpdate = true;
  return texture;
}

function withDungeonTexture(sourceMaterial, texture) {
  const material = sourceMaterial.clone();
  material.map = texture;
  // O atlas deve chegar ao shader sem tintura adicional do BaseColorFactor.
  material.color?.set(0xffffff);
  material.needsUpdate = true;
  return material;
}

export function applyDungeonTexture(root, texture) {
  root.traverse((child) => {
    if (!child.isMesh || !child.material) return;
    child.material = Array.isArray(child.material)
      ? child.material.map((material) => withDungeonTexture(material, texture))
      : withDungeonTexture(child.material, texture);
  });
  return root;
}
