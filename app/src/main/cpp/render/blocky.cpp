#include "blocky.h"

#include <algorithm>
#include <cmath>
#include <functional>
#include <vector>

#include "../core/math.h"
#include "../core/random.h"
#include "renderer.h"

namespace zz {

namespace {

struct Rgb {
    int r, g, b;
};

// Paletas (espelhadas em Look.kt para os ícones e o editor).
const Rgb kSkin[8] = {{255, 224, 196}, {241, 194, 160}, {224, 172, 128}, {198, 140, 98},
                      {161, 108, 72},  {120, 78, 52},   {84, 54, 36},    {120, 170, 96}};
const Rgb kZombieSkin[8] = {{110, 160, 90}, {96, 148, 104}, {124, 150, 96}, {104, 132, 110},
                            {128, 164, 84}, {90, 128, 92},  {116, 140, 120}, {98, 156, 80}};
const Rgb kHair[8] = {{34, 28, 26}, {92, 58, 34}, {222, 186, 96}, {178, 74, 38},
                      {180, 180, 184}, {58, 104, 214}, {232, 104, 170}, {80, 180, 80}};
const Rgb kEyes[4] = {{92, 58, 34}, {64, 118, 224}, {70, 160, 80}, {120, 124, 132}};
const Rgb kTop[16] = {{198, 52, 46},  {232, 124, 40}, {236, 196, 56}, {84, 170, 62},  {46, 98, 52},  {48, 176, 196},
                      {56, 108, 206}, {32, 46, 98},   {124, 64, 176}, {226, 110, 170}, {232, 232, 232}, {120, 124, 130},
                      {36, 38, 44},   {110, 72, 44},  {214, 192, 150}, {232, 186, 48}};
const Rgb kPants[8] = {{58, 84, 140}, {34, 36, 42}, {176, 150, 102}, {84, 96, 60},
                       {110, 112, 118}, {150, 40, 40}, {220, 220, 224}, {92, 104, 70}};
const Rgb kShoes[4] = {{92, 60, 36}, {30, 30, 34}, {226, 226, 226}, {180, 40, 40}};

constexpr int kTex = 64;

Rgb mul(Rgb c, float f) { return {static_cast<int>(c.r * f), static_cast<int>(c.g * f), static_cast<int>(c.b * f)}; }

struct Rect {
    int x, y, w, h;
};

// Faces de uma caixa no layout de pele do Minecraft (64x64).
struct Faces {
    Rect top, bottom, right, front, left, back;
};

Faces layout(int u, int v, int w, int h, int d) {
    Faces f;
    f.top = {u + d, v, w, d};
    f.bottom = {u + d + w, v, w, d};
    f.right = {u, v + d, d, h};
    f.front = {u + d, v + d, w, h};
    f.left = {u + d + w, v + d, d, h};
    f.back = {u + d + w + d, v + d, w, h};
    return f;
}

const Faces kHeadUv = layout(0, 0, 8, 8, 8);
const Faces kBodyUv = layout(16, 16, 8, 12, 4);
const Faces kArmRUv = layout(40, 16, 4, 12, 4);
const Faces kLegRUv = layout(0, 16, 4, 12, 4);
const Faces kArmLUv = layout(32, 48, 4, 12, 4);
const Faces kLegLUv = layout(16, 48, 4, 12, 4);

// Cores sólidas das peças extras (cabelo volumoso, chapéus): blocos 4x4 em x 32..63, y 0..7
// (blocos inteiros para as cores não se misturarem nas versões reduzidas da textura).
enum PaletteSlot { P_HAIR = 0, P_HAIR_DARK, P_HAT, P_HAT_DARK, P_OLIVE, P_OLIVE_DARK, P_BLACK, P_LENS, P_GOLD, P_GOLD_DARK, P_RED, P_WHITE, P_GEM, P_GREY };

struct Painter {
    Image& img;
    uint32_t seed;
    bool zombie;

    void set(int x, int y, Rgb c, float shade = 1.0f, bool noise = true) {
        if (x < 0 || y < 0 || x >= kTex || y >= kTex) return;
        float n = noise ? 0.93f + 0.12f * hashFloat(x, y, seed) : 1.0f;
        uint8_t* p = &img.rgba[static_cast<size_t>(y * kTex + x) * 4];
        p[0] = static_cast<uint8_t>(clampf(c.r * shade * n, 0, 255));
        p[1] = static_cast<uint8_t>(clampf(c.g * shade * n, 0, 255));
        p[2] = static_cast<uint8_t>(clampf(c.b * shade * n, 0, 255));
        p[3] = 255;
    }
    void fill(const Rect& r, Rgb c, float shade = 1.0f) {
        for (int y = 0; y < r.h; ++y)
            for (int x = 0; x < r.w; ++x) set(r.x + x, r.y + y, c, shade);
    }
    // Pinta as linhas [0, rows) de uma face lateral.
    void rows(const Rect& r, int rows, Rgb c, float shade = 1.0f) {
        for (int y = 0; y < std::min(rows, r.h); ++y)
            for (int x = 0; x < r.w; ++x) set(r.x + x, r.y + y, c, shade);
    }
    void at(const Rect& r, int x, int y, Rgb c, float shade = 1.0f) {
        if (x >= 0 && y >= 0 && x < r.w && y < r.h) set(r.x + x, r.y + y, c, shade);
    }
    void palette(int slot, Rgb c) {
        int x0 = 32 + (slot % 8) * 4, y0 = (slot / 8) * 4;
        for (int y = 0; y < 4; ++y)
            for (int x = 0; x < 4; ++x) set(x0 + x, y0 + y, c, 1.0f, false);
    }
    // Pixels vazios copiam a cor do vizinho: as bordas não escurecem ao reduzir a textura.
    void dilate() {
        for (int pass = 0; pass < 4; ++pass) {
            std::vector<uint8_t> src = img.rgba;
            for (int y = 0; y < kTex; ++y)
                for (int x = 0; x < kTex; ++x) {
                    uint8_t* p = &img.rgba[static_cast<size_t>(y * kTex + x) * 4];
                    if (p[3] != 0) continue;
                    static const int nb[4][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (const auto& d : nb) {
                        int nx = x + d[0], ny = y + d[1];
                        if (nx < 0 || ny < 0 || nx >= kTex || ny >= kTex) continue;
                        const uint8_t* q = &src[static_cast<size_t>(ny * kTex + nx) * 4];
                        if (q[3] == 0) continue;
                        p[0] = q[0]; p[1] = q[1]; p[2] = q[2]; p[3] = 1;  // 1: preenchido (não é pintura)
                        break;
                    }
                }
        }
        for (size_t i = 3; i < img.rgba.size(); i += 4) img.rgba[i] = 255;
    }
    // Roupa rasgada dos zumbis: alguns pixels mostram a pele ou manchas.
    void tear(const Rect& r, Rgb skin, float chance) {
        for (int y = 0; y < r.h; ++y)
            for (int x = 0; x < r.w; ++x) {
                float h = hashFloat(r.x + x, r.y + y, seed + 911);
                if (h < chance) set(r.x + x, r.y + y, skin, 0.9f);
                else if (h < chance * 1.4f) set(r.x + x, r.y + y, {120, 26, 26}, 1.0f);
            }
    }
};

Rgb skinOf(const Look& l) { return l.zombie ? kZombieSkin[l.skin & 7] : kSkin[l.skin & 7]; }

void paintHead(Painter& P, const Look& l) {
    const Faces& f = kHeadUv;
    Rgb skin = skinOf(l);
    Rgb hair = l.zombie ? mul(kHair[l.hairColor & 7], 0.7f) : kHair[l.hairColor & 7];
    P.fill(f.top, skin, 1.05f);
    P.fill(f.bottom, skin, 0.85f);
    P.fill(f.front, skin);
    P.fill(f.back, skin, 0.92f);
    P.fill(f.right, skin, 0.95f);
    P.fill(f.left, skin, 0.95f);
    // Cabelo: quantas linhas cobre na frente, nas laterais e atrás.
    int front = 0, side = 0, back = 0;
    bool topHair = l.hairStyle != 0;
    switch (l.hairStyle) {
        case 1: front = 1; side = 2; back = 3; break;
        case 2: front = 1; side = 7; back = 8; break;
        case 3: topHair = false; break;
        case 4: front = 1; side = 2; back = 4; break;
        case 5: front = 2; side = 4; back = 7; break;
        case 6: front = 2; side = 3; back = 4; break;
        case 7: front = 1; side = 2; back = 3; break;
        default: break;
    }
    if (topHair) P.fill(f.top, hair);
    if (l.hairStyle == 3) {
        for (int y = 0; y < 8; ++y) {
            P.at(f.top, 3, y, hair);
            P.at(f.top, 4, y, hair);
        }
        P.at(f.front, 3, 0, hair);
        P.at(f.front, 4, 0, hair);
        P.at(f.back, 3, 0, hair);
        P.at(f.back, 4, 0, hair);
    }
    P.rows(f.front, front, hair);
    if (l.hairStyle == 6) {  // franja caída de lado
        for (int x = 0; x < 6; ++x) P.at(f.front, x, 1, hair);
        P.at(f.front, 0, 2, hair);
        P.at(f.front, 1, 2, hair);
    }
    P.rows(f.right, side, hair, 0.95f);
    P.rows(f.left, side, hair, 0.95f);
    P.rows(f.back, back, hair, 0.92f);
    // Olhos, sobrancelhas, nariz e boca.
    Rgb white = l.zombie ? Rgb{24, 22, 22} : Rgb{244, 244, 244};
    Rgb iris = l.zombie ? Rgb{150, 24, 24} : kEyes[l.eyes & 3];
    P.at(f.front, 1, 4, white, 1.0f);
    P.at(f.front, 2, 4, iris, 1.0f);
    P.at(f.front, 5, 4, iris, 1.0f);
    P.at(f.front, 6, 4, white, 1.0f);
    Rgb brow = l.hairStyle == 0 || l.hairStyle == 3 ? mul(skin, 0.7f) : mul(hair, 0.85f);
    for (int x : {1, 2, 5, 6}) P.at(f.front, x, 3, brow);
    P.at(f.front, 3, 5, skin, 0.86f);
    P.at(f.front, 4, 5, skin, 0.86f);
    Rgb mouth = l.zombie ? Rgb{40, 26, 26} : mul(skin, 0.58f);
    P.at(f.front, 3, 6, mouth);
    P.at(f.front, 4, 6, mouth);
    if (l.zombie) {
        P.at(f.front, 2, 6, mouth);
        P.at(f.front, 5, 6, mouth);
        P.at(f.front, 6, 7, {120, 26, 26});
        P.at(f.front, 1, 2, skin, 0.75f);
    }
    switch (l.face) {
        case 1:  // barba
            for (int y = 5; y < 8; ++y)
                for (int x = 0; x < 8; ++x)
                    if (!(y == 6 && (x == 3 || x == 4))) P.at(f.front, x, y, hair, 0.9f);
            for (int y = 4; y < 8; ++y) {
                P.at(f.right, 6, y, hair, 0.9f);
                P.at(f.right, 7, y, hair, 0.9f);
                P.at(f.left, 0, y, hair, 0.9f);
                P.at(f.left, 1, y, hair, 0.9f);
            }
            break;
        case 2:  // bigode
            for (int x = 2; x < 6; ++x) P.at(f.front, x, 5, hair, 0.85f);
            break;
        case 3:  // cicatriz
            P.at(f.front, 6, 3, {170, 70, 70});
            P.at(f.front, 7, 5, {170, 70, 70});
            P.at(f.front, 6, 6, {170, 70, 70});
            P.at(f.front, 5, 7, {170, 70, 70});
            break;
        default: break;
    }
}

// Cor da roupa num pixel (padrões de xadrez e camuflado).
Rgb topPixel(const Look& l, int x, int y) {
    Rgb c = kTop[l.topColor & 15];
    if (l.top == 4) {
        bool a = x % 4 == 1, b = y % 4 == 1;
        if (a && b) return mul(c, 0.5f);
        if (a || b) return mul(c, 0.72f);
        if (x % 4 == 3 || y % 4 == 3) return mul(c, 1.12f);
    }
    return c;
}

void paintTopFace(Painter& P, const Look& l, const Rect& r, float shade) {
    for (int y = 0; y < r.h; ++y)
        for (int x = 0; x < r.w; ++x) P.set(r.x + x, r.y + y, topPixel(l, r.x + x, r.y + y), shade);
}

void paintBody(Painter& P, const Look& l) {
    const Faces& f = kBodyUv;
    Rgb skin = skinOf(l);
    Rgb c = kTop[l.topColor & 15];
    const Rect sides[] = {f.front, f.back, f.right, f.left, f.top, f.bottom};
    const float shades[] = {1.0f, 0.9f, 0.94f, 0.94f, 1.05f, 0.8f};
    switch (l.top) {
        case 5: {  // colete tático por cima de uma camisa escura
            Rgb shirt{54, 58, 64}, vest{96, 106, 66};
            for (int i = 0; i < 6; ++i) P.fill(sides[i], shirt, shades[i]);
            for (const Rect* r : {&f.front, &f.back})
                for (int y = 0; y < 11; ++y)
                    for (int x = 1; x < 7; ++x) P.at(*r, x, y, vest, r == &f.front ? 1.0f : 0.9f);
            for (int x : {1, 2, 5, 6})
                for (int y = 6; y < 9; ++y) P.at(f.front, x, y, vest, y == 6 ? 1.15f : 0.78f);
            for (int y = 0; y < 2; ++y) {
                P.at(f.front, 2, y, {28, 28, 30});
                P.at(f.front, 5, y, {28, 28, 30});
            }
            P.at(f.front, 5, 2, c);
            P.at(f.front, 6, 2, c);
            break;
        }
        case 6: {  // armadura de ferro com emblema na cor escolhida
            Rgb iron{172, 176, 184};
            for (int i = 0; i < 6; ++i) P.fill(sides[i], iron, shades[i]);
            for (const Rect* r : {&f.front, &f.back, &f.right, &f.left}) {
                P.rows(*r, 1, iron, 0.8f);
                for (int x = 0; x < r->w; ++x) {
                    P.at(*r, x, 5, iron, 0.8f);
                    P.at(*r, x, 10, iron, 0.8f);
                }
            }
            for (int x : {0, 7})
                for (int y : {1, 6}) P.at(f.front, x, y, {236, 236, 240});
            for (int y = 2; y < 4; ++y) {
                P.at(f.front, 3, y, c);
                P.at(f.front, 4, y, c);
            }
            break;
        }
        case 7: {  // jaleco branco com camisa e gravata
            Rgb coat{236, 236, 236};
            for (int i = 0; i < 6; ++i) P.fill(sides[i], coat, shades[i]);
            for (int y = 0; y < 4; ++y) {
                P.at(f.front, 3, y, c);
                P.at(f.front, 4, y, c);
            }
            for (int y = 1; y < 7; ++y) P.at(f.front, y < 4 ? 4 : 3, y, mul(c, 0.55f));
            P.at(f.front, 1, 4, {170, 170, 176});
            P.at(f.front, 2, 4, {170, 170, 176});
            P.at(f.front, 4, 8, {150, 150, 156});
            P.at(f.front, 4, 10, {150, 150, 156});
            break;
        }
        default: {
            for (int i = 0; i < 6; ++i) paintTopFace(P, l, sides[i], shades[i]);
            if (l.top == 1) {  // regata: braços e ombros de fora
                for (int y = 0; y < 3; ++y) {
                    for (int x : {0, 1, 6, 7}) {
                        P.at(f.front, x, y, skin);
                        P.at(f.back, x, y, skin, 0.92f);
                    }
                    P.rows(f.right, 3, skin, 0.95f);
                    P.rows(f.left, 3, skin, 0.95f);
                }
                for (int y = 0; y < 4; ++y)
                    for (int x : {0, 1, 6, 7}) P.at(f.top, x, y, skin, 1.05f);
            }
            if (l.top == 2) {  // jaqueta aberta com camisa clara
                for (int y = 0; y < 11; ++y) {
                    P.at(f.front, 3, y, {220, 220, 214});
                    P.at(f.front, 4, y, {220, 220, 214});
                }
                for (int x : {1, 2, 5, 6}) {
                    P.at(f.front, x, 0, c, 1.25f);
                    P.at(f.front, x, 8, c, 0.72f);
                }
            }
            if (l.top == 3) {  // moletom com bolso e cordão
                for (int y = 7; y < 10; ++y)
                    for (int x = 1; x < 7; ++x) P.at(f.front, x, y, c, 0.8f);
                for (int y = 1; y < 4; ++y) {
                    P.at(f.front, 3, y, {236, 236, 236});
                    P.at(f.front, 4, y, {236, 236, 236});
                }
                P.rows(f.back, 3, c, 0.78f);
            }
            if (l.top != 1) {
                P.at(f.front, 3, 0, skin);
                P.at(f.front, 4, 0, skin);
            }
            break;
        }
    }
    // Cinto.
    if (l.top != 7) {
        Rgb belt = l.top == 6 ? Rgb{90, 90, 96} : Rgb{64, 44, 28};
        for (const Rect* r : {&f.front, &f.back, &f.right, &f.left})
            for (int x = 0; x < r->w; ++x) P.at(*r, x, 11, belt);
        if (l.top != 6) {
            P.at(f.front, 3, 11, {200, 170, 80});
            P.at(f.front, 4, 11, {200, 170, 80});
        }
    }
    if (l.zombie) {
        for (const Rect* r : {&f.front, &f.back, &f.right, &f.left}) P.tear(*r, skin, 0.12f);
    }
}

void paintArm(Painter& P, const Look& l, const Faces& f) {
    Rgb skin = skinOf(l);
    for (const Rect* r : {&f.front, &f.back, &f.right, &f.left, &f.top, &f.bottom}) P.fill(*r, skin, r == &f.bottom ? 0.85f : 0.96f);
    int sleeve = 11;
    Rgb c = kTop[l.topColor & 15];
    bool pattern = l.top == 4;
    switch (l.top) {
        case 0: sleeve = 4; break;
        case 1: sleeve = 0; break;
        case 5: c = {54, 58, 64}; break;
        case 6: c = {172, 176, 184}; break;
        case 7: c = {236, 236, 236}; break;
        default: break;
    }
    if (l.top == 2) c = mul(c, 0.9f);
    for (const Rect* r : {&f.front, &f.back, &f.right, &f.left}) {
        for (int y = 0; y < std::min(sleeve, r->h); ++y)
            for (int x = 0; x < r->w; ++x) P.set(r->x + x, r->y + y, pattern ? topPixel(l, r->x + x, r->y + y) : c, r == &f.back ? 0.9f : 0.96f);
        if (l.top == 0) for (int x = 0; x < r->w; ++x) P.at(*r, x, 3, c, 0.78f);
        if (l.top == 6)  // manopla de ferro
            for (int y = 8; y < 12; ++y)
                for (int x = 0; x < r->w; ++x) P.at(*r, x, y, {150, 154, 162});
    }
    if (sleeve > 0) {
        for (int y = 0; y < f.top.h; ++y)
            for (int x = 0; x < f.top.w; ++x) P.set(f.top.x + x, f.top.y + y, pattern ? topPixel(l, f.top.x + x, f.top.y + y) : c, 1.05f);
    }
    if (l.zombie) {
        for (const Rect* r : {&f.front, &f.back, &f.right, &f.left}) P.tear(*r, skin, 0.15f);
    }
}

Rgb pantsPixel(const Look& l, int x, int y, uint32_t seed) {
    Rgb c = kPants[l.pantsColor & 7];
    if ((l.pantsColor & 7) == 7) {  // camuflado
        float n = fbm(x * 0.45f, y * 0.45f, seed + 33, 2);
        if (n < 0.42f) return {70, 80, 52};
        if (n < 0.5f) return {120, 110, 80};
        if (n > 0.6f) return {54, 58, 40};
    }
    return c;
}

void paintLeg(Painter& P, const Look& l, const Faces& f) {
    Rgb skin = skinOf(l);
    Rgb shoe = kShoes[l.shoes & 3];
    for (const Rect* r : {&f.front, &f.back, &f.right, &f.left}) {
        float shade = r == &f.back ? 0.88f : (r == &f.front ? 1.0f : 0.94f);
        for (int y = 0; y < r->h; ++y)
            for (int x = 0; x < r->w; ++x) {
                Rgb c;
                if (y >= 10) c = y == 10 ? mul(shoe, 1.12f) : shoe;
                else if (l.shorts && y >= 6) c = skin;
                else c = pantsPixel(l, r->x + x, r->y + y, P.seed);
                P.set(r->x + x, r->y + y, c, shade);
            }
    }
    for (int y = 0; y < f.top.h; ++y)
        for (int x = 0; x < f.top.w; ++x) P.set(f.top.x + x, f.top.y + y, pantsPixel(l, f.top.x + x, f.top.y + y, P.seed), 1.05f);
    P.fill(f.bottom, shoe, 0.7f);
    if (l.zombie) {
        for (const Rect* r : {&f.front, &f.back, &f.right, &f.left}) {
            Rect cloth{r->x, r->y, r->w, 10};
            P.tear(cloth, skin, 0.1f);
        }
    }
}

// ----------------------------------------------------------------------------- malhas

struct MeshBuilder {
    ModelMesh mesh;
    float px = kBlockyPixel;

    void quad(const Vec3 p[4], const Vec3& n, float u0, float v0, float u1, float v1) {
        const float uv[4][2] = {{u0, v1}, {u1, v1}, {u1, v0}, {u0, v0}};
        uint16_t base = static_cast<uint16_t>(mesh.vertices.size() / 8);
        for (int i = 0; i < 4; ++i) {
            mesh.vertices.insert(mesh.vertices.end(), {p[i].x * px, p[i].y * px, p[i].z * px, n.x, n.y, n.z, uv[i][0], uv[i][1]});
        }
        mesh.indices.insert(mesh.indices.end(), {base, static_cast<uint16_t>(base + 1), static_cast<uint16_t>(base + 2), base,
                                                 static_cast<uint16_t>(base + 2), static_cast<uint16_t>(base + 3)});
    }

    // Caixa com as faces mapeadas no layout de pele (coordenadas em pixels do personagem).
    void box(const Vec3& a, const Vec3& b, const Faces& f) {
        auto uvq = [&](const Vec3 p[4], const Vec3& n, const Rect& r) {
            quad(p, n, r.x / static_cast<float>(kTex), r.y / static_cast<float>(kTex), (r.x + r.w) / static_cast<float>(kTex), (r.y + r.h) / static_cast<float>(kTex));
        };
        boxFaces(a, b, [&](int face, const Vec3 p[4], const Vec3& n) {
            const Rect* rects[6] = {&f.front, &f.back, &f.right, &f.left, &f.top, &f.bottom};
            uvq(p, n, *rects[face]);
        });
    }

    // Caixa de cor sólida (pixel da paleta).
    void solid(const Vec3& a, const Vec3& b, int slot) {
        float u = (32 + (slot % 8) * 4 + 2.0f) / kTex, v = ((slot / 8) * 4 + 2.0f) / kTex;
        boxFaces(a, b, [&](int, const Vec3 p[4], const Vec3& n) { quad(p, n, u, v, u, v); });
    }

    template <typename F>
    void boxFaces(const Vec3& a, const Vec3& b, F&& emit) {
        // Cantos em ordem anti-horária vistos de fora: inferior esquerdo, inferior direito, superior direito, superior esquerdo.
        const Vec3 front[4] = {{a.x, a.y, b.z}, {b.x, a.y, b.z}, {b.x, b.y, b.z}, {a.x, b.y, b.z}};
        const Vec3 back[4] = {{b.x, a.y, a.z}, {a.x, a.y, a.z}, {a.x, b.y, a.z}, {b.x, b.y, a.z}};
        const Vec3 right[4] = {{a.x, a.y, a.z}, {a.x, a.y, b.z}, {a.x, b.y, b.z}, {a.x, b.y, a.z}};
        const Vec3 left[4] = {{b.x, a.y, b.z}, {b.x, a.y, a.z}, {b.x, b.y, a.z}, {b.x, b.y, b.z}};
        const Vec3 top[4] = {{a.x, b.y, b.z}, {b.x, b.y, b.z}, {b.x, b.y, a.z}, {a.x, b.y, a.z}};
        const Vec3 bottom[4] = {{a.x, a.y, a.z}, {b.x, a.y, a.z}, {b.x, a.y, b.z}, {a.x, a.y, b.z}};
        emit(0, front, Vec3(0, 0, 1));
        emit(1, back, Vec3(0, 0, -1));
        emit(2, right, Vec3(-1, 0, 0));
        emit(3, left, Vec3(1, 0, 0));
        emit(4, top, Vec3(0, 1, 0));
        emit(5, bottom, Vec3(0, -1, 0));
    }
};

// Cabelo com volume (fora da cabeça) — coordenadas locais da cabeça, em pixels.
bool buildHair(const Look& l, MeshBuilder& m) {
    switch (l.hairStyle) {
        case 2: m.solid({-4.2f, -3.0f, -4.6f}, {4.2f, 3.0f, -3.9f}, P_HAIR); return true;     // longo caindo nas costas
        case 3: m.solid({-1.0f, 8.0f, -4.2f}, {1.0f, 10.0f, 4.2f}, P_HAIR); return true;      // moicano
        case 4: m.solid({-1.0f, 1.0f, -5.8f}, {1.0f, 5.5f, -4.0f}, P_HAIR_DARK); return true;  // rabo de cavalo
        case 5: m.solid({-5.0f, 5.0f, -5.0f}, {5.0f, 10.0f, 4.6f}, P_HAIR); return true;      // black power
        case 7: m.solid({-1.6f, 8.0f, -3.4f}, {1.6f, 10.2f, -0.2f}, P_HAIR_DARK); return true; // coque
        default: return false;
    }
}

bool buildHat(const Look& l, MeshBuilder& m) {
    switch (l.hat) {
        case 1:  // boné
            m.solid({-4.25f, 6.4f, -4.25f}, {4.25f, 8.7f, 4.25f}, P_HAT);
            m.solid({-4.0f, 6.4f, 4.25f}, {4.0f, 7.0f, 7.6f}, P_HAT_DARK);
            return true;
        case 2:  // capacete militar
            m.solid({-4.6f, 5.0f, -4.6f}, {4.6f, 9.2f, 4.6f}, P_OLIVE);
            m.solid({-4.8f, 5.0f, -4.8f}, {4.8f, 5.6f, 4.8f}, P_OLIVE_DARK);
            return true;
        case 3:  // gorro com pompom
            m.solid({-4.3f, 5.6f, -4.3f}, {4.3f, 9.4f, 4.3f}, P_HAT);
            m.solid({-4.4f, 5.4f, -4.4f}, {4.4f, 6.4f, 4.4f}, P_WHITE);
            m.solid({-1.0f, 9.4f, -1.0f}, {1.0f, 11.0f, 1.0f}, P_WHITE);
            return true;
        case 4:  // faixa ninja
            m.solid({-4.3f, 5.2f, -4.3f}, {4.3f, 6.4f, 4.3f}, P_RED);
            m.solid({-0.6f, 4.0f, -6.5f}, {0.6f, 6.0f, -4.3f}, P_RED);
            return true;
        case 5:  // óculos táticos
            m.solid({-4.2f, 3.0f, 4.0f}, {4.2f, 4.8f, 4.5f}, P_BLACK);
            m.solid({-3.4f, 3.3f, 4.45f}, {-0.6f, 4.5f, 4.6f}, P_LENS);
            m.solid({0.6f, 3.3f, 4.45f}, {3.4f, 4.5f, 4.6f}, P_LENS);
            return true;
        case 6:  // fone gamer
            m.solid({-4.6f, 8.0f, -0.7f}, {4.6f, 8.9f, 0.7f}, P_BLACK);
            m.solid({-5.2f, 2.5f, -1.6f}, {-4.0f, 5.6f, 1.6f}, P_HAT);
            m.solid({4.0f, 2.5f, -1.6f}, {5.2f, 5.6f, 1.6f}, P_HAT);
            return true;
        case 7:  // coroa
            m.solid({-4.1f, 8.0f, -4.1f}, {4.1f, 9.3f, 4.1f}, P_GOLD);
            for (float x : {-3.6f, -0.7f, 2.2f}) {
                m.solid({x, 9.3f, 3.0f}, {x + 1.4f, 10.8f, 4.1f}, P_GOLD);
                m.solid({x, 9.3f, -4.1f}, {x + 1.4f, 10.8f, -3.0f}, P_GOLD_DARK);
            }
            m.solid({-0.6f, 8.3f, 4.1f}, {0.6f, 9.0f, 4.3f}, P_GEM);
            return true;
        default:
            return false;
    }
}

// ----------------------------------------------------------------------------- animações

enum NodeId { N_ROOT = 0, N_TORSO, N_HEAD, N_ARM_R, N_ARM_L, N_LEG_R, N_LEG_L, N_COUNT };

struct KeyPose {
    Quat r[N_COUNT];
    Vec3 rootT;
};

Quat rx(float a) { return Quat::axisAngle({1, 0, 0}, a); }
Quat ry(float a) { return Quat::axisAngle({0, 1, 0}, a); }
Quat rz(float a) { return Quat::axisAngle({0, 0, 1}, a); }

AnimClip makeClip(const char* name, float duration, const std::function<void(float, KeyPose&)>& fn) {
    AnimClip clip;
    clip.name = name;
    clip.duration = duration;
    const int keys = std::max(2, static_cast<int>(duration * 24.0f) + 1);
    std::vector<AnimChannel> rot(N_COUNT);
    AnimChannel trans;
    trans.node = N_ROOT;
    trans.path = 0;
    for (int n = 0; n < N_COUNT; ++n) {
        rot[static_cast<size_t>(n)].node = n;
        rot[static_cast<size_t>(n)].path = 1;
    }
    for (int k = 0; k < keys; ++k) {
        float t = duration * k / (keys - 1);
        KeyPose p;
        fn(t, p);
        for (int n = 0; n < N_COUNT; ++n) {
            AnimChannel& c = rot[static_cast<size_t>(n)];
            c.times.push_back(t);
            c.values.insert(c.values.end(), {p.r[n].x, p.r[n].y, p.r[n].z, p.r[n].w});
        }
        trans.times.push_back(t);
        trans.values.insert(trans.values.end(), {p.rootT.x, p.rootT.y, p.rootT.z});
    }
    for (auto& c : rot) clip.channels.push_back(std::move(c));
    clip.channels.push_back(std::move(trans));
    return clip;
}

std::vector<AnimClip> buildClips() {
    const float P = kBlockyPixel;
    std::vector<AnimClip> clips;
    // Parado: respiração e braços balançando de leve.
    clips.push_back(makeClip("idle", 3.2f, [](float t, KeyPose& p) {
        float b = std::sin(t / 3.2f * kTwoPi);
        p.r[N_ARM_R] = rz(-0.05f - 0.025f * b) * rx(0.04f * b);
        p.r[N_ARM_L] = rz(0.05f + 0.025f * b) * rx(-0.04f * b);
        p.r[N_HEAD] = rx(0.025f * std::sin(t / 3.2f * kPi * 2.0f + 1.0f));
    }));
    // Andar: pernas e braços alternados, tronco girando um pouco.
    clips.push_back(makeClip("walk", 1.0f, [P](float t, KeyPose& p) {
        float s = std::sin(t * kTwoPi);
        p.r[N_LEG_R] = rx(0.62f * s);
        p.r[N_LEG_L] = rx(-0.62f * s);
        p.r[N_ARM_R] = rx(-0.55f * s) * rz(-0.04f);
        p.r[N_ARM_L] = rx(0.55f * s) * rz(0.04f);
        p.r[N_TORSO] = ry(0.07f * s);
        p.r[N_HEAD] = ry(-0.05f * s);
        p.rootT = {0, -0.35f * P * std::fabs(s), 0};
    }));
    // Recuar mantendo a mira: passada invertida e tronco ligeiramente para trás.
    clips.push_back(makeClip("walk-back", 1.0f, [P](float t, KeyPose& p) {
        float s = std::sin(t * kTwoPi);
        p.r[N_LEG_R] = rx(-0.48f * s);
        p.r[N_LEG_L] = rx(0.48f * s);
        p.r[N_ARM_R] = rx(0.2f * s) * rz(-0.03f);
        p.r[N_ARM_L] = rx(-0.2f * s) * rz(0.03f);
        p.r[N_TORSO] = rx(-0.08f) * ry(-0.05f * s);
        p.r[N_HEAD] = rx(0.04f) * ry(0.04f * s);
        p.rootT = {0, -0.22f * P * std::fabs(s), 0};
    }));
    // Variações procedurais para recuar correndo e deslocar-se agachado.
    clips.push_back(makeClip("run-back", 0.58f, [P](float t, KeyPose& p) {
        float s = std::sin(t / 0.58f * kTwoPi);
        p.r[N_LEG_R] = rx(-0.92f * s + 0.08f);
        p.r[N_LEG_L] = rx(0.92f * s + 0.08f);
        p.r[N_ARM_R] = rx(0.8f * s - 0.1f) * rz(-0.1f);
        p.r[N_ARM_L] = rx(-0.8f * s - 0.1f) * rz(0.1f);
        p.r[N_TORSO] = rx(-0.12f) * ry(-0.08f * s);
        p.r[N_HEAD] = rx(-0.16f) * ry(0.06f * s);
        p.rootT = {0, P * (0.7f * std::fabs(std::cos(t / 0.58f * kTwoPi)) - 0.4f), 0};
    }));
    clips.push_back(makeClip("crouch-walk", 1.1f, [P](float t, KeyPose& p) {
        float s = std::sin(t / 1.1f * kTwoPi);
        p.r[N_LEG_R] = rx(0.34f * s + 0.2f);
        p.r[N_LEG_L] = rx(-0.34f * s + 0.2f);
        p.r[N_ARM_R] = rx(-0.28f * s - 0.1f);
        p.r[N_ARM_L] = rx(0.28f * s - 0.1f);
        p.r[N_TORSO] = rx(0.12f) * ry(0.04f * s);
        p.r[N_HEAD] = rx(-0.08f) * ry(-0.03f * s);
        p.rootT = {0, -0.45f * P - 0.12f * P * std::fabs(s), 0};
    }));
    clips.push_back(makeClip("crouch-back", 1.1f, [P](float t, KeyPose& p) {
        float s = std::sin(t / 1.1f * kTwoPi);
        p.r[N_LEG_R] = rx(-0.3f * s + 0.2f);
        p.r[N_LEG_L] = rx(0.3f * s + 0.2f);
        p.r[N_ARM_R] = rx(0.1f * s - 0.08f);
        p.r[N_ARM_L] = rx(-0.1f * s - 0.08f);
        p.r[N_TORSO] = rx(-0.08f) * ry(-0.04f * s);
        p.r[N_HEAD] = rx(-0.06f) * ry(0.03f * s);
        p.rootT = {0, -0.45f * P - 0.1f * P * std::fabs(s), 0};
    }));
    // Faca à frente: passada compacta e mão de ataque sempre pronta.
    clips.push_back(makeClip("knife-walk", 0.8f, [P](float t, KeyPose& p) {
        float s = std::sin(t / 0.8f * kTwoPi);
        p.r[N_LEG_R] = rx(0.5f * s);
        p.r[N_LEG_L] = rx(-0.5f * s);
        p.r[N_ARM_R] = rz(-0.3f) * rx(-1.0f + 0.08f * s);
        p.r[N_ARM_L] = rz(0.08f) * rx(0.35f * s - 0.18f);
        p.r[N_TORSO] = ry(0.08f * s);
        p.rootT = {0, -0.25f * P * std::fabs(s), 0};
    }));
    clips.push_back(makeClip("knife-back", 0.8f, [P](float t, KeyPose& p) {
        float s = std::sin(t / 0.8f * kTwoPi);
        p.r[N_LEG_R] = rx(-0.42f * s);
        p.r[N_LEG_L] = rx(0.42f * s);
        p.r[N_ARM_R] = rz(-0.3f) * rx(-0.95f + 0.06f * s);
        p.r[N_ARM_L] = rz(0.08f) * rx(-0.25f * s - 0.18f);
        p.r[N_TORSO] = rx(-0.06f) * ry(-0.05f * s);
        p.rootT = {0, -0.2f * P * std::fabs(s), 0};
    }));
    clips.push_back(makeClip("knife-sprint", 0.62f, [P](float t, KeyPose& p) {
        float s = std::sin(t / 0.62f * kTwoPi);
        p.r[N_LEG_R] = rx(0.86f * s - 0.1f);
        p.r[N_LEG_L] = rx(-0.86f * s - 0.1f);
        p.r[N_ARM_R] = rz(-0.36f) * rx(-0.9f + 0.1f * s);
        p.r[N_ARM_L] = rx(0.65f * s - 0.25f);
        p.r[N_TORSO] = rx(0.2f) * ry(0.1f * s);
        p.r[N_HEAD] = rx(-0.16f);
        p.rootT = {0, P * (0.8f * std::fabs(std::cos(t / 0.62f * kTwoPi)) - 0.4f), 0};
    }));
    // Passos laterais curtos: quadril e ombros compensam sem girar o personagem.
    for (int side : {-1, 1}) {
        const char* name = side < 0 ? "strafe-left" : "strafe-right";
        clips.push_back(makeClip(name, 0.9f, [P, side](float t, KeyPose& p) {
            float ph = t / 0.9f * kTwoPi;
            float s = std::sin(ph);
            float liftR = std::max(0.0f, s), liftL = std::max(0.0f, -s);
            p.r[N_LEG_R] = rx(0.34f * liftR) * ry(side * 0.12f);
            p.r[N_LEG_L] = rx(0.34f * liftL) * ry(-side * 0.12f);
            p.r[N_ARM_R] = rx(-0.18f * s) * rz(-side * 0.05f);
            p.r[N_ARM_L] = rx(0.18f * s) * rz(side * 0.05f);
            p.r[N_TORSO] = rz(-side * 0.1f * s) * ry(side * 0.06f);
            p.r[N_HEAD] = rz(side * 0.025f * s);
            p.rootT = {side * 0.12f * P * s, 0.12f * P * std::fabs(s), 0};
        }));
    }
    // Correr: passadas largas, tronco inclinado para a frente, braços fortes e quique do corpo.
    clips.push_back(makeClip("sprint", 0.62f, [P](float t, KeyPose& p) {
        float ph = t / 0.62f * kTwoPi;
        float s = std::sin(ph);
        p.r[N_LEG_R] = rx(0.98f * s - 0.12f);
        p.r[N_LEG_L] = rx(-0.98f * s - 0.12f);
        p.r[N_ARM_R] = rx(-1.15f * s - 0.3f) * rz(-0.14f);
        p.r[N_ARM_L] = rx(1.15f * s - 0.3f) * rz(0.14f);
        p.r[N_TORSO] = rx(0.3f) * ry(0.12f * s);
        p.r[N_HEAD] = rx(-0.24f) * ry(-0.08f * s);
        p.rootT = {0, P * (1.1f * std::fabs(std::cos(ph)) - 0.6f), 0};
    }));
    // Morrer: cai de costas com os braços abertos.
    clips.push_back(makeClip("die", 1.1f, [P](float t, KeyPose& p) {
        float k = smoothstepf(0.0f, 0.62f, t);
        float lift = std::sin(std::min(1.0f, t / 0.62f) * kPi) * 1.2f * P;
        p.r[N_ROOT] = rx(-1.48f * k);
        p.r[N_ARM_R] = rz(-0.9f * k) * rx(-0.6f * k);
        p.r[N_ARM_L] = rz(0.9f * k) * rx(-0.6f * k);
        p.r[N_LEG_R] = rx(-0.25f * k) * rz(-0.12f * k);
        p.r[N_LEG_L] = rx(0.1f * k) * rz(0.12f * k);
        p.r[N_HEAD] = rx(0.35f * k);
        p.rootT = {0, lift, 0};
    }));
    // Golpe dos zumbis: tronco gira e o braço desce num tapa.
    clips.push_back(makeClip("attack-melee-right", 0.6f, [](float t, KeyPose& p) {
        float k = t / 0.6f;
        float wind = smoothstepf(0.0f, 0.35f, k), strike = smoothstepf(0.35f, 0.6f, k), back = smoothstepf(0.7f, 1.0f, k);
        p.r[N_ARM_R] = rz(-0.2f) * rx(lerpf(-2.6f * wind, -0.5f, strike) * (1.0f - back));
        p.r[N_ARM_L] = rx(-0.4f);
        p.r[N_TORSO] = ry((-0.35f * wind + 0.6f * strike) * (1.0f - back));
    }));
    // Gestos do lobby.
    clips.push_back(makeClip("emote-yes", 1.2f, [](float t, KeyPose& p) {
        float env = std::sin(std::min(1.0f, t / 1.2f) * kPi);
        p.r[N_HEAD] = rx(0.32f * std::sin(t / 0.3f * kPi) * env);
        p.r[N_ARM_R] = rz(-0.35f * env) * rx(-2.7f * env + 0.25f * std::sin(t * 18.0f) * env);
        p.r[N_ARM_L] = rz(0.05f);
    }));
    clips.push_back(makeClip("interact-right", 0.9f, [](float t, KeyPose& p) {
        float env = std::sin(std::min(1.0f, t / 0.9f) * kPi);
        p.r[N_ARM_R] = rx(-1.35f * env);
        p.r[N_HEAD] = rx(0.2f * env);
    }));
    clips.push_back(makeClip("pick-up", 1.0f, [](float t, KeyPose& p) {
        float env = std::sin(std::min(1.0f, t) * kPi);
        p.r[N_TORSO] = rx(0.75f * env);
        p.r[N_HEAD] = rx(-0.2f * env);
        p.r[N_ARM_R] = rx(-0.7f * env);
        p.r[N_ARM_L] = rx(-0.7f * env);
    }));
    // Acenar: o braço direito sobe ao lado da cabeça e a mão balança algumas vezes.
    clips.push_back(makeClip("wave", 2.8f, [](float t, KeyPose& p) {
        float up = smoothstepf(0.0f, 0.35f, t) * (1.0f - smoothstepf(2.35f, 2.8f, t));
        float wave = std::sin((t - 0.35f) * kTwoPi * 2.2f) * smoothstepf(0.2f, 0.5f, t);
        p.r[N_ARM_R] = rz((-2.5f + 0.4f * wave) * up) * rx(-0.15f * up);
        p.r[N_ARM_L] = rz(0.06f);
        p.r[N_TORSO] = rz(0.05f * up);
        p.r[N_HEAD] = rz(-0.1f * up) * ry(0.08f * wave * up);
    }));
    // Dançar: quique no ritmo; primeiro braços alternados e chutes curtos, depois braços para o alto
    // balançando de um lado para o outro com o quadril.
    clips.push_back(makeClip("dance", 6.4f, [P](float t, KeyPose& p) {
        float env = smoothstepf(0.0f, 0.3f, t) * (1.0f - smoothstepf(6.0f, 6.4f, t));
        float hop = std::fabs(std::sin(t / 0.4f * kPi));  // uma batida a cada 0,4 s
        float s = std::sin(t / 0.8f * kTwoPi);           // lado a lado a cada duas batidas
        float m = smoothstepf(3.0f, 3.4f, t);             // segunda parte da coreografia
        p.r[N_ARM_R] = rz(lerpf(-0.25f, -2.7f + 0.35f * s, m) * env) * rx(lerpf(-1.2f - 0.8f * s, 0.0f, m) * env);
        p.r[N_ARM_L] = rz(lerpf(0.25f, 2.7f + 0.35f * s, m) * env) * rx(lerpf(-1.2f + 0.8f * s, 0.0f, m) * env);
        p.r[N_LEG_R] = rx(lerpf(-0.45f * std::max(0.0f, s), 0.12f * s, m) * env);
        p.r[N_LEG_L] = rx(lerpf(-0.45f * std::max(0.0f, -s), -0.12f * s, m) * env);
        p.r[N_TORSO] = ry(lerpf(0.25f * s, 0.0f, m) * env) * rz(lerpf(0.0f, 0.12f * s, m) * env);
        p.r[N_HEAD] = rx(0.12f * hop * env) * ry(lerpf(-0.2f * s, 0.0f, m) * env);
        p.rootT = {lerpf(0.0f, 0.9f * P * s, m) * env, 0.6f * P * hop * env, 0};
    }));
    return clips;
}

}  // namespace

Look Look::decode(uint32_t c) {
    Look l;
    l.skin = static_cast<int>(c & 7u);
    l.hairStyle = static_cast<int>((c >> 3) & 7u);
    l.hairColor = static_cast<int>((c >> 6) & 7u);
    l.eyes = static_cast<int>((c >> 9) & 3u);
    l.face = static_cast<int>((c >> 11) & 3u);
    l.top = static_cast<int>((c >> 13) & 7u);
    l.topColor = static_cast<int>((c >> 16) & 15u);
    l.pantsColor = static_cast<int>((c >> 20) & 7u);
    l.shoes = static_cast<int>((c >> 23) & 3u);
    l.hat = static_cast<int>((c >> 25) & 7u);
    l.shorts = ((c >> 28) & 1u) != 0;
    l.zombie = (c & kLookZombieBit) != 0;
    return l;
}

uint32_t Look::encode() const {
    uint32_t c = static_cast<uint32_t>(skin & 7) | (static_cast<uint32_t>(hairStyle & 7) << 3) | (static_cast<uint32_t>(hairColor & 7) << 6) |
                 (static_cast<uint32_t>(eyes & 3) << 9) | (static_cast<uint32_t>(face & 3) << 11) | (static_cast<uint32_t>(top & 7) << 13) |
                 (static_cast<uint32_t>(topColor & 15) << 16) | (static_cast<uint32_t>(pantsColor & 7) << 20) |
                 (static_cast<uint32_t>(shoes & 3) << 23) | (static_cast<uint32_t>(hat & 7) << 25) | (shorts ? 1u << 28 : 0u);
    return zombie ? (c | kLookZombieBit) : c;
}

uint32_t zombieLook(int variant, uint32_t seed) {
    Rng rng(hash2(variant, 77, seed));
    Look z;
    z.zombie = true;
    z.skin = variant & 7;
    static const int styles[] = {0, 1, 1, 2, 4, 6};
    z.hairStyle = styles[rng.rangeInt(0, 5)];
    z.hairColor = rng.chance(0.5f) ? 0 : rng.rangeInt(1, 4);
    static const int tops[] = {0, 0, 1, 2, 3, 4, 7};
    z.top = tops[rng.rangeInt(0, 6)];
    z.topColor = rng.rangeInt(0, 14);
    z.pantsColor = rng.rangeInt(0, 6);
    z.shoes = rng.rangeInt(0, 3);
    z.shorts = rng.chance(0.15f);
    z.face = rng.chance(0.2f) ? 1 : 0;
    return z.encode();
}

void buildBlockyCharacter(uint32_t code, CharacterAsset& out) {
    const Look l = Look::decode(code);
    // Pele 64x64 em pixel art.
    out.image.width = out.image.height = kTex;
    out.image.rgba.assign(static_cast<size_t>(kTex) * kTex * 4, 0);
    Painter P{out.image, hash2(static_cast<int>(code & 0xFFFF), static_cast<int>(code >> 16), 4242u), l.zombie};
    paintHead(P, l);
    paintBody(P, l);
    paintArm(P, l, kArmRUv);
    paintArm(P, l, kArmLUv);
    paintLeg(P, l, kLegRUv);
    paintLeg(P, l, kLegLUv);
    Rgb hair = l.zombie ? mul(kHair[l.hairColor & 7], 0.7f) : kHair[l.hairColor & 7];
    Rgb hat = kTop[l.topColor & 15];
    P.palette(P_HAIR, hair);
    P.palette(P_HAIR_DARK, mul(hair, 0.8f));
    P.palette(P_HAT, hat);
    P.palette(P_HAT_DARK, mul(hat, 0.75f));
    P.palette(P_OLIVE, {92, 104, 64});
    P.palette(P_OLIVE_DARK, {70, 80, 48});
    P.palette(P_BLACK, {30, 30, 34});
    P.palette(P_LENS, {70, 200, 220});
    P.palette(P_GOLD, {236, 190, 52});
    P.palette(P_GOLD_DARK, {196, 150, 36});
    P.palette(P_RED, {200, 40, 40});
    P.palette(P_WHITE, {236, 236, 236});
    P.palette(P_GEM, {220, 40, 70});
    P.palette(P_GREY, {150, 150, 156});
    P.dilate();

    // Caixas (em pixels): pernas e tronco presos ao quadril, cabeça e braços presos ao tronco.
    const float px = kBlockyPixel;
    std::vector<ModelMesh> meshes;
    auto addMesh = [&](MeshBuilder& b) {
        meshes.push_back(std::move(b.mesh));
        return static_cast<int>(meshes.size()) - 1;
    };
    MeshBuilder body, head, armR, armL, legR, legL;
    body.box({-4, 0, -2}, {4, 12, 2}, kBodyUv);
    head.box({-4, 0, -4}, {4, 8, 4}, kHeadUv);
    armR.box({-2, -10, -2}, {2, 2, 2}, kArmRUv);
    armL.box({-2, -10, -2}, {2, 2, 2}, kArmLUv);
    legR.box({-2, -12, -2}, {2, 0, 2}, kLegRUv);
    legL.box({-2, -12, -2}, {2, 0, 2}, kLegLUv);

    std::vector<ModelNode> nodes(N_COUNT);
    auto node = [&](int id, const char* name, int parent, Vec3 t, int mesh) {
        ModelNode& n = nodes[static_cast<size_t>(id)];
        n.name = name;
        n.parent = parent;
        n.translation = t * px;
        n.mesh = mesh;
    };
    node(N_ROOT, "root", -1, {0, 0, 0}, -1);
    node(N_TORSO, "torso", N_ROOT, {0, 12, 0}, addMesh(body));
    node(N_HEAD, "head", N_TORSO, {0, 12, 0}, addMesh(head));
    node(N_ARM_R, "arm-right", N_TORSO, {-6, 10, 0}, addMesh(armR));
    node(N_ARM_L, "arm-left", N_TORSO, {6, 10, 0}, addMesh(armL));
    node(N_LEG_R, "leg-right", N_ROOT, {-2, 12, 0}, addMesh(legR));
    node(N_LEG_L, "leg-left", N_ROOT, {2, 12, 0}, addMesh(legL));
    MeshBuilder hairMesh, hatMesh;
    if (buildHair(l, hairMesh)) {
        ModelNode n;
        n.name = "hair";
        n.parent = N_HEAD;
        n.mesh = addMesh(hairMesh);
        nodes.push_back(n);
    }
    if (buildHat(l, hatMesh)) {
        ModelNode n;
        n.name = "hat";
        n.parent = N_HEAD;
        n.mesh = addMesh(hatMesh);
        nodes.push_back(n);
    }
    out.model.build(std::move(nodes), std::move(meshes), buildClips(), 32.0f * px);
    out.pixelArt = true;
}

}  // namespace zz
