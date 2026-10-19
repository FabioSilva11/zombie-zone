// Regras PURAS de validação do PvP em Wi-Fi local, executadas só no ANFITRIÃO (a autoridade).
// Sem rede, sem mundo e sem relógio próprio: tudo recebe o tempo `now` (segundos) e dados simples,
// para poder rodar no autoteste de depuração (selfTest) sem compilador C++ de host.
//
// O PvP é CASUAL entre amigos: estas regras barram erros e trapaças ingênuas, mas o jogo não é
// vendido como competitivo nem como placar global seguro (cada aparelho ainda simula o próprio corpo).
//
// Ordem de validação de um tiro no anfitrião (net.cpp):
//   remetente -> formato -> taxa de pacotes -> geometria -> cadência/munição -> linha de tiro -> registra.
// O acerto (NE_HIT) só vale se casar com um tiro JÁ validado ("crédito de tiro") do mesmo jogador,
// com o alvo dentro do alcance e do ponto de impacto; o dano é decidido pelo anfitrião.
#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>

#include "../core/math.h"

namespace zz::pvp {

// ----------------------------------------------------------------------------- motivos de rejeição

enum Reject : uint8_t {
    OK = 0,
    SENDER,          // id inexistente/forjado ou endpoint (IP:porta) diferente do registrado
    FORMAT,          // tamanho, NaN, fora de faixa, tipo de evento desconhecido
    PACKET_RATE,     // pacotes por segundo acima do limite
    STALE,           // pacote de estado antigo/repetido (número de sequência)
    DEAD,            // atirador morto ou partida encerrada
    CADENCE,         // disparos mais rápidos que a arma permite
    AMMO,            // sem munição
    RELOAD,          // atirou durante a recarga
    ORIGIN,          // origem do tiro longe do corpo do atirador
    RANGE,           // além do alcance da arma
    LINE_OF_SIGHT,   // o tiro atravessa blocos sólidos
    NO_SHOT,         // acerto sem tiro validado correspondente
    AIM,             // ponto de impacto longe do alvo
    TARGET,          // alvo inválido (morto, ausente, protegido, o próprio atirador)
    kRejectCount
};

inline const char* rejectName(int r) {
    static const char* const names[kRejectCount] = {
        "ok", "remetente", "formato", "taxa_pacotes", "pacote_antigo", "morto", "cadencia", "sem_municao",
        "recarga", "origem", "alcance", "linha_de_tiro", "acerto_sem_tiro", "mira", "alvo",
    };
    return r >= 0 && r < kRejectCount ? names[r] : "?";
}

struct Counters {
    uint32_t rejected[kRejectCount] = {};
    uint32_t firesAccepted = 0;
    uint32_t hitsAccepted = 0;
    uint32_t headDowngraded = 0;  // "cabeça" reclamada sem geometria plausível: vira dano de corpo
    uint32_t total() const {
        uint32_t t = 0;
        for (int i = 1; i < kRejectCount; ++i) t += rejected[i];
        return t;
    }
};

// ----------------------------------------------------------------------------- limites da arma

// Valores vindos da definição real da arma (weaponDef) + margens de tolerância.
struct Limits {
    float interval = 0.1f;   // segundos entre disparos
    int magazine = 30;       // tamanho do pente
    float reload = 1.6f;     // segundos de recarga
    float range = 120.0f;    // alcance máximo do tiro
    int damageBody = 16;
    int damageHead = 34;
};

constexpr float kCadenceSlack = 0.85f;   // aceita até 15% mais rápido que o nominal (jitter de rede)
constexpr float kBurstShots = 6.0f;      // rajada acumulada (pacotes atrasados chegam em bloco)
constexpr float kReloadSlack = 0.85f;    // recarga mínima = 85% da nominal
constexpr float kCreditLife = 1.2f;      // vida de um tiro validado esperando seu acerto
constexpr int kMaxCredits = 8;
constexpr float kOriginSlack = 6.0f;     // distância máxima origem do tiro <-> corpo do atirador
constexpr float kRangeSlack = 2.0f;      // margem no comprimento do tiro
constexpr float kHitRangeSlack = 6.0f;   // margem de latência no alcance até o alvo
constexpr float kAimSlackXZ = 1.8f;      // ponto de impacto vs posição do alvo no anfitrião (latência)
constexpr float kAimMinY = -1.5f, kAimMaxY = 3.2f;
constexpr float kPlayerHeight = 1.8f;
constexpr float kPacketRate = 40.0f;     // pacotes/s sustentados por jogador (o cliente manda 20)
constexpr float kPacketBurst = 60.0f;
constexpr int kMaxEventsPerPacket = 30;
constexpr size_t kMinStateBytes = 29;    // depois do cabeçalho de 4 bytes: id, seq, pos, yaw, pitch, flags, vel, n
constexpr size_t kMaxStateBytes = 1200;

// ----------------------------------------------------------------------------- finitude e faixas

inline bool finite3(const Vec3& v) { return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z); }

// Coordenadas plausíveis para este jogo (mundo PvP tem 128 blocos; a margem cobre qualquer tamanho futuro).
inline bool plausiblePos(const Vec3& v) {
    return finite3(v) && v.x > -16.0f && v.x < 4096.0f && v.z > -16.0f && v.z < 4096.0f && v.y > -64.0f && v.y < 512.0f;
}

// Ponto final de um tiro: sem acerto ele cai no fim do alcance e pode sair do mundo por qualquer lado
// (x/z negativos inclusive). O comprimento já é limitado por RANGE; aqui só barramos lixo e valores absurdos.
inline bool plausibleEnd(const Vec3& v) {
    constexpr float kLo = -300.0f, kHi = 4096.0f + 300.0f;
    return finite3(v) && v.x > kLo && v.x < kHi && v.z > kLo && v.z < kHi && v.y > -300.0f && v.y < 800.0f;
}

inline bool validStateFields(const Vec3& pos, float yaw, float pitch, float speed, uint8_t flags) {
    return plausiblePos(pos) && std::isfinite(yaw) && std::fabs(yaw) < 1000.0f && std::isfinite(pitch) && std::fabs(pitch) < 4.0f &&
           std::isfinite(speed) && speed >= 0.0f && speed < 40.0f && (flags & 0x80) == 0;
}

inline bool statePacketSizeOk(size_t bytesAfterHeader) { return bytesAfterHeader >= kMinStateBytes && bytesAfterHeader <= kMaxStateBytes; }

// Número de sequência de 16 bits: `cur` é mais novo que `prev` (com volta).
inline bool seqIsNewer(uint16_t prev, uint16_t cur) { return static_cast<int16_t>(static_cast<uint16_t>(cur - prev)) > 0; }

// ----------------------------------------------------------------------------- pacotes por segundo

struct PacketBucket {
    double last = -1.0;
    float tokens = kPacketBurst;
};

inline bool packetAllowed(PacketBucket& b, double now) {
    if (b.last < 0.0) b.last = now;
    double dt = now - b.last;
    if (dt < 0.0) dt = 0.0;
    b.last = now;
    b.tokens = std::fmin(kPacketBurst, b.tokens + static_cast<float>(dt) * kPacketRate);
    if (b.tokens < 1.0f) return false;
    b.tokens -= 1.0f;
    return true;
}

// ----------------------------------------------------------------------------- atirador (cadência, pente, recarga)

struct ShotCredit {
    Vec3 from, to;
    double t = -1e9;
    bool open = false;
};

struct ShooterState {
    double lastT = -1.0;         // último instante processado
    float tokens = kBurstShots;  // disparos disponíveis (balde de cadência)
    int ammo = 30;
    double lastShotT = -1e9;
    double reloadUntil = 0;      // enquanto now < reloadUntil o pente está vazio e recarregando
    ShotCredit credits[kMaxCredits];
    int nextCredit = 0;
};

inline void shooterReset(ShooterState& s, const Limits& lim, double now) {
    s = ShooterState{};
    s.lastT = now;
    s.ammo = lim.magazine;
}

// Reabastece o balde pelo tempo passado e presume recarga manual se o jogador ficou parado tempo suficiente.
inline void shooterAdvance(ShooterState& s, const Limits& lim, double now) {
    if (s.lastT < 0.0) {
        shooterReset(s, lim, now);
        return;
    }
    double dt = now - s.lastT;
    if (dt < 0.0) dt = 0.0;
    s.lastT = now;
    s.tokens = std::fmin(kBurstShots, s.tokens + static_cast<float>(dt) / (lim.interval * kCadenceSlack));
    if (s.ammo < lim.magazine && now - s.lastShotT >= lim.reload * kReloadSlack && now >= s.reloadUntil) s.ammo = lim.magazine;
}

// Gasta um disparo. Deve vir depois de shooterAdvance.
inline Reject shooterTryFire(ShooterState& s, const Limits& lim, double now) {
    if (now < s.reloadUntil) return RELOAD;
    if (s.ammo <= 0) return AMMO;
    if (s.tokens < 1.0f) return CADENCE;
    s.tokens -= 1.0f;
    s.ammo -= 1;
    s.lastShotT = now;
    if (s.ammo <= 0) s.reloadUntil = now + lim.reload * kReloadSlack;
    return OK;
}

// ----------------------------------------------------------------------------- geometria do tiro

inline Reject validateFire(const Vec3& from, const Vec3& to, const Vec3& shooterPos, const Limits& lim) {
    if (!plausiblePos(from) || !plausibleEnd(to)) return FORMAT;
    if (length(from - shooterPos) > kOriginSlack) return ORIGIN;
    if (length(to - from) > lim.range + kRangeSlack) return RANGE;
    return OK;
}

// Trecho que o raycast de linha de tiro deve examinar: recua da origem (o cano pode encostar numa quina) e
// do ponto de impacto (que fica sobre a superfície do alvo/bloco).
inline void losSegment(const Vec3& from, const Vec3& to, Vec3& a, Vec3& b) {
    Vec3 d = to - from;
    float len = length(d);
    if (len < 1e-3f) {
        a = from;
        b = to;
        return;
    }
    Vec3 dir = d / len;
    float cutStart = std::fmin(0.6f, len * 0.5f), cutEnd = std::fmin(0.3f, len * 0.25f);
    a = from + dir * cutStart;
    b = to - dir * cutEnd;
}

inline void creditAdd(ShooterState& s, const Vec3& from, const Vec3& to, double now) {
    ShotCredit& c = s.credits[s.nextCredit];
    s.nextCredit = (s.nextCredit + 1) % kMaxCredits;
    c.from = from;
    c.to = to;
    c.t = now;
    c.open = true;
}

// O ponto de impacto declarado cai perto do corpo do alvo (posição no anfitrião, com folga de latência)?
inline bool aimMatches(const Vec3& impact, const Vec3& targetPos) {
    float dx = impact.x - targetPos.x, dz = impact.z - targetPos.z;
    float dy = impact.y - targetPos.y;
    return dx * dx + dz * dz <= (0.36f + kAimSlackXZ) * (0.36f + kAimSlackXZ) && dy >= kAimMinY && dy <= kAimMaxY;
}

// Procura o tiro validado mais antigo ainda aberto que combine com o alvo. -1 = nenhum tiro aberto; -2 = nenhum combina.
inline int creditFind(const ShooterState& s, const Vec3& targetPos, double now) {
    int best = -1;
    bool anyOpen = false;
    for (int i = 0; i < kMaxCredits; ++i) {
        const ShotCredit& c = s.credits[i];
        if (!c.open || now - c.t > kCreditLife) continue;
        anyOpen = true;
        if (!aimMatches(c.to, targetPos)) continue;
        if (best < 0 || c.t < s.credits[best].t) best = i;
    }
    if (best >= 0) return best;
    return anyOpen ? -2 : -1;
}

// Alcance até o alvo, medido da origem do tiro, com folga de latência.
inline Reject validateHitRange(const ShotCredit& c, const Vec3& targetPos, const Limits& lim) {
    float dx = c.from.x - targetPos.x, dz = c.from.z - targetPos.z;
    float lim2 = lim.range + kHitRangeSlack;
    return dx * dx + dz * dz <= lim2 * lim2 ? OK : RANGE;
}

// "Cabeça" só é plausível se o impacto está na metade de cima do corpo (com folga para agachar/pular).
inline bool headPlausible(const Vec3& impact, const Vec3& targetPos) { return impact.y - targetPos.y >= kPlayerHeight * 0.68f - 0.6f; }

inline int hitDamage(bool head, const Limits& lim) { return head ? lim.damageHead : lim.damageBody; }

// ----------------------------------------------------------------------------- autoteste (depuração)

// Retorna quantas verificações falharam (0 = tudo certo); `firstFailLine` recebe a linha da primeira falha.
inline int selfTest(int* firstFailLine) {
    int failed = 0;
    int firstLine = 0;
#define PVP_CHECK(cond)                      \
    do {                                     \
        if (!(cond)) {                       \
            ++failed;                        \
            if (firstLine == 0) firstLine = __LINE__; \
        }                                    \
    } while (0)

    Limits lim;  // fuzil: 0,1 s, 30 balas, 1,6 s, 120 m
    const Vec3 pos(60, 10, 60);

    // Cadência: rajada inicial de 6, depois recusa; após 0,09 s volta um disparo.
    {
        ShooterState s;
        double t = 100.0;
        shooterReset(s, lim, t);
        int ok = 0;
        for (int i = 0; i < 6; ++i) {
            shooterAdvance(s, lim, t);
            if (shooterTryFire(s, lim, t) == OK) ++ok;
        }
        PVP_CHECK(ok == 6);
        shooterAdvance(s, lim, t);
        PVP_CHECK(shooterTryFire(s, lim, t) == CADENCE);
        t += 0.09;
        shooterAdvance(s, lim, t);
        PVP_CHECK(shooterTryFire(s, lim, t) == OK);
    }
    // Ritmo legítimo (0,1 s) passa por 30 tiros; o 31º cai na recarga; só depois de ~1,4 s volta.
    {
        ShooterState s;
        double t = 5.0;
        shooterReset(s, lim, t);
        int ok = 0;
        for (int i = 0; i < 30; ++i) {
            t += 0.1;
            shooterAdvance(s, lim, t);
            if (shooterTryFire(s, lim, t) == OK) ++ok;
        }
        PVP_CHECK(ok == 30);
        t += 0.1;
        shooterAdvance(s, lim, t);
        PVP_CHECK(shooterTryFire(s, lim, t) == RELOAD);
        t += 1.0;
        shooterAdvance(s, lim, t);
        PVP_CHECK(shooterTryFire(s, lim, t) == RELOAD);
        t += 0.4;
        shooterAdvance(s, lim, t);
        PVP_CHECK(shooterTryFire(s, lim, t) == OK);
    }
    // Recarga manual presumida: parou por mais que a recarga -> pente cheio de novo.
    {
        ShooterState s;
        double t = 1.0;
        shooterReset(s, lim, t);
        for (int i = 0; i < 10; ++i) {
            t += 0.1;
            shooterAdvance(s, lim, t);
            shooterTryFire(s, lim, t);
        }
        PVP_CHECK(s.ammo == 20);
        t += 0.5;
        shooterAdvance(s, lim, t);
        PVP_CHECK(s.ammo == 20);  // ainda cedo: não houve tempo de recarregar
        t += 1.2;
        shooterAdvance(s, lim, t);
        PVP_CHECK(s.ammo == 30);
    }
    // Geometria do tiro.
    {
        PVP_CHECK(validateFire(pos + Vec3(0, 1.3f, 0), pos + Vec3(10, 1, 0), pos, lim) == OK);
        PVP_CHECK(validateFire(Vec3(NAN, 1, 1), pos, pos, lim) == FORMAT);
        PVP_CHECK(validateFire(pos, Vec3(1e9f, 0, 0), pos, lim) == FORMAT);
        // Tiro legítimo que sai do mundo pelo lado negativo (x=50 atirando para -x): não é formato inválido.
        PVP_CHECK(validateFire(Vec3(50, 11, 50), Vec3(-70, 11, 50), Vec3(50, 10, 50), lim) == OK);
        PVP_CHECK(validateFire(Vec3(50, 11, 50), Vec3(50, 11, -70), Vec3(50, 10, 50), lim) == OK);
        PVP_CHECK(validateFire(pos + Vec3(30, 0, 0), pos + Vec3(31, 0, 0), pos, lim) == ORIGIN);
        PVP_CHECK(validateFire(pos, pos + Vec3(0, 0, 200), pos, lim) == RANGE);
    }
    // Linha de tiro: o trecho examinado é menor que o tiro e nunca inverte.
    {
        Vec3 a, b;
        losSegment(Vec3(0, 0, 0), Vec3(10, 0, 0), a, b);
        PVP_CHECK(a.x > 0.5f && a.x < 0.7f && b.x < 9.8f && b.x > 9.6f);
        losSegment(Vec3(0, 0, 0), Vec3(0.2f, 0, 0), a, b);
        PVP_CHECK(a.x <= b.x);
    }
    // Créditos de tiro: um acerto consome um tiro; sem tiro, sem acerto; tiro longe do alvo não conta; expira.
    {
        ShooterState s;
        double t = 50.0;
        shooterReset(s, lim, t);
        const Vec3 target(70, 10, 60);
        PVP_CHECK(creditFind(s, target, t) == -1);
        creditAdd(s, pos + Vec3(0, 1.3f, 0), target + Vec3(0, 1.2f, 0), t);
        int idx = creditFind(s, target, t + 0.2);
        PVP_CHECK(idx >= 0);
        if (idx >= 0) {
            PVP_CHECK(validateHitRange(s.credits[idx], target, lim) == OK);
            s.credits[idx].open = false;
        }
        PVP_CHECK(creditFind(s, target, t + 0.2) == -1);  // já consumido
        creditAdd(s, pos, Vec3(10, 10, 10), t);
        PVP_CHECK(creditFind(s, target, t + 0.2) == -2);  // tiro aberto, mas longe do alvo
        creditAdd(s, pos, target, t);
        PVP_CHECK(creditFind(s, target, t + 5.0) == -1);  // expirou
        PVP_CHECK(validateHitRange(ShotCredit{Vec3(0, 0, 0), Vec3(), 0, true}, Vec3(500, 0, 0), lim) == RANGE);
    }
    // Cabeça e dano.
    {
        const Vec3 target(0, 10, 0);
        PVP_CHECK(headPlausible(Vec3(0, 11.5f, 0), target));
        PVP_CHECK(!headPlausible(Vec3(0, 10.2f, 0), target));
        PVP_CHECK(hitDamage(true, lim) == 34 && hitDamage(false, lim) == 16);
    }
    // Sequência e pacotes por segundo.
    {
        PVP_CHECK(seqIsNewer(5, 6));
        PVP_CHECK(!seqIsNewer(5, 5));
        PVP_CHECK(!seqIsNewer(6, 5));
        PVP_CHECK(seqIsNewer(65535, 0));
        PacketBucket b;
        int pass = 0;
        for (int i = 0; i < 100; ++i)
            if (packetAllowed(b, 10.0)) ++pass;
        PVP_CHECK(pass == static_cast<int>(kPacketBurst));
        PVP_CHECK(packetAllowed(b, 10.5));  // 0,5 s depois já voltaram ~20 fichas
        int steady = 0;
        for (int i = 0; i < 40; ++i)
            if (packetAllowed(b, 20.0 + i * 0.05)) ++steady;  // 20 Hz legítimos
        PVP_CHECK(steady == 40);
    }
    // Campos do pacote de estado e tamanho.
    {
        PVP_CHECK(validStateFields(pos, 1.0f, 0.2f, 5.0f, 0x7F));
        PVP_CHECK(!validStateFields(Vec3(NAN, 0, 0), 0, 0, 0, 0));
        PVP_CHECK(!validStateFields(pos, 5000.0f, 0, 0, 0));
        PVP_CHECK(!validStateFields(pos, 0, 9.0f, 0, 0));
        PVP_CHECK(!validStateFields(pos, 0, 0, -1.0f, 0));
        PVP_CHECK(!validStateFields(pos, 0, 0, 100.0f, 0));
        PVP_CHECK(!validStateFields(pos, 0, 0, 1.0f, 0x80));
        PVP_CHECK(!validStateFields(Vec3(60, 9999, 60), 0, 0, 1.0f, 0));
        PVP_CHECK(statePacketSizeOk(29) && statePacketSizeOk(900) && !statePacketSizeOk(28) && !statePacketSizeOk(1400));
    }
#undef PVP_CHECK
    if (firstFailLine) *firstFailLine = firstLine;
    return failed;
}

}  // namespace zz::pvp
