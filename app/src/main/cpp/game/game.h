// Lógica principal: lobby 3D, missão (jogador, zumbis, tiros, coletáveis, portal), câmera e HUD.
#pragma once

#include <array>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "../core/math.h"
#include "../core/random.h"
#include "../render/character.h"
#include "../render/renderer.h"
#include "../world/terrain.h"
#include "../world/theme.h"
#include "../world/world.h"
#include "nav.h"
#include "net.h"
#include "protocol.h"

namespace zz {

struct MissionConfig {
    int stage = 1;
    int skin = 0;
    int zombieCount = 10;
    float zombieHealth = 70;
    float zombieDamage = 8;
    float zombieSpeed = 1.0f;
    float playerHealth = 100;
    int magazine = 30;
    float power = 1.0f;
    int crystalMask = 0;
    int crystalTotal = 3;
    float sensitivity = 1.0f;
    bool aimAssist = true;
    bool autoFire = false;
    bool invertY = false;
    uint32_t seed = 1;
    float reloadSpeed = 1.0f;
};

struct PvpConfig {
    int localId = 0;
    bool host = false;
    std::string hostIp;
    uint32_t seed = 1;
    int killLimit = 10;
    float timeLimit = 300;
    float sensitivity = 1.0f;
    bool invertY = false;
    uint8_t skins[kMaxNetPlayers] = {255, 255, 255, 255, 255, 255, 255, 255};
};

struct RemoteSample {
    float time = 0;
    Vec3 pos;
    float yaw = 0, pitch = 0, speed = 0;
    uint8_t flags = 0;
};

// Jogador de outro aparelho, desenhado com interpolação (100 ms de atraso).
struct RemotePlayer {
    bool present = false;
    CharacterAsset* asset = nullptr;
    Animator anim;
    RemoteSample samples[8];
    int sampleCount = 0;
    uint32_t lastVersion = 0;
    Vec3 renderPos;
    float renderYaw = 0, renderPitch = 0, speed = 0;
    uint8_t flags = 0;
    float fireFlash = 0;
    float lastFire = -10;
    bool alive = true;
};

// Entrada de toque escrita pela thread de UI e consumida no quadro.
struct InputState {
    float moveX = 0, moveY = 0;
    float lookX = 0, lookY = 0;  // acumulado em dp
    bool fire = false;
    bool aim = false;
    int jumps = 0;
    int reloads = 0;
    float previewRotate = 0;
};

enum class ZombieKind { Walker = 0, Runner = 1, Brute = 2 };
enum class ZState { Idle, Wander, Chase, Attack, Dead };

struct Zombie {
    ZombieKind kind = ZombieKind::Walker;
    CharacterAsset* asset = nullptr;
    BodyState body;
    Animator anim;
    float yaw = 0;
    float health = 0, maxHealth = 0;
    float speed = 2.3f;
    float damage = 8;
    float scale = 1.0f;
    Vec3 tint{1, 1, 1};
    ZState state = ZState::Idle;
    bool alerted = false;
    float lostSight = 0;
    float windup = 0;
    float cooldown = 0;
    bool attackHit = false;
    float hitFlash = 0;
    float deathTime = 0;
    float groanTimer = 3;
    float perceptionTimer = 0;
    bool canSee = false;
    Vec3 home;
    Vec3 wanderTarget;
    float wanderTimer = 0;
    float stuckTimer = 0;
    float sidestepTimer = 0;
    Vec3 sidestepDir;
    Vec3 lastPos;
    float stun = 0;
    float barTimer = 0;
};

struct Pickup {
    int type = 0;  // 0 cristal, 1 vida
    int crystalIndex = -1;
    Vec3 position;
    Vec3 velocity;
    float age = 0;
    bool taken = false;
};

struct FxParticle {
    Vec3 position, velocity;
    Vec4 color;
    float size = 0.1f;
    float life = 0, maxLife = 1;
    float gravity = 0;
    bool additive = false;
};

struct FxLabel {
    Vec3 position;
    int value = 0;
    int kind = 0;  // 1 dano, 2 tiro na cabeça, 3 cura
    float life = 0;
};

struct Tracer {
    Vec3 from, to;
    float life = 0;
};

class Game {
public:
    Game();
    void onSurfaceCreated();
    void onSurfaceChanged(int width, int height);
    void frame(double nowSeconds);

    // Comandos (thread GL)
    void enterLobby(int skin, int screen);
    void setPreviewSkin(int skin);
    void startMission(const MissionConfig& config);
    void startPvp(const PvpConfig& config);
    void stopPvp();
    void setPaused(bool paused);
    void revive();
    void setSettings(float sensitivity, bool aimAssist, bool autoFire, bool invertY);

    // Entrada (qualquer thread)
    void setMove(float x, float y);
    void addLook(float dx, float dy);
    void setFire(bool held);
    void setAim(bool on);
    void pressJump();
    void pressReload();
    void rotatePreview(float dx);

    // Saídas (qualquer thread)
    void copyHud(float* out, int count);
    int drainEvents(int* out, int maxInts);
    void copyResult(float* out, int count);
    // Minimapa RGBA (thread GL após startMission).
    int minimapSize() const { return world_.width(); }
    void copyMinimap(uint32_t* argb, int count);

private:
    enum class Mode { None, Lobby, Mission, Pvp };

    CharacterAsset* character(int skin);
    void emit(int type, int a = 0, int b = 0, int c = 0);
    void emitSound(int sound, const Vec3& at, float volume = 1.0f);

    void updateLobby(float dt);
    void updateMission(float dt);
    void updatePvp(float dt);
    void updateRemotes(float dt);
    void resetPlayerState(const Vec3& position, float yaw, float health, int magazine);
    void addRifle(const CharacterDraw& draw, int armRight, Vec3* muzzle);
    void poseArmed(Pose& pose, const CharacterModel& model, float pitch, bool moveHead);
    void updatePlayer(float dt, const InputState& in);
    void updateCamera(float dt, const InputState& in);
    void updateWeapon(float dt, const InputState& in);
    void fireShot();
    void updateZombies(float dt);
    void updateZombie(Zombie& z, size_t index, float dt);
    void damageZombie(Zombie& z, float amount, bool headshot, const Vec3& dir, const Vec3& hitPoint);
    void killZombie(Zombie& z, bool headshot);
    void damagePlayer(float amount, const Vec3& from);
    void updatePickups(float dt);
    void updateEffects(float dt);
    void spawnBurst(const Vec3& at, const Vec4& color, int count, float speed, float size, bool additive, float gravity);
    void alertNear(const Vec3& at, float radius);
    float zombieHeight(const Zombie& z) const { return 1.8f * z.scale; }

    void buildScene();
    void addCharacter(CharacterAsset* asset, const Vec3& pos, float yaw, float scale, const Pose& pose, const Vec3& tint, float flash);
    void posePlayer(Pose& pose, bool armed);
    void poseZombieArms(Pose& pose, const CharacterModel& model, float t);
    void publishHud();
    bool project(const Vec3& world, float& sx, float& sy) const;

    Renderer renderer_;
    World world_;
    NavField nav_;
    WorldLayout layout_;
    Theme theme_ = lobbyTheme();
    Mode mode_ = Mode::None;
    bool paused_ = false;
    MissionConfig config_;
    std::map<int, std::unique_ptr<CharacterAsset>> characters_;
    std::vector<Vec3> layerColors_;

    // Entrada
    std::mutex inputMutex_;
    InputState input_;
    bool aimToggle_ = false;

    // Saídas
    std::mutex outMutex_;
    std::vector<int> events_;
    std::array<float, proto::H_SIZE> hud_{};
    std::array<float, proto::R_SIZE> result_{};

    // Tempo
    double lastTime_ = -1;
    float time_ = 0;
    float missionTime_ = 0;
    float fps_ = 60;

    // Jogador
    BodyState player_;
    CharacterAsset* playerAsset_ = nullptr;
    Animator playerAnim_;
    float playerYaw_ = 0;
    float playerModelYaw_ = 0;
    float health_ = 100, maxHealth_ = 100;
    float visualStep_ = 0;
    float invulnerable_ = 0;
    float damageFlash_ = 0;
    float damageDir_ = 0, damageDirTimer_ = 0;
    bool dead_ = false;
    bool victory_ = false;
    bool sprinting_ = false;
    float stepTimer_ = 0;
    bool wasInWater_ = false;
    float lobbyEmoteTimer_ = 5;
    float previewYaw_ = 0;
    int lobbyScreen_ = 0;

    // Arma
    int ammo_ = 30;
    float reloadTimer_ = 0;
    float reloadDuration_ = 1.6f;
    float fireCooldown_ = 0;
    float bloom_ = 0;
    float recoil_ = 0;
    float aimBlend_ = 0;
    float hitMark_ = 0;
    bool headshotMark_ = false;
    float muzzleTimer_ = 0;
    Vec3 muzzlePos_;
    bool firingNow_ = false;
    bool targetLocked_ = false;
    float targetHp_ = 0;
    int assistTarget_ = -1;
    float assistAngle_ = 10;
    int shots_ = 0, hits_ = 0;
    int combo_ = 0;
    float comboTimer_ = 0;

    // Câmera
    float camYaw_ = 0, camPitch_ = -0.12f;
    float camDistance_ = 4.2f;
    Vec3 camPos_, camForward_;
    Mat4 view_, proj_;
    float fov_ = 1.0f;

    // Missão
    std::vector<Zombie> zombies_;
    std::vector<Pickup> pickups_;
    std::vector<FxParticle> particles_;
    std::vector<FxLabel> labels_;
    std::vector<Tracer> tracers_;
    int crystalsSpawnedMask_ = 0;
    int crystalsCollectedMask_ = 0;
    int kills_ = 0, headshots_ = 0;
    float damageTaken_ = 0;
    bool portalOpen_ = false;
    float portalAnim_ = 0;
    float navTimer_ = 0;
    int navX_ = -1, navZ_ = -1;
    float fireEmberTimer_ = 0;
    Rng rng_{1234};

    // PvP
    NetSession net_;
    PvpConfig pvp_;
    RemotePlayer remotes_[kMaxNetPlayers];
    std::vector<NetEvent> netEvents_;
    uint8_t pvpSpawnSeq_ = 0;
    float pvpHealthSeen_ = 100;
    bool pvpEnded_ = false;
    bool pvpLost_ = false;

    FrameScene scene_;
    Pose scratchPose_;
    std::vector<Mat4> scratchGlobals_;
};

}  // namespace zz
