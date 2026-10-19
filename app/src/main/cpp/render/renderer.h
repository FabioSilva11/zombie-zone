// Renderizador OpenGL ES 3.0: céu, nuvens, chunks voxel (com vento na grama e nas folhas), personagens,
// caixas coloridas, partículas e decalques. O modo cinematográfico adiciona sombras do sol em tempo real,
// bloom, raios de sol, névoa baixa, tone mapping filmico, correção de cor e vinheta.
#pragma once

#include <GLES3/gl3.h>

#include <memory>
#include <string>
#include <vector>

#include "../core/math.h"
#include "../core/perf.h"
#include "../core/platform.h"
#include "../world/theme.h"
#include "../world/world.h"
#include "character.h"
#include "gl_util.h"

namespace zz {

// Personagem (modelo + textura de pele) com recursos de GPU recriáveis após perda de contexto.
struct CharacterAsset {
    CharacterModel model;
    Image image;
    bool pixelArt = false;  // pele em pixel art: ampliação sem suavizar
    bool doubleSided = false;
    float roughness = 0.82f;
    float metallic = 0.0f;
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
    Vec3 flashColor{1.0f, 0.25f, 0.2f};
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

// Marca plana no chão: 0 = mancha suave, 1 = anel de aviso pulsante.
struct Decal {
    Vec3 position;
    float radius;
    Vec4 color;
    int mode = 0;
};

struct Beam {
    Vec3 from, to;
    Vec4 color;
    float width;
};

struct PointLight {
    Vec3 position;
    float radius;
    Vec3 color{1.0f, 0.72f, 0.42f};
};

// Orçamento de upload de malhas para a GPU por quadro (o mais próximo sempre sobe: piso de 1).
struct UploadBudget {
    int maxChunks = 4;
    size_t maxBytes = 384 * 1024;
    bool unlimited = false;
};

// Contadores do último quadro desenhado (diagnóstico).
struct RenderStats {
    int uploads = 0, uploadsPending = 0, gpuDeletes = 0;
    size_t uploadBytes = 0, gpuBytes = 0;      // gpuBytes: VBOs de chunk residentes
    float uploadMs = 0;
    int chunkDraws[3] = {0, 0, 0};             // opaco, recorte, água (passe principal)
    int shadowChunkDraws = 0;                  // mapa de sombras (cinematic)
    int characterDraws = 0, partDraws = 0;     // personagens e partes (1 draw por parte)
    int boxDraws = 0, billboardFlushes = 0;
    int culledCharacters = 0, culledBoxes = 0;
};

struct FrameScene {
    World* world = nullptr;
    Mat4 view, proj;
    Vec3 cameraPos;
    Vec3 cameraForward;
    SkyPreset sky;
    float time = 0;
    std::vector<PointLight> lights;
    Vec4 muzzleFlash;  // xyz posição, w intensidade
    std::vector<CharacterDraw> characters;
    size_t characterCount = 0;  // itens válidos em characters (reuso de memória)
    std::vector<BoxDraw> boxes;
    std::vector<Particle> particles;
    std::vector<ShadowBlob> shadows;
    std::vector<Decal> decals;
    std::vector<Beam> beams;
    bool portalVisible = false;
    Vec3 portalCenter;
    float portalYaw = 0;
    float portalOpen = 0;
    float drawDistance = 140;
    Vec3 focus;  // centro da área de sombras (o jogador)
    UploadBudget upload;
};

class Renderer {
public:
    void onSurfaceCreated();
    void onSurfaceChanged(int width, int height);
    void setTheme(const Theme& theme);
    void setCinematic(bool on) { cinematic_ = on; }
    bool cinematic() const { return cinematic_; }
    void render(FrameScene& scene);
    int width() const { return width_; }
    int height() const { return height_; }
    int contextId() const { return contextId_; }
    // Prepara GPU de um personagem (textura e malhas) se necessário.
    void ensureCharacter(CharacterAsset& asset);
    // Libera a GPU de um personagem que saiu do cache.
    void releaseCharacter(CharacterAsset& asset);
    const RenderStats& stats() const { return stats_; }
    int pendingUploads() const { return stats_.uploadsPending; }

private:
    struct Target {
        GLuint fbo = 0, color = 0, depth = 0;
        int w = 0, h = 0;
    };

    void createPrograms();
    void createStaticBuffers();
    void uploadBlockTextures();
    void syncChunks(World& world, const FrameScene& scene);
    void uploadChunk(Chunk& chunk, size_t bytes);
    void ensureQuadIndices(size_t quads);
    bool ensurePostTargets();
    void drawSky(const FrameScene& scene, const Mat4& viewProj);
    void drawClouds(const FrameScene& scene, const Mat4& viewProj);
    void drawChunks(const FrameScene& scene, int pass, const Vec4* planes, float maxDist, const Vec3& center, int* drawCounter);
    void drawCharacters(const FrameScene& scene, const ShaderProgram& program, bool depthOnly);
    void drawBoxes(const FrameScene& scene, const ShaderProgram& program);
    void drawBillboards(const FrameScene& scene, const Mat4& viewProj);
    void renderShadowMap(const FrameScene& scene);
    void postProcess(const FrameScene& scene, const Mat4& viewProj);
    const ShaderProgram& voxelProgram() const { return useShadows() ? voxelLit_ : voxel_; }
    const ShaderProgram& modelProgram() const { return useShadows() ? modelLit_ : model_; }
    bool useShadows() const { return cinematic_ && shadowReady_; }
    void setVoxelUniforms(const FrameScene& scene, const ShaderProgram& p, const Mat4& viewProj);
    void setModelUniforms(const FrameScene& scene, const ShaderProgram& p, const Mat4& viewProj);
    void setLightUniforms(const FrameScene& scene, const ShaderProgram& p);
    void drawFullscreen();

    int width_ = 1, height_ = 1;
    int contextId_ = 0;
    bool cinematic_ = false;
    bool shadowReady_ = false;
    ShaderProgram voxel_, voxelLit_, voxelDepth_;
    ShaderProgram model_, modelLit_, modelDepth_;
    ShaderProgram sky_, billboard_, cloud_;
    ShaderProgram bright_, blur_, rays_, composite_;
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
    std::vector<ChunkGpu> garbage_;
    std::vector<Chunk*> uploadQueue_;  // reuso entre quadros
    size_t gpuBytes_ = 0;
    RenderStats stats_;
    Theme theme_ = themeForStage(1);
    bool themeDirty_ = true;
    Vec4 frustum_[6];
    // Sombras e pós-processamento (modo cinematográfico).
    GLuint shadowFbo_ = 0, shadowTex_ = 0;
    int shadowContext_ = -1;
    Mat4 shadowMatrix_;
    Vec4 shadowPlanes_[6];
    Vec3 shadowCenter_;
    Target scene_, bloomA_, bloomB_;
    int postContext_ = -1;
};

}  // namespace zz
