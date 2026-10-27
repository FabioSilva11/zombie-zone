import test from 'node:test';
import assert from 'node:assert/strict';
import { decodeTerrainPacket, encodeTerrainAck } from '../scripts/lan-bot.mjs';

function batch(blocks, first = 1) {
  const packet = Buffer.alloc(14 + blocks.length * 7);
  packet.set([90, 90, 3, 3]);
  packet.writeUInt32LE(42, 4);
  packet.writeUInt32LE(first, 8);
  packet.writeUInt16LE(blocks.length, 12);
  blocks.forEach((block, i) => {
    const offset = 14 + i * 7;
    packet.writeUInt16LE(10 + i, offset);
    packet.writeUInt16LE(16, offset + 2);
    packet.writeUInt16LE(10, offset + 4);
    packet[offset + 6] = block;
  });
  return packet;
}

test('flowing water and falling water advance the durable terrain ACK', () => {
  const decoded = decodeTerrainPacket(batch([58, 65]), 42, 0);
  assert.equal(decoded?.revision, 2);
  assert.deepEqual(decoded.edits.map(edit => edit.block), [58, 65]);
  assert.equal(encodeTerrainAck(42, 1, decoded.revision).readUInt32LE(9), 2);
});

test('duplicate, gap, foreign seed and invalid block do not mutate terrain', () => {
  assert.deepEqual(decodeTerrainPacket(batch([58, 65]), 42, 2).edits, []);
  assert.equal(decodeTerrainPacket(batch([0], 4), 42, 2), null);
  assert.equal(decodeTerrainPacket(batch([0]), 99, 0), null);
  assert.equal(decodeTerrainPacket(batch([58, 66]), 42, 0), null);
});
