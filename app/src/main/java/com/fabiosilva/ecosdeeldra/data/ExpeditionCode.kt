package com.fabiosilva.ecosdeeldra.data

/**
 * Código curto e reproduzível da expedição: ZZ-XXXX-XXXX-XXXXX (13 símbolos base32 Crockford, CRC-12).
 * Carga útil de 53 bits (ver 2, tipo 1, fase 10, semente 24, contrato 8, mods 8) + CRC-12 = 65 bits.
 * O código reproduz ilha e regras, não a partida quadro a quadro.
 */
object ExpeditionCode {
    const val FORMAT_VERSION = 1
    const val STAGE_MAX = 1023
    const val SEED_MAX = 0xFF_FFFF

    enum class Problem { EMPTY, LENGTH, ALPHABET, CHECKSUM, VERSION, STAGE, SEED, CONTRACT, KIND_CONTRACT, MODS }

    sealed interface Decoded {
        data class Ok(val plan: ExpeditionPlan) : Decoded        // sharedSeed = true
        data class Bad(val problem: Problem) : Decoded
    }

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val POLY = 0x80F
    private const val PAYLOAD_BITS = 53
    private const val CRC_BITS = 12
    private const val SYMBOLS = 13

    private val EXTRACT = Regex("ZZ-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{5}")

    private fun crc12(bits: IntArray, count: Int): Int {
        var crc = 0xFFF
        for (i in 0 until count) {
            val top = (crc shr 11) and 1
            crc = (crc shl 1) and 0xFFF
            if ((top xor bits[i]) == 1) crc = crc xor POLY
        }
        return crc
    }

    fun encode(plan: ExpeditionPlan): String? {
        if (plan.stage !in 1..STAGE_MAX || plan.seed !in 1..SEED_MAX) return null
        val bits = IntArray(PAYLOAD_BITS + CRC_BITS)
        var at = 0
        fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits[at++] = (value shr i) and 1 }
        put(FORMAT_VERSION, 2); put(plan.kind.ordinal, 1); put(plan.stage, 10)
        put(plan.seed, 24); put(plan.contract.code, 8); put(0, 8)
        put(crc12(bits, PAYLOAD_BITS), CRC_BITS)
        val raw = buildString {
            for (s in 0 until SYMBOLS) {
                var v = 0
                for (k in 0 until 5) v = (v shl 1) or bits[s * 5 + k]
                append(ALPHABET[v])
            }
        }
        return "ZZ-${raw.substring(0, 4)}-${raw.substring(4, 8)}-${raw.substring(8)}"
    }

    fun decode(text: String): Decoded {
        var s = buildString {
            for (ch in text.uppercase()) {
                if (ch == '-' || ch.isWhitespace()) continue
                append(when (ch) { 'O' -> '0'; 'I', 'L' -> '1'; else -> ch })
            }
        }
        if (s.isEmpty()) return Decoded.Bad(Problem.EMPTY)
        // O prefixo "ZZ" só sai quando o comprimento é 15: um código de 13 símbolos pode começar por "ZZ".
        if (s.length == SYMBOLS + 2 && s.startsWith("ZZ")) s = s.substring(2)
        if (s.length != SYMBOLS) return Decoded.Bad(Problem.LENGTH)
        val bits = IntArray(PAYLOAD_BITS + CRC_BITS)
        for ((i, ch) in s.withIndex()) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) return Decoded.Bad(Problem.ALPHABET)
            for (k in 0 until 5) bits[i * 5 + k] = (v shr (4 - k)) and 1
        }
        var crc = 0
        for (i in PAYLOAD_BITS until PAYLOAD_BITS + CRC_BITS) crc = (crc shl 1) or bits[i]
        if (crc12(bits, PAYLOAD_BITS) != crc) return Decoded.Bad(Problem.CHECKSUM)
        var at = 0
        fun take(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bits[at++] }; return v }
        val ver = take(2); val kindBit = take(1); val stage = take(10)
        val seed = take(24); val contractCode = take(8); val mods = take(8)
        if (ver != FORMAT_VERSION) return Decoded.Bad(Problem.VERSION)
        if (mods != 0) return Decoded.Bad(Problem.MODS)
        if (stage !in 1..STAGE_MAX) return Decoded.Bad(Problem.STAGE)
        if (seed !in 1..SEED_MAX) return Decoded.Bad(Problem.SEED)
        val contract = ContractId.fromCode(contractCode) ?: return Decoded.Bad(Problem.CONTRACT)
        val kind = ExpeditionKind.entries[kindBit]
        if (kind !in Contracts.get(contract).kinds) return Decoded.Bad(Problem.KIND_CONTRACT)
        return Decoded.Ok(ExpeditionPlan(kind, stage, seed, contract, sharedSeed = true))
    }

    /** Acha "ZZ-XXXX-XXXX-XXXXX" dentro de uma mensagem colada. */
    fun extract(text: String): String? = EXTRACT.find(text.uppercase())?.value

    fun message(problem: Problem): String = when (problem) {
        Problem.EMPTY -> "Digite ou cole um código."
        Problem.LENGTH -> "O código tem 13 letras e números (ex.: ZZ-80W0-Y4G0-100RS)."
        Problem.ALPHABET -> "Há um caractere inválido. O código só usa números e letras (sem I, L, O, U)."
        Problem.CHECKSUM -> "O código não confere. Confira se digitou tudo certo."
        Problem.VERSION, Problem.MODS -> "Este código vem de uma versão mais nova do jogo."
        Problem.STAGE, Problem.SEED, Problem.CONTRACT, Problem.KIND_CONTRACT -> "Este código tem dados inválidos."
    }
}
