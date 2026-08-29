import test from 'node:test';
import assert from 'node:assert/strict';
import { createWeaponAudio } from '../app/src/main/assets/game/js/weapon-audio.js';

function fakeAudio(url) {
  return {
    url,
    paused: true,
    currentTime: 9,
    preload: '',
    volume: 0,
    playCalls: 0,
    pauseCalls: 0,
    removeAttributeCalls: 0,
    loadCalls: 0,
    play() {
      this.paused = false;
      this.playCalls += 1;
      return Promise.resolve();
    },
    pause() {
      this.paused = true;
      this.pauseCalls += 1;
    },
    removeAttribute(name) {
      assert.equal(name, 'src');
      this.removeAttributeCalls += 1;
    },
    load() { this.loadCalls += 1; },
  };
}

function createSubject(options = {}) {
  const tracks = [];
  const audio = createWeaponAudio({
    fireUrl: 'rifle-fire.mp3',
    reloadUrl: 'rifle-reload.mp3',
    audioFactory: (url) => {
      const track = fakeAudio(url);
      tracks.push(track);
      return track;
    },
    ...options,
  });
  return { audio, tracks };
}

test('cinco disparos alternam circularmente em um pool de quatro', async () => {
  const { audio, tracks } = createSubject();
  await Promise.all(Array.from({ length: 5 }, () => audio.playFire()));
  assert.equal(tracks.length, 5);
  assert.deepEqual(tracks.slice(0, 4).map((track) => track.playCalls), [2, 1, 1, 1]);
  assert.equal(tracks[4].playCalls, 0);
  assert.ok(tracks.slice(0, 4).every((track) => track.currentTime === 0));
});

test('desativar som pausa as cinco instâncias e bloqueia novas reproduções', async () => {
  const { audio, tracks } = createSubject();
  await audio.playFire();
  await audio.playReload();
  audio.setEnabled(false);
  assert.ok(tracks.every((track) => track.paused));
  assert.ok(tracks.every((track) => track.pauseCalls === 1));
  assert.equal(await audio.playFire(), false);
  assert.equal(await audio.playReload(), false);
});

test('recarga usa instância separada, com volume reduzido', async () => {
  const { audio, tracks } = createSubject({ volume: 0.5 });
  await audio.playReload();
  assert.equal(tracks[4].url, 'rifle-reload.mp3');
  assert.equal(tracks[4].playCalls, 1);
  assert.equal(tracks.slice(0, 4).reduce((total, track) => total + track.playCalls, 0), 0);
  assert.equal(tracks[0].preload, 'auto');
  assert.equal(tracks[0].volume, 0.5);
  assert.equal(tracks[4].volume, 0.4);
});

test('dispose pausa, libera src e impede novas reproduções', async () => {
  const { audio, tracks } = createSubject();
  audio.dispose();
  assert.ok(tracks.every((track) => track.pauseCalls === 1));
  assert.ok(tracks.every((track) => track.removeAttributeCalls === 1));
  assert.ok(tracks.every((track) => track.loadCalls === 1));
  assert.equal(await audio.playFire(), false);
  assert.equal(await audio.playReload(), false);
});
