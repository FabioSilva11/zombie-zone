package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.ContractId
import com.fabiosilva.ecosdeeldra.data.Contracts
import com.fabiosilva.ecosdeeldra.data.ExpeditionCode
import com.fabiosilva.ecosdeeldra.data.ExpeditionCode.Decoded
import com.fabiosilva.ecosdeeldra.data.ExpeditionCode.Problem
import com.fabiosilva.ecosdeeldra.data.ExpeditionKind
import com.fabiosilva.ecosdeeldra.data.ExpeditionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpeditionCodeTest {
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val poly = 0x80F

    private fun plan(kind: ExpeditionKind, stage: Int, seed: Int, id: ContractId, shared: Boolean = false) =
        ExpeditionPlan(kind, stage, seed, id, shared)

    /** Só de teste: monta um código com CRC válido e campos arbitrários (copia a rotina crc12 do formato). */
    private fun encodeRaw(version: Int, kind: Int, stage: Int, seed: Int, contract: Int, mods: Int): String {
        val bits = IntArray(65)
        var at = 0
        fun put(v: Int, n: Int) { for (i in n - 1 downTo 0) bits[at++] = (v shr i) and 1 }
        put(version, 2); put(kind, 1); put(stage, 10); put(seed, 24); put(contract, 8); put(mods, 8)
        var crc = 0xFFF
        for (i in 0 until 53) {
            val top = (crc shr 11) and 1
            crc = (crc shl 1) and 0xFFF
            if ((top xor bits[i]) == 1) crc = crc xor poly
        }
        put(crc, 12)
        return buildString { for (s in 0 until 13) { var v = 0; for (k in 0 until 5) v = (v shl 1) or bits[s * 5 + k]; append(alphabet[v]) } }
    }

    private val golden = listOf(
        Triple(plan(ExpeditionKind.CAMPAIGN, 1, 1, ContractId.NONE), "ZZ-8040-0008-003W3", 0xF83),
        Triple(plan(ExpeditionKind.CAMPAIGN, 7, 123456, ContractId.SILENT_RECOVERY), "ZZ-80W0-Y4G0-100RS", 0x319),
        Triple(plan(ExpeditionKind.CAMPAIGN, 12, 15999999, ContractId.RED_NIGHT), "ZZ-81KT-27ZR-302ZS", 0xBF9),
        Triple(plan(ExpeditionKind.SURVIVAL, 11, 4242, ContractId.ENDURANCE), "ZZ-C1C0-114G-502VA", 0xB6A),
        Triple(plan(ExpeditionKind.CAMPAIGN, 1023, 16777215, ContractId.LOST_CRATE), "ZZ-BZZZ-ZZZR-40262", 0x8C2),
        Triple(plan(ExpeditionKind.CAMPAIGN, 3, 777, ContractId.UNSTABLE_NEST), "ZZ-80C0-0628-20395", 0xD25),
        Triple(plan(ExpeditionKind.CAMPAIGN, 2, 123456, ContractId.SILENT_RECOVERY), "ZZ-8080-Y4G0-1011S", 0x439),
        Triple(plan(ExpeditionKind.CAMPAIGN, 3, 123456, ContractId.RED_NIGHT), "ZZ-80C0-Y4G0-300CK", 0x193),
        Triple(plan(ExpeditionKind.SURVIVAL, 1, 2024, ContractId.ENDURANCE), "ZZ-C040-0FT0-50355", 0xCA5),
        Triple(plan(ExpeditionKind.SURVIVAL, 6, 99, ContractId.RED_NIGHT), "ZZ-C0R0-00RR-301G3", 0x603),
    )

    @Test
    fun goldenVectorsAreStable() {
        for ((p, code, _) in golden) {
            assertEquals(code, p.code)
            val back = ExpeditionCode.decode(code)
            assertTrue("$code -> $back", back is Decoded.Ok)
            assertEquals(p.copy(sharedSeed = true), (back as Decoded.Ok).plan)
        }
    }

    @Test
    fun roundTripKeepsEveryField() {
        for (kind in ExpeditionKind.entries) for (id in ContractId.entries) {
            if (kind !in Contracts.get(id).kinds) continue
            for (stage in listOf(1, 2, 7, 20, 137, 1023)) for (seed in listOf(1, 4242, 123456, 15_999_999, 16_777_215)) {
                val p = plan(kind, stage, seed, id)
                val code = p.code!!
                assertEquals(Decoded.Ok(p.copy(sharedSeed = true)), ExpeditionCode.decode(code))
            }
        }
    }

    @Test
    fun singleCharacterSubstitutionIsAlwaysRejected() {
        for ((_, code, _) in golden.take(3)) {
            val raw = code.replace("-", "").substring(2)
            assertEquals(13, raw.length)
            for (pos in raw.indices) for (c in alphabet) {
                if (c == raw[pos]) continue
                val mutated = raw.substring(0, pos) + c + raw.substring(pos + 1)
                assertTrue("$code pos=$pos -> $c aceito", ExpeditionCode.decode(mutated) is Decoded.Bad)
            }
        }
    }

    @Test
    fun normalizationAcceptsLowercaseSeparatorsAndConfusables() {
        val want = Decoded.Ok(plan(ExpeditionKind.CAMPAIGN, 7, 123456, ContractId.SILENT_RECOVERY, true))
        assertEquals(want, ExpeditionCode.decode("zz-80w0-y4g0-100rs"))
        assertEquals(want, ExpeditionCode.decode("  ZZ 80W0 Y4G0 100RS \n"))
        assertEquals(want, ExpeditionCode.decode("ZZ-80W0-Y4G0-\n100RS"))
        assertEquals(want, ExpeditionCode.decode("80W0-Y4G0-100RS"))
        assertEquals(want, ExpeditionCode.decode("ZZ-8OW0-Y4G0-1OORS"))      // O no lugar de 0
        assertEquals(want, ExpeditionCode.decode("ZZ-80W0-Y4G0-l00RS"))      // l no lugar de 1
        assertEquals("ZZ-80W0-Y4G0-100RS", ExpeditionCode.extract("Joga essa ilha: ZZ-80W0-Y4G0-100RS!"))
        assertEquals("ZZ-80W0-Y4G0-100RS", ExpeditionCode.extract("joga zz-80w0-y4g0-100rs"))
        assertNull(ExpeditionCode.extract("sem código aqui"))
    }

    @Test
    fun codeStartingWithZzIsNotMistakenForThePrefix() {
        // 13 símbolos que começam por ZZ: precisa decodificar (ou falhar por CRC), nunca por LENGTH.
        val d = ExpeditionCode.decode("ZZZZZZZZZZZZZ")
        assertEquals(Decoded.Bad(Problem.CHECKSUM), d)
    }

    @Test
    fun lengthAlphabetAndChecksumHaveReadableProblems() {
        assertEquals(Decoded.Bad(Problem.EMPTY), ExpeditionCode.decode(""))
        assertEquals(Decoded.Bad(Problem.EMPTY), ExpeditionCode.decode("  - \n"))
        assertEquals(Decoded.Bad(Problem.LENGTH), ExpeditionCode.decode("ZZ-80W0-Y4G0-100R"))
        assertEquals(Decoded.Bad(Problem.ALPHABET), ExpeditionCode.decode("ZZ-80W0-Y4G0-100RU"))
        assertEquals(Decoded.Bad(Problem.CHECKSUM), ExpeditionCode.decode("ZZ-80W0-Y4G0-100RT"))
        for (p in Problem.entries) assertTrue(ExpeditionCode.message(p).isNotBlank())
    }

    @Test
    fun invalidFieldsAreRejectedAfterTheChecksum() {
        fun bad(raw: String) = (ExpeditionCode.decode(raw) as Decoded.Bad).problem
        assertEquals(Problem.VERSION, bad(encodeRaw(2, 0, 7, 100, 1, 0)))
        assertEquals(Problem.MODS, bad(encodeRaw(1, 0, 7, 100, 1, 1)))
        assertEquals(Problem.STAGE, bad(encodeRaw(1, 0, 0, 100, 1, 0)))
        assertEquals(Problem.SEED, bad(encodeRaw(1, 0, 7, 0, 1, 0)))
        for (c in listOf(6, 7, 255)) assertEquals(Problem.CONTRACT, bad(encodeRaw(1, 0, 7, 100, c, 0)))
        assertEquals(Problem.KIND_CONTRACT, bad(encodeRaw(1, 1, 6, 100, ContractId.SILENT_RECOVERY.code, 0)))
        assertEquals(Problem.KIND_CONTRACT, bad(encodeRaw(1, 0, 6, 100, ContractId.ENDURANCE.code, 0)))
        // E o caso feliz com o mesmo helper bate com o encode real.
        val ok = encodeRaw(1, 0, 7, 123456, 1, 0)
        assertEquals("ZZ-80W0-Y4G0-100RS".replace("-", "").substring(2), ok)
    }

    @Test
    fun encodeRefusesWhatCannotFit() {
        assertNull(plan(ExpeditionKind.CAMPAIGN, 1024, 5, ContractId.NONE).code)
        assertNull(plan(ExpeditionKind.CAMPAIGN, 5, 0, ContractId.NONE).code)
        assertNull(plan(ExpeditionKind.CAMPAIGN, 0, 5, ContractId.NONE).code)
        assertNull(plan(ExpeditionKind.CAMPAIGN, 5, 16_777_216, ContractId.NONE).code)
    }

    @Test
    fun seedSurvivesTheFloatRoundTrip() {
        for (seed in listOf(1, 2, 4242, 8_388_607, 8_388_608, 16_000_000, 16_777_215)) assertEquals(seed, seed.toFloat().toInt())
        for (seed in 16_777_000..16_777_215) assertEquals(seed, seed.toFloat().toInt())
    }
}
