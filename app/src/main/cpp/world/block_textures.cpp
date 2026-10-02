#include "block_textures.h"

#include <cmath>

#include "../core/random.h"

namespace zz {

namespace {

struct Canvas {
    uint8_t* px;
    int layer;
    void set(int x, int y, int r, int g, int b, int a = 255) {
        x &= 15; y &= 15;
        uint8_t* p = px + (y * kTexSize + x) * 4;
        p[0] = static_cast<uint8_t>(clampf(static_cast<float>(r), 0, 255));
        p[1] = static_cast<uint8_t>(clampf(static_cast<float>(g), 0, 255));
        p[2] = static_cast<uint8_t>(clampf(static_cast<float>(b), 0, 255));
        p[3] = static_cast<uint8_t>(a);
    }
    void setV(int x, int y, const Vec3& c, int a = 255) {
        set(x, y, static_cast<int>(c.x), static_cast<int>(c.y), static_cast<int>(c.z), a);
    }
    Vec3 get(int x, int y) const {
        const uint8_t* p = px + ((y & 15) * kTexSize + (x & 15)) * 4;
        return {static_cast<float>(p[0]), static_cast<float>(p[1]), static_cast<float>(p[2])};
    }
    void clear() {
        for (int i = 0; i < kTexSize * kTexSize * 4; ++i) px[i] = 0;
    }
};

float n01(int x, int y, uint32_t seed) { return hashFloat(x, y, seed); }
float sgn(int x, int y, uint32_t seed) { return n01(x, y, seed) * 2.0f - 1.0f; }

Vec3 jitter(const Vec3& base, int x, int y, uint32_t seed, float amount) {
    float j = sgn(x, y, seed) * amount;
    return {base.x + j, base.y + j, base.z + j};
}

void fillNoise(Canvas& c, const Vec3& base, float amount, uint32_t seed) {
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) c.setV(x, y, jitter(base, x, y, seed, amount));
}

void speckle(Canvas& c, const Vec3& color, float probability, uint32_t seed) {
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x)
            if (n01(x, y, seed) < probability) c.setV(x, y, jitter(color, x, y, seed + 7, 8));
}

void dirt(Canvas& c, uint32_t seed) {
    fillNoise(c, {134, 96, 67}, 14, seed);
    speckle(c, {98, 68, 46}, 0.16f, seed + 1);
    speckle(c, {160, 120, 86}, 0.08f, seed + 2);
}

void stone(Canvas& c, uint32_t seed) {
    fillNoise(c, {126, 126, 126}, 10, seed);
    for (int i = 0; i < 9; ++i) {
        int sx = static_cast<int>(n01(i, 1, seed) * 16), sy = static_cast<int>(n01(i, 2, seed) * 16);
        int len = 2 + static_cast<int>(n01(i, 3, seed) * 3);
        for (int k = 0; k < len; ++k) c.set(sx + k, sy, 104, 104, 106);
    }
    speckle(c, {148, 148, 148}, 0.06f, seed + 3);
}

void cobble(Canvas& c, uint32_t seed) {
    // Pedras irregulares por Voronoi com bordas escuras.
    float px[7], py[7], shade[7];
    for (int i = 0; i < 7; ++i) {
        px[i] = n01(i, 11, seed) * 16; py[i] = n01(i, 12, seed) * 16; shade[i] = 105 + n01(i, 13, seed) * 45;
    }
    for (int y = 0; y < 16; ++y) {
        for (int x = 0; x < 16; ++x) {
            float best = 1e9f, second = 1e9f; int id = 0;
            for (int i = 0; i < 7; ++i) {
                for (int ox = -16; ox <= 16; ox += 16) {
                    for (int oy = -16; oy <= 16; oy += 16) {
                        float dx = x + 0.5f - (px[i] + ox), dy = y + 0.5f - (py[i] + oy);
                        float d = dx * dx + dy * dy;
                        if (d < best) { second = best; best = d; id = i; } else if (d < second) second = d;
                    }
                }
            }
            bool edge = std::sqrt(second) - std::sqrt(best) < 1.1f;
            float v = edge ? 72 : shade[id] + sgn(x, y, seed) * 8;
            c.set(x, y, static_cast<int>(v), static_cast<int>(v), static_cast<int>(v + 2));
        }
    }
}

void grassTop(Canvas& c, const Vec3& tint, uint32_t seed) {
    Vec3 base = Vec3(255.0f * tint.x, 255.0f * tint.y, 255.0f * tint.z) * 0.8f;
    fillNoise(c, base, 16, seed);
    speckle(c, base * 0.78f, 0.18f, seed + 1);
    speckle(c, base * 1.12f, 0.08f, seed + 2);
}

void sideWithTop(Canvas& c, const Canvas& topSource, int rows, uint32_t seed) {
    for (int x = 0; x < 16; ++x) {
        int depth = rows + (n01(x, 0, seed) < 0.45f ? 1 : 0) + (n01(x, 1, seed) < 0.15f ? 1 : 0);
        for (int y = 0; y < depth; ++y) {
            Vec3 col = topSource.get(x, y + 3);
            if (y == depth - 1) col = col * 0.85f;
            c.setV(x, y, col);
        }
    }
}

void logSide(Canvas& c, const Vec3& base, uint32_t seed) {
    for (int y = 0; y < 16; ++y) {
        for (int x = 0; x < 16; ++x) {
            float stripe = (x % 4 == 0) ? 0.72f : ((x % 4 == 2) ? 0.9f : 1.0f);
            float v = 1.0f + sgn(x / 2, y / 3, seed) * 0.08f;
            c.setV(x, y, base * (stripe * v));
        }
    }
}

void logTop(Canvas& c, uint32_t seed) {
    for (int y = 0; y < 16; ++y) {
        for (int x = 0; x < 16; ++x) {
            float dx = x - 7.5f, dy = y - 7.5f;
            float r = std::sqrt(dx * dx + dy * dy);
            if (std::max(std::fabs(dx), std::fabs(dy)) > 6.6f) { c.set(x, y, 96, 72, 42); continue; }
            float ring = std::fmod(r, 2.4f) < 0.9f ? 0.84f : 1.0f;
            c.setV(x, y, jitter(Vec3(182, 146, 94) * ring, x, y, seed, 6));
        }
    }
}

void leaves(Canvas& c, const Vec3& base, uint32_t seed) {
    for (int y = 0; y < 16; ++y) {
        for (int x = 0; x < 16; ++x) {
            float n = n01(x, y, seed);
            float v = n < 0.22f ? 0.55f : (n > 0.86f ? 1.2f : 0.85f + n * 0.2f);
            c.setV(x, y, base * v);
        }
    }
}

void planks(Canvas& c, const Vec3& base, uint32_t seed) {
    for (int y = 0; y < 16; ++y) {
        int board = y / 4;
        int seam = (board * 7 + 3) % 16;
        for (int x = 0; x < 16; ++x) {
            float v = 1.0f + sgn(x / 3, y, seed + board) * 0.05f;
            if (y % 4 == 3) v = 0.62f;
            else if (x == seam) v = 0.7f;
            else if (n01(x, y, seed + 9) < 0.08f) v *= 0.86f;
            c.setV(x, y, base * v);
        }
    }
}

void crate(Canvas& c, uint32_t seed) {
    planks(c, {168, 124, 70}, seed);
    for (int i = 0; i < 16; ++i) {
        for (int t = 0; t < 2; ++t) {
            c.set(i, t, 112, 78, 42); c.set(i, 15 - t, 112, 78, 42);
            c.set(t, i, 112, 78, 42); c.set(15 - t, i, 112, 78, 42);
        }
        if (i > 1 && i < 14) { c.set(i, i, 120, 84, 46); c.set(i, i + 1, 120, 84, 46); }
    }
    c.set(1, 1, 70, 70, 74); c.set(14, 1, 70, 70, 74); c.set(1, 14, 70, 70, 74); c.set(14, 14, 70, 70, 74);
}

void cross(Canvas& c, const Vec3& stem, const Vec3& head, int kind, uint32_t seed) {
    c.clear();
    if (kind == 0 || kind == 1) {  // flores
        for (int y = 7; y < 16; ++y) c.setV(7 + (y % 5 == 0 ? 1 : 0), y, stem);
        c.setV(6, 11, stem); c.setV(5, 10, stem); c.setV(9, 12, stem); c.setV(10, 11, stem);
        for (int y = 3; y < 8; ++y)
            for (int x = 5; x < 11; ++x) {
                float dx = x - 7.5f, dy = y - 5.0f;
                if (dx * dx + dy * dy < 7.5f) c.setV(x, y, jitter(head, x, y, seed, 14));
            }
        c.set(7, 5, 250, 220, 90); c.set(8, 5, 240, 200, 80);
    } else if (kind == 2) {  // capim
        for (int b = 0; b < 7; ++b) {
            int x = 1 + b * 2 + (n01(b, 0, seed) < 0.5f ? 0 : 1);
            int h = 6 + static_cast<int>(n01(b, 1, seed) * 9);
            for (int y = 15; y > 15 - h; --y) c.setV(x + ((15 - y) > h / 2 && b % 2 ? 1 : 0), y, jitter(stem, x, y, seed, 18));
        }
    } else if (kind == 3) {  // arbusto seco
        for (int b = 0; b < 6; ++b) {
            float ang = -1.1f + b * 0.44f;
            for (int t = 0; t < 9; ++t) {
                int x = static_cast<int>(7.5f + std::sin(ang) * t), y = 15 - static_cast<int>(std::cos(ang) * t);
                c.setV(x, y, stem);
            }
        }
    } else {  // cogumelo
        for (int y = 9; y < 16; ++y) { c.set(7, y, 228, 220, 200); c.set(8, y, 210, 200, 182); }
        for (int y = 4; y < 10; ++y)
            for (int x = 3; x < 13; ++x) {
                float dx = x - 7.5f, dy = (y - 9.0f) * 1.3f;
                if (dx * dx + dy * dy < 22) c.setV(x, y, head);
            }
        c.set(5, 6, 240, 240, 240); c.set(9, 5, 240, 240, 240); c.set(10, 7, 240, 240, 240); c.set(7, 7, 240, 240, 240);
    }
}

void ore(Canvas& c, const Vec3& color, uint32_t seed) {
    stone(c, seed);
    for (int k = 0; k < 4; ++k) {
        int cx = 2 + static_cast<int>(n01(k, 21, seed) * 11), cy = 2 + static_cast<int>(n01(k, 22, seed) * 11);
        for (int i = 0; i < 4; ++i) c.setV(cx + (i & 1), cy + (i >> 1), jitter(color, cx + i, cy, seed, 12));
    }
}

void lantern(Canvas& c) {
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            bool frame = x < 2 || x > 13 || y < 2 || y > 13 || x == 7 || x == 8 || y == 7 || y == 8;
            if (frame) { c.set(x, y, 52, 42, 34); continue; }
            float dx = x - 7.5f, dy = y - 7.5f;
            float glow = 1.0f - std::sqrt(dx * dx + dy * dy) / 11.0f;
            c.set(x, y, 255, static_cast<int>(176 + glow * 70), static_cast<int>(80 + glow * 110));
        }
}

void magma(Canvas& c, uint32_t seed) {
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            float n = fbm(x * 0.35f, y * 0.35f, seed, 3);
            if (n > 0.47f && n < 0.56f) c.set(x, y, 255, static_cast<int>(130 + n * 120), 40);
            else c.setV(x, y, jitter(Vec3(78, 26, 20), x, y, seed, 10));
        }
}

void campfireTop(Canvas& c, uint32_t seed) {
    logTop(c, seed);
    for (int y = 3; y < 13; ++y)
        for (int x = 3; x < 13; ++x) {
            float dx = x - 7.5f, dy = y - 7.5f;
            float d = std::sqrt(dx * dx + dy * dy);
            if (d < 4.6f) c.set(x, y, 255, static_cast<int>(240 - d * 30), static_cast<int>(120 - d * 22));
        }
}

void campfireSide(Canvas& c, uint32_t seed) {
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            if (y >= 11) { c.setV(x, y, jitter(Vec3(96, 70, 40), x / 3, y, seed, 10)); continue; }
            float flame = std::sin(x * 1.3f + seed) * 2.0f + 5.0f;
            if (y > 11 - flame - 3) c.set(x, y, 255, static_cast<int>(150 + (y * 8)), 40);
            else c.set(x, y, 40, 30, 24);
        }
}

}  // namespace

std::vector<uint8_t> generateBlockTextures(const Theme& theme) {
    std::vector<uint8_t> data(static_cast<size_t>(T_COUNT) * kTexSize * kTexSize * 4, 255);
    auto layer = [&](int index) { return Canvas{data.data() + static_cast<size_t>(index) * kTexSize * kTexSize * 4, index}; };
    const uint32_t s = 0xC0FFEEu;
    const Vec3 tint = theme.grassTint;

    Canvas c = layer(T_DIRT); dirt(c, s + 1);
    c = layer(T_GRASS_TOP); grassTop(c, tint, s + 2);
    c = layer(T_GRASS_SIDE); dirt(c, s + 1); sideWithTop(c, layer(T_GRASS_TOP), 3, s + 3);
    c = layer(T_STONE); stone(c, s + 4);
    c = layer(T_COBBLE); cobble(c, s + 5);
    c = layer(T_SAND); fillNoise(c, {222, 208, 162}, 8, s + 6); speckle(c, {200, 186, 140}, 0.12f, s + 7);
    c = layer(T_SANDSTONE_TOP); fillNoise(c, {216, 200, 150}, 6, s + 8);
    c = layer(T_SANDSTONE_SIDE);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            float band = y < 3 ? 1.06f : (y % 5 == 4 ? 0.86f : 0.97f);
            c.setV(x, y, jitter(Vec3(214, 196, 142) * band, x, y, s + 9, 5));
        }
    c = layer(T_SNOW); fillNoise(c, {240, 246, 252}, 5, s + 10); speckle(c, {214, 228, 244}, 0.1f, s + 11);
    c = layer(T_SNOW_SIDE); dirt(c, s + 1); sideWithTop(c, layer(T_SNOW), 4, s + 12);
    c = layer(T_ICE);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) c.set(x, y, 150, ((x + y) % 7 == 0) ? 214 : 190, 240);
    c = layer(T_WATER);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            float w = 0.85f + 0.15f * std::sin((x + y * 0.5f) * 0.9f) + sgn(x, y, s + 13) * 0.05f;
            c.setV(x, y, Vec3(theme.waterTint.x * 255, theme.waterTint.y * 255, theme.waterTint.z * 255) * w, 255);
        }
    c = layer(T_LOG_OAK); logSide(c, {108, 82, 50}, s + 14);
    c = layer(T_LOG_TOP); logTop(c, s + 15);
    c = layer(T_LEAVES_OAK); leaves(c, Vec3(70, 150, 52) * 1.0f * (0.5f + tint.y * 0.6f), s + 16);
    c = layer(T_LOG_SPRUCE); logSide(c, {72, 52, 32}, s + 17);
    c = layer(T_LEAVES_SPRUCE); leaves(c, {48, 92, 62}, s + 18);
    if (theme.tree == TreeKind::Spruce) {  // neve sobre os pinheiros
        for (int x = 0; x < 16; ++x)
            for (int y = 0; y < 16; ++y)
                if (n01(x, y, s + 40) < 0.28f) c.set(x, y, 236, 242, 248);
    }
    c = layer(T_LOG_DEAD); logSide(c, {96, 86, 78}, s + 19);
    c = layer(T_PLANKS); planks(c, {184, 142, 86}, s + 20);
    c = layer(T_CRATE); crate(c, s + 21);
    c = layer(T_CACTUS_SIDE);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            float v = (x % 4 == 1) ? 0.8f : 1.0f;
            c.setV(x, y, jitter(Vec3(76, 148, 62) * v, x, y, s + 22, 6));
            if (x % 4 == 3 && y % 3 == 0) c.set(x, y, 226, 226, 180);
        }
    c = layer(T_CACTUS_TOP); fillNoise(c, {86, 160, 70}, 8, s + 23);
    for (int i = 4; i < 12; ++i) { c.set(i, 4, 60, 120, 50); c.set(i, 11, 60, 120, 50); c.set(4, i, 60, 120, 50); c.set(11, i, 60, 120, 50); }
    c = layer(T_GRAVEL);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            float n = n01(x / 2, y / 2, s + 24);
            c.setV(x, y, n < 0.33f ? Vec3(112, 106, 104) : (n < 0.66f ? Vec3(140, 134, 128) : Vec3(122, 108, 96)));
        }
    c = layer(T_PATH_TOP); fillNoise(c, {150, 118, 72}, 10, s + 25); speckle(c, {124, 96, 58}, 0.15f, s + 26);
    c = layer(T_PATH_SIDE); dirt(c, s + 1); for (int x = 0; x < 16; ++x) c.setV(x, 0, Vec3(150, 118, 72));
    c = layer(T_OBSIDIAN);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            float n = n01(x, y, s + 27);
            c.setV(x, y, n > 0.9f ? Vec3(70, 44, 104) : (n > 0.7f ? Vec3(38, 26, 56) : Vec3(18, 13, 28)));
        }
    c = layer(T_PORTAL);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            float sw = std::sin(x * 0.8f + y * 0.4f) * 0.5f + 0.5f;
            c.set(x, y, static_cast<int>(120 + sw * 80), static_cast<int>(40 + sw * 40), 255, 210);
        }
    c = layer(T_NETHERRACK); fillNoise(c, {112, 46, 46}, 16, s + 28); speckle(c, {140, 64, 60}, 0.12f, s + 29);
    c = layer(T_ASH_TOP); fillNoise(c, {108, 98, 84}, 12, s + 30); speckle(c, {84, 76, 66}, 0.2f, s + 31);
    c = layer(T_ASH_SIDE); dirt(c, s + 1); sideWithTop(c, layer(T_ASH_TOP), 3, s + 32);
    c = layer(T_BEDROCK);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            float n = n01(x, y, s + 33);
            c.setV(x, y, n < 0.35f ? Vec3(30, 30, 30) : (n < 0.7f ? Vec3(70, 70, 70) : Vec3(110, 110, 110)));
        }
    c = layer(T_LANTERN); lantern(c);
    c = layer(T_MAGMA); magma(c, s + 34);
    c = layer(T_TERRACOTTA); fillNoise(c, {176, 98, 62}, 6, s + 35);
    c = layer(T_HAY_SIDE);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) {
            bool band = y == 3 || y == 12;
            c.setV(x, y, band ? Vec3(150, 60, 40) : jitter(Vec3(214, 178, 62) * ((x % 3 == 0) ? 0.86f : 1.0f), x, y, s + 36, 8));
        }
    c = layer(T_HAY_TOP);
    for (int y = 0; y < 16; ++y)
        for (int x = 0; x < 16; ++x) c.setV(x, y, jitter(Vec3(204, 168, 58) * (((x + y) % 4 == 0) ? 0.85f : 1.0f), x, y, s + 37, 8));
    c = layer(T_COAL_ORE); ore(c, {34, 34, 36}, s + 38);
    c = layer(T_IRON_ORE); ore(c, {216, 176, 140}, s + 39);
    c = layer(T_CAMPFIRE_TOP); campfireTop(c, s + 41);
    c = layer(T_CAMPFIRE_SIDE); campfireSide(c, s + 42);
    Vec3 stem = Vec3(70, 140, 50) * (0.6f + tint.y * 0.5f);
    c = layer(T_FLOWER_RED); cross(c, stem, {214, 40, 40}, 0, s + 43);
    c = layer(T_FLOWER_YELLOW); cross(c, stem, {246, 214, 52}, 1, s + 44);
    c = layer(T_TALL_GRASS); cross(c, Vec3(110, 180, 80) * (0.55f + tint.y * 0.5f), {}, 2, s + 45);
    c = layer(T_DEAD_BUSH); cross(c, {120, 84, 44}, {}, 3, s + 46);
    c = layer(T_MUSHROOM_RED); cross(c, {}, {200, 36, 36}, 4, s + 47);
    c = layer(T_DIAMOND); fillNoise(c, {90, 230, 220}, 18, s + 48);
    c = layer(T_GOLD); fillNoise(c, {250, 208, 64}, 16, s + 49);
    return data;
}

Vec3 averageLayerColor(const std::vector<uint8_t>& layers, int layerIndex) {
    const size_t base = static_cast<size_t>(layerIndex) * kTexSize * kTexSize * 4;
    if (base + kTexSize * kTexSize * 4 > layers.size()) return {0.5f, 0.5f, 0.5f};
    float r = 0, g = 0, b = 0, n = 0;
    for (int i = 0; i < kTexSize * kTexSize; ++i) {
        const uint8_t* p = &layers[base + i * 4];
        if (p[3] < 128) continue;
        r += p[0]; g += p[1]; b += p[2]; n += 1;
    }
    if (n < 1) return {0.5f, 0.5f, 0.5f};
    return {r / n / 255.0f, g / n / 255.0f, b / n / 255.0f};
}

}  // namespace zz
