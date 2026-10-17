#include "weapon_asset.h"

#include <cstring>
#include <limits>
#include <utility>
#include <vector>

#include "character.h"
#include "renderer.h"
#include "../core/log.h"
#include "../core/platform.h"

namespace zz {

namespace {

uint32_t readU32(const uint8_t* data) {
    return static_cast<uint32_t>(data[0]) |
           (static_cast<uint32_t>(data[1]) << 8) |
           (static_cast<uint32_t>(data[2]) << 16) |
           (static_cast<uint32_t>(data[3]) << 24);
}

}  // namespace

bool loadConvertedAk47(CharacterAsset& out) {
    std::vector<uint8_t> bytes;
    if (!platform::readAsset("weapons/ak47.zzm", bytes) || bytes.size() < 12 ||
        std::memcmp(bytes.data(), "ZZM1", 4) != 0) {
        LOGE("Modelo convertido do AK ausente ou inválido");
        return false;
    }

    const uint32_t vertexCount = readU32(bytes.data() + 4);
    const uint32_t indexCount = readU32(bytes.data() + 8);
    constexpr size_t kVertexStride = 8;
    constexpr size_t kVertexBytes = kVertexStride * sizeof(float);
    if (vertexCount == 0 || vertexCount > 65535 || indexCount == 0 || indexCount % 3 != 0) {
        LOGE("Contagens inválidas na malha convertida do AK");
        return false;
    }
    const size_t vertexOffset = 12;
    const size_t indexOffset = vertexOffset + static_cast<size_t>(vertexCount) * kVertexBytes;
    if (indexCount > (std::numeric_limits<size_t>::max() - indexOffset) / sizeof(uint16_t)) return false;
    const size_t expectedSize = indexOffset + static_cast<size_t>(indexCount) * sizeof(uint16_t);
    if (expectedSize != bytes.size()) {
        LOGE("Tamanho inválido na malha convertida do AK: %zu != %zu", bytes.size(), expectedSize);
        return false;
    }

    ModelMesh mesh;
    mesh.vertices.resize(static_cast<size_t>(vertexCount) * kVertexStride);
    std::memcpy(mesh.vertices.data(), bytes.data() + vertexOffset, mesh.vertices.size() * sizeof(float));
    mesh.indices.resize(indexCount);
    std::memcpy(mesh.indices.data(), bytes.data() + indexOffset, mesh.indices.size() * sizeof(uint16_t));
    for (uint16_t index : mesh.indices) {
        if (index >= vertexCount) {
            LOGE("Índice fora da malha convertida do AK");
            return false;
        }
    }

    ModelNode root;
    root.name = "ak47-root";
    root.mesh = 0;
    out.model.build({root}, {std::move(mesh)}, {});
    out.pixelArt = false;
    out.doubleSided = true;
    // Acabamento de metal pintado/fosco do rifle, aproximado para a iluminação PBR do modo ultra.
    out.roughness = 0.42f;
    out.metallic = 0.48f;
    if (!platform::decodeAssetImage("weapons/ak47_texture.jpg", 1024, out.image)) {
        LOGE("Textura do AK não pôde ser decodificada");
        out.model = CharacterModel{};
        return false;
    }
    return true;
}

}  // namespace zz
