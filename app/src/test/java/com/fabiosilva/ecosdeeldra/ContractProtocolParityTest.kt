package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.ContractId
import com.fabiosilva.ecosdeeldra.data.Contracts
import com.fabiosilva.ecosdeeldra.data.ExpeditionKind
import com.fabiosilva.ecosdeeldra.data.ExpeditionPlan
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.engine.Cfg
import com.fabiosilva.ecosdeeldra.engine.Ev
import com.fabiosilva.ecosdeeldra.engine.Hud
import com.fabiosilva.ecosdeeldra.engine.Res
import com.fabiosilva.ecosdeeldra.engine.applyContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Paridade C++ <-> Kotlin dos blocos de contrato (erro mais caro desta área: índice trocado entre linguagens, sem aparelho para ver).
 * Lê protocol.h, calcula os valores dos enums respeitando "= N" e a numeração sequencial.
 */
class ContractProtocolParityTest {
    private fun header(): String {
        val file = listOf("src/main/cpp/game/protocol.h", "app/src/main/cpp/game/protocol.h").map(::File).firstOrNull { it.exists() }
        assertNotNull("protocol.h não encontrado a partir de ${File(".").absolutePath}", file)
        return file!!.readText()
    }

    private fun enumValues(text: String, name: String): Map<String, Int> {
        val clean = text.lines().joinToString("\n") { it.substringBefore("//") }
        val m = Regex("""enum\s+$name\s*\{([^}]*)\}""").find(clean)
        assertNotNull("enum $name não encontrado", m)
        val out = linkedMapOf<String, Int>()
        var next = 0
        for (entry in m!!.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = entry.split('=').map { it.trim() }
            val value = if (parts.size == 2) parts[1].toIntOrNull() ?: error("valor não literal em $name: $entry") else next
            out[parts[0]] = value
            next = value + 1
        }
        return out
    }

    private val h = header()
    private val cfg = enumValues(h, "ConfigIndex")
    private val hud = enumValues(h, "HudIndex")
    private val ev = enumValues(h, "EventType")
    private val res = enumValues(h, "ResultIndex")

    @Test
    fun configBlockMatches() {
        val pairs = mapOf(
            "C_CONTRACT_ID" to Cfg.CONTRACT_ID, "C_AMMO_CLIPS" to Cfg.AMMO_CLIPS, "C_CHEST_AMMO" to Cfg.CHEST_AMMO,
            "C_VISION_MUL" to Cfg.VISION_MUL, "C_AGGRESSION" to Cfg.AGGRESSION, "C_RED_SKY" to Cfg.RED_SKY,
            "C_GUARD_MUL" to Cfg.GUARD_MUL, "C_NEST_BOMB" to Cfg.NEST_BOMB, "C_CARGO" to Cfg.CARGO, "C_COUNT" to Cfg.COUNT,
            // Blocos anteriores continuam onde estavam.
            "C_EXPLOSIVES" to Cfg.EXPLOSIVES, "C_PERF" to Cfg.PERF, "C_PERF_FRAMES" to Cfg.PERF_FRAMES,
        )
        for ((name, kotlin) in pairs) assertEquals(name, cfg.getValue(name), kotlin)
        assertTrue(Cfg.CARGO < Cfg.COUNT)
    }

    @Test
    fun hudBlockMatches() {
        val pairs = mapOf(
            "H_CT_ID" to Hud.CT_ID, "H_CT_RESERVE_ROUNDS" to Hud.CT_RESERVE_ROUNDS, "H_CT_RESERVE_CLIPS" to Hud.CT_RESERVE_CLIPS,
            "H_CT_ALERTS" to Hud.CT_ALERTS, "H_CT_CARGO_STATE" to Hud.CT_CARGO_STATE, "H_CT_CARGO_X" to Hud.CT_CARGO_X,
            "H_CT_CARGO_Z" to Hud.CT_CARGO_Z, "H_CT_CARGO_SX" to Hud.CT_CARGO_SX, "H_CT_CARGO_SY" to Hud.CT_CARGO_SY,
            "H_CT_CARGO_ON" to Hud.CT_CARGO_ON, "H_CT_BOMBS" to Hud.CT_BOMBS, "H_SIZE" to Hud.SIZE,
            "H_CRYSTAL_ITEMS" to Hud.CRYSTAL_ITEMS, "H_PERF_FLAGS" to Hud.PERF_FLAGS,
        )
        for ((name, kotlin) in pairs) assertEquals(name, hud.getValue(name), kotlin)
        assertTrue(Hud.CT_BOMBS < Hud.SIZE)
    }

    @Test
    fun eventAndResultBlocksMatch() {
        assertEquals(ev.getValue("EV_CONTRACT"), Ev.CONTRACT)
        assertEquals(ev.getValue("EV_WAVE"), Ev.WAVE)
        val pairs = mapOf(
            "R_ALERTS" to Res.ALERTS, "R_CARGO" to Res.CARGO, "R_RESERVE_PCT" to Res.RESERVE_PCT, "R_BOMBS" to Res.BOMBS,
            "R_SIZE" to Res.SIZE, "R_SCORE" to Res.SCORE, "R_NESTS" to Res.NESTS,
        )
        for ((name, kotlin) in pairs) assertEquals(name, res.getValue(name), kotlin)
        assertTrue(Res.BOMBS < Res.SIZE)
    }

    @Test
    fun contractBlocksDoNotCollideWithOtherAreas() {
        // Nenhum índice conhecido do HUD cai dentro de 700..719 a não ser os do contrato (e vice-versa).
        val ownHud = hud.filterKeys { it.startsWith("H_CT_") }.values.toSet()
        for ((name, v) in hud) if (name != "H_SIZE" && !name.startsWith("H_CT_")) assertTrue("$name=$v invade o bloco de contrato", v !in 700..719)
        assertEquals(11, ownHud.size)
        val ownCfg = cfg.filterKeys { it in setOf("C_CONTRACT_ID", "C_AMMO_CLIPS", "C_CHEST_AMMO", "C_VISION_MUL", "C_AGGRESSION", "C_RED_SKY", "C_GUARD_MUL", "C_NEST_BOMB", "C_CARGO") }.values
        for ((name, v) in cfg) if (name != "C_COUNT" && v !in ownCfg) assertTrue("$name=$v invade 40..55", v !in 40..55)
        for ((name, v) in res) if (name != "R_SIZE" && !name.matches(Regex("R_(ALERTS|CARGO|RESERVE_PCT|BOMBS)"))) assertTrue("$name=$v invade 24..31", v !in 24..31)
    }

    @Test
    fun engineConfigWritesTheContractBlock() {
        val red = FloatArray(Cfg.COUNT)
        red.applyContract(ExpeditionPlan(ExpeditionKind.CAMPAIGN, 3, 5, ContractId.RED_NIGHT), Contracts.engineRules(ContractId.RED_NIGHT, 3))
        assertEquals(3f, red[Cfg.CONTRACT_ID], 0f)
        assertEquals(1f, red[Cfg.RED_SKY], 0f)
        assertEquals(0.45f, red[Cfg.VISION_MUL], 0f)
        assertEquals(1.35f, red[Cfg.AGGRESSION], 0f)
        assertEquals(0f, red[Cfg.AMMO_CLIPS], 0f)

        val silent = FloatArray(Cfg.COUNT)
        silent.applyContract(ExpeditionPlan(ExpeditionKind.CAMPAIGN, 2, 5, ContractId.SILENT_RECOVERY), Contracts.engineRules(ContractId.SILENT_RECOVERY, 2))
        assertEquals(1f, silent[Cfg.CONTRACT_ID], 0f)
        assertEquals(3.2f, silent[Cfg.AMMO_CLIPS], 0.0001f)
        assertEquals(50f, silent[Cfg.CHEST_AMMO], 0f)
        assertEquals(0.5f, silent[Cfg.GUARD_MUL], 0f)
        assertEquals(0f, silent[Cfg.RED_SKY], 0f)

        val none = FloatArray(Cfg.COUNT)
        none.applyContract(ExpeditionPlan.standard(4), Contracts.engineRules(ContractId.NONE, 4))
        assertEquals(0f, none[Cfg.CONTRACT_ID], 0f)
        for (i in listOf(Cfg.AMMO_CLIPS, Cfg.CHEST_AMMO, Cfg.RED_SKY, Cfg.NEST_BOMB, Cfg.CARGO)) assertEquals(0f, none[i], 0f)
        for (i in listOf(Cfg.VISION_MUL, Cfg.AGGRESSION, Cfg.GUARD_MUL)) assertEquals(1f, none[i], 0f)   // neutro (o JNI trata 0 e 1 igual)
    }

    @Test
    fun deferredContractsWriteTheirEngineSettings() {
        val nest = FloatArray(Cfg.COUNT)
        nest.applyContract(ExpeditionPlan(ExpeditionKind.CAMPAIGN, 4, 5, ContractId.UNSTABLE_NEST), Contracts.engineRules(ContractId.UNSTABLE_NEST, 4))
        assertEquals(2f, nest[Cfg.CONTRACT_ID], 0f)
        assertEquals(0.35f, nest[Cfg.NEST_BOMB], 0f)
        assertEquals(0f, nest[Cfg.CARGO], 0f)
        val crate = FloatArray(Cfg.COUNT)
        crate.applyContract(ExpeditionPlan(ExpeditionKind.CAMPAIGN, 3, 5, ContractId.LOST_CRATE), Contracts.engineRules(ContractId.LOST_CRATE, 3))
        assertEquals(4f, crate[Cfg.CONTRACT_ID], 0f)
        assertEquals(1f, crate[Cfg.CARGO], 0f)
        assertEquals(0f, crate[Cfg.NEST_BOMB], 0f)
    }

    @Test
    fun cargoConstantsMatchTheNativeRules() {
        val file = listOf("src/main/cpp/game/contract_rules.h", "app/src/main/cpp/game/contract_rules.h").map(::File).firstOrNull { it.exists() }
        assertNotNull("contract_rules.h não encontrado", file)
        val text = file!!.readText()
        fun const(name: String): Float {
            val m = Regex("constexpr[ ]+float[ ]+" + name + "[ ]*=[ ]*([0-9.]+)f").find(text)
            assertNotNull("$name não encontrado em contract_rules.h", m)
            return m!!.groupValues[1].toFloat()
        }
        assertEquals(Contracts.CARGO_MIN_START_M.toFloat(), const("kCargoMinStartM"), 0f)
        assertEquals(Contracts.CARGO_MAX_START_M.toFloat(), const("kCargoMaxStartM"), 0f)
        assertEquals(Contracts.CARGO_NEST_CLEAR_M.toFloat(), const("kCargoNestClearM"), 0f)
        assertTrue(const("kCargoPickupM") in 1f..3f)
        // O plano B só pode ser mais largo que a janela principal.
        assertTrue(const("kCargoRelaxMinStartM") <= const("kCargoMinStartM"))
        assertTrue(const("kCargoRelaxMaxStartM") >= const("kCargoMaxStartM"))
        assertTrue(const("kCargoRelaxNestClearM") <= const("kCargoNestClearM"))
        // O autoteste de depuração cobre a caixa (janelas, determinismo e vetores dourados).
        assertTrue(text.contains("cargoSiteOk(450.0f") && text.contains("cargoHash(12345u)"))
    }

    @Test
    fun neutralPlanLeavesTheCampaignDifficultyIdentical() {
        val d = Campaign.difficulty(5, Progress())
        assertEquals(d, Contracts.engineRules(ContractId.NONE, 5).applyTo(d))
    }
}
