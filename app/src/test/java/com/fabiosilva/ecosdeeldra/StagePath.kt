package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.MissionResult
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules

/** Vence o caminho da esquerda da árvore de fases (nós 1, 2, 4, 8...) até a profundidade [depth]. */
fun winLeftmostPath(p: Progress, depth: Int) {
    for (d in 1..depth) Rules.applyResult(p, Campaign.stage(1 shl (d - 1)), MissionResult(true, 60f, 100f, 100f, 0, 0, 0, 0))
}
