#include "nav.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>

namespace zz {

namespace {
const int kDirs[8][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
}

void NavField::rebuildHeights(const World& world) {
    w_ = world.width();
    d_ = world.depth();
    height_.assign(static_cast<size_t>(w_) * d_, 0);
    blocked_.assign(static_cast<size_t>(w_) * d_, 0);
    dist_.assign(static_cast<size_t>(w_) * d_, kUnreached);
    for (int z = 0; z < d_; ++z) {
        for (int x = 0; x < w_; ++x) {
            int ground = world.groundHeight(x, z);
            int stand = world.standHeight(x, z);
            size_t i = static_cast<size_t>(z) * w_ + x;
            height_[i] = static_cast<int16_t>(stand);
            // Troncos, pilhas altas e bordas do mundo bloqueiam a coluna.
            bool tooTall = stand - ground >= 2;
            bool headBlocked = world.solid(x, stand + 1, z);
            bool edge = x < 2 || z < 2 || x >= w_ - 2 || z >= d_ - 2;
            blocked_[i] = (tooTall || headBlocked || edge) ? 1 : 0;
        }
    }
}

bool NavField::canMove(int ax, int az, int bx, int bz) const {
    if (!walkable(ax, az) || !walkable(bx, bz)) return false;
    int ha = heightAt(ax, az), hb = heightAt(bx, bz);
    if (hb - ha > 1 || ha - hb > 4) return false;
    if (ax != bx && az != bz) {
        // Diagonal só se as duas laterais também forem passáveis.
        if (!walkable(bx, az) || !walkable(ax, bz)) return false;
        if (std::abs(heightAt(bx, az) - ha) > 1 || std::abs(heightAt(ax, bz) - ha) > 1) return false;
    }
    return true;
}

void NavField::compute(int tx, int tz, int maxDistance) {
    if (w_ == 0) return;
    std::fill(dist_.begin(), dist_.end(), kUnreached);
    if (tx < 0 || tz < 0 || tx >= w_ || tz >= d_) return;
    queue_.clear();
    queue_.push_back(tz * w_ + tx);
    dist_[static_cast<size_t>(tz) * w_ + tx] = 0;
    size_t head = 0;
    while (head < queue_.size()) {
        int cur = queue_[head++];
        int cx = cur % w_, cz = cur / w_;
        uint16_t cd = dist_[static_cast<size_t>(cur)];
        if (cd >= maxDistance) continue;
        for (const auto& dir : kDirs) {
            int nx = cx + dir[0], nz = cz + dir[1];
            if (nx < 0 || nz < 0 || nx >= w_ || nz >= d_) continue;
            size_t ni = static_cast<size_t>(nz) * w_ + nx;
            if (dist_[ni] != kUnreached) continue;
            // Aresta reversa: o zumbi em (nx,nz) precisa conseguir andar até (cx,cz).
            if (!canMove(nx, nz, cx, cz)) continue;
            dist_[ni] = static_cast<uint16_t>(cd + 1);
            queue_.push_back(static_cast<int>(ni));
        }
    }
}

bool NavField::nextStep(const Vec3& from, Vec3& out) const {
    int x = static_cast<int>(std::floor(from.x)), z = static_cast<int>(std::floor(from.z));
    uint16_t best = distanceAt(x, z);
    if (best == kUnreached) {
        // Fora da malha (empurrado para um canto): procura vizinho alcançado.
        for (const auto& dir : kDirs) {
            uint16_t d = distanceAt(x + dir[0], z + dir[1]);
            if (d < best) {
                best = d;
                out = {x + dir[0] + 0.5f, static_cast<float>(heightAt(x + dir[0], z + dir[1])), z + dir[1] + 0.5f};
            }
        }
        return best != kUnreached;
    }
    if (best == 0) return false;
    int bx = x, bz = z;
    for (const auto& dir : kDirs) {
        int nx = x + dir[0], nz = z + dir[1];
        uint16_t d = distanceAt(nx, nz);
        if (d < best && canMove(x, z, nx, nz)) {
            best = d;
            bx = nx;
            bz = nz;
        }
    }
    if (bx == x && bz == z) return false;
    out = {bx + 0.5f, static_cast<float>(heightAt(bx, bz)), bz + 0.5f};
    return true;
}

}  // namespace zz
