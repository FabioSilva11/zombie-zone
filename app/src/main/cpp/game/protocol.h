// Protocolo compartilhado com o Kotlin (NativeProtocol.kt deve espelhar estes índices).
#pragma once

namespace zz::proto {

// Configuração da missão (FloatArray vindo do Kotlin).
enum ConfigIndex {
    C_STAGE = 0, C_SKIN, C_ZOMBIES, C_ZOMBIE_HP, C_ZOMBIE_DMG, C_ZOMBIE_SPEED, C_PLAYER_HP, C_MAGAZINE,
    C_POWER, C_CRYSTAL_MASK, C_CRYSTAL_TOTAL, C_SENSITIVITY, C_AIM_ASSIST, C_AUTO_FIRE, C_INVERT_Y, C_SEED,
    C_RELOAD_SPEED, C_BOOST_HEALTH, C_BOOST_DAMAGE, C_QUALITY, C_CINEMATIC,
    // Modo e melhorias
    C_MODE,          // 0 campanha, 1 sobrevivência por ondas
    C_ARMOR,         // redução de dano 0..0.6
    C_REGEN,         // fração da vida máxima regenerada por segundo
    C_SPEED,         // multiplicador de velocidade
    C_HEADSHOT,      // multiplicador extra de tiro na cabeça
    C_LUCK,          // multiplicador da chance de itens
    C_EXPLOSIVES,    // nível de explosivos (dano e pilha de granadas)
    C_COUNT
};

// Inventário (IntArray vindo do Kotlin): (id, quantidade) x 24 + espaço da arma + espaço do arremessável.
constexpr int kInvIntCount = 24 * 2 + 2;

// Estado do HUD (FloatArray lido pelo Kotlin).
enum HudIndex {
    H_HEALTH = 0, H_MAX_HEALTH, H_AMMO, H_MAG, H_RELOAD, H_ALIVE, H_TOTAL, H_PORTAL_OPEN, H_CRYSTALS,
    H_ELAPSED, H_TARGET_LOCKED, H_TARGET_HP, H_AIM, H_SPREAD, H_HITMARK, H_HEADSHOT_MARK, H_DAMAGE_FLASH,
    H_PLAYER_X, H_PLAYER_Z, H_CAM_YAW, H_PORTAL_X, H_PORTAL_Z, H_SPRINTING, H_JUMP_READY, H_KILLS,
    H_HEADSHOTS, H_DAMAGE_DIR, H_DAMAGE_DIR_T, H_PORTAL_SX, H_PORTAL_SY, H_PORTAL_ONSCREEN, H_FIRING,
    H_WORLD_SIZE, H_STATE, H_IN_WATER, H_FOV, H_FPS, H_CRYSTAL_TOTAL, H_COMBO, H_RESERVED,
    H_ENEMY_COUNT = 40, H_ENEMIES = 41,           // (x, z, alertado) x 40
    H_LABEL_COUNT = 161, H_LABELS = 162,          // (sx, sy, tipo, valor, alfa) x 24
    H_BAR_COUNT = 282, H_BARS = 283,              // (sx, sy, fração, alfa, elite) x 16
    // Bloco PvP
    H_PVP = 400, H_PVP_LOCAL, H_PVP_TIME, H_PVP_LIMIT, H_PVP_ENDED, H_PVP_WINNER, H_PVP_RESPAWN, H_PVP_LINK,
    H_PVP_PLAYERS = 408,                          // (presente, ativo, vivo, abates, mortes, sx, sy, na tela, x, z, atirando) x 8
    // Bloco da ilha (missões)
    H_OBJ_COUNT = 500, H_OBJ = 501,               // (x, z, estado, sx, sy, na tela) x 4 — estado: 1 ninho, 2 destruído, 3 portal fechado, 4 portal aberto
    H_NESTS_DONE = 525, H_NEST_TOTAL, H_NEST_HP, H_BURNING, H_BLEEDING, H_POISON, H_SLOWED, H_BOOST,
    H_SUPPLY, H_DISTANCE, H_CHESTS, H_CHEST_DIAMONDS, H_ISLAND, H_SHAKE, H_NEST_NEAR, H_SUPPLY_X, H_SUPPLY_Z,
    // Controles completos
    H_CROUCH = 542, H_GRENADES, H_MELEE, H_SHOULDER, H_SPRINT_LOCK,
    // Inventário: (id, quantidade) x 24
    H_INV = 560,
    H_INV_ENABLED = 608, H_WEAPON_SLOT, H_THROW_SLOT, H_WEAPON, H_ARMOR, H_ARMOR_MAX, H_ADRENALINE,
    H_THROW_AIM, H_THROW_DIST, H_SWAP, H_THROW_KIND, H_THROW_SX, H_THROW_SY, H_THROW_ONSCREEN,
    // Modo sobrevivência
    H_SURV = 630, H_WAVE, H_WAVE_STATE, H_WAVE_TIMER, H_WAVE_PROGRESS, H_WAVE_LEFT, H_WAVE_BIG, H_SURV_TIME,
    H_SCORE, H_FRONT_COUNT, H_FRONTS = 640,       // ângulo de cada frente de ataque x 3
    H_WAVE_FLAGS = 643,                            // posição (0..1) das grandes hordas no progresso desta onda
    H_SIZE = 660
};
constexpr int kHudPvpStride = 11;
constexpr int kHudBarStride = 5;
constexpr int kHudObjStride = 6;
constexpr int kMaxHudObjectives = 4;

// Configuração da partida PvP (FloatArray vindo do Kotlin).
enum PvpConfigIndex {
    P_LOCAL_ID = 0, P_IS_HOST, P_SEED, P_KILL_LIMIT, P_TIME_LIMIT, P_SENSITIVITY, P_INVERT_Y,
    P_SKINS = 7,  // 8 entradas: skin de cada id (255 = vaga vazia)
    P_COUNT = 15
};
constexpr int kMaxHudEnemies = 40;
constexpr int kMaxHudLabels = 24;
constexpr int kMaxHudBars = 16;

enum EventType {
    EV_SFX = 1,          // a = som, b = volume 0..100, c = pan -100..100
    EV_KILL = 2,         // a = tiro na cabeça, b = tipo do zumbi, c = combo
    EV_CRYSTAL = 3,      // a = índice do cristal
    EV_PORTAL_OPEN = 4,
    EV_VICTORY = 5,
    EV_DEFEAT = 6,
    EV_PLAYER_HURT = 7,  // a = dano
    EV_MISSION_READY = 8, // a = estilo do mapa (-1 = ilha)
    EV_HEAL = 9,
    EV_PVP_KILL = 10,     // a = autor, b = vítima, c = tiro na cabeça
    EV_PVP_LEFT = 11,     // a = jogador que saiu
    EV_PVP_END = 12,      // a = vencedor
    EV_PVP_LOST = 13,     // conexão com o anfitrião perdida
    EV_NEST_DESTROYED = 14, // a = índice do ninho
    EV_CHEST = 15,        // a = diamantes, b = 1 se veio granada
    EV_POI = 16,          // a = tipo do local descoberto
    EV_SUPPLY = 17,       // a = 0 lançado, 1 coletado
    EV_ELITE = 18,        // a = tipo, b = mutações
    EV_STATUS = 19,       // a = 1 queimando, 2 sangrando, 3 veneno, 4 lentidão
    EV_MINIMAP = 20,      // a = centro x, b = centro z
    EV_NEW_KIND = 21,     // a = tipo de zumbi visto pela primeira vez na fase
    EV_ITEM = 22,         // a = item, b = quantidade, c = 1 se a mochila estava cheia
    EV_ITEM_USED = 23,    // a = item, b = 1 usado / 0 recusado (vida cheia etc.)
    EV_WEAPON = 24,       // a = arma equipada
    EV_WAVE = 25,         // a = onda, b = 1 se grande horda, c = 0 preparando, 1 começou, 2 vencida
};

enum SoundId {
    SND_SHOT = 0, SND_RELOAD, SND_HIT, SND_HEADSHOT, SND_GROAN, SND_ZOMBIE_DIE, SND_HURT, SND_PICKUP,
    SND_PORTAL, SND_JUMP, SND_STEP, SND_EMPTY, SND_SPLASH, SND_ZOMBIE_ATTACK, SND_HEAL,
    SND_THROW, SND_FIRE, SND_EXPLOSION, SND_KNIFE, SND_SCREAM, SND_ROCK, SND_CHEST, SND_SPIT, SND_RACK,
    SND_PISTOL, SND_SHOTGUN, SND_SMG, SND_SNIPER, SND_LMG, SND_FLAME, SND_LAUNCHER, SND_EQUIP, SND_WAVE,
    SND_COUNT
};

// Resultado da missão.
enum ResultIndex {
    R_ELAPSED = 0, R_HEALTH, R_MAX_HEALTH, R_KILLS, R_HEADSHOTS, R_CRYSTAL_MASK, R_DAMAGE_TAKEN,
    R_SHOTS, R_HITS, R_CHESTS, R_CHEST_DIAMONDS, R_DISTANCE, R_NESTS, R_WAVE, R_SCORE, R_SIZE
};

}  // namespace zz::proto
