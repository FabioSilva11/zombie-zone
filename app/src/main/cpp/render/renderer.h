// Renderizador OpenGL ES 3.0: céu, nuvens, chunks voxel, personagens, caixas coloridas e partículas.
#pragma once

#include <GLES3/gl3.h>

#include <memory>
#include <string>
#include <vector>

#include "../core/math.h"
#include "../core/platform.h"
#include "../world/theme.h"
#include "../world/world.h"
#include "character.h"
#include "gl_util.h"

namespace zz {

// Personagem (modelo + textura de skin) com recursos de GPU recriáveis após perda de contexto.
struct CharacterAsset {
    CharacterModel model;
    Image image;
    std::string texturePath;
    struct MeshGpu {
        GLuint vao = 0, vbo = 0, ibo = 0;
        GLsizei count = 0;
    };
    std::vector<MeshGpu> meshes;
    GLuint texture = 0;
    int contextId = -1;
};

struct CharacterDraw {
    CharacterAsset* asset = nullptr;
    Mat4 world;                 // posição/rotação/escala do personagem
    std::vector<Mat4> nodes;    // matrizes globais dos nós (espaço do modelo)
    Vec3 tint{1, 1, 1};
    float flash = 0;            // flash de dano (0..1)
    float alpha = 1;
};

struct BoxDraw {
    Mat4 world;
    Vec4 color;
    float emissive = 0;
};

struct Particle {
    Vec3 position;
    Vec4 color;
    float size;
    bool additive;
};

struct ShadowBlob {
    Vec3 position;
    float radius;
    float alpha;
};

struct Beam {
    Vec3 from, to;
    Vec4 color;
    float width;
};

struct PointLight {
    Vec3 position;
    float radius;
};

struct FrameScene {
    World* world = nullptr;
    Mat4 view, proj;
    Vec3 cameraPos;
    Vec3 cameraForward;
    SkyPreset sky;
    float time = 0;
    std::vector<PointLight> lights;
    Vec3 lightColor{1.0f, 0.75f, 0.45f};
    Vec4 muzzleFlash;  // xyz posição, w intensidade
    std::vector<CharacterDraw> characters;
    size_t characterCount = 0;  // itens válidos em characters (reuso de memória)
    std::vector<BoxDraw> boxes;
    std::vector<Particle> particles;
    std::vector<ShadowBlob> shadows;
    std::vector<Beam> beams;
    bool portalVisible = false;
    Vec3 portalCenter;
    float portalYaw = 0;
    float portalOpen = 0;
    float drawDistance = 140;
};

class Renderer {
public:
    void onSurfaceCreated();
    void onSurfaceChanged(int width, int height);
    void setTheme(const Theme& theme);
    void render(FrameScene& scene);
    int width() const { return width_; }
    int height() const { return height_; }
    int contextId() const { return contextId_; }
    // Prepara GPU de um personagem (textura e malhas) se necessário.
    void ensureCharacter(CharacterAsset& asset);

private:
    struct ChunkGpu {
        GLuint vao[3] = {0, 0, 0};
        GLuint vbo[3] = {0, 0, 0};
        GLsizei quads[3] = {0, 0, 0};
        uint32_t version = 0;
        int contextId = -1;
    };

    void createPrograms();
    void createStaticBuffers();
    void uploadBlockTextures();
    void syncChunks(World& world);
    void ensureQuadIndices(size_t quads);
    void drawSky(const FrameScene& scene, const Mat4& viewProj);
    void drawClouds(const FrameScene& scene, const Mat4& viewProj);
    void drawChunks(const FrameScene& scene, const Mat4& viewProj, int pass);
    void drawCharacters(const FrameScene& scene, const Mat4& viewProj);
    void drawBoxes(const FrameScene& scene, const Mat4& viewProj);
    void drawBillboards(const FrameScene& scene, const Mat4& viewProj);
    void setVoxelUniforms(const FrameScene& scene, const Mat4& viewProj);
    void setModelUniforms(const FrameScene& scene, const Mat4& viewProj);

    int width_ = 1, height_ = 1;
    int contextId_ = 0;
    ShaderProgram voxel_, model_, sky_, billboard_, cloud_;
    GLuint quadIbo_ = 0;
    size_t quadIboCapacity_ = 0;
    GLuint blockTextures_ = 0;
    GLuint cloudTexture_ = 0;
    GLuint softTexture_ = 0;
    GLuint skyVao_ = 0;
    GLuint boxVao_ = 0, boxVbo_ = 0, boxIbo_ = 0;
    GLuint cloudVao_ = 0, cloudVbo_ = 0;
    GLuint billboardVao_ = 0, billboardVbo_ = 0;
    size_t billboardCapacity_ = 0;
    std::vector<float> billboardData_;
    std::vector<ChunkGpu> chunkGpu_;
    const World* lastWorld_ = nullptr;
    uint32_t lastGeneration_ = 0;
    Theme theme_ = themeForStage(1);
    bool themeDirty_ = true;
    Vec4 frustum_[6];
};

}  // namespace zz
