#include "game.h"

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
constexpr float kFireInterval = 0.1f;
constexpr int kSkinCount = 18;

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

}  // namespace

Game::Game() {
    hud_.fill(0);
    result_.fill(0);
}

void Game::onSurfaceCreated() {
    renderer_.onSurfaceCreated();
    renderer_.setTheme(theme_);
}

void Game::onSurfaceChanged(int width, int height) { renderer_.onSurfaceChanged(width, height); }

CharacterAsset* Game::character(int skin) {
    skin = std::max(0, std::min(kSkinCount - 1, skin));
    auto it = characters_.find(skin);
    if (it != characters_.end()) return it->second.get();
    auto asset = std::make_unique<CharacterAsset>();
    char path[64];
    std::snprintf(path, sizeof(path), "characters/character-%c.glb", 'a' + skin);
    std::vector<uint8_t> data;
    if (!platform::readAsset(path, data) || !asset->model.loadGlb(data)) {
        LOGE("Falha ao carregar %s", path);
        return nullptr;
    }
    asset->texturePath = "characters/" + asset->model.textureUri();
    if (!platform::decodeAssetImage(asset->texturePath, 512, asset->image)) LOGW("Textura ausente: %s", asset->texturePath.c_str());
    CharacterAsset* raw = asset.get();
    characters_[skin] = std::move(asset);
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

void Game::enterLobby(int skin, int screen) {
    stopPvp();
    bool regenerate = mode_ != Mode::Lobby;
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
    lobbyEmoteTimer_ = 4.0f;
}

void Game::setPreviewSkin(int skin) {
    playerAsset_ = character(skin);
    playerAnim_ = Animator();
    if (playerAsset_) {
        playerAnim_.play(playerAsset_->model.clipIndex("emote-yes"), false, 0);
        lobbyEmoteTimer_ = 6.0f;
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

// ----------------------------------------------------------------------------- quadro

void Game::frame(double now) {
    float dt = lastTime_ < 0 ? 1.0f / 60.0f : static_cast<float>(now - lastTime_);
    lastTime_ = now;
    if (dt > 0) fps_ = lerpf(fps_, 1.0f / std::max(dt, 1e-3f), 0.05f);
    dt = clampf(dt, 0.0f, 0.05f);
    time_ += dt;
    static float fpsLogTimer = 0;
    fpsLogTimer += dt;
    if (fpsLogTimer > 5.0f) {
        fpsLogTimer = 0;
        LOGI("FPS %.1f (modo %d, chunks %zu, zumbis %zu, partículas %zu)", fps_, static_cast<int>(mode_), world_.chunks().size(), zombies_.size(), particles_.size());
    }

    if (mode_ == Mode::Lobby) updateLobby(dt);
    else if (mode_ == Mode::Pvp) updatePvp(dt);
    else if (mode_ == Mode::Mission) {
        if (!paused_) updateMission(dt);
        else {
            std::lock_guard<std::mutex> lock(inputMutex_);
            input_.lookX = input_.lookY = 0;
            input_.jumps = input_.reloads = 0;
        }
    }
    if (!world_.streaming()) world_.rebuildDirty(6);
    if (mode_ == Mode::None) {
        glClearColor(0.03f, 0.05f, 0.08f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        return;
    }
    buildScene();
    renderer_.render(scene_);
    if (mode_ == Mode::Mission || mode_ == Mode::Pvp) publishHud();
}

void Game::updateLobby(float dt) {
    InputState in;
    {
        std::lock_guard<std::mutex> lock(inputMutex_);
        in = input_;
        input_.previewRotate = 0;
        input_.lookX = input_.lookY = 0;
        input_.jumps = input_.reloads = 0;
        input_.supplyRequests = 0;
    }
    previewYaw_ += in.previewRotate * 0.012f;
    if (lobbyScreen_ == 0) previewYaw_ = lerpAngle(previewYaw_, 0.0f, damp(1.5f, dt));
    if (playerAsset_) {
        playerAnim_.update(dt);
        lobbyEmoteTimer_ -= dt;
        if (playerAnim_.finished(playerAsset_->model)) playerAnim_.play(playerAsset_->model.clipIndex("idle"), true, 0.25f);
        if (lobbyEmoteTimer_ <= 0) {
            const char* emotes[] = {"emote-yes", "interact-right", "pick-up"};
            playerAnim_.play(playerAsset_->model.clipIndex(emotes[rng_.rangeInt(0, 2)]), false, 0.2f);
            lobbyEmoteTimer_ = rng_.range(7.0f, 11.0f);
        }
    }
    updateEffects(dt);

    Vec3 c = layout_.start;
    Vec3 target, eye;
    if (lobbyScreen_ == 1) {
        target = c + Vec3(0.95f, 1.0f, 0);
        eye = target + Vec3(0.0f, 0.35f, 3.5f);
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
    {
        std::lock_guard<std::mutex> lock(inputMutex_);
        in = input_;
        input_.lookX = input_.lookY = 0;
        input_.jumps = input_.reloads = 0;
        input_.previewRotate = 0;
        input_.supplyRequests = 0;
    }
    aimToggle_ = in.aim;
    if (!victory_) missionTime_ += dt;

    streamWorld(2, 3);
    updateCamera(dt, in);
    updatePlayer(dt, in);
    if (!dead_ && !victory_) updateWeapon(dt, in);
    else {
        firingNow_ = false;
        targetLocked_ = false;
    }
    updateStatus(dt);

    // Recalcula o campo de fluxo quando o jogador muda de coluna (ou periodicamente).
    navTimer_ -= dt;
    int px = static_cast<int>(std::floor(player_.position.x)), pz = static_cast<int>(std::floor(player_.position.z));
    if (navTimer_ <= 0 && (px != navX_ || pz != navZ_)) {
        nav_.compute(world_, px, pz, 60);
        navX_ = px;
        navZ_ = pz;
        navTimer_ = 0.3f;
    }

    updateSpawner(dt);
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
    if (minimapTimer_ <= 0 || std::fabs(player_.position.x - minimapCX_) > 26 || std::fabs(player_.position.z - minimapCZ_) > 26) rebuildMinimap();

    int needed = (1 << static_cast<int>(nests_.size())) - 1;
    if (!portalOpen_ && needed > 0 && (crystalsCollectedMask_ & needed) == needed) {
        portalOpen_ = true;
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
            emit(EV_VICTORY);
        }
    }
    if (dead_) finishResult();
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
}

// ----------------------------------------------------------------------------- jogador e câmera

void Game::updateCamera(float dt, const InputState& in) {
    float k = 0.0058f * config_.sensitivity * (1.0f - 0.42f * aimBlend_);
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
    aimBlend_ = lerpf(aimBlend_, (aimToggle_ && !dead_) ? 1.0f : 0.0f, damp(12.0f, dt));
    recoil_ = lerpf(recoil_, 0.0f, damp(10.0f, dt));
    shake_ = std::max(0.0f, shake_ - dt * 1.8f);
    fov_ = lerpf(1.0f, 0.68f, aimBlend_);
    float wantDistance = dead_ ? 5.5f : lerpf(4.9f, 2.4f, aimBlend_);
    float shoulder = lerpf(1.0f, 0.78f, aimBlend_);
    float pitch = camPitch_ + recoil_;
    float shakeAmp = shake_ * shake_ * 0.12f;
    float yaw = camYaw_ + std::sin(time_ * 47.0f) * shakeAmp * 0.6f;
    pitch += std::sin(time_ * 39.0f + 1.3f) * shakeAmp * 0.5f;

    Vec3 body = player_.position + Vec3(0, visualStep_, 0);
    Vec3 head = body + Vec3(0, 1.72f, 0);
    Vec3 right = rightFromYaw(camYaw_);
    RayHit hit;
    if (world_.raycast(head, right, shoulder + 0.25f, hit)) shoulder = std::max(0.0f, hit.distance - 0.25f);
    Vec3 pivot = head + right * shoulder;
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

void Game::updatePlayer(float dt, const InputState& in) {
    invulnerable_ = std::max(0.0f, invulnerable_ - dt);
    damageFlash_ = std::max(0.0f, damageFlash_ - dt * 1.8f);
    damageDirTimer_ = std::max(0.0f, damageDirTimer_ - dt);
    float mx = dead_ || victory_ ? 0 : in.moveX, my = dead_ || victory_ ? 0 : in.moveY;
    float mag = std::min(1.0f, std::sqrt(mx * mx + my * my));
    sprinting_ = mag > 0.9f && aimBlend_ < 0.3f && !in.fire && !firingNow_ && slowed_ <= 0;
    float speed = kPlayerSpeed * (sprinting_ ? 1.45f : 1.0f) * lerpf(1.0f, 0.6f, aimBlend_) * (player_.inWater ? 0.6f : 1.0f) * (slowed_ > 0 ? 0.55f : 1.0f);
    Vec3 fwd = forwardFromYaw(camYaw_), right = rightFromYaw(camYaw_);
    Vec3 wish = right * mx + fwd * my;
    if (lengthXZ(wish) > 1e-3f) wish = normalize(wish) * (mag * speed);
    // Mar aberto: uma corrente suave leva o jogador de volta à ilha.
    if (island_ && player_.inWater) {
        Vec3 toCenter(kIslandCenter - player_.position.x, 0, kIslandCenter - player_.position.z);
        float d = lengthXZ(toCenter);
        if (d > kIslandRadius + 380.0f) wish += normalize(toCenter) * clampf((d - kIslandRadius - 380.0f) * 0.05f, 0.0f, 3.5f);
    }
    float accel = player_.onGround || player_.inWater ? 16.0f : 5.0f;
    player_.velocity.x = lerpf(player_.velocity.x, wish.x, damp(accel, dt));
    player_.velocity.z = lerpf(player_.velocity.z, wish.z, damp(accel, dt));

    if (in.jumps > 0 && !dead_) {
        if (player_.onGround && !player_.inWater) {
            player_.velocity.y = kJumpVelocity;
            emitSound(SND_JUMP, player_.position, 0.5f);
        } else if (player_.inWater) {
            player_.velocity.y = 5.0f;
        }
    }
    if (player_.inWater) {
        player_.velocity.y -= 9.0f * dt;
        if (world_.isWaterAt(player_.position + Vec3(0, 1.3f, 0))) player_.velocity.y += 17.0f * dt;
        player_.velocity.y = clampf(player_.velocity.y, -3.0f, 5.0f);
    } else {
        player_.velocity.y = std::max(-40.0f, player_.velocity.y - kGravity * dt);
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
    }
    if (!dead_) distance_ += distXZ(before, player_.position);

    float horizontal = lengthXZ(player_.velocity);
    if (player_.onGround && horizontal > 1.0f && !player_.inWater) {
        stepTimer_ -= dt * horizontal / 4.2f;
        if (stepTimer_ <= 0) {
            stepTimer_ = 0.42f;
            emitSound(SND_STEP, player_.position, 0.35f);
        }
    }
    bool combat = aimBlend_ > 0.3f || in.fire || firingNow_;
    if (combat) playerYaw_ = camYaw_;
    else if (horizontal > 0.6f) playerYaw_ = yawFromDirection(player_.velocity.x, player_.velocity.z);
    playerModelYaw_ = lerpAngle(playerModelYaw_, playerYaw_, damp(combat ? 22.0f : 12.0f, dt));

    if (playerAsset_) {
        const CharacterModel& m = playerAsset_->model;
        if (dead_) {
            int die = m.clipIndex("die");
            if (playerAnim_.clip() != die) playerAnim_.play(die, false, 0.1f);
        } else if (horizontal > 5.4f) {
            playerAnim_.play(m.clipIndex("sprint"), true, 0.15f, horizontal / 6.0f);
        } else if (horizontal > 0.5f) {
            playerAnim_.play(m.clipIndex("walk"), true, 0.15f, std::max(0.6f, horizontal / 3.6f));
        } else {
            playerAnim_.play(m.clipIndex("idle"), true, 0.2f);
        }
        playerAnim_.update(dt);
    }
}

void Game::updateWeapon(float dt, const InputState& in) {
    fireCooldown_ = std::max(-kFireInterval, fireCooldown_ - dt);
    bloom_ = std::max(0.0f, bloom_ - dt * 0.09f);
    muzzleTimer_ = std::max(0.0f, muzzleTimer_ - dt);
    hitMark_ = std::max(0.0f, hitMark_ - dt);
    comboTimer_ = std::max(0.0f, comboTimer_ - dt);
    if (comboTimer_ <= 0) combo_ = 0;

    if (in.reloads > 0 && ammo_ < config_.magazine && reloadTimer_ <= 0) {
        reloadTimer_ = reloadDuration_;
        emitSound(SND_RELOAD, player_.position, 0.8f);
    }
    if (reloadTimer_ > 0) {
        reloadTimer_ -= dt;
        if (reloadTimer_ <= 0) {
            reloadTimer_ = 0;
            ammo_ = config_.magazine;
        }
    }

    // Alvo da mira assistida: zumbi vivo mais próximo do centro da tela.
    assistTarget_ = -1;
    assistAngle_ = 10;
    float bestAngle = 10;
    int best = -1;
    for (size_t i = 0; i < zombies_.size(); ++i) {
        const Zombie& z = zombies_[i];
        if (z.state == ZState::Dead) continue;
        Vec3 chest = z.body.position + Vec3(0, zombieHeight(z) * 0.55f, 0);
        Vec3 v = chest - camPos_;
        float dist = length(v);
        if (dist > 60 || dist < 0.5f) continue;
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
        if (world_.lineOfSight(camPos_, chest)) {
            assistTarget_ = best;
            assistAngle_ = std::max(0.0f, bestAngle);
        }
    }
    float cone = config_.aimAssist ? lerpf(0.075f, 0.045f, aimBlend_) : 0.0f;
    if (assistAngle_ > std::max(cone, 0.03f)) assistTarget_ = -1;
    targetLocked_ = assistTarget_ >= 0;
    targetHp_ = targetLocked_ ? zombies_[static_cast<size_t>(assistTarget_)].health / zombies_[static_cast<size_t>(assistTarget_)].maxHealth : 0;

    bool autoTrigger = false;
    if (config_.autoFire && assistTarget_ >= 0) {
        const Zombie& z = zombies_[static_cast<size_t>(assistTarget_)];
        autoTrigger = assistAngle_ < 0.035f && distXZ(z.body.position, player_.position) < 42.0f;
    }
    bool trigger = in.fire || autoTrigger;
    if (trigger && ammo_ == 0 && reloadTimer_ <= 0) {
        reloadTimer_ = reloadDuration_;
        emitSound(SND_RELOAD, player_.position, 0.8f);
    }
    firingNow_ = trigger && ammo_ > 0 && reloadTimer_ <= 0;
    int fired = 0;
    while (firingNow_ && fireCooldown_ <= 0 && fired < 3 && ammo_ > 0) {
        fireShot();
        fireCooldown_ += kFireInterval;
        ++fired;
    }
}

void Game::fireShot() {
    --ammo_;
    ++shots_;
    Vec3 f = camForward_;
    Vec3 right = normalize(cross(f, Vec3(0, 1, 0)));
    Vec3 up = cross(right, f);
    float moving = std::min(1.0f, lengthXZ(player_.velocity) / kPlayerSpeed);
    float spread = lerpf(0.016f, 0.004f, aimBlend_) + bloom_ + moving * 0.008f;
    float ang = rng_.range(0, kTwoPi), rad = std::sqrt(rng_.uniform()) * spread;
    Vec3 dir = normalize(f + right * (std::cos(ang) * rad) + up * (std::sin(ang) * rad));
    if (config_.aimAssist && assistTarget_ >= 0) {
        const Zombie& z = zombies_[static_cast<size_t>(assistTarget_)];
        Vec3 chest = z.body.position + Vec3(0, zombieHeight(z) * 0.6f, 0);
        dir = normalize(lerp(dir, normalize(chest - camPos_), 0.6f));
    }

    RayHit wh;
    float maxT = 120.0f;
    bool worldHit = world_.raycast(camPos_, dir, maxT, wh);
    if (worldHit) maxT = wh.distance;
    float minT = camDistance_ * 0.7f;
    int bestZombie = -1;
    bool head = false;
    float bestT = maxT;
    for (size_t i = 0; i < zombies_.size(); ++i) {
        const Zombie& z = zombies_[i];
        if (z.state == ZState::Dead) continue;
        float h = zombieHeight(z), w = 0.36f * z.scale * z.bloat;
        Vec3 p = z.body.position;
        float t;
        if (rayBox(camPos_, dir, p + Vec3(-w * 0.85f, h * 0.68f, -w * 0.85f), p + Vec3(w * 0.85f, h + 0.05f, w * 0.85f), t) && t > minT && t < bestT) {
            bestT = t; bestZombie = static_cast<int>(i); head = true;
        }
        if (rayBox(camPos_, dir, p + Vec3(-w, 0, -w), p + Vec3(w, h * 0.68f, w), t) && t > minT && t < bestT) {
            bestT = t; bestZombie = static_cast<int>(i); head = false;
        }
    }
    // Núcleos dos ninhos de infecção.
    int nestHit = -1;
    float nestT = 0;
    if (mode_ == Mode::Mission && damageNestRay(camPos_, dir, bestT, nestT, nestHit) && nestT > minT) {
        bestT = nestT;
        bestZombie = -1;
    } else {
        nestHit = -1;
    }
    // PvP: adversários (posições interpoladas que o jogador vê na tela).
    int bestRemote = -1;
    if (mode_ == Mode::Pvp) {
        for (int i = 0; i < kMaxNetPlayers; ++i) {
            const RemotePlayer& r = remotes_[i];
            if (!r.present || !r.alive || !net_.player(i).active) continue;
            const float h = 1.8f, w = 0.36f;
            Vec3 p = r.renderPos;
            float t;
            if (rayBox(camPos_, dir, p + Vec3(-w * 0.85f, h * 0.68f, -w * 0.85f), p + Vec3(w * 0.85f, h + 0.05f, w * 0.85f), t) && t > minT && t < bestT) {
                bestT = t; bestRemote = i; head = true;
            }
            if (rayBox(camPos_, dir, p + Vec3(-w, 0, -w), p + Vec3(w, h * 0.68f, w), t) && t > minT && t < bestT) {
                bestT = t; bestRemote = i; head = false;
            }
        }
    }
    Vec3 hitPoint = camPos_ + dir * bestT;
    if (mode_ == Mode::Pvp) net_.localFire(muzzlePos_, hitPoint);
    if (bestRemote >= 0) {
        int dmg = head ? 34 : 16;
        net_.localHit(bestRemote, dmg, head);
        ++hits_;
        hitMark_ = 0.18f;
        headshotMark_ = head;
        labels_.push_back({hitPoint, dmg, head ? 2 : 1, 0.8f});
        spawnBurst(hitPoint, {0.75f, 0.12f, 0.12f, 1.0f}, head ? 9 : 5, 2.2f, 0.1f, false, 14.0f);
        emitSound(head ? SND_HEADSHOT : SND_HIT, hitPoint, head ? 0.9f : 0.6f);
        bestZombie = -1;
        worldHit = false;
    }
    bool boosted = damageBoost_ > 0;
    Tracer tracer{muzzlePos_, hitPoint, 0.06f};
    if (boosted) tracer.color = {1.0f, 0.55f, 0.2f, 1.0f};
    tracers_.push_back(tracer);
    muzzleTimer_ = 0.05f;
    for (int i = 0; i < 2; ++i) {
        FxParticle p;
        p.position = muzzlePos_ + dir * (0.08f * i);
        p.velocity = dir * 1.5f;
        p.color = boosted ? Vec4(1.0f, 0.6f, 0.25f, 1.0f) : Vec4(1.0f, 0.82f, 0.4f, 1.0f);
        p.size = 0.32f - i * 0.1f;
        p.life = p.maxLife = 0.05f;
        p.additive = true;
        particles_.push_back(p);
    }
    float power = config_.power * config_.boostDamage * (boosted ? 1.5f : 1.0f);
    if (nestHit >= 0) {
        ++hits_;
        hitMark_ = 0.18f;
        headshotMark_ = false;
        damageNest(nestHit, 18.0f * power, hitPoint);
    } else if (bestZombie >= 0) {
        Zombie& z = zombies_[static_cast<size_t>(bestZombie)];
        float dmg = 18.0f * power * (head ? 2.0f : 1.0f);
        ++hits_;
        hitMark_ = 0.18f;
        headshotMark_ = head;
        damageZombie(z, dmg, head, dir, hitPoint);
    } else if (worldHit) {
        const BlockInfo& info = blockInfo(wh.block);
        Vec3 c = info.side < layerColors_.size() ? layerColors_[info.side] : Vec3(0.5f, 0.5f, 0.5f);
        spawnBurst(hitPoint + Vec3(static_cast<float>(wh.nx), static_cast<float>(wh.ny), static_cast<float>(wh.nz)) * 0.06f,
                   {c.x, c.y, c.z, 1.0f}, 5, 2.6f, 0.08f, false, 14.0f);
    }
    bloom_ = std::min(0.045f, bloom_ + 0.004f * (1.0f - aimBlend_ * 0.5f));
    recoil_ += 0.012f;
    camPitch_ += 0.0035f;
    emitSound(SND_SHOT, muzzlePos_, 0.75f);
    alertNear(player_.position, 30.0f);
}

void Game::damagePlayer(float amount, const Vec3& from) {
    if (invulnerable_ > 0 || dead_ || victory_) return;
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
        Vec3 toPlayer = player_.position + Vec3(0, 0.8f, 0) - p.position;
        float dist = length(toPlayer);
        bool magnet = p.type != 2 && p.age > 0.6f && dist < 3.2f && !dead_;
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
        if (dist < (p.type == 2 ? 1.9f : 1.25f) && !dead_) {
            p.taken = true;
            if (p.type == 0) {
                crystalsCollectedMask_ |= 1 << p.crystalIndex;
                emit(EV_CRYSTAL, p.crystalIndex);
                emitSound(SND_PICKUP, p.position, 1.0f);
                spawnBurst(p.position, {0.4f, 1.0f, 0.95f, 1.0f}, 16, 3.0f, 0.12f, true, -2.0f);
            } else if (p.type == 1) {
                float heal = maxHealth_ * 0.3f;
                health_ = std::min(maxHealth_, health_ + heal);
                labels_.push_back({p.position, static_cast<int>(std::lround(heal)), 3, 0.9f});
                emit(EV_HEAL, static_cast<int>(heal));
                emitSound(SND_HEAL, p.position, 1.0f);
                spawnBurst(p.position, {1.0f, 0.4f, 0.4f, 1.0f}, 12, 2.5f, 0.12f, true, -2.0f);
            } else {
                // Caixa de suprimentos (anúncio): munição, vida e munição incendiária.
                float heal = maxHealth_ * 0.5f;
                health_ = std::min(maxHealth_, health_ + heal);
                ammo_ = config_.magazine;
                reloadTimer_ = 0;
                damageBoost_ = 45.0f;
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

void Game::updateEffects(float dt) {
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

void Game::addCharacter(CharacterAsset* asset, const Vec3& pos, float yaw, float scale, const Pose& pose, const Vec3& tint, float flash, const Vec3& scaleXYZ) {
    if (!asset) return;
    if (scene_.characterCount >= scene_.characters.size()) scene_.characters.emplace_back();
    CharacterDraw& draw = scene_.characters[scene_.characterCount++];
    draw.asset = asset;
    float s = scale * 1.8f / std::max(0.1f, asset->model.bindHeight());
    draw.world = Mat4::translation(pos) * Mat4::rotationY(modelRotationFromYaw(yaw)) * Mat4::scale({s * scaleXYZ.x, s * scaleXYZ.y, s * scaleXYZ.z}) *
                 Mat4::translation({0, -asset->model.bindMinY(), 0});
    asset->model.globals(pose, draw.nodes);
    draw.tint = tint;
    draw.flash = flash;
    draw.flashColor = {1.0f, 0.25f, 0.2f};
    draw.alpha = 1.0f;
}

void Game::posePlayer(Pose& pose, bool armed) {
    if (!playerAsset_ || !armed || dead_) return;
    bool playing = mode_ == Mode::Mission || mode_ == Mode::Pvp;
    poseArmed(pose, playerAsset_->model, playing ? camPitch_ : -0.3f, playing);
}

void Game::poseArmed(Pose& pose, const CharacterModel& m, float pitch, bool moveHead) {
    int armR = m.nodeIndex("arm-right"), armL = m.nodeIndex("arm-left"), head = m.nodeIndex("head");
    float armPitch = pitch * 0.85f;
    Quat aimX = Quat::axisAngle({1, 0, 0}, -kPi * 0.5f - armPitch);
    if (armR >= 0) pose.r[armR] = Quat::axisAngle({0, 1, 0}, 0.08f) * aimX;
    if (armL >= 0) pose.r[armL] = Quat::axisAngle({0, 1, 0}, -0.5f) * Quat::axisAngle({1, 0, 0}, -kPi * 0.5f - armPitch + 0.1f);
    if (head >= 0 && moveHead) pose.r[head] = Quat::axisAngle({1, 0, 0}, -pitch * 0.5f);
}

void Game::addRifle(const CharacterDraw& draw, int armR, Vec3* muzzle) {
    Mat4 frame = draw.world * draw.nodes[static_cast<size_t>(armR)] * Mat4::translation({-0.2f, -0.9f, 0.0f}) * Mat4::rotationX(kPi * 0.5f);
    struct Part { Vec3 c, size; Vec4 color; float emissive; };
    static const Part parts[] = {
        {{0, 0.05f, 0.25f}, {0.16f, 0.22f, 0.9f}, {0.22f, 0.24f, 0.27f, 1}, 0.0f},
        {{0, 0.1f, 0.9f}, {0.08f, 0.08f, 0.6f}, {0.1f, 0.1f, 0.11f, 1}, 0.0f},
        {{0, 0.0f, -0.36f}, {0.14f, 0.2f, 0.42f}, {0.45f, 0.3f, 0.17f, 1}, 0.0f},
        {{0, -0.2f, 0.32f}, {0.1f, 0.3f, 0.14f}, {0.16f, 0.17f, 0.19f, 1}, 0.0f},
        {{0, 0.21f, 0.2f}, {0.06f, 0.07f, 0.22f}, {0.12f, 0.12f, 0.13f, 1}, 0.0f},
        {{0, 0.12f, 0.62f}, {0.13f, 0.06f, 0.1f}, {0.55f, 0.95f, 0.25f, 1}, 0.5f},
    };
    for (const Part& p : parts) {
        BoxDraw box;
        box.world = frame * Mat4::translation(p.c) * Mat4::scale(p.size);
        box.color = p.color;
        box.emissive = p.emissive;
        if (damageBoost_ > 0 && &p == &parts[5]) box.color = {1.0f, 0.5f, 0.15f, 1};
        scene_.boxes.push_back(box);
    }
    if (muzzle) *muzzle = frame.transformPoint({0, 0.1f, 1.25f});
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
    if (theme_.sky.night > 0.5f && mode_ != Mode::Lobby) s.lights.push_back({player_.position + Vec3(0, 2.2f, 0), 11.0f, Vec3(0.95f, 0.9f, 0.8f)});
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
        bool armed = mode_ == Mode::Mission || mode_ == Mode::Pvp || lobbyScreen_ == 0;
        posePlayer(scratchPose_, armed);
        Vec3 pos = player_.position + Vec3(0, visualStep_, 0);
        float yaw = mode_ == Mode::Lobby ? kPi + previewYaw_ : playerModelYaw_;
        float flash = invulnerable_ > 0 && damageFlash_ > 0.6f ? 0.45f : 0.0f;
        addCharacter(playerAsset_, pos, yaw, 1.0f, scratchPose_, {1, 1, 1}, flash);
        CharacterDraw& draw = scene_.characters[scene_.characterCount - 1];
        s.shadows.push_back({pos, 0.55f, 0.32f});
        int armR = playerAsset_->model.nodeIndex("arm-right");
        if (armed && armR >= 0 && !dead_) addRifle(draw, armR, &muzzlePos_);
    }

    // Jogadores remotos (PvP).
    if (mode_ == Mode::Pvp) {
        for (int i = 0; i < kMaxNetPlayers; ++i) {
            RemotePlayer& r = remotes_[i];
            if (!r.present || !r.asset || !net_.player(i).active) continue;
            if (length(r.renderPos - camPos_) > s.drawDistance) continue;
            r.anim.evaluate(r.asset->model, scratchPose_);
            if (r.alive) poseArmed(scratchPose_, r.asset->model, r.renderPitch, true);
            float flash = net_.player(i).invulnerable > 0 && std::fmod(time_, 0.3f) < 0.15f ? 0.25f : 0.0f;
            addCharacter(r.asset, r.renderPos, r.renderYaw, 1.0f, scratchPose_, {1, 1, 1}, flash);
            CharacterDraw& draw = scene_.characters[scene_.characterCount - 1];
            int armR = r.asset->model.nodeIndex("arm-right");
            if (r.alive && armR >= 0) {
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
        core.color = n.hitFlash > 0 ? Vec4(1.0f, 0.9f, 0.9f, 1) : Vec4(0.62f, 0.16f, 0.78f, 1);
        core.emissive = 0.8f;
        s.boxes.push_back(core);
        BoxDraw shell;
        shell.world = Mat4::translation(n.core) * Mat4::rotationY(-time_ * 0.5f) * Mat4::rotationZ(0.7f) * Mat4::scale({0.7f, 0.7f, 0.7f});
        shell.color = {0.35f, 0.95f, 0.35f, 1};
        shell.emissive = 0.9f;
        s.boxes.push_back(shell);
        s.particles.push_back({n.core, {0.75f, 0.3f, 1.0f, 0.55f}, 3.2f * pulse, true});
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

    s.portalVisible = mode_ == Mode::Mission;
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
    pickups_.clear();
    particles_.clear();
    labels_.clear();
    tracers_.clear();
    projectiles_.clear();
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
    stopPvp();
    pvp_ = c;
    mode_ = Mode::Pvp;
    paused_ = false;
    config_ = MissionConfig{};
    config_.stage = 0;
    config_.skin = c.skins[c.localId] == 255 ? 0 : c.skins[c.localId];
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
        r.asset = character(c.skins[i]);
        if (r.asset) r.anim.play(r.asset->model.clipIndex("idle"), true, 0);
        if (!layout_.spawnPoints.empty()) r.renderPos = layout_.spawnPoints[static_cast<size_t>(i) % layout_.spawnPoints.size()];
    }
    NetConfig nc;
    nc.host = c.host;
    nc.localId = c.localId;
    nc.hostIp = c.hostIp;
    nc.killLimit = c.killLimit;
    nc.timeLimit = c.timeLimit;
    for (int i = 0; i < kMaxNetPlayers; ++i) nc.skins[i] = c.skins[i];
    bool ok = net_.start(nc, layout_.spawnPoints);
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
    {
        std::lock_guard<std::mutex> lock(inputMutex_);
        in = input_;
        input_.lookX = input_.lookY = 0;
        input_.jumps = input_.reloads = 0;
        input_.previewRotate = 0;
        input_.supplyRequests = 0;
    }
    aimToggle_ = in.aim;
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
            if (teleport) r.sampleCount = 0;
            if (r.sampleCount == 8) {
                for (int k = 1; k < 8; ++k) r.samples[k - 1] = r.samples[k];
                r.sampleCount = 7;
            }
            RemoteSample& s = r.samples[r.sampleCount++];
            s.time = time_;
            s.pos = np.pos;
            s.yaw = np.yaw;
            s.pitch = np.pitch;
            s.speed = np.speed;
            s.flags = np.flags;
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
            if (r.speed > 5.4f) r.anim.play(m.clipIndex("sprint"), true, 0.15f, r.speed / 6.0f);
            else if (r.speed > 0.5f) r.anim.play(m.clipIndex("walk"), true, 0.15f, std::max(0.6f, r.speed / 3.6f));
            else r.anim.play(m.clipIndex("idle"), true, 0.2f);
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
    h[H_MAG] = static_cast<float>(config_.magazine);
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
    h[H_AIM] = aimBlend_;
    h[H_SPREAD] = lerpf(0.016f, 0.004f, aimBlend_) + bloom_ + std::min(1.0f, lengthXZ(player_.velocity) / kPlayerSpeed) * 0.008f;
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
    h[H_FPS] = fps_;
    h[H_CRYSTAL_TOTAL] = static_cast<float>(nests_.empty() ? config_.crystalTotal : static_cast<int>(nests_.size()));
    h[H_COMBO] = static_cast<float>(combo_);

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
        if (n < kMaxHudObjectives) {
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
        h[H_SUPPLY_X] = -1.0f;
        h[H_SUPPLY_Z] = -1.0f;
        for (const Pickup& p : pickups_) {
            if (p.type != 2) continue;
            h[H_SUPPLY_X] = p.position.x;
            h[H_SUPPLY_Z] = p.position.z;
        }
    }

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
            bool visible = np.active && r.alive && length(head - camPos_) < 45.0f && project(head, px, py) && world_.lineOfSight(camPos_, head);
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
