export function createRifleEffects({ THREE, scene, recoilGroup, muzzle }) {
  const flashMaterial = new THREE.MeshStandardMaterial({
    color: 0xffb24a,
    emissive: 0xff7a16,
    emissiveIntensity: 3,
    roughness: 0.25,
  });
  const flash = new THREE.Mesh(new THREE.SphereGeometry(0.12, 8, 6), flashMaterial);
  flash.name = 'rifle-muzzle-flash';
  flash.visible = false;
  muzzle.add(flash);
  const muzzlePosition = new THREE.Vector3();
  const originalRecoilZ = recoilGroup.position.z;
  let flashTime = 0;
  let recoil = 0;
  let disposed = false;
  const tracers = [];

  function removeTracer(tracer) {
    tracer.line.removeFromParent();
    tracer.line.geometry.dispose();
    tracer.line.material.dispose();
    tracers.splice(tracers.indexOf(tracer), 1);
  }

  return {
    flash,
    pulseShot(endPoint) {
      if (disposed) return;
      muzzle.getWorldPosition(muzzlePosition);
      const geometry = new THREE.BufferGeometry().setFromPoints([muzzlePosition.clone(), endPoint.clone()]);
      const material = new THREE.LineBasicMaterial({ color: 0xffb24a });
      const line = new THREE.Line(geometry, material);
      line.name = 'rifle-tracer';
      scene.add(line);
      tracers.push({ line, life: 0.065 });
      flash.visible = true;
      flashTime = 0.045;
      recoil = 0.08;
      recoilGroup.position.z = originalRecoilZ + recoil;
    },
    update(delta) {
      if (disposed) return;
      const elapsed = Math.max(0, delta);
      flashTime = Math.max(0, flashTime - elapsed);
      flash.visible = flashTime > 0;
      [...tracers].forEach((tracer) => {
        tracer.life -= elapsed;
        if (tracer.life <= 0) removeTracer(tracer);
      });
      recoil *= Math.exp(-120 * elapsed);
      if (recoil < 0.00001) recoil = 0;
      recoilGroup.position.z = originalRecoilZ + recoil;
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      [...tracers].forEach(removeTracer);
      flash.removeFromParent();
      flash.geometry.dispose();
      flashMaterial.dispose();
      recoilGroup.position.z = originalRecoilZ;
    },
  };
}
