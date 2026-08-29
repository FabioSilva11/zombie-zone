import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
import { createMusicController, MUSIC_MODES } from '../app/src/main/assets/game/js/music-controller.js';

const GAME_ROOT = new URL('../app/src/main/assets/game/', import.meta.url);

function fakeAudio(url) {
  return {
    url,
    paused: true,
    loop: false,
    playCalls: 0,
    pauseCalls: 0,
    play() { this.paused = false; this.playCalls += 1; return Promise.resolve(); },
    pause() { this.paused = true; this.pauseCalls += 1; },
  };
}

test('menu e gameplay nunca tocam ao mesmo tempo', async () => {
  const tracks = [];
  const music = createMusicController({
    menuUrl: 'menu.mp3', gameplayUrl: 'gameplay.mp3', audioFactory: (url) => {
      const track = fakeAudio(url); tracks.push(track); return track;
    },
  });
  await music.setMode(MUSIC_MODES.MENU);
  assert.equal(tracks[0].paused, false);
  assert.equal(tracks[1].paused, true);
  await music.setMode(MUSIC_MODES.GAMEPLAY);
  assert.equal(tracks[0].paused, true);
  assert.equal(tracks[1].paused, false);
});

test('desativar som pausa as duas trilhas e reativar retoma o estado atual', async () => {
  const tracks = [];
  const music = createMusicController({
    menuUrl: 'menu.mp3', gameplayUrl: 'gameplay.mp3', audioFactory: (url) => {
      const track = fakeAudio(url); tracks.push(track); return track;
    },
  });
  await music.setMode(MUSIC_MODES.GAMEPLAY);
  await music.setEnabled(false);
  assert.equal(tracks.every((track) => track.paused), true);
  await music.setEnabled(true);
  assert.equal(tracks[1].paused, false);
});

test('as duas músicas MP3 estão empacotadas e válidas', async () => {
  for (const file of ['musica-menu-tps-ambient.mp3', 'trilha-tps-dark-loop.mp3']) {
    const url = new URL(`audio/${file}`, GAME_ROOT);
    assert.ok((await stat(url)).size > 100_000, `${file} precisa conter a faixa completa`);
    assert.equal((await readFile(url)).subarray(0, 3).toString('ascii'), 'ID3');
  }
});
