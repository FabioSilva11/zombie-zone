package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.net.LanWire
import com.fabiosilva.ecosdeeldra.net.LanWire.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validação das mensagens da sala do PvP (tamanho, campos, nome, limites). Lógica pura na JVM. */
class LanWireTest {
    private fun parse(s: String) = LanWire.parse(s)

    @Test
    fun wellFormedMessagesParse() {
        assertEquals(Msg.Beacon("Ana", 2, 8, false), parse("ZZ1|BEACON|Ana|2|8|0"))
        assertEquals(Msg.Join("123456", 77, "Bia"), parse("ZZ1|JOIN|123456|77|Bia"))
        assertEquals(Msg.Ping(3), parse("ZZ1|PING|3"))
        assertEquals(Msg.Leave(3), parse("ZZ1|LEAVE|3"))
        assertEquals(Msg.Accept("123456", 2), parse("ZZ1|ACCEPT|123456|2"))
        assertEquals(Msg.Close, parse("ZZ1|CLOSE"))
        assertTrue(parse("ZZ1|DISCOVER|nsd") is Msg.Discover)
        assertTrue(parse("ZZ1|DISCOVER|123456789") is Msg.Discover)
        val room = parse("ZZ1|ROOM|10|5|0|0,3,Ana;1,17,Bia") as Msg.Room
        assertEquals(LanWire.RulesData(10, 5), room.rules)
        assertEquals(2, room.players.size)
        val start = parse("ZZ1|START|424242|15|8|0,3,Ana;1,17,Bia") as Msg.Start
        assertEquals(424242, start.seed)
        assertEquals(LanWire.RulesData(15, 8), start.rules)
    }

    @Test
    fun wrongPrefixUnknownTypeAndEmptyAreIgnored() {
        assertNull(parse(""))
        assertNull(parse("ZZ1"))
        assertNull(parse("XX1|PING|1"))
        assertNull(parse("ZZ1|HACK|1"))
        assertNull(parse("ZZ1|CLOSE|extra"))
    }

    @Test
    fun oversizedDatagramsAreDropped() {
        val big = "ZZ1|JOIN|123456|1|" + "A".repeat(LanWire.MAX_DATAGRAM)
        assertNull(parse(big))
        assertNull(LanWire.parse(ByteArray(LanWire.MAX_DATAGRAM + 1) { 'Z'.code.toByte() }, LanWire.MAX_DATAGRAM + 1))
        assertNull(LanWire.parse(ByteArray(10), 0))
        assertNull(LanWire.parse(ByteArray(10), 99))  // comprimento maior que o buffer
        val ok = "ZZ1|PING|2".toByteArray()
        assertEquals(Msg.Ping(2), LanWire.parse(ok, ok.size))
    }

    @Test
    fun controlCharactersRejectTheWholeMessage() {
        assertNull(parse("ZZ1|JOIN|123456|1|Bi\u0000a"))
        assertNull(parse("ZZ1|PING|1\n"))
    }

    @Test
    fun playerNamesAreSanitizedAndLimited() {
        val j = parse("ZZ1|JOIN|123456|1|  ${"X".repeat(40)}  ") as Msg.Join
        assertEquals(LanWire.MAX_NAME, j.name.length)
        val withPipe = parse("ZZ1|JOIN|123456|1|Ba|d;na,me") as Msg.Join
        assertFalse(withPipe.name.contains('|') || withPipe.name.contains(';') || withPipe.name.contains(','))
        assertEquals("Jogador", (parse("ZZ1|JOIN|123456|1|   ") as Msg.Join).name)
        assertEquals("Jogador", LanWire.sanitizeName("\t\u0001"))
    }

    @Test
    fun joinFieldsAreRangeChecked() {
        assertNull(parse("ZZ1|JOIN|abc|1|Ana"))        // nonce não numérico
        assertNull(parse("ZZ1|JOIN|12|1|Ana"))         // nonce curto
        assertNull(parse("ZZ1|JOIN|123456|-5|Ana"))    // aparência negativa
        assertNull(parse("ZZ1|JOIN|123456|999999999999|Ana"))
        assertNull(parse("ZZ1|JOIN|123456|${LanWire.MAX_SKIN + 1}|Ana"))
        assertNotNull(parse("ZZ1|JOIN|123456|${LanWire.MAX_SKIN}|Ana"))
        assertNull(parse("ZZ1|JOIN|123456"))
    }

    @Test
    fun idsAndCountsAreRangeChecked() {
        assertNull(parse("ZZ1|PING|8"))
        assertNull(parse("ZZ1|PING|-1"))
        assertNull(parse("ZZ1|PING|x"))
        assertNull(parse("ZZ1|ACCEPT|123456|0"))       // 0 é o anfitrião: ninguém recebe esse id
        assertNull(parse("ZZ1|ACCEPT|123456|8"))
        assertNull(parse("ZZ1|BEACON|Ana|0|8|0"))
        assertNull(parse("ZZ1|BEACON|Ana|9|8|0"))
        assertNull(parse("ZZ1|BEACON|Ana|2|99|0"))
        assertNull(parse("ZZ1|BEACON|Ana|2|8|2"))
        assertNull(parse("ZZ1|BEACON|Ana|2|8"))
    }

    @Test
    fun roomRulesAndSeedHaveLimits() {
        assertNull(parse("ZZ1|ROOM|0|5|0|0,1,A"))
        assertNull(parse("ZZ1|ROOM|10|0|0|0,1,A"))
        assertNull(parse("ZZ1|ROOM|51|5|0|0,1,A"))
        assertNull(parse("ZZ1|ROOM|10|31|0|0,1,A"))
        assertNull(parse("ZZ1|ROOM|10|5|3|0,1,A"))
        assertNull(parse("ZZ1|START|0|10|5|0,1,A;1,1,B"))
        assertNull(parse("ZZ1|START|16000001|10|5|0,1,A;1,1,B"))
        assertNull(parse("ZZ1|START|7|10|5|0,1,A"))     // sozinho não inicia partida
        assertNotNull(parse("ZZ1|START|16000000|50|30|0,1,A;1,1,B"))
    }

    @Test
    fun playerListIsStrict() {
        val raw = (0..11).joinToString(";") { "${it % 10},1,P$it" }  // repete ids e passa de 8
        val players = LanWire.decodePlayers(raw)
        assertTrue(players.size <= LanWire.MAX_PLAYERS)
        assertEquals(players.size, players.map { it.id }.toSet().size)
        assertTrue(players.all { it.id in 0 until LanWire.MAX_PLAYERS })
        assertTrue(LanWire.decodePlayers("0,-1,A;1,x,B;2,5").isEmpty())  // aparência inválida, número ruim, campo faltando
        assertEquals(1, LanWire.decodePlayers("0,1,A;0,2,B").size)       // id repetido
    }

    @Test
    fun encodeDecodeRoundTripKeepsPlayers() {
        val players = listOf(LanWire.Player(0, 3, "Ana"), LanWire.Player(5, 536870911, "Bia"))
        assertEquals(players, LanWire.decodePlayers(LanWire.encodePlayers(players)))
        // Um START com 8 jogadores de nome máximo cabe no limite de datagrama.
        val eight = (0 until 8).map { LanWire.Player(it, LanWire.MAX_SKIN, "N".repeat(LanWire.MAX_NAME)) }
        val msg = "ZZ1|START|16000000|50|30|" + LanWire.encodePlayers(eight)
        assertTrue(msg.length <= LanWire.MAX_DATAGRAM)
        assertEquals(8, (parse(msg) as Msg.Start).players.size)
    }

    @Test
    fun rejectReasonIsBounded() {
        assertNull(parse("ZZ1|REJECT|123456|" + "x".repeat(700)))  // acima do limite de datagrama
        assertEquals(LanWire.MAX_REASON, (parse("ZZ1|REJECT|123456|" + "x".repeat(300)) as Msg.Reject).reason.length)
        val short = parse("ZZ1|REJECT|123456|Sala cheia.") as Msg.Reject
        assertEquals("Sala cheia.", short.reason)
    }

    @Test
    fun hostLimitsEntriesPerAddress() {
        assertTrue(LanWire.canAdmit(listOf("10.0.0.2"), "10.0.0.2"))
        assertFalse(LanWire.canAdmit(listOf("10.0.0.2", "10.0.0.2"), "10.0.0.2"))
        assertTrue(LanWire.canAdmit(listOf("10.0.0.2", "10.0.0.2"), "10.0.0.3"))
    }
}
