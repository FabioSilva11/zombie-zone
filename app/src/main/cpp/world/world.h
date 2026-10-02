// Mundo voxel: blocos em grade, chunks com malha (opaca, recorte e água), raycast e colisão AABB.
#pragma once

#include <cstdint>
#include <vector>

#include "../core/math.h"
#include "blocks.h"

namespace zz {

constexpr int kChunkSize = 16;
constexpr float kPosScale = 8.0f;  // posições dos vértices em 1/8 de bloco

enum VertexFlags : uint8_t {
    VF_WATER = 1,
    VF_EMISSIVE = 2,
    VF_SWAY = 4,
};

#pragma pack(push, 1)
struct VoxelVertex {
    int16_t x, y, z;
    uint8_t u, v;
    uint8_t layer, light, flags, pad;
};
#pragma pack(pop)
static_assert(sizeof(VoxelVertex) == 12, "VoxelVertex deve ter 12 bytes");

struct ChunkMesh {
    std::vector<VoxelVertex> opaque;
    std::vector<VoxelVertex> cutout;
    std::vector<VoxelVertex> water;
};

struct Chunk {
    int cx = 0, cz = 0;
    bool dirty = true;
    uint32_t version = 0;  // incrementa a cada remalha; o renderer reenvia ao GPU
    ChunkMesh mesh;
    AABB bounds;
};

struct RayHit {
    int x = 0, y = 0, z = 0;
    int nx = 0, ny = 0, nz = 0;
    float distance = 0;
    uint8_t block = B_AIR;
};

class World {
public:
    void reset(int width, int height, int depth);

    int width() const { return width_; }
    int height() const { return height_; }
    int depth() const { return depth_; }
    bool inBounds(int x, int y, int z) const {
        return x >= 0 && z >= 0 && y >= 0 && x < width_ && z < depth_ && y < height_;
    }

    uint8_t get(int x, int y, int z) const {
        if (y < 0) return B_BEDROCK;
        if (!inBounds(x, y, z)) return B_AIR;
        return blocks_[index(x, y, z)];
    }
    void set(int x, int y, int z, uint8_t block, bool markDirty = true);
    bool solid(int x, int y, int z) const { return isSolid(get(x, y, z)); }

    // Altura em que um ator fica de pé na coluna (primeiro ar acima do chão gerado).
    int standHeight(int x, int z) const;
    void setGroundHeight(int x, int z, int h) { if (columnInBounds(x, z)) ground_[x + z * width_] = static_cast<int16_t>(h); }
    int groundHeight(int x, int z) const { return columnInBounds(x, z) ? ground_[x + z * width_] : 0; }
    bool columnInBounds(int x, int z) const { return x >= 0 && z >= 0 && x < width_ && z < depth_; }
    // Topo visível (inclui árvores) — usado no minimapa.
    int topBlockY(int x, int z) const;

    bool raycast(const Vec3& origin, const Vec3& direction, float maxDistance, RayHit& hit) const;
    bool lineOfSight(const Vec3& from, const Vec3& to) const;
    bool boxCollides(const Vec3& minCorner, const Vec3& maxCorner) const;
    bool isWaterAt(const Vec3& p) const { return get(static_cast<int>(std::floor(p.x)), static_cast<int>(std::floor(p.y)), static_cast<int>(std::floor(p.z))) == B_WATER; }

    std::vector<Chunk>& chunks() { return chunks_; }
    const std::vector<Chunk>& chunks() const { return chunks_; }
    int chunksX() const { return chunksX_; }
    int chunksZ() const { return chunksZ_; }
    uint32_t generation() const { return generation_; }
    void markAllDirty();
    // Remalha até maxChunks chunks sujos; retorna quantos foram reconstruídos.
    int rebuildDirty(int maxChunks);

private:
    size_t index(int x, int y, int z) const {
        return (static_cast<size_t>(y) * depth_ + static_cast<size_t>(z)) * width_ + static_cast<size_t>(x);
    }
    void markDirtyAt(int x, int z);
    void buildChunk(Chunk& chunk);

    int width_ = 0, height_ = 0, depth_ = 0;
    int chunksX_ = 0, chunksZ_ = 0;
    uint32_t generation_ = 0;
    std::vector<uint8_t> blocks_;
    std::vector<int16_t> ground_;
    std::vector<Chunk> chunks_;
};

// Movimento de uma caixa (pés no centro da base) contra blocos sólidos, com subida automática de degrau.
struct BodyState {
    Vec3 position;       // centro dos pés
    Vec3 velocity;
    float halfWidth = 0.3f;
    float height = 1.8f;
    bool onGround = false;
    bool inWater = false;
    bool hitWall = false;
    float stepOffset = 0;  // degrau recém-subido (para suavizar câmera/modelo)
};

void moveBody(const World& world, BodyState& body, const Vec3& displacement, float stepHeight);

}  // namespace zz
