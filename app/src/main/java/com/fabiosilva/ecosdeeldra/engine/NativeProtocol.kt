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
    const val COUNT = 17
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
    const val BARS = 283
    const val PVP = 350
    const val PVP_LOCAL = 351
    const val PVP_TIME = 352
    const val PVP_LIMIT = 353
    const val PVP_ENDED = 354
    const val PVP_WINNER = 355
    const val PVP_RESPAWN = 356
    const val PVP_LINK = 357
    const val PVP_PLAYERS = 358 // (presente, ativo, vivo, abates, mortes, sx, sy, na tela, x, z, atirando) x 8
    const val PVP_STRIDE = 11
    const val SIZE = 450
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
    const val SIZE = 9
}
