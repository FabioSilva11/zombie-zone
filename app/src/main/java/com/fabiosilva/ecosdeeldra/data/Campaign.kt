package com.fabiosilva.ecosdeeldra.data

import kotlin.math.min

/** Zona de 5 fases. Os 4 biomes se repetem em ciclos cada vez mais difíceis (II, III...). */
data class Zone(val index: Int, val name: String, val color: Int, val biome: Int) {
    val cycle: Int get() = index / 4
}

data class Stage(
    val number: Int,
    val title: String,
    val zone: Zone,
    val speaker: String,
    val story: List<String>,
    val targetSeconds: Int,
    val firstClearDiamonds: Int,
) {
    /** Cristais = ninhos de infecção a destruir na ilha. */
    val crystalTotal: Int get() = 3
    val islandKm: String get() = "5,1 km"
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

/** Tipos de zumbi (mesma ordem de ZombieKind no C++). */
data class ZombieInfo(val kind: Int, val name: String, val ability: String, val minStage: Int, val color: Int)

object ZombieKinds {
    val all = listOf(
        ZombieInfo(0, "Errante", "Anda em bando e ataca de perto.", 1, 0xFF8FD46B.toInt()),
        ZombieInfo(1, "Corredor", "Muito rápido e dá botes de longe.", 1, 0xFF9DB4FF.toInt()),
        ZombieInfo(2, "Brutamontes", "Gigante: soca o chão e arremessa você.", 3, 0xFF6E9C5E.toInt()),
        ZombieInfo(3, "Arremessador", "Atira pedras e mantém distância.", 2, 0xFFB8B4A6.toInt()),
        ZombieInfo(4, "Incendiário", "Lança coquetéis molotov que deixam fogo no chão.", 3, 0xFFFF9A4D.toInt()),
        ZombieInfo(5, "Retalhador", "Avança com uma faca e causa sangramento.", 2, 0xFFE07A7A.toInt()),
        ZombieInfo(6, "Inchado", "Corre até você e explode — mate de longe!", 4, 0xFFB6FF5C.toInt()),
        ZombieInfo(7, "Gritador", "Grita, chama a horda e a deixa furiosa.", 5, 0xFFD6B8FF.toInt()),
        ZombieInfo(8, "Cuspidor", "Cospe ácido que deixa poças corrosivas.", 6, 0xFFE4FF6B.toInt()),
        ZombieInfo(9, "Atirador de facas", "Lança facas rápidas em linha reta.", 7, 0xFF9CD8FF.toInt()),
    )

    fun get(kind: Int): ZombieInfo = all[kind.coerceIn(0, all.size - 1)]
    fun available(stage: Int): List<ZombieInfo> = all.filter { it.minStage <= stage }
    fun newAt(stage: Int): List<ZombieInfo> = all.filter { it.minStage == stage }
}

object Campaign {
    const val STAGES_PER_ZONE = 5

    private val biomeNames = listOf("Floresta", "Deserto", "Neve", "Terra Devastada")
    private val biomeColors = listOf(0xFF7BD34A.toInt(), 0xFFF2B84B.toInt(), 0xFF9FD8FF.toInt(), 0xFFFF5E5E.toInt())
    private val romans = listOf("", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X")

    private val firstTitles = listOf(
        "Primeiro Alarme", "Trilha Sombria", "Acampamento Perdido", "Bosque dos Gemidos", "Coração da Floresta",
        "Dunas Ardentes", "Oásis Infectado", "Cânion Vermelho", "Tempestade de Areia", "Templo Soterrado",
        "Lago Congelado", "Picos Uivantes", "Vila Nevada", "Nevasca Final", "Fortaleza de Gelo",
        "Cinzas Vermelhas", "Rio de Magma", "Céu Sem Lua", "Ninho da Horda", "Coração da Zona Z",
    )

    // Títulos procedurais depois da fase 20: lugar do bioma + complemento.
    private val places = listOf(
        listOf("Bosque", "Clareira", "Trilha", "Vale", "Mata", "Colina", "Riacho", "Pântano"),
        listOf("Dunas", "Cânion", "Oásis", "Mesetas", "Ruínas", "Miragem", "Desfiladeiro", "Caravana"),
        listOf("Geleira", "Picos", "Lago", "Fiorde", "Nevasca", "Cabanas", "Encosta", "Abismo"),
        listOf("Cratera", "Cinzas", "Rio de Lava", "Planície", "Cicatriz", "Fornalha", "Escombros", "Fendas"),
    )
    private val complements = listOf(
        "das Sombras", "do Silêncio", "dos Gemidos", "da Névoa", "do Último Grito", "da Horda", "dos Esquecidos",
        "do Eclipse", "da Febre", "dos Ossos", "do Fim", "da Lua Vermelha", "dos Uivos", "da Infecção",
    )

    private val lines = listOf(
        "Três ninhos de infecção estão espalhando a praga pela ilha. Destrua todos!",
        "A ilha é enorme: siga as trilhas, suba nas torres de vigia e procure baús escondidos.",
        "Zumbis com facas e molotovs foram vistos perto dos acampamentos. Cuidado.",
        "Os cristais dos ninhos abrem o portal de extração. Sem eles, ninguém sai daqui.",
        "Ouço gritos ao longe. Se um Gritador te achar, a horda inteira vem junto.",
        "Explore: cada ruína e cada depósito pode guardar diamantes.",
    )

    fun zone(stageNumber: Int): Zone {
        val index = (stageNumber.coerceAtLeast(1) - 1) / STAGES_PER_ZONE
        val biome = index % 4
        val cycle = index / 4
        return Zone(index, biomeNames[biome] + romans[min(cycle, romans.size - 1)], biomeColors[biome], biome)
    }

    fun stage(number: Int): Stage {
        val n = number.coerceAtLeast(1)
        val zone = zone(n)
        val title = if (n <= firstTitles.size) firstTitles[n - 1] else {
            val h = (n * 2654435761L).toInt() ushr 1
            "${places[zone.biome][h % places[zone.biome].size]} ${complements[(h / 7) % complements.size]}"
        }
        val fresh = ZombieKinds.newAt(n)
        val story = buildList {
            add("Zona ${zone.index + 1} • ${zone.name} • Fase $n")
            add(lines[(n - 1) % lines.size])
            if (fresh.isNotEmpty()) add("Novo inimigo: ${fresh.joinToString { it.name }} — ${fresh.first().ability}")
        }
        return Stage(
            number = n,
            title = title,
            zone = zone,
            speaker = if (n % 2 == 1) "Nyra" else "Kael",
            story = story,
            targetSeconds = 360 + min(n, 40) * 9,
            firstClearDiamonds = 40 + min(n, 60) * 10,
        )
    }

    fun difficulty(stageNumber: Int, vitality: Int, power: Int, reload: Int): Difficulty {
        val step = stageNumber.coerceAtLeast(1) - 1
        val late = (step - 19).coerceAtLeast(0)
        return Difficulty(
            zombieCount = (8 + min(step, 30) * 0.6f + late / 10).toInt().coerceAtMost(30),
            zombieHealth = 60f + min(step, 19) * 12f + late * 4f,
            zombieDamage = (8f + min(step, 40) * 0.6f + (step - 40).coerceAtLeast(0) * 0.25f).coerceAtMost(50f),
            zombieSpeed = 1f + min(0.45f, step * 0.022f),
            playerHealth = 100f + min(step, 40) * 12f + vitality.coerceIn(0, 5) * 20f,
            magazine = 30 + min(step, 30) * 2,
            // Armas melhores a cada fase: o dano acompanha a vida dos zumbis nas fases infinitas.
            power = (1f + power.coerceIn(0, 5) * 0.15f) * (1f + min(late, 60) * 0.02f),
            reloadSpeed = 1f + reload.coerceIn(0, 5) * 0.12f,
        )
    }
}
