// Itens do inventário (estilo Minecraft): armas e consumíveis. Os ids espelham Items.kt.
#pragma once

#include <cstdint>

namespace zz {

enum ItemId : uint8_t {
    IT_NONE = 0,
    // Armas
    IT_RIFLE = 1, IT_PISTOL = 2, IT_SHOTGUN = 3, IT_SMG = 4, IT_SNIPER = 5, IT_LMG = 6, IT_FLAMER = 7, IT_LAUNCHER = 8,
    IT_WEAPON_LAST = 15,
    // Consumíveis
    IT_MEDKIT = 20, IT_BANDAGE = 21, IT_ANTIDOTE = 22, IT_ADRENALINE = 23, IT_FIRE_AMMO = 24, IT_ARMOR = 25,
    IT_GRENADE = 26, IT_MOLOTOV = 27, IT_SMOKE = 28,
    IT_LAST = 28,
};

constexpr int kInvSlots = 24;    // 6 na barra rápida + 18 na mochila
constexpr int kHotbarSlots = 6;

inline bool isWeaponItem(int id) { return id >= IT_RIFLE && id <= IT_LAUNCHER; }
inline bool isThrowableItem(int id) { return id == IT_GRENADE || id == IT_MOLOTOV || id == IT_SMOKE; }
inline bool isValidItem(int id) { return isWeaponItem(id) || (id >= IT_MEDKIT && id <= IT_LAST); }

// Quantidade máxima por espaço (as granadas crescem com a melhoria de explosivos).
int itemMaxStack(int id, int explosivesLevel);

enum class FireKind { Bullet, Flame, Launcher };

struct WeaponDef {
    FireKind kind;
    float damage;        // por projétil (por tique no lança-chamas)
    float interval;      // segundos entre disparos
    int magazine;        // pente base
    int pellets;         // escopeta: vários chumbos
    float spreadHip, spreadAim;
    float bloomPerShot;
    float reload;        // segundos
    float range;
    float recoil;
    float headshot;      // multiplicador de tiro na cabeça
    float aimFov;
    int pierce;          // zumbis atravessados pelo projétil
    float moveMul;       // velocidade ao andar com a arma
    int sound;
    float tracerR, tracerG, tracerB;
    float length;        // comprimento do modelo (posição do cano)
};

const WeaponDef& weaponDef(int id);

}  // namespace zz
