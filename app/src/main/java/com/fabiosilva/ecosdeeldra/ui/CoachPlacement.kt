package com.fabiosilva.ecosdeeldra.ui

/**
 * Onde fica o cartão do tutorial (coach) na tela. Geometria pura, em px (use [unit] = px por dp),
 * para o HudView e os testes de 640x360 dp usarem exatamente a mesma conta.
 *
 * O cartão ocupa a faixa inferior-central, acima da barra de itens e abaixo da mira, e se estreita
 * ou sobe para não encostar em nenhum controle visível. Nunca usa o aviso de combate (CombatFeed).
 */
object CoachPlacement {
    const val CARD_H_DP = 64f
    const val MIN_W_DP = 190f
    const val MAX_W_DP = 300f
    const val MAX_W_FRACTION = 0.44f
    const val GAP_DP = 4f
    const val SIDE_MARGIN_DP = 8f
    const val STEP_DP = 6f

    /** Folga abaixo da mira: o recuo máximo da mira (espalhamento) chega a ~48 dp do centro. */
    const val RETICLE_CLEAR_DP = 44f

    const val SKIP_W_DP = 84f
    const val SKIP_H_DP = 22f
    /** Folga extra de toque em volta do botão PULAR (dp). */
    const val SKIP_TOUCH_PAD_DP = 8f

    class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
        fun toArea() = HudTouchArea.Box(left, top, right, bottom)
    }

    private fun HudTouchArea.bounds(): FloatArray = when (this) {
        is HudTouchArea.Circle -> floatArrayOf(cx - r, cy - r, cx + r, cy + r)
        is HudTouchArea.Box -> floatArrayOf(left, top, right, bottom)
    }

    /**
     * @param w largura da tela (px); @param h altura (px)
     * @param bottomLimit linha que o cartão não pode ultrapassar (topo da barra de itens menos folga)
     * @param blockers áreas de toque dos controles visíveis (sem a barra de itens)
     * @return o retângulo do cartão, ou null se não couber nenhum cartão legível (o HudView então usa o aviso curto)
     */
    fun place(w: Float, h: Float, bottomLimit: Float, blockers: List<HudTouchArea>, unit: Float): Rect? {
        val cardH = CARD_H_DP * unit
        val gap = GAP_DP * unit
        val minTop = h / 2f + RETICLE_CLEAR_DP * unit
        val bounds = blockers.map { it.bounds() }
        var bottom = bottomLimit
        while (bottom - cardH >= minTop - 0.5f) {
            val top = bottom - cardH
            var l = SIDE_MARGIN_DP * unit
            var r = w - SIDE_MARGIN_DP * unit
            var blocked = false
            for (b in bounds) {
                if (b[3] < top - gap || b[1] > bottom + gap) continue      // fora da faixa vertical
                val cx = (b[0] + b[2]) / 2f
                if (b[0] < w / 2f && b[2] > w / 2f) { blocked = true; break }   // atravessa o centro
                if (cx < w / 2f) l = maxOf(l, b[2] + gap) else r = minOf(r, b[0] - gap)
            }
            if (!blocked) {
                val cardW = minOf(r - l, MAX_W_DP * unit, MAX_W_FRACTION * w)
                if (cardW >= MIN_W_DP * unit) {
                    val left = (w / 2f - cardW / 2f).coerceIn(l, r - cardW)
                    return Rect(left, top, left + cardW, bottom)
                }
            }
            bottom -= STEP_DP * unit
        }
        return null
    }

    /** Botão PULAR TUTORIAL: pílula logo acima do canto superior direito do cartão (só 5 dp dentro dele, sem cobrir o título). */
    fun skipRect(card: Rect, unit: Float): Rect {
        val right = card.right - 6f * unit
        val left = right - SKIP_W_DP * unit
        val top = card.top - (SKIP_H_DP - 5f) * unit
        return Rect(left, top, right, top + SKIP_H_DP * unit)
    }

    /** Zona de toque do PULAR: o botão com folga (mínimo confortável para o polegar). */
    fun skipTouch(card: Rect, unit: Float): Rect {
        val s = skipRect(card, unit)
        val pad = SKIP_TOUCH_PAD_DP * unit
        return Rect(s.left - pad, s.top - pad, s.right + pad, s.bottom + pad)
    }
}
