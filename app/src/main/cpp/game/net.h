// Rede da partida PvP em Wi-Fi local (UDP). O anfitrião é a autoridade de vida, abates,
// renascimento e placar; cada aparelho simula o próprio jogador e detecta os próprios acertos.
#pragma once

#include <netinet/in.h>

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "../core/math.h"
#include "pvp_rules.h"

namespace zz {

constexpr int kMaxNetPlayers = 8;
constexpr uint16_t kGamePort = 47778;

enum NetAnimFlags : uint8_t {
    NF_MOVING = 1, NF_SPRINT = 2, NF_AIR = 4, NF_FIRING = 8, NF_AIM = 16, NF_WATER = 32, NF_CROUCH = 64,
};

enum NetEventKind : uint8_t { NE_FIRE = 1, NE_HIT = 2, NE_KILL = 3, NE_LEFT = 4 };

struct NetEvent {
    uint8_t kind = 0;
    uint16_t id = 0;
    uint8_t a = 0, b = 0, c = 0;  // FIRE: atirador | HIT: alvo, dano, cabeça | KILL: autor, vítima, cabeça | LEFT: jogador
    Vec3 from, to;
    float age = 0;
};

struct NetPlayer {
    bool present = false;  // participa da partida (definido no início)
    bool active = false;   // conectado
    bool alive = true;
    uint8_t skin = 0;
    float health = 100;
    int kills = 0, deaths = 0;
    uint8_t spawnSeq = 0, spawnIndex = 0, lastAttacker = 255;
    Vec3 pos;
    float yaw = 0, pitch = 0, speed = 0;
    uint8_t flags = 0;
    uint32_t version = 0;  // incrementa a cada estado novo recebido
    float lastHeard = 0;
    float respawnTimer = 0;
    float invulnerable = 0;
    // somente no anfitrião
    sockaddr_in addr{};
    bool hasAddr = false;
    uint16_t seen[32] = {};
    int seenCount = 0;
    uint16_t lastSeq = 0;      // último número de sequência aceito (pacotes antigos são descartados)
    bool hasSeq = false;
    pvp::PacketBucket packets;  // limite de pacotes por segundo
    pvp::ShooterState shooter;  // cadência, pente, recarga e tiros validados
};

struct NetConfig {
    bool host = false;
    int localId = 0;
    std::string hostIp;
    int killLimit = 10;
    float timeLimit = 300;
    uint8_t skins[kMaxNetPlayers] = {255, 255, 255, 255, 255, 255, 255, 255};
    uint32_t ips[kMaxNetPlayers] = {};  // IPv4 (ordem de host) esperado de cada id; 0 = sem verificação
};

class NetSession {
public:
    ~NetSession() { stop(); }
    bool start(const NetConfig& config, const std::vector<Vec3>& spawnPoints);
    void stop();
    bool running() const { return socket_ >= 0; }
    bool isHost() const { return config_.host; }
    int localId() const { return config_.localId; }

    void setLocalState(const Vec3& pos, float yaw, float pitch, uint8_t flags, float speed);
    void localFire(const Vec3& from, const Vec3& to);
    void localHit(int target, int damage, bool headshot);

    // Anfitrião: teste de linha de tiro no mundo da arena (true = livre). Sem isso o filtro de linha de tiro fica desligado.
    void setLineOfSight(std::function<bool(const Vec3&, const Vec3&)> fn) { los_ = std::move(fn); }
    const pvp::Counters& guardStats() const { return guard_; }

    // Envia/recebe pacotes; eventos novos (de outros jogadores e do anfitrião) vão para `out`.
    void update(float dt, std::vector<NetEvent>& out);

    const NetPlayer& player(int id) const { return players_[id]; }
    float timeLeft() const { return timeLeft_; }
    bool ended() const { return ended_; }
    int winner() const { return winner_; }
    int killLimit() const { return config_.killLimit; }
    bool connectionLost() const { return !config_.host && sinceSnapshot_ > 6.0f; }
    bool snapshotReceived() const { return snapshotCount_ > 0; }
    float sinceSnapshot() const { return sinceSnapshot_; }
    uint32_t snapshotCount() const { return snapshotCount_; }

private:
    void hostTick(float dt, std::vector<NetEvent>& out);
    void clientTick(float dt, std::vector<NetEvent>& out);
    void receive(std::vector<NetEvent>& out);
    void handleState(const uint8_t* data, size_t len, const sockaddr_in& from, std::vector<NetEvent>& out);
    void handleSnapshot(const uint8_t* data, size_t len, std::vector<NetEvent>& out);
    void applyHit(int shooter, int target, int damage, bool headshot, std::vector<NetEvent>& out);
    void pushHostEvent(NetEvent e, std::vector<NetEvent>& out);
    void sendSnapshot();
    void sendState();
    int chooseSpawn(int id) const;
    bool seenHostEvent(uint16_t id);
    void reject(pvp::Reject why);
    void logGuard(bool force);
    pvp::Limits limits() const;
    bool endpointBoundElsewhere(int id, const sockaddr_in& from) const;

    NetConfig config_;
    std::vector<Vec3> spawns_;
    int socket_ = -1;
    sockaddr_in hostAddr_{};
    NetPlayer players_[kMaxNetPlayers];
    std::vector<NetEvent> recent_;     // eventos reenviados por redundância
    std::vector<NetEvent> localQueue_; // eventos locais do cliente (tiro/acerto)
    uint16_t nextEventId_ = 1;
    uint16_t seq_ = 0;
    uint32_t tick_ = 0;
    float sendTimer_ = 0;
    float timeLeft_ = 300;
    bool ended_ = false;
    int winner_ = -1;
    float sinceSnapshot_ = 0;
    uint32_t snapshotCount_ = 0;
    uint16_t seenHost_[64] = {};
    int seenHostCount_ = 0;
    // Validação no anfitrião (PvP casual: ver pvp_rules.h).
    std::function<bool(const Vec3&, const Vec3&)> los_;
    pvp::Counters guard_;
    pvp::Counters guardLogged_;
    double clock_ = 0;       // relógio real (s) do anfitrião, usado por cadência e pacotes por segundo
    double nextGuardLog_ = 0;
};

}  // namespace zz
