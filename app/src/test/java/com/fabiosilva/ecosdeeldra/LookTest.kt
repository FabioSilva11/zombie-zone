package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Cosmetics
import com.fabiosilva.ecosdeeldra.data.Look
import com.fabiosilva.ecosdeeldra.data.LookOptions
import com.fabiosilva.ecosdeeldra.data.LookPart
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
