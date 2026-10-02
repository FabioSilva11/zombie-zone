#include "world.h"

#include <cmath>

namespace zz {

namespace {

struct FaceDef {
    int nx, ny, nz;
    int corners[4][3];
    float shade;
};

// Cantos em ordem anti-horária vista de fora; uv: v0(0,1) v1(1,1) v2(1,0) v3(0,0).
const FaceDef kFaces[6] = {
    {1, 0, 0, {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}}, 0.72f},   // +X
    {-1, 0, 0, {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, 0.72f},  // -X
    {0, 1, 0, {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}}, 1.0f},    // +Y
    {0, -1, 0, {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, 0.5f},   // -Y
    {0, 0, 1, {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}, 0.86f},   // +Z
    {0, 0, -1, {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}}, 0.86f},  // -Z
};
const uint8_t kUv[4][2] = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};
const float kAoCurve[4] = {0.46f, 0.64f, 0.82f, 1.0f};

}  // namespace

void World::reset(int width, int height, int depth) {
    ++generation_;
    width_ = width;
    height_ = height;
    depth_ = depth;
    blocks_.assign(static_cast<size_t>(width) * height * depth, B_AIR);
    ground_.assign(static_cast<size_t>(width) * depth, 0);
    chunksX_ = (width + kChunkSize - 1) / kChunkSize;
    chunksZ_ = (depth + kChunkSize - 1) / kChunkSize;
    chunks_.clear();
    chunks_.resize(static_cast<size_t>(chunksX_) * chunksZ_);
    for (int cz = 0; cz < chunksZ_; ++cz) {
        for (int cx = 0; cx < chunksX_; ++cx) {
            Chunk& c = chunks_[cz * chunksX_ + cx];
            c.cx = cx;
            c.cz = cz;
            c.dirty = true;
            c.bounds.min = {static_cast<float>(cx * kChunkSize), 0, static_cast<float>(cz * kChunkSize)};
            c.bounds.max = {static_cast<float>((cx + 1) * kChunkSize), static_cast<float>(height), static_cast<float>((cz + 1) * kChunkSize)};
        }
    }
}

void World::set(int x, int y, int z, uint8_t block, bool markDirty) {
    if (!inBounds(x, y, z)) return;
    blocks_[index(x, y, z)] = block;
    if (!markDirty) return;
    markDirtyAt(x, z);
    if (x % kChunkSize == 0) markDirtyAt(x - 1, z);
    if (x % kChunkSize == kChunkSize - 1) markDirtyAt(x + 1, z);
    if (z % kChunkSize == 0) markDirtyAt(x, z - 1);
    if (z % kChunkSize == kChunkSize - 1) markDirtyAt(x, z + 1);
}

void World::markDirtyAt(int x, int z) {
    if (!columnInBounds(x, z)) return;
    chunks_[(z / kChunkSize) * chunksX_ + (x / kChunkSize)].dirty = true;
}

void World::markAllDirty() {
    for (auto& c : chunks_) c.dirty = true;
}

int World::standHeight(int x, int z) const {
    if (!columnInBounds(x, z)) return height_;
    int y = std::max(0, groundHeight(x, z));
    while (y < height_ && solid(x, y, z)) ++y;
    return y;
}

int World::topBlockY(int x, int z) const {
    for (int y = height_ - 1; y >= 0; --y) {
        uint8_t b = get(x, y, z);
        if (b != B_AIR && blockInfo(b).shape != Shape::Cross) return y;
    }
    return -1;
}

int World::rebuildDirty(int maxChunks) {
    int rebuilt = 0;
    for (auto& c : chunks_) {
        if (!c.dirty) continue;
        buildChunk(c);
        c.dirty = false;
        ++c.version;
        if (++rebuilt >= maxChunks) break;
    }
    return rebuilt;
}

void World::buildChunk(Chunk& chunk) {
    ChunkMesh& mesh = chunk.mesh;
    mesh.opaque.clear();
    mesh.cutout.clear();
    mesh.water.clear();
    const int x0 = chunk.cx * kChunkSize, z0 = chunk.cz * kChunkSize;
    const int x1 = std::min(width_, x0 + kChunkSize), z1 = std::min(depth_, z0 + kChunkSize);

    auto occludes = [&](int x, int y, int z) { return isOpaque(get(x, y, z)) ? 1 : 0; };

    for (int y = 0; y < height_; ++y) {
        for (int z = z0; z < z1; ++z) {
            for (int x = x0; x < x1; ++x) {
                uint8_t b = blocks_[index(x, y, z)];
                if (b == B_AIR) continue;
                const BlockInfo& info = blockInfo(b);

                if (info.shape == Shape::Cross) {
                    // Duas faces diagonais, frente e verso (plantas).
                    const int diag[2][4][3] = {
                        {{0, 0, 0}, {1, 0, 1}, {1, 1, 1}, {0, 1, 0}},
                        {{1, 0, 0}, {0, 0, 1}, {0, 1, 1}, {1, 1, 0}},
                    };
                    for (const auto& d : diag) {
                        for (int side = 0; side < 2; ++side) {
                            for (int k = 0; k < 4; ++k) {
                                int i = side ? 3 - k : k;
                                VoxelVertex v{};
                                v.x = static_cast<int16_t>((x + d[i][0]) * kPosScale);
                                v.y = static_cast<int16_t>((y + d[i][1]) * kPosScale);
                                v.z = static_cast<int16_t>((z + d[i][2]) * kPosScale);
                                v.u = (i == 1 || i == 2) ? 1 : 0;
                                v.v = (i == 0 || i == 1) ? 1 : 0;
                                v.layer = info.side;
                                v.light = 230;
                                v.flags = static_cast<uint8_t>(d[i][1] ? VF_SWAY : 0);
                                mesh.cutout.push_back(v);
                            }
                        }
                    }
                    continue;
                }

                const bool water = info.shape == Shape::Liquid;
                if (info.shape != Shape::Cube && !water) continue;

                for (int f = 0; f < 6; ++f) {
                    const FaceDef& face = kFaces[f];
                    uint8_t nb = get(x + face.nx, y + face.ny, z + face.nz);
                    if (water) {
                        if (nb == B_WATER || isOpaque(nb)) continue;
                        if (face.ny < 0) continue;
                    } else {
                        if (isOpaque(nb)) continue;
                        if (y + face.ny < 0) continue;
                    }
                    uint8_t layer = face.ny > 0 ? info.top : (face.ny < 0 ? info.bottom : info.side);
                    int ao[4];
                    for (int k = 0; k < 4; ++k) {
                        const int* c = face.corners[k];
                        if (info.emissive || water) { ao[k] = 3; continue; }
                        int px = x + face.nx, py = y + face.ny, pz = z + face.nz;
                        int t1[3] = {0, 0, 0}, t2[3] = {0, 0, 0};
                        if (face.nx != 0) { t1[1] = c[1] ? 1 : -1; t2[2] = c[2] ? 1 : -1; }
                        else if (face.ny != 0) { t1[0] = c[0] ? 1 : -1; t2[2] = c[2] ? 1 : -1; }
                        else { t1[0] = c[0] ? 1 : -1; t2[1] = c[1] ? 1 : -1; }
                        int s1 = occludes(px + t1[0], py + t1[1], pz + t1[2]);
                        int s2 = occludes(px + t2[0], py + t2[1], pz + t2[2]);
                        int cc = occludes(px + t1[0] + t2[0], py + t1[1] + t2[1], pz + t1[2] + t2[2]);
                        ao[k] = (s1 && s2) ? 0 : 3 - (s1 + s2 + cc);
                    }
                    // Gira o quad quando a AO é anisotrópica para evitar costura diagonal.
                    int start = (ao[0] + ao[2] < ao[1] + ao[3]) ? 1 : 0;
                    std::vector<VoxelVertex>& target = water ? mesh.water : mesh.opaque;
                    const bool waterTop = water && face.ny > 0 && get(x, y + 1, z) != B_WATER;
                    for (int n = 0; n < 4; ++n) {
                        int k = (start + n) % 4;
                        const int* c = face.corners[k];
                        VoxelVertex v{};
                        float vy = static_cast<float>(y + c[1]);
                        if (water && c[1] == 1 && (waterTop || get(x, y + 1, z) != B_WATER)) vy -= 0.125f;
                        v.x = static_cast<int16_t>((x + c[0]) * kPosScale);
                        v.y = static_cast<int16_t>(std::lround(vy * kPosScale));
                        v.z = static_cast<int16_t>((z + c[2]) * kPosScale);
                        v.u = kUv[k][0];
                        v.v = kUv[k][1];
                        v.layer = layer;
                        float light = info.emissive ? 1.0f : face.shade * kAoCurve[ao[k]];
                        v.light = static_cast<uint8_t>(clampf(light * 255.0f, 0, 255));
                        v.flags = static_cast<uint8_t>((water ? VF_WATER : 0) | (info.emissive ? VF_EMISSIVE : 0));
                        target.push_back(v);
                    }
                }
            }
        }
    }
}

bool World::raycast(const Vec3& origin, const Vec3& direction, float maxDistance, RayHit& hit) const {
    Vec3 dir = normalize(direction);
    if (length(dir) < 0.5f) return false;
    int x = static_cast<int>(std::floor(origin.x));
    int y = static_cast<int>(std::floor(origin.y));
    int z = static_cast<int>(std::floor(origin.z));
    const int stepX = dir.x > 0 ? 1 : -1, stepY = dir.y > 0 ? 1 : -1, stepZ = dir.z > 0 ? 1 : -1;
    const float inf = 1e30f;
    float tDeltaX = std::fabs(dir.x) > 1e-6f ? std::fabs(1.0f / dir.x) : inf;
    float tDeltaY = std::fabs(dir.y) > 1e-6f ? std::fabs(1.0f / dir.y) : inf;
    float tDeltaZ = std::fabs(dir.z) > 1e-6f ? std::fabs(1.0f / dir.z) : inf;
    float tMaxX = std::fabs(dir.x) > 1e-6f ? ((stepX > 0 ? (x + 1 - origin.x) : (origin.x - x)) * tDeltaX) : inf;
    float tMaxY = std::fabs(dir.y) > 1e-6f ? ((stepY > 0 ? (y + 1 - origin.y) : (origin.y - y)) * tDeltaY) : inf;
    float tMaxZ = std::fabs(dir.z) > 1e-6f ? ((stepZ > 0 ? (z + 1 - origin.z) : (origin.z - z)) * tDeltaZ) : inf;
    int nx = 0, ny = 0, nz = 0;
    float t = 0;
    for (int i = 0; i < 1024 && t <= maxDistance; ++i) {
        uint8_t b = get(x, y, z);
        if (isSolid(b)) {
            hit = {x, y, z, nx, ny, nz, t, b};
            return true;
        }
        if (tMaxX < tMaxY && tMaxX < tMaxZ) {
            x += stepX; t = tMaxX; tMaxX += tDeltaX; nx = -stepX; ny = 0; nz = 0;
        } else if (tMaxY < tMaxZ) {
            y += stepY; t = tMaxY; tMaxY += tDeltaY; nx = 0; ny = -stepY; nz = 0;
        } else {
            z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ; nx = 0; ny = 0; nz = -stepZ;
        }
        if (y >= height_ && stepY > 0) break;
    }
    return false;
}

bool World::lineOfSight(const Vec3& from, const Vec3& to) const {
    Vec3 d = to - from;
    float dist = length(d);
    if (dist < 1e-3f) return true;
    RayHit hit;
    return !raycast(from, d, dist - 0.05f, hit);
}

bool World::boxCollides(const Vec3& mn, const Vec3& mx) const {
    int x0 = static_cast<int>(std::floor(mn.x)), x1 = static_cast<int>(std::floor(mx.x));
    int y0 = static_cast<int>(std::floor(mn.y)), y1 = static_cast<int>(std::floor(mx.y));
    int z0 = static_cast<int>(std::floor(mn.z)), z1 = static_cast<int>(std::floor(mx.z));
    for (int y = y0; y <= y1; ++y)
        for (int z = z0; z <= z1; ++z)
            for (int x = x0; x <= x1; ++x)
                if (solid(x, y, z)) return true;
    // Bordas do mundo funcionam como barreira invisível.
    return mn.x < 1.0f || mn.z < 1.0f || mx.x > width_ - 1.0f || mx.z > depth_ - 1.0f;
}

namespace {

bool overlaps(const World& world, const BodyState& b, const Vec3& p) {
    Vec3 mn(p.x - b.halfWidth, p.y, p.z - b.halfWidth);
    Vec3 mx(p.x + b.halfWidth, p.y + b.height, p.z + b.halfWidth);
    const float e = 1e-4f;
    return world.boxCollides(mn + Vec3(e, e, e), mx - Vec3(e, e, e));
}

// Move num eixo e resolve contra blocos; retorna true se colidiu.
bool moveAxis(const World& world, BodyState& b, int axis, float amount) {
    if (amount == 0) return false;
    Vec3 p = b.position;
    p[axis] += amount;
    if (!overlaps(world, b, p)) {
        b.position = p;
        return false;
    }
    // Busca binária pela maior fração livre.
    float lo = 0, hi = 1;
    for (int i = 0; i < 10; ++i) {
        float mid = (lo + hi) * 0.5f;
        Vec3 q = b.position;
        q[axis] += amount * mid;
        if (overlaps(world, b, q)) hi = mid; else lo = mid;
    }
    b.position[axis] += amount * lo;
    return true;
}

}  // namespace

void moveBody(const World& world, BodyState& body, const Vec3& displacement, float stepHeight) {
    body.hitWall = false;
    int steps = std::max(1, static_cast<int>(std::ceil(std::max(std::fabs(displacement.x), std::max(std::fabs(displacement.y), std::fabs(displacement.z))) / 0.35f)));
    Vec3 d = displacement / static_cast<float>(steps);
    bool grounded = false;
    for (int s = 0; s < steps; ++s) {
        if (moveAxis(world, body, 1, d.y)) {
            if (d.y < 0) grounded = true;
            body.velocity.y = 0;
        }
        for (int axis = 0; axis <= 2; axis += 2) {
            float amount = d[axis];
            if (amount == 0) continue;
            Vec3 before = body.position;
            if (!moveAxis(world, body, axis, amount)) continue;
            // Tenta subir um degrau de até stepHeight.
            bool stepped = false;
            if ((body.onGround || grounded) && stepHeight > 0) {
                Vec3 raised = before;
                raised.y += stepHeight;
                if (!overlaps(world, body, raised)) {
                    Vec3 target = raised;
                    target[axis] += amount;
                    if (!overlaps(world, body, target)) {
                        // Desce até apoiar no degrau.
                        BodyState probe = body;
                        probe.position = target;
                        moveAxis(world, probe, 1, -stepHeight);
                        body.stepOffset += probe.position.y - before.y;
                        body.position = probe.position;
                        stepped = true;
                    }
                }
            }
            if (!stepped) body.hitWall = true;
        }
    }
    body.onGround = grounded;
    if (!grounded && body.velocity.y <= 0) {
        // Checagem de contato com o chão (para pulo/animação).
        Vec3 probe = body.position;
        probe.y -= 0.05f;
        body.onGround = overlaps(world, body, probe);
    }
    Vec3 mid = body.position;
    mid.y += 0.6f;
    body.inWater = world.isWaterAt(mid) || world.isWaterAt(body.position + Vec3(0, 0.15f, 0));
}

}  // namespace zz
