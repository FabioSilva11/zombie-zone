#include "game.h"
#include "../render/weapon_asset.h"

#include <GLES3/gl3.h>

#include <algorithm>
#include <cmath>
#include <cstdio>

#include "../core/log.h"
#include "../core/platform.h"
#include "../world/block_textures.h"

namespace zz {

using namespace proto;

namespace {

constexpr float kGravity = 28.0f;
constexpr float kJumpVelocity = 8.6f;
constexpr float kStepHeight = 1.05f;
constexpr float kPlayerSpeed = 4.5f;

bool rayBox(const Vec3& o, const Vec3& d, const Vec3& mn, const Vec3& mx, float& tHit) {
    float tmin = 0, tmax = 1e9f;
    for (int a = 0; a < 3; ++a) {
        if (std::fabs(d[a]) < 1e-7f) {
            if (o[a] < mn[a] || o[a] > mx[a]) return false;
            continue;
        }
        float inv = 1.0f / d[a];
        float t1 = (mn[a] - o[a]) * inv, t2 = (mx[a] - o[a]) * inv;
        if (t1 > t2) std::swap(t1, t2);
        tmin = std::max(tmin, t1);
        tmax = std::min(tmax, t2);
        if (tmin > tmax) return false;
    }
    tHit = tmin;
    return true;
}

Vec3 cameraDirection(float yaw, float pitch) {
    float cp = std::cos(pitch);
    return {std::sin(yaw) * cp, std::sin(pitch), -std::cos(yaw) * cp};
}

int locomotionClip(const CharacterModel& model, const Vec3& velocity, float yaw, float speed, bool sprinting, bool crouched = false, bool knife = false) {
    if (speed <= 0.5f) return model.clipIndex(crouched ? "crouch-idle" : "idle");
    Vec3 planar{velocity.x, 0.0f, velocity.z};
    if (lengthXZ(planar) <= 0.1f) return model.clipIndex("walk");
    float forward = dot(planar, forwardFromYaw(yaw));
    float side = dot(planar, rightFromYaw(yaw));
    if (sprinting) {
        if (knife) return model.clipIndex("knife-sprint");
        return forward < -0.4f ? model.clipIndex("run-back") : model.clipIndex("sprint");
    }
    if (std::fabs(side) > std::fabs(forward) * 1.15f)
        return model.clipIndex(side < 0.0f ? "strafe-left" : "strafe-right");
    if (forward < -0.4f) return model.clipIndex(knife ? "knife-back" : (crouched ? "crouch-back" : "walk-back"));
    return model.clipIndex(knife ? "knife-walk" : (crouched ? "crouch-walk" : "walk"));
}

}  // namespace

Game::Game() {
    hud_.fill(0);
    result_.fill(0);
    ak47Loaded_ = loadConvertedAk47(ak47Asset_);
}

void Game::onSurfaceCreated() {
    renderer_.onSurfaceCreated();
    renderer_.setTheme(theme_);
}

void Game::onSurfaceChanged(int width, int height) { renderer_.onSurfaceChanged(width, height); }

// Personagem de blocos gerado a partir da aparência (cache por código).
CharacterAsset* Game::character(uint32_t look) {
    auto it = characters_.find(look);
    if (it != characters_.end()) return it->second.get();
    // No editor cada ajuste cria uma aparência nova: limpa o cache no lobby (sem zumbis em cena).
    if (characters_.size() > 40 && mode_ == Mode::Lobby) {
        for (auto i = characters_.begin(); i != characters_.end();) {
            if (i->second.get() == playerAsset_) {
                ++i;
                continue;
            }
            renderer_.releaseCharacter(*i->second);
            i = characters_.erase(i);
        }
    }
    auto asset = std::make_unique<CharacterAsset>();
    buildBlockyCharacter(look, *asset);
    CharacterAsset* raw = asset.get();
    characters_[look] = std::move(asset);
    return raw;
}

void Game::emit(int type, int a, int b, int c) {
    std::lock_guard<std::mutex> lock(outMutex_);
    if (events_.size() > 4096) return;
    events_.insert(events_.end(), {type, a, b, c});
}

void Game::emitSound(int sound, const Vec3& at, float volume) {
    float dist = length(at - camPos_);
    float v = volume * clampf(1.0f - dist / 38.0f, 0.0f, 1.0f);
    if (mode_ == Mode::Mission && dist < 2.5f) v = volume;
    if (v < 0.03f) return;
    Vec3 rel = at - camPos_;
    Vec3 right = rightFromYaw(camYaw_);
    float pan = lengthXZ(rel) > 0.5f ? clampf(dot(normalize(Vec3(rel.x, 0, rel.z)), right), -1.0f, 1.0f) : 0.0f;
    emit(EV_SFX, sound, static_cast<int>(v * 100), static_cast<int>(pan * 80));
}

// ----------------------------------------------------------------------------- comandos

void Game::enterLobby(uint32_t skin, int screen) {
    stopPvp();
    bool regenerate = mode_ != Mode::Lobby;
    if (regenerate) {
        prof_.skipFrames(3);
        uploadUnlimitedFrames_ = 3;
        streamStats_ = StreamStats{};
    }
    mode_ = Mode::Lobby;
    paused_ = false;
    lobbyScreen_ = screen;
    if (regenerate) {
        theme_ = lobbyTheme();
        renderer_.setTheme(theme_);
        layout_ = generateLobbyWorld(world_, theme_);
        island_.reset();  // o mundo finito já não aponta para a ilha
        world_.rebuildDirty(1 << 20);
        zombies_.clear();
        pickups_.clear();
        particles_.clear();
        labels_.clear();
        tracers_.clear();
        projectiles_.clear();
        hazards_.clear();
        smokeClouds_.clear();
        explosions_.clear();
        flashes_.clear();
        nests_.clear();
        chests_.clear();
        supply_ = SupplyDrop{};
        player_ = BodyState{};
        player_.position = layout_.start;
        camYaw_ = 0;
        camPitch_ = 0;
        portalOpen_ = false;
        previewYaw_ = 0;
    }
    playerAsset_ = character(skin);
    playerAnim_ = Animator();
    if (playerAsset_) playerAnim_.play(playerAsset_->model.clipIndex("idle"), true, 0);
    dead_ = false;
    lobbyEmoteTimer_ = 1.2f;
    lobbyEmoteCount_ = 0;
}

void Game::setPreviewSkin(uint32_t skin) {
    playerAsset_ = character(skin);
    playerAnim_ = Animator();
    if (playerAsset_) {
        playerAnim_.play(playerAsset_->model.clipIndex("wave"), false, 0);
        lobbyEmoteTimer_ = 6.0f;
        lobbyEmoteCount_ = 1;  // depois do aceno vem a dança
    }
}

void Game::rotatePreview(float dx) {
    std::lock_guard<std::mutex> lock(inputMutex_);
    input_.previewRotate += dx;
}

void Game::setSettings(float sensitivity, bool aimAssist, bool autoFire, bool invertY) {
    config_.sensitivity = sensitivity;
    config_.aimAssist = aimAssist;
    config_.autoFire = autoFire;
    config_.invertY = invertY;
}

void Game::setGraphics(int quality, bool cinematic) {
    quality_ = quality;
    viewRadius_ = quality <= 0 ? 6 : (quality >= 2 ? 10 : 8);
    renderer_.setCinematic(cinematic);
}

void Game::setPaused(bool paused) {
    paused_ = mode_ == Mode::Pvp ? false : paused;
    std::lock_guard<std::mutex> lock(inputMutex_);
    input_.fire = false;
    input_.lookX = input_.lookY = 0;
}

void Game::revive() {
    if (mode_ != Mode::Mission || !dead_) return;
    dead_ = false;
    health_ = maxHealth_ * 0.6f;
    invulnerable_ = 3.0f;
    ammo_ = config_.magazine;
    reloadTimer_ = 0;
    burning_ = bleeding_ = poisoned_ = slowed_ = 0;
    for (auto& z : zombies_) {
        if (z.state == ZState::Dead) continue;
        Vec3 d = z.body.position - player_.position;
        float dist = lengthXZ(d);
        if (dist < 8.0f) {
            Vec3 push = dist > 0.01f ? normalize(Vec3(d.x, 0, d.z)) : Vec3(1, 0, 0);
            z.body.velocity = push * 9.0f + Vec3(0, 5.0f, 0);
            z.stun = 1.6f;
            z.action = ZAction::None;
            z.state = ZState::Chase;
        }
    }
    projectiles_.clear();
    hazards_.erase(std::remove_if(hazards_.begin(), hazards_.end(), [&](const Hazard& h) { return distXZ(h.pos, player_.position) < h.radius + 4.0f; }), hazards_.end());
    spawnBurst(player_.position + Vec3(0, 1, 0), {0.6f, 1.0f, 0.4f, 1.0f}, 30, 6.0f, 0.18f, true, 2.0f);
    if (playerAsset_) playerAnim_.play(playerAsset_->model.clipIndex("idle"), true, 0.2f);
}

// ----------------------------------------------------------------------------- entrada

void Game::setMove(float x, float y) {
    std::lock_guard<std::mutex> lock(inputMutex_);
    input_.moveX = x;
    input_.moveY = y;
}

void Game::addLook(float dx, float dy) {
    std::lock_guard<std::mutex> lock(inputMutex_);
    input_.lookX += dx;
    input_.lookY += dy;
}

void Game::setFire(bool held) {
    std::lock_guard<std::mutex> lock(inputMutex_);
    input_.fire = held;
}

void Game::setAim(bool on) {
    std::lock_guard<std::mutex> lock(inputMutex_);
    input_.aim = on;
    input_.aimStage = on ? 1 : 0;
}

void Game::setAimStage(int stage) {
    std::lock_guard<std::mutex> lock(inputMutex_);
    input_.aimStage = std::max(0, std::min(2, stage));
    input_.aim = input_.aimStage > 0;
}

void Game::pressJump() {
    std::lock_guard<std::mutex> lock(inputMutex_);
    ++input_.jumps;
}

void Game::pressReload() {
    std::lock_guard<std::mutex> lock(inputMutex_);
    ++input_.reloads;
}

void Game::requestSupply() {
    std::lock_guard<std::mutex> lock(inputMutex_);
    ++input_.supplyRequests;
}

void Game::toggleCrouch() {
    std::lock_guard<std::mutex> lock(inputMutex_);
    ++input_.crouchToggles;
}

void Game::toggleSprint() {
    std::lock_guard<std::mutex> lock(inputMutex_);
    ++input_.sprintToggles;
}

void Game::pressMelee() {
    std::lock_guard<std::mutex> lock(inputMutex_);
    ++input_.melees;
}

void Game::pressGrenade() {
    std::lock_guard<std::mutex> lock(inputMutex_);
    ++input_.grenades;
}

void Game::swapShoulder() {
    std::lock_guard<std::mutex> lock(inputMutex_);
    ++input_.shoulderSwaps;
}

// Copia a entrada do quadro e zera os toques de uso único (o pedido de suprimentos só é zerado
// quando consumido pela missão, para sobreviver à pausa do anúncio).
void Game::consumeInput(InputState& in) {
    std::lock_guard<std::mutex> lock(inputMutex_);
    in = input_;
    input_.lookX = input_.lookY = 0;
    input_.jumps = input_.reloads = 0;
    input_.previewRotate = 0;
    input_.supplyRequests = 0;
    input_.crouchToggles = input_.sprintToggles = input_.melees = input_.grenades = input_.shoulderSwaps = 0;
    input_.tapCount = input_.useCount = 0;
    input_.throwReleases = input_.throwCancels = 0;
}

// ----------------------------------------------------------------------------- quadro

void Game::frame(double now) {
    perf::FrameGuard guard(prof_, now);  // begin() agora, end() em qualquer saída
    ZZ_TRACE_SCOPE("ZZ frame");
    float dt = lastTime_ < 0 ? 1.0f / 60.0f : static_cast<float>(now - lastTime_);
    lastTime_ = now;
    if (dt > 0) fps_ = lerpf(fps_, 1.0f / std::max(dt, 1e-3f), 0.05f);
    dt = clampf(dt, 0.0f, 0.05f);
    if (autopilot_.active && mode_ != Mode::Mission) {  // saiu da missão no meio do autopiloto
        prof_.endRun();
        autopilot_.active = false;
        noEnemies_ = false;
    }
    if (autopilot_.active) dt = 1.0f / 60.0f;  // trajetória independente da taxa de quadros
    time_ += dt;
    prof_.setContext(static_cast<int>(mode_), quality_, renderer_.cinematic());  // o log ZZPerf (5 s) substitui o antigo "FPS"

    if (mode_ == Mode::Lobby) updateLobby(dt);
    else if (mode_ == Mode::Pvp) updatePvp(dt);
    else if (mode_ == Mode::Mission) {
        if (!paused_) updateMission(dt);
        else {
            std::lock_guard<std::mutex> lock(inputMutex_);
            input_.lookX = input_.lookY = 0;
            input_.jumps = input_.reloads = 0;
            input_.crouchToggles = input_.sprintToggles = input_.melees = input_.grenades = input_.shoulderSwaps = 0;
            input_.throwReleases = input_.throwCancels = 0;
            input_.throwAim = false;
        }
    }
    if (!world_.streaming()) world_.rebuildDirty(6);
    if (mode_ == Mode::None) {
        glClearColor(0.03f, 0.05f, 0.08f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        return;
    }
    {
        perf::Scoped t(prof_, perf::Sec::Scene);
        buildScene();
    }
    // Orçamento de upload só vale no mundo em streaming; lobby/PvP (mundo finito) e os quadros pós-carga sobem tudo.
    scene_.upload.unlimited = uploadUnlimitedFrames_ > 0 || !world_.streaming();
    if (uploadUnlimitedFrames_ > 0) --uploadUnlimitedFrames_;
    {
        perf::Scoped t(prof_, perf::Sec::Render);
        renderer_.render(scene_);
    }
    const RenderStats& rs = renderer_.stats();
    prof_.add(perf::Sec::Upload, rs.uploadMs);
    prof_.count(perf::Cnt::Uploads, rs.uploads);
    prof_.count(perf::Cnt::UploadBytes, static_cast<long long>(rs.uploadBytes));
    prof_.count(perf::Cnt::ChunkDraws, rs.chunkDraws[0] + rs.chunkDraws[1] + rs.chunkDraws[2]);
    prof_.count(perf::Cnt::CharDraws, rs.partDraws);
    prof_.count(perf::Cnt::BoxDraws, rs.boxDraws);
    prof_.count(perf::Cnt::BillboardDraws, rs.billboardFlushes);
    prof_.count(perf::Cnt::Culled, rs.culledCharacters + rs.culledBoxes);
    prof_.gauge(streamStats_.pending, streamStats_.pendingUrgent, rs.uploadsPending, static_cast<int>(world_.chunks().size()),
                static_cast<float>(rs.gpuBytes) / (1024.0f * 1024.0f), perfFlags());
    if (mode_ == Mode::Mission || mode_ == Mode::Pvp) {
        perf::Scoped t(prof_, perf::Sec::Hud);
        publishHud();
    }
}

int Game::perfFlags() const {
    int f = 0;
    if (world_.streaming()) f |= 1;
    if (streamStats_.timeCapped) f |= 2;
    if (prof_.sustainedSlow()) f |= 4;
    if (autopilot_.active) f |= 8;
    return f;
}

void Game::updateLobby(float dt) {
    InputState in;
    consumeInput(in);
    previewYaw_ += in.previewRotate * 0.012f;
    if (lobbyScreen_ == 0) previewYaw_ = lerpAngle(previewYaw_, 0.0f, damp(1.5f, dt));
    if (playerAsset_) {
        playerAnim_.update(dt);
        lobbyEmoteTimer_ -= dt;
        if (playerAnim_.finished(playerAsset_->model)) playerAnim_.play(playerAsset_->model.clipIndex("idle"), true, 0.25f);
        if (lobbyEmoteTimer_ <= 0) {
            // Sem arma no menu: o herói alterna entre acenar e dançar, com pausas curtas.
            int clip = playerAsset_->model.clipIndex(lobbyEmoteCount_++ % 2 == 0 ? "wave" : "dance");
            playerAnim_.play(clip, false, 0.25f);
            float length = clip >= 0 ? playerAsset_->model.clips()[static_cast<size_t>(clip)].duration : 3.0f;
            lobbyEmoteTimer_ = length + rng_.range(2.5f, 4.5f);
        }
    }
    updateEffects(dt);

    Vec3 c = layout_.start;
    Vec3 target, eye;
    if (lobbyScreen_ == 1) {
        // Editor de personagem: o menu ocupa a faixa esquerda, então o herói fica à direita.
        target = c + Vec3(-2.35f, 0.95f, 0);
        eye = target + Vec3(0.0f, 0.3f, 4.4f);
    } else if (lobbyScreen_ == 2) {
        // Inventário: o herói aparece no painel "Personagem" à esquerda da tela.
        target = c + Vec3(5.0f, 0.62f, 0);
        eye = target + Vec3(0.0f, 0.3f, 6.8f);
    } else {
        target = c + Vec3(1.3f, 1.05f, 0);
        eye = target + Vec3(std::sin(time_ * 0.2f) * 0.25f, 0.5f + std::sin(time_ * 0.13f) * 0.06f, 4.4f);
    }
    camPos_ = eye;
    camForward_ = normalize(target - eye);
    camYaw_ = yawFromDirection(camForward_.x, camForward_.z);
    view_ = Mat4::lookAt(eye, target, {0, 1, 0});
    float aspect = static_cast<float>(renderer_.width()) / static_cast<float>(renderer_.height());
    proj_ = Mat4::perspective(0.85f, aspect, 0.08f, 200.0f);
}

void Game::updateMission(float dt) {
    InputState in;
    consumeInput(in);
    aimStage_ = std::max(0, std::min(2, in.aimStage));
    aimToggle_ = in.aim || aimStage_ > 0;
    if (autopilot_.active) perfDrive(in);
    if (!victory_) missionTime_ += dt;

    streamWorld();
    for (int i = 0; i < in.tapCount; ++i) handleSlotTap(in.taps[i]);
    for (int i = 0; i < in.useCount; ++i) useItemSlot(in.uses[i]);
    updateCamera(dt, in);
    updatePlayer(dt, in);
    if (!dead_ && !victory_) updateWeapon(dt, in);
    else {
        firingNow_ = false;
        targetLocked_ = false;
    }
    updateThrowAim(dt, in);
    updateItemStatus(dt);
    updateStatus(dt);
    if (survival()) {
        if (!dead_) survivalTime_ += dt;
        updateWaves(dt);
    }
    updateContract(dt);

    // Recalcula o campo de fluxo quando o jogador muda de coluna (ou periodicamente).
    navTimer_ -= dt;
    int px = static_cast<int>(std::floor(player_.position.x)), pz = static_cast<int>(std::floor(player_.position.z));
    if (navTimer_ <= 0 && (px != navX_ || pz != navZ_)) {
        ZZ_TRACE_SCOPE("ZZ nav");
        perf::Scoped navTimerScope(prof_, perf::Sec::Nav);
        nav_.compute(world_, px, pz, 60);
        navX_ = px;
        navZ_ = pz;
        navTimer_ = 0.3f;
    }

    if (!survival()) updateSpawner(dt);
    updatePois(dt);
    updateNests(dt);
    updateZombies(dt);
    updateProjectiles(dt);
    updateHazards(dt);
    processExplosions();
    updatePickups(dt);
    updateChests(dt);
    updateSupply(dt, in);
    updateEffects(dt);

    minimapTimer_ -= dt;
    if (minimapTimer_ <= 0 || std::fabs(player_.position.x - minimapCX_) > 26 || std::fabs(player_.position.z - minimapCZ_) > 26) {
        ZZ_TRACE_SCOPE("ZZ minimap");
        perf::Scoped minimapScope(prof_, perf::Sec::Minimap);
        rebuildMinimap();
    }

    int needed = (1 << static_cast<int>(nests_.size())) - 1;
    if (!portalOpen_ && needed > 0 && (crystalsCollectedMask_ & needed) == needed) {
        portalOpen_ = true;
        LOGI("OBJ portal_aberto t=%.1f abates=%d", missionTime_, kills_);
        emit(EV_PORTAL_OPEN);
        emitSound(SND_PORTAL, camPos_, 0.9f);
    }
    if (portalOpen_) portalAnim_ = std::min(1.0f, portalAnim_ + dt * 0.8f);

    if (portalOpen_ && !victory_ && !dead_) {
        Vec3 d = player_.position - layout_.portalBase;
        Vec3 n = forwardFromYaw(layout_.portalYaw);
        Vec3 axis(-n.z, 0, n.x);
        if (std::fabs(dot(d, axis)) < 1.6f && std::fabs(dot(d, n)) < 1.0f && d.y > -1.0f && d.y < 3.0f) {
            victory_ = true;
            finishResult();
            spawnBurst(player_.position + Vec3(0, 1, 0), {0.75f, 0.45f, 1.0f, 1.0f}, 40, 5.0f, 0.16f, true, -1.0f);
            emitSound(SND_PORTAL, player_.position, 1.0f);
            LOGI("OBJ vitoria t=%.1f abates=%d", missionTime_, kills_);
            emit(EV_VICTORY);
        }
    }
    // Resultado sempre atualizado: sair pelo menu também conta abates, ondas e pontos.
    finishResult();
}

void Game::finishResult() {
    int nests = 0;
    for (const Nest& n : nests_)
        if (n.destroyed) ++nests;
    std::lock_guard<std::mutex> lock(outMutex_);
    result_[R_ELAPSED] = missionTime_;
    result_[R_HEALTH] = health_;
    result_[R_MAX_HEALTH] = maxHealth_;
    result_[R_KILLS] = static_cast<float>(kills_);
    result_[R_HEADSHOTS] = static_cast<float>(headshots_);
    result_[R_CRYSTAL_MASK] = static_cast<float>(crystalsCollectedMask_);
    result_[R_DAMAGE_TAKEN] = damageTaken_;
    result_[R_SHOTS] = static_cast<float>(shots_);
    result_[R_HITS] = static_cast<float>(hits_);
    result_[R_CHESTS] = static_cast<float>(chestsOpened_);
    result_[R_CHEST_DIAMONDS] = static_cast<float>(chestDiamonds_);
    result_[R_DISTANCE] = distance_;
    result_[R_NESTS] = static_cast<float>(nests);
    result_[R_WAVE] = static_cast<float>(wave_);
    result_[R_SCORE] = static_cast<float>(score_ + static_cast<int>(survivalTime_ * 2.0f));
    result_[R_ALERTS] = static_cast<float>(alerts_);
    result_[R_RESERVE_PCT] = static_cast<float>(reservePct_);
    result_[R_CARGO] = cargo_.collected ? 1.0f : 0.0f;
    result_[R_BOMBS] = static_cast<float>(bombs_);
}

// ----------------------------------------------------------------------------- jogador e câmera

void Game::updateCamera(float dt, const InputState& in) {
    const WeaponDef& weapon = weaponDef(weaponId());
    // Mirando, a sensibilidade cai junto com o zoom (luneta do rifle de precisão fica bem mais lenta).
    const float aimedFov = (weaponId() == IT_SNIPER && aimStage_ >= 2) ? weapon.aimFov * 0.55f : weapon.aimFov;
    float k = 0.0058f * config_.sensitivity * lerpf(1.0f, aimedFov * 0.85f, aimBlend_);
    if (targetLocked_ && firingNow_) k *= 0.72f;  // "fricção" da mira sobre o alvo
    if (!dead_) {
        camYaw_ = wrapAngle(camYaw_ + in.lookX * k);
        camPitch_ += (config_.invertY ? in.lookY : -in.lookY) * k;
    }
    // Atração suave da mira assistida enquanto atira.
    if (config_.aimAssist && firingNow_ && assistTarget_ >= 0 && assistTarget_ < static_cast<int>(zombies_.size())) {
        const Zombie& z = zombies_[static_cast<size_t>(assistTarget_)];
        Vec3 chest = z.body.position + Vec3(0, zombieHeight(z) * 0.55f, 0);
        Vec3 to = chest - camPos_;
        float targetYaw = yawFromDirection(to.x, to.z);
        float targetPitch = std::atan2(to.y, lengthXZ(to));
        float pull = clampf(dt * 2.6f, 0, 1) * (1.0f - clampf(assistAngle_ / 0.1f, 0, 1));
        camYaw_ += wrapAngle(targetYaw - camYaw_) * pull * 0.5f;
        camPitch_ += (targetPitch - camPitch_) * pull * 0.35f;
    }
    camPitch_ = clampf(camPitch_, -1.15f, 1.0f);
    for (int i = 0; i < in.shoulderSwaps; ++i) shoulder_ = -shoulder_;
    shoulderBlend_ = lerpf(shoulderBlend_, shoulder_, damp(9.0f, dt));
    aimBlend_ = lerpf(aimBlend_, (aimToggle_ && !dead_) ? 1.0f : 0.0f, damp(12.0f, dt));
    recoil_ = lerpf(recoil_, 0.0f, damp(10.0f, dt));
    shake_ = std::max(0.0f, shake_ - dt * 1.8f);
    // Ao correr o campo de visão abre um pouco (sensação de velocidade).
    sprintFov_ = lerpf(sprintFov_, sprinting_ ? 0.07f : 0.0f, damp(4.0f, dt));
    fov_ = lerpf(1.0f, aimedFov, aimBlend_) + sprintFov_;
    float wantDistance = dead_ ? 5.5f : lerpf(lerpf(4.9f, 4.3f, crouchBlend_), 2.4f, aimBlend_);
    float shoulder = lerpf(1.0f, 0.78f, aimBlend_);
    float pitch = camPitch_ + recoil_;
    float shakeAmp = shake_ * shake_ * 0.12f;
    float yaw = camYaw_ + std::sin(time_ * 47.0f) * shakeAmp * 0.6f;
    pitch += std::sin(time_ * 39.0f + 1.3f) * shakeAmp * 0.5f;

    // O pivô segue o jogador com um atraso mínimo: degraus, pulos e trancos ficam suaves.
    float horizontal = lengthXZ(player_.velocity);
    if (player_.onGround && horizontal > 0.5f && !dead_) bobPhase_ += dt * horizontal * 1.7f;
    float bob = std::sin(bobPhase_ * 2.0f) * (sprinting_ ? 0.045f : 0.02f) * clampf(horizontal / 4.0f, 0.0f, 1.0f) * (1.0f - aimBlend_);
    Vec3 target = player_.position + Vec3(0, visualStep_ + lerpf(1.72f, 1.2f, crouchBlend_) + bob, 0);
    if (!camPivotInit_ || length(target - camPivot_) > 4.0f) {
        camPivot_ = target;
        camPivotInit_ = true;
    }
    camPivot_.x = lerpf(camPivot_.x, target.x, damp(20.0f, dt));
    camPivot_.z = lerpf(camPivot_.z, target.z, damp(20.0f, dt));
    camPivot_.y = lerpf(camPivot_.y, target.y, damp(13.0f, dt));
    Vec3 head = camPivot_;
    // Ombro da câmera (direito/esquerdo) com transição suave pelo centro.
    Vec3 right = rightFromYaw(camYaw_) * (shoulderBlend_ >= 0 ? 1.0f : -1.0f);
    float side = shoulder * std::fabs(shoulderBlend_);
    RayHit hit;
    if (world_.raycast(head, right, side + 0.25f, hit)) side = std::max(0.0f, hit.distance - 0.25f);
    Vec3 pivot = head + right * side;
    Vec3 f = cameraDirection(yaw, pitch);
    float allowed = wantDistance;
    if (world_.raycast(pivot, -f, wantDistance + 0.3f, hit)) allowed = std::max(0.3f, hit.distance - 0.3f);
    if (allowed < camDistance_) camDistance_ = allowed;
    else camDistance_ = lerpf(camDistance_, allowed, damp(5.0f, dt));
    camPos_ = pivot - f * camDistance_;
    camForward_ = f;
    view_ = Mat4::lookAt(camPos_, camPos_ + f, {0, 1, 0});
    float aspect = static_cast<float>(renderer_.width()) / static_cast<float>(renderer_.height());
    proj_ = Mat4::perspective(fov_, aspect, 0.08f, 260.0f);
}

bool Game::canStand() const {
    const float hw = player_.halfWidth - 1e-3f;
    Vec3 mn = player_.position + Vec3(-hw, 0.05f, -hw);
    Vec3 mx = player_.position + Vec3(hw, 1.75f - 1e-3f, hw);
    return !world_.boxCollides(mn, mx);
}

// Procura uma borda de 2 a 3 blocos à frente com espaço para o corpo e começa a escalada.
bool Game::tryMantle(const Vec3& dir) {
    if (dead_ || victory_ || mantleTime_ >= 0 || lengthXZ(dir) < 0.5f) return false;
    const float hw = player_.halfWidth - 1e-3f;
    Vec3 probe = player_.position + dir * (player_.halfWidth + 0.35f);
    const int x = static_cast<int>(std::floor(probe.x)), z = static_cast<int>(std::floor(probe.z));
    const int px = static_cast<int>(std::floor(player_.position.x)), pz = static_cast<int>(std::floor(player_.position.z));
    if (x == px && z == pz) return false;
    const int feet = static_cast<int>(std::floor(player_.position.y + 0.05f));
    int top = -1;
    for (int y = feet + 1; y <= feet + 3; ++y) {
        if (world_.solid(x, y - 1, z) && !world_.solid(x, y, z) && !world_.solid(x, y + 1, z)) {
            top = y;
            break;
        }
    }
    // Degraus de 1 bloco o movimento normal já sobe sozinho.
    if (top < 0 || static_cast<float>(top) - player_.position.y < 1.05f) return false;
    // Precisa de espaço acima da cabeça para subir e de espaço em cima da borda para ficar de pé.
    Vec3 risen(player_.position.x, top + 0.02f, player_.position.z);
    if (world_.boxCollides(risen + Vec3(-hw, 0.0f, -hw), risen + Vec3(hw, 1.74f, hw))) return false;
    Vec3 dest(x + 0.5f, static_cast<float>(top), z + 0.5f);
    if (world_.boxCollides(dest + Vec3(-hw, 0.02f, -hw), dest + Vec3(hw, 1.74f, hw))) return false;
    mantleFrom_ = player_.position;
    mantleTo_ = dest;
    mantleDuration_ = 0.28f + 0.12f * (static_cast<float>(top) - player_.position.y);
    mantleTime_ = 0;
    crouching_ = false;
    player_.height = 1.75f;
    player_.velocity = {};
    playerYaw_ = yawFromDirection(dir.x, dir.z);
    emitSound(SND_JUMP, player_.position, 0.55f);
    return true;
}

// Buraco sem saída: a partir da coluna do jogador, nenhum caminho (andando ou escalando até 3 blocos)
// chega a 7 blocos de distância.
bool Game::playerEnclosed() const {
    constexpr int R = 7, N = 2 * R + 1;
    const int cx = static_cast<int>(std::floor(player_.position.x)), cz = static_cast<int>(std::floor(player_.position.z));
    int height[N * N];
    bool seen[N * N] = {};
    for (int j = 0; j < N; ++j)
        for (int i = 0; i < N; ++i) {
            int x = cx - R + i, z = cz - R + j;
            height[j * N + i] = world_.columnReady(x, z) ? world_.standHeight(x, z) : -1;
        }
    int queue[N * N];
    int head = 0, tail = 0;
    queue[tail++] = R * N + R;
    seen[R * N + R] = true;
    const int dirs[4][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    while (head < tail) {
        int cur = queue[head++];
        int ci = cur % N, cj = cur / N;
        if (ci == 0 || cj == 0 || ci == N - 1 || cj == N - 1) return false;
        int ha = height[cur];
        for (const auto& d : dirs) {
            int ni = ci + d[0], nj = cj + d[1];
            int n = nj * N + ni;
            if (seen[n]) continue;
            int hb = height[n];
            if (hb < 0) return false;  // área ainda não carregada: não dá para afirmar
            if (hb - ha > 3 || world_.solid(cx - R + ni, hb + 1, cz - R + nj)) continue;
            seen[n] = true;
            queue[tail++] = n;
        }
    }
    return true;
}

// Tira o jogador do buraco: o chão mais próximo por cima da borda.
void Game::unstickPlayer() {
    const int cx = static_cast<int>(std::floor(player_.position.x)), cz = static_cast<int>(std::floor(player_.position.z));
    const int feet = static_cast<int>(std::floor(player_.position.y + 0.05f));
    for (int r = 1; r <= 12; ++r) {
        for (int dz = -r; dz <= r; ++dz)
            for (int dx = -r; dx <= r; ++dx) {
                if (std::max(std::abs(dx), std::abs(dz)) != r) continue;
                int x = cx + dx, z = cz + dz;
                if (!world_.columnReady(x, z)) continue;
                int s = world_.standHeight(x, z), g = world_.groundHeight(x, z);
                if (s < feet + 2 || s - g > 1 || world_.get(x, s, z) == B_WATER || world_.solid(x, s + 1, z)) continue;
                player_.position = {x + 0.5f, static_cast<float>(s), z + 0.5f};
                player_.velocity = {};
                camPivotInit_ = false;
                spawnBurst(player_.position + Vec3(0, 0.8f, 0), {0.85f, 0.95f, 1.0f, 1.0f}, 18, 3.0f, 0.12f, true, -1.0f);
                emitSound(SND_JUMP, player_.position, 0.7f);
                emit(EV_STATUS, 5);
                return;
            }
    }
}

void Game::updatePlayer(float dt, const InputState& in) {
    invulnerable_ = std::max(0.0f, invulnerable_ - dt);
    damageFlash_ = std::max(0.0f, damageFlash_ - dt * 1.8f);
    damageDirTimer_ = std::max(0.0f, damageDirTimer_ - dt);

    // Agachar e correr são alternados pelos botões.
    for (int i = 0; i < in.crouchToggles && !dead_; ++i) {
        if (crouching_) {
            if (canStand()) crouching_ = false;
        } else if (!player_.inWater) {
            crouching_ = true;
            sprintLock_ = false;
        }
    }
    for (int i = 0; i < in.sprintToggles && !dead_; ++i) {
        sprintLock_ = !sprintLock_;
        if (sprintLock_ && crouching_ && canStand()) crouching_ = false;
    }
    if (crouching_ && player_.inWater && canStand()) crouching_ = false;
    player_.height = crouching_ ? 1.2f : 1.75f;
    crouchBlend_ = lerpf(crouchBlend_, crouching_ ? 1.0f : 0.0f, damp(12.0f, dt));

    // Escalada em andamento: o corpo sobe até a borda e passa por cima (sem física nesse meio-tempo).
    if (mantleTime_ >= 0) {
        mantleTime_ += dt;
        float t = clampf(mantleTime_ / mantleDuration_, 0.0f, 1.0f);
        float up = smoothstepf(0.0f, 0.65f, t), across = smoothstepf(0.4f, 1.0f, t);
        player_.position.y = lerpf(mantleFrom_.y, mantleTo_.y + 0.02f, up);
        player_.position.x = lerpf(mantleFrom_.x, mantleTo_.x, across);
        player_.position.z = lerpf(mantleFrom_.z, mantleTo_.z, across);
        player_.velocity = {};
        if (t >= 1.0f) {
            mantleTime_ = -1;
            player_.onGround = true;
            coyote_ = 0.12f;
        }
        if (playerAsset_) {
            playerAnim_.play(playerAsset_->model.clipIndex("walk"), true, 0.1f, 1.6f);
            playerAnim_.update(dt);
        }
        return;
    }

    float mx = dead_ || victory_ ? 0 : in.moveX, my = dead_ || victory_ ? 0 : in.moveY;
    // Botão CORRER: o personagem sai correndo para a frente sozinho; o joystick ainda dirige,
    // e puxá-lo para trás (ou tocar de novo no botão) encerra a corrida.
    if (dead_ || victory_ || (my < -0.5f && -my > std::fabs(mx))) sprintLock_ = false;
    if (sprintLock_ && mx * mx + my * my < 0.01f) {
        mx = 0;
        my = 1;
    }
    float mag = std::min(1.0f, std::sqrt(mx * mx + my * my));
    // Curva do analógico: precisão em toques leves, velocidade total no fim do curso.
    float response = mag > 1e-3f ? std::pow(mag, 1.25f) : 0.0f;
    // So o botao de correr ativa a corrida; empurrar o analogico ate o fim so anda.
    bool wantSprint = sprintLock_ && mag > 0.35f;
    sprinting_ = wantSprint && !crouching_ && aimBlend_ < 0.3f && !in.fire && !firingNow_ && slowed_ <= 0 && meleeTime_ < 0;
    if (sprinting_ && sprintLock_) response = 1.0f;
    // Andar no fim do analogico e 1.45x (como antes); correr pelo botao e 2x andar.
    float speed = kPlayerSpeed * (sprinting_ ? 2.0f : (mag > 0.9f ? 1.45f : 1.0f)) * (crouching_ ? 0.5f : 1.0f) * lerpf(1.0f, 0.6f, aimBlend_) *
                  (player_.inWater ? 0.6f : 1.0f) * (slowed_ > 0 ? 0.55f : 1.0f) * weaponDef(weaponId()).moveMul *
                  config_.speedMul * (adrenaline_ > 0 ? 1.3f : 1.0f);
    Vec3 fwd = forwardFromYaw(camYaw_), right = rightFromYaw(camYaw_);
    Vec3 wish = right * mx + fwd * my;
    if (lengthXZ(wish) > 1e-3f) wish = normalize(wish) * (response * speed);
    // Mar aberto: uma corrente suave leva o jogador de volta à ilha.
    if (island_ && player_.inWater) {
        Vec3 toCenter(kIslandCenter - player_.position.x, 0, kIslandCenter - player_.position.z);
        float d = lengthXZ(toCenter);
        if (d > kIslandRadius + 380.0f) wish += normalize(toCenter) * clampf((d - kIslandRadius - 380.0f) * 0.05f, 0.0f, 3.5f);
    }
    // Aceleração: arranca suave, freia firme, vira rápido; pouco controle no ar.
    Vec3 hv(player_.velocity.x, 0, player_.velocity.z);
    float accel;
    if (!player_.onGround && !player_.inWater) accel = 3.5f;
    else if (lengthXZ(wish) < 0.1f) accel = 14.0f;
    else if (dot(hv, wish) < 0) accel = 18.0f;
    else accel = lengthXZ(wish) > lengthXZ(hv) ? 10.0f : 12.0f;
    player_.velocity.x = lerpf(player_.velocity.x, wish.x, damp(accel, dt));
    player_.velocity.z = lerpf(player_.velocity.z, wish.z, damp(accel, dt));

    // Pulo tolerante: vale logo depois de sair da beirada e se apertado um pouco antes de pousar.
    coyote_ = player_.onGround ? 0.12f : std::max(0.0f, coyote_ - dt);
    jumpBuffer_ = in.jumps > 0 ? 0.15f : std::max(0.0f, jumpBuffer_ - dt);
    if (jumpBuffer_ > 0 && !dead_ && !victory_) {
        if (crouching_) {
            if (canStand()) crouching_ = false;  // pular levanta
            jumpBuffer_ = 0;
        } else if (player_.inWater) {
            player_.velocity.y = 5.0f;
            jumpBuffer_ = 0;
        } else if (coyote_ > 0 && player_.velocity.y <= 1.0f) {
            player_.velocity.y = kJumpVelocity;
            coyote_ = 0;
            jumpBuffer_ = 0;
            emitSound(SND_JUMP, player_.position, 0.5f);
        }
    }
    if (player_.inWater) {
        player_.velocity.y -= 9.0f * dt;
        if (world_.isWaterAt(player_.position + Vec3(0, 1.3f, 0))) player_.velocity.y += 17.0f * dt;
        player_.velocity.y = clampf(player_.velocity.y, -3.0f, 5.0f);
    } else {
        // Queda um pouco mais pesada que a subida: pulo mais firme, sem "flutuar".
        float g = player_.velocity.y < 0 ? kGravity * 1.2f : kGravity;
        player_.velocity.y = std::max(-40.0f, player_.velocity.y - g * dt);
    }
    Vec3 before = player_.position;
    moveBody(world_, player_, player_.velocity * dt, kStepHeight);
    visualStep_ -= player_.stepOffset;
    player_.stepOffset = 0;
    visualStep_ = lerpf(visualStep_, 0.0f, damp(14.0f, dt));
    if (player_.inWater && !wasInWater_) emitSound(SND_SPLASH, player_.position, 0.8f);
    wasInWater_ = player_.inWater;
    if (player_.position.y < 1.0f) {
        player_.position = layout_.start;
        player_.velocity = {};
        camPivotInit_ = false;
    }
    if (!dead_) distance_ += distXZ(before, player_.position);

    // Escalar: empurrando contra uma parede de 2 a 3 blocos (ou pulando nela) o personagem agarra a borda e sobe.
    const float wanted = lengthXZ(wish) * dt;
    const float moved = distXZ(before, player_.position);
    if (!dead_ && !victory_ && wanted > 0.004f && moved < wanted * 0.35f) wallPush_ += dt;
    else wallPush_ = std::max(0.0f, wallPush_ - dt * 3.0f);
    if (wallPush_ > 0.22f || (wallPush_ > 0.02f && in.jumps > 0)) {
        if (tryMantle(normalize(Vec3(wish.x, 0, wish.z)))) wallPush_ = 0;
    }
    // Resgate: empurrando há alguns segundos sem sair do lugar dentro de um buraco sem saída.
    stuckCheck_ -= dt;
    if (stuckCheck_ <= 0) {
        stuckCheck_ = 1.0f;
        bool pushing = mag > 0.3f && !dead_ && !victory_ && !player_.inWater;
        stuckTimer_ = (pushing && distXZ(player_.position, stuckAnchor_) < 1.5f) ? stuckTimer_ + 1.0f : 0.0f;
        stuckAnchor_ = player_.position;
        if (stuckTimer_ >= 3.0f) {
            stuckTimer_ = 0;
            if (playerEnclosed()) unstickPlayer();
        }
    }

    float horizontal = lengthXZ(player_.velocity);
    if (player_.onGround && horizontal > 1.0f && !player_.inWater) {
        stepTimer_ -= dt * horizontal / 4.2f;
        if (stepTimer_ <= 0) {
            stepTimer_ = crouching_ ? 0.6f : 0.42f;
            emitSound(SND_STEP, player_.position, crouching_ ? 0.18f : 0.35f);
        }
    }
    bool combat = aimBlend_ > 0.3f || in.fire || firingNow_ || meleeTime_ >= 0 || throwAiming_ || throwAnimTime_ >= 0;
    if (combat) playerYaw_ = camYaw_;
    else if (horizontal > 0.6f) playerYaw_ = yawFromDirection(player_.velocity.x, player_.velocity.z);
    float prevYaw = playerModelYaw_;
    playerModelYaw_ = lerpAngle(playerModelYaw_, playerYaw_, damp(combat ? 22.0f : 13.0f, dt));
    // Inclina o corpo para dentro das curvas.
    float turnRate = wrapAngle(playerModelYaw_ - prevYaw) / std::max(dt, 1e-3f);
    float lean = clampf(turnRate * horizontal * 0.012f, -0.16f, 0.16f) * (crouching_ ? 0.3f : 1.0f);
    leanRoll_ = lerpf(leanRoll_, player_.onGround ? lean : 0.0f, damp(8.0f, dt));

    // Correndo a arma vai para as costas; parar, mirar ou atirar a traz de volta.
    const bool utility = throwAiming_ || throwAnimTime_ >= 0;
    const bool holster = (sprinting_ || utility) && meleeTime_ < 0 && reloadTimer_ <= 0 && !player_.inWater;
    armedBlend_ = lerpf(armedBlend_, holster ? 0.0f : 1.0f, damp(holster ? 7.0f : 16.0f, dt));
    airBlend_ = lerpf(airBlend_, (!player_.onGround && !player_.inWater) ? 1.0f : 0.0f, damp(player_.onGround ? 16.0f : 7.0f, dt));
    if (playerAsset_) {
        const CharacterModel& m = playerAsset_->model;
        if (dead_) {
            int die = m.clipIndex("die");
            if (playerAnim_.clip() != die) playerAnim_.play(die, false, 0.1f);
        } else {
            const bool sprinting = !crouching_ && horizontal > 5.4f;
            const int jumpClip = m.clipIndex("jump");
            const bool jumping = !player_.onGround && jumpClip >= 0;
            int clip = jumping ? jumpClip : locomotionClip(m, player_.velocity, playerModelYaw_, horizontal, sprinting, crouching_, meleeTime_ >= 0);
            float speed = jumping ? 1.0f : (crouching_ ? std::max(0.5f, horizontal / 2.2f)
                                     : (sprinting ? horizontal / 6.0f : std::max(0.6f, horizontal / 3.6f)));
            playerAnim_.play(clip, !jumping, jumping ? 0.1f : (sprinting ? 0.18f : 0.2f), speed);
        }
        playerAnim_.update(dt);
    }
}

void Game::updateWeapon(float dt, const InputState& in) {
    const int weapon = weaponId();
    const WeaponDef& w = weaponDef(weapon);
    const int magazine = magazineFor(weapon);
    const float interval = w.interval * (adrenaline_ > 0 ? 0.87f : 1.0f);
    fireCooldown_ = std::max(-interval, fireCooldown_ - dt);
    bloom_ = std::max(0.0f, bloom_ - dt * 0.09f);
    muzzleTimer_ = std::max(0.0f, muzzleTimer_ - dt);
    weaponKick_ = std::max(0.0f, weaponKick_ - dt * (weapon == IT_SNIPER || weapon == IT_SHOTGUN ? 4.0f : 7.0f));
    if (throwAnimTime_ >= 0.0f) {
        throwAnimTime_ += dt;
        if (throwAnimTime_ > 0.62f) {
            throwAnimTime_ = -1.0f;
            throwAnimItem_ = 0;
        }
    }
    hitMark_ = std::max(0.0f, hitMark_ - dt);
    comboTimer_ = std::max(0.0f, comboTimer_ - dt);
    if (comboTimer_ <= 0) combo_ = 0;

    emptyClick_ = std::max(0.0f, emptyClick_ - dt);
    auto startReload = [&]() {
        reloadDuration_ = w.reload / std::max(0.5f, config_.reloadSpeed) / (adrenaline_ > 0 ? 1.5f : 1.0f);
        reloadTimer_ = reloadDuration_;
        rackPlayed_ = false;
        emitSound(SND_RELOAD, player_.position, 0.9f);  // pente saindo e entrando
    };
    if (in.reloads > 0 && ammo_ < magazine && reloadTimer_ <= 0 && meleeTime_ < 0 && !throwAiming_ && throwAnimTime_ < 0 && canReloadNow(magazine)) startReload();
    if (reloadTimer_ > 0) {
        reloadTimer_ -= dt;
        if (!rackPlayed_ && reloadTimer_ <= reloadDuration_ * 0.3f) {
            rackPlayed_ = true;
            emitSound(SND_RACK, player_.position, 0.9f);  // ferrolho puxado: arma carregada
        }
        if (reloadTimer_ <= 0) {
            reloadTimer_ = 0;
            completeReload(magazine);  // contrato de munição escassa: gasta a reserva
        }
    }

    // Faca (corpo a corpo).
    meleeCooldown_ = std::max(0.0f, meleeCooldown_ - dt);
    grenadeCooldown_ = std::max(0.0f, grenadeCooldown_ - dt);
    if (meleeTime_ >= 0) {
        meleeTime_ += dt;
        if (!meleeDone_ && meleeTime_ >= 0.13f) {
            meleeDone_ = true;
            meleeHit();
        }
        if (meleeTime_ > 0.38f) meleeTime_ = -1;
    }
    if (in.melees > 0 && meleeCooldown_ <= 0 && mode_ == Mode::Mission) {
        meleeTime_ = 0;
        meleeDone_ = false;
        meleeCooldown_ = 0.55f;
        emitSound(SND_KNIFE, player_.position, 0.6f);
    }

    // Alvo da mira assistida: zumbi vivo mais próximo do centro da tela.
    assistTarget_ = -1;
    assistAngle_ = 10;
    float bestAngle = 10;
    int best = -1;
    const float assistRange = std::min(60.0f, w.range);
    for (size_t i = 0; i < zombies_.size(); ++i) {
        const Zombie& z = zombies_[i];
        if (z.state == ZState::Dead) continue;
        Vec3 chest = z.body.position + Vec3(0, zombieHeight(z) * 0.55f, 0);
        Vec3 v = chest - camPos_;
        float dist = length(v);
        if (dist > assistRange || dist < 0.5f) continue;
        float c = dot(v / dist, camForward_);
        if (c < 0.9f) continue;
        // Ângulo efetivo considera o tamanho do alvo (mais tolerante de perto).
        float angle = std::acos(clampf(c, -1, 1)) - std::atan2(0.35f * z.scale, dist);
        if (angle < bestAngle) {
            bestAngle = angle;
            best = static_cast<int>(i);
        }
    }
    if (best >= 0) {
        const Zombie& z = zombies_[static_cast<size_t>(best)];
        Vec3 chest = z.body.position + Vec3(0, zombieHeight(z) * 0.55f, 0);
        if (clearLineOfSight(camPos_, chest)) {
            assistTarget_ = best;
            assistAngle_ = std::max(0.0f, bestAngle);
        }
    }
    float cone = config_.aimAssist ? lerpf(0.075f, 0.045f, aimBlend_) : 0.0f;
    if (w.kind == FireKind::Flame) cone = 0.12f;
    if (assistAngle_ > std::max(cone, 0.03f)) assistTarget_ = -1;
    targetLocked_ = assistTarget_ >= 0;
    targetHp_ = targetLocked_ ? zombies_[static_cast<size_t>(assistTarget_)].health / zombies_[static_cast<size_t>(assistTarget_)].maxHealth : 0;

    bool autoTrigger = false;
    if (config_.autoFire && assistTarget_ >= 0 && w.kind != FireKind::Launcher) {
        const Zombie& z = zombies_[static_cast<size_t>(assistTarget_)];
        float reach = w.kind == FireKind::Flame ? w.range : std::min(42.0f, w.range);
        autoTrigger = assistAngle_ < 0.035f && distXZ(z.body.position, player_.position) < reach;
    }
    bool trigger = (in.fire || autoTrigger) && swapTimer_ <= 0 && !throwAiming_ && throwAnimTime_ < 0;
    if (trigger && ammo_ == 0 && reloadTimer_ <= 0 && meleeTime_ < 0) {
        if (emptyClick_ <= 0) {
            emitSound(SND_EMPTY, player_.position, 0.8f);  // clique de arma vazia
            emptyClick_ = 0.35f;
        }
        if (canReloadNow(magazine)) startReload();
        else notifyOutOfAmmo();  // sem reserva: só a faca e as granadas
    }
    firingNow_ = trigger && ammo_ > 0 && reloadTimer_ <= 0 && meleeTime_ < 0;
    int fired = 0;
    while (firingNow_ && fireCooldown_ <= 0 && fired < 3 && ammo_ > 0) {
        if (w.kind == FireKind::Flame) fireFlame();
        else if (w.kind == FireKind::Launcher) fireLauncher();
        else fireShot();
        fireCooldown_ += interval;
        ++fired;
    }
}

void Game::meleeHit() {
    Vec3 fwd = forwardFromYaw(camYaw_);
    float power = config_.power * config_.boostDamage * (damageBoost_ > 0 ? 1.5f : 1.0f);
    bool any = false;
    for (Zombie& z : zombies_) {
        if (z.state == ZState::Dead) continue;
        Vec3 d = z.body.position - player_.position;
        float dist = lengthXZ(d);
        if (dist > 2.4f + 0.3f * z.scale || std::fabs(d.y) > 1.8f) continue;
        Vec3 dir = dist > 0.05f ? Vec3(d.x, 0, d.z) / dist : fwd;
        if (dot(dir, fwd) < 0.3f && dist > 0.9f) continue;
        z.stun = std::max(z.stun, 0.45f);
        z.action = ZAction::None;
        z.body.velocity += dir * 5.0f + Vec3(0, 2.0f, 0);
        damageZombie(z, 55.0f * power, false, dir, z.body.position + Vec3(0, zombieHeight(z) * 0.6f, 0));
        any = true;
    }
    Vec3 chest = player_.position + Vec3(0, 1.2f, 0);
    for (size_t i = 0; i < nests_.size(); ++i) {
        if (nests_[i].destroyed || length(nests_[i].core - chest) > 2.8f) continue;
        damageNest(static_cast<int>(i), 40.0f * power, nests_[i].core);
        any = true;
    }
    if (any) {
        hitMark_ = 0.18f;
        headshotMark_ = false;
        shake_ = std::max(shake_, 0.2f);
    }
}

void Game::fireShot() {
    const int weapon = weaponId();
    const WeaponDef& w = weaponDef(weapon);
    --ammo_;
    ++shots_;
    Vec3 f = camForward_;
    Vec3 right = normalize(cross(f, Vec3(0, 1, 0)));
    Vec3 up = cross(right, f);
    float moving = std::min(1.0f, lengthXZ(player_.velocity) / kPlayerSpeed);
    float spread = lerpf(w.spreadHip, w.spreadAim, aimBlend_) + bloom_ + moving * 0.008f;
    bool boosted = damageBoost_ > 0;
    float power = config_.power * config_.boostDamage * (boosted ? 1.5f : 1.0f);
    float headMul = w.headshot * config_.headshotMul;
    bool anyHit = false, anyHead = false;
    Vec3 tracerColor = boosted ? Vec3(1.0f, 0.55f, 0.2f) : Vec3(w.tracerR, w.tracerG, w.tracerB);

    for (int pellet = 0; pellet < w.pellets; ++pellet) {
        float ang = rng_.range(0, kTwoPi), rad = std::sqrt(rng_.uniform()) * spread;
        Vec3 dir = normalize(f + right * (std::cos(ang) * rad) + up * (std::sin(ang) * rad));
        if (config_.aimAssist && assistTarget_ >= 0 && w.pellets == 1) {
            const Zombie& z = zombies_[static_cast<size_t>(assistTarget_)];
            Vec3 chest = z.body.position + Vec3(0, zombieHeight(z) * 0.6f, 0);
            dir = normalize(lerp(dir, normalize(chest - camPos_), 0.6f));
        }

        RayHit wh;
        float maxT = w.range;
        bool worldHit = world_.raycast(camPos_, dir, maxT, wh);
        if (worldHit) maxT = wh.distance;
        float minT = camDistance_ * 0.7f;
        // Zumbis atingidos ao longo do raio, do mais próximo ao mais longe
        // (o rifle de precisão e a metralhadora atravessam alguns).
        struct Hit {
            float t;
            int index;
            bool head;
        };
        Hit hits[8];
        int hitCount = 0;
        for (size_t i = 0; i < zombies_.size(); ++i) {
            const Zombie& z = zombies_[i];
            if (z.state == ZState::Dead) continue;
            float h = zombieHeight(z), hw = 0.36f * z.scale * z.bloat;
            Vec3 p = z.body.position;
            if (smokeObscures(camPos_, p + Vec3(0, h * 0.55f, 0))) continue;
            float t, best = 1e9f;
            bool head = false;
            if (rayBox(camPos_, dir, p + Vec3(-hw * 0.85f, h * 0.68f, -hw * 0.85f), p + Vec3(hw * 0.85f, h + 0.05f, hw * 0.85f), t) && t > minT && t < maxT) {
                best = t;
                head = true;
            }
            if (rayBox(camPos_, dir, p + Vec3(-hw, 0, -hw), p + Vec3(hw, h * 0.68f, hw), t) && t > minT && t < maxT && t < best) {
                best = t;
                head = false;
            }
            if (best > maxT) continue;
            Hit hit{best, static_cast<int>(i), head};
            int k = std::min(hitCount, 7);
            while (k > 0 && hits[k - 1].t > hit.t) {
                hits[k] = hits[k - 1];
                --k;
            }
            hits[k] = hit;
            hitCount = std::min(8, hitCount + 1);
        }
        const int maxTargets = 1 + w.pierce;
        const int targets = std::min(hitCount, maxTargets);
        float endT = targets > 0 ? hits[targets - 1].t : maxT;
        if (hitCount >= maxTargets) worldHit = false;
        // Núcleos dos ninhos de infecção.
        int nestHit = -1;
        float nestT = 0;
        float nestLimit = hitCount > 0 ? hits[0].t : maxT;
        if (mode_ == Mode::Mission && damageNestRay(camPos_, dir, nestLimit, nestT, nestHit) && nestT > minT) {
            if (!smokeObscures(camPos_, camPos_ + dir * nestT)) {
                hitCount = 0;
                endT = nestT;
                worldHit = false;
            } else {
                nestHit = -1;
            }
        } else {
            nestHit = -1;
        }
        // PvP: adversários (posições interpoladas que o jogador vê na tela).
        int bestRemote = -1;
        bool remoteHead = false;
        if (mode_ == Mode::Pvp) {
            float bestT = hitCount > 0 ? hits[0].t : maxT;
            for (int i = 0; i < kMaxNetPlayers; ++i) {
                const RemotePlayer& r = remotes_[i];
                if (!r.present || !r.alive || !net_.player(i).active) continue;
                const float h = 1.8f, rw = 0.36f;
                Vec3 p = r.renderPos;
                if (smokeObscures(camPos_, p + Vec3(0, h * 0.55f, 0))) continue;
                float t;
                if (rayBox(camPos_, dir, p + Vec3(-rw * 0.85f, h * 0.68f, -rw * 0.85f), p + Vec3(rw * 0.85f, h + 0.05f, rw * 0.85f), t) && t > minT && t < bestT) {
                    bestT = t;
                    bestRemote = i;
                    remoteHead = true;
                }
                if (rayBox(camPos_, dir, p + Vec3(-rw, 0, -rw), p + Vec3(rw, h * 0.68f, rw), t) && t > minT && t < bestT) {
                    bestT = t;
                    bestRemote = i;
                    remoteHead = false;
                }
            }
            if (bestRemote >= 0) {
                endT = bestT;
                hitCount = 0;
                worldHit = false;
            }
        }
        Vec3 hitPoint = camPos_ + dir * endT;
        if (mode_ == Mode::Pvp) net_.localFire(muzzlePos_, hitPoint);
        if (bestRemote >= 0) {
            int dmg = remoteHead ? 34 : 16;
            net_.localHit(bestRemote, dmg, remoteHead);
            anyHit = true;
            anyHead = anyHead || remoteHead;
            labels_.push_back({hitPoint, dmg, remoteHead ? 2 : 1, 0.8f});
            spawnBurst(hitPoint, {0.75f, 0.12f, 0.12f, 1.0f}, remoteHead ? 9 : 5, 2.2f, 0.1f, false, 14.0f);
            emitSound(remoteHead ? SND_HEADSHOT : SND_HIT, hitPoint, remoteHead ? 0.9f : 0.6f);
        }
        Tracer tracer{muzzlePos_, hitPoint, w.pellets > 1 ? 0.05f : 0.06f};
        tracer.color = {tracerColor.x, tracerColor.y, tracerColor.z, 1.0f};
        tracers_.push_back(tracer);
        if (nestHit >= 0) {
            anyHit = true;
            damageNest(nestHit, w.damage * power, hitPoint);
        }
        for (int k = 0; k < std::min(hitCount, maxTargets); ++k) {
            Zombie& z = zombies_[static_cast<size_t>(hits[k].index)];
            if (z.state == ZState::Dead) continue;
            float falloff = w.pellets > 1 ? clampf(1.0f - (hits[k].t - 6.0f) / w.range, 0.3f, 1.0f) : 1.0f;
            float pierceLoss = 1.0f - 0.2f * static_cast<float>(k);
            float dmg = w.damage * power * falloff * pierceLoss * (hits[k].head ? headMul : 1.0f);
            anyHit = true;
            anyHead = anyHead || hits[k].head;
            Vec3 hp = camPos_ + dir * hits[k].t;
            if (weapon == IT_SHOTGUN) z.body.velocity += Vec3(dir.x, 0, dir.z) * 0.9f;
            damageZombie(z, dmg, hits[k].head, dir, hp);
        }
        if (worldHit && hitCount == 0 && nestHit < 0 && bestRemote < 0) {
            const BlockInfo& info = blockInfo(wh.block);
            Vec3 c = info.side < layerColors_.size() ? layerColors_[info.side] : Vec3(0.5f, 0.5f, 0.5f);
            spawnBurst(hitPoint + Vec3(static_cast<float>(wh.nx), static_cast<float>(wh.ny), static_cast<float>(wh.nz)) * 0.06f,
                       {c.x, c.y, c.z, 1.0f}, w.pellets > 1 ? 2 : 5, 2.6f, 0.08f, false, 14.0f);
        }
    }
    if (anyHit) {
        ++hits_;
        hitMark_ = 0.18f;
        headshotMark_ = anyHead;
    }
    const bool heavy = weapon == IT_SHOTGUN || weapon == IT_SNIPER;
    muzzleTimer_ = heavy ? 0.07f : 0.05f;
    weaponKick_ = std::max(weaponKick_, weapon == IT_SNIPER ? 1.0f : (weapon == IT_PISTOL ? 0.72f : 0.5f));
    for (int i = 0; i < 2; ++i) {
        FxParticle p;
        p.position = muzzlePos_ + f * (0.08f * i);
        p.velocity = f * 1.5f;
        p.color = boosted ? Vec4(1.0f, 0.6f, 0.25f, 1.0f) : Vec4(1.0f, 0.82f, 0.4f, 1.0f);
        p.size = (0.32f - i * 0.1f) * (heavy ? 1.5f : (weapon == IT_PISTOL ? 0.75f : 1.0f));
        p.life = p.maxLife = 0.05f;
        p.additive = true;
        particles_.push_back(p);
    }
    bloom_ = std::min(0.045f, bloom_ + w.bloomPerShot * (1.0f - aimBlend_ * 0.5f));
    recoil_ += w.recoil;
    camPitch_ += w.recoil * 0.3f;
    if (heavy) shake_ = std::max(shake_, 0.25f);
    emitSound(w.sound, muzzlePos_, weapon == IT_SNIPER ? 0.95f : 0.75f);
    alertNear(player_.position, weapon == IT_SNIPER ? 45.0f : 30.0f);
}

void Game::damagePlayer(float amount, const Vec3& from) {
    if (invulnerable_ > 0 || dead_ || victory_) return;
    // Melhoria de armadura reduz todo dano; o colete absorve a maior parte até acabar.
    amount *= 1.0f - clampf(config_.armor, 0.0f, 0.6f);
    if (armor_ > 0) {
        float absorbed = std::min(armor_, amount * 0.7f);
        armor_ -= absorbed;
        amount -= absorbed;
        if (armor_ <= 0.5f) {
            armor_ = armorMax_ = 0;
            spawnBurst(player_.position + Vec3(0, 1.2f, 0), {0.6f, 0.7f, 0.9f, 1.0f}, 14, 3.0f, 0.12f, false, 12.0f);
        }
    }
    sinceHurt_ = 0;
    health_ -= amount;
    damageTaken_ += amount;
    damageFlash_ = 1.0f;
    Vec3 d = from - player_.position;
    damageDir_ = wrapAngle(yawFromDirection(d.x, d.z) - camYaw_);
    damageDirTimer_ = 1.2f;
    invulnerable_ = 0.25f;
    shake_ = std::max(shake_, std::min(1.0f, 0.25f + amount * 0.025f));
    emit(EV_PLAYER_HURT, static_cast<int>(amount));
    emitSound(SND_HURT, player_.position, 0.9f);
    if (health_ <= 0) {
        health_ = 0;
        dead_ = true;
        firingNow_ = false;
        emit(EV_DEFEAT);
    }
}

void Game::damageOverTime(float amount) {
    if (dead_ || victory_ || invulnerable_ > 1.0f) return;
    amount *= 1.0f - clampf(config_.armor, 0.0f, 0.6f);
    sinceHurt_ = 0;
    health_ -= amount;
    damageTaken_ += amount;
    if (health_ <= 0) {
        health_ = 0;
        dead_ = true;
        firingNow_ = false;
        emit(EV_DEFEAT);
    }
}

void Game::updateStatus(float dt) {
    float scale = 1.0f + std::min(2.0f, config_.stage * 0.03f);
    if (burning_ > 0) {
        burning_ = std::max(0.0f, burning_ - dt);
        damageOverTime(5.0f * scale * dt);
        if (rng_.chance(dt * 18.0f)) {
            FxParticle p;
            p.position = player_.position + Vec3(rng_.range(-0.3f, 0.3f), rng_.range(0.2f, 1.6f), rng_.range(-0.3f, 0.3f));
            p.velocity = {0, rng_.range(1.0f, 2.0f), 0};
            p.color = {1.0f, rng_.range(0.4f, 0.7f), 0.15f, 1.0f};
            p.size = rng_.range(0.15f, 0.3f);
            p.life = p.maxLife = 0.45f;
            p.additive = true;
            particles_.push_back(p);
        }
    }
    if (bleeding_ > 0) {
        bleeding_ = std::max(0.0f, bleeding_ - dt);
        damageOverTime(2.6f * scale * dt);
        if (rng_.chance(dt * 6.0f)) spawnBurst(player_.position + Vec3(0, 1.0f, 0), {0.6f, 0.05f, 0.05f, 1.0f}, 2, 1.0f, 0.08f, false, 12.0f);
    }
    if (poisoned_ > 0) {
        poisoned_ = std::max(0.0f, poisoned_ - dt);
        damageOverTime(3.0f * scale * dt);
    }
    slowed_ = std::max(0.0f, slowed_ - dt);
    damageBoost_ = std::max(0.0f, damageBoost_ - dt);
    if (player_.inWater) burning_ = 0;
}

// ----------------------------------------------------------------------------- coletáveis e efeitos

void Game::updatePickups(float dt) {
    for (auto& p : pickups_) {
        if (p.taken) continue;
        p.age += dt;
        p.blocked = std::max(0.0f, p.blocked - dt);
        Vec3 toPlayer = player_.position + Vec3(0, 0.8f, 0) - p.position;
        float dist = length(toPlayer);
        bool magnet = p.type != 2 && p.age > 0.6f && dist < 3.2f && !dead_ && p.blocked <= 0;
        if (magnet) {
            p.velocity = lerp(p.velocity, normalize(toPlayer) * 9.0f, damp(8.0f, dt));
        } else {
            p.velocity.y -= 18.0f * dt;
            p.velocity.x *= std::exp(-2.0f * dt);
            p.velocity.z *= std::exp(-2.0f * dt);
        }
        p.position += p.velocity * dt;
        int gx = static_cast<int>(std::floor(p.position.x)), gz = static_cast<int>(std::floor(p.position.z));
        float floorY = static_cast<float>(world_.standHeight(gx, gz)) + (p.type == 2 ? 0.5f : 0.45f);
        if (p.position.y < floorY && !magnet) {
            p.position.y = floorY;
            p.velocity.y = 0;
        }
        if (dist < (p.type == 2 ? 1.9f : 1.25f) && !dead_ && p.blocked <= 0) {
            if (p.type == 3) {
                p.taken = collectPickup(p);
                continue;
            }
            p.taken = true;
            if (p.type == 0) {
                crystalsCollectedMask_ |= 1 << p.crystalIndex;
                LOGI("OBJ cristal idx=%d mascara=0x%x", p.crystalIndex, crystalsCollectedMask_);
                emit(EV_CRYSTAL, p.crystalIndex);
                emitSound(SND_PICKUP, p.position, 1.0f);
                spawnBurst(p.position, {0.4f, 1.0f, 0.95f, 1.0f}, 16, 3.0f, 0.12f, true, -2.0f);
            } else if (p.type == 1) {
                float heal = maxHealth_ * 0.12f;
                health_ = std::min(maxHealth_, health_ + heal);
                labels_.push_back({p.position, static_cast<int>(std::lround(heal)), 3, 0.9f});
                emit(EV_HEAL, static_cast<int>(heal));
                emitSound(SND_HEAL, p.position, 1.0f);
                spawnBurst(p.position, {1.0f, 0.4f, 0.4f, 1.0f}, 12, 2.5f, 0.12f, true, -2.0f);
            } else {
                // Caixa de suprimentos (anúncio): munição, vida e munição incendiária.
                float heal = maxHealth_ * 0.3f;
                health_ = std::min(maxHealth_, health_ + heal);
                ammo_ = magazineFor(weaponId());
                // Com reserva limitada (contrato) o suprimento só completa o pente atual: não repõe a reserva nem as outras armas.
                if (!ammoLimited()) {
                    for (int w = IT_RIFLE; w <= IT_LAUNCHER; ++w) clipsInit_[w] = false;
                    clipsInit_[weaponId()] = true;
                }
                clips_[weaponId()] = ammo_;
                reloadTimer_ = 0;
                damageBoost_ = 45.0f;
                giveSupplyBundle();
                burning_ = bleeding_ = poisoned_ = slowed_ = 0;
                labels_.push_back({p.position, static_cast<int>(std::lround(heal)), 3, 0.9f});
                emit(EV_SUPPLY, 1);
                emitSound(SND_CHEST, p.position, 1.0f);
                spawnBurst(p.position, {0.5f, 1.0f, 0.4f, 1.0f}, 30, 4.0f, 0.15f, true, -1.5f);
            }
        }
    }
    pickups_.erase(std::remove_if(pickups_.begin(), pickups_.end(), [](const Pickup& p) { return p.taken; }), pickups_.end());
}

void Game::spawnBurst(const Vec3& at, const Vec4& color, int count, float speed, float size, bool additive, float gravity) {
    for (int i = 0; i < count && particles_.size() < 900; ++i) {
        FxParticle p;
        p.position = at;
        Vec3 dir = normalize(Vec3(rng_.range(-1, 1), rng_.range(-0.2f, 1.0f), rng_.range(-1, 1)));
        p.velocity = dir * (speed * rng_.range(0.4f, 1.0f));
        float shade = rng_.range(0.8f, 1.1f);
        p.color = {color.x * shade, color.y * shade, color.z * shade, color.w};
        p.size = size * rng_.range(0.7f, 1.3f);
        p.life = p.maxLife = rng_.range(0.35f, 0.8f);
        p.gravity = gravity;
        p.additive = additive;
        particles_.push_back(p);
    }
}

void Game::addFlash(const Vec3& at, float radius, const Vec3& color, float life) {
    if (flashes_.size() > 12) flashes_.erase(flashes_.begin());
    flashes_.push_back({at, radius, color, life, life});
}

bool Game::smokeObscures(const Vec3& from, const Vec3& to) const {
    const Vec3 segment = to - from;
    const float segmentSq = dot(segment, segment);
    for (const SmokeCloud& cloud : smokeClouds_) {
        if (cloud.life <= 0.0f) continue;
        const Vec3 center = cloud.pos + Vec3(0, 2.0f, 0);
        const float t = segmentSq > 1e-5f ? clampf(dot(center - from, segment) / segmentSq, 0.0f, 1.0f) : 0.0f;
        const Vec3 nearest = from + segment * t;
        const Vec3 delta = nearest - center;
        const float r = cloud.radius * 0.92f;
        if (dot(delta, delta) < r * r) return true;
    }
    return false;
}

bool Game::clearLineOfSight(const Vec3& from, const Vec3& to) const {
    return world_.lineOfSight(from, to) && !smokeObscures(from, to);
}

void Game::updateEffects(float dt) {
    for (SmokeCloud& cloud : smokeClouds_) {
        cloud.life -= dt;
        cloud.fxTimer -= dt;
        if (cloud.life <= 0.0f || cloud.fxTimer > 0.0f || particles_.size() > 820 || length(cloud.pos - camPos_) > 90.0f) continue;
        cloud.fxTimer = 0.14f;
        for (int i = 0; i < 3 && particles_.size() < 850; ++i) {
            float angle = rng_.range(0.0f, kTwoPi);
            float radius = std::sqrt(rng_.uniform()) * cloud.radius * 0.68f;
            FxParticle p;
            p.position = cloud.pos + Vec3(std::cos(angle) * radius, rng_.range(0.7f, 3.1f), std::sin(angle) * radius);
            p.velocity = {std::cos(angle) * rng_.range(0.25f, 0.7f), rng_.range(0.3f, 0.8f), std::sin(angle) * rng_.range(0.25f, 0.7f)};
            float shade = rng_.range(0.78f, 1.08f);
            p.color = {0.48f * shade, 0.53f * shade, 0.57f * shade, 0.33f};
            p.size = rng_.range(1.25f, 2.35f);
            p.life = p.maxLife = rng_.range(1.7f, 2.6f);
            p.gravity = -0.18f;
            p.additive = false;
            particles_.push_back(p);
        }
    }
    smokeClouds_.erase(std::remove_if(smokeClouds_.begin(), smokeClouds_.end(), [](const SmokeCloud& cloud) { return cloud.life <= 0.0f; }), smokeClouds_.end());
    for (auto& p : particles_) {
        p.life -= dt;
        p.velocity.y -= p.gravity * dt;
        p.position += p.velocity * dt;
    }
    particles_.erase(std::remove_if(particles_.begin(), particles_.end(), [](const FxParticle& p) { return p.life <= 0; }), particles_.end());
    for (auto& l : labels_) {
        l.life -= dt;
        l.position.y += dt * 1.1f;
    }
    labels_.erase(std::remove_if(labels_.begin(), labels_.end(), [](const FxLabel& l) { return l.life <= 0; }), labels_.end());
    for (auto& t : tracers_) t.life -= dt;
    tracers_.erase(std::remove_if(tracers_.begin(), tracers_.end(), [](const Tracer& t) { return t.life <= 0; }), tracers_.end());
    for (auto& f : flashes_) f.life -= dt;
    flashes_.erase(std::remove_if(flashes_.begin(), flashes_.end(), [](const FlashLight& f) { return f.life <= 0; }), flashes_.end());

    // Pétalas de cerejeira, ipê e jacarandá e folhas de bordo levadas pelo vento.
    static float petalTimer = 0;
    petalTimer -= dt;
    if (petalTimer <= 0 && mode_ != Mode::None && particles_.size() < 760) {
        petalTimer = 0.05f;
        Vec3 around = mode_ == Mode::Lobby ? layout_.start : player_.position;
        for (int k = 0; k < 4; ++k) {
            float ang = rng_.range(0, kTwoPi), r = rng_.range(2.0f, 20.0f);
            int x = static_cast<int>(std::floor(around.x + std::cos(ang) * r));
            int z = static_cast<int>(std::floor(around.z + std::sin(ang) * r));
            int y = static_cast<int>(std::floor(around.y)) + rng_.rangeInt(2, 10);
            uint8_t b = world_.get(x, y, z);
            if (!isBlossomBlock(b) || world_.get(x, y - 1, z) != B_AIR) continue;
            uint32_t c = blockInfo(b).mapColor;
            FxParticle p;
            p.position = {x + rng_.uniform(), static_cast<float>(y) - 0.05f, z + rng_.uniform()};
            p.velocity = {rng_.range(0.3f, 1.0f), rng_.range(-0.6f, -0.3f), rng_.range(0.1f, 0.7f)};
            float shade = rng_.range(0.85f, 1.1f);
            p.color = {((c >> 16) & 0xFF) / 255.0f * shade, ((c >> 8) & 0xFF) / 255.0f * shade, (c & 0xFF) / 255.0f * shade, 1.0f};
            p.size = rng_.range(0.07f, 0.12f);
            p.life = p.maxLife = rng_.range(3.0f, 5.0f);
            p.gravity = 0.15f;
            particles_.push_back(p);
        }
    }

    // Brasas das fogueiras e faíscas do portal.
    fireEmberTimer_ -= dt;
    if (fireEmberTimer_ <= 0) {
        fireEmberTimer_ = 0.05f;
        auto ember = [&](const Vec3& fire) {
            if (length(fire - camPos_) > 45.0f || particles_.size() > 820) return;
            FxParticle p;
            p.position = fire + Vec3(rng_.range(-0.3f, 0.3f), 0.1f, rng_.range(-0.3f, 0.3f));
            p.velocity = {rng_.range(-0.2f, 0.2f), rng_.range(1.0f, 2.2f), rng_.range(-0.2f, 0.2f)};
            p.color = {1.0f, rng_.range(0.45f, 0.75f), 0.15f, 1.0f};
            p.size = rng_.range(0.12f, 0.28f);
            p.life = p.maxLife = rng_.range(0.4f, 0.9f);
            p.additive = true;
            particles_.push_back(p);
        };
        for (const Vec3& fire : layout_.campfires) ember(fire);
        if (world_.streaming()) {
            for (const Chunk* c : world_.chunks()) {
                if (c->campfires.empty() || distXZ((c->bounds.min + c->bounds.max) * 0.5f, camPos_) > 56.0f) continue;
                for (const Vec3& fire : c->campfires) ember(fire);
            }
        }
        if (mode_ == Mode::Mission && portalOpen_ && particles_.size() < 820) {
            Vec3 n = forwardFromYaw(layout_.portalYaw);
            Vec3 axis(-n.z, 0, n.x);
            FxParticle p;
            p.position = layout_.portalBase + axis * rng_.range(-1.4f, 1.4f) + Vec3(0, rng_.range(0.2f, 3.8f), 0);
            p.velocity = n * rng_.range(-0.6f, 0.6f) + Vec3(0, rng_.range(-0.3f, 0.6f), 0);
            p.color = {0.75f, 0.4f, 1.0f, 1.0f};
            p.size = rng_.range(0.08f, 0.16f);
            p.life = p.maxLife = rng_.range(0.6f, 1.2f);
            p.additive = true;
            particles_.push_back(p);
        }
    }
}

// ----------------------------------------------------------------------------- cena

void Game::addCharacter(CharacterAsset* asset, const Vec3& pos, float yaw, float scale, const Pose& pose, const Vec3& tint, float flash, const Vec3& scaleXYZ, float roll) {
    if (!asset) return;
    if (scene_.characterCount >= scene_.characters.size()) scene_.characters.emplace_back();
    CharacterDraw& draw = scene_.characters[scene_.characterCount++];
    draw.asset = asset;
    float s = scale * 1.8f / std::max(0.1f, asset->model.bindHeight());
    draw.world = Mat4::translation(pos) * Mat4::rotationY(modelRotationFromYaw(yaw)) * Mat4::rotationZ(roll) *
                 Mat4::scale({s * scaleXYZ.x, s * scaleXYZ.y, s * scaleXYZ.z}) * Mat4::translation({0, -asset->model.bindMinY(), 0});
    asset->model.globals(pose, draw.nodes);
    draw.tint = tint;
    draw.flash = flash;
    draw.flashColor = {1.0f, 0.25f, 0.2f};
    draw.alpha = 1.0f;
}

// Agachado (estilo Minecraft): tronco inclinado para a frente, cabeça olhando adiante,
// pernas um pouco atrás e passos curtos.
void Game::poseCrouch(Pose& pose, const CharacterModel& m, float blend, float moving) {
    if (blend <= 0.01f) return;
    const int legL = m.nodeIndex("leg-left"), legR = m.nodeIndex("leg-right"), torso = m.nodeIndex("torso"), head = m.nodeIndex("head");
    const float px = kBlockyPixel;
    if (torso >= 0) pose.r[torso] = slerp(pose.r[torso], pose.r[torso] * Quat::axisAngle({1, 0, 0}, 0.45f), blend);
    if (head >= 0) pose.r[head] = slerp(pose.r[head], pose.r[head] * Quat::axisAngle({1, 0, 0}, -0.4f), blend);
    for (int leg : {legL, legR}) {
        if (leg < 0) continue;
        // Passada menor e pernas recuadas sob o corpo inclinado.
        pose.r[leg] = slerp(pose.r[leg], Quat(), blend * (0.45f - 0.15f * moving));
        pose.t[leg].z -= 2.0f * px * blend;
    }
}

// No ar: uma perna dobrada à frente, a outra atrás; braços livres se abrem para equilibrar.
void Game::poseAir(Pose& pose, const CharacterModel& m, float blend, bool armsFree) {
    if (blend <= 0.01f) return;
    const int legL = m.nodeIndex("leg-left"), legR = m.nodeIndex("leg-right"), torso = m.nodeIndex("torso");
    const int armR = m.nodeIndex("arm-right"), armL = m.nodeIndex("arm-left");
    const bool falling = player_.velocity.y < -2.0f;
    if (legR >= 0) pose.r[legR] = slerp(pose.r[legR], Quat::axisAngle({1, 0, 0}, falling ? -0.25f : -0.75f), blend);
    if (legL >= 0) pose.r[legL] = slerp(pose.r[legL], Quat::axisAngle({1, 0, 0}, falling ? 0.2f : 0.4f), blend);
    if (torso >= 0) pose.r[torso] = slerp(pose.r[torso], pose.r[torso] * Quat::axisAngle({1, 0, 0}, 0.1f), blend);
    if (armsFree) {
        float up = falling ? -1.6f : -0.5f;
        if (armR >= 0) pose.r[armR] = slerp(pose.r[armR], Quat::axisAngle({0, 0, 1}, -0.55f) * Quat::axisAngle({1, 0, 0}, up), blend);
        if (armL >= 0) pose.r[armL] = slerp(pose.r[armL], Quat::axisAngle({0, 0, 1}, 0.55f) * Quat::axisAngle({1, 0, 0}, up), blend);
    }
}

void Game::posePlayer(Pose& pose, bool armed) {
    if (!playerAsset_ || dead_) return;
    const CharacterModel& m = playerAsset_->model;
    bool playing = mode_ == Mode::Mission || mode_ == Mode::Pvp;
    const int currentClip = playerAnim_.clip();
    const bool authoredCrouch = currentClip == m.clipIndex("crouch-idle") || currentClip == m.clipIndex("crouch-walk") || currentClip == m.clipIndex("crouch-back");
    if (playing && !authoredCrouch) poseCrouch(pose, m, crouchBlend_, clampf(lengthXZ(player_.velocity) / 2.0f, 0.0f, 1.0f));
    if (playing && mantleTime_ >= 0) {
        // Escalando: os dois braços se esticam para a borda e puxam o corpo.
        float k = clampf(mantleTime_ / std::max(0.05f, mantleDuration_), 0.0f, 1.0f);
        float reach = lerpf(-kPi * 0.95f, -kPi * 0.35f, k);
        int armR = m.nodeIndex("arm-right"), armL = m.nodeIndex("arm-left");
        if (armR >= 0) pose.r[armR] = Quat::axisAngle({0, 0, 1}, -0.15f) * Quat::axisAngle({1, 0, 0}, reach);
        if (armL >= 0) pose.r[armL] = Quat::axisAngle({0, 0, 1}, 0.15f) * Quat::axisAngle({1, 0, 0}, reach);
        return;
    }
    const float armedW = playing ? armedBlend_ : 1.0f;
    const bool authoredJump = playing && !player_.onGround && currentClip == m.clipIndex("jump");
    if (playing && !authoredJump) poseAir(pose, m, airBlend_, !armed || armedW < 0.5f);
    if (!armed) return;
    if (playing && (throwAiming_ || throwAnimTime_ >= 0.0f)) {
        const int armR = m.nodeIndex("arm-right"), armL = m.nodeIndex("arm-left"), torso = m.nodeIndex("torso");
        if (throwAiming_) {
            if (armR >= 0) pose.r[armR] = Quat::axisAngle({0, 0, 1}, -0.28f) * Quat::axisAngle({1, 0, 0}, -2.35f);
            if (armL >= 0) pose.r[armL] = Quat::axisAngle({0, 1, 0}, -0.42f) * Quat::axisAngle({1, 0, 0}, -1.55f);
            if (torso >= 0) pose.r[torso] = pose.r[torso] * Quat::axisAngle({0, 1, 0}, 0.08f);
        } else {
            const float t = throwAnimTime_;
            const float release = smoothstepf(0.12f, 0.42f, t);
            const float follow = smoothstepf(0.38f, 0.62f, t);
            const float armAngle = lerpf(-2.35f, -0.42f, release) + 0.1f * follow;
            if (armR >= 0) pose.r[armR] = Quat::axisAngle({0, 0, 1}, lerpf(-0.28f, -0.04f, release)) * Quat::axisAngle({1, 0, 0}, armAngle);
            if (armL >= 0) pose.r[armL] = Quat::axisAngle({0, 1, 0}, -0.4f + 0.28f * follow) * Quat::axisAngle({1, 0, 0}, -1.45f + 0.55f * follow);
            if (torso >= 0) pose.r[torso] = pose.r[torso] * Quat::axisAngle({0, 1, 0}, 0.22f * (1.0f - release) - 0.12f * follow);
        }
        return;
    }
    if (armedW <= 0.01f) return;
    float pitch = playing ? camPitch_ : -0.3f;
    const int weapon = playing ? weaponId() : IT_RIFLE;
    poseArmedBlend(pose, m, pitch, playing, 0.45f * (playing ? crouchBlend_ : 0.0f), armedW, weapon);
    if (!playing) return;
    int armR = m.nodeIndex("arm-right"), armL = m.nodeIndex("arm-left");
    if (reloadTimer_ > 0) {
        // Recarga: a arma desce e gira, a mão esquerda vai ao pente e volta.
        float k = 1.0f - reloadTimer_ / std::max(0.01f, reloadDuration_);
        float dip = std::sin(k * kPi);
        const float reloadTurn = weapon == IT_PISTOL ? 0.45f : (weapon == IT_SNIPER ? 0.2f : 0.0f);
        if (armR >= 0) pose.r[armR] = Quat::axisAngle({0, 0, 1}, -0.35f * dip) * Quat::axisAngle({1, 0, 0}, -kPi * 0.5f - pitch * 0.85f + 0.75f * dip + reloadTurn * dip);
        if (armL >= 0) pose.r[armL] = Quat::axisAngle({0, 1, 0}, -0.3f - reloadTurn * 0.2f) * Quat::axisAngle({1, 0, 0}, -kPi * 0.25f - 0.5f * std::sin(k * kTwoPi));
    }
    if (meleeTime_ >= 0 && armR >= 0) {
        // Golpe de faca: o braço sobe e corta para a frente.
        float wind = 1.0f - smoothstepf(0.0f, 0.14f, meleeTime_);
        float slash = smoothstepf(0.08f, 0.25f, meleeTime_);
        float follow = smoothstepf(0.25f, 0.38f, meleeTime_);
        pose.r[armR] = Quat::axisAngle({0, 1, 0}, 0.42f - 1.1f * slash) *
                       Quat::axisAngle({1, 0, 0}, -2.65f * wind - 0.55f * slash + 0.25f * follow);
        if (armL >= 0) pose.r[armL] = Quat::axisAngle({0, 1, 0}, 0.35f * slash) * Quat::axisAngle({1, 0, 0}, -1.1f + 0.4f * wind);
    }
}

void Game::poseArmed(Pose& pose, const CharacterModel& m, float pitch, bool moveHead) {
    poseArmedBlend(pose, m, pitch, moveHead, 0.0f, 1.0f, IT_RIFLE);
}

// Braços apontando a arma para onde a câmera mira. `lean` compensa o tronco inclinado (agachado);
// `weight` mistura com a pose da animação (sacar/guardar a arma ao parar/começar a correr).
void Game::poseArmedBlend(Pose& pose, const CharacterModel& m, float pitch, bool moveHead, float lean, float weight, int weapon) {
    int armR = m.nodeIndex("arm-right"), armL = m.nodeIndex("arm-left"), head = m.nodeIndex("head"), torso = m.nodeIndex("torso");
    float armPitch = pitch * 0.85f + lean;
    Quat aimR = Quat::axisAngle({0, 1, 0}, 0.08f) * Quat::axisAngle({1, 0, 0}, -kPi * 0.5f - armPitch);
    Quat aimL = Quat::axisAngle({0, 1, 0}, -0.5f) * Quat::axisAngle({1, 0, 0}, -kPi * 0.5f - armPitch + 0.1f);
    if (weapon == IT_PISTOL) {
        // Pistola em uma mão: a esquerda fica recolhida e livre para o equilíbrio.
        aimR = Quat::axisAngle({0, 1, 0}, 0.16f) * Quat::axisAngle({1, 0, 0}, -kPi * 0.5f - armPitch);
        aimL = Quat::axisAngle({0, 1, 0}, -0.92f) * Quat::axisAngle({1, 0, 0}, -kPi * 0.34f - armPitch * 0.35f) * Quat::axisAngle({0, 0, 1}, 0.22f);
    } else if (weapon == IT_SNIPER) {
        // Apoio longo do rifle de precisão, cotovelo esquerdo mais à frente e cabeça baixa.
        aimR = Quat::axisAngle({0, 1, 0}, 0.03f) * Quat::axisAngle({1, 0, 0}, -kPi * 0.5f - armPitch + 0.04f);
        aimL = Quat::axisAngle({0, 1, 0}, -0.3f) * Quat::axisAngle({1, 0, 0}, -kPi * 0.5f - armPitch + 0.18f);
    } else if (weapon == IT_FLAMER) {
        // O lança-chamas é pesado: as duas mãos sustentam a frente do corpo.
        aimR = Quat::axisAngle({0, 1, 0}, 0.12f) * Quat::axisAngle({1, 0, 0}, -kPi * 0.43f - armPitch);
        aimL = Quat::axisAngle({0, 1, 0}, -0.36f) * Quat::axisAngle({1, 0, 0}, -kPi * 0.43f - armPitch + 0.08f);
    }
    float move = clampf(lengthXZ(player_.velocity) / 6.0f, 0.0f, 1.0f);
    float sway = std::sin(time_ * 9.0f) * move * (weapon == IT_SNIPER ? 0.012f : 0.035f);
    float kick = weaponKick_ * (weapon == IT_SNIPER ? 0.18f : (weapon == IT_PISTOL ? 0.14f : 0.1f));
    aimR = Quat::axisAngle({0, 0, 1}, sway) * Quat::axisAngle({1, 0, 0}, kick) * aimR;
    aimL = Quat::axisAngle({0, 0, 1}, -sway * 0.55f) * aimL;
    if (armR >= 0) pose.r[armR] = slerp(pose.r[armR], aimR, weight);
    if (armL >= 0) pose.r[armL] = slerp(pose.r[armL], aimL, weight);
    if (head >= 0 && moveHead) pose.r[head] = slerp(pose.r[head], Quat::axisAngle({1, 0, 0}, -pitch * 0.5f - lean), weight);
    if (torso >= 0 && weapon == IT_SNIPER) pose.r[torso] = slerp(pose.r[torso], pose.r[torso] * Quat::axisAngle({1, 0, 0}, 0.04f), weight);
}

// Arma pendurada nas costas (correndo): cano para cima, na diagonal.
void Game::addWeaponOnBack(const CharacterDraw& draw, int torso, int weapon) {
    const float px = kBlockyPixel;
    Mat4 frame = draw.world * draw.nodes[static_cast<size_t>(torso)] * Mat4::translation({0.0f, 6.5f * px, -2.7f * px}) *
                 Mat4::rotationZ(0.62f) * Mat4::rotationX(-kPi * 0.5f) * Mat4::translation({0.0f, 0.0f, -0.35f});
    addWeaponParts(frame, weapon, nullptr);
}

void Game::addThrowableModel(const CharacterDraw& draw, int armR, int item) {
    Mat4 hand = draw.world * draw.nodes[static_cast<size_t>(armR)] * Mat4::translation({0.0f, -0.82f, 0.05f}) * Mat4::rotationX(kPi * 0.5f);
    auto part = [&](const Vec3& center, const Vec3& size, const Vec4& color) {
        BoxDraw box;
        box.world = hand * Mat4::translation(center) * Mat4::scale(size);
        box.color = color;
        scene_.boxes.push_back(box);
    };
    if (item == IT_SMOKE) {
        part({0, 0, 0.02f}, {0.16f, 0.26f, 0.16f}, {0.62f, 0.68f, 0.72f, 1});
        part({0, 0.14f, 0.02f}, {0.12f, 0.05f, 0.12f}, {0.28f, 0.34f, 0.38f, 1});
        part({0, -0.11f, 0.02f}, {0.18f, 0.04f, 0.18f}, {0.82f, 0.85f, 0.87f, 1});
    } else if (item == IT_MOLOTOV) {
        part({0, 0, 0.02f}, {0.12f, 0.26f, 0.12f}, {0.32f, 0.48f, 0.22f, 1});
        part({0, 0.15f, 0.02f}, {0.06f, 0.08f, 0.06f}, {1.0f, 0.58f, 0.18f, 1});
    } else {
        part({0, 0, 0.02f}, {0.19f, 0.22f, 0.19f}, {0.3f, 0.37f, 0.2f, 1});
        part({0, 0.13f, 0.02f}, {0.08f, 0.05f, 0.08f}, {0.72f, 0.74f, 0.74f, 1});
    }
}

void Game::addRifle(const CharacterDraw& draw, int armR, Vec3* muzzle) {
    Mat4 frame = draw.world * draw.nodes[static_cast<size_t>(armR)] * Mat4::translation({0.0f, -0.9f, 0.0f}) * Mat4::rotationX(kPi * 0.5f);
    addWeaponParts(frame, IT_RIFLE, muzzle);
}

void Game::buildScene() {
    FrameScene& s = scene_;
    s.world = &world_;
    s.view = view_;
    s.proj = proj_;
    s.cameraPos = camPos_;
    s.cameraForward = camForward_;
    s.sky = theme_.sky;
    s.time = time_;
    s.focus = mode_ == Mode::Lobby ? layout_.start : player_.position;
    if (world_.streaming()) {
        float far = viewRadius_ * 16.0f - 6.0f;
        s.sky.fogEnd = std::min(theme_.sky.fogEnd, far);
        s.sky.fogStart = std::min(theme_.sky.fogStart, s.sky.fogEnd * 0.45f);
    }
    s.drawDistance = s.sky.fogEnd + 10.0f;
    // Sem nevoeiro: a mistura com a cor do ceu fica desligada; o alcance de desenho nao muda.
    s.sky.fogStart = 1.0e6f;
    s.sky.fogEnd = 1.0e6f + 1.0f;
    s.characterCount = 0;
    s.boxes.clear();
    s.particles.clear();
    s.shadows.clear();
    s.decals.clear();
    s.beams.clear();
    s.lights.clear();

    // Luzes: explosões e fogo primeiro, depois as fontes fixas mais próximas da câmera.
    struct Cand { float score; PointLight light; };
    static std::vector<Cand> cands;
    cands.clear();
    const Vec3 warm(1.0f, 0.72f, 0.42f);
    // Lanterna noturna: alta, à frente e suave (perto da cabeça ela "estourava" o personagem).
    if (theme_.sky.night > 0.5f && mode_ != Mode::Lobby)
        s.lights.push_back({player_.position + Vec3(0, 4.6f, 0) + forwardFromYaw(camYaw_) * 2.0f, 14.0f, Vec3(0.55f, 0.53f, 0.47f)});
    for (const FlashLight& f : flashes_) {
        float a = f.life / f.maxLife;
        cands.push_back({length(f.pos - camPos_) * 0.3f, {f.pos, f.radius * (0.5f + 0.5f * a), f.color * a}});
    }
    for (const Hazard& h : hazards_) {
        float flicker = 0.85f + 0.15f * std::sin(time_ * 17.0f + h.pos.x);
        Vec3 c = h.kind == 0 ? Vec3(1.0f, 0.55f, 0.2f) * flicker : Vec3(0.35f, 0.9f, 0.25f) * 0.6f;
        cands.push_back({length(h.pos - camPos_) * 0.6f, {h.pos + Vec3(0, 0.8f, 0), h.radius * 3.0f, c}});
    }
    for (const Nest& n : nests_)
        if (!n.destroyed) cands.push_back({length(n.core - camPos_) * 0.8f, {n.core, 12.0f, Vec3(0.75f, 0.25f, 0.95f)}});
    for (const Vec3& l : layout_.lights) {
        float d = length(l - camPos_);
        if (d < 70.0f) cands.push_back({d, {l, 9.0f, warm}});
    }
    if (world_.streaming()) {
        for (const Chunk* c : world_.chunks()) {
            if (c->lights.empty() || distXZ((c->bounds.min + c->bounds.max) * 0.5f, camPos_) > 80.0f) continue;
            for (const Vec3& l : c->lights) {
                float d = length(l - camPos_);
                if (d < 70.0f) cands.push_back({d, {l, 9.0f, warm}});
            }
        }
    }
    std::sort(cands.begin(), cands.end(), [](const Cand& a, const Cand& b) { return a.score < b.score; });
    for (const Cand& c : cands) {
        if (s.lights.size() >= 8) break;
        s.lights.push_back(c.light);
    }
    s.muzzleFlash = muzzleTimer_ > 0 ? Vec4(muzzlePos_.x, muzzlePos_.y, muzzlePos_.z, 1.6f) : Vec4(0, -100, 0, 0);

    // Jogador.
    if (playerAsset_) {
        playerAnim_.evaluate(playerAsset_->model, scratchPose_);
        bool armed = mode_ == Mode::Mission || mode_ == Mode::Pvp;  // no menu o herói fica sem arma
        posePlayer(scratchPose_, armed);
        bool playing = mode_ == Mode::Mission || mode_ == Mode::Pvp;
        Vec3 pos = player_.position + Vec3(0, visualStep_ - (playing ? 0.12f * crouchBlend_ : 0.0f), 0);
        // No editor a câmera está do lado oposto ao herói: o corpo vira de frente para ela.
        float facing = lobbyScreen_ == 2 ? 0.6f : (lobbyScreen_ == 1 ? -0.49f : 0.0f);
        float yaw = mode_ == Mode::Lobby ? kPi + previewYaw_ - facing : playerModelYaw_;
        float flash = invulnerable_ > 0 && damageFlash_ > 0.6f ? 0.45f : 0.0f;
        addCharacter(playerAsset_, pos, yaw, 1.0f, scratchPose_, {1, 1, 1}, flash, Vec3(1, 1, 1), playing ? leanRoll_ : 0.0f);
        CharacterDraw& draw = scene_.characters[scene_.characterCount - 1];
        s.shadows.push_back({player_.position, 0.55f, 0.32f});
        int armR = playerAsset_->model.nodeIndex("arm-right");
        int torsoNode = playerAsset_->model.nodeIndex("torso");
        if (armed && armR >= 0 && !dead_) {
            const bool throwing = playing && (throwAiming_ || throwAnimTime_ >= 0.0f);
            if (throwing) {
                if (torsoNode >= 0) addWeaponOnBack(draw, torsoNode, weaponId());
                if (throwAiming_ || throwAnimTime_ < 0.18f) addThrowableModel(draw, armR, throwAiming_ ? throwableId() : throwAnimItem_);
            } else if (meleeTime_ >= 0) {
                // Durante o golpe a mão direita segura a faca.
                Mat4 hand = draw.world * draw.nodes[static_cast<size_t>(armR)] * Mat4::translation({0.0f, -0.85f, 0.05f}) * Mat4::rotationX(kPi * 0.5f);
                BoxDraw grip;
                grip.world = hand * Mat4::translation({0, 0, 0.08f}) * Mat4::scale({0.09f, 0.09f, 0.24f});
                grip.color = {0.3f, 0.2f, 0.12f, 1};
                s.boxes.push_back(grip);
                BoxDraw blade;
                blade.world = hand * Mat4::translation({0, 0.02f, 0.42f}) * Mat4::scale({0.03f, 0.13f, 0.46f});
                blade.color = {0.85f, 0.88f, 0.92f, 1};
                blade.emissive = 0.2f;
                s.boxes.push_back(blade);
            } else if (playing && armedBlend_ < 0.5f && torsoNode >= 0) {
                // Correndo: a arma vai para as costas e os braços balançam livres.
                addWeaponOnBack(draw, torsoNode, weaponId());
            } else {
                if (mode_ == Mode::Pvp) addRifle(draw, armR, &muzzlePos_);
                else addWeaponModel(draw, armR, weaponId(), &muzzlePos_);
            }
        }
    }

    // Jogadores remotos (PvP).
    if (mode_ == Mode::Pvp) {
        for (int i = 0; i < kMaxNetPlayers; ++i) {
            RemotePlayer& r = remotes_[i];
            if (!r.present || !r.asset || !net_.player(i).active) continue;
            if (length(r.renderPos - camPos_) > s.drawDistance) continue;
            r.anim.evaluate(r.asset->model, scratchPose_);
            bool crouched = r.alive && (r.flags & NF_CROUCH);
            if (crouched) poseCrouch(scratchPose_, r.asset->model, 1.0f, clampf(r.speed / 2.0f, 0.0f, 1.0f));
            const bool holster = (r.flags & NF_SPRINT) && !(r.flags & NF_FIRING);
            if (r.alive && !holster) poseArmed(scratchPose_, r.asset->model, r.renderPitch, true);
            float flash = net_.player(i).invulnerable > 0 && std::fmod(time_, 0.3f) < 0.15f ? 0.25f : 0.0f;
            addCharacter(r.asset, r.renderPos - Vec3(0, crouched ? 0.22f : 0.0f, 0), r.renderYaw, 1.0f, scratchPose_, {1, 1, 1}, flash);
            CharacterDraw& draw = scene_.characters[scene_.characterCount - 1];
            int armR = r.asset->model.nodeIndex("arm-right");
            int torsoNode = r.asset->model.nodeIndex("torso");
            if (r.alive && holster && torsoNode >= 0) {
                addWeaponOnBack(draw, torsoNode, IT_RIFLE);
            } else if (r.alive && armR >= 0) {
                Vec3 muzzle;
                addRifle(draw, armR, &muzzle);
                if (r.fireFlash > 0) {
                    s.particles.push_back({muzzle, {1.0f, 0.82f, 0.4f, 1.0f}, 0.34f, true});
                    if (s.lights.size() < 8) s.lights.push_back({muzzle, 5.0f, warm});
                }
            }
            if (r.alive) s.shadows.push_back({r.renderPos, 0.55f, 0.32f});
        }
    }

    // Zumbis (com itens, capacetes e auras das variações).
    for (auto& z : zombies_) {
        if (!z.asset) continue;
        if (z.state == ZState::Dead && z.deathTime > 3.2f) continue;
        if (length(z.body.position - camPos_) > s.drawDistance) continue;
        z.anim.evaluate(z.asset->model, scratchPose_);
        if (z.state != ZState::Dead) poseZombie(scratchPose_, z, z.asset->model, time_ + z.home.x);
        float flash = z.hitFlash > 0 ? 0.55f : 0.0f;
        Vec3 flashColor(1.0f, 0.25f, 0.2f);
        if (z.action == ZAction::Fuse && std::fmod(z.actionTime, 0.22f) < 0.11f) {
            flash = 0.75f;
            flashColor = {1.0f, 1.0f, 0.85f};
        }
        // Zumbis no raio explosivo piscam em amarelo; fumaça não causa dano.
        if (throwAiming_ && throwableId() != IT_SMOKE && throwLandingValid_ && z.state != ZState::Dead &&
            length(z.body.position + Vec3(0, 0.9f, 0) - throwLanding_) < (throwableId() == IT_MOLOTOV ? 3.2f : 5.0f)) {
            flash = std::max(flash, 0.3f + 0.15f * std::sin(time_ * 12.0f));
            flashColor = {1.0f, 0.85f, 0.3f};
        }
        Vec3 tint = z.tint;
        if (z.frenzy > 0) tint = lerp(tint, Vec3(1.0f, 0.45f, 0.4f), 0.35f);
        addCharacter(z.asset, z.body.position, z.yaw, z.scale, scratchPose_, tint, flash, Vec3(z.bloat, 1.0f, z.bloat));
        CharacterDraw& draw = scene_.characters[scene_.characterCount - 1];
        draw.flashColor = flashColor;
        if (z.state != ZState::Dead) {
            s.shadows.push_back({z.body.position, 0.5f * z.scale * z.bloat, 0.3f});
            addZombieVisuals(z, draw);
        }
    }

    // Coletáveis.
    for (const auto& p : pickups_) {
        float bob = p.type == 2 ? 0.0f : std::sin(p.age * 3.0f) * 0.12f;
        Vec3 pos = p.position + Vec3(0, bob, 0);
        BoxDraw box;
        if (p.type == 0) {
            box.world = Mat4::translation(pos) * Mat4::rotationY(p.age * 2.2f) * Mat4::rotationX(kPi * 0.25f) * Mat4::rotationZ(kPi * 0.25f) * Mat4::scale({0.42f, 0.42f, 0.42f});
            box.color = {0.35f, 0.98f, 0.92f, 1};
            box.emissive = 0.75f;
            s.boxes.push_back(box);
            s.particles.push_back({pos, {0.3f, 1.0f, 0.9f, 0.6f}, 1.3f, true});
        } else if (p.type == 3) {
            // Item do inventário: modelo simples girando + brilho e feixe de luz para achar no chão.
            Mat4 base = Mat4::translation(pos) * Mat4::rotationY(p.age * 1.8f);
            auto part = [&](const Vec3& c, const Vec3& size, const Vec4& color, float emissive) {
                BoxDraw b;
                b.world = base * Mat4::translation(c) * Mat4::scale(size);
                b.color = color;
                b.emissive = emissive;
                s.boxes.push_back(b);
            };
            Vec4 glow{1.0f, 0.9f, 0.5f, 0.5f};
            if (isWeaponItem(p.item)) {
                part({0, 0, 0}, {0.14f, 0.18f, 0.8f}, {0.22f, 0.24f, 0.27f, 1}, 0.0f);
                part({0, -0.14f, 0.1f}, {0.1f, 0.2f, 0.12f}, {0.16f, 0.17f, 0.19f, 1}, 0.0f);
                part({0, 0.1f, 0.2f}, {0.08f, 0.05f, 0.12f}, {1.0f, 0.8f, 0.3f, 1}, 0.8f);
                glow = {1.0f, 0.75f, 0.25f, 0.7f};
            } else {
                switch (p.item) {
                    case IT_MEDKIT:
                        part({0, 0, 0}, {0.42f, 0.3f, 0.3f}, {0.95f, 0.95f, 0.95f, 1}, 0.2f);
                        part({0, 0, 0.16f}, {0.26f, 0.08f, 0.02f}, {0.9f, 0.1f, 0.1f, 1}, 0.5f);
                        part({0, 0, 0.16f}, {0.08f, 0.24f, 0.02f}, {0.9f, 0.1f, 0.1f, 1}, 0.5f);
                        glow = {1.0f, 0.4f, 0.4f, 0.5f};
                        break;
                    case IT_BANDAGE: part({0, 0, 0}, {0.24f, 0.24f, 0.24f}, {0.93f, 0.85f, 0.72f, 1}, 0.2f); glow = {1.0f, 0.6f, 0.6f, 0.4f}; break;
                    case IT_ANTIDOTE:
                        part({0, 0, 0}, {0.16f, 0.3f, 0.16f}, {0.4f, 0.95f, 0.4f, 1}, 0.5f);
                        part({0, 0.2f, 0}, {0.08f, 0.1f, 0.08f}, {0.9f, 0.9f, 0.9f, 1}, 0.0f);
                        glow = {0.5f, 1.0f, 0.4f, 0.5f};
                        break;
                    case IT_ADRENALINE:
                        part({0, 0, 0}, {0.08f, 0.08f, 0.36f}, {0.3f, 0.95f, 1.0f, 1}, 0.7f);
                        part({0, 0, 0.24f}, {0.02f, 0.02f, 0.14f}, {0.85f, 0.85f, 0.9f, 1}, 0.0f);
                        glow = {0.4f, 1.0f, 1.0f, 0.5f};
                        break;
                    case IT_FIRE_AMMO: part({0, 0, 0}, {0.36f, 0.24f, 0.24f}, {1.0f, 0.5f, 0.15f, 1}, 0.6f); glow = {1.0f, 0.55f, 0.2f, 0.6f}; break;
                    case IT_ARMOR:
                        part({0, 0, 0}, {0.4f, 0.44f, 0.16f}, {0.35f, 0.42f, 0.55f, 1}, 0.15f);
                        part({0, 0.12f, 0.09f}, {0.3f, 0.08f, 0.02f}, {0.75f, 0.8f, 0.9f, 1}, 0.3f);
                        glow = {0.6f, 0.7f, 1.0f, 0.5f};
                        break;
                    case IT_MOLOTOV:
                        part({0, 0, 0}, {0.14f, 0.3f, 0.14f}, {0.3f, 0.45f, 0.2f, 1}, 0.0f);
                        part({0, 0.2f, 0}, {0.06f, 0.1f, 0.06f}, {1.0f, 0.6f, 0.2f, 1}, 1.0f);
                        glow = {1.0f, 0.55f, 0.2f, 0.5f};
                        break;
                    case IT_SMOKE:
                        part({0, 0, 0}, {0.2f, 0.32f, 0.2f}, {0.58f, 0.64f, 0.68f, 1}, 0.0f);
                        part({0, 0.2f, 0}, {0.12f, 0.07f, 0.12f}, {0.25f, 0.3f, 0.34f, 1}, 0.0f);
                        part({0, -0.1f, 0}, {0.22f, 0.05f, 0.22f}, {0.78f, 0.82f, 0.84f, 1}, 0.0f);
                        glow = {0.75f, 0.84f, 0.9f, 0.5f};
                        break;
                    default:  // granada
                        part({0, 0, 0}, {0.22f, 0.26f, 0.22f}, {0.28f, 0.34f, 0.18f, 1}, 0.0f);
                        part({0, 0.16f, 0}, {0.08f, 0.06f, 0.08f}, {0.7f, 0.7f, 0.7f, 1}, 0.0f);
                        glow = {0.9f, 1.0f, 0.5f, 0.45f};
                        break;
                }
            }
            s.particles.push_back({pos, glow, 1.0f, true});
            if (length(pos - camPos_) < 60.0f) s.beams.push_back({pos, pos + Vec3(0, 2.6f, 0), {glow.x, glow.y, glow.z, 0.35f}, 0.05f});
        } else if (p.type == 1) {
            box.world = Mat4::translation(pos) * Mat4::rotationY(p.age * 1.6f) * Mat4::scale({0.32f, 0.3f, 0.32f});
            box.color = {0.92f, 0.16f, 0.16f, 1};
            box.emissive = 0.35f;
            s.boxes.push_back(box);
            BoxDraw leaf;
            leaf.world = Mat4::translation(pos + Vec3(0, 0.22f, 0)) * Mat4::rotationY(p.age * 1.6f) * Mat4::scale({0.06f, 0.14f, 0.06f});
            leaf.color = {0.3f, 0.7f, 0.2f, 1};
            s.boxes.push_back(leaf);
            s.particles.push_back({pos, {1.0f, 0.3f, 0.3f, 0.4f}, 0.9f, true});
        } else {
            box.world = Mat4::translation(pos) * Mat4::scale({0.95f, 0.75f, 0.95f});
            box.color = {0.32f, 0.42f, 0.22f, 1};
            s.boxes.push_back(box);
            BoxDraw band;
            band.world = Mat4::translation(pos + Vec3(0, 0.1f, 0)) * Mat4::scale({1.0f, 0.12f, 1.0f});
            band.color = {0.85f, 0.9f, 0.3f, 1};
            band.emissive = 0.6f;
            s.boxes.push_back(band);
            if (std::fmod(p.age, 0.12f) < 0.02f && particles_.size() < 820) {
                FxParticle smoke;
                smoke.position = pos + Vec3(0, 0.5f, 0);
                smoke.velocity = {rng_.range(-0.3f, 0.3f), rng_.range(2.0f, 3.0f), rng_.range(-0.3f, 0.3f)};
                smoke.color = {0.45f, 1.0f, 0.4f, 0.55f};
                smoke.size = rng_.range(0.6f, 1.0f);
                smoke.life = smoke.maxLife = 2.0f;
                particles_.push_back(smoke);
            }
        }
        s.shadows.push_back({{pos.x, std::floor(pos.y - 0.4f), pos.z}, p.type == 2 ? 0.7f : 0.3f, 0.25f});
    }

    // Ninhos de infecção: núcleo pulsante.
    for (const Nest& n : nests_) {
        if (n.destroyed || length(n.core - camPos_) > s.drawDistance) continue;
        float pulse = 1.0f + 0.08f * std::sin(time_ * 4.0f) + (n.hitFlash > 0 ? 0.12f : 0.0f);
        BoxDraw core;
        core.world = Mat4::translation(n.core + Vec3(0, std::sin(time_ * 1.5f) * 0.15f, 0)) * Mat4::rotationY(time_ * 0.8f) * Mat4::rotationX(0.6f) * Mat4::scale({1.1f * pulse, 1.1f * pulse, 1.1f * pulse});
        const bool unstable = config_.nestBomb > 0.0f;  // contrato Ninho instável: núcleo laranja
        core.color = n.hitFlash > 0 ? Vec4(1.0f, 0.9f, 0.9f, 1) : (unstable ? Vec4(1.0f, 0.45f, 0.1f, 1) : Vec4(0.62f, 0.16f, 0.78f, 1));
        core.emissive = 0.8f;
        s.boxes.push_back(core);
        BoxDraw shell;
        shell.world = Mat4::translation(n.core) * Mat4::rotationY(-time_ * 0.5f) * Mat4::rotationZ(0.7f) * Mat4::scale({0.7f, 0.7f, 0.7f});
        shell.color = unstable ? Vec4(1.0f, 0.82f, 0.2f, 1) : Vec4(0.35f, 0.95f, 0.35f, 1);
        shell.emissive = 0.9f;
        s.boxes.push_back(shell);
        s.particles.push_back({n.core, unstable ? Vec4(1.0f, 0.5f, 0.1f, 0.55f) : Vec4(0.75f, 0.3f, 1.0f, 0.55f), 3.2f * pulse, true});
    }

    // Balizas didáticas: feixes verticais para achar o que importa de longe (independem do HUD).
    if (mode_ == Mode::Mission && !survival()) {
        auto ring = [&](const Vec3& at, float radius, const Vec4& color) {
            const int gx = static_cast<int>(std::floor(at.x)), gz = static_cast<int>(std::floor(at.z));
            const float gy = world_.columnReady(gx, gz) ? static_cast<float>(world_.standHeight(gx, gz)) : at.y;
            s.decals.push_back({{at.x, gy, at.z}, radius, color, 1});
        };
        for (const Nest& n : nests_) {          // núcleo vivo a < 60 m
            if (n.destroyed || distXZ(n.core, player_.position) > 60.0f) continue;
            s.beams.push_back({n.core + Vec3(0, 0.9f, 0), n.core + Vec3(0, 9.0f, 0), config_.nestBomb > 0.0f ? Vec4(1.0f, 0.55f, 0.15f, 0.4f) : Vec4(0.85f, 0.4f, 1.0f, 0.35f), 0.18f});
        }
        for (const Pickup& p : pickups_) {      // cristal caído: sempre visível
            if (p.type != 0 || p.taken) continue;
            s.beams.push_back({p.position, p.position + Vec3(0, 9.0f, 0), {0.3f, 1.0f, 0.95f, 0.55f}, 0.22f});
            ring(p.position, 1.8f, {0.3f, 1.0f, 0.95f, 0.7f});
        }
        if (cargo_.placed && cargo_.revealed && !cargo_.collected) {  // carga revelada (4D): sempre visível
            s.beams.push_back({cargo_.pos, cargo_.pos + Vec3(0, 22.0f, 0), {1.0f, 0.62f, 0.12f, 0.6f}, 0.3f});
            ring(cargo_.pos, 2.0f, {1.0f, 0.7f, 0.2f, 0.7f});
        }
        if (portalOpen_) {                      // portal aberto: sempre visível
            s.beams.push_back({layout_.portalBase + Vec3(0, 4.0f, 0), layout_.portalBase + Vec3(0, 18.0f, 0), {0.75f, 0.45f, 1.0f, 0.5f}, 0.5f});
            ring(layout_.portalBase, 3.2f, {0.75f, 0.45f, 1.0f, 0.7f});
        }
    }

    // Carga do contrato Caixa perdida: caixa laranja sem colisão (o jogador a atravessa e coleta por proximidade).
    if (cargo_.placed && !cargo_.collected && length(cargo_.pos - camPos_) < 110.0f) {
        const Vec3 at = cargo_.pos + Vec3(0, 0.35f + std::sin(time_ * 2.0f) * 0.05f, 0);
        BoxDraw crate;
        crate.world = Mat4::translation(at) * Mat4::rotationY(0.4f) * Mat4::scale({0.9f, 0.7f, 0.9f});
        crate.color = {1.0f, 0.5f, 0.12f, 1};
        crate.emissive = 0.45f;
        s.boxes.push_back(crate);
        BoxDraw strap;
        strap.world = Mat4::translation(at + Vec3(0, 0.02f, 0)) * Mat4::rotationY(0.4f) * Mat4::scale({0.94f, 0.2f, 0.94f});
        strap.color = {0.18f, 0.12f, 0.08f, 1};
        s.boxes.push_back(strap);
        s.particles.push_back({at + Vec3(0, 0.9f, 0), {1.0f, 0.65f, 0.2f, 0.5f}, 1.0f, true});
        s.shadows.push_back({cargo_.pos, 0.7f, 0.3f});
    }

    // Baús escondidos nos pontos de interesse.
    for (const Chest& c : chests_) {
        if (length(c.pos - camPos_) > 70.0f) continue;
        BoxDraw base;
        base.world = Mat4::translation(c.pos + Vec3(0, 0.3f, 0)) * Mat4::scale({0.9f, 0.6f, 0.6f});
        base.color = {0.55f, 0.36f, 0.18f, 1};
        s.boxes.push_back(base);
        float lid = c.opened ? std::min(1.0f, c.openAnim * 3.0f) * 1.2f : 0.0f;
        BoxDraw top;
        top.world = Mat4::translation(c.pos + Vec3(0, 0.6f, -0.3f)) * Mat4::rotationX(-lid) * Mat4::translation({0, 0.09f, 0.3f}) * Mat4::scale({0.92f, 0.18f, 0.62f});
        top.color = {0.62f, 0.42f, 0.22f, 1};
        s.boxes.push_back(top);
        BoxDraw lock;
        lock.world = Mat4::translation(c.pos + Vec3(0, 0.5f, 0.31f)) * Mat4::scale({0.14f, 0.18f, 0.04f});
        lock.color = {1.0f, 0.82f, 0.25f, 1};
        lock.emissive = 0.6f;
        s.boxes.push_back(lock);
        if (!c.opened) s.particles.push_back({c.pos + Vec3(0, 0.9f + std::sin(time_ * 3.0f + c.pos.x) * 0.15f, 0), {1.0f, 0.85f, 0.3f, 0.5f}, 0.9f, true});
        s.shadows.push_back({c.pos, 0.6f, 0.3f});
    }

    // Suprimentos descendo de paraquedas.
    if (supply_.active) {
        BoxDraw crate;
        crate.world = Mat4::translation(supply_.pos) * Mat4::rotationY(time_ * 0.6f) * Mat4::scale({0.95f, 0.75f, 0.95f});
        crate.color = {0.32f, 0.42f, 0.22f, 1};
        s.boxes.push_back(crate);
        BoxDraw chute;
        chute.world = Mat4::translation(supply_.pos + Vec3(0, 3.0f, 0)) * Mat4::scale({2.6f, 0.4f, 2.6f});
        chute.color = {0.95f, 0.35f, 0.2f, 1};
        s.boxes.push_back(chute);
        for (int i = 0; i < 4; ++i) {
            Vec3 corner((i & 1) ? 1.1f : -1.1f, 1.8f, (i & 2) ? 1.1f : -1.1f);
            s.beams.push_back({supply_.pos + Vec3(0, 0.4f, 0), supply_.pos + corner + Vec3(0, 1.0f, 0), {0.9f, 0.9f, 0.9f, 0.8f}, 0.04f});
        }
        float groundY = static_cast<float>(world_.standHeight(static_cast<int>(std::floor(supply_.pos.x)), static_cast<int>(std::floor(supply_.pos.z))));
        s.decals.push_back({{supply_.pos.x, groundY, supply_.pos.z}, 1.6f, {0.5f, 1.0f, 0.4f, 0.8f}, 1});
    }

    // Projéteis e avisos de onde vão cair.
    for (const Projectile& p : projectiles_) {
        BoxDraw b;
        switch (p.kind) {
            case ProjectileKind::Rock:
                b.world = Mat4::translation(p.pos) * Mat4::rotationX(p.spin) * Mat4::rotationZ(p.spin * 0.7f) * Mat4::scale({0.34f, 0.3f, 0.32f});
                b.color = {0.48f, 0.46f, 0.44f, 1};
                s.boxes.push_back(b);
                break;
            case ProjectileKind::Molotov:
                b.world = Mat4::translation(p.pos) * Mat4::rotationX(p.spin) * Mat4::scale({0.14f, 0.3f, 0.14f});
                b.color = {0.3f, 0.45f, 0.2f, 1};
                s.boxes.push_back(b);
                s.particles.push_back({p.pos + Vec3(0, 0.15f, 0), {1.0f, 0.6f, 0.2f, 0.9f}, 0.55f, true});
                if (s.lights.size() < 8) s.lights.push_back({p.pos, 6.0f, Vec3(1.0f, 0.55f, 0.2f)});
                s.decals.push_back({p.target, 2.6f, {1.0f, 0.45f, 0.1f, 0.85f}, 1});
                break;
            case ProjectileKind::Acid:
                s.particles.push_back({p.pos, {0.55f, 1.0f, 0.25f, 0.95f}, 0.6f, true});
                s.particles.push_back({p.pos - normalize(p.vel) * 0.3f, {0.4f, 0.8f, 0.2f, 0.6f}, 0.4f, true});
                s.decals.push_back({p.target, 2.0f, {0.5f, 1.0f, 0.2f, 0.8f}, 1});
                break;
            case ProjectileKind::Grenade:
                b.world = Mat4::translation(p.pos) * Mat4::rotationX(p.spin) * Mat4::rotationZ(p.spin * 0.6f) * Mat4::scale({0.22f, 0.26f, 0.22f});
                b.color = {0.28f, 0.34f, 0.18f, 1};
                s.boxes.push_back(b);
                s.decals.push_back({p.target, 5.0f, {1.0f, 0.85f, 0.3f, 0.55f}, 1});
                break;
            case ProjectileKind::Smoke:
                b.world = Mat4::translation(p.pos) * Mat4::rotationX(p.spin) * Mat4::rotationZ(p.spin * 0.6f) * Mat4::scale({0.2f, 0.32f, 0.2f});
                b.color = {0.58f, 0.64f, 0.68f, 1};
                s.boxes.push_back(b);
                s.particles.push_back({p.pos + Vec3(0, 0.18f, 0), {0.75f, 0.84f, 0.9f, 0.65f}, 0.38f, false});
                s.decals.push_back({p.target, 5.2f, {0.65f, 0.76f, 0.82f, 0.48f}, 1});
                break;
            case ProjectileKind::Knife: {
                Vec3 d = normalize(p.vel);
                float yaw = std::atan2(d.x, d.z);
                float pitch = -std::asin(clampf(d.y, -1, 1));
                b.world = Mat4::translation(p.pos) * Mat4::rotationY(yaw) * Mat4::rotationX(pitch) * Mat4::rotationZ(p.spin) * Mat4::scale({0.05f, 0.14f, 0.5f});
                b.color = {0.85f, 0.88f, 0.92f, 1};
                b.emissive = 0.2f;
                s.boxes.push_back(b);
                break;
            }
        }
    }
    for (const Hazard& h : hazards_) {
        float a = clampf(h.life / 0.8f, 0, 1);
        if (h.kind == 0) s.decals.push_back({h.pos, h.radius * 1.15f, {0.08f, 0.04f, 0.02f, 0.55f * a}, 0});
        else s.decals.push_back({h.pos, h.radius, {0.4f, 0.95f, 0.2f, 0.6f * a}, 0});
    }

    // Mira do arremesso: arco pontilhado, anel de alcance da explosão e marcador no ponto de queda.
    if (throwAiming_ && throwArc_.size() > 1) {
        const int kind = throwableId();
        const bool molotov = kind == IT_MOLOTOV;
        const Vec4 arc = molotov ? Vec4(1.0f, 0.65f, 0.25f, 0.9f)
                                 : (kind == IT_SMOKE ? Vec4(0.72f, 0.84f, 0.92f, 0.9f) : Vec4(1.0f, 0.95f, 0.55f, 0.9f));
        for (size_t i = 0; i + 1 < throwArc_.size(); ++i) {
            if (i % 2 == 1) continue;  // tracejado
            float fade = 0.45f + 0.55f * (static_cast<float>(i) / throwArc_.size());
            s.beams.push_back({throwArc_[i], throwArc_[i + 1], {arc.x, arc.y, arc.z, arc.w * fade}, 0.06f});
        }
        if (throwLandingValid_) {
            const float radius = molotov ? 3.2f : 5.0f;
            int gx = static_cast<int>(std::floor(throwLanding_.x)), gz = static_cast<int>(std::floor(throwLanding_.z));
            float ground = world_.columnReady(gx, gz) ? static_cast<float>(world_.standHeight(gx, gz)) : throwLanding_.y;
            if (ground > throwLanding_.y + 1.5f) ground = std::floor(throwLanding_.y);
            Vec3 spot(throwLanding_.x, ground, throwLanding_.z);
            const Vec4 landingColor = molotov ? Vec4(1.0f, 0.45f, 0.1f, 0.85f)
                                              : (kind == IT_SMOKE ? Vec4(0.66f, 0.78f, 0.86f, 0.75f) : Vec4(1.0f, 0.25f, 0.15f, 0.85f));
            s.decals.push_back({spot, radius, landingColor, 1});
            s.decals.push_back({spot, 0.6f, {1.0f, 1.0f, 1.0f, 0.9f}, 0});
            float pulse = 0.8f + 0.2f * std::sin(time_ * 9.0f);
            s.particles.push_back({throwLanding_ + Vec3(0, 0.3f, 0), {arc.x, arc.y, arc.z, 0.8f}, 0.9f * pulse, true});
            s.beams.push_back({spot, spot + Vec3(0, 3.0f, 0), {arc.x, arc.y, arc.z, 0.5f}, 0.07f});
        }
    }

    // Partículas, fogueiras e traçantes.
    for (const auto& p : particles_) {
        float a = clampf(p.life / std::max(0.01f, p.maxLife), 0, 1);
        s.particles.push_back({p.position, {p.color.x, p.color.y, p.color.z, p.color.w * a}, p.size * (p.additive ? 1.0f : 0.8f + a * 0.2f), p.additive});
    }
    auto fireGlow = [&](const Vec3& fire) {
        if (length(fire - camPos_) > 60.0f) return;
        float flicker = 0.85f + 0.15f * std::sin(time_ * 13.0f + fire.x);
        s.particles.push_back({fire + Vec3(0, 0.45f, 0), {1.0f, 0.55f, 0.15f, 0.8f}, 1.3f * flicker, true});
        s.particles.push_back({fire + Vec3(0, 0.3f, 0), {1.0f, 0.85f, 0.4f, 0.9f}, 0.7f * flicker, true});
    };
    for (const Vec3& fire : layout_.campfires) fireGlow(fire);
    if (world_.streaming()) {
        for (const Chunk* c : world_.chunks()) {
            if (c->campfires.empty() || distXZ((c->bounds.min + c->bounds.max) * 0.5f, camPos_) > 70.0f) continue;
            for (const Vec3& fire : c->campfires) fireGlow(fire);
        }
    }
    for (const auto& t : tracers_) s.beams.push_back({t.from, t.to, {t.color.x, t.color.y, t.color.z, std::min(1.0f, t.life * 18.0f)}, 0.045f});

    s.portalVisible = mode_ == Mode::Mission && !survival();
    s.portalCenter = layout_.portalBase;
    s.portalYaw = layout_.portalYaw;
    s.portalOpen = portalAnim_;
    if (portalOpen_ && s.lights.size() < 8) s.lights.push_back({layout_.portalBase + Vec3(0, 2, 0), 10.0f, Vec3(0.8f, 0.5f, 1.0f)});
}

// ----------------------------------------------------------------------------- PvP (Wi-Fi local)

void Game::resetPlayerState(const Vec3& position, float yaw, float health, int magazine) {
    player_ = BodyState{};
    player_.halfWidth = 0.3f;
    player_.height = 1.75f;
    player_.position = position;
    lastPlayerPos_ = position;
    playerYaw_ = playerModelYaw_ = camYaw_ = yaw;
    camPitch_ = -0.14f;
    camDistance_ = 4.0f;
    maxHealth_ = health_ = health;
    ammo_ = magazine;
    reloadTimer_ = 0;
    reloadDuration_ = 1.6f / std::max(0.5f, config_.reloadSpeed);
    fireCooldown_ = 0;
    bloom_ = recoil_ = aimBlend_ = 0;
    weaponKick_ = 0;
    aimToggle_ = false;
    invulnerable_ = 1.0f;
    damageFlash_ = damageDirTimer_ = 0;
    visualStep_ = 0;
    kills_ = headshots_ = shots_ = hits_ = 0;
    combo_ = 0;
    comboTimer_ = 0;
    damageTaken_ = 0;
    missionTime_ = 0;
    portalOpen_ = false;
    portalAnim_ = 0;
    crystalsSpawnedMask_ = 0;
    crystalsCollectedMask_ = 0;
    dead_ = false;
    victory_ = false;
    burning_ = bleeding_ = poisoned_ = slowed_ = damageBoost_ = shake_ = 0;
    distance_ = 0;
    chestsOpened_ = chestDiamonds_ = 0;
    seenKinds_ = 0;
    crouching_ = sprintLock_ = false;
    crouchBlend_ = leanRoll_ = sprintFov_ = 0;
    player_.height = 1.75f;
    meleeTime_ = -1;
    meleeCooldown_ = grenadeCooldown_ = emptyClick_ = 0;
    coyote_ = jumpBuffer_ = 0;
    mantleTime_ = -1;
    wallPush_ = stuckTimer_ = 0;
    stuckAnchor_ = position;
    camPivotInit_ = false;
    rackPlayed_ = true;
    resetInventory();
    pickups_.clear();
    particles_.clear();
    labels_.clear();
    tracers_.clear();
    projectiles_.clear();
    smokeClouds_.clear();
    hazards_.clear();
    explosions_.clear();
    flashes_.clear();
    std::lock_guard<std::mutex> lock(inputMutex_);
    input_ = InputState{};
}

void Game::stopPvp() {
    net_.stop();
    for (auto& r : remotes_) r = RemotePlayer{};
}

void Game::startPvp(const PvpConfig& c) {
    prof_.skipFrames(3);
    uploadUnlimitedFrames_ = 3;
    streamStats_ = StreamStats{};
    stopPvp();
    pvp_ = c;
    mode_ = Mode::Pvp;
    paused_ = false;
    config_ = MissionConfig{};
    reservePct_ = -1;  // PvP não herda reserva de munição nem alertas da missão anterior
    alerts_ = 0;
    bombs_ = 0;        // nem Inchados de ninho nem carga: contratos não existem no PvP
    cargo_ = Cargo{};
    config_.stage = 0;
    config_.skin = c.looks[c.localId];
    config_.playerHealth = 100;
    config_.magazine = 30;
    config_.crystalTotal = 0;
    config_.sensitivity = c.sensitivity;
    config_.invertY = c.invertY;
    config_.aimAssist = false;  // PvP: sem assistência de mira nem tiro automático
    config_.autoFire = false;
    config_.seed = c.seed;
    int zoneStage = static_cast<int>(c.seed % 4) * 5 + 1;
    theme_ = applySkyVariant(themeForStage(zoneStage), c.seed, true);
    renderer_.setTheme(theme_);
    {
        std::vector<uint8_t> layers = generateBlockTextures(theme_);
        layerColors_.resize(T_COUNT);
        for (int i = 0; i < T_COUNT; ++i) layerColors_[i] = averageLayerColor(layers, i);
    }
    rng_.reseed(c.seed * 2246822519u + 3u + static_cast<uint32_t>(c.localId));
    GenOptions options;
    options.size = 128;
    options.camps = 5;
    options.portal = false;
    options.pvp = true;
    layout_ = generateStageWorld(world_, theme_, 1, c.seed, options);
    island_.reset();
    world_.rebuildDirty(1 << 20);
    zombies_.clear();
    nests_.clear();
    chests_.clear();
    supply_ = SupplyDrop{};
    suppliesLeft_ = 0;

    Vec3 spawn = layout_.spawnPoints.empty() ? layout_.start : layout_.spawnPoints[static_cast<size_t>(c.localId) % layout_.spawnPoints.size()];
    Vec3 toCenter = Vec3(world_.width() * 0.5f, 0, world_.depth() * 0.5f) - spawn;
    resetPlayerState(spawn, yawFromDirection(toCenter.x, toCenter.z), 100, 30);
    invulnerable_ = 2.0f;
    playerAsset_ = character(config_.skin);
    playerAnim_ = Animator();
    if (playerAsset_) playerAnim_.play(playerAsset_->model.clipIndex("idle"), true, 0);

    for (int i = 0; i < kMaxNetPlayers; ++i) {
        remotes_[i] = RemotePlayer{};
        if (i == c.localId || c.skins[i] == 255) continue;
        RemotePlayer& r = remotes_[i];
        r.present = true;
        r.asset = character(c.looks[i]);
        if (r.asset) r.anim.play(r.asset->model.clipIndex("idle"), true, 0);
        if (!layout_.spawnPoints.empty()) r.renderPos = layout_.spawnPoints[static_cast<size_t>(i) % layout_.spawnPoints.size()];
    }
    NetConfig nc;
    nc.host = c.host;
    nc.localId = c.localId;
    nc.hostIp = c.hostIp;
    nc.killLimit = c.killLimit;
    nc.timeLimit = c.timeLimit;
    for (int i = 0; i < kMaxNetPlayers; ++i) { nc.skins[i] = c.skins[i]; nc.ips[i] = c.ips[i]; }
    bool ok = net_.start(nc, layout_.spawnPoints);
    // Anfitrião: tiros remotos que atravessam blocos sólidos do mundo da arena são rejeitados (ver pvp_rules.h).
    net_.setLineOfSight([this](const Vec3& a, const Vec3& b) { return world_.lineOfSight(a, b); });
    pvpSpawnSeq_ = 0;
    pvpHealthSeen_ = 100;
    pvpEnded_ = false;
    pvpLost_ = false;
    {
        std::lock_guard<std::mutex> lock(outMutex_);
        events_.clear();
        result_.fill(0);
    }
    lastTime_ = -1;
    emit(EV_MISSION_READY, static_cast<int>(layout_.style));
    if (!ok) {
        pvpLost_ = true;
        emit(EV_PVP_LOST);
    }
    LOGI("PvP: jogador %d, %zu pontos de renascimento, estilo %d", c.localId, layout_.spawnPoints.size(), static_cast<int>(layout_.style));
}

void Game::updatePvp(float dt) {
    InputState in;
    consumeInput(in);
    aimStage_ = std::max(0, std::min(2, in.aimStage));
    aimToggle_ = in.aim || aimStage_ > 0;
    missionTime_ += dt;

    // Vida, morte e renascimento vêm do anfitrião.
    const NetPlayer& me = net_.player(pvp_.localId);
    maxHealth_ = 100;
    if (net_.isHost() || net_.snapshotReceived()) {
        health_ = me.health;
        if (!me.alive && !dead_) {
            dead_ = true;
            firingNow_ = false;
            reloadTimer_ = 0;
        }
        if (me.alive && me.spawnSeq != pvpSpawnSeq_) {
            pvpSpawnSeq_ = me.spawnSeq;
            Vec3 spawn = layout_.spawnPoints.empty() ? layout_.start : layout_.spawnPoints[me.spawnIndex % layout_.spawnPoints.size()];
            player_.position = spawn;
            player_.velocity = {};
            mantleTime_ = -1;
            camPivotInit_ = false;
            crouching_ = false;
            dead_ = false;
            invulnerable_ = 2.0f;
            ammo_ = config_.magazine;
            reloadTimer_ = 0;
            health_ = pvpHealthSeen_ = 100;
            if (playerAsset_) playerAnim_.play(playerAsset_->model.clipIndex("idle"), true, 0.1f);
            spawnBurst(spawn + Vec3(0, 1, 0), {0.4f, 0.9f, 1.0f, 1.0f}, 24, 4.0f, 0.14f, true, -1.0f);
        }
        if (health_ < pvpHealthSeen_ - 0.5f) {
            float dmg = pvpHealthSeen_ - health_;
            damageFlash_ = 1.0f;
            damageTaken_ += dmg;
            emit(EV_PLAYER_HURT, static_cast<int>(dmg));
            emitSound(SND_HURT, player_.position, 0.9f);
            if (me.lastAttacker < kMaxNetPlayers) {
                Vec3 d = remotes_[me.lastAttacker].renderPos - player_.position;
                damageDir_ = wrapAngle(yawFromDirection(d.x, d.z) - camYaw_);
                damageDirTimer_ = 1.2f;
            }
        }
        pvpHealthSeen_ = health_;
    }

    updateCamera(dt, in);
    updatePlayer(dt, in);
    if (!dead_ && !net_.ended()) updateWeapon(dt, in);
    else firingNow_ = false;

    float horizontal = lengthXZ(player_.velocity);
    uint8_t flags = 0;
    if (horizontal > 0.5f) flags |= NF_MOVING;
    if (sprinting_) flags |= NF_SPRINT;
    if (!player_.onGround) flags |= NF_AIR;
    if (firingNow_) flags |= NF_FIRING;
    if (aimBlend_ > 0.5f) flags |= NF_AIM;
    if (player_.inWater) flags |= NF_WATER;
    if (crouching_) flags |= NF_CROUCH;
    net_.setLocalState(player_.position, playerModelYaw_, camPitch_, flags, horizontal);

    netEvents_.clear();
    net_.update(dt, netEvents_);
    for (const NetEvent& e : netEvents_) {
        switch (e.kind) {
            case NE_FIRE:
                if (e.a < kMaxNetPlayers && e.a != pvp_.localId) {
                    tracers_.push_back({e.from, e.to, 0.07f});
                    remotes_[e.a].fireFlash = 0.06f;
                    remotes_[e.a].lastFire = time_;
                    emitSound(SND_SHOT, e.from, 0.75f);
                    spawnBurst(e.to, {1.0f, 0.85f, 0.5f, 1.0f}, 3, 2.0f, 0.07f, true, 8.0f);
                }
                break;
            case NE_KILL:
                if (e.a == pvp_.localId) {
                    ++kills_;
                    if (e.c) ++headshots_;
                }
                emit(EV_PVP_KILL, e.a, e.b, e.c);
                break;
            case NE_LEFT:
                emit(EV_PVP_LEFT, e.a);
                break;
            default:
                break;
        }
    }
    if (net_.ended() && !pvpEnded_) {
        pvpEnded_ = true;
        firingNow_ = false;
        emit(EV_PVP_END, net_.winner());
    }
    if (net_.connectionLost() && !pvpLost_) {
        pvpLost_ = true;
        emit(EV_PVP_LOST);
    }
    updateRemotes(dt);
    updateEffects(dt);
}

void Game::updateRemotes(float dt) {
    const float delay = 0.1f;  // atraso de interpolação
    for (int i = 0; i < kMaxNetPlayers; ++i) {
        RemotePlayer& r = remotes_[i];
        if (!r.present) continue;
        const NetPlayer& np = net_.player(i);
        if (np.version != r.lastVersion) {
            r.lastVersion = np.version;
            bool teleport = r.sampleCount > 0 && distXZ(r.samples[r.sampleCount - 1].pos, np.pos) > 6.0f;
            if (teleport) {
                r.sampleCount = 0;
                r.moveVelocity = {};
            }
            if (r.sampleCount == 8) {
                for (int k = 1; k < 8; ++k) r.samples[k - 1] = r.samples[k];
                r.sampleCount = 7;
            }
            const bool hasPrevious = r.sampleCount > 0;
            const RemoteSample previous = hasPrevious ? r.samples[r.sampleCount - 1] : RemoteSample{};
            RemoteSample& s = r.samples[r.sampleCount++];
            s.time = time_;
            s.pos = np.pos;
            s.yaw = np.yaw;
            s.pitch = np.pitch;
            s.speed = np.speed;
            s.flags = np.flags;
            if (hasPrevious && s.time > previous.time + 1e-3f) {
                Vec3 delta = s.pos - previous.pos;
                float distance = lengthXZ(delta);
                if (distance > 0.02f && distance < 6.0f) {
                    delta.y = 0.0f;
                    r.moveVelocity = delta * (np.speed / distance);
                } else if (distance <= 0.02f && np.speed <= 0.5f) {
                    r.moveVelocity = {};
                }
            }
        }
        if (r.sampleCount > 0) {
            float rt = time_ - delay;
            const RemoteSample* a = &r.samples[0];
            const RemoteSample* b = a;
            if (rt >= r.samples[r.sampleCount - 1].time) {
                a = b = &r.samples[r.sampleCount - 1];
            } else {
                for (int k = 0; k + 1 < r.sampleCount; ++k) {
                    if (r.samples[k + 1].time >= rt) {
                        a = &r.samples[k];
                        b = &r.samples[k + 1];
                        break;
                    }
                }
            }
            float span = b->time - a->time;
            float t = span > 1e-4f ? clampf((rt - a->time) / span, 0, 1) : 1.0f;
            r.renderPos = lerp(a->pos, b->pos, t);
            r.renderYaw = lerpAngle(a->yaw, b->yaw, t);
            r.renderPitch = lerpf(a->pitch, b->pitch, t);
            r.speed = lerpf(a->speed, b->speed, t);
            r.flags = b->flags;
        }
        r.fireFlash = std::max(0.0f, r.fireFlash - dt);
        if (np.alive != r.alive) {
            r.alive = np.alive;
            if (r.asset) r.anim.play(r.asset->model.clipIndex(r.alive ? "idle" : "die"), r.alive, 0.1f);
        }
        if (r.asset && r.alive) {
            const CharacterModel& m = r.asset->model;
            const bool sprinting = r.speed > 5.4f && !(r.flags & NF_CROUCH);
            int clip = locomotionClip(m, r.moveVelocity, r.renderYaw, r.speed, sprinting, (r.flags & NF_CROUCH) != 0);
            float speed = sprinting ? r.speed / 6.0f : std::max(0.6f, r.speed / 3.6f);
            r.anim.play(clip, true, sprinting ? 0.15f : 0.2f, speed);
        }
        r.anim.update(dt);
    }
}

bool Game::project(const Vec3& p, float& sx, float& sy) const {
    Vec4 clip = (proj_ * view_).transform({p.x, p.y, p.z, 1.0f});
    if (clip.w <= 0.05f) return false;
    float nx = clip.x / clip.w, ny = clip.y / clip.w;
    sx = nx * 0.5f + 0.5f;
    sy = 1.0f - (ny * 0.5f + 0.5f);
    return std::fabs(nx) <= 1.05f && std::fabs(ny) <= 1.05f;
}

void Game::publishHud() {
    std::array<float, H_SIZE> h{};
    int alive = 0;
    for (const auto& z : zombies_)
        if (z.state != ZState::Dead) ++alive;
    h[H_HEALTH] = health_;
    h[H_MAX_HEALTH] = maxHealth_;
    h[H_AMMO] = static_cast<float>(ammo_);
    h[H_MAG] = static_cast<float>(magazineFor(weaponId()));
    h[H_RELOAD] = reloadTimer_ > 0 ? 1.0f - reloadTimer_ / reloadDuration_ : -1.0f;
    h[H_ALIVE] = static_cast<float>(alive);
    h[H_TOTAL] = static_cast<float>(zombies_.size());
    h[H_PORTAL_OPEN] = portalOpen_ ? 1.0f : 0.0f;
    int crystals = 0;
    for (int i = 0; i < 8; ++i)
        if (crystalsCollectedMask_ & (1 << i)) ++crystals;
    h[H_CRYSTALS] = static_cast<float>(crystals);
    h[H_ELAPSED] = missionTime_;
    h[H_TARGET_LOCKED] = targetLocked_ ? 1.0f : 0.0f;
    h[H_TARGET_HP] = targetHp_;
    h[H_AIM] = aimBlend_ * (aimStage_ >= 2 ? 2.0f : 1.0f);
    {
        const WeaponDef& w = weaponDef(weaponId());
        h[H_SPREAD] = lerpf(w.spreadHip, w.spreadAim, aimBlend_) + bloom_ + std::min(1.0f, lengthXZ(player_.velocity) / kPlayerSpeed) * 0.008f;
    }
    h[H_HITMARK] = hitMark_ / 0.18f;
    h[H_HEADSHOT_MARK] = headshotMark_ ? 1.0f : 0.0f;
    h[H_DAMAGE_FLASH] = damageFlash_;
    h[H_PLAYER_X] = player_.position.x;
    h[H_PLAYER_Z] = player_.position.z;
    h[H_CAM_YAW] = camYaw_;
    h[H_PORTAL_X] = layout_.portalBase.x;
    h[H_PORTAL_Z] = layout_.portalBase.z;
    h[H_SPRINTING] = sprinting_ ? 1.0f : 0.0f;
    h[H_JUMP_READY] = player_.onGround ? 1.0f : 0.0f;
    h[H_KILLS] = static_cast<float>(kills_);
    h[H_HEADSHOTS] = static_cast<float>(headshots_);
    h[H_DAMAGE_DIR] = damageDir_;
    h[H_DAMAGE_DIR_T] = damageDirTimer_;
    float sx = 0, sy = 0;
    bool onScreen = project(layout_.portalBase + Vec3(0, 2.0f, 0), sx, sy);
    h[H_PORTAL_SX] = sx;
    h[H_PORTAL_SY] = sy;
    h[H_PORTAL_ONSCREEN] = onScreen ? 1.0f : 0.0f;
    h[H_FIRING] = firingNow_ ? 1.0f : 0.0f;
    h[H_WORLD_SIZE] = static_cast<float>(world_.width());
    h[H_STATE] = dead_ ? 1.0f : (victory_ ? 2.0f : 0.0f);
    h[H_IN_WATER] = player_.inWater ? 1.0f : 0.0f;
    h[H_FOV] = fov_;
    {
        // O texto "NN FPS" do HUD reflete a janela real (~5 s); antes de ter amostras usa a média exponencial.
        const perf::Snapshot& ps = prof_.snapshot();
        h[H_FPS] = ps.frames >= 30 ? ps.fps : fps_;
        h[H_PERF_FPS] = ps.fps;
        h[H_PERF_AVG_MS] = ps.intAvg;
        h[H_PERF_P95_MS] = ps.intP95;
        h[H_PERF_MAX_MS] = ps.intMax;
        h[H_PERF_WORK_MS] = ps.workAvg;
        h[H_PERF_STREAM_MS] = ps.sec[static_cast<int>(perf::Sec::Stream)];
        h[H_PERF_PENDING] = static_cast<float>(streamStats_.pending);
        h[H_PERF_GEN_PS] = prof_.genPerSecond();
        h[H_PERF_UP_PS] = prof_.uploadsPerSecond();
        h[H_PERF_FLAGS] = static_cast<float>(perfFlags());
    }
    // Sobrevivência não tem ninhos nem cristais: o HUD não deve mostrar "0/3".
    h[H_CRYSTAL_TOTAL] = survival() ? 0.0f : static_cast<float>(nests_.empty() ? config_.crystalTotal : static_cast<int>(nests_.size()));
    h[H_COMBO] = static_cast<float>(combo_);
    h[H_CROUCH] = crouching_ ? 1.0f : 0.0f;
    h[H_SHOULDER] = shoulder_;
    h[H_SPRINT_LOCK] = sprintLock_ ? 1.0f : 0.0f;
    h[H_GRENADES] = inventoryEnabled() ? static_cast<float>(countItem(throwableId())) : -1.0f;
    h[H_MELEE] = mode_ == Mode::Mission ? meleeCooldown_ / 0.55f : -1.0f;

    // Inventário (barra rápida e mochila) e efeitos dos itens.
    h[H_INV_ENABLED] = inventoryEnabled() ? 1.0f : 0.0f;
    h[H_WEAPON] = static_cast<float>(weaponId());
    if (inventoryEnabled()) {
        for (int i = 0; i < kInvSlots; ++i) {
            h[H_INV + i * 2] = static_cast<float>(inv_[static_cast<size_t>(i)].id);
            h[H_INV + i * 2 + 1] = static_cast<float>(inv_[static_cast<size_t>(i)].count);
        }
        h[H_WEAPON_SLOT] = static_cast<float>(weaponSlot_);
        int ts = throwSlot_;
        if (ts < 0 || !isThrowableItem(inv_[static_cast<size_t>(ts)].id)) {
            ts = -1;
            for (int i = 0; i < kInvSlots; ++i)
                if (isThrowableItem(inv_[static_cast<size_t>(i)].id)) { ts = i; break; }
        }
        h[H_THROW_SLOT] = static_cast<float>(ts);
        h[H_THROW_KIND] = static_cast<float>(throwableId());
        h[H_ARMOR] = armor_;
        h[H_ARMOR_MAX] = armorMax_;
        h[H_ADRENALINE] = adrenaline_;
        h[H_SWAP] = swapTimer_ / 0.35f;
        h[H_THROW_AIM] = throwAiming_ ? 1.0f : 0.0f;
        if (throwAiming_ && throwLandingValid_) {
            h[H_THROW_DIST] = distXZ(throwLanding_, player_.position);
            float tx = 0, ty = 0;
            bool on = project(throwLanding_ + Vec3(0, 0.6f, 0), tx, ty);
            h[H_THROW_SX] = tx;
            h[H_THROW_SY] = ty;
            h[H_THROW_ONSCREEN] = on ? 1.0f : 0.0f;
        }
    }
    if (survival()) {
        h[H_SURV] = 1.0f;
        h[H_WAVE] = static_cast<float>(wave_);
        h[H_WAVE_STATE] = static_cast<float>(waveState_);
        h[H_WAVE_TIMER] = waveTimer_;
        int killed = kills_ - waveKilledBase_;
        h[H_WAVE_PROGRESS] = waveTotal_ > 0 ? clampf(static_cast<float>(killed) / waveTotal_, 0.0f, 1.0f) : 0.0f;
        h[H_WAVE_LEFT] = static_cast<float>(std::max(0, waveTotal_ - killed));
        h[H_WAVE_BIG] = waveBig_ ? 1.0f : 0.0f;
        h[H_SURV_TIME] = survivalTime_;
        h[H_SCORE] = static_cast<float>(score_ + static_cast<int>(survivalTime_ * 2.0f));
        h[H_FRONT_COUNT] = waveState_ == 1 ? static_cast<float>(frontCount_) : 0.0f;
        for (int i = 0; i < 3; ++i) h[H_FRONTS + i] = frontAngles_[i];
    }

    // Inimigos para o minimapa: os mais próximos primeiro.
    static std::vector<std::pair<float, const Zombie*>> near;
    near.clear();
    for (const auto& z : zombies_)
        if (z.state != ZState::Dead) near.push_back({distXZ(z.body.position, player_.position), &z});
    std::sort(near.begin(), near.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    int n = 0;
    for (const auto& e : near) {
        if (n >= kMaxHudEnemies) break;
        h[H_ENEMIES + n * 3] = e.second->body.position.x;
        h[H_ENEMIES + n * 3 + 1] = e.second->body.position.z;
        h[H_ENEMIES + n * 3 + 2] = e.second->alerted ? 1.0f : 0.0f;
        ++n;
    }
    h[H_ENEMY_COUNT] = static_cast<float>(n);

    n = 0;
    for (const auto& l : labels_) {
        if (n >= kMaxHudLabels) break;
        float lx, ly;
        if (!project(l.position, lx, ly)) continue;
        h[H_LABELS + n * 5] = lx;
        h[H_LABELS + n * 5 + 1] = ly;
        h[H_LABELS + n * 5 + 2] = static_cast<float>(l.kind);
        h[H_LABELS + n * 5 + 3] = static_cast<float>(l.value);
        h[H_LABELS + n * 5 + 4] = clampf(l.life / 0.5f, 0, 1);
        ++n;
    }
    h[H_LABEL_COUNT] = static_cast<float>(n);

    n = 0;
    for (const auto& z : zombies_) {
        if (n >= kMaxHudBars || z.state == ZState::Dead) continue;
        bool elite = z.mutations != 0;
        if (z.barTimer <= 0 && !(elite && distXZ(z.body.position, player_.position) < 22.0f)) continue;
        if (length(z.body.position - camPos_) > 40.0f) continue;
        float bx, by;
        if (!project(z.body.position + Vec3(0, zombieHeight(z) + 0.3f, 0), bx, by)) continue;
        const int o = H_BARS + n * kHudBarStride;
        h[o] = bx;
        h[o + 1] = by;
        h[o + 2] = clampf(z.health / z.maxHealth, 0, 1);
        h[o + 3] = z.barTimer > 0 ? clampf(z.barTimer / 0.4f, 0, 1) : 0.85f;
        h[o + 4] = static_cast<float>(static_cast<int>(z.kind) + (elite ? 100 : 0));
        ++n;
    }
    h[H_BAR_COUNT] = static_cast<float>(n);

    if (mode_ == Mode::Mission && island_) {
        h[H_ISLAND] = 1.0f;
        n = 0;
        int done = 0;
        int nearNest = -1;
        float nearDist = 1e9f;
        for (size_t i = 0; i < nests_.size() && n < kMaxHudObjectives; ++i, ++n) {
            const Nest& ne = nests_[i];
            const int o = H_OBJ + n * kHudObjStride;
            h[o] = ne.core.x;
            h[o + 1] = ne.core.z;
            h[o + 2] = ne.destroyed ? 2.0f : 1.0f;
            float ox = 0, oy = 0;
            bool on = !ne.destroyed && project(ne.core + Vec3(0, 1.5f, 0), ox, oy);
            h[o + 3] = ox;
            h[o + 4] = oy;
            h[o + 5] = on ? 1.0f : 0.0f;
            if (ne.destroyed) ++done;
            float d = distXZ(ne.core, player_.position);
            if (!ne.destroyed && d < 45.0f && d < nearDist) {
                nearDist = d;
                nearNest = static_cast<int>(i);
            }
        }
        if (n < kMaxHudObjectives && !survival()) {
            const int o = H_OBJ + n * kHudObjStride;
            h[o] = layout_.portalBase.x;
            h[o + 1] = layout_.portalBase.z;
            h[o + 2] = portalOpen_ ? 4.0f : 3.0f;
            h[o + 3] = sx;
            h[o + 4] = sy;
            h[o + 5] = onScreen ? 1.0f : 0.0f;
            ++n;
        }
        h[H_OBJ_COUNT] = static_cast<float>(n);
        h[H_NESTS_DONE] = static_cast<float>(done);
        h[H_NEST_TOTAL] = static_cast<float>(nests_.size());
        h[H_NEST_NEAR] = static_cast<float>(nearNest);
        h[H_NEST_HP] = nearNest >= 0 ? nests_[static_cast<size_t>(nearNest)].health / nests_[static_cast<size_t>(nearNest)].maxHealth : -1.0f;
        h[H_BURNING] = burning_;
        h[H_BLEEDING] = bleeding_;
        h[H_POISON] = poisoned_;
        h[H_SLOWED] = slowed_;
        h[H_BOOST] = damageBoost_;
        h[H_SUPPLY] = suppliesLeft_ <= 0 ? -1.0f : (supply_.active ? 0.0f : clampf(1.0f - supplyCooldown_ / supplyPeriod_, 0.0f, 1.0f));
        h[H_DISTANCE] = distance_;
        h[H_CHESTS] = static_cast<float>(chestsOpened_);
        h[H_CHEST_DIAMONDS] = static_cast<float>(chestDiamonds_);
        h[H_SHAKE] = shake_;
        // Cristais caídos ainda não recolhidos: posição e projeção (HUD, minimapa, mapa e tutorial).
        int cn = 0;
        for (const Pickup& p : pickups_) {
            if (p.type != 0 || p.taken || cn >= kMaxHudCrystals) continue;
            const int o = H_CRYSTAL_ITEMS + cn * kHudCrystalStride;
            float cx = 0, cy = 0;
            const bool on = project(p.position + Vec3(0, 0.6f, 0), cx, cy);
            h[o] = p.position.x;
            h[o + 1] = p.position.z;
            h[o + 2] = cx;
            h[o + 3] = cy;
            h[o + 4] = on ? 1.0f : 0.0f;
            ++cn;
        }
        h[H_CRYSTAL_COUNT] = static_cast<float>(cn);
        h[H_SUPPLY_X] = -1.0f;
        h[H_SUPPLY_Z] = -1.0f;
        for (const Pickup& p : pickups_) {
            if (p.type != 2) continue;
            h[H_SUPPLY_X] = p.position.x;
            h[H_SUPPLY_Z] = p.position.z;
        }
    }

    if (mode_ == Mode::Mission) publishContractHud(h);

    if (mode_ == Mode::Pvp) {
        const NetPlayer& me = net_.player(pvp_.localId);
        h[H_PVP] = 1.0f;
        h[H_PVP_LOCAL] = static_cast<float>(pvp_.localId);
        h[H_PVP_TIME] = net_.timeLeft();
        h[H_PVP_LIMIT] = static_cast<float>(net_.killLimit());
        h[H_PVP_ENDED] = net_.ended() ? 1.0f : 0.0f;
        h[H_PVP_WINNER] = static_cast<float>(net_.winner());
        h[H_PVP_RESPAWN] = dead_ ? std::max(0.0f, me.respawnTimer) : 0.0f;
        h[H_PVP_LINK] = net_.sinceSnapshot();
        h[H_KILLS] = static_cast<float>(me.kills);
        h[H_CRYSTAL_TOTAL] = 0;
        for (int i = 0; i < kMaxNetPlayers; ++i) {
            const NetPlayer& np = net_.player(i);
            const int b = H_PVP_PLAYERS + i * kHudPvpStride;
            h[b] = np.present ? 1.0f : 0.0f;
            h[b + 1] = np.active ? 1.0f : 0.0f;
            h[b + 2] = np.alive ? 1.0f : 0.0f;
            h[b + 3] = static_cast<float>(np.kills);
            h[b + 4] = static_cast<float>(np.deaths);
            if (i == pvp_.localId || !remotes_[i].present) continue;
            const RemotePlayer& r = remotes_[i];
            Vec3 head = r.renderPos + Vec3(0, 2.1f, 0);
            float px = 0, py = 0;
            bool visible = np.active && r.alive && length(head - camPos_) < 45.0f && project(head, px, py) && clearLineOfSight(camPos_, head);
            h[b + 5] = px;
            h[b + 6] = py;
            h[b + 7] = visible ? 1.0f : 0.0f;
            h[b + 8] = r.renderPos.x;
            h[b + 9] = r.renderPos.z;
            h[b + 10] = time_ - r.lastFire < 1.5f ? 1.0f : 0.0f;
        }
    }

    std::lock_guard<std::mutex> lock(outMutex_);
    hud_ = h;
}

void Game::copyHud(float* out, int count) {
    std::lock_guard<std::mutex> lock(outMutex_);
    std::copy(hud_.begin(), hud_.begin() + std::min<int>(count, H_SIZE), out);
}

int Game::drainEvents(int* out, int maxInts) {
    std::lock_guard<std::mutex> lock(outMutex_);
    int n = std::min<int>(maxInts / 4 * 4, static_cast<int>(events_.size()));
    std::copy(events_.begin(), events_.begin() + n, out);
    events_.erase(events_.begin(), events_.begin() + n);
    return n;
}

void Game::copyResult(float* out, int count) {
    std::lock_guard<std::mutex> lock(outMutex_);
    std::copy(result_.begin(), result_.begin() + std::min<int>(count, R_SIZE), out);
}

int Game::minimapSize() const { return island_ ? kMinimapSize : world_.width(); }

void Game::copyMinimap(uint32_t* argb, int count) {
    if (island_) {
        if (count >= kMinimapSize * kMinimapSize && minimap_.size() == static_cast<size_t>(kMinimapSize * kMinimapSize))
            std::copy(minimap_.begin(), minimap_.end(), argb);
        return;
    }
    int w = world_.width(), d = world_.depth();
    if (count < w * d) return;
    for (int z = 0; z < d; ++z) {
        for (int x = 0; x < w; ++x) {
            int top = world_.topBlockY(x, z);
            uint8_t b = top >= 0 ? world_.get(x, top, z) : B_AIR;
            uint32_t c = blockInfo(b).mapColor;
            float r = ((c >> 16) & 0xFF), g = ((c >> 8) & 0xFF), bl = (c & 0xFF);
            int left = world_.topBlockY(std::max(0, x - 1), std::max(0, z - 1));
            float shade = 0.82f + (top - layout_.seaLevel) * 0.012f + (top > left ? 0.12f : (top < left ? -0.12f : 0.0f));
            if (b == B_WATER) shade = 0.9f;
            if (b == B_PATH) { r = 196; g = 160; bl = 104; }
            shade = clampf(shade, 0.45f, 1.3f);
            uint32_t R = static_cast<uint32_t>(clampf(r * shade, 0, 255));
            uint32_t G = static_cast<uint32_t>(clampf(g * shade, 0, 255));
            uint32_t B = static_cast<uint32_t>(clampf(bl * shade, 0, 255));
            argb[z * w + x] = 0xFF000000u | (R << 16) | (G << 8) | B;
        }
    }
}

void Game::copyBigMap(uint32_t* argb, int count) {
    if (count >= kBigMapSize * kBigMapSize && bigMap_.size() == static_cast<size_t>(kBigMapSize * kBigMapSize))
        std::copy(bigMap_.begin(), bigMap_.end(), argb);
}

}  // namespace zz
