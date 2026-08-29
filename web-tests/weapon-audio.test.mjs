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
  const { audioFactory: suppliedFactory, ...audioOptions } = options;
  const audio = createWeaponAudio({
    fireUrl: 'rifle-fire.mp3',
    reloadUrl: 'rifle-reload.mp3',
    audioFactory: (url) => {
      const track = suppliedFactory?.(url) ?? fakeAudio(url);
      tracks.push(track);
      return track;
    },
    ...audioOptions,
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
  assert.ok(tracks.every((track) => track.preload === 'auto'));
  assert.ok(tracks.slice(0, 4).every((track) => track.volume === 0.5));
  assert.equal(tracks[4].volume, 0.4);
});

test('falhas síncronas e promessas rejeitadas de play retornam false', async () => {
  const synchronous = createSubject({
    audioFactory: (url) => {
      const track = fakeAudio(url);
      track.play = () => { throw new Error('autoplay bloqueado'); };
      return track;
    },
  });
  assert.equal(await synchronous.audio.playFire(), false);
  assert.equal(await synchronous.audio.playReload(), false);

  const rejected = createSubject({
    audioFactory: (url) => {
      const track = fakeAudio(url);
      track.play = () => Promise.reject(new Error('autoplay bloqueado'));
      return track;
    },
  });
  assert.equal(await rejected.audio.playFire(), false);
  assert.equal(await rejected.audio.playReload(), false);
});

test('poolSize inválido é normalizado e playFire permanece seguro', async () => {
  for (const [poolSize, expectedFireTracks] of [[0, 1], [-4, 1], [2.9, 2]]) {
    const { audio, tracks } = createSubject({ poolSize });
    assert.equal(tracks.length, expectedFireTracks + 1);
    assert.equal(await audio.playFire(), true);
  }
});

test('dispose duas vezes libera recursos uma única vez e impede novas reproduções', async () => {
  const { audio, tracks } = createSubject();
  audio.dispose();
  audio.dispose();
  assert.ok(tracks.every((track) => track.pauseCalls === 1));
  assert.ok(tracks.every((track) => track.removeAttributeCalls === 1));
  assert.ok(tracks.every((track) => track.loadCalls === 1));
  assert.equal(await audio.playFire(), false);
  assert.equal(await audio.playReload(), false);
});
