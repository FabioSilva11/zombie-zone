package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Cosmetics
import com.fabiosilva.ecosdeeldra.data.Look
import com.fabiosilva.ecosdeeldra.data.LookOptions
import com.fabiosilva.ecosdeeldra.data.LookPainter
import com.fabiosilva.ecosdeeldra.data.LookPart
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LookTest {
    @Test
    fun encodeDecodeRoundTripsEveryPart() {
        val look = Look(skin = 6, hairStyle = 7, hairColor = 5, eyes = 3, face = 2, top = 6, topColor = 15, pantsColor = 7, shoes = 3, hat = 7, shorts = true)
        assertEquals(look, Look.decode(look.encode()))
        for (part in LookPart.entries) {
            for (v in 0 until LookOptions.count(part)) {
                assertEquals(v, Look.decode(Look().with(part, v).encode())[part])
            }
        }
        // O bit de zumbi (30) é só do motor: nunca aparece numa aparência de jogador.
        assertEquals(0, Look(hat = 7, shorts = true).encode() and (1 shl 30))
    }

    @Test
    fun lockedPartsNeedTheirCosmetic() {
        val p = Progress()
        assertFalse(Cosmetics.available(p, LookPart.HAT, 7))  // coroa: 7 dias de login
        assertTrue(Cosmetics.available(p, LookPart.HAT, 0))
        val crowned = Look(hat = 7, top = 6)
        assertEquals(Look(), Cosmetics.sanitize(p, crowned))
        p.ownedCosmetics.add(15)
        assertEquals(7, Cosmetics.sanitize(p, crowned).hat)
        // Sorteio só usa peças livres.
        repeat(50) { i ->
            val r = Look.random(p, Random(i))
            for (part in LookPart.entries) assertTrue(Cosmetics.available(p, part, r[part]))
        }
    }

    @Test
    fun heroesCanBeCreatedSelectedAndDeleted() {
        val p = Progress()
        assertEquals(1, p.heroes.size)
        assertFalse(Rules.deleteHero(p, 0))
        assertTrue(Rules.createHero(p, Look(hat = 7), "Ana|"))
        assertEquals(1, p.selectedHero)
        assertEquals("Ana", p.currentHero.name)
        assertEquals(0, Look.decode(p.lookCode).hat)  // peça travada removida
        repeat(10) { Rules.createHero(p, Look(), "X") }
        assertEquals(Rules.MAX_HEROES, p.heroes.size)
        val selected = p.currentHero
        assertTrue(Rules.deleteHero(p, 0))
        assertEquals(selected, p.currentHero)  // o personagem em uso continua o mesmo
    }

    @Test
    fun bodyThumbnailFollowsEveryPiece() {
        fun pixel(look: Look, x: Int, y: Int) = LookPainter.bodyPixels(look)[y * LookPainter.BODY_W + x]
        fun alphaOf(color: Int) = (color ushr 24) and 0xFF

        val base = Look()
        val thumb = LookPainter.bodyPixels(base)
        assertEquals(LookPainter.BODY_W * LookPainter.BODY_H, thumb.size)
        assertEquals(0, alphaOf(thumb[0]))  // fundo transparente
        // Cabeça (7,6), camisa (7,15), calça (6,25) e sapato (6,31).
        assertEquals(LookOptions.skinColors[base.skin], pixel(base, 7, 6))
        assertEquals(LookOptions.topColors[base.topColor], pixel(base, 7, 15))
        assertEquals(LookOptions.pantsColors[base.pantsColor], pixel(base, 6, 25))
        assertEquals(LookOptions.shoeColors[base.shoes], pixel(base, 6, 31))
        assertTrue(thumb.all { alphaOf(it) == 0 || alphaOf(it) == 255 })

        // Cada peça muda a miniatura no ponto correspondente.
        assertEquals(LookOptions.skinColors[5], pixel(Look(skin = 5), 7, 6))
        assertEquals(LookOptions.hairColors[2], pixel(Look(hairStyle = 1, hairColor = 2), 7, 2))
        assertEquals(LookOptions.topColors[0], pixel(Look(topColor = 0), 7, 15))
        assertEquals(LookOptions.pantsColors[6], pixel(Look(pantsColor = 6), 6, 25))
        assertEquals(LookOptions.shoeColors[3], pixel(Look(shoes = 3), 6, 31))
        assertNotEquals(pixel(Look(), 6, 25), pixel(Look(shorts = true), 6, 25))  // bermuda expõe a perna
        assertEquals(0, alphaOf(pixel(Look(hairStyle = 0), 7, 2)))          // careca: sem cabelo
        assertEquals(LookOptions.hairColors[2], pixel(Look(hairStyle = 1, hairColor = 2), 7, 2))
    }

    @Test
    fun everyOptionHasAUsableThumbnail() {
        for (part in LookPart.entries) {
            for (v in 0 until LookOptions.count(part)) {
                val thumb = LookPainter.bodyPixels(Look().with(part, v))
                assertTrue(
                    "miniatura vazia para ${part.name} = $v",
                    thumb.any { ((it ushr 24) and 0xFF) == 255 },
                )
            }
        }
    }
}
