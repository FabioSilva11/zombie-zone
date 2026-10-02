package com.fabiosilva.ecosdeeldra.data

import kotlin.math.min

data class Zone(val index: Int, val name: String, val color: Int)

data class Stage(
    val number: Int,
    val title: String,
    val zone: Zone,
    val speaker: String,
    val story: List<String>,
    val targetSeconds: Int,
    val firstClearDiamonds: Int,
) {
    val crystalTotal: Int get() = 3
}

data class Difficulty(
    val zombieCount: Int,
    val zombieHealth: Float,
    val zombieDamage: Float,
    val zombieSpeed: Float,
    val playerHealth: Float,
    val magazine: Int,
    val power: Float,
    val reloadSpeed: Float,
)

object Campaign {
    val zones = listOf(
        Zone(0, "Floresta", 0xFF7BD34A.toInt()),
        Zone(1, "Deserto", 0xFFF2B84B.toInt()),
        Zone(2, "Neve", 0xFF9FD8FF.toInt()),
        Zone(3, "Terra Devastada", 0xFFFF5E5E.toInt()),
    )

    private val titles = listOf(
        "Primeiro Alarme", "Trilha Sombria", "Acampamento Perdido", "Bosque dos Gemidos", "Coração da Floresta",
        "Dunas Ardentes", "Oásis Infectado", "Cânion Vermelho", "Tempestade de Areia", "Templo Soterrado",
        "Lago Congelado", "Picos Uivantes", "Vila Nevada", "Nevasca Final", "Fortaleza de Gelo",
        "Cinzas Vermelhas", "Rio de Magma", "Céu Sem Lua", "Ninho da Horda", "Coração da Zona Z",
    )

    private val lines = listOf(
        "Os acampamentos perto da base foram tomados. Limpe a área!",
        "Siga a trilha de terra: ela leva aos acampamentos infectados.",
        "Um grupo de sobreviventes sumiu por aqui. Procure pistas.",
        "Ouço gemidos entre as árvores. Fique atento aos flancos.",
        "O portal de obsidiana se abre quando a horda cair.",
    )

    val stages: List<Stage> = titles.mapIndexed { i, title ->
        val number = i + 1
        val zone = zones[i / 5]
        Stage(
            number = number,
            title = title,
            zone = zone,
            speaker = if (number % 2 == 1) "Nyra" else "Kael",
            story = listOf(
                "Zona ${zone.name} • Fase $number",
                if (number == 20) "Este é o coração da invasão. Acabe com tudo e atravesse o último portal!" else lines[i % lines.size],
            ),
            targetSeconds = 150 + number * 8,
            firstClearDiamonds = 40 + number * 10,
        )
    }

    fun stage(number: Int): Stage = stages[(number - 1).coerceIn(0, stages.size - 1)]

    fun difficulty(stageNumber: Int, vitality: Int, power: Int, reload: Int): Difficulty {
        val step = (stageNumber.coerceIn(1, 20) - 1)
        return Difficulty(
            zombieCount = 8 + step,
            zombieHealth = 60f + step * 12f,
            zombieDamage = 8f + (step * 0.6f).toInt(),
            zombieSpeed = 1f + min(0.45f, step * 0.022f),
            playerHealth = 100f + step * 12f + vitality.coerceIn(0, 5) * 20f,
            magazine = 30 + step * 2,
            power = 1f + power.coerceIn(0, 5) * 0.15f,
            reloadSpeed = 1f + reload.coerceIn(0, 5) * 0.12f,
        )
    }
}
