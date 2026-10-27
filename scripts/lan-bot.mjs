// Jogador-robô para testar o PvP em Wi-Fi local sem um segundo celular.
// Uso: node scripts/lan-bot.mjs <ip-do-celular-anfitrião> [nome] [segundos] [abuso]
// O celular cria a sala; o robô entra, anda em volta do anfitrião e atira nele de vez em quando.
// Com o 4º argumento "abuso", depois de ~8 s o robô também manda pacotes inválidos de propósito
// (id forjado, NaN, rajada além da cadência, acerto sem tiro, pacote gigante) para conferir que o anfitrião
// os rejeita: veja no logcat do anfitrião a linha com a tag [PVP_GUARD] (adb logcat -s ZombieZoneNative).
// O PvP é casual entre amigos; o anfitrião valida remetente, cadência, munição, alcance e linha de tiro.
// Implementa o mesmo protocolo de app/src/main/java/.../net/LanLobby.kt (sala) e
// app/src/main/cpp/game/net.cpp (partida).
import dgram from 'node:dgram';
import { pathToFileURL } from 'node:url';

// Mesmo lote contíguo de terrain_sync.h: 14 bytes de cabeçalho + 7 por edição.
export function decodeTerrainPacket(buf, seed, revision) {
  if (buf.length < 14 || buf.length > 1400 || buf[0] !== 90 || buf[1] !== 90 ||
      buf[2] !== 3 || buf[3] !== 3 || buf.readUInt32LE(4) !== seed) return null;
  const first = buf.readUInt32LE(8), count = buf.readUInt16LE(12), last = first + count - 1;
  if (!first || !count || count > 198 || buf.length !== 14 + count * 7 ||
      first > revision + 1 || last > 0xffffffff) return null;
  const edits = [];
  for (let i = 0; i < count; i++) {
    const o = 14 + i * 7;
    const x = buf.readUInt16LE(o), y = buf.readUInt16LE(o + 2), z = buf.readUInt16LE(o + 4), block = buf[o + 6];
    if (x >= 128 || y >= 128 || z >= 128 || block >= 66) return null; // B_COUNT, incluindo os oito estados de fluxo
    if (first + i > revision) edits.push({ x, y, z, block });
  }
  return { revision: Math.max(revision, last), edits };
}
export function encodeTerrainAck(seed, id, revision) {
  const b = Buffer.alloc(13);
  b.set([90, 90, 3, 4]); b.writeUInt32LE(seed, 4); b[8] = id; b.writeUInt32LE(revision, 9);
  return b;
}

function main() {

const hostIp = process.argv[2];
const name = process.argv[3] ?? 'Robo';
const duration = Number(process.argv[4] ?? 120);
const abuse = process.argv[5] === 'abuso';
if (!hostIp) {
  console.log('uso: node scripts/lan-bot.mjs <ip-do-anfitrião> [nome] [segundos]');
  process.exit(1);
}

const LOBBY_PORT = 47777;
const GAME_PORT = 47778;
const nonce = String(100000 + Math.floor(Math.random() * 900000));
let localId = -1;
let started = false;
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

// ------------------------------------------------------------------ sala
const lobby = dgram.createSocket('udp4');
const sendLobby = (msg) => lobby.send(Buffer.from(`ZZ3|${msg}`), LOBBY_PORT, hostIp);
lobby.on('message', (buf, from) => {
  if (from.address !== hostIp || from.port !== LOBBY_PORT) return;
  const p = buf.toString('utf8').split('|');
  if (p[0] !== 'ZZ3') return;
  if (p[1] === 'ACCEPT' && p[2] === nonce && localId < 0) {
    localId = Number(p[3]);
    log(`entrou na sala como jogador ${localId}`);
  } else if (p[1] === 'REJECT') {
    log('recusado:', p[3]);
    process.exit(2);
  } else if (p[1] === 'ROOM') {
    if (!started) log(`sala: ${p[5]} (limite ${p[2]} abates, ${p[3]} min)`);
  } else if (p[1] === 'START' && !started) {
    started = true;
    log(`PARTIDA! semente ${p[2]}, limite ${p[3]}, jogadores ${p[5]}`);
    startGame(Number(p[2]));
  } else if (p[1] === 'CLOSE') {
    log('anfitrião fechou a sala');
    process.exit(0);
  }
});
lobby.bind(() => {
  sendLobby(`JOIN|${nonce}|${Math.floor(Math.random() * 18)}|${name}`);
  setInterval(() => {
    if (localId < 0) sendLobby(`JOIN|${nonce}|5|${name}`);
    else sendLobby(`PING|${localId}`);
  }, 1000);
});

// ------------------------------------------------------------------ partida
function startGame(seed) {
  const game = dgram.createSocket('udp4');
  let seq = 0;
  let eventId = 1;
  let pos = { x: 64, y: 20, z: 64 };
  let target = null;
  let me = null;
  const queue = [];
  let snapshots = 0;
  let lastKills = 0;
  const t0 = Date.now();
  let terrainRevision = 0;
  const terrain = new Map();
  const sendTerrainAck = () => game.send(encodeTerrainAck(seed, localId, terrainRevision), GAME_PORT, hostIp);

  game.on('message', (buf, from) => {
    if (from.address !== hostIp || from.port !== GAME_PORT || buf.length > 1400) return;
    if (buf.length >= 4 && buf[3] === 3) {
      const batch = decodeTerrainPacket(buf, seed, terrainRevision);
      if (!batch) return;
      for (const e of batch.edits) terrain.set(`${e.x},${e.y},${e.z}`, e.block);
      terrainRevision = batch.revision;
      sendTerrainAck();
      return;
    }
    if (buf.length < 16 || buf[0] !== 0x5a || buf[1] !== 0x5a || buf[2] !== 3 || buf[3] !== 2) return;
    if (buf[15] > 8 || buf.length < 16 + buf[15] * 38 + 1) return;
    let o = 4;
    const u8 = () => buf.readUInt8(o++);
    const f32 = () => { const v = buf.readFloatLE(o); o += 4; return v; };
    o += 4; // tick
    const timeLeft = f32();
    const ended = u8();
    const winner = u8();
    u8(); // limite
    const count = u8();
    for (let i = 0; i < count; i++) {
      const id = u8(); const bits = u8(); const health = u8(); const kills = u8(); const deaths = u8();
      u8(); u8(); u8(); u8(); // spawnSeq, spawnIndex, lastAttacker, skin
      const x = f32(), y = f32(), z = f32(); f32(); f32(); u8(); f32(); f32();
      const p = { id, alive: !!(bits & 4), health, kills, deaths, x, y, z };
      if (id === 0) target = p;
      if (id === localId) me = p;
    }
    snapshots += 1;
    if (me && me.kills !== lastKills) { lastKills = me.kills; log(`robô fez ${me.kills} abate(s)`); }
    if (snapshots % 40 === 1 && target && me) {
      log(`anfitrião: vida ${target.health} abates ${target.kills} | robô: vida ${me.health} vivo=${me.alive} mortes ${me.deaths} | tempo ${timeLeft.toFixed(0)}s`);
    }
    if (ended) {
      log(`fim de partida, vencedor ${winner}`);
      setTimeout(() => process.exit(0), 500);
    }
  });

  let angle = 0;
  setInterval(() => {
    if (target) {
      angle += 0.04;
      pos = { x: target.x + Math.cos(angle) * 7, y: target.y, z: target.z + Math.sin(angle) * 7 };
    }
    const events = queue.splice(0, queue.length);
    const b = Buffer.alloc(64 + events.length * 32);
    let o = 0;
    const w8 = (v) => { b.writeUInt8(v, o); o += 1; };
    const w16 = (v) => { b.writeUInt16LE(v, o); o += 2; };
    const wf = (v) => { b.writeFloatLE(v, o); o += 4; };
    w8(0x5a); w8(0x5a); w8(3); w8(1);
    w8(localId); w16(seq++ & 0xffff);
    wf(pos.x); wf(pos.y); wf(pos.z);
    const yaw = target ? Math.atan2(target.x - pos.x, -(target.z - pos.z)) : 0;
    wf(yaw); wf(0); w8(1); wf(4.5);
    w8(events.length);
    for (const e of events) {
      w8(e.kind); w16(e.id);
      if (e.kind === 1) { wf(e.from.x); wf(e.from.y); wf(e.from.z); wf(e.to.x); wf(e.to.y); wf(e.to.z); }
      else { w8(e.target); w8(e.damage); w8(e.head); }
    }
    game.send(b.subarray(0, o), GAME_PORT, hostIp);
    sendTerrainAck();
  }, 50);

  // A cada 2,5 s: um tiro visível e um acerto no anfitrião.
  setInterval(() => {
    if (!target || !me?.alive || !target.alive) return;
    const from = { x: pos.x, y: pos.y + 1.4, z: pos.z };
    const to = { x: target.x, y: target.y + 1.2, z: target.z };
    queue.push({ kind: 1, id: eventId++, from, to });
    queue.push({ kind: 2, id: eventId++, target: 0, damage: 16, head: 0 });
    log('robô atirou no anfitrião (-16)');
  }, 2500);

  // Pacote de estado "cru" para os testes de abuso.
  function rawState({ id = localId, seqNo = seq++ & 0xffff, p = pos, events = [], pad = 0 } = {}) {
    const b = Buffer.alloc(64 + events.length * 32 + pad);
    let o = 0;
    const w8 = (v) => { b.writeUInt8(v, o); o += 1; };
    const w16 = (v) => { b.writeUInt16LE(v, o); o += 2; };
    const wf = (v) => { b.writeFloatLE(v, o); o += 4; };
    w8(0x5a); w8(0x5a); w8(3); w8(1);
    w8(id); w16(seqNo);
    wf(p.x); wf(p.y); wf(p.z); wf(0); wf(0); w8(1); wf(4.5);
    w8(events.length);
    for (const e of events) {
      w8(e.kind); w16(e.id);
      if (e.kind === 1) { wf(e.from.x); wf(e.from.y); wf(e.from.z); wf(e.to.x); wf(e.to.y); wf(e.to.z); }
      else { w8(e.target); w8(e.damage); w8(e.head); }
    }
    return b.subarray(0, o + pad);
  }
  if (abuse) {
    setTimeout(() => {
      log('modo abuso: enviando pacotes inválidos (o anfitrião deve rejeitar todos)');
      const send = (buf) => game.send(buf, GAME_PORT, hostIp);
      const from = { x: pos.x, y: pos.y + 1.4, z: pos.z };
      const to = target ? { x: target.x, y: target.y + 1.2, z: target.z } : { x: 64, y: 21, z: 64 };
      send(rawState({ id: localId === 1 ? 2 : 1 }));                           // id de outro jogador (remetente)
      send(rawState({ p: { x: NaN, y: 0, z: 0 } }));                           // NaN (formato)
      send(rawState({ pad: 1300 }));                                            // pacote gigante (formato)
      send(rawState({ events: [{ kind: 2, id: eventId++, target: 0, damage: 60, head: 1 }] }));  // acerto sem tiro
      const burst = [];
      for (let i = 0; i < 20; i++) burst.push({ kind: 1, id: eventId++, from, to });             // rajada (cadência/munição)
      for (let k = 0; k < burst.length; k += 10) send(rawState({ events: burst.slice(k, k + 10) }));
      send(rawState({ events: [{ kind: 1, id: eventId++, from: { x: from.x + 90, y: from.y, z: from.z }, to }] })); // origem longe
    }, 8000);
  }

  setTimeout(() => { log('tempo do teste esgotado'); process.exit(0); }, duration * 1000);
  log(`enviando estados para ${hostIp}:${GAME_PORT}`);
  game.bind();
  void t0;
}

}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
