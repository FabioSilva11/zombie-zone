package com.fabiosilva.ecosdeeldra.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** O cartão do tutorial cabe sem tocar controles, barra de itens, mira ou aviso de combate (inclui 640x360 dp). */
class CoachPlacementTest {
    private class Screen(val w: Float, val h: Float, val name: String)

    private val screens = listOf(
        Screen(640f, 360f, "640x360"),
        Screen(800f, 360f, "800x360"),
        Screen(892f, 412f, "892x412"),
        Screen(2712f / 3f, 1220f / 3f, "2712x1220@3"),
        Screen(2712f / 2.625f, 1220f / 2.625f, "2712x1220@2.625"),
    )

    private fun blockers(l: HudLayout, s: Screen): List<HudTouchArea> =
        HudControl.entries.filter { it != HudControl.HOTBAR && l[it].visible }.map { c ->
            HudTouch.area(c, l[c].x * s.w, l[c].y * s.h, c.baseDp * l[c].scale, 1f)
        }

    private fun hotbarTop(l: HudLayout, s: Screen): Float =
        l[HudControl.HOTBAR].y * s.h - HudControl.HOTBAR.baseDp * l[HudControl.HOTBAR].scale

    private fun place(l: HudLayout, s: Screen) =
        CoachPlacement.place(s.w, s.h, hotbarTop(l, s) - 6f, blockers(l, s), 1f)

    @Test
    fun cardFitsOnEveryDefaultScreen() {
        for (s in screens) for (left in listOf(false, true)) {
            val l = HudLayout.defaults(s.w, s.h, left)
            val tag = "${s.name} left=$left"
            val card = place(l, s)
            assertNotNull("$tag: cartão não coube", card)
            card!!
            assertTrue("$tag largura legível ${card.width}", card.width >= CoachPlacement.MIN_W_DP - 0.01f)
            assertTrue("$tag largura <= 44%", card.width <= 0.44f * s.w + 0.01f)
            assertTrue("$tag dentro da tela", card.left >= 0f && card.right <= s.w && card.top >= 0f && card.bottom <= s.h)
            assertTrue("$tag abaixo da mira", card.top >= s.h / 2f + CoachPlacement.RETICLE_CLEAR_DP - 0.5f)
            assertTrue("$tag acima da barra de itens", card.bottom <= hotbarTop(l, s))
            for (b in blockers(l, s)) assertFalse("$tag encosta num controle", card.toArea().overlaps(b))
        }
    }

    @Test
    fun skipButtonIsReachableAndInsideTheScreen() {
        for (s in screens) {
            val l = HudLayout.defaults(s.w, s.h, false)
            val card = place(l, s)!!
            val skip = CoachPlacement.skipTouch(card, 1f)
            assertTrue(skip.left >= card.left && skip.right <= card.right + 8f)
            assertTrue(skip.top >= 0f && skip.bottom <= s.h)
            assertTrue("zona de toque confortável", skip.height >= 36f)
            for (b in blockers(l, s)) assertFalse("${s.name}: PULAR encosta num controle", skip.toArea().overlaps(b))
        }
    }

    @Test
    fun cardScalesWithDensity() {
        val s = Screen(640f, 360f, "640x360")
        val l = HudLayout.defaults(s.w, s.h, false)
        val d = 2f
        val blockers = HudControl.entries.filter { it != HudControl.HOTBAR && l[it].visible }.map { c ->
            HudTouch.area(c, l[c].x * s.w * d, l[c].y * s.h * d, c.baseDp * l[c].scale * d, d)
        }
        val card = CoachPlacement.place(s.w * d, s.h * d, (hotbarTop(l, s) - 6f) * d, blockers, d)
        assertNotNull(card)
        assertTrue(card!!.width >= CoachPlacement.MIN_W_DP * d - 0.01f)
    }

    @Test
    fun returnsNullWhenNothingFits() {
        // Um controle gigante atravessando o centro em toda a faixa impede qualquer cartão.
        val wall = HudTouchArea.Box(100f, 0f, 540f, 360f)
        assertTrue(CoachPlacement.place(640f, 360f, 290f, listOf(wall), 1f) == null)
    }
}
