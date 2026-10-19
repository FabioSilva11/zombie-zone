package com.fabiosilva.ecosdeeldra.tutorial

import com.fabiosilva.ecosdeeldra.data.Difficulty

/** Regras puras de quem aprende, migração do save e ajuste leve da corrida com tutorial. */
object TutorialPolicy {
    const val VERSION = 1

    /** Máscara de lições já feitas a partir da qual ensinar; null = não ensinar. */
    fun maskToTeach(savedVersion: Int, savedMask: Int, completedStages: Int): Int? {
        if (completedStages > 0) return null                 // veterano: nunca interrompe
        val mask = if (savedVersion >= VERSION) savedMask and Lesson.ALL else 0
        return if (mask == Lesson.ALL) null else mask
    }

    fun isDone(savedVersion: Int, savedMask: Int) = savedVersion >= VERSION && (savedMask and Lesson.ALL) == Lesson.ALL

    data class Saved(val version: Int, val mask: Int)

    /** Save sem o objeto "tutorial" (versões antigas). `tutorialSeen` antigo NÃO prova aprendizado: só fase vencida prova. */
    fun migrate(hasTutorialObject: Boolean, version: Int, mask: Int, completedStages: Int): Saved = when {
        hasTutorialObject -> Saved(version, mask and Lesson.ALL)
        completedStages > 0 -> Saved(VERSION, Lesson.ALL)
        else -> Saved(0, 0)
    }

    /** Ajuste leve SÓ na corrida com tutorial: menos zumbis e dano; nunca depende de anúncios. */
    fun tune(d: Difficulty): Difficulty = d.copy(
        zombieCount = minOf(d.zombieCount, 4),
        zombieDamage = d.zombieDamage * 0.7f,
        playerHealth = d.playerHealth * 1.25f,
    )
}
