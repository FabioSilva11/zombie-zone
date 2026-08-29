import test from 'node:test';
import assert from 'node:assert/strict';
import { access, readFile } from 'node:fs/promises';

const GAME_ROOT = new URL('../app/src/main/assets/game/', import.meta.url);

test('runtime import map resolves every engine module from packaged assets', async () => {
  const html = await readFile(new URL('index.html', GAME_ROOT), 'utf8');
  assert.doesNotMatch(html, /cdn\.jsdelivr\.net/);
  for (const relativePath of [
    'vendor/three.module.js',
    'vendor/three.core.js',
    'vendor/three/addons/utils/SkeletonUtils.js',
    'vendor/camera-controls.module.js',
    'vendor/rapier.mjs',
    'vendor/rapier_wasm3d_bg.wasm',
    'vendor/nipplejs.mjs',
  ]) {
    await assert.doesNotReject(access(new URL(relativePath, GAME_ROOT)), `${relativePath} should be packaged`);
  }
  assert.match(html, /style-src 'self' 'unsafe-inline'/);
});
