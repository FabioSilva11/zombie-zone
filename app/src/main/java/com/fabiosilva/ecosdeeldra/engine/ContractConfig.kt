package com.fabiosilva.ecosdeeldra.engine

import com.fabiosilva.ecosdeeldra.data.EngineRules
import com.fabiosilva.ecosdeeldra.data.ExpeditionPlan

/** Escreve o bloco de contrato no FloatArray de startMission (valores neutros = 0). */
fun FloatArray.applyContract(plan: ExpeditionPlan, rules: EngineRules) {
    this[Cfg.CONTRACT_ID] = plan.contract.code.toFloat()
    this[Cfg.AMMO_CLIPS] = rules.ammoClips
    this[Cfg.CHEST_AMMO] = rules.chestAmmoPct.toFloat()
    this[Cfg.VISION_MUL] = rules.visionMul
    this[Cfg.AGGRESSION] = rules.aggression
    this[Cfg.RED_SKY] = if (rules.redSky) 1f else 0f
    this[Cfg.GUARD_MUL] = rules.guardMul
    this[Cfg.NEST_BOMB] = rules.nestBomb
    this[Cfg.CARGO] = if (rules.cargo) 1f else 0f
}
