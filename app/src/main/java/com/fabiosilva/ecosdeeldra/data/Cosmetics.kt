package com.fabiosilva.ecosdeeldra.data

/** Como um visual é liberado. Objetivos acompanham estatísticas de longo prazo do jogador. */
enum class UnlockKind {
    FREE, DIAMONDS, KILLS, HEADSHOTS, STAGE, STARS, CRYSTALS, PLAYTIME_MIN, ADS, LOGIN_DAYS
}

/**
 * Peça especial do editor de personagem (roupa, acessório, cabelo...) liberada por objetivo,
 * anúncios, dias de login ou diamantes. As peças comuns do editor são todas livres.
 */
data class Cosmetic(
    val index: Int,
    val name: String,
    val part: LookPart?,
    val value: Int,
    val unlock: UnlockKind,
    val goal: Int,
    /** Preço alternativo em diamantes (0 = só pelo objetivo). */
    val price: Int,
)

object Cosmetics {
    val all = listOf(
        Cosmetic(0, "Kit básico", null, 0, UnlockKind.FREE, 0, 0),
        Cosmetic(1, "Jaqueta de couro", LookPart.TOP, 2, UnlockKind.DIAMONDS, 150, 150),
        Cosmetic(2, "Colete tático", LookPart.TOP, 5, UnlockKind.KILLS, 40, 300),
        Cosmetic(3, "Boné", LookPart.HAT, 1, UnlockKind.LOGIN_DAYS, 2, 0),
        Cosmetic(4, "Capacete militar", LookPart.HAT, 2, UnlockKind.DIAMONDS, 250, 250),
        Cosmetic(5, "Jaleco de médico", LookPart.TOP, 7, UnlockKind.ADS, 3, 0),
        Cosmetic(6, "Gorro de inverno", LookPart.HAT, 3, UnlockKind.STAGE, 5, 0),
        Cosmetic(7, "Óculos táticos", LookPart.HAT, 5, UnlockKind.HEADSHOTS, 50, 450),
        Cosmetic(8, "Black power", LookPart.HAIR_STYLE, 5, UnlockKind.LOGIN_DAYS, 5, 0),
        Cosmetic(9, "Camisa xadrez", LookPart.TOP, 4, UnlockKind.DIAMONDS, 400, 400),
        Cosmetic(10, "Fone gamer", LookPart.HAT, 6, UnlockKind.ADS, 8, 0),
        Cosmetic(11, "Cicatriz de batalha", LookPart.FACE, 3, UnlockKind.KILLS, 250, 700),
        Cosmetic(12, "Armadura de ferro", LookPart.TOP, 6, UnlockKind.STARS, 30, 600),
        Cosmetic(13, "Moicano", LookPart.HAIR_STYLE, 3, UnlockKind.PLAYTIME_MIN, 60, 600),
        Cosmetic(14, "Pele de morto-vivo", LookPart.SKIN, 7, UnlockKind.CRYSTALS, 30, 700),
        Cosmetic(15, "Coroa dourada", LookPart.HAT, 7, UnlockKind.LOGIN_DAYS, 7, 0),
        Cosmetic(16, "Cor dourada", LookPart.TOP_COLOR, 15, UnlockKind.STAGE, 15, 0),
        Cosmetic(17, "Faixa ninja", LookPart.HAT, 4, UnlockKind.ADS, 15, 0),
    )

    fun get(index: Int): Cosmetic = all[index.coerceIn(0, all.size - 1)]

    /** Peça especial que trava esta opção do editor (null = opção livre). */
    fun lockOf(part: LookPart, value: Int): Cosmetic? = all.firstOrNull { it.part == part && it.value == value }

    fun available(p: Progress, part: LookPart, value: Int): Boolean {
        val lock = lockOf(part, value) ?: return true
        return lock.index in p.ownedCosmetics
    }

    /** Aparência só com peças liberadas (o que estiver travado volta ao padrão). */
    fun sanitize(p: Progress, look: Look): Look {
        var out = look
        for (part in LookPart.entries) if (!available(p, part, out[part])) out = out.with(part, Look()[part])
        return out
    }

    /** Progresso atual (valor, meta) do objetivo de um visual. */
    fun progressOf(c: Cosmetic, p: Progress): Pair<Int, Int> = when (c.unlock) {
        UnlockKind.FREE -> 1 to 1
        UnlockKind.DIAMONDS -> p.diamonds.coerceAtMost(c.goal) to c.goal
        UnlockKind.KILLS -> p.stats.kills to c.goal
        UnlockKind.HEADSHOTS -> p.stats.headshots to c.goal
        UnlockKind.STAGE -> (if (p.isStageCompleted(c.goal)) 1 else 0) to 1
        UnlockKind.STARS -> p.totalStars() to c.goal
        UnlockKind.CRYSTALS -> p.stats.crystals to c.goal
        UnlockKind.PLAYTIME_MIN -> (p.stats.playSeconds / 60).toInt() to c.goal
        UnlockKind.ADS -> p.stats.adsWatched to c.goal
        UnlockKind.LOGIN_DAYS -> p.stats.loginDays to c.goal
    }

    /** Visuais com objetivo cumprido (excluindo os de diamantes, que exigem compra). */
    fun objectiveMet(c: Cosmetic, p: Progress): Boolean {
        if (c.unlock == UnlockKind.FREE) return true
        if (c.unlock == UnlockKind.DIAMONDS) return false
        val (value, goal) = progressOf(c, p)
        return value >= goal
    }

    fun requirementText(c: Cosmetic): String = when (c.unlock) {
        UnlockKind.FREE -> "Liberado"
        UnlockKind.DIAMONDS -> "Compre por ${c.goal} diamantes"
        UnlockKind.KILLS -> "Elimine ${c.goal} zumbis"
        UnlockKind.HEADSHOTS -> "Acerte ${c.goal} tiros na cabeça"
        UnlockKind.STAGE -> "Vença a fase ${c.goal}"
        UnlockKind.STARS -> "Junte ${c.goal} estrelas"
        UnlockKind.CRYSTALS -> "Colete ${c.goal} cristais nas fases"
        UnlockKind.PLAYTIME_MIN -> "Jogue por ${c.goal} minutos"
        UnlockKind.ADS -> "Assista a ${c.goal} anúncios"
        UnlockKind.LOGIN_DAYS -> "Entre no jogo em ${c.goal} dias diferentes"
    }
}
