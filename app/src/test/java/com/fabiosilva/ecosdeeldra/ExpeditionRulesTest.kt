package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.ContractId
import com.fabiosilva.ecosdeeldra.data.ContractRun
import com.fabiosilva.ecosdeeldra.data.Contracts
import com.fabiosilva.ecosdeeldra.data.ExpeditionKind
import com.fabiosilva.ecosdeeldra.data.ExpeditionOutcome
import com.fabiosilva.ecosdeeldra.data.ExpeditionPlan
import com.fabiosilva.ecosdeeldra.data.ExpeditionRules
import com.fabiosilva.ecosdeeldra.data.MissionResult
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.SurvivalResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpeditionRulesTest {
    private fun result(victory: Boolean) =
        MissionResult(victory, 120f, 80f, 100f, 12, 1, 0b111, bestCombo = 2, chests = 2, chestDiamonds = 15, distance = 900f, nests = 3)

    private fun plan(id: ContractId, stage: Int = 2) = ExpeditionPlan(ExpeditionKind.CAMPAIGN, stage, 123456, id)

    @Test
    fun campaignVictoryCreditsBaseAndContract() {
        val p = Progress().apply { record(1).completed = true; stats.wins = 1 }
        val s = ExpeditionRules.applyCampaign(p, plan(ContractId.RED_NIGHT), Campaign.stage(2), result(true), ContractRun(), "2026-10-18")
        assertTrue(s.contract.total > 0)
        assertEquals(s.base.total + s.contract.total, s.total)
        assertEquals(s.total, p.diamonds)
        assertEquals(1, p.expeditions.records.size)
        assertEquals(ExpeditionOutcome.VICTORY, p.expeditions.records[0].outcome)
        assertEquals(s.contract.total, p.expeditions.records[0].contractDiamonds)
        assertEquals(s.total, p.expeditions.records[0].totalDiamonds)
        assertEquals(1, p.expeditions.winsOf(ContractId.RED_NIGHT))
    }

    @Test
    fun campaignDefeatKeepsBaseRewardsAndPaysNoContract() {
        val p = Progress()
        val s = ExpeditionRules.applyCampaign(p, plan(ContractId.RED_NIGHT), Campaign.stage(2), result(false), ContractRun(), "2026-10-18")
        assertEquals(0, s.contract.total)
        assertEquals(s.base.total, p.diamonds)
        assertEquals(ExpeditionOutcome.DEFEAT, p.expeditions.records[0].outcome)
        assertEquals(1, p.stages[2]!!.fails)
        assertEquals(1, p.stats.losses)
    }

    @Test
    fun doublingNeverIncludesTheContractBonus() {
        val p = Progress().apply { record(1).completed = true; stats.wins = 1 }
        val c = ExpeditionRules.applyCampaign(p, plan(ContractId.SILENT_RECOVERY), Campaign.stage(2), result(true), ContractRun(alerts = 1), "d")
        assertTrue(c.contract.total > 0)
        assertEquals(c.base.total, c.doublable)
        val sp = ExpeditionPlan(ExpeditionKind.SURVIVAL, 6, 5, ContractId.RED_NIGHT)
        val sv = ExpeditionRules.applySurvival(Progress(), sp, "Ana", "d", SurvivalResult(90f, 4, 20, 0, 10, 1, 0f), ContractRun())
        assertEquals(sv.base.reward, sv.doublable)
        assertTrue(sv.contract.total > 0)
        assertEquals(sv.base.reward + sv.contract.total, sv.total)
    }

    @Test
    fun survivalContractAddsPercentOfWaveReward() {
        val p = Progress()
        val sp = ExpeditionPlan(ExpeditionKind.SURVIVAL, 1, 5, ContractId.RED_NIGHT)
        val s = ExpeditionRules.applySurvival(p, sp, "Ana", "2026-10-18", SurvivalResult(70f, 6, 30, 0, 10, 1, 0f), ContractRun())
        assertEquals(s.base.reward * 50 / 100, s.contract.total)
        assertEquals(s.base.reward + s.contract.total, p.diamonds)
        assertEquals(ExpeditionKind.SURVIVAL, p.expeditions.records[0].kind)
        val plain = Progress()
        val e = ExpeditionRules.applySurvival(plain, ExpeditionPlan(ExpeditionKind.SURVIVAL, 1, 5, ContractId.ENDURANCE), "Ana", "d", SurvivalResult(70f, 6, 30, 0, 10, 1, 0f), ContractRun())
        assertEquals(0, e.contract.total)
        assertEquals(s.base.reward, e.base.reward)
    }

    @Test
    fun stageProgressionIsUnaffectedByTheContract() {
        val a = Progress(); val b = Progress()
        val sa = ExpeditionRules.applyCampaign(a, plan(ContractId.NONE, 1), Campaign.stage(1), result(true), ContractRun(), "d")
        val sb = ExpeditionRules.applyCampaign(b, plan(ContractId.RED_NIGHT, 1), Campaign.stage(1), result(true), ContractRun(), "d")
        assertEquals(sa.base, sb.base)
        assertEquals(a.stages[1]!!.stars, b.stages[1]!!.stars)
        assertEquals(a.highestUnlockedStage(), b.highestUnlockedStage())
        // E o resultado base é o mesmo da regra original.
        val ref = Progress()
        assertEquals(sa.base, Rules.applyResult(ref, Campaign.stage(1), result(true)))
    }

    @Test
    fun unstableNestAwardsModuleOnlyWhenAllNestsFall() {
        val p = Progress()
        ExpeditionRules.applyCampaign(p, plan(ContractId.UNSTABLE_NEST, 4), Campaign.stage(4), result(true), ContractRun(nestsDestroyed = 2, nestsTotal = 3), "d")
        assertTrue(p.expeditions.modules.isEmpty())
        ExpeditionRules.applyCampaign(p, plan(ContractId.UNSTABLE_NEST, 4), Campaign.stage(4), result(true), ContractRun(nestsDestroyed = 3, nestsTotal = 3), "d")
        assertEquals(1, p.expeditions.modules["nucleo_instavel"])
        // Derrota não concede o módulo, mesmo com todos os ninhos destruídos.
        val d = Progress()
        ExpeditionRules.applyCampaign(d, plan(ContractId.UNSTABLE_NEST, 4), Campaign.stage(4), result(false), ContractRun(nestsDestroyed = 3, nestsTotal = 3), "d")
        assertTrue(d.expeditions.modules.isEmpty())
        // E o módulo vai para o resumo mostrado na tela de resultado.
        val s = ExpeditionRules.applyCampaign(Progress(), plan(ContractId.UNSTABLE_NEST, 4), Campaign.stage(4), result(true), ContractRun(nestsDestroyed = 3, nestsTotal = 3), "d")
        assertEquals(Contracts.MODULE_UNSTABLE_CORE, s.contract.moduleId)
    }

    @Test
    fun lostCratePaysTheObjectiveOnlyWhenCollected() {
        val a = Progress()
        val sa = ExpeditionRules.applyCampaign(a, plan(ContractId.LOST_CRATE, 3), Campaign.stage(3), result(true), ContractRun(cargoCollected = true), "d")
        val b = Progress()
        val sb = ExpeditionRules.applyCampaign(b, plan(ContractId.LOST_CRATE, 3), Campaign.stage(3), result(true), ContractRun(cargoCollected = false), "d")
        assertEquals(60, sa.contract.objective)
        assertEquals(0, sb.contract.objective)
        assertEquals(60, a.diamonds - b.diamonds)
        assertEquals(sa.base, sb.base)
        assertTrue(a.expeditions.modules.isEmpty())
    }
}
