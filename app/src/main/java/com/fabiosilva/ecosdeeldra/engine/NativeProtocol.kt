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
    const val COUNT = 21
}

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
    const val SIZE = 560
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
    const val COUNT = 24
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
    const val SIZE = 13
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
}
