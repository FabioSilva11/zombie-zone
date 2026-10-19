package com.fabiosilva.ecosdeeldra.ui

/**
 * Controles do HUD que o jogador pode mover, redimensionar e esconder (todos podem ser ocultados;
 * o joystick oculto continua funcionando como joystick invisível onde o polegar tocar).
 */
enum class HudControl(val key: String, val label: String, val optional: Boolean, val baseDp: Float) {
    // baseDp e o RAIO em dp na escala 1: o diametro desenhado e 2 * baseDp * ControlPos.scale
    // (minimapa 96, joystick 100, ATIRAR 80, MIRAR/PULAR/RECARREGAR 60, demais botoes 54).
    JOYSTICK("joy", "Movimento", true, 50f),
    FIRE("fire", "Atirar", true, 40f),
    FIRE_LEFT("fire2", "Atirar (2º botão)", true, 36f),
    AIM("aim", "Mirar", true, 30f),
    JUMP("jump", "Pular", true, 30f),
    RELOAD("reload", "Recarregar", true, 30f),
    CROUCH("crouch", "Agachar", true, 27f),
    SPRINT("sprint", "Correr", true, 27f),
    MELEE("melee", "Faca", true, 27f),
    GRENADE("grenade", "Granada", true, 27f),
    MEDKIT("medkit", "Kit médico", true, 27f),
    WEAPON("weaponpanel", "Painel da arma", true, 40f),
    EMOTE("emote", "Emoji", true, 22f),
    MAP("map", "Minimapa", true, 48f),
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

        /** Margem mínima entre a parte de baixo dos controles/barra de itens e a borda da tela. */
        const val BOTTOM_MARGIN_DP = 16f
        private const val REF_W_DP = 860f
        private const val REF_H_DP = 400f
        private const val MIN_COMBAT_SCALE = 0.75f

        /** Escala dos controles de combate: 1 em telas de referência e menor em landscape estreito. */
        fun combatScale(wDp: Float, hDp: Float): Float =
            minOf(wDp / REF_W_DP, hDp / REF_H_DP).coerceIn(MIN_COMBAT_SCALE, 1f)

        /**
         * Layout padrão responsivo (medidas em dp). Os controles de combate encolhem em telas estreitas
         * (640 x 360 dp cabe com folga) e as distâncias entre eles encolhem menos, para as áreas de toque
         * não se encostarem. Canhoto espelha só os controles de combate; minimapa, suprimentos, pausa e
         * mochila ficam onde estão.
         */
        fun defaults(wDp: Float, hDp: Float, leftHanded: Boolean): HudLayout {
            val map = LinkedHashMap<HudControl, ControlPos>()
            val u = combatScale(wDp, hDp)
            val v = (u + 1f) / 2f                 // espaçamento: encolhe menos que o tamanho
            val bot = hDp - BOTTOM_MARGIN_DP      // linha de base: nada visível passa daqui
            fun put(c: HudControl, xDp: Float, yDp: Float, scale: Float = u, visible: Boolean = true, mirror: Boolean = true) {
                var fx = xDp / wDp
                if (leftHanded && mirror) fx = 1f - fx
                map[c] = ControlPos(fx, yDp / hDp, scale, visible)
            }
            // Controles do polegar direito: a = distância até a borda direita, b = altura sobre a linha de base.
            fun right(c: HudControl, a: Float, b: Float, visible: Boolean = true) = put(c, wDp - a * v, bot - b * v, u, visible)

            // Barra de itens no centro de baixo, entre o joystick e os botões (encolhe em telas estreitas).
            val leftEdge = 62f * v + 50f * u + 8f
            val rightEdge = wDp - (222f * v + 27f * u * 1.2f + 4f + 8f)
            val slot = ((rightEdge - leftEdge - 16f) / 6f).coerceIn(30f, 44f)
            put(HudControl.HOTBAR, (leftEdge + rightEdge) / 2f, bot - slot / 2f - 4f, slot / 44f)
            put(HudControl.JOYSTICK, 62f * v, bot - 50f * u)
            right(HudControl.FIRE, 48f, 40f)
            put(HudControl.FIRE_LEFT, 150f * v, bot - 190f * v, u, visible = false)
            right(HudControl.AIM, 135f, 112f)
            right(HudControl.JUMP, 143f, 30f)
            right(HudControl.RELOAD, 40f, 135f)
            right(HudControl.CROUCH, 222f, 27f)
            put(HudControl.SPRINT, 62f * v, bot - (132.4f * u + 6f))
            right(HudControl.MELEE, 216f, 112f)
            right(HudControl.GRENADE, 180f, 178f)
            right(HudControl.MEDKIT, 262f, 178f)
            put(HudControl.WEAPON, wDp - 172f, 92f, 1f, mirror = false)
            put(HudControl.EMOTE, 136f, 86f, 1f, mirror = false)
            put(HudControl.MAP, 62f, 86f, 1f, mirror = false)
            put(HudControl.SUPPLY, 62f, 155f, 1f, mirror = false)
            put(HudControl.PAUSE, wDp - 40f, 36f, 1f, mirror = false)
            put(HudControl.BAG, wDp - 40f, 96f, 1f, mirror = false)
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
