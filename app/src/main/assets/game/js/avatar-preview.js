const PREVIEW_MODES = Object.freeze({
  home: 'showcase',
  avatar: 'rotate',
  victory: 'celebrate',
});

export function resolvePreviewMode(screen) {
  return PREVIEW_MODES[screen] ?? null;
}

export function previewCameraDistance(size, aspect, verticalFovDegrees = 32, padding = 1.24) {
  const verticalFov = verticalFovDegrees * Math.PI / 180;
  const horizontalFov = 2 * Math.atan(Math.tan(verticalFov / 2) * Math.max(0.1, aspect));
  const halfHeight = size.y / 2;
  const halfWidth = Math.max(size.x, size.z) / 2;
  return Math.max(
    halfHeight / Math.tan(verticalFov / 2),
    halfWidth / Math.tan(horizontalFov / 2),
  ) * padding;
}

export function previewRotationForMode(mode) {
  return mode === 'rotate' ? null : -0.18;
}

function disposeModel(model) {
  model?.traverse((child) => {
    if (!child.isMesh) return;
    child.geometry?.dispose?.();
    const materials = Array.isArray(child.material) ? child.material : [child.material];
    for (const material of materials) {
      if (!material) continue;
      for (const value of Object.values(material)) value?.isTexture && value.dispose?.();
      material.dispose?.();
    }
  });
}

export function choosePreviewClipName(names, mode) {
  const preferences = mode === 'celebrate'
    ? [/celebr|victory|cheer|jump|wave|emote-yes/i, /idle/i, /static/i]
    : mode === 'showcase'
      ? [/emote-yes/i, /wave|taunt|cheer|celebr|victory/i, /idle/i, /static/i]
      : [/idle/i, /static/i];
  for (const pattern of preferences) {
    const name = names.find((candidate) => pattern.test(candidate));
    if (name) return name;
  }
  return names[0] ?? null;
}

function chooseClip(clips, mode) {
  const name = choosePreviewClipName(clips.map((clip) => clip.name), mode);
  return clips.find((clip) => clip.name === name);
}

export function createAvatarPreview({ THREE, GLTFLoader, characterById, hosts, windowRef = window }) {
  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'low-power' });
  renderer.setPixelRatio(Math.min(windowRef.devicePixelRatio || 1, 1.5));
  renderer.setClearColor(0x000000, 0);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.NoToneMapping;
  renderer.toneMappingExposure = 1;
  renderer.domElement.className = 'avatar-preview-canvas';

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(32, 1, 0.1, 60);
  camera.position.set(0, 0.1, 9);
  camera.lookAt(0, 0, 0);
  scene.add(new THREE.HemisphereLight(0xfffbf2, 0x665b4d, 2.15));
  const key = new THREE.DirectionalLight(0xfff2dd, 2.7); key.position.set(4, 7, 5); scene.add(key);
  const rim = new THREE.DirectionalLight(0xdde7ff, 1.2); rim.position.set(-4, 4, -2); scene.add(rim);
  const loader = new GLTFLoader();
  const clock = new THREE.Clock();
  let model = null;
  let mixer = null;
  let loadedClips = [];
  let activeCharacterId = null;
  let modelSize = null;
  let activeMode = null;
  let frame = 0;
  let loadToken = 0;
  let disposed = false;

  const resize = () => {
    const host = renderer.domElement.parentElement;
    if (!host) return;
    const width = Math.max(1, host.clientWidth);
    const height = Math.max(1, host.clientHeight);
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    if (modelSize) camera.position.z = previewCameraDistance(modelSize, camera.aspect, camera.fov);
    camera.updateProjectionMatrix();
    camera.lookAt(0, 0, 0);
  };

  const animate = () => {
    if (disposed || !renderer.domElement.parentElement) { frame = 0; return; }
    const delta = Math.min(clock.getDelta(), 0.05);
    mixer?.update(delta);
    if (model && activeMode === 'rotate') model.rotation.y += delta * 0.48;
    else if (model && activeMode === 'showcase') model.rotation.y = -0.18 + Math.sin(clock.elapsedTime * 0.75) * 0.09;
    renderer.render(scene, camera);
    frame = windowRef.requestAnimationFrame(animate);
  };

  const start = () => {
    if (frame || disposed) return;
    clock.start();
    frame = windowRef.requestAnimationFrame(animate);
  };

  const stop = () => {
    if (frame) windowRef.cancelAnimationFrame(frame);
    frame = 0;
    clock.stop();
  };

  const load = async (characterId) => {
    if (activeCharacterId === characterId && model) return;
    const token = ++loadToken;
    const gltf = await loader.loadAsync(characterById(characterId).model);
    if (disposed || token !== loadToken) { disposeModel(gltf.scene); return; }
    if (model) { scene.remove(model); disposeModel(model); }
    mixer?.stopAllAction?.();
    model = gltf.scene;
    const bounds = new THREE.Box3().setFromObject(model);
    const size = bounds.getSize(new THREE.Vector3());
    const center = bounds.getCenter(new THREE.Vector3());
    const scale = 4.2 / Math.max(0.1, size.y);
    model.scale.setScalar(scale);
    model.position.set(-center.x * scale, -center.y * scale, -center.z * scale);
    model.rotation.y = -0.18;
    model.traverse((child) => { if (child.isMesh) { child.castShadow = false; child.receiveShadow = false; } });
    scene.add(model);
    modelSize = new THREE.Vector3(size.x * scale, size.y * scale, size.z * scale);
    resize();
    mixer = gltf.animations?.length ? new THREE.AnimationMixer(model) : null;
    loadedClips = gltf.animations ?? [];
    const clip = chooseClip(loadedClips, activeMode);
    if (mixer && clip) mixer.clipAction(clip).reset().fadeIn(0.12).play();
    activeCharacterId = characterId;
  };

  const show = async (screen, characterId) => {
    const host = hosts[screen];
    const mode = resolvePreviewMode(screen);
    if (!host || !mode || disposed) { stop(); renderer.domElement.remove(); return; }
    activeMode = mode;
    if (renderer.domElement.parentElement !== host) host.replaceChildren(renderer.domElement);
    resize();
    await load(characterId);
    if (model) {
      const fixedRotation = previewRotationForMode(mode);
      if (fixedRotation !== null) model.rotation.y = fixedRotation;
      const clip = chooseClip(loadedClips, mode);
      if (mixer && clip) { mixer.stopAllAction(); mixer.clipAction(clip).reset().fadeIn(0.12).play(); }
    }
    start();
  };

  const hide = () => { stop(); renderer.domElement.remove(); };
  const onResize = () => resize();
  windowRef.addEventListener('resize', onResize);

  return {
    show,
    hide,
    dispose() {
      if (disposed) return;
      disposed = true;
      loadToken += 1;
      stop();
      windowRef.removeEventListener('resize', onResize);
      mixer?.stopAllAction?.();
      if (model) { scene.remove(model); disposeModel(model); }
      renderer.dispose();
      renderer.domElement.remove();
    },
  };
}
