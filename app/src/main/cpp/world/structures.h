// Estruturas procedurais (árvores, acampamentos, torre, ninho, portal) escritas num "BlockSink":
// o mundo finito (lobby/PvP), um chunk em geração ou um molde guardado da ilha em streaming.
#pragma once

#include <cstdint>
#include <vector>

#include "../core/math.h"
#include "../core/random.h"
#include "theme.h"

namespace zz {

class BlockSink {
public:
    virtual ~BlockSink() = default;
    virtual uint8_t get(int x, int y, int z) const = 0;
    virtual void set(int x, int y, int z, uint8_t block) = 0;
};

enum CampKind {
    K_FIRE = 0, K_RUINS = 1, K_GRAVEYARD = 2, K_DEPOT = 3, K_FARM = 4,
    K_TOWER = 5, K_OUTPOST = 6, K_NEST = 7, K_PORTAL = 8,
};

struct Camp {
    Vec3 center;   // posição dos pés no centro do acampamento
    float radius;
    bool start;
    bool portal;
    int kind = 0;
};

// Saídas da decoração: luzes, fogueiras e baús (posições dos pés).
struct DecorOut {
    std::vector<Vec3> lights;
    std::vector<Vec3> campfires;
    std::vector<Vec3> chests;
};

void placeTree(BlockSink& w, TreeKind kind, int x, int y, int z, Rng& rng);
void lanternPost(BlockSink& w, int x, int y, int z, DecorOut& out);
void decorateCamp(BlockSink& w, const Camp& camp, const Theme& theme, Rng& rng, DecorOut& out);
// Moldura de obsidiana com a abertura voltada para `towards`.
void buildPortal(BlockSink& w, const Camp& camp, const Vec3& towards, Vec3& base, float& yaw, DecorOut& out);
// Cratera infectada com pedestal; `core` recebe o ponto onde flutua o núcleo do ninho.
void buildNest(BlockSink& w, const Camp& camp, Rng& rng, DecorOut& out, Vec3& core);

}  // namespace zz
