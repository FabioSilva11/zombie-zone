#include "net.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstring>

#include "../core/log.h"

namespace zz {

namespace {

constexpr uint8_t kVersion = 1;
constexpr uint8_t kTypeState = 1;
constexpr uint8_t kTypeSnapshot = 2;
constexpr float kSendInterval = 0.05f;   // 20 Hz
constexpr float kHostEventLife = 0.6f;   // eventos repetidos em vários instantâneos
constexpr float kClientEventLife = 0.35f;
constexpr float kTimeout = 6.0f;

struct Writer {
    uint8_t buf[1400];
    size_t n = 0;
    bool overflow = false;
    void raw(const void* p, size_t s) {
        if (n + s > sizeof(buf)) { overflow = true; return; }
        std::memcpy(buf + n, p, s);
        n += s;
    }
    void u8(uint8_t v) { raw(&v, 1); }
    void u16(uint16_t v) { raw(&v, 2); }
    void u32(uint32_t v) { raw(&v, 4); }
    void f32(float v) { raw(&v, 4); }
    void vec(const Vec3& v) { f32(v.x); f32(v.y); f32(v.z); }
};

struct Reader {
    const uint8_t* p;
    size_t n, i = 0;
    bool fail = false;
    Reader(const uint8_t* data, size_t len) : p(data), n(len) {}
    void raw(void* out, size_t s) {
        if (i + s > n) { fail = true; std::memset(out, 0, s); return; }
        std::memcpy(out, p + i, s);
        i += s;
    }
    uint8_t u8() { uint8_t v; raw(&v, 1); return v; }
    uint16_t u16() { uint16_t v; raw(&v, 2); return v; }
    uint32_t u32() { uint32_t v; raw(&v, 4); return v; }
    float f32() { float v; raw(&v, 4); return std::isfinite(v) ? v : 0.0f; }
    Vec3 vec() { float x = f32(), y = f32(), z = f32(); return {x, y, z}; }
};

void header(Writer& w, uint8_t type) {
    w.u8('Z');
    w.u8('Z');
    w.u8(kVersion);
    w.u8(type);
}

}  // namespace

bool NetSession::start(const NetConfig& config, const std::vector<Vec3>& spawnPoints) {
    stop();
    config_ = config;
    spawns_ = spawnPoints;
    for (int i = 0; i < kMaxNetPlayers; ++i) {
        players_[i] = NetPlayer{};
        players_[i].present = config.skins[i] != 255;
        players_[i].active = players_[i].present;
        players_[i].skin = config.skins[i] == 255 ? 0 : config.skins[i];
        players_[i].lastHeard = -8.0f;  // tolerância enquanto os aparelhos geram o mundo
        if (!spawns_.empty()) {
            players_[i].spawnIndex = static_cast<uint8_t>(i % spawns_.size());
            players_[i].pos = spawns_[players_[i].spawnIndex];
        }
        players_[i].invulnerable = 2.0f;
    }
    recent_.clear();
    localQueue_.clear();
    timeLeft_ = config.timeLimit;
    ended_ = false;
    winner_ = -1;
    sinceSnapshot_ = -8.0f;
    snapshotCount_ = 0;
    seenHostCount_ = 0;
    tick_ = 0;

    socket_ = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (socket_ < 0) {
        LOGE("socket() falhou: %d", errno);
        return false;
    }
    int yes = 1;
    setsockopt(socket_, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    sockaddr_in local{};
    local.sin_family = AF_INET;
    local.sin_addr.s_addr = htonl(INADDR_ANY);
    local.sin_port = htons(config.host ? kGamePort : 0);
    if (bind(socket_, reinterpret_cast<sockaddr*>(&local), sizeof(local)) < 0) {
        LOGE("bind() falhou: %d", errno);
        stop();
        return false;
    }
    fcntl(socket_, F_SETFL, fcntl(socket_, F_GETFL, 0) | O_NONBLOCK);
    if (!config.host) {
        hostAddr_ = sockaddr_in{};
        hostAddr_.sin_family = AF_INET;
        hostAddr_.sin_port = htons(kGamePort);
        if (inet_pton(AF_INET, config.hostIp.c_str(), &hostAddr_.sin_addr) != 1) {
            LOGE("IP do anfitrião inválido: %s", config.hostIp.c_str());
            stop();
            return false;
        }
    }
    LOGI("PvP iniciado: %s, jogador %d", config.host ? "anfitrião" : "cliente", config.localId);
    return true;
}

void NetSession::stop() {
    if (socket_ >= 0) ::close(socket_);
    socket_ = -1;
}

void NetSession::setLocalState(const Vec3& pos, float yaw, float pitch, uint8_t flags, float speed) {
    NetPlayer& p = players_[config_.localId];
    p.pos = pos;
    p.yaw = yaw;
    p.pitch = pitch;
    p.flags = flags;
    p.speed = speed;
}

void NetSession::localFire(const Vec3& from, const Vec3& to) {
    NetEvent e;
    e.kind = NE_FIRE;
    e.id = nextEventId_++;
    e.a = static_cast<uint8_t>(config_.localId);
    e.from = from;
    e.to = to;
    localQueue_.push_back(e);
}

void NetSession::localHit(int target, int damage, bool headshot) {
    NetEvent e;
    e.kind = NE_HIT;
    e.id = nextEventId_++;
    e.a = static_cast<uint8_t>(target);
    e.b = static_cast<uint8_t>(std::max(1, std::min(60, damage)));
    e.c = headshot ? 1 : 0;
    localQueue_.push_back(e);
}

void NetSession::update(float dt, std::vector<NetEvent>& out) {
    if (socket_ < 0) return;
    if (config_.host) hostTick(dt, out);
    else clientTick(dt, out);
}

// ----------------------------------------------------------------------------- anfitrião

void NetSession::pushHostEvent(NetEvent e, std::vector<NetEvent>& out) {
    e.id = nextEventId_++;
    e.age = 0;
    recent_.push_back(e);
    // O próprio anfitrião não precisa ver de novo os efeitos do seu tiro.
    if (!(e.kind == NE_FIRE && e.a == config_.localId)) out.push_back(e);
}

void NetSession::applyHit(int shooter, int target, int damage, bool headshot, std::vector<NetEvent>& out) {
    if (ended_ || shooter < 0 || target < 0 || shooter >= kMaxNetPlayers || target >= kMaxNetPlayers || shooter == target) return;
    NetPlayer& s = players_[shooter];
    NetPlayer& t = players_[target];
    if (!s.present || !s.active || !s.alive || !t.present || !t.active || !t.alive || t.invulnerable > 0) return;
    if (distXZ(s.pos, t.pos) > 160.0f) return;
    t.health -= static_cast<float>(std::max(1, std::min(60, damage)));
    t.lastAttacker = static_cast<uint8_t>(shooter);
    if (t.health > 0) return;
    t.health = 0;
    t.alive = false;
    t.respawnTimer = 3.0f;
    t.deaths += 1;
    s.kills += 1;
    NetEvent kill;
    kill.kind = NE_KILL;
    kill.a = static_cast<uint8_t>(shooter);
    kill.b = static_cast<uint8_t>(target);
    kill.c = headshot ? 1 : 0;
    pushHostEvent(kill, out);
    if (s.kills >= config_.killLimit) {
        ended_ = true;
        winner_ = shooter;
    }
}

int NetSession::chooseSpawn(int id) const {
    if (spawns_.empty()) return 0;
    int best = 0;
    float bestScore = -1;
    for (size_t i = 0; i < spawns_.size(); ++i) {
        float nearest = 1e9f;
        for (int k = 0; k < kMaxNetPlayers; ++k) {
            const NetPlayer& o = players_[k];
            if (k == id || !o.present || !o.active || !o.alive) continue;
            nearest = std::min(nearest, distXZ(o.pos, spawns_[i]));
        }
        // Pequena variação para não renascer sempre no mesmo ponto.
        float score = nearest + static_cast<float>((tick_ * 7 + i * 13) % 11);
        if (score > bestScore) {
            bestScore = score;
            best = static_cast<int>(i);
        }
    }
    return best;
}

void NetSession::hostTick(float dt, std::vector<NetEvent>& out) {
    receive(out);
    NetPlayer& me = players_[config_.localId];
    me.active = true;
    me.lastHeard = 0;
    for (const NetEvent& e : localQueue_) {
        if (e.kind == NE_FIRE) pushHostEvent(e, out);
        else if (e.kind == NE_HIT) applyHit(config_.localId, e.a, e.b, e.c != 0, out);
    }
    localQueue_.clear();

    for (int i = 0; i < kMaxNetPlayers; ++i) {
        NetPlayer& p = players_[i];
        if (!p.present) continue;
        if (i != config_.localId && p.active) {
            p.lastHeard += dt;
            if (p.lastHeard > kTimeout) {
                p.active = false;
                NetEvent left;
                left.kind = NE_LEFT;
                left.a = static_cast<uint8_t>(i);
                pushHostEvent(left, out);
            }
        }
        p.invulnerable = std::max(0.0f, p.invulnerable - dt);
        if (!p.alive && !ended_) {
            p.respawnTimer -= dt;
            if (p.respawnTimer <= 0) {
                p.alive = true;
                p.health = 100;
                p.spawnSeq = static_cast<uint8_t>(p.spawnSeq + 1);
                p.spawnIndex = static_cast<uint8_t>(chooseSpawn(i));
                if (!spawns_.empty()) p.pos = spawns_[p.spawnIndex];
                p.invulnerable = 2.0f;
            }
        }
    }
    if (!ended_) {
        timeLeft_ -= dt;
        int activeCount = 0;
        for (const auto& p : players_)
            if (p.present && p.active) ++activeCount;
        if (timeLeft_ <= 0 || activeCount < 2) {
            // Fim por tempo (ou todos os adversários saíram): vence quem tem mais abates.
            if (timeLeft_ <= 0 || tick_ > 20 * 12) {
                ended_ = true;
                timeLeft_ = std::max(0.0f, timeLeft_);
                int best = config_.localId;
                for (int i = 0; i < kMaxNetPlayers; ++i)
                    if (players_[i].present && players_[i].kills > players_[best].kills) best = i;
                winner_ = best;
            }
        }
    }
    for (auto& e : recent_) e.age += dt;
    recent_.erase(std::remove_if(recent_.begin(), recent_.end(), [](const NetEvent& e) { return e.age > kHostEventLife; }), recent_.end());
    sinceSnapshot_ = 0;
    sendTimer_ -= dt;
    if (sendTimer_ <= 0) {
        sendTimer_ = kSendInterval;
        ++tick_;
        sendSnapshot();
    }
}

void NetSession::handleState(const uint8_t* data, size_t len, const sockaddr_in& from, std::vector<NetEvent>& out) {
    Reader r(data, len);
    int id = r.u8();
    r.u16();  // seq
    Vec3 pos = r.vec();
    float yaw = r.f32(), pitch = r.f32();
    uint8_t flags = r.u8();
    float speed = r.f32();
    int count = r.u8();
    if (r.fail || id <= 0 || id >= kMaxNetPlayers || id == config_.localId || !players_[id].present) return;
    NetPlayer& p = players_[id];
    if (!p.active) {
        p.active = true;
        LOGI("Jogador %d reconectado", id);
    }
    p.addr = from;
    p.hasAddr = true;
    p.lastHeard = 0;
    if (p.alive) p.pos = pos;
    p.yaw = yaw;
    p.pitch = pitch;
    p.flags = flags;
    p.speed = speed;
    ++p.version;
    for (int i = 0; i < count && !r.fail; ++i) {
        NetEvent e;
        e.kind = r.u8();
        e.id = r.u16();
        if (e.kind == NE_FIRE) {
            e.from = r.vec();
            e.to = r.vec();
        } else if (e.kind == NE_HIT) {
            e.a = r.u8();
            e.b = r.u8();
            e.c = r.u8();
        } else {
            return;
        }
        if (r.fail) return;
        bool dup = false;
        for (int k = 0; k < std::min(p.seenCount, 32); ++k)
            if (p.seen[k] == e.id) { dup = true; break; }
        if (dup) continue;
        p.seen[p.seenCount % 32] = e.id;
        ++p.seenCount;
        if (e.kind == NE_FIRE) {
            e.a = static_cast<uint8_t>(id);
            pushHostEvent(e, out);
        } else {
            applyHit(id, e.a, e.b, e.c != 0, out);
        }
    }
}

void NetSession::sendSnapshot() {
    Writer w;
    header(w, kTypeSnapshot);
    w.u32(tick_);
    w.f32(timeLeft_);
    w.u8(ended_ ? 1 : 0);
    w.u8(winner_ < 0 ? 255 : static_cast<uint8_t>(winner_));
    w.u8(static_cast<uint8_t>(config_.killLimit));
    int count = 0;
    for (const auto& p : players_)
        if (p.present) ++count;
    w.u8(static_cast<uint8_t>(count));
    for (int i = 0; i < kMaxNetPlayers; ++i) {
        const NetPlayer& p = players_[i];
        if (!p.present) continue;
        w.u8(static_cast<uint8_t>(i));
        w.u8(static_cast<uint8_t>(1 | (p.active ? 2 : 0) | (p.alive ? 4 : 0) | (p.invulnerable > 0 ? 8 : 0)));
        w.u8(static_cast<uint8_t>(std::max(0.0f, std::min(255.0f, p.health))));
        w.u8(static_cast<uint8_t>(std::min(255, p.kills)));
        w.u8(static_cast<uint8_t>(std::min(255, p.deaths)));
        w.u8(p.spawnSeq);
        w.u8(p.spawnIndex);
        w.u8(p.lastAttacker);
        w.u8(p.skin);
        w.vec(p.pos);
        w.f32(p.yaw);
        w.f32(p.pitch);
        w.u8(p.flags);
        w.f32(p.speed);
        w.f32(p.respawnTimer);
    }
    w.u8(static_cast<uint8_t>(std::min<size_t>(recent_.size(), 40)));
    for (size_t i = 0; i < recent_.size() && i < 40; ++i) {
        const NetEvent& e = recent_[i];
        w.u8(e.kind);
        w.u16(e.id);
        w.u8(e.a);
        w.u8(e.b);
        w.u8(e.c);
        if (e.kind == NE_FIRE) {
            w.vec(e.from);
            w.vec(e.to);
        }
    }
    if (w.overflow) return;
    for (int i = 0; i < kMaxNetPlayers; ++i) {
        const NetPlayer& p = players_[i];
        if (i == config_.localId || !p.present || !p.hasAddr) continue;
        sendto(socket_, w.buf, w.n, 0, reinterpret_cast<const sockaddr*>(&p.addr), sizeof(p.addr));
    }
}

// ----------------------------------------------------------------------------- cliente

void NetSession::clientTick(float dt, std::vector<NetEvent>& out) {
    receive(out);
    sinceSnapshot_ += dt;
    for (auto& e : localQueue_) e.age += dt;
    localQueue_.erase(std::remove_if(localQueue_.begin(), localQueue_.end(), [](const NetEvent& e) { return e.age > kClientEventLife; }), localQueue_.end());
    sendTimer_ -= dt;
    if (sendTimer_ <= 0) {
        sendTimer_ = kSendInterval;
        sendState();
    }
}

void NetSession::sendState() {
    const NetPlayer& me = players_[config_.localId];
    Writer w;
    header(w, kTypeState);
    w.u8(static_cast<uint8_t>(config_.localId));
    w.u16(seq_++);
    w.vec(me.pos);
    w.f32(me.yaw);
    w.f32(me.pitch);
    w.u8(me.flags);
    w.f32(me.speed);
    w.u8(static_cast<uint8_t>(std::min<size_t>(localQueue_.size(), 30)));
    for (size_t i = 0; i < localQueue_.size() && i < 30; ++i) {
        const NetEvent& e = localQueue_[i];
        w.u8(e.kind);
        w.u16(e.id);
        if (e.kind == NE_FIRE) {
            w.vec(e.from);
            w.vec(e.to);
        } else {
            w.u8(e.a);
            w.u8(e.b);
            w.u8(e.c);
        }
    }
    if (w.overflow) return;
    sendto(socket_, w.buf, w.n, 0, reinterpret_cast<const sockaddr*>(&hostAddr_), sizeof(hostAddr_));
}

bool NetSession::seenHostEvent(uint16_t id) {
    int n = std::min(seenHostCount_, 64);
    for (int i = 0; i < n; ++i)
        if (seenHost_[i] == id) return true;
    seenHost_[seenHostCount_ % 64] = id;
    ++seenHostCount_;
    return false;
}

void NetSession::handleSnapshot(const uint8_t* data, size_t len, std::vector<NetEvent>& out) {
    Reader r(data, len);
    uint32_t tick = r.u32();
    float timeLeft = r.f32();
    bool ended = r.u8() != 0;
    int winner = r.u8();
    int killLimit = r.u8();
    int count = r.u8();
    if (r.fail) return;
    if (snapshotCount_ > 0 && tick <= tick_ && tick_ - tick < 1000) return;  // fora de ordem
    tick_ = tick;
    timeLeft_ = timeLeft;
    ended_ = ended;
    winner_ = winner == 255 ? -1 : winner;
    config_.killLimit = killLimit;
    for (int i = 0; i < count && !r.fail; ++i) {
        int id = r.u8();
        uint8_t bits = r.u8();
        float health = r.u8();
        int kills = r.u8();
        int deaths = r.u8();
        uint8_t spawnSeq = r.u8();
        uint8_t spawnIndex = r.u8();
        uint8_t lastAttacker = r.u8();
        uint8_t skin = r.u8();
        Vec3 pos = r.vec();
        float yaw = r.f32(), pitch = r.f32();
        uint8_t flags = r.u8();
        float speed = r.f32();
        float respawn = r.f32();
        if (r.fail || id < 0 || id >= kMaxNetPlayers) return;
        NetPlayer& p = players_[id];
        p.present = true;
        p.active = (bits & 2) != 0;
        p.alive = (bits & 4) != 0;
        p.invulnerable = (bits & 8) ? 1.0f : 0.0f;
        p.health = health;
        p.kills = kills;
        p.deaths = deaths;
        p.spawnSeq = spawnSeq;
        p.spawnIndex = spawnIndex;
        p.lastAttacker = lastAttacker;
        p.skin = skin;
        p.respawnTimer = respawn;
        if (id != config_.localId) {
            p.pos = pos;
            p.yaw = yaw;
            p.pitch = pitch;
            p.flags = flags;
            p.speed = speed;
            ++p.version;
        }
    }
    int events = r.u8();
    for (int i = 0; i < events && !r.fail; ++i) {
        NetEvent e;
        e.kind = r.u8();
        e.id = r.u16();
        e.a = r.u8();
        e.b = r.u8();
        e.c = r.u8();
        if (e.kind == NE_FIRE) {
            e.from = r.vec();
            e.to = r.vec();
        }
        if (r.fail) break;
        if (seenHostEvent(e.id)) continue;
        if (e.kind == NE_FIRE && e.a == config_.localId) continue;
        out.push_back(e);
    }
    sinceSnapshot_ = 0;
    ++snapshotCount_;
}

void NetSession::receive(std::vector<NetEvent>& out) {
    uint8_t buf[1500];
    for (int guard = 0; guard < 128; ++guard) {
        sockaddr_in from{};
        socklen_t fromLen = sizeof(from);
        ssize_t n = recvfrom(socket_, buf, sizeof(buf), 0, reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (n <= 0) break;
        if (n < 4 || buf[0] != 'Z' || buf[1] != 'Z' || buf[2] != kVersion) continue;
        if (config_.host && buf[3] == kTypeState) handleState(buf + 4, static_cast<size_t>(n - 4), from, out);
        else if (!config_.host && buf[3] == kTypeSnapshot) handleSnapshot(buf + 4, static_cast<size_t>(n - 4), out);
    }
}

}  // namespace zz
