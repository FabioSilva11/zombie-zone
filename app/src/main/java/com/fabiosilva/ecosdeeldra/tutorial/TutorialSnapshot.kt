package com.fabiosilva.ecosdeeldra.tutorial

import com.fabiosilva.ecosdeeldra.engine.Hud
import com.fabiosilva.ecosdeeldra.engine.Res

/** Fatos do quadro (HUD + resultado) que a máquina do tutorial consome. */
data class TutorialSnapshot(
    val valid: Boolean,               // campanha na ilha (não sobrevivência/PvP)
    val dead: Boolean,
    val victory: Boolean,
    val yaw: Float,                   // Hud.CAM_YAW
    val distanceWalked: Float,        // Hud.DISTANCE
    val shots: Int,                   // Res.SHOTS
    val objective: Objective,
    val nearNest: Int,                // Hud.NEST_NEAR (ninho vivo a < 45 m) ou -1
    val nearNestHp: Float,            // Hud.NEST_HP ou -1
    val nearestNestIndex: Int,        // ninho vivo mais próximo em H_OBJ (qualquer distância) ou -1
    val nearestNestDist: Float,       // metros; Float.MAX_VALUE se não houver
) {
    companion object {
        fun from(hud: FloatArray, res: FloatArray): TutorialSnapshot {
            val obj = Objective.from(hud)
            var best = Float.MAX_VALUE
            var bestIdx = -1
            val n = hud[Hud.OBJ_COUNT].toInt().coerceIn(0, 4)
            for (i in 0 until n) {
                val o = Hud.OBJ + i * Hud.OBJ_STRIDE
                if (hud[o + 2].toInt() != 1) continue          // 1 = ninho vivo
                val d = kotlin.math.hypot(hud[o] - hud[Hud.PLAYER_X], hud[o + 1] - hud[Hud.PLAYER_Z])
                if (d < best) { best = d; bestIdx = i }
            }
            return TutorialSnapshot(
                valid = obj.active,
                dead = hud[Hud.STATE].toInt() == 1,
                victory = hud[Hud.STATE].toInt() == 2,
                yaw = hud[Hud.CAM_YAW], distanceWalked = hud[Hud.DISTANCE], shots = res[Res.SHOTS].toInt(),
                objective = obj, nearNest = hud[Hud.NEST_NEAR].toInt(), nearNestHp = hud[Hud.NEST_HP],
                nearestNestIndex = bestIdx, nearestNestDist = best,
            )
        }
    }
}
