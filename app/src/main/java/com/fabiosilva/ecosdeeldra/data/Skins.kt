package com.fabiosilva.ecosdeeldra.data

/** Como uma skin é liberada. Objetivos acompanham estatísticas de longo prazo do jogador. */
enum class UnlockKind {
    FREE, DIAMONDS, KILLS, HEADSHOTS, STAGE, STARS, CRYSTALS, PLAYTIME_MIN, ADS, LOGIN_DAYS
}

data class SkinDef(
    val index: Int,
    val name: String,
    val role: String,
    val unlock: UnlockKind,
    val goal: Int,
    /** Preço alternativo em diamantes (0 = só pelo objetivo). */
    val price: Int,
) {
    val letter: Char get() = 'a' + index
}

object Skins {
    val all = listOf(
        SkinDef(0, "Kael", "Batedor", UnlockKind.FREE, 0, 0),
        SkinDef(1, "Mira", "Atiradora", UnlockKind.DIAMONDS, 150, 150),
        SkinDef(2, "Brann", "Caçador", UnlockKind.KILLS, 40, 300),
        SkinDef(3, "Nyla", "Exploradora", UnlockKind.LOGIN_DAYS, 2, 0),
        SkinDef(4, "Toren", "Guarda", UnlockKind.DIAMONDS, 250, 250),
        SkinDef(5, "Zuri", "Sobrevivente", UnlockKind.ADS, 3, 0),
        SkinDef(6, "Lyra", "Patrulheira", UnlockKind.STAGE, 5, 0),
        SkinDef(7, "Dax", "Franco-atirador", UnlockKind.HEADSHOTS, 50, 450),
        SkinDef(8, "Elara", "Médica", UnlockKind.LOGIN_DAYS, 5, 0),
        SkinDef(9, "Orin", "Engenheiro", UnlockKind.DIAMONDS, 400, 400),
        SkinDef(10, "Vex", "Mercenário", UnlockKind.ADS, 8, 0),
        SkinDef(11, "Runa", "A Infectada", UnlockKind.KILLS, 250, 700),
        SkinDef(12, "Grom", "Tanque", UnlockKind.STARS, 30, 600),
        SkinDef(13, "Soren", "Veterano", UnlockKind.PLAYTIME_MIN, 60, 600),
        SkinDef(14, "Iris", "Morta-viva", UnlockKind.CRYSTALS, 30, 700),
        SkinDef(15, "Kiro", "Lendário", UnlockKind.LOGIN_DAYS, 7, 0),
        SkinDef(16, "Atlas", "Comandante", UnlockKind.STAGE, 15, 0),
        SkinDef(17, "Nova", "Estrela da Zona", UnlockKind.ADS, 15, 0),
    )

    fun get(index: Int): SkinDef = all[index.coerceIn(0, all.size - 1)]

    /** Progresso atual (valor, meta) do objetivo de uma skin. */
    fun progressOf(skin: SkinDef, p: Progress): Pair<Int, Int> = when (skin.unlock) {
        UnlockKind.FREE -> 1 to 1
        UnlockKind.DIAMONDS -> p.diamonds.coerceAtMost(skin.goal) to skin.goal
        UnlockKind.KILLS -> p.stats.kills to skin.goal
        UnlockKind.HEADSHOTS -> p.stats.headshots to skin.goal
        UnlockKind.STAGE -> (if (p.isStageCompleted(skin.goal)) 1 else 0) to 1
        UnlockKind.STARS -> p.totalStars() to skin.goal
        UnlockKind.CRYSTALS -> p.stats.crystals to skin.goal
        UnlockKind.PLAYTIME_MIN -> (p.stats.playSeconds / 60).toInt() to skin.goal
        UnlockKind.ADS -> p.stats.adsWatched to skin.goal
        UnlockKind.LOGIN_DAYS -> p.stats.loginDays to skin.goal
    }

    /** Skins com objetivo cumprido (excluindo as de diamantes, que exigem compra). */
    fun objectiveMet(skin: SkinDef, p: Progress): Boolean {
        if (skin.unlock == UnlockKind.FREE) return true
        if (skin.unlock == UnlockKind.DIAMONDS) return false
        val (value, goal) = progressOf(skin, p)
        return value >= goal
    }

    fun requirementText(skin: SkinDef): String = when (skin.unlock) {
        UnlockKind.FREE -> "Liberado"
        UnlockKind.DIAMONDS -> "Compre por ${skin.goal} diamantes"
        UnlockKind.KILLS -> "Elimine ${skin.goal} zumbis"
        UnlockKind.HEADSHOTS -> "Acerte ${skin.goal} tiros na cabeça"
        UnlockKind.STAGE -> "Vença a fase ${skin.goal}"
        UnlockKind.STARS -> "Junte ${skin.goal} estrelas"
        UnlockKind.CRYSTALS -> "Colete ${skin.goal} cristais nas fases"
        UnlockKind.PLAYTIME_MIN -> "Jogue por ${skin.goal} minutos"
        UnlockKind.ADS -> "Assista a ${skin.goal} anúncios"
        UnlockKind.LOGIN_DAYS -> "Entre no jogo em ${skin.goal} dias diferentes"
    }
}
