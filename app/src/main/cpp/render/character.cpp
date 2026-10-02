#include "character.h"

#include <cstring>

#include "../core/json.h"
#include "../core/log.h"

namespace zz {

namespace {

uint32_t readU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

int componentCount(const std::string& type) {
    if (type == "SCALAR") return 1;
    if (type == "VEC2") return 2;
    if (type == "VEC3") return 3;
    if (type == "VEC4") return 4;
    if (type == "MAT4") return 16;
    return 0;
}

int componentSize(int componentType) {
    switch (componentType) {
        case 5120: case 5121: return 1;
        case 5122: case 5123: return 2;
        case 5125: case 5126: return 4;
        default: return 0;
    }
}

struct GltfData {
    const JsonValue* json = nullptr;
    const uint8_t* bin = nullptr;
    size_t binSize = 0;
};

bool readAccessor(const GltfData& g, int accessorIndex, std::vector<float>& out, int& comps) {
    const JsonValue& acc = (*g.json)["accessors"][static_cast<size_t>(accessorIndex)];
    if (!acc.isObject()) return false;
    comps = componentCount(acc["type"].string());
    int ctype = acc["componentType"].integer();
    int csize = componentSize(ctype);
    int count = acc["count"].integer();
    bool normalized = acc["normalized"].boolean(false);
    const JsonValue& view = (*g.json)["bufferViews"][static_cast<size_t>(acc["bufferView"].integer())];
    size_t offset = static_cast<size_t>(view["byteOffset"].integer()) + static_cast<size_t>(acc["byteOffset"].integer());
    size_t stride = view.has("byteStride") ? static_cast<size_t>(view["byteStride"].integer()) : static_cast<size_t>(comps * csize);
    if (comps == 0 || csize == 0) return false;
    if (offset + stride * (count - 1) + comps * csize > g.binSize) return false;
    out.resize(static_cast<size_t>(count) * comps);
    for (int i = 0; i < count; ++i) {
        const uint8_t* base = g.bin + offset + stride * i;
        for (int c = 0; c < comps; ++c) {
            const uint8_t* p = base + c * csize;
            float v = 0;
            switch (ctype) {
                case 5126: { float f; std::memcpy(&f, p, 4); v = f; break; }
                case 5121: v = normalized ? p[0] / 255.0f : p[0]; break;
                case 5120: v = normalized ? std::max(static_cast<int8_t>(p[0]) / 127.0f, -1.0f) : static_cast<int8_t>(p[0]); break;
                case 5123: { uint16_t s; std::memcpy(&s, p, 2); v = normalized ? s / 65535.0f : s; break; }
                case 5122: { int16_t s; std::memcpy(&s, p, 2); v = normalized ? std::max(s / 32767.0f, -1.0f) : s; break; }
                case 5125: { uint32_t s; std::memcpy(&s, p, 4); v = static_cast<float>(s); break; }
            }
            out[static_cast<size_t>(i) * comps + c] = v;
        }
    }
    return true;
}

}  // namespace

bool CharacterModel::loadGlb(const std::vector<uint8_t>& data) {
    if (data.size() < 20 || readU32(data.data()) != 0x46546C67u) {
        LOGE("GLB inválido");
        return false;
    }
    uint32_t jsonLength = readU32(data.data() + 12);
    if (readU32(data.data() + 16) != 0x4E4F534Au || 20 + jsonLength > data.size()) return false;
    std::string err;
    auto json = JsonValue::parse(reinterpret_cast<const char*>(data.data() + 20), jsonLength, &err);
    if (!json) {
        LOGE("JSON do GLB inválido: %s", err.c_str());
        return false;
    }
    GltfData g;
    g.json = json.get();
    size_t binHeader = 20 + jsonLength;
    if (binHeader + 8 <= data.size()) {
        uint32_t binLength = readU32(data.data() + binHeader);
        g.bin = data.data() + binHeader + 8;
        g.binSize = std::min<size_t>(binLength, data.size() - binHeader - 8);
    }
    const JsonValue& root = *json;

    // Nós
    const JsonValue& jnodes = root["nodes"];
    nodes_.assign(jnodes.size(), ModelNode{});
    for (size_t i = 0; i < jnodes.size(); ++i) {
        const JsonValue& n = jnodes[i];
        ModelNode& node = nodes_[i];
        node.name = n["name"].string();
        node.mesh = n.has("mesh") ? n["mesh"].integer() : -1;
        if (n.has("translation")) node.translation = {static_cast<float>(n["translation"][0].number()), static_cast<float>(n["translation"][1].number()), static_cast<float>(n["translation"][2].number())};
        if (n.has("rotation")) node.rotation = {static_cast<float>(n["rotation"][0].number()), static_cast<float>(n["rotation"][1].number()), static_cast<float>(n["rotation"][2].number()), static_cast<float>(n["rotation"][3].number(1))};
        if (n.has("scale")) node.scale = {static_cast<float>(n["scale"][0].number(1)), static_cast<float>(n["scale"][1].number(1)), static_cast<float>(n["scale"][2].number(1))};
    }
    for (size_t i = 0; i < jnodes.size(); ++i) {
        const JsonValue& children = jnodes[i]["children"];
        for (size_t c = 0; c < children.size(); ++c) {
            int child = children[c].integer();
            if (child >= 0 && child < static_cast<int>(nodes_.size())) nodes_[child].parent = static_cast<int>(i);
        }
    }
    // Ordem topológica.
    order_.clear();
    std::vector<int> depth(nodes_.size(), 0);
    for (size_t i = 0; i < nodes_.size(); ++i) {
        int d = 0, p = nodes_[i].parent;
        while (p >= 0 && d < 64) { ++d; p = nodes_[p].parent; }
        depth[i] = d;
    }
    for (int d = 0; d < 64; ++d)
        for (size_t i = 0; i < nodes_.size(); ++i)
            if (depth[i] == d) order_.push_back(static_cast<int>(i));

    // Malhas
    const JsonValue& jmeshes = root["meshes"];
    meshes_.assign(jmeshes.size(), ModelMesh{});
    for (size_t m = 0; m < jmeshes.size(); ++m) {
        const JsonValue& prims = jmeshes[m]["primitives"];
        ModelMesh& mesh = meshes_[m];
        for (size_t p = 0; p < prims.size(); ++p) {
            const JsonValue& attrs = prims[p]["attributes"];
            std::vector<float> pos, nrm, uv, idx;
            int pc = 0, nc = 0, uc = 0, ic = 0;
            if (!readAccessor(g, attrs["POSITION"].integer(), pos, pc) || pc != 3) continue;
            if (attrs.has("NORMAL")) readAccessor(g, attrs["NORMAL"].integer(), nrm, nc);
            if (attrs.has("TEXCOORD_0")) readAccessor(g, attrs["TEXCOORD_0"].integer(), uv, uc);
            size_t count = pos.size() / 3;
            size_t baseVertex = mesh.vertices.size() / 8;
            for (size_t v = 0; v < count; ++v) {
                mesh.vertices.push_back(pos[v * 3]);
                mesh.vertices.push_back(pos[v * 3 + 1]);
                mesh.vertices.push_back(pos[v * 3 + 2]);
                mesh.vertices.push_back(nc == 3 ? nrm[v * 3] : 0);
                mesh.vertices.push_back(nc == 3 ? nrm[v * 3 + 1] : 1);
                mesh.vertices.push_back(nc == 3 ? nrm[v * 3 + 2] : 0);
                mesh.vertices.push_back(uc == 2 ? uv[v * 2] : 0);
                mesh.vertices.push_back(uc == 2 ? uv[v * 2 + 1] : 0);
            }
            if (prims[p].has("indices") && readAccessor(g, prims[p]["indices"].integer(), idx, ic)) {
                for (float f : idx) mesh.indices.push_back(static_cast<uint16_t>(baseVertex + static_cast<size_t>(f)));
            } else {
                for (size_t v = 0; v < count; ++v) mesh.indices.push_back(static_cast<uint16_t>(baseVertex + v));
            }
        }
    }

    // Textura (primeira imagem)
    const JsonValue& images = root["images"];
    if (images.size() > 0) textureUri_ = images[0]["uri"].string();

    // Animações
    const JsonValue& anims = root["animations"];
    clips_.clear();
    for (size_t a = 0; a < anims.size(); ++a) {
        AnimClip clip;
        clip.name = anims[a]["name"].string();
        const JsonValue& samplers = anims[a]["samplers"];
        const JsonValue& channels = anims[a]["channels"];
        for (size_t c = 0; c < channels.size(); ++c) {
            const JsonValue& ch = channels[c];
            const JsonValue& sampler = samplers[static_cast<size_t>(ch["sampler"].integer())];
            AnimChannel out;
            out.node = ch["target"]["node"].integer(-1);
            const std::string& path = ch["target"]["path"].string();
            out.path = path == "translation" ? 0 : (path == "rotation" ? 1 : (path == "scale" ? 2 : -1));
            if (out.path < 0 || out.node < 0) continue;
            const std::string& interp = sampler["interpolation"].string();
            out.step = interp == "STEP";
            out.cubic = interp == "CUBICSPLINE";
            int tc = 0, vc = 0;
            if (!readAccessor(g, sampler["input"].integer(), out.times, tc)) continue;
            if (!readAccessor(g, sampler["output"].integer(), out.values, vc)) continue;
            if (out.cubic) {
                // Mantém só o valor (descarta tangentes in/out).
                std::vector<float> values;
                size_t stride = static_cast<size_t>(vc) * 3;
                for (size_t k = 0; k + stride <= out.values.size(); k += stride)
                    for (int j = 0; j < vc; ++j) values.push_back(out.values[k + vc + j]);
                out.values = values;
            }
            if (!out.times.empty()) clip.duration = std::max(clip.duration, out.times.back());
            clip.channels.push_back(std::move(out));
        }
        clips_.push_back(std::move(clip));
    }

    // Altura na pose de bind.
    Pose pose;
    bindPose(pose);
    std::vector<Mat4> g2;
    globals(pose, g2);
    float minY = 1e9f, maxY = -1e9f;
    for (size_t i = 0; i < nodes_.size(); ++i) {
        if (nodes_[i].mesh < 0 || nodes_[i].mesh >= static_cast<int>(meshes_.size())) continue;
        const auto& verts = meshes_[nodes_[i].mesh].vertices;
        for (size_t v = 0; v + 8 <= verts.size(); v += 8) {
            Vec3 p = g2[i].transformPoint({verts[v], verts[v + 1], verts[v + 2]});
            minY = std::min(minY, p.y);
            maxY = std::max(maxY, p.y);
        }
    }
    if (maxY > minY) {
        bindHeight_ = maxY - minY;
        bindMinY_ = minY;
    }
    LOGI("GLB: %zu nós, %zu malhas, %zu clipes, altura %.2f", nodes_.size(), meshes_.size(), clips_.size(), bindHeight_);
    return !meshes_.empty();
}

int CharacterModel::clipIndex(const std::string& name) const {
    for (size_t i = 0; i < clips_.size(); ++i)
        if (clips_[i].name == name) return static_cast<int>(i);
    return -1;
}

int CharacterModel::nodeIndex(const std::string& name) const {
    for (size_t i = 0; i < nodes_.size(); ++i)
        if (nodes_[i].name == name) return static_cast<int>(i);
    return -1;
}

void CharacterModel::bindPose(Pose& out) const {
    out.t.resize(nodes_.size());
    out.r.resize(nodes_.size());
    out.s.resize(nodes_.size());
    for (size_t i = 0; i < nodes_.size(); ++i) {
        out.t[i] = nodes_[i].translation;
        out.r[i] = nodes_[i].rotation;
        out.s[i] = nodes_[i].scale;
    }
}

void CharacterModel::sample(int clipIndex, float time, bool loop, Pose& pose) const {
    if (clipIndex < 0 || clipIndex >= static_cast<int>(clips_.size())) return;
    const AnimClip& clip = clips_[clipIndex];
    float t = time;
    if (clip.duration > 0) {
        if (loop) t = std::fmod(time, clip.duration);
        else t = std::min(time, clip.duration);
    }
    for (const auto& ch : clip.channels) {
        if (ch.node >= static_cast<int>(nodes_.size()) || ch.times.empty()) continue;
        const int comps = ch.path == 1 ? 4 : 3;
        size_t n = ch.times.size();
        if (ch.values.size() < n * comps) continue;
        size_t k1 = 0;
        while (k1 < n && ch.times[k1] < t) ++k1;
        size_t k0 = k1 == 0 ? 0 : k1 - 1;
        if (k1 >= n) k1 = n - 1;
        float f = 0;
        if (k1 != k0 && !ch.step) {
            float span = ch.times[k1] - ch.times[k0];
            f = span > 1e-6f ? clampf((t - ch.times[k0]) / span, 0, 1) : 0;
        }
        const float* a = &ch.values[k0 * comps];
        const float* b = &ch.values[k1 * comps];
        if (ch.path == 1) {
            pose.r[ch.node] = slerp(Quat(a[0], a[1], a[2], a[3]), Quat(b[0], b[1], b[2], b[3]), f);
        } else {
            Vec3 v(lerpf(a[0], b[0], f), lerpf(a[1], b[1], f), lerpf(a[2], b[2], f));
            if (ch.path == 0) pose.t[ch.node] = v; else pose.s[ch.node] = v;
        }
    }
}

void CharacterModel::globals(const Pose& pose, std::vector<Mat4>& out) const {
    out.resize(nodes_.size());
    for (int i : order_) {
        Mat4 local = Mat4::trs(pose.t[i], pose.r[i], pose.s[i]);
        out[i] = nodes_[i].parent >= 0 ? out[nodes_[i].parent] * local : local;
    }
}

void blendPose(const Pose& a, const Pose& b, float w, Pose& out) {
    size_t n = std::min(a.t.size(), b.t.size());
    out.t.resize(n);
    out.r.resize(n);
    out.s.resize(n);
    for (size_t i = 0; i < n; ++i) {
        out.t[i] = lerp(a.t[i], b.t[i], w);
        out.r[i] = slerp(a.r[i], b.r[i], w);
        out.s[i] = lerp(a.s[i], b.s[i], w);
    }
}

void Animator::play(int clip, bool loop, float fade, float speed) {
    if (clip == clip_ && loop == loop_) {
        speed_ = speed;
        return;
    }
    prevClip_ = clip_;
    prevTime_ = time_;
    prevLoop_ = loop_;
    prevSpeed_ = speed_;
    clip_ = clip;
    time_ = 0;
    loop_ = loop;
    speed_ = speed;
    fadeDuration_ = fade;
    fade_ = prevClip_ >= 0 ? fade : 0;
}

void Animator::update(float dt) {
    time_ += dt * speed_;
    prevTime_ += dt * prevSpeed_;
    if (fade_ > 0) fade_ = std::max(0.0f, fade_ - dt);
}

bool Animator::finished(const CharacterModel& model) const {
    if (clip_ < 0 || clip_ >= static_cast<int>(model.clips().size())) return true;
    return !loop_ && time_ >= model.clips()[clip_].duration;
}

void Animator::evaluate(const CharacterModel& model, Pose& out) {
    model.bindPose(scratchA_);
    model.sample(clip_, time_, loop_, scratchA_);
    if (fade_ > 0 && prevClip_ >= 0 && fadeDuration_ > 0) {
        model.bindPose(scratchB_);
        model.sample(prevClip_, prevTime_, prevLoop_, scratchB_);
        blendPose(scratchB_, scratchA_, 1.0f - fade_ / fadeDuration_, out);
    } else {
        out = scratchA_;
    }
}

}  // namespace zz
