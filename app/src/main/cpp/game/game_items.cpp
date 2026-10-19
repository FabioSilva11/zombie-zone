// Inventário estilo Minecraft (barra rápida + mochila), armas, consumíveis, itens deixados pelos
// zumbis e a mira do arremesso com trajetória visível antes de soltar a granada/molotov.
#include <algorithm>
#include <utility>
#include <cmath>

#include "game.h"

namespace zz {

using namespace proto;

namespace {

constexpr float kThrowGravity = 22.0f;
constexpr float kHealCooldown = 8.0f;  // segundos entre kits médicos e bandagens

// clang-format off
const WeaponDef kWeapons[] = {
    //  tipo                dano  intervalo pente chumbos quadril mira   bloom   recarga alcance recuo  cabeça fov   atravessa mov   som            traçante              cano
    {FireKind::Bullet,     18.0f, 0.100f, 30, 1, 0.016f, 0.004f, 0.0040f, 1.6f, 120.0f, 0.012f, 2.0f, 0.68f, 0, 1.00f, SND_SHOT,     1.0f, 0.90f, 0.60f, 1.25f},  // fuzil
    {FireKind::Bullet,     30.0f, 0.200f, 12, 1, 0.012f, 0.003f, 0.0060f, 1.1f,  90.0f, 0.020f, 2.2f, 0.74f, 0, 1.08f, SND_PISTOL,   1.0f, 0.95f, 0.70f, 0.62f},  // pistola
    {FireKind::Bullet,     13.0f, 0.800f,  6, 9, 0.075f, 0.055f, 0.0000f, 2.3f,  32.0f, 0.060f, 1.5f, 0.80f, 0, 0.95f, SND_SHOTGUN,  1.0f, 0.80f, 0.50f, 1.32f},  // escopeta
    {FireKind::Bullet,     12.0f, 0.065f, 36, 1, 0.026f, 0.012f, 0.0030f, 1.3f,  80.0f, 0.008f, 1.8f, 0.72f, 0, 1.05f, SND_SMG,      1.0f, 0.90f, 0.50f, 0.78f},  // submetralhadora
    {FireKind::Bullet,    120.0f, 1.150f,  5, 1, 0.040f, 0.000f, 0.0000f, 2.5f, 220.0f, 0.080f, 2.5f, 0.30f, 3, 0.92f, SND_SNIPER,   0.6f, 0.90f, 1.00f, 1.58f},  // precisão
    {FireKind::Bullet,     17.0f, 0.085f, 90, 1, 0.030f, 0.012f, 0.0025f, 3.6f, 120.0f, 0.010f, 1.8f, 0.70f, 1, 0.82f, SND_LMG,      1.0f, 0.70f, 0.40f, 1.38f},  // metralhadora
    {FireKind::Flame,       9.0f, 0.070f, 80, 1, 0.000f, 0.000f, 0.0000f, 2.6f,   8.5f, 0.000f, 1.0f, 0.85f, 0, 0.95f, SND_FLAME,    1.0f, 0.60f, 0.20f, 0.95f},  // lança-chamas
    {FireKind::Launcher,  160.0f, 1.000f,  3, 1, 0.000f, 0.000f, 0.0000f, 2.8f,  70.0f, 0.060f, 1.0f, 0.70f, 0, 0.90f, SND_LAUNCHER, 1.0f, 0.70f, 0.30f, 1.00f},  // lança-granadas
};
// clang-format on

// Modelos das armas: caixas no espaço da mão (z = cano para a frente).
struct Part {
    Vec3 c, size;
    Vec4 color;
    float emissive;
    bool accent;  // muda de cor com a munição incendiária
};

const Vec4 kMetal{0.22f, 0.24f, 0.27f, 1}, kDark{0.1f, 0.1f, 0.11f, 1}, kWood{0.45f, 0.3f, 0.17f, 1}, kGrip{0.16f, 0.17f, 0.19f, 1};
const Vec4 kOlive{0.3f, 0.36f, 0.2f, 1}, kAccent{0.55f, 0.95f, 0.25f, 1};

const std::vector<Part>& weaponParts(int id) {
    static const std::vector<Part> rifle = {
        {{0, 0.05f, 0.25f}, {0.16f, 0.22f, 0.9f}, kMetal, 0, false},
        {{0, 0.1f, 0.9f}, {0.08f, 0.08f, 0.6f}, kDark, 0, false},
        {{0, 0.0f, -0.36f}, {0.14f, 0.2f, 0.42f}, kWood, 0, false},
        {{0, -0.2f, 0.32f}, {0.1f, 0.3f, 0.14f}, kGrip, 0, false},
        {{0, 0.21f, 0.2f}, {0.06f, 0.07f, 0.22f}, {0.12f, 0.12f, 0.13f, 1}, 0, false},
        {{0, 0.12f, 0.62f}, {0.13f, 0.06f, 0.1f}, kAccent, 0.5f, true},
    };
    static const std::vector<Part> pistol = {
        {{0, 0.08f, 0.32f}, {0.12f, 0.16f, 0.52f}, kMetal, 0, false},
        {{0, -0.12f, 0.16f}, {0.1f, 0.28f, 0.14f}, kGrip, 0, false},
        {{0, 0.17f, 0.5f}, {0.04f, 0.04f, 0.06f}, kAccent, 0.5f, true},
    };
    static const std::vector<Part> shotgun = {
        {{0.045f, 0.1f, 0.75f}, {0.08f, 0.08f, 1.1f}, kDark, 0, false},
        {{-0.045f, 0.1f, 0.75f}, {0.08f, 0.08f, 1.1f}, kDark, 0, false},
        {{0, -0.02f, 0.62f}, {0.16f, 0.12f, 0.36f}, kWood, 0, false},
        {{0, 0.02f, 0.12f}, {0.16f, 0.2f, 0.36f}, kMetal, 0, false},
        {{0, -0.04f, -0.36f}, {0.14f, 0.22f, 0.52f}, kWood, 0, false},
        {{0, 0.16f, 1.25f}, {0.05f, 0.04f, 0.05f}, kAccent, 0.5f, true},
    };
    static const std::vector<Part> smg = {
        {{0, 0.05f, 0.2f}, {0.14f, 0.2f, 0.6f}, kMetal, 0, false},
        {{0, 0.08f, 0.62f}, {0.07f, 0.07f, 0.32f}, kDark, 0, false},
        {{0, -0.26f, 0.28f}, {0.08f, 0.4f, 0.1f}, kDark, 0, false},
        {{0, -0.16f, 0.02f}, {0.09f, 0.24f, 0.12f}, kGrip, 0, false},
        {{0, 0.04f, -0.24f}, {0.05f, 0.1f, 0.36f}, kGrip, 0, false},
        {{0, 0.17f, 0.3f}, {0.05f, 0.04f, 0.12f}, kAccent, 0.5f, true},
    };
    static const std::vector<Part> sniper = {
        {{0, 0.05f, 0.3f}, {0.13f, 0.18f, 1.0f}, kMetal, 0, false},
        {{0, 0.08f, 1.12f}, {0.06f, 0.06f, 0.92f}, kDark, 0, false},
        {{0, 0.27f, 0.32f}, {0.1f, 0.1f, 0.58f}, kDark, 0, false},
        {{0, 0.27f, 0.62f}, {0.07f, 0.07f, 0.02f}, {0.4f, 0.9f, 1.0f, 1}, 0.8f, false},
        {{0, -0.02f, -0.46f}, {0.13f, 0.22f, 0.54f}, kWood, 0, false},
        {{0, -0.16f, 0.16f}, {0.09f, 0.24f, 0.12f}, kGrip, 0, false},
        {{0.09f, 0.1f, 0.12f}, {0.06f, 0.04f, 0.04f}, kAccent, 0.5f, true},
    };
    static const std::vector<Part> lmg = {
        {{0, 0.05f, 0.3f}, {0.2f, 0.26f, 1.0f}, kOlive, 0, false},
        {{0, 0.1f, 1.02f}, {0.1f, 0.1f, 0.72f}, kDark, 0, false},
        {{0.04f, -0.2f, 0.26f}, {0.26f, 0.24f, 0.26f}, {0.24f, 0.28f, 0.16f, 1}, 0, false},
        {{0, 0.25f, 0.4f}, {0.04f, 0.1f, 0.3f}, kDark, 0, false},
        {{0.08f, -0.18f, 1.05f}, {0.03f, 0.3f, 0.03f}, kDark, 0, false},
        {{-0.08f, -0.18f, 1.05f}, {0.03f, 0.3f, 0.03f}, kDark, 0, false},
        {{0, -0.02f, -0.36f}, {0.16f, 0.22f, 0.44f}, kGrip, 0, false},
        {{0, 0.2f, 0.75f}, {0.12f, 0.05f, 0.1f}, kAccent, 0.5f, true},
    };
    static const std::vector<Part> flamer = {
        {{0, -0.06f, -0.08f}, {0.24f, 0.24f, 0.52f}, {0.75f, 0.15f, 0.12f, 1}, 0, false},
        {{0, 0.06f, 0.5f}, {0.1f, 0.1f, 0.78f}, kDark, 0, false},
        {{0, -0.18f, 0.32f}, {0.09f, 0.24f, 0.12f}, kGrip, 0, false},
        {{0, 0.06f, 0.92f}, {0.07f, 0.07f, 0.07f}, {1.0f, 0.6f, 0.2f, 1}, 1.0f, false},
    };
    static const std::vector<Part> launcher = {
        {{0, 0.12f, 0.35f}, {0.24f, 0.24f, 1.25f}, kOlive, 0, false},
        {{0, -0.14f, 0.3f}, {0.09f, 0.26f, 0.12f}, kGrip, 0, false},
        {{0, 0.3f, 0.2f}, {0.05f, 0.1f, 0.08f}, kDark, 0, false},
        {{0, 0.12f, 0.98f}, {0.26f, 0.26f, 0.04f}, kAccent, 0.5f, true},
    };
    switch (id) {
        case IT_PISTOL: return pistol;
        case IT_SHOTGUN: return shotgun;
        case IT_SMG: return smg;
        case IT_SNIPER: return sniper;
        case IT_LMG: return lmg;
        case IT_FLAMER: return flamer;
        case IT_LAUNCHER: return launcher;
        default: return rifle;
    }
}

}  // namespace

int itemMaxStack(int id, int explosivesLevel) {
    switch (id) {
        case IT_MEDKIT: return 3;
        case IT_BANDAGE: return 5;
        case IT_ANTIDOTE: return 3;
        case IT_ADRENALINE: return 5;
        case IT_FIRE_AMMO: return 5;
        case IT_ARMOR: return 3;
        case IT_GRENADE:
        case IT_MOLOTOV:
        case IT_SMOKE: return 8 + explosivesLevel / 3;
        default: return isWeaponItem(id) ? 1 : 0;
    }
}

const WeaponDef& weaponDef(int id) {
    if (id < IT_RIFLE || id > IT_LAUNCHER) id = IT_RIFLE;
    return kWeapons[id - IT_RIFLE];
}

// ----------------------------------------------------------------------------- entrada (qualquer thread)

void Game::tapSlot(int slot) {
    std::lock_guard<std::mutex> lock(inputMutex_);
    if (input_.tapCount < 8) input_.taps[input_.tapCount++] = slot;
}

void Game::useSlot(int slot) {
    std::lock_guard<std::mutex> lock(inputMutex_);
    if (input_.useCount < 8) input_.uses[input_.useCount++] = slot;
}

void Game::setThrowAim(bool held, bool cancel) {
    std::lock_guard<std::mutex> lock(inputMutex_);
    if (held) {
        input_.throwAim = true;
        return;
    }
    input_.throwAim = false;
    if (cancel) ++input_.throwCancels;
    else ++input_.throwReleases;
}

// ----------------------------------------------------------------------------- inventário

void Game::setInventory(const int* data, int count) {
    int previous = weaponId();
    clips_[previous] = ammo_;
    for (int i = 0; i < kInvSlots; ++i) {
        int id = 2 * i + 1 < count ? data[2 * i] : 0;
        int n = 2 * i + 1 < count ? data[2 * i + 1] : 0;
        if (!isValidItem(id) || n <= 0) id = n = 0;
        inv_[static_cast<size_t>(i)] = {id, std::min(n, std::max(1, itemMaxStack(id, config_.explosives)))};
    }
    int ws = count > 2 * kInvSlots ? data[2 * kInvSlots] : -1;
    int ts = count > 2 * kInvSlots + 1 ? data[2 * kInvSlots + 1] : -1;
    throwSlot_ = (ts >= 0 && ts < kInvSlots && isThrowableItem(inv_[static_cast<size_t>(ts)].id)) ? ts : -1;
    weaponSlot_ = (ws >= 0 && ws < kInvSlots && isWeaponItem(inv_[static_cast<size_t>(ws)].id)) ? ws : -1;
    ensureWeapon();
    if (weaponId() != previous) loadWeaponAmmo();
}

// Munição do pente da arma em mãos (cada arma guarda a sua).
void Game::loadWeaponAmmo() {
    int w = weaponId();
    if (!clipsInit_[w]) {
        clipsInit_[w] = true;
        clips_[w] = magazineFor(w);
    }
    ammo_ = std::min(clips_[w], magazineFor(w));
    reloadTimer_ = 0;
    fireCooldown_ = 0;
    bloom_ = 0;
}

void Game::resetInventory() {
    for (auto& s : inv_) s = {};
    inv_[0] = {IT_RIFLE, 1};
    weaponSlot_ = 0;
    throwSlot_ = -1;
    for (int i = 0; i < 16; ++i) clipsInit_[i] = false;
    clipsInit_[IT_RIFLE] = true;
    clips_[IT_RIFLE] = ammo_;
    swapTimer_ = 0;
    armor_ = armorMax_ = 0;
    adrenaline_ = 0;
    healCooldown_ = 0;
    sinceHurt_ = 10;
    throwAiming_ = false;
    throwArc_.clear();
    throwAnimTime_ = -1.0f;
    throwAnimItem_ = 0;
}

int Game::weaponId() const {
    if (!inventoryEnabled() || weaponSlot_ < 0 || weaponSlot_ >= kInvSlots) return IT_RIFLE;
    int id = inv_[static_cast<size_t>(weaponSlot_)].id;
    return isWeaponItem(id) ? id : IT_RIFLE;
}

int Game::magazineFor(int weapon) const {
    float mul = std::max(1.0f, static_cast<float>(config_.magazine) / 30.0f);
    return std::max(1, static_cast<int>(std::lround(weaponDef(weapon).magazine * mul)));
}

// Garante uma arma válida na mão (a primeira da barra rápida, depois da mochila).
void Game::ensureWeapon() {
    if (weaponSlot_ >= 0 && weaponSlot_ < kInvSlots && isWeaponItem(inv_[static_cast<size_t>(weaponSlot_)].id)) return;
    weaponSlot_ = -1;
    for (int i = 0; i < kInvSlots; ++i)
        if (isWeaponItem(inv_[static_cast<size_t>(i)].id)) {
            weaponSlot_ = i;
            break;
        }
}

void Game::equipSlot(int slot, bool silent) {
    clips_[weaponId()] = ammo_;
    weaponSlot_ = slot;
    ensureWeapon();
    loadWeaponAmmo();
    int w = weaponId();
    if (!silent) {
        swapTimer_ = 0.35f;
        emitSound(SND_EQUIP, player_.position, 0.8f);
        emit(EV_WEAPON, w);
    }
}

int Game::throwableId() const {
    if (!inventoryEnabled()) return 0;
    if (throwSlot_ >= 0 && throwSlot_ < kInvSlots && isThrowableItem(inv_[static_cast<size_t>(throwSlot_)].id)) return inv_[static_cast<size_t>(throwSlot_)].id;
    for (const auto& s : inv_)
        if (isThrowableItem(s.id) && s.count > 0) return s.id;
    return 0;
}

int Game::countItem(int id) const {
    int n = 0;
    for (const auto& s : inv_)
        if (s.id == id) n += s.count;
    return n;
}

bool Game::takeItem(int id, int count) {
    if (countItem(id) < count) return false;
    // Consome primeiro do espaço escolhido, depois dos outros (de trás para frente).
    if (throwSlot_ >= 0 && inv_[static_cast<size_t>(throwSlot_)].id == id) {
        auto& s = inv_[static_cast<size_t>(throwSlot_)];
        int t = std::min(count, s.count);
        s.count -= t;
        count -= t;
        if (s.count <= 0) s = {};
    }
    for (int i = kInvSlots - 1; i >= 0 && count > 0; --i) {
        auto& s = inv_[static_cast<size_t>(i)];
        if (s.id != id) continue;
        int t = std::min(count, s.count);
        s.count -= t;
        count -= t;
        if (s.count <= 0) s = {};
    }
    return true;
}

int Game::addItem(int id, int count) {
    if (!inventoryEnabled() || !isValidItem(id) || count <= 0) return count;
    const int maxStack = itemMaxStack(id, config_.explosives);
    // Arma repetida não ocupa espaço: vira munição cheia.
    if (isWeaponItem(id) && countItem(id) > 0) {
        if (!ammoLimited()) {  // contrato de munição escassa: arma repetida não vira munição grátis
            clipsInit_[id] = false;
            if (weaponId() == id) ammo_ = magazineFor(id);
        }
        return 0;
    }
    // Completa pilhas existentes, depois ocupa espaços vazios (barra rápida primeiro, como no Minecraft).
    for (auto& s : inv_) {
        if (count <= 0) break;
        if (s.id != id || s.count >= maxStack) continue;
        int t = std::min(count, maxStack - s.count);
        s.count += t;
        count -= t;
    }
    for (auto& s : inv_) {
        if (count <= 0) break;
        if (s.id != 0) continue;
        int t = std::min(count, maxStack);
        s = {id, t};
        count -= t;
    }
    ensureWeapon();
    return count;
}

void Game::dropItem(const Vec3& at, int id, int count) {
    if (!inventoryEnabled() || count <= 0) return;
    Pickup p;
    p.type = 3;
    p.item = id;
    p.count = count;
    p.position = at;
    p.velocity = {rng_.range(-1.5f, 1.5f), 4.5f, rng_.range(-1.5f, 1.5f)};
    pickups_.push_back(p);
}

void Game::rollZombieDrop(const Zombie& z) {
    if (!inventoryEnabled()) return;
    // Itens de cura são raros: a maior parte dos drops é de ataque.
    float chance = (z.mutations ? 0.45f : 0.15f) * config_.luck;
    if (z.kind == ZombieKind::Brute) chance += 0.1f;
    if (!rng_.chance(std::min(1.0f, chance))) return;
    struct W { int id; float w; };
    static const W table[] = {
        {IT_BANDAGE, 12}, {IT_GRENADE, 26}, {IT_MEDKIT, 3}, {IT_MOLOTOV, 16}, {IT_SMOKE, 12},
        {IT_FIRE_AMMO, 12}, {IT_ADRENALINE, 9}, {IT_ANTIDOTE, 5}, {IT_ARMOR, 4},
    };
    float total = 0;
    for (const W& e : table) total += e.w;
    float r = rng_.range(0, total);
    int id = IT_BANDAGE;
    for (const W& e : table) {
        if (r < e.w) {
            id = e.id;
            break;
        }
        r -= e.w;
    }
    // Zumbis que carregam itens largam o que usavam.
    if (z.kind == ZombieKind::Molotov && rng_.chance(0.5f)) id = IT_MOLOTOV;
    dropItem(z.body.position + Vec3(0, 1.0f, 0), id, 1);
}

// Caixa de suprimentos (anúncio): consumíveis variados; o que não couber fica no chão.
void Game::giveSupplyBundle() {
    const int bundle[][2] = {{IT_BANDAGE, 1}, {IT_GRENADE, 2}, {IT_MOLOTOV, 1}, {IT_SMOKE, 1}, {IT_ADRENALINE, 1}, {IT_ARMOR, 1}};
    for (const auto& b : bundle) {
        int left = addItem(b[0], b[1]);
        if (left < b[1]) emit(EV_ITEM, b[0], b[1] - left, 0);
        if (left > 0) dropItem(player_.position + Vec3(0, 1.2f, 0), b[0], left);
    }
}

bool Game::collectPickup(Pickup& p) {
    int left = addItem(p.item, p.count);
    if (left == p.count) {
        // Mochila cheia: o item continua no chão.
        if (invFullTimer_ <= 0) {
            emit(EV_ITEM, p.item, 0, 1);
            invFullTimer_ = 3.0f;
        }
        p.blocked = 2.0f;
        return false;
    }
    emit(EV_ITEM, p.item, p.count - left, 0);
    emitSound(SND_PICKUP, p.position, 0.9f);
    spawnBurst(p.position, {1.0f, 0.9f, 0.5f, 1.0f}, 10, 2.5f, 0.1f, true, -2.0f);
    p.count = left;
    return left <= 0;
}

// ----------------------------------------------------------------------------- uso dos itens

void Game::handleSlotTap(int slot) {
    if (!inventoryEnabled() || slot < 0 || slot >= kInvSlots || dead_) return;
    int id = inv_[static_cast<size_t>(slot)].id;
    if (isWeaponItem(id)) {
        if (slot != weaponSlot_) {
            // Permuta, como no Free Fire: a arma do cartao vai para o espaco tocado e a tocada vai para o cartao.
            clips_[weaponId()] = ammo_;
            if (weaponSlot_ >= 0 && weaponSlot_ < kInvSlots && isWeaponItem(inv_[static_cast<size_t>(weaponSlot_)].id))
                std::swap(inv_[static_cast<size_t>(weaponSlot_)], inv_[static_cast<size_t>(slot)]);
            // O cartao continua no mesmo indice: depois da permuta ele mostra a arma tocada.
            ensureWeapon();
            loadWeaponAmmo();
            swapTimer_ = 0.35f;
            emitSound(SND_EQUIP, player_.position, 0.8f);
            emit(EV_WEAPON, weaponId());
        }
    } else if (isThrowableItem(id)) {
        throwSlot_ = slot;
        emitSound(SND_EQUIP, player_.position, 0.5f);
    } else if (id != 0) {
        useItemSlot(slot);
    }
}

void Game::useItemSlot(int slot) {
    if (slot < 0 || slot >= kInvSlots || dead_ || victory_) return;
    ItemStack& s = inv_[static_cast<size_t>(slot)];
    if (s.id == 0 || s.count <= 0) return;
    if (isWeaponItem(s.id) || isThrowableItem(s.id)) {
        handleSlotTap(slot);
        return;
    }
    // Curas seguidas têm intervalo: não dá para encher a vida no meio de um ataque.
    if ((s.id == IT_MEDKIT || s.id == IT_BANDAGE) && healCooldown_ > 0 && (health_ < maxHealth_ - 0.5f || bleeding_ > 0)) {
        emit(EV_ITEM_USED, s.id, 2, static_cast<int>(std::ceil(healCooldown_)));
        return;
    }
    bool used = true;
    float heal = 0;
    switch (s.id) {
        case IT_MEDKIT:
            if (health_ >= maxHealth_ - 0.5f && bleeding_ <= 0) used = false;
            else {
                heal = maxHealth_ * 0.4f;
                bleeding_ = 0;
                healCooldown_ = kHealCooldown;
            }
            break;
        case IT_BANDAGE:
            if (health_ >= maxHealth_ - 0.5f && bleeding_ <= 0) used = false;
            else {
                heal = maxHealth_ * 0.12f;
                bleeding_ = 0;
                healCooldown_ = kHealCooldown;
            }
            break;
        case IT_ANTIDOTE:
            // Só trata veneno, lentidão e fogo; não recupera vida.
            if (poisoned_ <= 0 && slowed_ <= 0 && burning_ <= 0) used = false;
            else poisoned_ = slowed_ = burning_ = 0;
            break;
        case IT_ADRENALINE:
            adrenaline_ = 20.0f;
            break;
        case IT_FIRE_AMMO:
            damageBoost_ = std::max(damageBoost_, 0.0f) + 45.0f;
            break;
        case IT_ARMOR:
            if (armor_ >= armorMax_ - 0.5f && armorMax_ > 0) used = false;
            else {
                armorMax_ = std::max(armorMax_, maxHealth_ * 0.6f);
                armor_ = armorMax_;
            }
            break;
        default:
            used = false;
            break;
    }
    emit(EV_ITEM_USED, s.id, used ? 1 : 0);
    if (!used) return;
    if (heal > 0) {
        health_ = std::min(maxHealth_, health_ + heal);
        labels_.push_back({player_.position + Vec3(0, 2.0f, 0), static_cast<int>(std::lround(heal)), 3, 0.9f});
        emitSound(SND_HEAL, player_.position, 1.0f);
        spawnBurst(player_.position + Vec3(0, 1.0f, 0), {1.0f, 0.4f, 0.4f, 1.0f}, 12, 2.5f, 0.12f, true, -2.0f);
    } else {
        emitSound(SND_PICKUP, player_.position, 0.9f);
        Vec4 c = s.id == IT_ADRENALINE ? Vec4(0.4f, 1.0f, 1.0f, 1.0f) : (s.id == IT_ARMOR ? Vec4(0.6f, 0.75f, 1.0f, 1.0f) : Vec4(1.0f, 0.55f, 0.2f, 1.0f));
        spawnBurst(player_.position + Vec3(0, 1.0f, 0), c, 16, 3.0f, 0.12f, true, -2.0f);
    }
    if (--s.count <= 0) s = {};
}

void Game::updateItemStatus(float dt) {
    adrenaline_ = std::max(0.0f, adrenaline_ - dt);
    healCooldown_ = std::max(0.0f, healCooldown_ - dt);
    swapTimer_ = std::max(0.0f, swapTimer_ - dt);
    invFullTimer_ = std::max(0.0f, invFullTimer_ - dt);
    sinceHurt_ += dt;
    // Regeneração (melhoria): começa alguns segundos depois do último dano.
    if (config_.regen > 0 && sinceHurt_ > 4.0f && !dead_ && !victory_ && health_ < maxHealth_)
        health_ = std::min(maxHealth_, health_ + maxHealth_ * config_.regen * dt);
}

// ----------------------------------------------------------------------------- arremesso com mira

bool Game::throwVelocity(Vec3& hand, Vec3& vel, float& gravity) const {
    hand = player_.position + Vec3(0, 1.6f - 0.45f * crouchBlend_, 0) + rightFromYaw(camYaw_) * 0.35f;
    // Cai onde a mira aponta (até 38 m); apontar para o céu joga o mais longe possível.
    Vec3 target = camPos_ + camForward_ * 38.0f;
    RayHit wh;
    if (world_.raycast(camPos_, camForward_, 38.0f, wh)) target = camPos_ + camForward_ * wh.distance;
    float t = clampf(distXZ(hand, target) / 17.0f, 0.35f, 1.5f);
    gravity = kThrowGravity;
    Vec3 d = target - hand;
    vel = {d.x / t, (d.y + 0.5f * gravity * t * t) / t, d.z / t};
    return true;
}

// Simula o voo com a mesma física dos projéteis para desenhar o arco e o ponto de queda.
void Game::simulateThrow() {
    throwArc_.clear();
    throwLandingValid_ = false;
    Vec3 pos, vel;
    float g;
    throwVelocity(pos, vel, g);
    const float dt = 1.0f / 60.0f;
    throwArc_.push_back(pos);
    for (int i = 0; i < 240; ++i) {
        vel.y -= g * dt;
        Vec3 next = pos + vel * dt;
        Vec3 seg = next - pos;
        float len = length(seg);
        RayHit wh;
        bool hit = len > 1e-4f && world_.raycast(pos, seg, len, wh);
        if (hit) next = pos + normalize(seg) * std::max(0.0f, wh.distance - 0.05f);
        bool hitZombie = false;
        for (const Zombie& z : zombies_) {
            if (z.state == ZState::Dead) continue;
            if (length(z.body.position + Vec3(0, 0.9f * z.scale, 0) - next) < 0.8f * z.scale + 0.3f) {
                hitZombie = true;
                break;
            }
        }
        pos = next;
        if (i % 3 == 2) throwArc_.push_back(pos);
        if (hit || hitZombie || world_.isWaterAt(pos) || pos.y < 0) {
            throwArc_.push_back(pos);
            throwLanding_ = pos;
            throwLandingValid_ = true;
            return;
        }
    }
    throwLanding_ = pos;
}

void Game::throwSelected() {
    int id = throwableId();
    if (id == 0 || grenadeCooldown_ > 0 || !takeItem(id, 1)) return;
    grenadeCooldown_ = 0.8f;
    Projectile p;
    p.kind = id == IT_MOLOTOV ? ProjectileKind::Molotov : (id == IT_SMOKE ? ProjectileKind::Smoke : ProjectileKind::Grenade);
    p.friendly = true;
    throwVelocity(p.pos, p.vel, p.gravity);
    p.target = throwLandingValid_ ? throwLanding_ : p.pos + p.vel;
    p.life = 4.0f;
    float boom = 1.0f + config_.explosives * 0.08f;
    p.damage = id == IT_SMOKE ? 0.0f : (id == IT_MOLOTOV ? 30.0f : 200.0f) * config_.power * config_.boostDamage * boom;
    p.radius = id == IT_MOLOTOV ? 3.2f : 5.0f;
    projectiles_.push_back(p);
    throwAnimTime_ = 0.0f;
    throwAnimItem_ = id;
    playerYaw_ = camYaw_;
    emitSound(SND_THROW, p.pos, 0.8f);
    // Sem mais deste item: escolhe outro arremessável.
    if (throwSlot_ >= 0 && inv_[static_cast<size_t>(throwSlot_)].id != id) throwSlot_ = -1;
}

void Game::updateThrowAim(float dt, const InputState& in) {
    bool can = inventoryEnabled() && !dead_ && !victory_ && throwableId() != 0;
    bool thrown = false;
    if (in.throwAim && can) {
        throwAiming_ = true;
        throwAimTime_ += dt;
        simulateThrow();
    } else if (throwAiming_) {
        // Soltou o botão: arremessa (ou cancela se arrastou até o X).
        if (in.throwCancels == 0 && can) {
            simulateThrow();
            throwSelected();
            thrown = true;
        }
        throwAiming_ = false;
        throwAimTime_ = 0;
        throwArc_.clear();
    }
    // Toque rápido (apertou e soltou no mesmo quadro) ou o botão antigo de granada.
    if (!thrown && !throwAiming_ && can && in.throwCancels == 0 && (in.throwReleases > 0 || in.grenades > 0) && !in.throwAim) {
        simulateThrow();
        throwSelected();
        throwArc_.clear();
    }
}

// ----------------------------------------------------------------------------- armas especiais

void Game::fireFlame() {
    const WeaponDef& w = weaponDef(IT_FLAMER);
    --ammo_;
    ++shots_;
    float power = config_.power * config_.boostDamage * (damageBoost_ > 0 ? 1.5f : 1.0f);
    Vec3 origin = muzzlePos_;
    Vec3 dir = camForward_;
    // Partículas do jato de fogo.
    for (int i = 0; i < 4 && particles_.size() < 880; ++i) {
        FxParticle p;
        p.position = origin + dir * rng_.range(0.0f, 0.4f);
        Vec3 jitter(rng_.range(-0.12f, 0.12f), rng_.range(-0.08f, 0.12f), rng_.range(-0.12f, 0.12f));
        p.velocity = normalize(dir + jitter) * rng_.range(9.0f, 13.0f);
        p.color = rng_.chance(0.3f) ? Vec4(1.0f, 0.9f, 0.5f, 1.0f) : Vec4(1.0f, rng_.range(0.35f, 0.6f), 0.12f, 1.0f);
        p.size = rng_.range(0.25f, 0.5f);
        p.life = p.maxLife = rng_.range(0.45f, 0.7f);
        p.gravity = -2.0f;
        p.additive = true;
        particles_.push_back(p);
    }
    muzzleTimer_ = 0.06f;
    flameSoundTimer_ -= w.interval;
    if (flameSoundTimer_ <= 0) {
        flameSoundTimer_ = 0.22f;
        emitSound(SND_FLAME, origin, 0.75f);
    }
    bool any = false;
    for (Zombie& z : zombies_) {
        if (z.state == ZState::Dead) continue;
        Vec3 c = z.body.position + Vec3(0, zombieHeight(z) * 0.5f, 0);
        Vec3 to = c - origin;
        float d = length(to);
        if (d > w.range + 0.4f * z.scale || d < 0.05f) continue;
        if (dot(to / d, dir) < 0.86f && d > 1.2f) continue;
        if (!clearLineOfSight(origin, c)) continue;
        z.burn = std::max(z.burn, 2.5f);
        z.burnDps = std::max(z.burnDps, 16.0f * power);
        damageZombie(z, w.damage * power, false, dir, c);
        any = true;
    }
    for (size_t i = 0; i < nests_.size(); ++i) {
        if (nests_[i].destroyed || length(nests_[i].core - origin) > w.range) continue;
        damageNest(static_cast<int>(i), w.damage * power * 0.8f, nests_[i].core);
        any = true;
    }
    if (any) {
        ++hits_;
        hitMark_ = 0.12f;
        headshotMark_ = false;
    }
    if (rng_.chance(0.3f)) alertNear(player_.position, 22.0f);
}

void Game::fireLauncher() {
    const WeaponDef& w = weaponDef(IT_LAUNCHER);
    --ammo_;
    ++shots_;
    Projectile p;
    p.kind = ProjectileKind::Grenade;
    p.friendly = true;
    p.pos = muzzlePos_;
    // Disparo quase reto rumo à mira, com leve queda.
    Vec3 target = camPos_ + camForward_ * w.range;
    RayHit wh;
    if (world_.raycast(camPos_, camForward_, w.range, wh)) target = camPos_ + camForward_ * wh.distance;
    Vec3 dir = normalize(target - p.pos);
    p.vel = dir * 42.0f;
    p.gravity = 4.0f;
    p.target = target;
    p.life = 3.0f;
    p.radius = 4.5f;
    float boom = 1.0f + config_.explosives * 0.08f;
    p.damage = w.damage * config_.power * config_.boostDamage * (damageBoost_ > 0 ? 1.5f : 1.0f) * boom;
    projectiles_.push_back(p);
    muzzleTimer_ = 0.08f;
    weaponKick_ = std::max(weaponKick_, 0.9f);
    recoil_ += w.recoil;
    camPitch_ += w.recoil * 0.4f;
    spawnBurst(p.pos, {0.8f, 0.8f, 0.8f, 0.6f}, 8, 2.0f, 0.3f, false, -1.0f);
    emitSound(SND_LAUNCHER, p.pos, 0.9f);
    alertNear(player_.position, 35.0f);
}

// ----------------------------------------------------------------------------- modelo da arma

void Game::addWeaponModel(const CharacterDraw& draw, int armR, int weapon, Vec3* muzzle) {
    // Ao trocar de arma ela desce e volta.
    float dip = swapTimer_ > 0 ? std::sin(clampf(swapTimer_ / 0.35f, 0.0f, 1.0f) * kPi) * 0.6f : 0.0f;
    Mat4 frame = draw.world * draw.nodes[static_cast<size_t>(armR)] * Mat4::translation({0.0f, -0.9f, 0.0f}) * Mat4::rotationX(kPi * 0.5f + dip);
    addWeaponParts(frame, weapon, muzzle);
}

void Game::addWeaponParts(const Mat4& frame, int weapon, Vec3* muzzle) {
    if (weapon == IT_RIFLE && ak47Loaded_) {
        addImportedAk47(frame, muzzle);
        return;
    }
    for (const Part& p : weaponParts(weapon)) {
        BoxDraw box;
        box.world = frame * Mat4::translation(p.c) * Mat4::scale(p.size);
        box.color = p.color;
        box.emissive = p.emissive;
        if (p.accent && damageBoost_ > 0) box.color = {1.0f, 0.5f, 0.15f, 1};
        scene_.boxes.push_back(box);
    }
    if (muzzle) *muzzle = frame.transformPoint({0, 0.1f, weaponDef(weapon).length});
}

void Game::addImportedAk47(const Mat4& frame, Vec3* muzzle) {
    // O GLB original tem o cano no eixo -Z; o espaço de armas do jogo usa +Z.
    const Mat4 modelFrame = frame * Mat4::rotationY(kPi);
    if (muzzle) *muzzle = modelFrame.transformPoint({-0.01814f, 0.10661f, 0.64570f});
    if (scene_.characterCount >= scene_.characters.size()) scene_.characters.emplace_back();
    CharacterDraw& draw = scene_.characters[scene_.characterCount++];
    draw.asset = &ak47Asset_;
    draw.world = modelFrame;
    Pose bind;
    ak47Asset_.model.bindPose(bind);
    ak47Asset_.model.globals(bind, draw.nodes);
    draw.tint = {1, 1, 1};
    draw.flash = 0;
    draw.alpha = 1;
}

}  // namespace zz
