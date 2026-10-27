package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.ContractId
import com.fabiosilva.ecosdeeldra.data.ContractReward
import com.fabiosilva.ecosdeeldra.data.ContractRun
import com.fabiosilva.ecosdeeldra.data.Contracts
import com.fabiosilva.ecosdeeldra.data.EngineRules
import com.fabiosilva.ecosdeeldra.data.ExpeditionKind
import com.fabiosilva.ecosdeeldra.data.ExpeditionPlan
import com.fabiosilva.ecosdeeldra.data.Inventory
import com.fabiosilva.ecosdeeldra.data.Items
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.SurvivalResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class ContractsTest {
    private fun progressAt(stage: Int): Progress = Progress().apply {
        for (d in 1 until stage) record(1 shl (d - 1)).completed = true   // caminho da esquerda da árvore
        if (stage > 1) stats.wins = 1
    }

    private fun plan(id: ContractId, stage: Int = 10, kind: ExpeditionKind = ExpeditionKind.CAMPAIGN) =
        ExpeditionPlan(kind, stage, 1234, id)

    private fun joined(id: ContractId, kind: ExpeditionKind, stage: Int): String {
        val c = Contracts.card(id, kind, stage)
        return (c.risks + c.rewards + listOfNotNull(c.objective, c.summary)).joinToString("\n")
    }

    @Test
    fun catalogCoversEveryIdAndKindsAreConsistent() {
        for (id in ContractId.entries) assertEquals(id, Contracts.get(id).id)
        assertEquals(setOf(ExpeditionKind.CAMPAIGN), Contracts.get(ContractId.NONE).kinds)
        assertEquals(setOf(ExpeditionKind.SURVIVAL), Contracts.get(ContractId.ENDURANCE).kinds)
        assertEquals(ExpeditionKind.entries.toSet(), Contracts.get(ContractId.RED_NIGHT).kinds)
        assertEquals(ContractId.entries.size, Contracts.all.size)
    }

    @Test
    fun onlyTheEngineReadyContractsAreOffered() {
        assertTrue(Contracts.get(ContractId.RED_NIGHT).engineReady)
        assertTrue(Contracts.get(ContractId.SILENT_RECOVERY).engineReady)
        assertTrue(Contracts.get(ContractId.UNSTABLE_NEST).engineReady)    // 4C
        assertTrue(Contracts.get(ContractId.LOST_CRATE).engineReady)       // 4D
        assertEquals(ContractId.entries.toSet(), Contracts.all.filter { it.engineReady }.map { it.id }.toSet())
    }

    @Test
    fun cardNumbersComeFromTheSameTuningTheEngineReceives() {
        // Recuperação silenciosa, fase 2
        var r = Contracts.engineRules(ContractId.SILENT_RECOVERY, 2)
        var text = joined(ContractId.SILENT_RECOVERY, ExpeditionKind.CAMPAIGN, 2)
        assertEquals(0.55f, r.zombieCountMul, 0f)
        assertTrue(text, "45% menos zumbis" in text)
        assertTrue(text, "50% menos guardas" in text)
        assertEquals(3.2f, r.ammoClips, 0.0001f)
        assertTrue(text, "3,2 pentes" in text)
        assertTrue(text, "+0,5 pente" in text)
        assertTrue(text, "◆ +22 ao extrair" in text)           // 20 + 1*2
        assertTrue(text, "+25% sobre abates" in text)
        assertTrue(text, "até ◆ +60" in text && "−◆ 3 por zumbi" in text)
        // Recuperação silenciosa, fase 40 (reserva no teto)
        r = Contracts.engineRules(ContractId.SILENT_RECOVERY, 40)
        text = joined(ContractId.SILENT_RECOVERY, ExpeditionKind.CAMPAIGN, 40)
        assertEquals(7f, r.ammoClips, 0.0001f)
        assertTrue(text, "7,0 pentes" in text)
        assertTrue(text, "◆ +60 ao extrair" in text)
        // Noite vermelha
        for (stage in listOf(2, 40)) {
            r = Contracts.engineRules(ContractId.RED_NIGHT, stage)
            text = joined(ContractId.RED_NIGHT, ExpeditionKind.CAMPAIGN, stage)
            assertTrue(text, "45% do alcance" in text)
            assertTrue(text, "(+35%)" in text)
            assertTrue(text, "10% mais rápidos" in text)
            assertTrue(text, "15% mais fortes" in text)
            assertTrue(text, "+50% sobre abates" in text)
            assertTrue(r.redSky)
        }
        assertTrue(joined(ContractId.RED_NIGHT, ExpeditionKind.CAMPAIGN, 2).contains("◆ +29 ao extrair"))   // 25 + 2*2
        assertTrue(joined(ContractId.RED_NIGHT, ExpeditionKind.CAMPAIGN, 60).contains("◆ +125 ao extrair"))  // teto
    }

    @Test
    fun unstableNestAndLostCrateCardsUseTheEngineNumbers() {
        val nestRules = Contracts.engineRules(ContractId.UNSTABLE_NEST, 4)
        assertEquals(0.35f, nestRules.nestBomb, 0f)
        assertFalse(nestRules.cargo)
        val nest = joined(ContractId.UNSTABLE_NEST, ExpeditionKind.CAMPAIGN, 4)
        assertTrue(nest, "35% dos zumbis dos ninhos são Inchados" in nest)
        assertTrue(nest, "◆ +14 ao extrair" in nest)               // 10 + 1*4
        assertTrue(nest, "+30% sobre abates" in nest)
        assertTrue(nest, "módulo raro Núcleo instável" in nest)
        assertTrue(joined(ContractId.UNSTABLE_NEST, ExpeditionKind.CAMPAIGN, 60).contains("◆ +60 ao extrair"))   // teto

        val crateRules = Contracts.engineRules(ContractId.LOST_CRATE, 3)
        assertTrue(crateRules.cargo)
        assertEquals(0f, crateRules.nestBomb, 0f)
        val crate = joined(ContractId.LOST_CRATE, ExpeditionKind.CAMPAIGN, 3)
        assertTrue(crate, "entre ${Contracts.CARGO_MIN_START_M} e ${Contracts.CARGO_MAX_START_M} m da largada" in crate)
        assertTrue(crate, "a mais de ${Contracts.CARGO_NEST_CLEAR_M} m dos ninhos" in crate)
        assertTrue(crate, "◆ +13 ao extrair" in crate)             // 10 + 1*3
        assertTrue(crate, "Carga: ◆ +60" in crate)
        assertTrue(crate, "primeiro ninho" in crate)
    }

    @Test
    fun cargoPaysOnlyWhenCollectedBeforeVictory() {
        val p = plan(ContractId.LOST_CRATE, stage = 3)
        val got = Contracts.reward(p, true, 0, ContractRun(cargoCollected = true))
        val missed = Contracts.reward(p, true, 0, ContractRun(cargoCollected = false))
        assertEquals(60, got.objective)
        assertEquals(0, missed.objective)
        assertEquals(13, got.extraction)
        assertEquals(got.extraction, missed.extraction)
        assertEquals(ContractReward.NONE, Contracts.reward(p, false, 100, ContractRun(cargoCollected = true)))   // derrota não paga
        assertNull(got.moduleId)
    }

    @Test
    fun neutralContractsLeaveTheDifficultyUntouched() {
        val d = Campaign.difficulty(7, Progress())
        for (id in listOf(ContractId.NONE, ContractId.ENDURANCE)) {
            val r = Contracts.engineRules(id, 7)
            assertEquals(EngineRules(), r)
            assertEquals(d, r.applyTo(d))
        }
    }

    @Test
    fun redNightHardensZombiesButKeepsPopulation() {
        val d = Campaign.difficulty(12, Progress())
        val h = Contracts.engineRules(ContractId.RED_NIGHT, 12).applyTo(d)
        assertEquals(d.zombieCount, h.zombieCount)
        assertEquals(d.zombieSpeed * 1.10f, h.zombieSpeed, 0.0001f)
        assertEquals(d.zombieDamage * 1.15f, h.zombieDamage, 0.0001f)
        assertEquals(d.zombieHealth, h.zombieHealth, 0f)
    }

    @Test
    fun silentRecoveryScalesAmmoWithStageAndCapsIt() {
        assertEquals(3.2f, Contracts.engineRules(ContractId.SILENT_RECOVERY, 2).ammoClips, 0.0001f)
        assertEquals(7f, Contracts.engineRules(ContractId.SILENT_RECOVERY, 40).ammoClips, 0.0001f)
        assertEquals(7f, Contracts.engineRules(ContractId.SILENT_RECOVERY, 90).ammoClips, 0.0001f)
        val rules = Contracts.engineRules(ContractId.SILENT_RECOVERY, 1)
        val d = Campaign.difficulty(1, Progress())
        val reduced = rules.applyTo(d)
        assertTrue(reduced.zombieCount < d.zombieCount)
        assertTrue(reduced.zombieCount >= 3)
        assertEquals(3, rules.applyTo(d.copy(zombieCount = 3)).zombieCount)
    }

    @Test
    fun rewardOnlyOnVictory() {
        for (id in ContractId.entries) {
            val kind = if (ExpeditionKind.CAMPAIGN in Contracts.get(id).kinds) ExpeditionKind.CAMPAIGN else ExpeditionKind.SURVIVAL
            assertEquals(ContractReward.NONE, Contracts.reward(plan(id, kind = kind), false, 100, ContractRun()))
        }
        assertEquals(ContractReward.NONE, Contracts.reward(plan(ContractId.NONE), true, 100, ContractRun()))
    }

    @Test
    fun campaignRewardMatchesTheTable() {
        // Noite vermelha, fase 10, saque 70: 45 + 35
        val red = Contracts.reward(plan(ContractId.RED_NIGHT), true, 70, ContractRun())
        assertEquals(45, red.extraction); assertEquals(35, red.runBonus); assertEquals(0, red.objective); assertEquals(80, red.total)
        // Silenciosa, fase 10, saque 70, 8 alertas: 30 + 17 + 36
        val silent = Contracts.reward(plan(ContractId.SILENT_RECOVERY), true, 70, ContractRun(alerts = 8))
        assertEquals(30, silent.extraction); assertEquals(17, silent.runBonus); assertEquals(36, silent.objective); assertEquals(83, silent.total)
        // O nível da fase é limitado a 60 na fórmula
        assertEquals(125, Contracts.reward(plan(ContractId.RED_NIGHT, stage = 500), true, 0, ContractRun()).extraction)
    }

    @Test
    fun silenceBonusDropsThreePerAlertAndClamps() {
        val p = plan(ContractId.SILENT_RECOVERY)
        fun obj(alerts: Int) = Contracts.reward(p, true, 0, ContractRun(alerts = alerts)).objective
        assertEquals(60, obj(0)); assertEquals(36, obj(8)); assertEquals(0, obj(20)); assertEquals(0, obj(40))
    }

    @Test
    fun survivalRewardHasNoExtractionAndScalesWithRunBase() {
        val s = plan(ContractId.RED_NIGHT, stage = 6, kind = ExpeditionKind.SURVIVAL)
        val r = Contracts.reward(s, true, 120, ContractRun())
        assertEquals(0, r.extraction)
        assertEquals(60, r.runBonus)
        assertEquals(60, r.total)
        assertEquals(0, Contracts.reward(plan(ContractId.ENDURANCE, 1, ExpeditionKind.SURVIVAL), true, 120, ContractRun()).total)
    }

    @Test
    fun survivalCardMatchesRules() {
        val p = Progress()
        val summary = Rules.applySurvivalResult(p, "Ana", "2026-10-18", SurvivalResult(70f, 3, 10, 0, 100, 1, 0f))
        // onda 3 => 2 ondas vencidas, 10 abates, recorde >= 60 s
        assertEquals((3 - 1) * Contracts.SURVIVAL_WAVE_REWARD + 10 / Contracts.SURVIVAL_KILLS_PER_DIAMOND + Contracts.SURVIVAL_RECORD_BONUS, summary.reward)
        val text = joined(ContractId.ENDURANCE, ExpeditionKind.SURVIVAL, 1)
        assertTrue(text, "◆ 8 por onda vencida" in text && "1 a cada 2 abates" in text && "+◆ 30" in text && "(60 s" in text)
    }

    @Test
    fun checkRejectsLockedStageEngineNotReadyAndWrongKind() {
        val fresh = Progress()
        val far = plan(ContractId.NONE, stage = 60)
        val r = Contracts.check(far, fresh)
        assertFalse(r.ok)
        assertTrue(r.message!!, "fase 6" in r.message!!)

        assertTrue(Contracts.check(plan(ContractId.UNSTABLE_NEST, 4), progressAt(4)).ok)
        assertTrue(Contracts.check(plan(ContractId.LOST_CRATE, 3), progressAt(3)).ok)
        val tooEarlyNest = Contracts.check(plan(ContractId.UNSTABLE_NEST, 3), progressAt(3))   // libera na fase 4
        assertFalse(tooEarlyNest.ok); assertTrue(tooEarlyNest.message!!, "fase 4" in tooEarlyNest.message!!)
        val tooEarlyCrate = Contracts.check(plan(ContractId.LOST_CRATE, 2), progressAt(2))     // libera na fase 3
        assertFalse(tooEarlyCrate.ok); assertTrue(tooEarlyCrate.message!!, "fase 3" in tooEarlyCrate.message!!)
        val surv = Contracts.check(plan(ContractId.LOST_CRATE, 1, ExpeditionKind.SURVIVAL), progressAt(10))   // só campanha
        assertFalse(surv.ok)

        assertFalse(Contracts.check(plan(ContractId.ENDURANCE, 1), fresh).ok)                       // tipo errado
        assertFalse(Contracts.check(plan(ContractId.SILENT_RECOVERY, 1, ExpeditionKind.SURVIVAL), fresh).ok)

        val locked = Contracts.check(plan(ContractId.RED_NIGHT, stage = 2), progressAt(2))          // libera na fase 3
        assertFalse(locked.ok); assertTrue(locked.message!!, "fase 3" in locked.message!!)

        assertTrue(Contracts.check(plan(ContractId.RED_NIGHT, stage = 3), progressAt(3)).ok)
        assertTrue(Contracts.check(plan(ContractId.SILENT_RECOVERY, stage = 2), progressAt(5)).ok)
        assertTrue(Contracts.check(plan(ContractId.ENDURANCE, stage = 11, kind = ExpeditionKind.SURVIVAL), fresh).ok)
        assertFalse(Contracts.check(plan(ContractId.ENDURANCE, stage = 7, kind = ExpeditionKind.SURVIVAL), fresh).ok)  // bioma inválido
    }

    @Test
    fun unlocksFollowProgress() {
        val fresh = Progress()
        assertFalse(Contracts.prepUnlocked(fresh))
        val won = Progress().apply { stats.wins = 1 }
        assertTrue(Contracts.prepUnlocked(won))
        assertTrue(Contracts.prepUnlocked(progressAt(2)))

        val opts = Contracts.options(ExpeditionKind.CAMPAIGN, progressAt(2))
        assertEquals(listOf(ContractId.NONE, ContractId.SILENT_RECOVERY, ContractId.UNSTABLE_NEST, ContractId.RED_NIGHT, ContractId.LOST_CRATE), opts.map { it.def.id })
        assertFalse(opts[0].locked); assertFalse(opts[1].locked)
        assertTrue(opts[2].locked); assertEquals("Libera na fase 4", opts[2].lockReason)
        assertTrue(opts[3].locked); assertEquals("Libera na fase 3", opts[3].lockReason)
        assertTrue(opts[4].locked); assertEquals("Libera na fase 3", opts[4].lockReason)
        val surv = Contracts.options(ExpeditionKind.SURVIVAL, progressAt(2)).map { it.def.id }
        assertEquals(listOf(ContractId.RED_NIGHT, ContractId.ENDURANCE).sorted(), surv.sorted())
    }

    @Test
    fun defaultContractFollowsTheLastUsedWhenStillValid() {
        val p = progressAt(5)
        assertEquals(ContractId.NONE, Contracts.defaultContract(ExpeditionKind.CAMPAIGN, p))
        assertEquals(ContractId.ENDURANCE, Contracts.defaultContract(ExpeditionKind.SURVIVAL, p))
        p.expeditions.remember(plan(ContractId.RED_NIGHT))
        assertEquals(ContractId.RED_NIGHT, Contracts.defaultContract(ExpeditionKind.CAMPAIGN, p))
        p.expeditions.lastCampaign = ContractId.UNSTABLE_NEST    // libera na fase 4: com progresso 5 vale
        assertEquals(ContractId.UNSTABLE_NEST, Contracts.defaultContract(ExpeditionKind.CAMPAIGN, p))
        val early = progressAt(3).apply { expeditions.lastCampaign = ContractId.UNSTABLE_NEST }  // ainda bloqueado: volta ao padrão
        assertEquals(ContractId.NONE, Contracts.defaultContract(ExpeditionKind.CAMPAIGN, early))
        val low = progressAt(2).apply { expeditions.lastCampaign = ContractId.RED_NIGHT }
        assertEquals(ContractId.NONE, Contracts.defaultContract(ExpeditionKind.CAMPAIGN, low))   // bloqueado até a fase 3
    }

    @Test
    fun retryKeepsTheIslandOnlyForSharedCodes() {
        val own = plan(ContractId.NONE).copy(seed = 77)
        val again = own.retry(Random(1))
        assertTrue(again.seed in 1 until Contracts.SEED_RANDOM_MAX)
        assertTrue(again.seed != 77)
        assertFalse(again.sharedSeed)
        val shared = own.copy(sharedSeed = true)
        assertEquals(shared, shared.retry(Random(1)))
    }

    @Test
    fun survivalPlanKeepsTheBiomeDraw() {
        repeat(40) {
            val p = ExpeditionPlan.survival(random = Random(it))
            assertTrue(p.stage in listOf(1, 6, 11, 16))
            assertEquals(ExpeditionKind.SURVIVAL, p.kind)
            assertEquals(ContractId.ENDURANCE, p.contract)
        }
    }

    @Test
    fun loadoutAdviceUsesTheInventory() {
        val bare = Inventory()
        assertNotNull(Contracts.loadoutAdvice(ContractId.SILENT_RECOVERY, bare))
        assertNotNull(Contracts.loadoutAdvice(ContractId.RED_NIGHT, bare))
        assertNotNull(Contracts.loadoutAdvice(ContractId.UNSTABLE_NEST, bare))
        assertNotNull(Contracts.loadoutAdvice(ContractId.ENDURANCE, bare))
        val kit = Inventory().apply {
            add(Items.GRENADE, 2); add(Items.SMOKE, 1); add(Items.MEDKIT, 1)
            add(Items.RIFLE, 1); add(Items.SHOTGUN, 1)
        }
        assertNull(Contracts.loadoutAdvice(ContractId.SILENT_RECOVERY, kit))
        assertNull(Contracts.loadoutAdvice(ContractId.RED_NIGHT, kit))
        assertNull(Contracts.loadoutAdvice(ContractId.UNSTABLE_NEST, kit))
        assertNull(Contracts.loadoutAdvice(ContractId.LOST_CRATE, bare))
        assertNull(Contracts.loadoutAdvice(ContractId.ENDURANCE, kit))
        assertNull(Contracts.loadoutAdvice(ContractId.NONE, bare))
    }

    @Test
    fun briefingAndShareTextAreOnlyForContractsWithRules() {
        assertNotNull(Contracts.briefing(plan(ContractId.RED_NIGHT)))
        assertNotNull(Contracts.briefing(plan(ContractId.SILENT_RECOVERY)))
        assertNotNull(Contracts.briefing(plan(ContractId.UNSTABLE_NEST)))
        assertNotNull(Contracts.briefing(plan(ContractId.LOST_CRATE)))
        assertNull(Contracts.briefing(plan(ContractId.NONE)))
        assertNull(Contracts.briefing(plan(ContractId.ENDURANCE, 1, ExpeditionKind.SURVIVAL)))
        val text = Contracts.shareText(plan(ContractId.RED_NIGHT, stage = 12))!!
        assertTrue(text, plan(ContractId.RED_NIGHT, stage = 12).code!! in text)
        assertNull(Contracts.shareText(plan(ContractId.NONE, stage = 2000)))
    }

    @Test
    fun readmeDescribesTheContractsWithoutInventingRules() {
        val file = listOf("../README.md", "README.md").map(::File).firstOrNull { it.exists() }
        org.junit.Assume.assumeTrue("README.md não encontrado", file != null)
        val text = file!!.readText()
        assertTrue(text.contains("ZZ-XXXX-XXXX-XXXXX"))
        for (def in Contracts.all) {
            if (def.engineReady) assertTrue("README sem o contrato ${def.name}", text.contains(def.name))
        }
        // Os números do README são os do motor.
        assertTrue(text.contains("3,2 pentes de reserva"))
        assertTrue(text.contains("◆ 20 + 1 por fase (até 80)"))
        assertTrue(text.contains("◆ 25 + 2 por fase (até 125)"))
        assertTrue(text.contains("Ninho instável") && text.contains("Caixa perdida"))
        assertFalse(text.contains("ainda não estão disponíveis"))
        assertTrue(text.contains("Núcleo instável"))
        assertTrue(text.contains("450 a 1400 m"))
        assertFalse(text.contains("Dificuldade adaptativa"))
    }

    @Test
    fun noTextMentionsAdsOrDiamondCostsToStartAContract() {
        for (def in Contracts.all) for (kind in def.kinds) {
            val text = joined(def.id, kind, 5).lowercase()
            assertFalse(text, "assista" in text || "vídeo" in text || "custa" in text)
        }
    }
}
