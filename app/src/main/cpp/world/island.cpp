#include "island.h"

#include <algorithm>
#include <cmath>

#include "../core/random.h"

namespace zz {

namespace {

uint32_t packPos(int x, int y, int z) {
    return (static_cast<uint32_t>(x) << 19) | (static_cast<uint32_t>(z) << 6) | static_cast<uint32_t>(y);
}

bool validPos(int x, int y, int z) {
    return x >= 0 && z >= 0 && y >= 0 && x < kIslandSize && z < kIslandSize && y < kIslandHeight;
}

// Grava as estruturas de um ponto de interesse como um molde reaplicável em qualquer chunk.
class StampSink : public BlockSink {
public:
    explicit StampSink(PoiStamp& s) : s_(s) {}
    uint8_t get(int x, int y, int z) const override {
        if (!validPos(x, y, z)) return B_AIR;
        auto it = s_.edits.find(packPos(x, y, z));
        return it == s_.edits.end() ? B_AIR : it->second;
    }
    void set(int x, int y, int z, uint8_t block) override {
        if (!validPos(x, y, z)) return;
        s_.edits[packPos(x, y, z)] = block;
        if (s_.maxX < s_.minX) {
            s_.minX = s_.maxX = x;
            s_.minZ = s_.maxZ = z;
        } else {
            s_.minX = std::min(s_.minX, x);
            s_.maxX = std::max(s_.maxX, x);
            s_.minZ = std::min(s_.minZ, z);
            s_.maxZ = std::max(s_.maxZ, z);
        }
    }

private:
    PoiStamp& s_;
};

// Escreve somente dentro do chunk em geração (árvores vizinhas são recortadas na borda).
class ChunkSink : public BlockSink {
public:
    ChunkSink(Chunk& c, int height) : c_(c), h_(height), x0_(c.cx * kChunkSize), z0_(c.cz * kChunkSize) {}
    uint8_t get(int x, int y, int z) const override {
        if (!inside(x, y, z)) return B_AIR;
        return c_.blocks[index(x, y, z)];
    }
    void set(int x, int y, int z, uint8_t block) override {
        if (!inside(x, y, z)) return;
        c_.blocks[index(x, y, z)] = block;
    }
    bool inside(int x, int y, int z) const {
        return x >= x0_ && z >= z0_ && x < x0_ + kChunkSize && z < z0_ + kChunkSize && y >= 0 && y < h_;
    }

private:
    size_t index(int x, int y, int z) const {
        return (static_cast<size_t>(y) << 8) | (static_cast<size_t>(z - z0_) << 4) | static_cast<size_t>(x - x0_);
    }
    Chunk& c_;
    int h_, x0_, z0_;
};

int floorDiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

uint32_t mixColor(uint32_t a, uint32_t b, float t) {
    t = clampf(t, 0, 1);
    auto ch = [&](int s) {
        float va = static_cast<float>((a >> s) & 0xFF), vb = static_cast<float>((b >> s) & 0xFF);
        return static_cast<uint32_t>(clampf(lerpf(va, vb, t), 0, 255)) << s;
    };
    return ch(16) | ch(8) | ch(0);
}

uint32_t shadeColor(uint32_t c, float f) {
    auto ch = [&](int s) { return static_cast<uint32_t>(clampf(((c >> s) & 0xFF) * f, 0, 255)) << s; };
    return ch(16) | ch(8) | ch(0);
}

}  // namespace

IslandGenerator::IslandGenerator(const Theme& theme, int stage, uint32_t seed, bool objectives)
    : primary_(theme), stage_(std::max(1, stage)), seed_(seed ? seed : 1u), objectives_(objectives) {
    // Bioma secundário: outra zona, escolhida pela semente.
    int other = (theme.id + 1 + static_cast<int>(seed_ % 3u)) % 4;
    secondary_ = themeForStage(other * 5 + 1);
    cache_.resize(static_cast<size_t>(kIslandCells) * kIslandCells);
    cached_.assign(static_cast<size_t>(kIslandCells) * kIslandCells, 0);
    chooseLayout();
}

float IslandGenerator::landMask(float x, float z) const {
    float dx = x - kIslandCenter, dz = z - kIslandCenter;
    float d = std::sqrt(dx * dx + dz * dz);
    float wobble = (fbm(x / 700.0f, z / 700.0f, seed_ + 71, 3) - 0.5f) * 900.0f +
                   (fbm(x / 150.0f, z / 150.0f, seed_ + 72, 2) - 0.5f) * 160.0f;
    return 1.0f - smoothstepf(kIslandRadius - 280.0f, kIslandRadius + 120.0f, d + wobble);
}

float IslandGenerator::secondaryWeight(float x, float z) const {
    return smoothstepf(0.575f, 0.64f, fbm(x / 900.0f, z / 900.0f, seed_ + 77, 3));
}

float IslandGenerator::riverMask(float x, float z) const {
    float n = fbm(x / 760.0f, z / 760.0f, seed_ + 200, 2);
    float d = std::fabs(n - 0.5f);
    return 1.0f - smoothstepf(0.004f, 0.0085f, d);
}

float IslandGenerator::naturalHeight(float x, float z) const {
    float land = landMask(x, z);
    float sw = secondaryWeight(x, z);
    float hill = lerpf(primary_.hilliness, secondary_.hilliness, sw);
    float n = fbm(x / 150.0f, z / 150.0f, seed_, 5);
    float detail = fbm(x / 24.0f, z / 24.0f, seed_ + 31, 2);
    float hills = (n - 0.5f) * 2.0f * 10.0f * hill;
    if (hills < 0) hills *= 0.55f;  // vales mais rasos: menos lagoas soltas
    hills += (detail - 0.5f) * 3.0f;
    float ridge = fbm(x / 420.0f, z / 420.0f, seed_ + 5, 4);
    float m = smoothstepf(0.55f, 0.8f, ridge);
    float h = static_cast<float>(sea_) + 6.5f + hills + m * m * 32.0f * hill;
    // Deserto: dunas mais baixas e suaves (sem terraços — paredes de areia mais altas que o jogador).
    float desert = (primary_.id == 1 ? 1.0f - sw : 0.0f) + (secondary_.id == 1 ? sw : 0.0f);
    if (desert > 0.01f) h = lerpf(h, static_cast<float>(sea_) + 6.5f + (h - sea_ - 6.5f) * 0.7f, desert);
    if (h > 40.0f) h = 40.0f + (h - 40.0f) * 0.55f;
    h = lerpf(static_cast<float>(sea_) - 12.0f, h, land);
    // Rios: margens rebaixadas e leito abaixo do mar.
    if (land > 0.55f) {
        float rn = fbm(x / 760.0f, z / 760.0f, seed_ + 200, 2);
        float d = std::fabs(rn - 0.5f);
        float bank = 1.0f - smoothstepf(0.0085f, 0.024f, d);
        float bed = 1.0f - smoothstepf(0.004f, 0.0085f, d);
        if (bank > 0) h = std::min(h, lerpf(h, static_cast<float>(sea_) + 0.6f, bank));
        if (bed > 0) h = lerpf(h, static_cast<float>(sea_) - 2.6f, bed);
    }
    return h;
}

const Theme& IslandGenerator::biomeTheme(float sw, int x, int z) const {
    float j = (hashFloat(x, z, seed_ + 5) - 0.5f) * 0.14f;
    return sw + j > 0.5f ? secondary_ : primary_;
}

IslandPoi IslandGenerator::basePoi(int cx, int cz) const {
    IslandPoi p;
    p.cellX = cx;
    p.cellZ = cz;
    if (cx < 0 || cz < 0 || cx >= kIslandCells || cz >= kIslandCells) return p;
    p.id = cz * kIslandCells + cx;
    uint32_t h = hash2(cx, cz, seed_ ^ 0x9017u);
    float px = cx * kIslandCell + 24.0f + hashFloat(cx, cz, seed_ + 1) * 80.0f;
    float pz = cz * kIslandCell + 24.0f + hashFloat(cx, cz, seed_ + 2) * 80.0f;
    p.center = {std::floor(px) + 0.5f, 0.0f, std::floor(pz) + 0.5f};
    if (landMask(px, pz) < 0.9f || riverMask(px, pz) > 0.0f) return p;
    float nh = naturalHeight(px, pz);
    if (nh < sea_ + 1.6f || nh > sea_ + 24.0f) return p;
    p.center.y = clampf(std::round(nh), sea_ + 2.0f, sea_ + 20.0f);
    p.exists = ((h >> 8) & 0xFFFF) < static_cast<uint32_t>(0.66f * 65536.0f);
    // Quase metade dos locais são construções: casas, vilarejos, igrejas, postos, prédios e celeiros.
    int roll = static_cast<int>((h >> 24) % 100u);
    if (roll < 10) p.kind = K_FIRE;
    else if (roll < 19) p.kind = K_RUINS;
    else if (roll < 26) p.kind = K_GRAVEYARD;
    else if (roll < 34) p.kind = K_DEPOT;
    else if (roll < 40) p.kind = K_FARM;
    else if (roll < 49) p.kind = K_TOWER;
    else if (roll < 56) p.kind = K_OUTPOST;
    else if (roll < 71) p.kind = K_HOUSE;
    else if (roll < 79) p.kind = K_VILLAGE;
    else if (roll < 84) p.kind = K_CHURCH;
    else if (roll < 91) p.kind = K_GAS;
    else if (roll < 96) p.kind = K_APARTMENT;
    else p.kind = K_BARN;
    p.radius = 6.5f + hashFloat(cx, cz, seed_ + 3) * 2.0f;
    if (p.kind == K_TOWER || p.kind == K_OUTPOST) p.radius = std::max(p.radius, 7.5f);
    if (isBuildingKind(p.kind)) p.radius = buildingRadius(p.kind);
    return p;
}

const IslandPoi& IslandGenerator::poi(int cx, int cz) {
    static const IslandPoi kNone;
    if (cx < 0 || cz < 0 || cx >= kIslandCells || cz >= kIslandCells) return kNone;
    size_t id = static_cast<size_t>(cz) * kIslandCells + cx;
    if (cached_[id]) return cache_[id];
    IslandPoi p = basePoi(cx, cz);
    auto role = roles_.find(static_cast<int>(id));
    if (role != roles_.end()) {
        p.exists = true;
        p.role = role->second;
        if (p.role == ROLE_START) { p.kind = K_FIRE; p.radius = 7.0f; }
        if (p.role == ROLE_NEST) {
            p.kind = K_NEST;
            p.radius = 10.0f;
            auto idx = objectiveIndex_.find(static_cast<int>(id));
            p.objective = idx != objectiveIndex_.end() ? idx->second : 0;
        }
        if (p.role == ROLE_PORTAL) { p.kind = K_PORTAL; p.radius = 8.5f; }
    }
    cache_[id] = p;
    cached_[id] = 1;
    return cache_[id];
}

void IslandGenerator::chooseLayout() {
    Rng rng(seed_ ^ 0x51A27u);
    auto valid = [&](const IslandPoi& p) { return p.id >= 0 && p.center.y > 0.0f; };
    auto findNear = [&](float x, float z, const std::vector<int>& used) -> int {
        int cx = static_cast<int>(x) / kIslandCell, cz = static_cast<int>(z) / kIslandCell;
        for (int ring = 0; ring < 16; ++ring)
            for (int dz = -ring; dz <= ring; ++dz)
                for (int dx = -ring; dx <= ring; ++dx) {
                    if (std::max(std::abs(dx), std::abs(dz)) != ring) continue;
                    IslandPoi b = basePoi(cx + dx, cz + dz);
                    if (!valid(b) || std::find(used.begin(), used.end(), b.id) != used.end()) continue;
                    return b.id;
                }
        return -1;
    };

    // Acampamento inicial perto do centro da ilha.
    std::vector<int> used;
    float a0 = rng.range(0, kTwoPi), d0 = rng.range(0, 650);
    int startId = findNear(kIslandCenter + std::cos(a0) * d0, kIslandCenter + std::sin(a0) * d0, used);
    if (startId < 0) startId = (kIslandCells / 2) * kIslandCells + kIslandCells / 2;
    used.push_back(startId);
    IslandPoi sp = basePoi(startId % kIslandCells, startId / kIslandCells);
    if (sp.center.y <= 0) sp.center.y = static_cast<float>(sea_ + 4);
    roles_[startId] = ROLE_START;
    Vec2 s(sp.center.x, sp.center.z);

    // Ninhos cada vez mais longe; distâncias crescem um pouco nas fases altas.
    float scale = 1.0f + std::min(0.6f, std::max(0, stage_ - 8) * 0.03f);
    const float ranges[kNestCount][2] = {{220, 420}, {400, 660}, {600, 920}};
    std::vector<Vec2> dirs;
    auto angleOk = [&](const Vec2& v, float minAngle) {
        float l = v.length();
        for (const Vec2& d : dirs) {
            float c = (v.x * d.x + v.y * d.y) / std::max(1e-3f, l * d.length());
            if (std::acos(clampf(c, -1, 1)) < minAngle) return false;
        }
        return true;
    };
    auto pick = [&](float r0, float r1, float minAngle) -> int {
        for (int attempt = 0; attempt < 500; ++attempt) {
            float relax = attempt > 300 ? 0.5f : 1.0f;
            float ang = rng.range(0, kTwoPi), dist = rng.range(r0, r1);
            Vec2 t(s.x + std::cos(ang) * dist, s.y + std::sin(ang) * dist);
            if (t.x < 0 || t.y < 0 || t.x >= kIslandSize || t.y >= kIslandSize) continue;
            IslandPoi b = basePoi(static_cast<int>(t.x) / kIslandCell, static_cast<int>(t.y) / kIslandCell);
            if (!valid(b) || std::find(used.begin(), used.end(), b.id) != used.end()) continue;
            Vec2 v(b.center.x - s.x, b.center.z - s.y);
            if (v.length() < r0 * 0.8f || !angleOk(v, minAngle * relax)) continue;
            return b.id;
        }
        float ang = rng.range(0, kTwoPi), dist = (r0 + r1) * 0.5f;
        return findNear(s.x + std::cos(ang) * dist, s.y + std::sin(ang) * dist, used);
    };
    for (int i = 0; i < (objectives_ ? kNestCount : 0); ++i) {
        int id = pick(ranges[i][0] * scale, ranges[i][1] * scale, 0.9f);
        if (id < 0) continue;
        used.push_back(id);
        nests_.push_back(id);
        IslandPoi b = basePoi(id % kIslandCells, id / kIslandCells);
        dirs.push_back(Vec2(b.center.x - s.x, b.center.z - s.y));
    }
    portal_ = objectives_ ? pick(350 * scale, 720 * scale, 0.7f) : -1;
    if (portal_ >= 0) {
        used.push_back(portal_);
        roles_[portal_] = ROLE_PORTAL;
    }

    // Ordena os ninhos pelo caminho mais curto a partir do início (vizinho mais próximo).
    std::vector<int> ordered;
    Vec2 cur = s;
    std::vector<int> left = nests_;
    route_ = 0;
    while (!left.empty()) {
        size_t best = 0;
        float bestD = 1e9f;
        for (size_t i = 0; i < left.size(); ++i) {
            IslandPoi b = basePoi(left[i] % kIslandCells, left[i] / kIslandCells);
            float d = (Vec2(b.center.x, b.center.z) - cur).length();
            if (d < bestD) { bestD = d; best = i; }
        }
        IslandPoi b = basePoi(left[best] % kIslandCells, left[best] / kIslandCells);
        route_ += bestD;
        cur = Vec2(b.center.x, b.center.z);
        ordered.push_back(left[best]);
        left.erase(left.begin() + static_cast<long>(best));
    }
    nests_ = ordered;
    for (size_t i = 0; i < nests_.size(); ++i) {
        roles_[nests_[i]] = ROLE_NEST;
        objectiveIndex_[nests_[i]] = static_cast<int>(i);
    }
    if (portal_ >= 0) {
        IslandPoi pp = basePoi(portal_ % kIslandCells, portal_ / kIslandCells);
        route_ += (Vec2(pp.center.x, pp.center.z) - cur).length();
    }

    start_ = sp.center + Vec3(0, 0, 2.5f);
    if (!nests_.empty()) {
        IslandPoi first = basePoi(nests_[0] % kIslandCells, nests_[0] / kIslandCells);
        startYaw_ = yawFromDirection(first.center.x - start_.x, first.center.z - start_.z);
    }
}

bool IslandGenerator::pathLink(int ax, int az, int bx, int bz) {
    const IslandPoi& a = poi(ax, az);
    const IslandPoi& b = poi(bx, bz);
    if (!a.exists || !b.exists) return false;
    int lo = std::min(a.id, b.id), hi = std::max(a.id, b.id);
    if (hashFloat(lo, hi, seed_ + 33) > 0.72f && a.role == ROLE_NONE && b.role == ROLE_NONE) return false;
    return distXZ(a.center, b.center) < 245.0f;
}

float IslandGenerator::pathDistance(int x, int z, int cx, int cz) {
    const IslandPoi& a = poi(cx, cz);
    if (!a.exists) return 1e9f;
    static const int kNb[4][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    float best = 1e9f;
    for (const auto& d : kNb) {
        if (!pathLink(cx, cz, cx + d[0], cz + d[1])) continue;
        const IslandPoi& b = poi(cx + d[0], cz + d[1]);
        // Orientação canônica: a curva sai idêntica vista das duas células.
        const IslandPoi& p0 = a.id < b.id ? a : b;
        const IslandPoi& p1 = a.id < b.id ? b : a;
        float ax = p0.center.x, az = p0.center.z;
        float dx = p1.center.x - ax, dz = p1.center.z - az;
        float len = std::sqrt(dx * dx + dz * dz);
        if (len < 1.0f) continue;
        float ux = dx / len, uz = dz / len;
        float px = x + 0.5f - ax, pz = z + 0.5f - az;
        float t = clampf((px * ux + pz * uz) / len, 0.0f, 1.0f);
        uint32_t hh = hash2(p0.id, p1.id, seed_ + 34);
        float amp = 4.0f + static_cast<float>(hh & 255u) / 255.0f * 7.0f;
        float waves = 1.0f + std::floor(len / 70.0f);
        float off = std::sin(t * kPi * waves) * amp * std::sin(t * kPi);
        float cxp = ux * t * len - uz * off, czp = uz * t * len + ux * off;
        float ddx = px - cxp, ddz = pz - czp;
        best = std::min(best, std::sqrt(ddx * ddx + ddz * ddz));
    }
    return best;
}

float IslandGenerator::surfaceHeight(int x, int z, const IslandPoi& p) const {
    float h = naturalHeight(x + 0.5f, z + 0.5f);
    if (p.exists) {
        float d = std::sqrt((x + 0.5f - p.center.x) * (x + 0.5f - p.center.x) + (z + 0.5f - p.center.z) * (z + 0.5f - p.center.z));
        if (d < p.radius + 8.0f) h = lerpf(p.center.y, h, smoothstepf(p.radius, p.radius + 8.0f, d));
    }
    return h;
}

ColumnInfo IslandGenerator::column(int x, int z) {
    ColumnInfo c;
    int cx = x / kIslandCell, cz = z / kIslandCell;
    const IslandPoi& p = poi(cx, cz);
    float fx = x + 0.5f, fz = z + 0.5f;
    c.secondary = secondaryWeight(fx, fz);
    c.biome = &biomeTheme(c.secondary, x, z);
    c.river = riverMask(fx, fz);
    float h = surfaceHeight(x, z, p);
    float poiDist = 1e9f;
    if (p.exists) {
        poiDist = std::sqrt((fx - p.center.x) * (fx - p.center.x) + (fz - p.center.z) * (fz - p.center.z));
        c.nearPoi = poiDist < p.radius + 3.5f;
    }
    float pd = pathDistance(x, z, cx, cz);
    c.nearPath = pd < 3.5f;
    c.top = std::max(2, std::min(kIslandHeight - 14, static_cast<int>(std::lround(h))));
    c.water = c.top <= sea_;
    if (pd < 1.45f && !(p.exists && poiDist < p.radius - 0.5f)) {
        c.path = true;
        c.bridge = c.water;
    }
    c.forest = smoothstepf(0.38f, 0.66f, fbm(fx / 220.0f, fz / 220.0f, seed_ + 9, 3));
    return c;
}

namespace {

uint8_t surfaceBlock(const ColumnInfo& c, int x, int z, int sea, uint32_t seed) {
    const Theme& t = *c.biome;
    int top = c.top;
    if (c.path && !c.water) return B_PATH;
    if (top - 1 <= sea + 1) {
        if (top - 1 < sea - 2) return (t.id == 0 || t.id == 1) ? B_SAND : B_GRAVEL;
        return t.beach;
    }
    if (t.id != 1 && top > 41) return B_SNOW;
    if (t.id != 1 && top > 37) return hashFloat(x, z, seed + 3) < 0.3f ? B_GRAVEL : B_STONE;
    if (t.id == 1 && top > 34) return B_TERRACOTTA;
    if (t.id == 3 && hashFloat(x, z, seed + 4) < 0.012f) return B_MAGMA;
    return t.surface;
}

}  // namespace

void IslandGenerator::generate(Chunk& chunk, int height) {
    const int x0 = chunk.cx * kChunkSize, z0 = chunk.cz * kChunkSize;
    ChunkSink sink(chunk, height);
    ColumnInfo cols[kChunkSize * kChunkSize];
    auto idx = [](int lx, int y, int lz) { return (static_cast<size_t>(y) << 8) | (static_cast<size_t>(lz) << 4) | static_cast<size_t>(lx); };

    // 1) Colunas de terreno.
    for (int lz = 0; lz < kChunkSize; ++lz) {
        for (int lx = 0; lx < kChunkSize; ++lx) {
            const int x = x0 + lx, z = z0 + lz;
            ColumnInfo& c = cols[lz * kChunkSize + lx];
            c = column(x, z);
            const Theme& t = *c.biome;
            const int top = c.top;
            const uint8_t surf = surfaceBlock(c, x, z, sea_, seed_);
            const uint8_t fill = (surf == B_SAND || surf == B_GRAVEL) ? surf : t.filler;
            for (int y = 0; y < top && y < height; ++y) {
                uint8_t b;
                if (y == 0) b = B_BEDROCK;
                else if (y < top - 4) {
                    b = t.deep;
                    if (b == B_STONE) {
                        float o = hashFloat(x * 31 + y, z * 17 - y, seed_ + 11);
                        if (o < 0.02f) b = o < 0.012f ? B_COAL_ORE : B_IRON_ORE;
                    }
                } else if (y < top - 1) b = c.bridge ? t.deep : fill;
                else b = surf;
                chunk.blocks[idx(lx, y, lz)] = b;
            }
            int ground = top;
            if (c.water) {
                for (int y = top; y <= sea_; ++y) chunk.blocks[idx(lx, y, lz)] = B_WATER;
                if (c.bridge) {
                    chunk.blocks[idx(lx, sea_, lz)] = B_PLANKS;
                    ground = sea_ + 1;
                } else if (t.id == 2) {
                    chunk.blocks[idx(lx, sea_, lz)] = B_ICE;
                    ground = sea_ + 1;
                }
            }
            chunk.ground[lz * kChunkSize + lx] = static_cast<int16_t>(ground);
        }
    }

    // 2) Árvores: uma por célula 5x5 no máximo, determinística (copas cruzam a borda do chunk).
    const int tc = 5;
    for (int tz = floorDiv(z0 - 6, tc); tz <= floorDiv(z0 + kChunkSize + 5, tc); ++tz) {
        for (int tx = floorDiv(x0 - 6, tc); tx <= floorDiv(x0 + kChunkSize + 5, tc); ++tx) {
            uint32_t h = hash2(tx, tz, seed_ + 404);
            int x = tx * tc + static_cast<int>((h >> 4) % tc), z = tz * tc + static_cast<int>((h >> 12) % tc);
            if (x < x0 - 5 || x > x0 + kChunkSize + 4 || z < z0 - 5 || z > z0 + kChunkSize + 4) continue;
            if (x < 2 || z < 2 || x >= kIslandSize - 2 || z >= kIslandSize - 2) continue;
            float roll = static_cast<float>((h >> 20) & 4095u) / 4096.0f;
            if (roll > 0.9f) continue;
            bool local = x >= x0 && z >= z0 && x < x0 + kChunkSize && z < z0 + kChunkSize;
            ColumnInfo c = local ? cols[(z - z0) * kChunkSize + (x - x0)] : column(x, z);
            const Theme& t = *c.biome;
            float p = clampf(t.treeDensity * 25.0f * (0.12f + 1.7f * c.forest * c.forest), 0.0f, 0.85f);
            if (roll >= p) continue;
            if (c.water || c.top <= sea_ + 2 || c.top >= height - 16 || c.nearPath || c.nearPoi || c.river > 0.01f) continue;
            if (t.id != 1 && c.top > 37) continue;
            Rng rng(hash2(x, z, seed_ + 505));
            TreeKind kind = pickTreeKind(t, x, z, seed_, c.top <= sea_ + 4);
            placeTree(sink, kind, x, c.top, z, rng);
        }
    }

    // 3) Plantas (capim alto em campos que balançam com o vento, flores, arbustos).
    for (int lz = 0; lz < kChunkSize; ++lz) {
        for (int lx = 0; lx < kChunkSize; ++lx) {
            const ColumnInfo& c = cols[lz * kChunkSize + lx];
            if (c.water || c.path || c.nearPoi || c.top <= sea_ + 2 || c.top >= height - 2) continue;
            const int x = x0 + lx, z = z0 + lz;
            const Theme& t = *c.biome;
            if (chunk.blocks[idx(lx, c.top - 1, lz)] != t.surface || chunk.blocks[idx(lx, c.top, lz)] != B_AIR) continue;
            float r = hashFloat(x, z, seed_ + 606);
            uint8_t plant = B_AIR;
            if (t.id == 0) {
                float meadow = smoothstepf(0.42f, 0.6f, fbm(x / 30.0f, z / 30.0f, seed_ + 19, 2));
                float grass = 0.12f + 0.42f * meadow;
                if (r < grass) plant = B_TALL_GRASS;
                else if (r < grass + 0.02f + 0.03f * (1.0f - c.forest)) plant = hashFloat(x, z, seed_ + 607) < 0.5f ? B_FLOWER_RED : B_FLOWER_YELLOW;
            } else if (t.id == 2) {
                if (r < 0.05f) plant = B_TALL_GRASS;
            } else if (t.id == 1) {
                if (r < 0.022f) plant = B_DEAD_BUSH;
            } else {
                if (r < 0.03f) plant = hashFloat(x, z, seed_ + 608) < 0.4f ? B_MUSHROOM_RED : B_DEAD_BUSH;
            }
            if (plant != B_AIR) chunk.blocks[idx(lx, c.top, lz)] = plant;
        }
    }

    // 4) Pedregulhos espalhados.
    if (hashFloat(chunk.cx, chunk.cz, seed_ + 707) < 0.22f) {
        int lx = 1 + static_cast<int>(hashFloat(chunk.cx, chunk.cz, seed_ + 708) * 13);
        int lz = 1 + static_cast<int>(hashFloat(chunk.cx, chunk.cz, seed_ + 709) * 13);
        const ColumnInfo& c = cols[lz * kChunkSize + lx];
        if (!c.water && !c.nearPath && !c.nearPoi && c.top > sea_ + 1 && c.top < height - 4) {
            const Theme& t = *c.biome;
            uint8_t rock = t.id == 1 ? B_SANDSTONE : (t.id == 3 ? B_NETHERRACK : B_COBBLE);
            Rng rng(hash2(chunk.cx, chunk.cz, seed_ + 710));
            for (int dz = 0; dz < 2; ++dz)
                for (int dx = 0; dx < 2; ++dx)
                    if (rng.chance(0.85f)) {
                        int y = cols[(lz + dz) * kChunkSize + lx + dx].top;
                        sink.set(x0 + lx + dx, y, z0 + lz + dz, rock);
                        if (rng.chance(0.35f)) sink.set(x0 + lx + dx, y + 1, z0 + lz + dz, B_STONE);
                    }
        }
    }

    // 5) Ponto de interesse da célula (cada chunk pertence a uma única célula).
    const IslandPoi& p = poi(x0 / kIslandCell, z0 / kIslandCell);
    if (p.exists) {
        const PoiStamp& s = stamp(p);
        if (!(s.maxX < x0 || s.minX >= x0 + kChunkSize || s.maxZ < z0 || s.minZ >= z0 + kChunkSize)) {
            for (const auto& e : s.edits) {
                int x = static_cast<int>(e.first >> 19), z = static_cast<int>((e.first >> 6) & 0x1FFFu), y = static_cast<int>(e.first & 63u);
                sink.set(x, y, z, e.second);
            }
        }
        auto inChunk = [&](const Vec3& v) { return v.x >= x0 && v.z >= z0 && v.x < x0 + kChunkSize && v.z < z0 + kChunkSize; };
        for (const Vec3& l : s.decor.lights)
            if (inChunk(l)) chunk.lights.push_back(l);
        for (const Vec3& f : s.decor.campfires)
            if (inChunk(f)) chunk.campfires.push_back(f);
    }
}

const PoiStamp& IslandGenerator::stamp(const IslandPoi& p) {
    auto it = stamps_.find(p.id);
    if (it != stamps_.end()) return it->second;
    PoiStamp& s = stamps_[p.id];
    StampSink sink(s);
    Rng rng(hash2(p.cellX, p.cellZ, seed_ + 811));
    Camp camp{p.center, p.radius, p.role == ROLE_START, p.role == ROLE_PORTAL, p.kind};
    const Theme& t = biomeTheme(secondaryWeight(p.center.x, p.center.z), static_cast<int>(p.center.x), static_cast<int>(p.center.z));
    if (p.role == ROLE_NEST) {
        buildNest(sink, camp, rng, s.decor, s.nestCore);
    } else if (p.role == ROLE_PORTAL) {
        Camp ruins = camp;
        ruins.kind = K_RUINS;
        decorateCamp(sink, ruins, t, rng, s.decor);
        buildPortal(sink, camp, start_, s.portalBase, s.portalYaw, s.decor);
    } else {
        decorateCamp(sink, camp, t, rng, s.decor);
    }
    return s;
}

void IslandGenerator::poisNear(const Vec3& pos, float radius, std::vector<const IslandPoi*>& out) {
    out.clear();
    int c0x = static_cast<int>((pos.x - radius) / kIslandCell), c1x = static_cast<int>((pos.x + radius) / kIslandCell);
    int c0z = static_cast<int>((pos.z - radius) / kIslandCell), c1z = static_cast<int>((pos.z + radius) / kIslandCell);
    for (int cz = c0z; cz <= c1z; ++cz)
        for (int cx = c0x; cx <= c1x; ++cx) {
            const IslandPoi& p = poi(cx, cz);
            if (p.exists && distXZ(p.center, pos) <= radius) out.push_back(&p);
        }
}

uint32_t IslandGenerator::mapColor(int x, int z) {
    ColumnInfo c = column(x, z);
    const int cx = x / kIslandCell, cz = z / kIslandCell;
    const IslandPoi& p = poi(cx, cz);
    uint32_t col;
    if (c.bridge) col = 0xB48A52;
    else if (c.water) {
        if (c.biome->id == 2 && c.top >= sea_ - 1) col = 0x9CC4F2;
        else col = mixColor(0x4A8AE8, 0x1A3A88, (sea_ - c.top) / 12.0f);
    } else if (c.path) col = 0xC9A46A;
    else {
        col = blockInfo(surfaceBlock(c, x, z, sea_, seed_)).mapColor;
        const Theme& t = *c.biome;
        if (t.tree != TreeKind::Cactus && c.top <= 37) col = mixColor(col, t.tree == TreeKind::Spruce ? 0x23482E : (t.tree == TreeKind::Dead ? 0x4A4038 : 0x2F6A22), c.forest * 0.55f);
        float slope = naturalHeight(x + 0.5f, z + 0.5f) - naturalHeight(x - 2.5f, z - 2.5f);
        col = shadeColor(col, clampf(0.84f + (c.top - sea_) * 0.011f + clampf(slope * 0.06f, -0.18f, 0.18f), 0.5f, 1.3f));
    }
    if (p.exists && distXZ(p.center, Vec3(x + 0.5f, 0, z + 0.5f)) < p.radius) {
        col = p.role == ROLE_NEST ? 0x8E2A6A : (p.role == ROLE_PORTAL ? 0x6A3FB0 : (isBuildingKind(p.kind) ? 0xB0644A : 0x9A7A4E));
    }
    return col;
}

}  // namespace zz
