import test from 'node:test';
import assert from 'node:assert/strict';
import {
  gateBlocksPosition,
  gateWorldTransform,
} from '../app/src/main/assets/game/js/world-navigation.js';
import { BUNDLED_MAP } from './bundled-map-fixture.mjs';

test('static bars always block their edge while open gate frames do not', () => {
  const bars = BUNDLED_MAP.placements.find((item) => item.pieceId === 'gateMetalBars');
  const frame = BUNDLED_MAP.placements.find((item) => item.pieceId === 'gate');
  const barsPoint = gateWorldTransform(bars, BUNDLED_MAP);
  const framePoint = gateWorldTransform(frame, BUNDLED_MAP);
  assert.equal(gateBlocksPosition(barsPoint, BUNDLED_MAP, true, 0.2), true);
  assert.equal(gateBlocksPosition(framePoint, BUNDLED_MAP, false, 0.2), false);
});

test('animated doors stop blocking only after the gate opens', () => {
  const door = BUNDLED_MAP.placements.find((item) => item.pieceId === 'gateDoor');
  const point = gateWorldTransform(door, BUNDLED_MAP);
  assert.equal(gateBlocksPosition(point, BUNDLED_MAP, false, 0.2), true);
  assert.equal(gateBlocksPosition(point, BUNDLED_MAP, true, 0.2), false);
});

test('gate transforms preserve level and quarter-turn orientation', () => {
  const gate = BUNDLED_MAP.placements.find((item) => item.pieceId === 'gateDoorWindow');
  const transform = gateWorldTransform(gate, BUNDLED_MAP);
  assert.equal(transform.y, gate.level * BUNDLED_MAP.levelHeight);
  assert.equal(transform.rotationY, -gate.rotation * Math.PI / 2);
});
