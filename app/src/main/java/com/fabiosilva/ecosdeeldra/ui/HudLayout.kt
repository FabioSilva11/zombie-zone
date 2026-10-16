package com.fabiosilva.ecosdeeldra.ui

/**
 * Controles do HUD que o jogador pode mover, redimensionar e esconder (todos podem ser ocultados;
 * o joystick oculto continua funcionando como joystick invisível onde o polegar tocar).
 */
enum class HudControl(val key: String, val label: String, val optional: Boolean, val baseDp: Float) {
    JOYSTICK("joy", "Movimento", true, 58f),
    FIRE("fire", "Atirar", true, 46f),
    FIRE_LEFT("fire2", "Atirar (2º botão)", true, 36f),
    AIM("aim", "Mirar", true, 30f),
    JUMP("jump", "Pular", true, 30f),
    RELOAD("reload", "Recarregar", true, 30f),
    CROUCH("crouch", "Agachar", true, 27f),
    SPRINT("sprint", "Correr", true, 27f),
    MELEE("melee", "Faca", true, 27f),
    GRENADE("grenade", "Granada", true, 27f),
    MAP("map", "Minimapa", true, 56f),
    SUPPLY("supply", "Suprimentos", true, 17f),
    PAUSE("pause", "Pausa", true, 20f),
    HOTBAR("hotbar", "Barra de itens", true, 22f),
    BAG("bag", "Mochila", true, 22f),
}

/** Centro do controle em frações da tela (0..1), escala e visibilidade. */
class ControlPos(var x: Float, var y: Float, var scale: Float = 1f, var visible: Boolean = true) {
    fun copy() = ControlPos(x, y, scale, visible)
}

class HudLayout(val controls: MutableMap<HudControl, ControlPos>) {
    operator fun get(c: HudControl): ControlPos = controls.getValue(c)

    fun copy() = HudLayout(controls.mapValuesTo(LinkedHashMap()) { it.value.copy() })

    fun encode(): String = controls.entries.joinToString(";") { (c, p) ->
        "${c.key}=%.4f,%.4f,%.3f,%d".format(java.util.Locale.US, p.x, p.y, p.scale, if (p.visible) 1 else 0)
    }

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.8f

        /** Layout padrão a partir de distâncias em dp das bordas (negativo = da borda direita/de baixo). */
        fun defaults(wDp: Float, hDp: Float, leftHanded: Boolean): HudLayout {
            val map = LinkedHashMap<HudControl, ControlPos>()
            fun put(c: HudControl, x: Float, y: Float, visible: Boolean = true) {
                var fx = (if (x < 0) wDp + x else x) / wDp
                val fy = (if (y < 0) hDp + y else y) / hDp
                if (leftHanded && c != HudControl.MAP && c != HudControl.SUPPLY && c != HudControl.PAUSE && c != HudControl.BAG) fx = 1f - fx
                map[c] = ControlPos(fx, fy, 1f, visible)
            }
            // Barra de itens no centro de baixo, entre o joystick e os botões (encolhe em telas estreitas).
            val leftEdge = 132f + 58f
            val rightEdge = wDp - 282f - 27f
            val slot = ((rightEdge - leftEdge - 16f) / 6f).coerceIn(30f, 44f)
            val hotbarX = ((leftEdge + rightEdge) / 2f / wDp).coerceIn(0.2f, 0.8f)
            map[HudControl.HOTBAR] = ControlPos(if (leftHanded) 1f - hotbarX else hotbarX, (hDp - 32f) / hDp, slot / 44f, true)
            put(HudControl.JOYSTICK, 132f, -116f)
            put(HudControl.FIRE, -104f, -96f)
            put(HudControl.FIRE_LEFT, 236f, -250f, visible = false)
            put(HudControl.AIM, -190f, -146f)
            put(HudControl.JUMP, -204f, -52f)
            put(HudControl.RELOAD, -86f, -200f)
            put(HudControl.CROUCH, -282f, -46f)
            put(HudControl.SPRINT, 62f, -232f)
            put(HudControl.MELEE, -264f, -124f)
            put(HudControl.GRENADE, -170f, -226f)
            put(HudControl.MAP, 84f, 70f)
            put(HudControl.SUPPLY, 84f, 152f)
            put(HudControl.PAUSE, -40f, 36f)
            put(HudControl.BAG, -40f, 96f)
            return HudLayout(map)
        }

        /** Lê o layout salvo; controles ausentes (versões antigas) vêm do padrão. */
        fun decode(text: String, fallback: HudLayout): HudLayout {
            val result = fallback.copy()
            if (text.isBlank()) return result
            for (entry in text.split(';')) {
                val parts = entry.split('=')
                if (parts.size != 2) continue
                val control = HudControl.entries.firstOrNull { it.key == parts[0] } ?: continue
                val v = parts[1].split(',')
                if (v.size < 4) continue
                val x = v[0].toFloatOrNull() ?: continue
                val y = v[1].toFloatOrNull() ?: continue
                val s = v[2].toFloatOrNull() ?: 1f
                result.controls[control] = ControlPos(
                    x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), s.coerceIn(MIN_SCALE, MAX_SCALE),
                    v[3] != "0",
                )
            }
            return result
        }
    }
}
