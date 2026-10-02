// Gerador procedural do mundo aberto (ilha) de cada fase, da arena PvP e do cenário do lobby.
#pragma once

#include <cstdint>
#include <vector>

#include "../core/math.h"
#include "theme.h"
#include "world.h"

namespace zz {

// Estilos de mapa sorteados pela semente: cada partida gera um mundo diferente.
enum class MapStyle : int { Island = 0, Archipelago, Valley, Mesas, Crater, Rivers, Count };

struct Camp {
    Vec3 center;   // posição dos pés no centro do acampamento
    float radius;
    bool start;
    bool portal;
    int kind = 0;  // 0 fogueira, 1 ruínas, 2 cemitério, 3 depósito, 4 fazenda
};

struct WorldLayout {
    Vec3 start;
    float startYaw = 0;
    std::vector<Camp> camps;
    Vec3 portalBase;        // centro da base interna do portal
    float portalYaw = 0;    // yaw da normal do portal (de onde o jogador vem)
    std::vector<Vec3> zombieSpawns;
    std::vector<Vec3> spawnPoints;  // renascimento no PvP
    std::vector<Vec3> lights;       // lanternas e fogueiras (luz pontual)
    std::vector<Vec3> campfires;    // partículas de fogo
    int seaLevel = 13;
    MapStyle style = MapStyle::Island;
};

struct GenOptions {
    int size = 192;
    int camps = 6;
    bool portal = true;
    bool pvp = false;
};

constexpr int kWorldSize = 192;
constexpr int kWorldHeight = 48;

WorldLayout generateStageWorld(World& world, const Theme& theme, int stage, uint32_t seed, const GenOptions& options = GenOptions());
WorldLayout generateLobbyWorld(World& world, const Theme& theme);

// Variação de céu/horário a partir da semente (manhã dourada, nublado, noite...).
Theme applySkyVariant(const Theme& base, uint32_t seed, bool allowNight);

}  // namespace zz
