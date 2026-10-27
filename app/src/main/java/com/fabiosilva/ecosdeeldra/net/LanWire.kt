package com.fabiosilva.ecosdeeldra.net

/**
 * Formato e validação das mensagens da sala do PvP em Wi-Fi local (porta 47777). Lógica PURA, sem Android,
 * para ser testada na JVM (PvpTest / LanWireTest). Tudo que chega pela rede passa por [parse]: tamanho,
 * prefixo, número de campos, números dentro de faixa, nome limpo e lista de jogadores limitada.
 *
 * O PvP é casual entre amigos: isto barra lixo e abuso simples, não é segurança competitiva.
 */
object LanWire {
    // Versão 3: terreno Horizonte destrutível, com journal autoritativo confirmado pela rede.
    const val PREFIX = "ZZ3"
    const val MAX_PLAYERS = 8
    const val MAX_DATAGRAM = 600          // a maior mensagem legítima (START com 8 jogadores) tem ~300 bytes
    const val MAX_NAME = 14
    const val MAX_SKIN = (1 shl 29) - 1   // aparência codificada (Look.encode usa 29 bits)
    const val MAX_REASON = 80
    const val MAX_MEMBERS_PER_ADDRESS = 2  // quantas entradas o anfitrião aceita do mesmo IP

    val KILL_LIMITS = 1..50
    val MATCH_MINUTES = 1..30
    val SEEDS = 1..16_000_000

    data class Player(val id: Int, val skin: Int, val name: String)
    data class RulesData(val killLimit: Int, val timeMinutes: Int)

    sealed interface Msg {
        data class Beacon(val name: String, val players: Int, val max: Int, val inMatch: Boolean) : Msg
        data class Discover(val tag: String) : Msg
        data class Join(val nonce: String, val skin: Int, val name: String) : Msg
        data class Ping(val id: Int) : Msg
        data class Leave(val id: Int) : Msg
        data class Accept(val nonce: String, val id: Int) : Msg
        data class Reject(val nonce: String, val reason: String) : Msg
        data class Room(val rules: RulesData, val inMatch: Boolean, val players: List<Player>) : Msg
        data class Start(val seed: Int, val rules: RulesData, val players: List<Player>) : Msg
        object Close : Msg
    }

    /** Nome exibível: sem separadores do protocolo nem caracteres de controle, até 14 caracteres. */
    fun sanitizeName(name: String): String =
        name.replace(Regex("[|;,\\p{Cntrl}]"), " ").trim().take(MAX_NAME).trim().ifEmpty { "Jogador" }

    fun isNonce(s: String): Boolean = s.length in 4..9 && s.all { it in '0'..'9' }

    fun validSkin(skin: Int): Boolean = skin in 0..MAX_SKIN

    fun encodePlayers(players: List<Player>): String =
        players.take(MAX_PLAYERS).joinToString(";") { "${it.id},${it.skin},${sanitizeName(it.name)}" }

    /** Lista estrita: ids 0..7 sem repetição, aparência válida, nome limpo, no máximo 8 jogadores. Entradas ruins são ignoradas. */
    fun decodePlayers(raw: String): List<Player> {
        val out = ArrayList<Player>()
        for (entry in raw.split(';')) {
            if (out.size >= MAX_PLAYERS) break
            val f = entry.split(',')
            if (f.size < 3) continue
            val id = f[0].toIntOrNull() ?: continue
            if (id !in 0 until MAX_PLAYERS || out.any { it.id == id }) continue
            val skin = f[1].toIntOrNull() ?: continue
            if (!validSkin(skin)) continue
            out.add(Player(id, skin, sanitizeName(f[2])))
        }
        return out
    }

    fun parse(bytes: ByteArray, length: Int): Msg? {
        if (length <= 0 || length > MAX_DATAGRAM || length > bytes.size) return null
        return parse(String(bytes, 0, length, Charsets.UTF_8))
    }

    fun parse(text: String): Msg? {
        if (text.length > MAX_DATAGRAM || text.any { it.isISOControl() }) return null
        val p = text.split('|')
        if (p.size < 2 || p[0] != PREFIX) return null
        return when (p[1]) {
            "BEACON" -> {
                if (p.size != 6) return null
                val players = p[3].toIntOrNull() ?: return null
                val max = p[4].toIntOrNull() ?: return null
                if (players !in 1..MAX_PLAYERS || max !in 2..MAX_PLAYERS || p[5] !in listOf("0", "1")) return null
                Msg.Beacon(sanitizeName(p[2]), players, max, p[5] == "1")
            }
            "DISCOVER" -> if (p.size in 2..3) Msg.Discover(p.getOrElse(2) { "" }.take(16)) else null
            "JOIN" -> {
                if (p.size < 5) return null
                val skin = p[3].toIntOrNull() ?: return null
                if (!isNonce(p[2]) || !validSkin(skin)) return null
                // O nome é o resto da mensagem (um '|' dentro dele vira espaço na limpeza).
                Msg.Join(p[2], skin, sanitizeName(p.drop(4).joinToString(" ")))
            }
            "PING" -> if (p.size == 3) idOf(p[2])?.let { Msg.Ping(it) } else null
            "LEAVE" -> if (p.size == 3) idOf(p[2])?.let { Msg.Leave(it) } else null
            "ACCEPT" -> {
                if (p.size != 4 || !isNonce(p[2])) return null
                val id = idOf(p[3]) ?: return null
                if (id < 1) return null
                Msg.Accept(p[2], id)
            }
            "REJECT" -> {
                if (p.size < 4 || !isNonce(p[2])) return null
                Msg.Reject(p[2], p.drop(3).joinToString(" ").trim().take(MAX_REASON).ifEmpty { "Entrada recusada." })
            }
            "ROOM" -> {
                if (p.size != 6) return null
                val rules = rulesOf(p[2], p[3]) ?: return null
                if (p[4] !in listOf("0", "1")) return null
                Msg.Room(rules, p[4] == "1", decodePlayers(p[5]))
            }
            "START" -> {
                if (p.size != 6) return null
                val seed = p[2].toIntOrNull() ?: return null
                if (seed !in SEEDS) return null
                val rules = rulesOf(p[3], p[4]) ?: return null
                val players = decodePlayers(p[5])
                if (players.size < 2) return null  // partida precisa de pelo menos dois jogadores
                Msg.Start(seed, rules, players)
            }
            "CLOSE" -> if (p.size == 2) Msg.Close else null
            else -> null
        }
    }

    private fun idOf(s: String): Int? = s.toIntOrNull()?.takeIf { it in 0 until MAX_PLAYERS }

    private fun rulesOf(kill: String, minutes: String): RulesData? {
        val k = kill.toIntOrNull() ?: return null
        val m = minutes.toIntOrNull() ?: return null
        return if (k in KILL_LIMITS && m in MATCH_MINUTES) RulesData(k, m) else null
    }

    /** O anfitrião aceita outra entrada deste IP? (limite simples contra enchente de JOIN com nonces novos). */
    fun canAdmit(addressesAlreadyInRoom: List<String>, address: String): Boolean =
        addressesAlreadyInRoom.count { it == address } < MAX_MEMBERS_PER_ADDRESS
}
