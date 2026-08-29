import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../', import.meta.url);
const android = new URL('app/src/main/', root);

async function pngSize(path) {
  const bytes = await readFile(new URL(path, android));
  assert.deepEqual([...bytes.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10], `${path} must be a PNG`);
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

test('Android launcher icon assets have every density and adaptive resource', async () => {
  const sizes = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
  for (const [density, edge] of Object.entries(sizes)) {
    assert.deepEqual(await pngSize(`res/mipmap-${density}/ic_launcher.png`), { width: edge, height: edge });
    assert.deepEqual(await pngSize(`res/mipmap-${density}/ic_launcher_round.png`), { width: edge, height: edge });
  }
  assert.deepEqual(await pngSize('res/drawable-nodpi/ic_launcher_foreground.png'), { width: 432, height: 432 });

  const [manifest, adaptive, roundAdaptive] = await Promise.all([
    readFile(new URL('AndroidManifest.xml', android), 'utf8'),
    readFile(new URL('res/mipmap-anydpi-v26/ic_launcher.xml', android), 'utf8'),
    readFile(new URL('res/mipmap-anydpi-v26/ic_launcher_round.xml', android), 'utf8'),
  ]);
  assert.match(manifest, /android:icon="@mipmap\/ic_launcher"/);
  assert.match(manifest, /android:roundIcon="@mipmap\/ic_launcher_round"/);
  for (const xml of [adaptive, roundAdaptive]) {
    assert.match(xml, /@color\/ic_launcher_background/);
    assert.match(xml, /@drawable\/ic_launcher_foreground/);
  }
});
