// Campo de fluxo 2.5D (BFS a partir do jogador) para os zumbis contornarem obstáculos e relevo.
#pragma once

#include <cstdint>
#include <vector>

#include "../core/math.h"
#include "../world/world.h"

namespace zz {

class NavField {
public:
    static constexpr uint16_t kUnreached = 0xFFFF;

    void rebuildHeights(const World& world);
    void compute(int targetX, int targetZ, int maxDistance);
    uint16_t distanceAt(int x, int z) const {
        if (x < 0 || z < 0 || x >= w_ || z >= d_) return kUnreached;
        return dist_[static_cast<size_t>(z) * w_ + x];
    }
    // Próximo ponto (centro de coluna vizinha) rumo ao alvo; false se não houver caminho.
    bool nextStep(const Vec3& from, Vec3& out) const;
    bool walkable(int x, int z) const { return x >= 0 && z >= 0 && x < w_ && z < d_ && !blocked_[static_cast<size_t>(z) * w_ + x]; }
    int heightAt(int x, int z) const { return (x >= 0 && z >= 0 && x < w_ && z < d_) ? height_[static_cast<size_t>(z) * w_ + x] : 0; }

private:
    bool canMove(int ax, int az, int bx, int bz) const;

    int w_ = 0, d_ = 0;
    std::vector<int16_t> height_;
    std::vector<uint8_t> blocked_;
    std::vector<uint16_t> dist_;
    std::vector<int> queue_;
};

}  // namespace zz
