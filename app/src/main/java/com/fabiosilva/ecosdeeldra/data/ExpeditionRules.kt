package com.fabiosilva.ecosdeeldra.data

data class ExpeditionSummary(val base: RewardSummary, val contract: ContractReward, val record: ExpeditionRecord) {
    val total: Int get() = base.total + contract.total
    /** O que o anúncio pode dobrar. O bônus do contrato NUNCA entra. */
    val doublable: Int get() = base.total
}

data class SurvivalExpeditionSummary(val base: SurvivalSummary, val contract: ContractReward, val record: ExpeditionRecord) {
    val total: Int get() = base.reward + contract.total
    val doublable: Int get() = base.reward
}

/** Aplica o contrato POR CIMA de [Rules]: applyResult/applySurvivalResult e MissionResult ficam intactos. */
object ExpeditionRules {
    fun applyCampaign(p: Progress, plan: ExpeditionPlan, stage: Stage, r: MissionResult, run: ContractRun, day: String): ExpeditionSummary {
        val base = Rules.applyResult(p, stage, r)               // já credita base.total em p.diamonds
        val runBase = base.killBonus + base.chestBonus + base.exploreBonus
        val bonus = Contracts.reward(plan, r.victory, runBase, run)
        p.diamonds += bonus.total
        bonus.moduleId?.let { p.expeditions.grantModule(it) }
        val record = ExpeditionRecord(
            plan.code ?: "-", plan.kind, plan.stage, plan.contract,
            if (r.victory) ExpeditionOutcome.VICTORY else ExpeditionOutcome.DEFEAT,
            r.elapsedSeconds.toInt(), r.kills, run.alerts, bonus.total, base.total + bonus.total, day,
        )
        p.expeditions.add(record)
        return ExpeditionSummary(base, bonus, record)
    }

    fun applySurvival(p: Progress, plan: ExpeditionPlan, name: String, day: String, r: SurvivalResult, run: ContractRun): SurvivalExpeditionSummary {
        val base = Rules.applySurvivalResult(p, name, day, r)   // já credita base.reward
        val bonus = Contracts.reward(plan, victory = true, runBase = base.reward, run = run)
        p.diamonds += bonus.total
        val record = ExpeditionRecord(
            plan.code ?: "-", plan.kind, plan.stage, plan.contract, ExpeditionOutcome.VICTORY,
            r.seconds.toInt(), r.kills, run.alerts, bonus.total, base.reward + bonus.total, day,
        )
        p.expeditions.add(record)
        return SurvivalExpeditionSummary(base, bonus, record)
    }
}
