#include "renderer.h"

#include <algorithm>
#include <cmath>

#include "../core/log.h"
#include "../core/random.h"
#include "../world/block_textures.h"

namespace zz {

namespace {

const char* kVoxelVs = R"(#version 300 es
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in vec4 a_meta;
uniform mat4 u_viewProj;
uniform float u_time;
out vec3 v_uvl;
out float v_light;
out vec3 v_world;
flat out float v_emissive;
void main() {
    vec3 p = a_pos * 0.125;
    float flags = a_meta.z;
    float water = mod(flags, 2.0);
    float sway = mod(floor(flags / 4.0), 2.0);
    p.x += sway * sin(u_time * 1.8 + p.z * 0.8 + p.x * 0.3) * 0.07;
    p.z += sway * cos(u_time * 1.4 + p.x * 0.8) * 0.07;
    vec2 uv = a_uv;
    if (water > 0.5) {
        p.y += sin(u_time * 1.6 + p.x * 0.7 + p.z * 0.5) * 0.035 - 0.03;
        uv += vec2(u_time * 0.05, u_time * 0.035);
    }
    v_uvl = vec3(uv, a_meta.x);
    v_light = a_meta.y / 255.0;
    v_emissive = mod(floor(flags / 2.0), 2.0);
    v_world = p;
    gl_Position = u_viewProj * vec4(p, 1.0);
}
)";

const char* kVoxelFs = R"(#version 300 es
precision highp float;
precision mediump sampler2DArray;
uniform sampler2DArray u_tex;
uniform vec3 u_dayLight;
uniform vec3 u_fogColor;
uniform vec2 u_fogRange;
uniform vec3 u_camPos;
uniform float u_alphaTest;
uniform float u_alpha;
uniform int u_lightCount;
uniform vec4 u_lights[8];
uniform vec3 u_lightColor;
uniform vec4 u_flash;
in vec3 v_uvl;
in float v_light;
in vec3 v_world;
flat in float v_emissive;
out vec4 o_color;
void main() {
    vec4 tex = texture(u_tex, v_uvl);
    if (tex.a < u_alphaTest) discard;
    vec3 light = u_dayLight;
    for (int i = 0; i < 8; ++i) {
        if (i >= u_lightCount) break;
        float d = distance(v_world, u_lights[i].xyz);
        float att = clamp(1.0 - d / u_lights[i].w, 0.0, 1.0);
        light += u_lightColor * att * att * 1.3;
    }
    float fd = distance(v_world, u_flash.xyz);
    float fatt = clamp(1.0 - fd / 7.0, 0.0, 1.0);
    light += vec3(1.0, 0.85, 0.55) * fatt * fatt * u_flash.w;
    vec3 color = tex.rgb * light * v_light;
    color = mix(color, tex.rgb * 1.1, v_emissive);
    float dist = distance(v_world, u_camPos);
    float fog = smoothstep(u_fogRange.x, u_fogRange.y, dist);
    o_color = vec4(mix(color, u_fogColor, fog), u_alpha * max(tex.a, 0.0));
}
)";

const char* kModelVs = R"(#version 300 es
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_uv;
uniform mat4 u_model;
uniform mat4 u_viewProj;
out vec3 v_normal;
out vec2 v_uv;
out vec3 v_world;
void main() {
    vec4 w = u_model * vec4(a_pos, 1.0);
    v_world = w.xyz;
    v_normal = mat3(u_model) * a_normal;
    v_uv = a_uv;
    gl_Position = u_viewProj * w;
}
)";

const char* kModelFs = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform float u_useTex;
uniform vec4 u_color;
uniform vec3 u_tint;
uniform float u_flash;
uniform float u_emissive;
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_ambient;
uniform vec3 u_fogColor;
uniform vec2 u_fogRange;
uniform vec3 u_camPos;
uniform int u_lightCount;
uniform vec4 u_lights[8];
uniform vec3 u_lightColor;
uniform vec4 u_flashLight;
in vec3 v_normal;
in vec2 v_uv;
in vec3 v_world;
out vec4 o_color;
void main() {
    vec4 base = u_useTex > 0.5 ? texture(u_tex, v_uv) : vec4(1.0);
    base *= u_color;
    base.rgb *= u_tint;
    vec3 n = normalize(v_normal);
    float ndl = max(dot(n, u_sunDir), 0.0);
    vec3 light = u_ambient + u_sunColor * ndl + vec3(0.06) * max(-n.y, 0.0);
    for (int i = 0; i < 8; ++i) {
        if (i >= u_lightCount) break;
        float d = distance(v_world, u_lights[i].xyz);
        float att = clamp(1.0 - d / u_lights[i].w, 0.0, 1.0);
        light += u_lightColor * att * att * 1.2;
    }
    float fd = distance(v_world, u_flashLight.xyz);
    float fatt = clamp(1.0 - fd / 7.0, 0.0, 1.0);
    light += vec3(1.0, 0.85, 0.55) * fatt * fatt * u_flashLight.w;
    vec3 color = base.rgb * light;
    color = mix(color, base.rgb, u_emissive);
    color = mix(color, vec3(1.0, 0.25, 0.2), u_flash);
    float fog = smoothstep(u_fogRange.x, u_fogRange.y, distance(v_world, u_camPos));
    o_color = vec4(mix(color, u_fogColor, fog), base.a);
}
)";

const char* kSkyVs = R"(#version 300 es
out vec2 v_ndc;
void main() {
    vec2 pts[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
    v_ndc = pts[gl_VertexID];
    gl_Position = vec4(v_ndc, 0.0, 1.0);
}
)";

const char* kSkyFs = R"(#version 300 es
precision highp float;
uniform mat4 u_invViewProj;
uniform vec3 u_camPos;
uniform vec3 u_zenith;
uniform vec3 u_horizon;
uniform vec3 u_fog;
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform float u_night;
uniform float u_time;
in vec2 v_ndc;
out vec4 o_color;
void main() {
    vec4 far = u_invViewProj * vec4(v_ndc, 1.0, 1.0);
    vec3 dir = normalize(far.xyz / far.w - u_camPos);
    float h = dir.y;
    vec3 col = mix(u_horizon, u_zenith, smoothstep(0.0, 0.55, h));
    col = mix(col, u_fog * 0.85, smoothstep(0.02, -0.25, h));
    vec3 s = normalize(u_sunDir);
    vec3 t1 = normalize(cross(abs(s.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0), s));
    vec3 t2 = cross(s, t1);
    float ds = dot(dir, s);
    if (ds > 0.0) {
        vec2 q = vec2(dot(dir, t1), dot(dir, t2)) / ds;
        float m = max(abs(q.x), abs(q.y));
        vec3 disc = u_night > 0.5 ? vec3(0.92, 0.94, 1.0) : u_sunColor * 1.35;
        col = mix(col, disc, step(m, 0.075));
        col += u_sunColor * 0.22 * pow(ds, 24.0);
    }
    if (u_night > 0.01 && h > 0.02) {
        vec3 cell = floor(dir * 160.0);
        float r = fract(sin(dot(cell, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
        if (r > 0.9955) col += vec3(0.85) * u_night * (0.6 + 0.4 * sin(u_time * 2.5 + r * 90.0));
    }
    o_color = vec4(col, 1.0);
}
)";

const char* kCloudVs = R"(#version 300 es
layout(location = 0) in vec2 a_pos;
uniform mat4 u_viewProj;
uniform vec3 u_center;
uniform float u_size;
uniform float u_time;
out vec2 v_uv;
out vec3 v_world;
void main() {
    vec3 w = vec3(u_center.x + a_pos.x * u_size, u_center.y, u_center.z + a_pos.y * u_size);
    v_world = w;
    v_uv = (w.xz + vec2(u_time * 1.2, 0.0)) / 768.0;
    gl_Position = u_viewProj * vec4(w, 1.0);
}
)";

const char* kCloudFs = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform vec3 u_cloudColor;
uniform vec3 u_camPos;
uniform float u_fade;
in vec2 v_uv;
in vec3 v_world;
out vec4 o_color;
void main() {
    float m = texture(u_tex, v_uv).r;
    if (m < 0.5) discard;
    float d = distance(v_world.xz, u_camPos.xz);
    float fade = 1.0 - smoothstep(u_fade * 0.5, u_fade, d);
    o_color = vec4(u_cloudColor, 0.78 * fade);
}
)";

const char* kBillboardVs = R"(#version 300 es
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec4 a_color;
layout(location = 2) in vec2 a_uv;
uniform mat4 u_viewProj;
out vec4 v_color;
out vec2 v_uv;
out vec3 v_world;
void main() {
    v_color = a_color;
    v_uv = a_uv;
    v_world = a_pos;
    gl_Position = u_viewProj * vec4(a_pos, 1.0);
}
)";

const char* kBillboardFs = R"(#version 300 es
precision highp float;
uniform sampler2D u_tex;
uniform int u_mode;
uniform float u_time;
uniform vec3 u_fogColor;
uniform vec2 u_fogRange;
uniform vec3 u_camPos;
in vec4 v_color;
in vec2 v_uv;
in vec3 v_world;
out vec4 o_color;
void main() {
    vec4 c;
    if (u_mode == 0) {
        c = vec4(v_color.rgb, v_color.a * texture(u_tex, v_uv).r);
    } else if (u_mode == 1) {
        c = v_color;
    } else {
        vec2 p = v_uv - 0.5;
        float r = length(p * vec2(1.0, 0.75));
        float ang = atan(p.y, p.x);
        float sw = sin(ang * 3.0 + r * 16.0 - u_time * 4.0) * 0.5 + 0.5;
        vec3 col = mix(vec3(0.32, 0.08, 0.7), vec3(0.88, 0.55, 1.0), sw);
        c = vec4(col, v_color.a * (0.72 + 0.28 * sw));
    }
    float fog = smoothstep(u_fogRange.x, u_fogRange.y, distance(v_world, u_camPos));
    c.rgb = mix(c.rgb, u_fogColor, fog * 0.8);
    o_color = c;
}
)";

const float kBoxVerts[] = {
    // +X
    0.5f, -0.5f, 0.5f, 1, 0, 0, 0, 1,  0.5f, -0.5f, -0.5f, 1, 0, 0, 1, 1,  0.5f, 0.5f, -0.5f, 1, 0, 0, 1, 0,  0.5f, 0.5f, 0.5f, 1, 0, 0, 0, 0,
    // -X
    -0.5f, -0.5f, -0.5f, -1, 0, 0, 0, 1,  -0.5f, -0.5f, 0.5f, -1, 0, 0, 1, 1,  -0.5f, 0.5f, 0.5f, -1, 0, 0, 1, 0,  -0.5f, 0.5f, -0.5f, -1, 0, 0, 0, 0,
    // +Y
    -0.5f, 0.5f, 0.5f, 0, 1, 0, 0, 1,  0.5f, 0.5f, 0.5f, 0, 1, 0, 1, 1,  0.5f, 0.5f, -0.5f, 0, 1, 0, 1, 0,  -0.5f, 0.5f, -0.5f, 0, 1, 0, 0, 0,
    // -Y
    -0.5f, -0.5f, -0.5f, 0, -1, 0, 0, 1,  0.5f, -0.5f, -0.5f, 0, -1, 0, 1, 1,  0.5f, -0.5f, 0.5f, 0, -1, 0, 1, 0,  -0.5f, -0.5f, 0.5f, 0, -1, 0, 0, 0,
    // +Z
    -0.5f, -0.5f, 0.5f, 0, 0, 1, 0, 1,  0.5f, -0.5f, 0.5f, 0, 0, 1, 1, 1,  0.5f, 0.5f, 0.5f, 0, 0, 1, 1, 0,  -0.5f, 0.5f, 0.5f, 0, 0, 1, 0, 0,
    // -Z
    0.5f, -0.5f, -0.5f, 0, 0, -1, 0, 1,  -0.5f, -0.5f, -0.5f, 0, 0, -1, 1, 1,  -0.5f, 0.5f, -0.5f, 0, 0, -1, 1, 0,  0.5f, 0.5f, -0.5f, 0, 0, -1, 0, 0,
};

constexpr int kBillboardFloats = 9;  // pos(3) cor(4) uv(2)
constexpr float kExposure = 0.8f;   // evita superexposição das cores das texturas

void pushVertex(std::vector<float>& out, const Vec3& p, const Vec4& c, float u, float v) {
    out.insert(out.end(), {p.x, p.y, p.z, c.x, c.y, c.z, c.w, u, v});
}

void pushQuad(std::vector<float>& out, const Vec3& a, const Vec3& b, const Vec3& c, const Vec3& d, const Vec4& color) {
    // Dois triângulos: a b c / a c d (uv a=(0,1) b=(1,1) c=(1,0) d=(0,0))
    pushVertex(out, a, color, 0, 1);
    pushVertex(out, b, color, 1, 1);
    pushVertex(out, c, color, 1, 0);
    pushVertex(out, a, color, 0, 1);
    pushVertex(out, c, color, 1, 0);
    pushVertex(out, d, color, 0, 0);
}

}  // namespace

void Renderer::onSurfaceCreated() {
    ++contextId_;
    // Handles antigos ficaram inválidos com o contexto; recria tudo sem chamar glDelete.
    chunkGpu_.clear();
    quadIbo_ = 0;
    quadIboCapacity_ = 0;
    billboardCapacity_ = 0;
    lastWorld_ = nullptr;
    themeDirty_ = true;
    createPrograms();
    createStaticBuffers();
    LOGI("GL: %s | %s", glGetString(GL_RENDERER), glGetString(GL_VERSION));
}

void Renderer::onSurfaceChanged(int width, int height) {
    width_ = std::max(1, width);
    height_ = std::max(1, height);
    glViewport(0, 0, width_, height_);
}

void Renderer::setTheme(const Theme& theme) {
    theme_ = theme;
    themeDirty_ = true;
}

void Renderer::createPrograms() {
    voxel_.id = compileProgram(kVoxelVs, kVoxelFs, "voxel");
    model_.id = compileProgram(kModelVs, kModelFs, "model");
    sky_.id = compileProgram(kSkyVs, kSkyFs, "sky");
    billboard_.id = compileProgram(kBillboardVs, kBillboardFs, "billboard");
    cloud_.id = compileProgram(kCloudVs, kCloudFs, "cloud");
}

void Renderer::createStaticBuffers() {
    glGenVertexArrays(1, &skyVao_);

    // Cubo unitário (armas, coletáveis).
    glGenVertexArrays(1, &boxVao_);
    glGenBuffers(1, &boxVbo_);
    glGenBuffers(1, &boxIbo_);
    glBindVertexArray(boxVao_);
    glBindBuffer(GL_ARRAY_BUFFER, boxVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kBoxVerts), kBoxVerts, GL_STATIC_DRAW);
    uint16_t idx[36];
    for (int f = 0; f < 6; ++f) {
        uint16_t b = static_cast<uint16_t>(f * 4);
        uint16_t q[6] = {b, static_cast<uint16_t>(b + 1), static_cast<uint16_t>(b + 2), b, static_cast<uint16_t>(b + 2), static_cast<uint16_t>(b + 3)};
        std::copy(q, q + 6, idx + f * 6);
    }
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, boxIbo_);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(idx), idx, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 32, reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 32, reinterpret_cast<void*>(12));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, 32, reinterpret_cast<void*>(24));
    glBindVertexArray(0);

    // Plano das nuvens.
    const float cloud[] = {-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1};
    glGenVertexArrays(1, &cloudVao_);
    glGenBuffers(1, &cloudVbo_);
    glBindVertexArray(cloudVao_);
    glBindBuffer(GL_ARRAY_BUFFER, cloudVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(cloud), cloud, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 8, nullptr);
    glBindVertexArray(0);

    // Máscara de nuvens em blocos.
    std::vector<uint8_t> mask(64 * 64);
    for (int y = 0; y < 64; ++y)
        for (int x = 0; x < 64; ++x) {
            float n = 0;
            float amp = 0.5f, freq = 1.0f / 8.0f, norm = 0;
            for (int o = 0; o < 3; ++o) {
                // ruído periódico (tileável) em 64
                int period = static_cast<int>(64 * freq);
                float fx = x * freq, fy = y * freq;
                int x0 = static_cast<int>(fx), y0 = static_cast<int>(fy);
                float tx = fx - x0, ty = fy - y0;
                auto h = [&](int a, int b) { return hashFloat(((a % period) + period) % period, ((b % period) + period) % period, 900u + o); };
                float v = lerpf(lerpf(h(x0, y0), h(x0 + 1, y0), tx), lerpf(h(x0, y0 + 1), h(x0 + 1, y0 + 1), tx), ty);
                n += v * amp;
                norm += amp;
                amp *= 0.5f;
                freq *= 2.0f;
            }
            mask[y * 64 + x] = (n / norm) > 0.56f ? 255 : 0;
        }
    glGenTextures(1, &cloudTexture_);
    glBindTexture(GL_TEXTURE_2D, cloudTexture_);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 64, 64, 0, GL_RED, GL_UNSIGNED_BYTE, mask.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);

    // Círculo suave para partículas e sombras.
    std::vector<uint8_t> soft(32 * 32);
    for (int y = 0; y < 32; ++y)
        for (int x = 0; x < 32; ++x) {
            float dx = (x + 0.5f) / 16.0f - 1.0f, dy = (y + 0.5f) / 16.0f - 1.0f;
            float d = std::sqrt(dx * dx + dy * dy);
            soft[y * 32 + x] = static_cast<uint8_t>(clampf(1.0f - d, 0, 1) * clampf(1.0f - d, 0, 1) * 255.0f);
        }
    glGenTextures(1, &softTexture_);
    glBindTexture(GL_TEXTURE_2D, softTexture_);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 32, 32, 0, GL_RED, GL_UNSIGNED_BYTE, soft.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

    glGenVertexArrays(1, &billboardVao_);
    glGenBuffers(1, &billboardVbo_);
    glBindVertexArray(billboardVao_);
    glBindBuffer(GL_ARRAY_BUFFER, billboardVbo_);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, kBillboardFloats * 4, reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, kBillboardFloats * 4, reinterpret_cast<void*>(12));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, kBillboardFloats * 4, reinterpret_cast<void*>(28));
    glBindVertexArray(0);

    glGenBuffers(1, &quadIbo_);
    ensureQuadIndices(16384);
}

void Renderer::ensureQuadIndices(size_t quads) {
    if (quads <= quadIboCapacity_) return;
    size_t capacity = std::max(quads, quadIboCapacity_ * 2);
    std::vector<uint32_t> idx(capacity * 6);
    for (size_t q = 0; q < capacity; ++q) {
        uint32_t b = static_cast<uint32_t>(q * 4);
        idx[q * 6 + 0] = b;
        idx[q * 6 + 1] = b + 1;
        idx[q * 6 + 2] = b + 2;
        idx[q * 6 + 3] = b;
        idx[q * 6 + 4] = b + 2;
        idx[q * 6 + 5] = b + 3;
    }
    glBindVertexArray(0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, quadIbo_);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLsizeiptr>(idx.size() * sizeof(uint32_t)), idx.data(), GL_STATIC_DRAW);
    quadIboCapacity_ = capacity;
}

void Renderer::uploadBlockTextures() {
    std::vector<uint8_t> layers = generateBlockTextures(theme_);
    if (blockTextures_ == 0 || !glIsTexture(blockTextures_)) glGenTextures(1, &blockTextures_);
    glBindTexture(GL_TEXTURE_2D_ARRAY, blockTextures_);
    glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, kTexSize, kTexSize, T_COUNT, 0, GL_RGBA, GL_UNSIGNED_BYTE, layers.data());
    glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAX_LEVEL, 4);
    themeDirty_ = false;
}

void Renderer::ensureCharacter(CharacterAsset& asset) {
    if (asset.contextId == contextId_) return;
    asset.meshes.clear();
    for (const auto& mesh : asset.model.meshes()) {
        CharacterAsset::MeshGpu gpu;
        glGenVertexArrays(1, &gpu.vao);
        glGenBuffers(1, &gpu.vbo);
        glGenBuffers(1, &gpu.ibo);
        glBindVertexArray(gpu.vao);
        glBindBuffer(GL_ARRAY_BUFFER, gpu.vbo);
        glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(mesh.vertices.size() * sizeof(float)), mesh.vertices.data(), GL_STATIC_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, gpu.ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLsizeiptr>(mesh.indices.size() * sizeof(uint16_t)), mesh.indices.data(), GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 32, reinterpret_cast<void*>(0));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 32, reinterpret_cast<void*>(12));
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, 32, reinterpret_cast<void*>(24));
        glBindVertexArray(0);
        gpu.count = static_cast<GLsizei>(mesh.indices.size());
        asset.meshes.push_back(gpu);
    }
    asset.texture = 0;
    if (!asset.image.rgba.empty()) {
        glGenTextures(1, &asset.texture);
        glBindTexture(GL_TEXTURE_2D, asset.texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, asset.image.width, asset.image.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, asset.image.rgba.data());
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        // Os UVs dos modelos Kenney saem de [0,1] e dependem de repetição.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    }
    asset.contextId = contextId_;
}

void Renderer::syncChunks(World& world) {
    auto& chunks = world.chunks();
    if (lastWorld_ != &world || chunkGpu_.size() != chunks.size()) {
        for (auto& g : chunkGpu_) {
            if (g.contextId != contextId_) continue;
            glDeleteVertexArrays(3, g.vao);
            glDeleteBuffers(3, g.vbo);
        }
        chunkGpu_.assign(chunks.size(), ChunkGpu{});
        lastWorld_ = &world;
    }
    if (lastGeneration_ != world.generation()) {
        for (auto& g : chunkGpu_) g.version = 0xFFFFFFFFu;
        lastGeneration_ = world.generation();
    }
    for (size_t i = 0; i < chunks.size(); ++i) {
        Chunk& chunk = chunks[i];
        ChunkGpu& gpu = chunkGpu_[i];
        if (chunk.dirty) continue;
        if (gpu.contextId == contextId_ && gpu.version == chunk.version) continue;
        if (gpu.contextId != contextId_) {
            glGenVertexArrays(3, gpu.vao);
            glGenBuffers(3, gpu.vbo);
            gpu.contextId = contextId_;
        }
        const std::vector<VoxelVertex>* lists[3] = {&chunk.mesh.opaque, &chunk.mesh.cutout, &chunk.mesh.water};
        for (int pass = 0; pass < 3; ++pass) {
            const auto& verts = *lists[pass];
            gpu.quads[pass] = static_cast<GLsizei>(verts.size() / 4);
            ensureQuadIndices(static_cast<size_t>(gpu.quads[pass]));
            glBindVertexArray(gpu.vao[pass]);
            glBindBuffer(GL_ARRAY_BUFFER, gpu.vbo[pass]);
            glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(verts.size() * sizeof(VoxelVertex)), verts.empty() ? nullptr : verts.data(), GL_STATIC_DRAW);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_SHORT, GL_FALSE, sizeof(VoxelVertex), reinterpret_cast<void*>(0));
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_UNSIGNED_BYTE, GL_FALSE, sizeof(VoxelVertex), reinterpret_cast<void*>(6));
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, GL_FALSE, sizeof(VoxelVertex), reinterpret_cast<void*>(8));
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, quadIbo_);
            glBindVertexArray(0);
        }
        gpu.version = chunk.version;
    }
}

void Renderer::setVoxelUniforms(const FrameScene& scene, const Mat4& viewProj) {
    const SkyPreset& sky = scene.sky;
    glUseProgram(voxel_.id);
    glUniformMatrix4fv(voxel_.uniform("u_viewProj"), 1, GL_FALSE, viewProj.m);
    glUniform1f(voxel_.uniform("u_time"), scene.time);
    Vec3 day = (sky.ambient + sky.sunColor * sky.sunIntensity) * kExposure;
    glUniform3f(voxel_.uniform("u_dayLight"), day.x, day.y, day.z);
    glUniform3f(voxel_.uniform("u_fogColor"), sky.fog.x, sky.fog.y, sky.fog.z);
    glUniform2f(voxel_.uniform("u_fogRange"), sky.fogStart, sky.fogEnd);
    glUniform3f(voxel_.uniform("u_camPos"), scene.cameraPos.x, scene.cameraPos.y, scene.cameraPos.z);
    int count = static_cast<int>(std::min<size_t>(8, scene.lights.size()));
    float packed[32] = {0};
    for (int i = 0; i < count; ++i) {
        packed[i * 4] = scene.lights[i].position.x;
        packed[i * 4 + 1] = scene.lights[i].position.y;
        packed[i * 4 + 2] = scene.lights[i].position.z;
        packed[i * 4 + 3] = scene.lights[i].radius;
    }
    glUniform1i(voxel_.uniform("u_lightCount"), count);
    glUniform4fv(voxel_.uniform("u_lights"), 8, packed);
    glUniform3f(voxel_.uniform("u_lightColor"), scene.lightColor.x, scene.lightColor.y, scene.lightColor.z);
    glUniform4f(voxel_.uniform("u_flash"), scene.muzzleFlash.x, scene.muzzleFlash.y, scene.muzzleFlash.z, scene.muzzleFlash.w);
    glUniform1i(voxel_.uniform("u_tex"), 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D_ARRAY, blockTextures_);
}

void Renderer::drawChunks(const FrameScene& scene, const Mat4& viewProj, int pass) {
    struct Item { size_t index; float dist; };
    std::vector<Item> items;
    items.reserve(chunkGpu_.size());
    const auto& chunks = scene.world->chunks();
    for (size_t i = 0; i < chunkGpu_.size() && i < chunks.size(); ++i) {
        const ChunkGpu& gpu = chunkGpu_[i];
        if (gpu.contextId != contextId_ || gpu.quads[pass] == 0) continue;
        const AABB& b = chunks[i].bounds;
        Vec3 center = (b.min + b.max) * 0.5f;
        float dist = distXZ(center, scene.cameraPos);
        if (dist > scene.drawDistance + 12) continue;
        bool visible = true;
        for (const Vec4& pl : frustum_) {
            Vec3 p(pl.x >= 0 ? b.max.x : b.min.x, pl.y >= 0 ? b.max.y : b.min.y, pl.z >= 0 ? b.max.z : b.min.z);
            if (pl.x * p.x + pl.y * p.y + pl.z * p.z + pl.w < 0) { visible = false; break; }
        }
        if (visible) items.push_back({i, dist});
    }
    if (pass == 2) std::sort(items.begin(), items.end(), [](const Item& a, const Item& b) { return a.dist > b.dist; });
    else std::sort(items.begin(), items.end(), [](const Item& a, const Item& b) { return a.dist < b.dist; });

    glUniform1f(voxel_.uniform("u_alphaTest"), pass == 1 ? 0.5f : -1.0f);
    glUniform1f(voxel_.uniform("u_alpha"), pass == 2 ? 0.74f : 1.0f);
    for (const Item& it : items) {
        const ChunkGpu& gpu = chunkGpu_[it.index];
        glBindVertexArray(gpu.vao[pass]);
        glDrawElements(GL_TRIANGLES, gpu.quads[pass] * 6, GL_UNSIGNED_INT, nullptr);
    }
    glBindVertexArray(0);
}

void Renderer::drawSky(const FrameScene& scene, const Mat4& viewProj) {
    const SkyPreset& sky = scene.sky;
    // Remove a translação para reconstruir direções a partir da câmera.
    Mat4 inv = viewProj.inverse();
    glUseProgram(sky_.id);
    glUniformMatrix4fv(sky_.uniform("u_invViewProj"), 1, GL_FALSE, inv.m);
    glUniform3f(sky_.uniform("u_camPos"), scene.cameraPos.x, scene.cameraPos.y, scene.cameraPos.z);
    glUniform3f(sky_.uniform("u_zenith"), sky.zenith.x, sky.zenith.y, sky.zenith.z);
    glUniform3f(sky_.uniform("u_horizon"), sky.horizon.x, sky.horizon.y, sky.horizon.z);
    glUniform3f(sky_.uniform("u_fog"), sky.fog.x, sky.fog.y, sky.fog.z);
    glUniform3f(sky_.uniform("u_sunDir"), sky.sunDirection.x, sky.sunDirection.y, sky.sunDirection.z);
    glUniform3f(sky_.uniform("u_sunColor"), sky.sunColor.x, sky.sunColor.y, sky.sunColor.z);
    glUniform1f(sky_.uniform("u_night"), sky.night);
    glUniform1f(sky_.uniform("u_time"), scene.time);
    glDisable(GL_DEPTH_TEST);
    glDepthMask(GL_FALSE);
    glBindVertexArray(skyVao_);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glBindVertexArray(0);
    glDepthMask(GL_TRUE);
    glEnable(GL_DEPTH_TEST);
}

void Renderer::drawClouds(const FrameScene& scene, const Mat4& viewProj) {
    glUseProgram(cloud_.id);
    glUniformMatrix4fv(cloud_.uniform("u_viewProj"), 1, GL_FALSE, viewProj.m);
    float height = std::max(62.0f, scene.cameraPos.y + 34.0f);
    glUniform3f(cloud_.uniform("u_center"), scene.cameraPos.x, height, scene.cameraPos.z);
    glUniform1f(cloud_.uniform("u_size"), 320.0f);
    glUniform1f(cloud_.uniform("u_time"), scene.time);
    glUniform1f(cloud_.uniform("u_fade"), 300.0f);
    Vec3 c = lerp(Vec3(1, 1, 1), scene.sky.horizon, 0.25f) * (scene.sky.night > 0.5f ? 0.35f : 1.0f);
    glUniform3f(cloud_.uniform("u_cloudColor"), c.x, c.y, c.z);
    glUniform3f(cloud_.uniform("u_camPos"), scene.cameraPos.x, scene.cameraPos.y, scene.cameraPos.z);
    glUniform1i(cloud_.uniform("u_tex"), 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, cloudTexture_);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDepthMask(GL_FALSE);
    glBindVertexArray(cloudVao_);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glBindVertexArray(0);
    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);
    glEnable(GL_CULL_FACE);
}

void Renderer::setModelUniforms(const FrameScene& scene, const Mat4& viewProj) {
    const SkyPreset& sky = scene.sky;
    glUseProgram(model_.id);
    glUniformMatrix4fv(model_.uniform("u_viewProj"), 1, GL_FALSE, viewProj.m);
    glUniform3f(model_.uniform("u_sunDir"), sky.sunDirection.x, sky.sunDirection.y, sky.sunDirection.z);
    Vec3 sun = sky.sunColor * (sky.sunIntensity * kExposure);
    glUniform3f(model_.uniform("u_sunColor"), sun.x, sun.y, sun.z);
    glUniform3f(model_.uniform("u_ambient"), sky.ambient.x * kExposure + 0.06f, sky.ambient.y * kExposure + 0.06f, sky.ambient.z * kExposure + 0.06f);
    glUniform3f(model_.uniform("u_fogColor"), sky.fog.x, sky.fog.y, sky.fog.z);
    glUniform2f(model_.uniform("u_fogRange"), sky.fogStart, sky.fogEnd);
    glUniform3f(model_.uniform("u_camPos"), scene.cameraPos.x, scene.cameraPos.y, scene.cameraPos.z);
    int count = static_cast<int>(std::min<size_t>(8, scene.lights.size()));
    float packed[32] = {0};
    for (int i = 0; i < count; ++i) {
        packed[i * 4] = scene.lights[i].position.x;
        packed[i * 4 + 1] = scene.lights[i].position.y;
        packed[i * 4 + 2] = scene.lights[i].position.z;
        packed[i * 4 + 3] = scene.lights[i].radius;
    }
    glUniform1i(model_.uniform("u_lightCount"), count);
    glUniform4fv(model_.uniform("u_lights"), 8, packed);
    glUniform3f(model_.uniform("u_lightColor"), scene.lightColor.x, scene.lightColor.y, scene.lightColor.z);
    glUniform4f(model_.uniform("u_flashLight"), scene.muzzleFlash.x, scene.muzzleFlash.y, scene.muzzleFlash.z, scene.muzzleFlash.w);
    glUniform1i(model_.uniform("u_tex"), 0);
    glActiveTexture(GL_TEXTURE0);
}

void Renderer::drawCharacters(const FrameScene& scene, const Mat4& viewProj) {
    setModelUniforms(scene, viewProj);
    GLint uModel = model_.uniform("u_model");
    GLint uUseTex = model_.uniform("u_useTex");
    GLint uColor = model_.uniform("u_color");
    GLint uTint = model_.uniform("u_tint");
    GLint uFlash = model_.uniform("u_flash");
    GLint uEmissive = model_.uniform("u_emissive");
    glUniform1f(uEmissive, 0.0f);
    for (size_t c = 0; c < scene.characterCount && c < scene.characters.size(); ++c) {
        const CharacterDraw& draw = scene.characters[c];
        if (!draw.asset) continue;
        ensureCharacter(*draw.asset);
        glBindTexture(GL_TEXTURE_2D, draw.asset->texture);
        glUniform1f(uUseTex, draw.asset->texture ? 1.0f : 0.0f);
        glUniform4f(uColor, 1, 1, 1, draw.alpha);
        glUniform3f(uTint, draw.tint.x, draw.tint.y, draw.tint.z);
        glUniform1f(uFlash, draw.flash);
        const auto& nodes = draw.asset->model.nodes();
        for (size_t i = 0; i < nodes.size() && i < draw.nodes.size(); ++i) {
            int mesh = nodes[i].mesh;
            if (mesh < 0 || mesh >= static_cast<int>(draw.asset->meshes.size())) continue;
            Mat4 m = draw.world * draw.nodes[i];
            glUniformMatrix4fv(uModel, 1, GL_FALSE, m.m);
            const auto& gpu = draw.asset->meshes[mesh];
            glBindVertexArray(gpu.vao);
            glDrawElements(GL_TRIANGLES, gpu.count, GL_UNSIGNED_SHORT, nullptr);
        }
    }
    glBindVertexArray(0);
}

void Renderer::drawBoxes(const FrameScene& scene, const Mat4& viewProj) {
    if (scene.boxes.empty()) return;
    setModelUniforms(scene, viewProj);
    GLint uModel = model_.uniform("u_model");
    GLint uColor = model_.uniform("u_color");
    GLint uEmissive = model_.uniform("u_emissive");
    glUniform1f(model_.uniform("u_useTex"), 0.0f);
    glUniform3f(model_.uniform("u_tint"), 1, 1, 1);
    glUniform1f(model_.uniform("u_flash"), 0.0f);
    glBindVertexArray(boxVao_);
    for (const BoxDraw& box : scene.boxes) {
        glUniformMatrix4fv(uModel, 1, GL_FALSE, box.world.m);
        glUniform4f(uColor, box.color.x, box.color.y, box.color.z, box.color.w);
        glUniform1f(uEmissive, box.emissive);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_SHORT, nullptr);
    }
    glBindVertexArray(0);
}

void Renderer::drawBillboards(const FrameScene& scene, const Mat4& viewProj) {
    Vec3 right(scene.view.m[0], scene.view.m[4], scene.view.m[8]);
    Vec3 up(scene.view.m[1], scene.view.m[5], scene.view.m[9]);
    glUseProgram(billboard_.id);
    glUniformMatrix4fv(billboard_.uniform("u_viewProj"), 1, GL_FALSE, viewProj.m);
    glUniform1f(billboard_.uniform("u_time"), scene.time);
    glUniform3f(billboard_.uniform("u_fogColor"), scene.sky.fog.x, scene.sky.fog.y, scene.sky.fog.z);
    glUniform2f(billboard_.uniform("u_fogRange"), scene.sky.fogStart, scene.sky.fogEnd);
    glUniform3f(billboard_.uniform("u_camPos"), scene.cameraPos.x, scene.cameraPos.y, scene.cameraPos.z);
    glUniform1i(billboard_.uniform("u_tex"), 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, softTexture_);
    GLint uMode = billboard_.uniform("u_mode");
    glBindVertexArray(billboardVao_);
    glBindBuffer(GL_ARRAY_BUFFER, billboardVbo_);
    glEnable(GL_BLEND);
    glDepthMask(GL_FALSE);
    glDisable(GL_CULL_FACE);

    auto flush = [&](int mode) {
        if (billboardData_.empty()) return;
        size_t bytes = billboardData_.size() * sizeof(float);
        if (bytes > billboardCapacity_) {
            billboardCapacity_ = std::max(bytes, billboardCapacity_ * 2);
            glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(billboardCapacity_), nullptr, GL_DYNAMIC_DRAW);
        }
        glBufferSubData(GL_ARRAY_BUFFER, 0, static_cast<GLsizeiptr>(bytes), billboardData_.data());
        glUniform1i(uMode, mode);
        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(billboardData_.size() / kBillboardFloats));
        billboardData_.clear();
    };

    // 1) sombras projetadas (manchas no chão)
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glEnable(GL_POLYGON_OFFSET_FILL);
    glPolygonOffset(-1.0f, -2.0f);
    for (const ShadowBlob& s : scene.shadows) {
        Vec3 p = s.position + Vec3(0, 0.04f, 0);
        float r = s.radius;
        pushQuad(billboardData_, p + Vec3(-r, 0, r), p + Vec3(r, 0, r), p + Vec3(r, 0, -r), p + Vec3(-r, 0, -r), Vec4(0, 0, 0, s.alpha));
    }
    flush(0);
    glDisable(GL_POLYGON_OFFSET_FILL);

    // 2) portal aberto
    if (scene.portalVisible && scene.portalOpen > 0.01f) {
        Vec3 n = forwardFromYaw(scene.portalYaw);
        Vec3 axis(-n.z, 0, n.x);
        Vec3 b = scene.portalCenter;
        float hw = 1.5f;
        Vec3 a0 = b - axis * hw, a1 = b + axis * hw;
        pushQuad(billboardData_, a0, a1, a1 + Vec3(0, 4, 0), a0 + Vec3(0, 4, 0), Vec4(1, 1, 1, scene.portalOpen));
        flush(2);
    }

    // 3) partículas com transparência comum
    for (const Particle& p : scene.particles) {
        if (p.additive) continue;
        Vec3 r = right * (p.size * 0.5f), u = up * (p.size * 0.5f);
        pushQuad(billboardData_, p.position - r - u, p.position + r - u, p.position + r + u, p.position - r + u, p.color);
    }
    flush(1);

    // 4) aditivos: brilhos, faíscas e traçantes
    glBlendFunc(GL_SRC_ALPHA, GL_ONE);
    for (const Particle& p : scene.particles) {
        if (!p.additive) continue;
        Vec3 r = right * (p.size * 0.5f), u = up * (p.size * 0.5f);
        pushQuad(billboardData_, p.position - r - u, p.position + r - u, p.position + r + u, p.position - r + u, p.color);
    }
    flush(0);
    for (const Beam& beam : scene.beams) {
        Vec3 dir = beam.to - beam.from;
        Vec3 side = normalize(cross(dir, scene.cameraPos - beam.from)) * (beam.width * 0.5f);
        pushQuad(billboardData_, beam.from - side, beam.to - side, beam.to + side, beam.from + side, beam.color);
    }
    flush(1);

    glDepthMask(GL_TRUE);
    glDisable(GL_BLEND);
    glEnable(GL_CULL_FACE);
    glBindVertexArray(0);
}

void Renderer::render(FrameScene& scene) {
    if (!voxel_.id) return;
    if (themeDirty_) uploadBlockTextures();
    Mat4 viewProj = scene.proj * scene.view;
    // Planos do frustum (Gribb-Hartmann).
    const float* m = viewProj.m;
    Vec4 rows[4] = {{m[0], m[4], m[8], m[12]}, {m[1], m[5], m[9], m[13]}, {m[2], m[6], m[10], m[14]}, {m[3], m[7], m[11], m[15]}};
    for (int i = 0; i < 3; ++i) {
        frustum_[i * 2] = {rows[3].x + rows[i].x, rows[3].y + rows[i].y, rows[3].z + rows[i].z, rows[3].w + rows[i].w};
        frustum_[i * 2 + 1] = {rows[3].x - rows[i].x, rows[3].y - rows[i].y, rows[3].z - rows[i].z, rows[3].w - rows[i].w};
    }

    glViewport(0, 0, width_, height_);
    glClearColor(scene.sky.fog.x, scene.sky.fog.y, scene.sky.fog.z, 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);
    glFrontFace(GL_CCW);

    drawSky(scene, viewProj);
    if (scene.world) {
        syncChunks(*scene.world);
        setVoxelUniforms(scene, viewProj);
        drawChunks(scene, viewProj, 0);
        glDisable(GL_CULL_FACE);
        drawChunks(scene, viewProj, 1);
        glEnable(GL_CULL_FACE);
    }
    drawCharacters(scene, viewProj);
    drawBoxes(scene, viewProj);
    if (scene.world) {
        setVoxelUniforms(scene, viewProj);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(GL_FALSE);
        glDisable(GL_CULL_FACE);
        drawChunks(scene, viewProj, 2);
        glEnable(GL_CULL_FACE);
        glDepthMask(GL_TRUE);
        glDisable(GL_BLEND);
    }
    drawClouds(scene, viewProj);
    drawBillboards(scene, viewProj);
}

}  // namespace zz
