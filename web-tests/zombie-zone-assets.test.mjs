import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';

const imageUrl = (name) => new URL(`../app/src/main/assets/game/images/${name}`, import.meta.url);

test('fundos Zombie Zone são locais, completos e usados pelo tema', () => {
  const files = [
    'zombie-zone-city-bg.png',
    'zombie-zone-campaign-bg.png',
    'zombie-zone-lab-bg.png',
  ];
  const css = readFileSync(new URL('../app/src/main/assets/game/css/game.css', import.meta.url), 'utf8');

  for (const file of files) {
    assert.ok(statSync(imageUrl(file)).size > 100_000, `${file} precisa conter a arte final`);
    assert.match(css, new RegExp(file.replace('.', '\\.')));
  }
  assert.doesNotMatch(css, /url\(['"]?https?:/i);
});

test('sons de rifle MIT e aviso de licença estão empacotados', () => {
  // Origem: FootPrint Arts/ThreeJS_FPS_2.0, revisão e2f3fe94ffe681e331f03790b03c6185e5f80d15.
  const fingerprints = {
    'rifle-fire.mp3': '1b48ffd1316801e21ca37772f2f5229da926d96c903d5472a576da66818f4a2d',
    'rifle-reload.mp3': 'c09b4b20ef9785ec65f8a3185451a62981f442441a81b5a992f01ec111266149',
  };
  for (const [file, expectedHash] of Object.entries(fingerprints)) {
    const url = new URL(`../app/src/main/assets/game/audio/${file}`, import.meta.url);
    assert.ok(statSync(url).size > 10_000);
    assert.equal(createHash('sha256').update(readFileSync(url)).digest('hex'), expectedHash);
  }
  const noticeUrl = new URL('../app/src/main/assets/game/notices/ThreeJS_FPS_2.0-MIT.txt', import.meta.url);
  const notice = readFileSync(noticeUrl, 'utf8');
  assert.equal(createHash('sha256').update(readFileSync(noticeUrl)).digest('hex'), 'bcf0e0542c9c087c9524b2da36a1ddd0631ad7ea6afb680a8fdf3a203618f70d');
  assert.match(notice, /Copyright \(c\) 2025 FootPrint Arts/);
  assert.match(notice, /THE SOFTWARE IS PROVIDED "AS IS"/);
});
