package com.fabiosilva.ecosdeeldra.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLayoutTest {
    private class Screen(val w: Float, val h: Float, val name: String)

    private fun dp(widthPx: Int, heightPx: Int, density: Float) =
        Screen(widthPx / density, heightPx / density, "${widthPx}x$heightPx@$density")

    /** Telas landscape de teste em dp (a última linha são 2712x1220 px em duas densidades comuns). */
    private val screens = listOf(
        Screen(640f, 360f, "640x360"),
        Screen(800f, 360f, "800x360"),
        Screen(892f, 412f, "892x412"),
        dp(2712, 1220, 3f),
        dp(2712, 1220, 2.625f),
    )

    private val combat = listOf(
        HudControl.FIRE, HudControl.FIRE_LEFT, HudControl.AIM, HudControl.JUMP, HudControl.RELOAD,
        HudControl.CROUCH, HudControl.SPRINT, HudControl.MELEE, HudControl.GRENADE, HudControl.JOYSTICK, HudControl.HOTBAR,
    )
    private val fixed = listOf(HudControl.MAP, HudControl.SUPPLY, HudControl.PAUSE, HudControl.BAG)

    private fun diameter(l: HudLayout, c: HudControl) = 2f * c.baseDp * l[c].scale

    private fun center(l: HudLayout, s: Screen, c: HudControl) = l[c].x * s.w to l[c].y * s.h

    @Test
    fun defaultSizesMatchReferenceScale() {
        val l = HudLayout.defaults(892f, 412f, false)
        assertEquals(96f, diameter(l, HudControl.MAP), 0.01f)
        assertEquals(100f, diameter(l, HudControl.JOYSTICK), 0.01f)
        assertEquals(80f, diameter(l, HudControl.FIRE), 0.01f)
        for (c in listOf(HudControl.AIM, HudControl.JUMP, HudControl.RELOAD)) assertEquals(c.name, 60f, diameter(l, c), 0.01f)
        for (c in listOf(HudControl.SPRINT, HudControl.MELEE, HudControl.GRENADE, HudControl.CROUCH)) assertEquals(c.name, 54f, diameter(l, c), 0.01f)
        // os seis espaços da barra de itens: 44 dp cada em telas de referência
        assertEquals(44f, diameter(l, HudControl.HOTBAR), 0.01f)
    }

    @Test
    fun defaultLayoutFitsNarrowLandscape() {
        for (s in screens) for (left in listOf(false, true)) {
            val l = HudLayout.defaults(s.w, s.h, left)
            for (c in HudControl.entries) {
                val (x, y) = center(l, s, c)
                val r = c.baseDp * l[c].scale
                val tag = "${s.name} left=$left ${c.name}"
                if (c == HudControl.HOTBAR) {
                    val slot = 2f * r
                    assertTrue("$tag slot legível", slot >= 30f - 0.01f)
                    assertTrue("$tag esquerda", x - slot * 3f >= 0f)
                    assertTrue("$tag direita", x + slot * 3f <= s.w)
                    // painel da barra = seis espaços + 4 dp em volta
                    assertTrue("$tag margem inferior", y + slot / 2f + 4f <= s.h - HudLayout.BOTTOM_MARGIN_DP + 0.01f)
                } else {
                    assertTrue("$tag esquerda", x - r >= 0f)
                    assertTrue("$tag direita", x + r <= s.w)
                    assertTrue("$tag topo", y - r >= 0f)
                    assertTrue("$tag margem inferior", y + r <= s.h - HudLayout.BOTTOM_MARGIN_DP + 0.01f)
                }
            }
        }
    }

    @Test
    fun defaultLayoutSeparatesTouchTargets() {
        for (s in screens) for (left in listOf(false, true)) {
            val l = HudLayout.defaults(s.w, s.h, left)
            val shown = HudControl.entries.filter { l[it].visible }
            val areas = shown.map { c ->
                val (x, y) = center(l, s, c)
                c to HudTouch.area(c, x, y, c.baseDp * l[c].scale, 1f)
            }
            for (i in areas.indices) for (j in i + 1 until areas.size) {
                assertFalse("${s.name} left=$left: ${areas[i].first} x ${areas[j].first}", areas[i].second.overlaps(areas[j].second))
            }
        }
    }

    @Test
    fun defaultLayoutMirrorsCombatControlsForLeftHanded() {
        for (s in screens) {
            val right = HudLayout.defaults(s.w, s.h, false)
            val left = HudLayout.defaults(s.w, s.h, true)
            for (c in combat) {
                assertEquals("${s.name} ${c.name} x", 1f - right[c].x, left[c].x, 0.0001f)
                assertEquals("${s.name} ${c.name} y", right[c].y, left[c].y, 0.0001f)
                assertEquals("${s.name} ${c.name} escala", right[c].scale, left[c].scale, 0.0001f)
            }
            for (c in fixed) {
                assertEquals("${s.name} ${c.name} x", right[c].x, left[c].x, 0.0001f)
                assertEquals("${s.name} ${c.name} y", right[c].y, left[c].y, 0.0001f)
            }
        }
    }

    @Test
    fun decodePreservesCustomControlPositions() {
        val fallback = HudLayout.defaults(892f, 412f, false)
        val saved = "fire=0.3000,0.4000,1.250,1;map=0.5000,0.2500,0.800,0;hotbar=0.4000,0.9000,1.000,1"
        val l = HudLayout.decode(saved, fallback)
        assertEquals(0.3f, l[HudControl.FIRE].x, 0.0001f)
        assertEquals(0.4f, l[HudControl.FIRE].y, 0.0001f)
        assertEquals(1.25f, l[HudControl.FIRE].scale, 0.0001f)
        assertEquals(0.5f, l[HudControl.MAP].x, 0.0001f)
        assertEquals(0.8f, l[HudControl.MAP].scale, 0.0001f)
        assertFalse(l[HudControl.MAP].visible)
        // controles que não estavam no layout salvo (versão antiga) vêm do padrão
        assertEquals(fallback[HudControl.JUMP].x, l[HudControl.JUMP].x, 0.0001f)
        assertEquals(fallback[HudControl.BAG].y, l[HudControl.BAG].y, 0.0001f)
        // texto antigo/errado não derruba: ignora a entrada inválida e preserva o resto
        val odd = HudLayout.decode("xyz=1,2,3,1;fire=abc,0.4,1,1;aim=0.1,0.2,9.0,1", fallback)
        assertEquals(fallback[HudControl.FIRE].x, odd[HudControl.FIRE].x, 0.0001f)
        assertEquals(HudLayout.MAX_SCALE, odd[HudControl.AIM].scale, 0.0001f)
        // salvar e ler de novo mantém tudo
        val again = HudLayout.decode(l.encode(), fallback)
        for (c in HudControl.entries) {
            assertEquals(c.name, l[c].x, again[c].x, 0.0001f)
            assertEquals(c.name, l[c].visible, again[c].visible)
        }
    }
}
