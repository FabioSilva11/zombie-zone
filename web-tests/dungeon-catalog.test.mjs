import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DUNGEON_PIECES,
  pieceById,
  rotatePiece,
} from '../app/src/main/assets/game/js/dungeon-catalog.js';

test('catalog exposes exactly 24 unique structural pieces', () => {
  assert.equal(DUNGEON_PIECES.length, 24);
  assert.equal(new Set(DUNGEON_PIECES.map((piece) => piece.id)).size, 24);
  assert.deepEqual(
    Object.fromEntries(['corridor', 'gate', 'room', 'stairs'].map((group) => [
      group,
      DUNGEON_PIECES.filter((piece) => piece.group === group).length,
    ])),
    { corridor: 11, gate: 4, room: 7, stairs: 2 },
  );
});

test('special pieces expose their measured native sockets', () => {
  assert.deepEqual(pieceById('roomCorner').sockets.map((socket) => socket.side), ['N', 'W']);
  assert.deepEqual(pieceById('roomWideVariation').sockets.map((socket) => socket.side), ['E', 'W']);
  assert.deepEqual(
    pieceById('corridorTransition').sockets.map(({ side, width }) => [side, width]),
    [['E', 'wide'], ['W', 'narrow']],
  );
  assert.deepEqual(
    pieceById('stairs').sockets.map(({ side, levelDelta }) => [side, levelDelta]),
    [['N', 0], ['S', 1]],
  );
});

test('quarter turns rotate footprint and sockets without changing source data', () => {
  const source = pieceById('corridorJunction');
  const rotated = rotatePiece(source, 1);
  assert.deepEqual(rotated.footprint, { width: 1, height: 1 });
  assert.deepEqual(rotated.sockets.map((socket) => socket.side), ['E', 'S', 'N']);
  assert.deepEqual(source.sockets.map((socket) => socket.side), ['N', 'E', 'W']);
});
