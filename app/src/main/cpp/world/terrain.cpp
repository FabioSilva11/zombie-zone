#include "terrain.h"

#include <algorithm>
#include <cmath>

#include "../core/random.h"

namespace zz {

namespace {

enum Reserve : uint8_t { R_NONE = 0, R_PATH = 1, R_CAMP = 2, R_TREE = 3, R_RIVER = 4 };

// Adaptador: estruturas escrevem direto no mundo finito (sem marcar chunks sujos).
class WorldSink : public BlockSink {
public:
    explicit WorldSink(World& w) : w_(w) {}
    uint8_t get(int x, int y, int z) const override { return w_.get(x, y, z); }
    void set(int x, int y, int z, uint8_t block) override { w_.set(x, y, z, block, false); }

private:
    World& w_;
};

void absorb(WorldLayout& layout, const DecorOut& out) {
    layout.lights.insert(layout.lights.end(), out.lights.begin(), out.lights.end());
    layout.campfires.insert(layout.campfires.end(), out.campfires.begin(), out.campfires.end());
}

struct HeightField {
    int w = 0, d = 0;
    std::vector<float> h;
    std::vector<float> natural;  // relevo antes de trilhas/acampamentos (para detectar pontes)
    std::vector<uint8_t> reserve;
    float& at(int x, int z) { return h[static_cast<size_t>(z) * w + x]; }
    float get(int x, int z) const {
        x = std::max(0, std::min(w - 1, x));
        z = std::max(0, std::min(d - 1, z));
        return h[static_cast<size_t>(z) * w + x];
    }
    float naturalAt(int x, int z) const { return natural.empty() ? get(x, z) : natural[static_cast<size_t>(z) * w + x]; }
    uint8_t& res(int x, int z) { return reserve[static_cast<size_t>(z) * w + x]; }
    bool inside(int x, int z) const { return x >= 0 && z >= 0 && x < w && z < d; }
};

struct Blob {
    float x, z, r;
};

// Forma geral do mapa (ilha elíptica, ilhas, vale...) sorteada por partida.
struct MapShape {
    MapStyle style = MapStyle::Island;
    float cx = 0, cz = 0, rx = 1, rz = 1, angle = 0;
    std::vector<Blob> blobs;
    float nx = 1, nz = 0, offset = 0;
    float terrace = 3;
};

MapShape makeShape(MapStyle style, int size, Rng& rng) {
    MapShape s;
    s.style = style;
    s.cx = size * rng.range(0.44f, 0.56f);
    s.cz = size * rng.range(0.44f, 0.56f);
    s.rx = size * rng.range(0.37f, 0.47f);
    s.rz = size * rng.range(0.37f, 0.47f);
    s.angle = rng.range(0, kPi);
    if (style == MapStyle::Archipelago) {
        int n = rng.rangeInt(4, 6);
        s.blobs.push_back({size * 0.5f + rng.range(-8, 8), size * 0.5f + rng.range(-8, 8), size * rng.range(0.19f, 0.24f)});
        float base = rng.range(0, kTwoPi);
        for (int i = 1; i < n; ++i) {
            float a = base + i * kTwoPi / (n - 1) + rng.range(-0.3f, 0.3f);
            float dist = size * rng.range(0.27f, 0.34f);
            float r = size * rng.range(0.1f, 0.15f);
            float bx = clampf(size * 0.5f + std::cos(a) * dist, r + 10, size - r - 10);
            float bz = clampf(size * 0.5f + std::sin(a) * dist, r + 10, size - r - 10);
            s.blobs.push_back({bx, bz, r});
        }
    }
    float a = rng.range(0, kPi);
    s.nx = std::cos(a);
    s.nz = std::sin(a);
    s.offset = rng.range(-0.08f, 0.08f) * size;
    s.terrace = rng.range(2.0f, 2.8f);  // degraus escaláveis
    return s;
}

float landMask(const MapShape& s, float x, float z, int size, uint32_t seed) {
    float wobble = (fbm(x / 26.0f, z / 26.0f, seed + 71, 2) - 0.5f) * 0.22f;
    float mask = 0;
    if (s.style == MapStyle::Archipelago) {
        for (const Blob& b : s.blobs) {
            float d = std::sqrt((x - b.x) * (x - b.x) + (z - b.z) * (z - b.z)) / b.r + wobble;
            mask = std::max(mask, 1.0f - smoothstepf(0.62f, 1.0f, d));
        }
    } else {
        float dx = x - s.cx, dz = z - s.cz;
        float c = std::cos(s.angle), sn = std::sin(s.angle);
        float u = (dx * c + dz * sn) / s.rx, v = (-dx * sn + dz * c) / s.rz;
        float r = std::sqrt(u * u + v * v) + wobble;
        mask = 1.0f - smoothstepf(0.78f, 1.02f, r);
    }
    float edge = std::min(std::min(x, z), std::min(size - x, size - z));
    return mask * smoothstepf(4.0f, 14.0f, edge);
}

float naturalHeight(const MapShape& s, float x, float z, int size, const Theme& theme, uint32_t seed, int sea) {
    float n = fbm(x / 46.0f, z / 46.0f, seed, 4);
    float detail = fbm(x / 13.0f, z / 13.0f, seed + 31, 2);
    float hills = (n - 0.5f) * 2.0f * 7.0f * theme.hilliness + (detail - 0.5f) * 3.0f;
    float ridge = fbm(x / 64.0f, z / 64.0f, seed + 5, 3);
    float mountains = ridge > 0.6f ? (ridge - 0.6f) * 55.0f * theme.hilliness : 0.0f;
    switch (s.style) {
        case MapStyle::Valley: {
            float dist = std::fabs((x - size * 0.5f) * s.nx + (z - size * 0.5f) * s.nz - s.offset) / (size * 0.5f);
            hills *= 0.55f;
            mountains = smoothstepf(0.28f, 0.75f, dist) * 24.0f * theme.hilliness * (0.7f + ridge * 0.6f);
            break;
        }
        case MapStyle::Mesas: {
            float raw = hills * 1.3f + mountains * 0.8f + (ridge - 0.5f) * 10.0f;
            hills = std::floor(raw / s.terrace) * s.terrace;
            mountains = 0;
            break;
        }
        case MapStyle::Crater: {
            float dx = (x - s.cx) / s.rx, dz = (z - s.cz) / s.rz;
            float r = std::sqrt(dx * dx + dz * dz);
            float ring = std::exp(-((r - 0.5f) / 0.13f) * ((r - 0.5f) / 0.13f)) * 17.0f * theme.hilliness;
            mountains = ring + mountains * 0.3f;
            if (r < 0.32f) hills = lerpf(-7.0f, hills, smoothstepf(0.06f, 0.32f, r));  // bacia com encosta suave
            break;
        }
        case MapStyle::Rivers:
            hills *= 0.6f;
            mountains *= 0.5f;
            break;
        case MapStyle::Archipelago:
            mountains *= 0.7f;
            break;
        default:
            break;
    }
    float base = sea + 4.0f + hills + mountains;
    return lerpf(sea - 7.0f, base, landMask(s, x, z, size, seed));
}

void flattenDisc(HeightField& hf, float cx, float cz, float radius, float target, float blend, uint8_t reserveTag) {
    int r = static_cast<int>(std::ceil(radius + blend));
    for (int z = static_cast<int>(cz) - r; z <= static_cast<int>(cz) + r; ++z) {
        for (int x = static_cast<int>(cx) - r; x <= static_cast<int>(cx) + r; ++x) {
            if (!hf.inside(x, z)) continue;
            float d = std::sqrt((x + 0.5f - cx) * (x + 0.5f - cx) + (z + 0.5f - cz) * (z + 0.5f - cz));
            if (d <= radius) {
                hf.at(x, z) = target;
                if (reserveTag) hf.res(x, z) = reserveTag;
            } else if (d < radius + blend) {
                float t = smoothstepf(radius, radius + blend, d);
                hf.at(x, z) = lerpf(target, hf.at(x, z), t);
            }
        }
    }
}

// Rio sinuoso que atravessa o mapa de borda a borda.
void carveRiver(HeightField& hf, Vec2 from, Vec2 to, Rng& rng, int sea) {
    Vec2 delta = to - from;
    float len = delta.length();
    if (len < 10) return;
    Vec2 perp(-delta.y / len, delta.x / len);
    float amp = rng.range(6.0f, 14.0f), waves = rng.range(1.5f, 3.0f), phase = rng.range(0, kTwoPi);
    float width = rng.range(1.6f, 2.6f);
    int steps = static_cast<int>(len);
    for (int i = 0; i <= steps; ++i) {
        float t = static_cast<float>(i) / steps;
        Vec2 p = from + delta * t + perp * (std::sin(t * kPi * waves + phase) * amp);
        int r = static_cast<int>(width + 5);
        for (int z = static_cast<int>(p.y) - r; z <= static_cast<int>(p.y) + r; ++z) {
            for (int x = static_cast<int>(p.x) - r; x <= static_cast<int>(p.x) + r; ++x) {
                if (!hf.inside(x, z)) continue;
                float d = std::sqrt((x + 0.5f - p.x) * (x + 0.5f - p.x) + (z + 0.5f - p.y) * (z + 0.5f - p.y));
                float& h = hf.at(x, z);
                if (d <= width) {
                    h = std::min(h, static_cast<float>(sea - 2));
                    hf.res(x, z) = R_RIVER;
                } else if (d < width + 5.0f && h > sea + 0.6f) {
                    float bank = sea + 0.6f;
                    h = lerpf(bank, h, smoothstepf(width, width + 5.0f, d));
                }
            }
        }
    }
}

// Trilha sinuosa entre dois pontos; segue o relevo suavizado e vira ponte sobre a água.
void carvePath(HeightField& hf, Vec2 a, Vec2 b, uint32_t seed, int sea) {
    Vec2 delta = b - a;
    float len = delta.length();
    if (len < 2) return;
    int steps = static_cast<int>(len);
    Vec2 perp(-delta.y / len, delta.x / len);
    float wobble = 3.0f + hashFloat(static_cast<int>(a.x), static_cast<int>(b.y), seed) * 4.0f;
    float waves = 1.0f + std::floor(len / 40.0f);
    std::vector<Vec2> pts(steps + 1);
    std::vector<float> heights(steps + 1);
    for (int i = 0; i <= steps; ++i) {
        float t = static_cast<float>(i) / steps;
        float w = std::sin(t * kPi * waves) * wobble * std::sin(t * kPi);
        pts[i] = a + delta * t + perp * w;
        heights[i] = std::max(static_cast<float>(sea + 1), hf.get(static_cast<int>(pts[i].x), static_cast<int>(pts[i].y)));
    }
    std::vector<float> smooth(heights);
    for (int pass = 0; pass < 3; ++pass) {
        for (int i = 0; i <= steps; ++i) {
            float sum = 0, n = 0;
            for (int k = -4; k <= 4; ++k) {
                int j = std::max(0, std::min(steps, i + k));
                sum += smooth[j];
                n += 1;
            }
            heights[i] = sum / n;
        }
        smooth = heights;
    }
    for (int i = 1; i <= steps; ++i) smooth[i] = clampf(smooth[i], smooth[i - 1] - 0.6f, smooth[i - 1] + 0.6f);
    for (int i = 0; i <= steps; ++i) {
        float px = pts[i].x, pz = pts[i].y;
        for (int z = static_cast<int>(pz) - 4; z <= static_cast<int>(pz) + 4; ++z) {
            for (int x = static_cast<int>(px) - 4; x <= static_cast<int>(px) + 4; ++x) {
                if (!hf.inside(x, z)) continue;
                float d = std::sqrt((x + 0.5f - px) * (x + 0.5f - px) + (z + 0.5f - pz) * (z + 0.5f - pz));
                if (d <= 1.5f) {
                    if (hf.res(x, z) != R_CAMP) {
                        hf.at(x, z) = std::max(smooth[i], static_cast<float>(sea + 1));
                        hf.res(x, z) = R_PATH;
                    }
                } else if (d < 4.0f && (hf.res(x, z) == R_NONE || hf.res(x, z) == R_RIVER) && hf.at(x, z) > sea) {
                    float t = smoothstepf(1.5f, 4.0f, d);
                    hf.at(x, z) = lerpf(smooth[i], hf.at(x, z), t);
                }
            }
        }
    }
}

void fillColumns(World& world, HeightField& hf, const Theme& theme, int sea, uint32_t seed) {
    Rng rng(seed ^ 0xA5A5u);
    for (int z = 0; z < hf.d; ++z) {
        for (int x = 0; x < hf.w; ++x) {
            int top = std::max(2, std::min(kWorldHeight - 14, static_cast<int>(std::lround(hf.at(x, z)))));
            uint8_t res = hf.res(x, z);
            bool bridge = res == R_PATH && hf.naturalAt(x, z) <= sea + 0.5f;
            for (int y = 0; y < top; ++y) {
                uint8_t b;
                if (y == 0) b = B_BEDROCK;
                else if (y < top - 4) {
                    b = theme.deep;
                    if (b == B_STONE && rng.chance(0.025f)) b = rng.chance(0.6f) ? B_COAL_ORE : B_IRON_ORE;
                } else if (y < top - 1) b = bridge ? theme.deep : theme.filler;
                else {
                    if (bridge) b = B_PLANKS;
                    else if (res == R_PATH) b = B_PATH;
                    else if (top - 1 <= sea + 1 && res != R_CAMP) b = theme.beach;
                    else if (top > 31 && theme.id != 1) b = (top > 33) ? B_SNOW : B_STONE;
                    else if (top > 28 && theme.id == 1) b = B_TERRACOTTA;
                    else b = theme.surface;
                    if (theme.id == 3 && b == B_ASH && rng.chance(0.015f)) b = B_MAGMA;
                }
                world.set(x, y, z, b, false);
            }
            int ground = top;
            if (top <= sea) {
                for (int y = top; y <= sea; ++y) world.set(x, y, z, B_WATER, false);
                if (theme.id == 2) {  // lagos congelados na neve
                    world.set(x, sea, z, B_ICE, false);
                    ground = sea + 1;
                }
            }
            world.setGroundHeight(x, z, ground);
        }
    }
}

bool farFromAll(const Vec2& p, const std::vector<Vec2>& others, float minDist) {
    for (const auto& o : others)
        if ((p - o).length() < minDist) return false;
    return true;
}

void scatterTreesAndPlants(World& world, HeightField& hf, const Theme& theme, int sea, uint32_t seed, float densityScale) {
    Rng rng(seed ^ 0x51ED27u);
    std::vector<uint8_t> trunk(static_cast<size_t>(hf.w) * hf.d, 0);
    auto nearReserved = [&](int x, int z, int radius) {
        for (int dz = -radius; dz <= radius; ++dz)
            for (int dx = -radius; dx <= radius; ++dx) {
                if (!hf.inside(x + dx, z + dz)) continue;
                uint8_t r = hf.res(x + dx, z + dz);
                if (r == R_PATH || r == R_CAMP) return true;
                if (trunk[static_cast<size_t>(z + dz) * hf.w + x + dx]) return true;
            }
        return false;
    };
    for (int z = 3; z < hf.d - 3; ++z) {
        for (int x = 3; x < hf.w - 3; ++x) {
            int y = world.groundHeight(x, z);
            if (y <= sea + 1 || y >= kWorldHeight - 16) continue;
            uint8_t surface = world.get(x, y - 1, z);
            if (surface != theme.surface && surface != B_GRASS) continue;
            float forest = fbm(x / 34.0f, z / 34.0f, seed + 9, 3);
            float p = theme.treeDensity * densityScale * (0.25f + forest * forest * 4.0f);
            if (rng.chance(p) && !nearReserved(x, z, theme.tree == TreeKind::Cactus ? 2 : 3)) {
                WorldSink sink(world);
                placeTree(sink, pickTreeKind(theme, x, z, seed, y <= sea + 4), x, y, z, rng);
                trunk[static_cast<size_t>(z) * hf.w + x] = 1;
                hf.res(x, z) = R_TREE;
                continue;
            }
            if (hf.res(x, z) == R_NONE && world.get(x, y, z) == B_AIR && rng.chance(theme.plantDensity)) {
                world.set(x, y, z, rng.chance(0.5f) ? theme.plantA : theme.plantB, false);
            }
        }
    }
    int boulders = static_cast<int>(hf.w * hf.d / 2600 * densityScale);
    for (int i = 0; i < boulders; ++i) {
        int x = rng.rangeInt(8, hf.w - 9), z = rng.rangeInt(8, hf.d - 9);
        if (nearReserved(x, z, 3)) continue;
        int y = world.groundHeight(x, z);
        if (y <= sea + 1) continue;
        uint8_t rock = theme.id == 1 ? B_SANDSTONE : (theme.id == 3 ? B_NETHERRACK : B_COBBLE);
        for (int dz = 0; dz < 2; ++dz)
            for (int dx = 0; dx < 2; ++dx)
                if (rng.chance(0.85f)) {
                    world.set(x + dx, y, z + dz, rock, false);
                    if (rng.chance(0.35f)) world.set(x + dx, y + 1, z + dz, B_STONE, false);
                }
    }
}

}  // namespace

Theme applySkyVariant(const Theme& base, uint32_t seed, bool allowNight) {
    Theme t = base;
    Rng rng(seed ^ 0x5151F00Du);
    float azimuth = rng.range(0, kTwoPi);
    float elevation = std::asin(clampf(t.sky.sunDirection.y, -1, 1));
    int variant = rng.rangeInt(0, allowNight ? 4 : 3);
    if (t.sky.night < 0.5f) {
        if (variant == 2) {  // hora dourada
            elevation = 0.22f;
            t.sky.zenith = {0.32f, 0.42f, 0.78f};
            t.sky.horizon = {1.0f, 0.68f, 0.46f};
            t.sky.fog = {0.96f, 0.76f, 0.6f};
            t.sky.sunColor = {1.0f, 0.82f, 0.58f};
        } else if (variant == 3) {  // nublado
            t.sky.zenith = {0.52f, 0.57f, 0.66f};
            t.sky.horizon = {0.74f, 0.77f, 0.82f};
            t.sky.fog = {0.72f, 0.75f, 0.8f};
            t.sky.sunIntensity *= 0.6f;
            t.sky.ambient = t.sky.ambient + Vec3(0.08f, 0.08f, 0.08f);
            t.sky.fogEnd *= 0.85f;
        } else if (variant == 4) {  // noite enluarada
            elevation = 0.6f;
            t.sky.zenith = {0.03f, 0.04f, 0.11f};
            t.sky.horizon = {0.12f, 0.14f, 0.26f};
            t.sky.fog = {0.08f, 0.09f, 0.17f};
            t.sky.sunColor = {0.62f, 0.68f, 0.9f};
            t.sky.sunIntensity = 0.35f;
            t.sky.ambient = {0.28f, 0.3f, 0.4f};
            t.sky.night = 1.0f;
            t.sky.fogStart = 28.0f;
            t.sky.fogEnd = 95.0f;
        }
    }
    float ce = std::cos(elevation);
    t.sky.sunDirection = normalize(Vec3(ce * std::cos(azimuth), std::sin(elevation), ce * std::sin(azimuth)));
    return t;
}

WorldLayout generateStageWorld(World& world, const Theme& theme, int stage, uint32_t seed, const GenOptions& options) {
    const int size = options.size;
    const int sea = 13;
    world.reset(size, kWorldHeight, size);
    WorldLayout layout;
    layout.seaLevel = sea;
    Rng rng(seed);

    // Estilo do mapa: deserto favorece mesetas; neve favorece vales.
    int styleRoll = rng.rangeInt(0, static_cast<int>(MapStyle::Count) - 1);
    if (theme.id == 1 && rng.chance(0.35f)) styleRoll = static_cast<int>(MapStyle::Mesas);
    if (theme.id == 2 && rng.chance(0.3f)) styleRoll = static_cast<int>(MapStyle::Valley);
    if (options.pvp && styleRoll == static_cast<int>(MapStyle::Archipelago)) styleRoll = static_cast<int>(MapStyle::Island);
    layout.style = static_cast<MapStyle>(styleRoll);
    MapShape shape = makeShape(layout.style, size, rng);

    HeightField hf;
    hf.w = size;
    hf.d = size;
    hf.h.resize(static_cast<size_t>(size) * size);
    hf.reserve.assign(static_cast<size_t>(size) * size, R_NONE);
    for (int z = 0; z < size; ++z)
        for (int x = 0; x < size; ++x) hf.at(x, z) = naturalHeight(shape, x + 0.5f, z + 0.5f, size, theme, seed, sea);

    // Rios.
    int rivers = layout.style == MapStyle::Rivers ? 2 : (layout.style == MapStyle::Valley ? 1 : (rng.chance(0.3f) ? 1 : 0));
    if (options.pvp) rivers = std::min(rivers, 1);
    for (int i = 0; i < rivers; ++i) {
        Vec2 from, to;
        if (layout.style == MapStyle::Valley && i == 0) {
            Vec2 dir(-shape.nz, shape.nx);
            Vec2 mid(size * 0.5f + shape.nx * shape.offset, size * 0.5f + shape.nz * shape.offset);
            from = mid - dir * (size * 0.7f);
            to = mid + dir * (size * 0.7f);
        } else {
            float a = rng.range(0, kTwoPi);
            Vec2 dir(std::cos(a), std::sin(a));
            Vec2 mid(size * 0.5f + rng.range(-20, 20), size * 0.5f + rng.range(-20, 20));
            from = mid - dir * (size * 0.7f);
            to = mid + dir * (size * 0.7f);
        }
        carveRiver(hf, from, to, rng, sea);
    }
    hf.natural = hf.h;

    // Candidatos a acampamento: terra firme, longe da borda e dos rios.
    auto okSpot = [&](Vec2 c) {
        int x = static_cast<int>(c.x), z = static_cast<int>(c.y);
        if (!hf.inside(x, z) || hf.res(x, z) == R_RIVER) return false;
        float h = hf.get(x, z);
        return h > sea + 1.5f && h < sea + 15.0f;
    };
    float margin = options.pvp ? 14.0f : 22.0f;
    Vec2 start(size * 0.5f, size * 0.5f);
    for (int attempt = 0; attempt < 300; ++attempt) {
        Vec2 c(rng.range(margin, size - margin), rng.range(margin, size - margin));
        float h = hf.get(static_cast<int>(c.x), static_cast<int>(c.y));
        if (okSpot(c) && h < sea + 9) { start = c; break; }
    }
    float startH = clampf(std::round(hf.get(static_cast<int>(start.x), static_cast<int>(start.y))), sea + 2.0f, sea + 8.0f);
    std::vector<Vec2> placed{start};
    std::vector<Camp> camps;
    camps.push_back({{start.x, startH, start.y}, 6.5f, true, false, K_FIRE});

    float minFromStart = options.pvp ? 20.0f : 38.0f;
    float minBetween = options.pvp ? 22.0f : 30.0f;
    int campCount = options.pvp ? options.camps : std::min(9, options.camps - 1 + stage / 4);
    for (int attempt = 0; attempt < 1500 && static_cast<int>(camps.size()) < campCount + 1; ++attempt) {
        Vec2 c(rng.range(margin, size - margin), rng.range(margin, size - margin));
        if ((c - start).length() < minFromStart) continue;
        if (!farFromAll(c, placed, minBetween) || !okSpot(c)) continue;
        float h = hf.get(static_cast<int>(c.x), static_cast<int>(c.y));
        placed.push_back(c);
        // Acampamentos e construções (casas, posto, celeiro) servem de cobertura no PvP.
        static const int kKinds[] = {K_FIRE, K_RUINS, K_GRAVEYARD, K_DEPOT, K_FARM, K_HOUSE, K_HOUSE, K_GAS, K_BARN};
        int kind = kKinds[rng.rangeInt(0, static_cast<int>(sizeof(kKinds) / sizeof(kKinds[0])) - 1)];
        float radius = isBuildingKind(kind) ? buildingRadius(kind) : rng.range(6.0f, 8.0f);
        camps.push_back({{c.x, std::round(clampf(h, sea + 2.0f, sea + 12.0f)), c.y}, radius, false, false, kind});
    }
    if (options.portal && camps.size() > 1) {
        size_t farIdx = 1;
        for (size_t i = 1; i < camps.size(); ++i)
            if (distXZ(camps[i].center, camps[0].center) > distXZ(camps[farIdx].center, camps[0].center)) farIdx = i;
        camps[farIdx].portal = true;
        camps[farIdx].kind = K_RUINS;
    }

    for (const auto& c : camps) flattenDisc(hf, c.center.x, c.center.z, c.radius, c.center.y, 6.0f, R_CAMP);
    for (size_t i = 1; i < camps.size(); ++i) {
        size_t from = 0;
        float best = 1e9f;
        for (size_t j = 0; j < i; ++j) {
            float d = distXZ(camps[i].center, camps[j].center);
            if (d < best) { best = d; from = j; }
        }
        carvePath(hf, {camps[from].center.x, camps[from].center.z}, {camps[i].center.x, camps[i].center.z}, seed + static_cast<uint32_t>(i), sea);
    }
    for (const auto& c : camps) flattenDisc(hf, c.center.x, c.center.z, c.radius, c.center.y, 2.0f, R_CAMP);

    fillColumns(world, hf, theme, sea, seed);
    scatterTreesAndPlants(world, hf, theme, sea, seed, options.pvp ? 1.3f : 1.0f);

    for (auto& c : camps) {
        c.center = {std::floor(c.center.x) + 0.5f, static_cast<float>(world.groundHeight(static_cast<int>(c.center.x), static_cast<int>(c.center.z))), std::floor(c.center.z) + 0.5f};
        WorldSink sink(world);
        DecorOut out;
        decorateCamp(sink, c, theme, rng, out);
        if (c.portal) buildPortal(sink, c, camps[0].center, layout.portalBase, layout.portalYaw, out);
        absorb(layout, out);
    }

    layout.camps = camps;
    layout.start = camps[0].center + Vec3(0, 0, 2.5f);
    if (camps.size() > 1) {
        size_t nearIdx = 1;
        for (size_t i = 1; i < camps.size(); ++i)
            if (distXZ(camps[i].center, camps[0].center) < distXZ(camps[nearIdx].center, camps[0].center)) nearIdx = i;
        Vec3 d = camps[nearIdx].center - layout.start;
        layout.startYaw = yawFromDirection(d.x, d.z);
    }

    auto standPoint = [&](int x, int z) { return Vec3(x + 0.5f, static_cast<float>(world.standHeight(x, z)), z + 0.5f); };
    auto clearSpot = [&](int x, int z) {
        return world.groundHeight(x, z) > sea + 1 && world.standHeight(x, z) - world.groundHeight(x, z) < 1 &&
               !world.solid(x, world.standHeight(x, z) + 1, z);
    };

    if (options.pvp) {
        // Pontos de renascimento espalhados (acampamentos + clareiras).
        for (const auto& c : camps) layout.spawnPoints.push_back(c.center + Vec3(0, 0, 2.5f));
        for (int attempt = 0; attempt < 600 && layout.spawnPoints.size() < 14; ++attempt) {
            int x = rng.rangeInt(12, size - 13), z = rng.rangeInt(12, size - 13);
            Vec3 p = standPoint(x, z);
            if (!clearSpot(x, z)) continue;
            bool far = true;
            for (const auto& s : layout.spawnPoints)
                if (distXZ(s, p) < 16.0f) { far = false; break; }
            if (far) layout.spawnPoints.push_back(p);
        }
    } else {
        std::vector<std::vector<Vec3>> perCamp;
        for (size_t i = 1; i < camps.size(); ++i) {
            std::vector<Vec3> pts;
            for (int k = 0; k < 7; ++k) {
                float ang = rng.range(0, kTwoPi), dist = rng.range(1.5f, camps[i].radius - 1.0f);
                int x = static_cast<int>(camps[i].center.x + std::cos(ang) * dist);
                int z = static_cast<int>(camps[i].center.z + std::sin(ang) * dist);
                pts.push_back(standPoint(x, z));
            }
            perCamp.push_back(pts);
        }
        std::vector<Vec3> wanderers;
        for (int attempt = 0; attempt < 500 && wanderers.size() < 16; ++attempt) {
            int x = rng.rangeInt(14, size - 15), z = rng.rangeInt(14, size - 15);
            Vec3 p = standPoint(x, z);
            if (distXZ(p, layout.start) < 34 || !clearSpot(x, z)) continue;
            wanderers.push_back(p);
        }
        for (size_t round = 0; round < 7; ++round) {
            for (auto& pts : perCamp)
                if (round < pts.size()) layout.zombieSpawns.push_back(pts[round]);
            if (round < wanderers.size()) layout.zombieSpawns.push_back(wanderers[round]);
            if (round + 7 < wanderers.size()) layout.zombieSpawns.push_back(wanderers[round + 7]);
        }
    }

    world.markAllDirty();
    return layout;
}

WorldLayout generateLobbyWorld(World& world, const Theme& theme) {
    const int size = 64;
    const int sea = 10;
    world.reset(size, 40, size);
    WorldLayout layout;
    layout.seaLevel = sea;
    HeightField hf;
    hf.w = size;
    hf.d = size;
    hf.h.resize(static_cast<size_t>(size) * size);
    hf.reserve.assign(static_cast<size_t>(size) * size, R_NONE);
    const uint32_t seed = 4242u;
    for (int z = 0; z < size; ++z)
        for (int x = 0; x < size; ++x) {
            float dx = (x - size * 0.5f), dz = (z - size * 0.5f);
            float d = std::sqrt(dx * dx + dz * dz);
            float hills = (fbm(x / 14.0f, z / 14.0f, seed, 3) - 0.5f) * 8.0f + smoothstepf(10, 30, d) * 6.0f;
            hf.at(x, z) = 16.0f + hills;
        }
    Camp camp{{size * 0.5f, 16.0f, size * 0.5f}, 6.0f, true, false, K_FIRE};
    flattenDisc(hf, camp.center.x, camp.center.z, 9.0f, 16.0f, 5.0f, R_CAMP);
    carvePath(hf, {size * 0.5f, size * 0.5f}, {size * 0.5f + 4, size - 4.0f}, seed, sea);
    hf.natural = hf.h;
    fillColumns(world, hf, theme, sea, seed);
    scatterTreesAndPlants(world, hf, theme, sea, seed, 2.6f);
    int cy = world.groundHeight(static_cast<int>(camp.center.x), static_cast<int>(camp.center.z));
    camp.center.y = static_cast<float>(cy);
    int fx = static_cast<int>(camp.center.x) + 3, fz = static_cast<int>(camp.center.z) - 2;
    world.set(fx, cy - 1, fz, B_CAMPFIRE, false);
    layout.campfires.push_back({fx + 0.5f, static_cast<float>(cy), fz + 0.5f});
    layout.lights.push_back({fx + 0.5f, cy + 0.8f, fz + 0.5f});
    world.set(fx + 2, cy, fz - 2, B_CRATE, false);
    world.set(fx + 2, cy + 1, fz - 2, B_CRATE, false);
    world.set(fx + 3, cy, fz - 2, B_HAY, false);
    {
        WorldSink sink(world);
        DecorOut out;
        lanternPost(sink, static_cast<int>(camp.center.x) - 6, cy, static_cast<int>(camp.center.z) - 7, out);
        absorb(layout, out);
    }
    for (int dz = -3; dz <= 3; ++dz)
        for (int dx = -3; dx <= 3; ++dx) {
            int x = static_cast<int>(camp.center.x) + dx, z = static_cast<int>(camp.center.z) + dz;
            if (dx * dx + dz * dz <= 9 && blockInfo(world.get(x, cy, z)).shape == Shape::Cross) world.set(x, cy, z, B_AIR, false);
        }
    layout.camps.push_back(camp);
    layout.start = camp.center;
    layout.startYaw = 0;
    world.markAllDirty();
    return layout;
}

}  // namespace zz
