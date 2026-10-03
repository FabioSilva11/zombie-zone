package com.fabiosilva.ecosdeeldra.engine

/** Espelha app/src/main/cpp/game/protocol.h. */
object Cfg {
    const val STAGE = 0
    const val SKIN = 1
    const val ZOMBIES = 2
    const val ZOMBIE_HP = 3
    const val ZOMBIE_DMG = 4
    const val ZOMBIE_SPEED = 5
    const val PLAYER_HP = 6
    const val MAGAZINE = 7
    const val POWER = 8
    const val CRYSTAL_MASK = 9
    const val CRYSTAL_TOTAL = 10
    const val SENSITIVITY = 11
    const val AIM_ASSIST = 12
    const val AUTO_FIRE = 13
    const val INVERT_Y = 14
    const val SEED = 15
    const val RELOAD_SPEED = 16
    const val BOOST_HEALTH = 17
    const val BOOST_DAMAGE = 18
    const val QUALITY = 19
    const val CINEMATIC = 20
    const val MODE = 21        // 0 campanha, 1 sobrevivência
    const val ARMOR = 22       // redução de dano 0..0.6
    const val REGEN = 23       // fração da vida por segundo
    const val SPEED = 24
    const val HEADSHOT = 25
    const val LUCK = 26
    const val EXPLOSIVES = 27
    const val COUNT = 28

    const val MODE_CAMPAIGN = 0
    const val MODE_SURVIVAL = 1
}

/** Inventário enviado ao motor: (id, quantidade) x 24 + espaço da arma + espaço do arremessável. */
const val INV_INT_COUNT = 24 * 2 + 2

object Hud {
    const val HEALTH = 0
    const val MAX_HEALTH = 1
    const val AMMO = 2
    const val MAG = 3
    const val RELOAD = 4
    const val ALIVE = 5
    const val TOTAL = 6
    const val PORTAL_OPEN = 7
    const val CRYSTALS = 8
    const val ELAPSED = 9
    const val TARGET_LOCKED = 10
    const val TARGET_HP = 11
    const val AIM = 12
    const val SPREAD = 13
    const val HITMARK = 14
    const val HEADSHOT_MARK = 15
    const val DAMAGE_FLASH = 16
    const val PLAYER_X = 17
    const val PLAYER_Z = 18
    const val CAM_YAW = 19
    const val PORTAL_X = 20
    const val PORTAL_Z = 21
    const val SPRINTING = 22
    const val JUMP_READY = 23
    const val KILLS = 24
    const val HEADSHOTS = 25
    const val DAMAGE_DIR = 26
    const val DAMAGE_DIR_T = 27
    const val PORTAL_SX = 28
    const val PORTAL_SY = 29
    const val PORTAL_ONSCREEN = 30
    const val FIRING = 31
    const val WORLD_SIZE = 32
    const val STATE = 33
    const val IN_WATER = 34
    const val FOV = 35
    const val FPS = 36
    const val CRYSTAL_TOTAL = 37
    const val COMBO = 38
    const val ENEMY_COUNT = 40
    const val ENEMIES = 41
    const val LABEL_COUNT = 161
    const val LABELS = 162
    const val BAR_COUNT = 282
    const val BARS = 283 // (sx, sy, fração, alfa, tipo + 100 se elite) x 16
    const val BAR_STRIDE = 5
    const val PVP = 400
    const val PVP_LOCAL = 401
    const val PVP_TIME = 402
    const val PVP_LIMIT = 403
    const val PVP_ENDED = 404
    const val PVP_WINNER = 405
    const val PVP_RESPAWN = 406
    const val PVP_LINK = 407
    const val PVP_PLAYERS = 408 // (presente, ativo, vivo, abates, mortes, sx, sy, na tela, x, z, atirando) x 8
    const val PVP_STRIDE = 11
    const val OBJ_COUNT = 500
    const val OBJ = 501 // (x, z, estado, sx, sy, na tela) x 4 — 1 ninho, 2 destruído, 3 portal fechado, 4 portal aberto
    const val OBJ_STRIDE = 6
    const val NESTS_DONE = 525
    const val NEST_TOTAL = 526
    const val NEST_HP = 527
    const val BURNING = 528
    const val BLEEDING = 529
    const val POISON = 530
    const val SLOWED = 531
    const val BOOST = 532
    const val SUPPLY = 533
    const val DISTANCE = 534
    const val CHESTS = 535
    const val CHEST_DIAMONDS = 536
    const val ISLAND = 537
    const val SHAKE = 538
    const val NEST_NEAR = 539
    const val SUPPLY_X = 540
    const val SUPPLY_Z = 541
    const val CROUCH = 542
    const val GRENADES = 543 // -1 = sem granadas (PvP)
    const val MELEE = 544    // recarga da faca 0..1, -1 = indisponível
    const val SHOULDER = 545
    const val SPRINT_LOCK = 546
    const val INV = 560        // (id, quantidade) x 24
    const val INV_ENABLED = 608
    const val WEAPON_SLOT = 609
    const val THROW_SLOT = 610
    const val WEAPON = 611
    const val ARMOR = 612
    const val ARMOR_MAX = 613
    const val ADRENALINE = 614
    const val THROW_AIM = 615
    const val THROW_DIST = 616
    const val SWAP = 617
    const val THROW_KIND = 618
    const val THROW_SX = 619
    const val THROW_SY = 620
    const val THROW_ONSCREEN = 621
    const val SURV = 630
    const val WAVE = 631
    const val WAVE_STATE = 632   // 0 preparando, 1 em andamento, 2 vencida
    const val WAVE_TIMER = 633
    const val WAVE_PROGRESS = 634
    const val WAVE_LEFT = 635
    const val WAVE_BIG = 636
    const val SURV_TIME = 637
    const val SCORE = 638
    const val FRONT_COUNT = 639
    const val FRONTS = 640
    const val SIZE = 660
}

/** Configuração da partida PvP (espelha PvpConfigIndex em protocol.h). */
object PvpCfg {
    const val LOCAL_ID = 0
    const val IS_HOST = 1
    const val SEED = 2
    const val KILL_LIMIT = 3
    const val TIME_LIMIT = 4
    const val SENSITIVITY = 5
    const val INVERT_Y = 6
    const val SKINS = 7
    const val COUNT = 15
}

object Ev {
    const val SFX = 1
    const val KILL = 2
    const val CRYSTAL = 3
    const val PORTAL_OPEN = 4
    const val VICTORY = 5
    const val DEFEAT = 6
    const val PLAYER_HURT = 7
    const val MISSION_READY = 8
    const val HEAL = 9
    const val PVP_KILL = 10
    const val PVP_LEFT = 11
    const val PVP_END = 12
    const val PVP_LOST = 13
    const val NEST_DESTROYED = 14
    const val CHEST = 15
    const val POI = 16
    const val SUPPLY = 17
    const val ELITE = 18
    const val STATUS = 19
    const val MINIMAP = 20
    const val NEW_KIND = 21
    const val ITEM = 22        // a = item, b = quantidade, c = 1 mochila cheia
    const val ITEM_USED = 23   // a = item, b = 1 usado / 0 recusado
    const val WEAPON = 24      // a = arma equipada
    const val WAVE = 25        // a = onda, b = grande horda, c = 0 preparando / 1 começou / 2 vencida
}

object Snd {
    const val SHOT = 0
    const val RELOAD = 1
    const val HIT = 2
    const val HEADSHOT = 3
    const val GROAN = 4
    const val ZOMBIE_DIE = 5
    const val HURT = 6
    const val PICKUP = 7
    const val PORTAL = 8
    const val JUMP = 9
    const val STEP = 10
    const val EMPTY = 11
    const val SPLASH = 12
    const val ZOMBIE_ATTACK = 13
    const val HEAL = 14
    const val THROW = 15
    const val FIRE = 16
    const val EXPLOSION = 17
    const val KNIFE = 18
    const val SCREAM = 19
    const val ROCK = 20
    const val CHEST = 21
    const val SPIT = 22
    const val RACK = 23
    const val PISTOL = 24
    const val SHOTGUN = 25
    const val SMG = 26
    const val SNIPER = 27
    const val LMG = 28
    const val FLAME = 29
    const val LAUNCHER = 30
    const val EQUIP = 31
    const val WAVE = 32
    const val COUNT = 33
}

object Res {
    const val ELAPSED = 0
    const val HEALTH = 1
    const val MAX_HEALTH = 2
    const val KILLS = 3
    const val HEADSHOTS = 4
    const val CRYSTAL_MASK = 5
    const val DAMAGE_TAKEN = 6
    const val SHOTS = 7
    const val HITS = 8
    const val CHESTS = 9
    const val CHEST_DIAMONDS = 10
    const val DISTANCE = 11
    const val NESTS = 12
    const val WAVE = 13
    const val SCORE = 14
    const val SIZE = 15
}

/** Tipos de ponto de interesse (CampKind em structures.h). */
object PoiKind {
    const val FIRE = 0
    const val RUINS = 1
    const val GRAVEYARD = 2
    const val DEPOT = 3
    const val FARM = 4
    const val TOWER = 5
    const val OUTPOST = 6
    const val NEST = 7
    const val PORTAL = 8
    const val HOUSE = 9
    const val VILLAGE = 10
    const val CHURCH = 11
    const val GAS = 12
    const val APARTMENT = 13
    const val BARN = 14
}
