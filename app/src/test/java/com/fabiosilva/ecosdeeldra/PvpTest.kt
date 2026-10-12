package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Look
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.net.LanLobby
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PvpTest {
    @Test
    fun pvpRewardCountsKillsAndVictory() {
        val p = Progress()
        assertEquals(6 * 2 + 25, Rules.applyPvpResult(p, kills = 6, won = true))
        assertEquals(5, Rules.applyPvpResult(p, kills = 0, won = false))
        assertEquals(6, p.stats.pvpKills)
        assertEquals(1, p.stats.pvpWins)
        assertEquals(2, p.stats.pvpMatches)
        assertEquals(42, p.diamonds)
    }

    @Test
    fun lobbyPlayersSurviveEncoding() {
        val players = listOf(
            LanLobby.LobbyPlayer(0, 3, "Ana"),
            LanLobby.LobbyPlayer(5, 17, "Bia|;,hack"),
        )
        val decoded = LanLobby.decodePlayers(LanLobby.encodePlayers(players))
        assertEquals(2, decoded.size)
        assertEquals(LanLobby.LobbyPlayer(0, 3, "Ana"), decoded[0])
        assertEquals(5, decoded[1].id)
        assertEquals(17, decoded[1].skin)
        assertFalse(decoded[1].name.contains('|') || decoded[1].name.contains(';') || decoded[1].name.contains(','))
    }

    @Test
    fun characterLookSurvivesLobbyEncoding() {
        val look = Look(skin = 6, hairStyle = 5, top = 6, topColor = 15, hat = 7, shorts = true).encode()
        val decoded = LanLobby.decodePlayers(LanLobby.encodePlayers(listOf(LanLobby.LobbyPlayer(1, look, "Rex"))))
        assertEquals(look, decoded.single().skin)
    }

    @Test
    fun invalidLobbyEntriesAreIgnored() {
        val decoded = LanLobby.decodePlayers("9,1,Fora;x,1,Ruim;2,99,Ok")
        assertEquals(1, decoded.size)
        assertEquals(2, decoded[0].id)
        assertEquals(99, decoded[0].skin)  // skin = aparência codificada do personagem
        assertTrue(LanLobby.sanitize("   ").isNotEmpty())
    }
}
