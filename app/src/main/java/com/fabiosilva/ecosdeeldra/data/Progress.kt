package com.fabiosilva.ecosdeeldra.data

import kotlin.random.Random

class Stats {
    var kills = 0
    var headshots = 0
    var wins = 0
    var crystals = 0
    var playSeconds = 0L
    var adsWatched = 0
    var loginDays = 0
    var bestCombo = 0
    var pvpKills = 0
    var pvpWins = 0
    var pvpMatches = 0
    var chests = 0
    var nests = 0
    var distance = 0L // metros percorridos nas ilhas
    var losses = 0
    var survivalRuns = 0
    var bestWave = 0
    var bestSurvivalSeconds = 0
}

/** Uma partida do modo sobrevivência (ranking local). */
data class SurvivalRecord(val name: String, val seconds: Int, val wave: Int, val kills: Int, val score: Int, val date: String)

/** Resultado de uma partida de sobrevivência. */
data class SurvivalResult(val seconds: Float, val wave: Int, val kills: Int, val headshots: Int, val score: Int, val bestCombo: Int, val distance: Float)

data class SurvivalSummary(val reward: Int, val rank: Int, val newRecord: Boolean)

class StageRecord(
    var completed: Boolean = false,
    var stars: Int = 0,
    var bestTime: Float = 0f,
    var crystalMask: Int = 0,
    var fails: Int = 0,
    var skipped: Boolean = false,
)

class DailyMission(val id: String, var progress: Int = 0, var claimed: Boolean = false)

class Settings {
    var sensitivity = 1.0f
    var musicVolume = 0.6f
    var sfxVolume = 1.0f
    var aimAssist = true
    var autoFire = true
    var invertY = false
    var vibration = true
    var quality = 1 // 0 baixa, 1 média, 2 alta
    var reminders = true
    var showFps = false
    var leftHanded = false
    var playerName = ""
    var cinematic = false // modo ultra-realista (PBR aproximado, sombras do sol e pós-processamento)
    var hudLayout = ""    // posições dos controles montadas pelo jogador (vazio = padrão)
    var hudOpacity = 0.85f
}

/** Todo o progresso salvo do jogador. A lógica fica em [Rules] para ser testável sem Android. */
class Progress {
    var diamonds = 0
    /** Visuais especiais liberados para o editor de personagem (índices de [Cosmetics]). */
    val ownedCosmetics: MutableSet<Int> = sortedSetOf(0)
    /** Personagens criados pelo jogador (estilo Minecraft); sempre há pelo menos um. */
    val heroes: MutableList<Hero> = mutableListOf(Hero("Herói", Look().encode()))
    var selectedHero = 0
    val currentHero: Hero get() = heroes.getOrElse(selectedHero) { heroes.first() }
    /** Aparência codificada do personagem em uso (enviada ao motor). */
    val lookCode: Int get() = currentHero.look
    val stages: MutableMap<Int, StageRecord> = mutableMapOf()
    /** Nível de cada melhoria (chaves em [Upgrades]). */
    val upgrades: MutableMap<String, Int> = mutableMapOf()
    var vitality: Int
        get() = upgrades["vitality"] ?: 0
        set(value) { upgrades["vitality"] = value }
    var power: Int
        get() = upgrades["power"] ?: 0
        set(value) { upgrades["power"] = value }
    var reload: Int
        get() = upgrades["reload"] ?: 0
        set(value) { upgrades["reload"] = value }
    fun upgrade(key: String): Int = upgrades[key] ?: 0
    /** Mochila (barra rápida + 18 espaços), salva entre as partidas. */
    val inventory = Inventory.starter()
    val survivalRecords: MutableList<SurvivalRecord> = mutableListOf()
    var supplyAdsDay = ""
    var supplyAdsToday = 0
    val stats = Stats()
    /** Histórico e contadores dos contratos de expedição (campo "expeditions" do save). */
    val expeditions = ExpeditionLog()
    var lastLoginDay = ""
    var calendarClaims = 0
    var lastCalendarClaim = ""
    var dailyDay = ""
    val daily: MutableList<DailyMission> = mutableListOf()
    var dailyBonusClaimed = false
    var adsDay = ""
    var adsToday = 0
    var chestReadyAt = 0L
    var tutorialSeen = false      // legado; derivado de TutorialPolicy.isDone ao salvar
    var tutorialVersion = 0       // maior versão do tutorial cumprida (TutorialPolicy.VERSION)
    var tutorialMask = 0          // bit por Lesson já concluída (0x1FF = tudo)
    var playTapsSinceAd = 0
    var missionsSinceInterstitial = 0
    var lastAdAt = 0L
    val settings = Settings()
    /** Campos de versões futuras do save, devolvidos intactos ao gravar (chave -> "[valor]" em JSON). Só o SaveCodec usa. */
    val saveExtras: MutableMap<String, String> = mutableMapOf()

    fun record(stage: Int): StageRecord = stages.getOrPut(stage) { StageRecord() }
    fun isStageCompleted(stage: Int): Boolean = stages[stage]?.completed == true
    fun totalStars(): Int = stages.values.sumOf { it.stars }
    fun completedStages(): Int = stages.values.count { it.completed }

    /** Profundidade máxima alcançada na árvore + 1 (define zonas, limites de melhorias e armas). */
    fun highestUnlockedStage(): Int = 1 + (stages.entries.filter { it.value.completed }.maxOfOrNull { StageTree.depth(it.key) } ?: 0)

    /** Nó a jogar por padrão (o próximo da árvore). Qualquer nó disponível pode ser escolhido no mapa. */
    fun frontierStage(): Int = StageTree.frontier(this)
}

data class MissionResult(
    val victory: Boolean,
    val elapsedSeconds: Float,
    val health: Float,
    val maxHealth: Float,
    val kills: Int,
    val headshots: Int,
    val crystalMask: Int,
    val bestCombo: Int,
    val shots: Int = 0,
    val hits: Int = 0,
    val chests: Int = 0,
    val chestDiamonds: Int = 0,
    val distance: Float = 0f,
    val nests: Int = 0,
)

data class RewardSummary(
    val stars: Int,
    val conditions: List<Boolean>,
    val firstClear: Int,
    val starBonus: Int,
    val crystalBonus: Int,
    val killBonus: Int,
    val newCrystals: Int,
    val chestBonus: Int = 0,
    val exploreBonus: Int = 0,
) {
    val total: Int get() = firstClear + starBonus + crystalBonus + killBonus + chestBonus + exploreBonus
}

data class DailyDef(val id: String, val title: String, val goal: Int, val reward: Int)

object Rules {
    const val AD_DIAMONDS = 25
    const val AD_DAILY_CAP = 5
    const val CAMPAIGN_PLAY_AD_EVERY = 5
    const val CHEST_INTERVAL_MS = 4L * 60 * 60 * 1000
    const val DAILY_BONUS = 80
    val UPGRADE_COSTS = intArrayOf(60, 110, 170, 250, 350)
    val CALENDAR = intArrayOf(30, 40, 50, 60, 80, 100, 150)

    val DAILY_POOL = listOf(
        DailyDef("kill", "Elimine 30 zumbis", 30, 40),
        DailyDef("headshot", "Acerte 10 tiros na cabeça", 10, 45),
        DailyDef("win", "Vença 2 fases", 2, 50),
        DailyDef("crystal", "Recolha 3 cristais nas fases", 3, 40),
        DailyDef("stars3", "Vença uma fase com 3 estrelas", 1, 60),
        DailyDef("ad", "Assista a 1 anúncio", 1, 30),
        DailyDef("play", "Jogue por 10 minutos", 600, 40),
        DailyDef("combo", "Faça um combo de 5 abates", 1, 50),
        DailyDef("chest", "Abra 3 baús escondidos na ilha", 3, 45),
        DailyDef("nest", "Destrua 2 ninhos de infecção", 2, 50),
        DailyDef("explore", "Percorra 2 km explorando", 2000, 40),
    )

    fun dailyDef(id: String): DailyDef = DAILY_POOL.first { it.id == id }

    /** Chamado ao abrir o app: conta dias de login e renova as missões diárias. */
    fun onAppOpen(p: Progress, day: String): Boolean {
        val newDay = p.lastLoginDay != day
        if (newDay) {
            p.stats.loginDays += 1
            p.lastLoginDay = day
        }
        refreshDaily(p, day)
        return newDay
    }

    fun refreshDaily(p: Progress, day: String) {
        if (p.dailyDay == day && p.daily.size == 3) return
        val random = Random(day.hashCode())
        val picks = DAILY_POOL.shuffled(random).take(3)
        p.daily.clear()
        picks.forEach { p.daily.add(DailyMission(it.id)) }
        p.dailyDay = day
        p.dailyBonusClaimed = false
    }

    fun bumpDaily(p: Progress, id: String, amount: Int) {
        p.daily.filter { it.id == id && !it.claimed }.forEach {
            it.progress = (it.progress + amount).coerceAtMost(dailyDef(id).goal)
        }
    }

    fun claimDaily(p: Progress, id: String): Int {
        val mission = p.daily.firstOrNull { it.id == id } ?: return 0
        val def = dailyDef(id)
        if (mission.claimed || mission.progress < def.goal) return 0
        mission.claimed = true
        p.diamonds += def.reward
        return def.reward
    }

    fun claimDailyBonus(p: Progress): Int {
        if (p.dailyBonusClaimed || p.daily.size < 3 || !p.daily.all { it.claimed }) return 0
        p.dailyBonusClaimed = true
        p.diamonds += DAILY_BONUS
        return DAILY_BONUS
    }

    fun claimableDailyCount(p: Progress): Int =
        p.daily.count { !it.claimed && it.progress >= dailyDef(it.id).goal } +
            (if (!p.dailyBonusClaimed && p.daily.size == 3 && p.daily.all { it.claimed }) 1 else 0)

    fun canClaimCalendar(p: Progress, day: String): Boolean = p.lastCalendarClaim != day

    /** A sequência não reinicia: depois do dia 7 cada dia paga +20 a mais que o anterior. */
    fun calendarReward(index: Int): Int =
        if (index < CALENDAR.size) CALENDAR[index] else CALENDAR.last() + (index - CALENDAR.size + 1) * 20

    fun claimCalendar(p: Progress, day: String): Int {
        if (!canClaimCalendar(p, day)) return 0
        val reward = calendarReward(p.calendarClaims)
        p.diamonds += reward
        p.calendarClaims += 1
        p.lastCalendarClaim = day
        return reward
    }

    fun chestReady(p: Progress, now: Long): Boolean = now >= p.chestReadyAt

    fun openChest(p: Progress, now: Long, random: Random = Random): Int {
        if (!chestReady(p, now)) return 0
        val amount = 20 + random.nextInt(41)
        p.diamonds += amount
        p.chestReadyAt = now + CHEST_INTERVAL_MS
        return amount
    }

    fun adsRemaining(p: Progress, day: String): Int =
        AD_DAILY_CAP - (if (p.adsDay == day) p.adsToday else 0)

    /** Todo anúncio assistido conta para skins de anúncio e para a missão diária. */
    fun registerAdWatched(p: Progress) {
        p.stats.adsWatched += 1
        bumpDaily(p, "ad", 1)
    }

    /** Recompensa do botão "+diamantes" (o anúncio em si é contado por [registerAdWatched]). */
    fun claimAdDiamonds(p: Progress, day: String): Int {
        if (p.adsDay != day) {
            p.adsDay = day
            p.adsToday = 0
        }
        if (p.adsToday >= AD_DAILY_CAP) return 0
        p.adsToday += 1
        p.diamonds += AD_DIAMONDS
        return AD_DIAMONDS
    }

    fun evaluateStars(stage: Stage, r: MissionResult): List<Boolean> {
        if (!r.victory) return listOf(false, false, false)
        val healthy = r.health / r.maxHealth.coerceAtLeast(1f) >= 0.5f
        val fast = r.elapsedSeconds <= stage.targetSeconds
        return listOf(true, healthy, fast)
    }

    /** Aplica o resultado de uma missão (vitória ou derrota) ao progresso. */
    fun applyResult(p: Progress, stage: Stage, r: MissionResult): RewardSummary {
        p.stats.kills += r.kills
        p.stats.headshots += r.headshots
        p.stats.playSeconds += r.elapsedSeconds.toLong()
        p.stats.bestCombo = maxOf(p.stats.bestCombo, r.bestCombo)
        bumpDaily(p, "kill", r.kills)
        bumpDaily(p, "headshot", r.headshots)
        bumpDaily(p, "play", r.elapsedSeconds.toInt())
        if (r.bestCombo >= 5) bumpDaily(p, "combo", 1)
        p.stats.chests += r.chests
        p.stats.nests += r.nests
        p.stats.distance += r.distance.toLong()
        bumpDaily(p, "chest", r.chests)
        bumpDaily(p, "nest", r.nests)
        bumpDaily(p, "explore", r.distance.toInt())

        val record = p.record(stage.number)
        val newMask = r.crystalMask and record.crystalMask.inv() and 0b111
        val newCrystals = Integer.bitCount(newMask)
        record.crystalMask = record.crystalMask or newMask
        p.stats.crystals += newCrystals
        bumpDaily(p, "crystal", newCrystals)

        val conditions = evaluateStars(stage, r)
        val stars = conditions.count { it }
        var firstClear = 0
        var starBonus = 0
        if (r.victory) {
            if (!record.completed) firstClear = stage.firstClearDiamonds
            starBonus = (stars - record.stars).coerceAtLeast(0) * 15
            record.completed = true
            record.stars = maxOf(record.stars, stars)
            record.bestTime = if (record.bestTime <= 0f) r.elapsedSeconds else minOf(record.bestTime, r.elapsedSeconds)
            p.stats.wins += 1
            bumpDaily(p, "win", 1)
            if (stars == 3) bumpDaily(p, "stars3", 1)
        } else {
            record.fails += 1
            p.stats.losses += 1
        }
        val crystalBonus = newCrystals * 10
        val killBonus = r.kills
        // Exploração também paga: diamantes dos baús e 1 diamante a cada 100 m percorridos.
        val exploreBonus = (r.distance / 100f).toInt().coerceAtMost(40)
        val summary = RewardSummary(stars, conditions, firstClear, starBonus, crystalBonus, killBonus, newCrystals, r.chestDiamonds, exploreBonus)
        p.diamonds += summary.total
        return summary
    }

    /** Fase travada depois de algumas derrotas pode ser pulada com um anúncio. */
    const val SKIP_AFTER_FAILS = 2

    /** [pendingFails] conta a derrota que ainda está na tela (o resultado só é aplicado ao sair). */
    fun canSkip(p: Progress, stage: Int, pendingFails: Int = 0): Boolean {
        val record = p.stages[stage]
        val fails = (record?.fails ?: 0) + pendingFails
        return record?.completed != true && fails >= SKIP_AFTER_FAILS && StageTree.isAvailable(p, stage)
    }

    fun skipStage(p: Progress, stage: Int): Boolean {
        if (!canSkip(p, stage)) return false
        val record = p.record(stage)
        record.completed = true
        record.skipped = true
        return true
    }

    /** Intersticial só depois de algumas partidas e nunca colado em outro anúncio. */
    const val INTERSTITIAL_EVERY = 2
    const val INTERSTITIAL_GAP_MS = 150_000L

    fun registerCampaignPlayTap(p: Progress): Boolean {
        p.playTapsSinceAd += 1
        if (p.playTapsSinceAd < CAMPAIGN_PLAY_AD_EVERY) return false
        p.playTapsSinceAd = 0
        return true
    }

    fun shouldShowInterstitial(p: Progress, now: Long): Boolean =
        p.stats.wins + p.stats.losses >= 3 &&
            p.missionsSinceInterstitial >= INTERSTITIAL_EVERY &&
            now - p.lastAdAt >= INTERSTITIAL_GAP_MS

    /** Libera visuais cujo objetivo foi cumprido; retorna os recém-liberados. */
    fun refreshCosmeticUnlocks(p: Progress): List<Cosmetic> {
        val unlocked = Cosmetics.all.filter { it.index !in p.ownedCosmetics && Cosmetics.objectiveMet(it, p) }
        unlocked.forEach { p.ownedCosmetics.add(it.index) }
        return unlocked
    }

    fun buyCosmetic(p: Progress, index: Int): Boolean {
        val c = Cosmetics.get(index)
        if (index in p.ownedCosmetics || c.price <= 0 || p.diamonds < c.price) return false
        p.diamonds -= c.price
        p.ownedCosmetics.add(index)
        return true
    }

    const val MAX_HEROES = 6

    /** Cria um personagem novo (aleatório, só com peças livres) e já o seleciona. */
    fun createHero(p: Progress, look: Look, name: String): Boolean {
        if (p.heroes.size >= MAX_HEROES) return false
        p.heroes.add(Hero(sanitizeName(name), Cosmetics.sanitize(p, look).encode()))
        p.selectedHero = p.heroes.size - 1
        return true
    }

    fun deleteHero(p: Progress, index: Int): Boolean {
        if (p.heroes.size <= 1 || index !in p.heroes.indices) return false
        p.heroes.removeAt(index)
        if (p.selectedHero >= index) p.selectedHero = (p.selectedHero - 1).coerceAtLeast(0)
        return true
    }

    /** Recompensa de uma partida PvP: 2 diamantes por abate + bônus de participação/vitória. */
    fun applyPvpResult(p: Progress, kills: Int, won: Boolean): Int {
        val reward = kills.coerceAtLeast(0) * 2 + if (won) 25 else 5
        p.stats.pvpKills += kills.coerceAtLeast(0)
        p.stats.pvpMatches += 1
        if (won) p.stats.pvpWins += 1
        p.diamonds += reward
        return reward
    }

    fun upgradeLevel(p: Progress, kind: String): Int = p.upgrade(kind)

    /** Limite de nível das melhorias: começa em 5 e sobe 3 a cada zona liberada (até 60). */
    fun upgradeCap(p: Progress): Int = (5 + 3 * ((p.highestUnlockedStage() - 1) / Campaign.STAGES_PER_ZONE)).coerceAtMost(60)

    /** Zona em que o próximo aumento de limite libera (1 = primeira zona). */
    fun nextCapZone(p: Progress): Int = (p.highestUnlockedStage() - 1) / Campaign.STAGES_PER_ZONE + 2

    /** Custo do próximo nível: os 5 primeiros seguem a tabela antiga, depois cresce em curva. */
    fun upgradeCost(level: Int): Int? {
        if (level < 0) return null
        UPGRADE_COSTS.getOrNull(level)?.let { return it }
        val k = level - 4
        val raw = 350 + k * 120 + k * k * 10
        return (raw + 4) / 5 * 5
    }

    fun canBuyUpgrade(p: Progress, kind: String): Boolean {
        val level = upgradeLevel(p, kind)
        val cost = upgradeCost(level) ?: return false
        return level < upgradeCap(p) && p.diamonds >= cost
    }

    fun buyUpgrade(p: Progress, kind: String): Boolean {
        if (Upgrades.get(kind) == null) return false
        val level = upgradeLevel(p, kind)
        val cost = upgradeCost(level) ?: return false
        if (level >= upgradeCap(p) || p.diamonds < cost) return false
        p.diamonds -= cost
        p.upgrades[kind] = level + 1
        return true
    }

    // ------------------------------------------------------------------ arsenal e mochila

    fun weaponUnlocked(p: Progress, id: Int): Boolean = (Items.get(id)?.unlockStage ?: 1) <= p.highestUnlockedStage()

    /** Compra uma arma (fica para sempre na mochila). */
    fun buyWeapon(p: Progress, id: Int): Boolean {
        val def = Items.get(id) ?: return false
        if (def.kind != ItemKind.WEAPON || p.inventory.has(id) || !weaponUnlocked(p, id)) return false
        if (p.diamonds < def.price || !p.inventory.canAdd(id)) return false
        p.diamonds -= def.price
        p.inventory.add(id, 1)
        p.inventory.normalize()
        return true
    }

    /** Compra um consumível com diamantes. */
    fun buyItem(p: Progress, id: Int, amount: Int = 1): Boolean {
        val def = Items.get(id) ?: return false
        if (def.kind == ItemKind.WEAPON || amount <= 0) return false
        val price = def.price * amount
        val explosives = p.upgrade("explosives")
        if (p.diamonds < price) return false
        val copy = Inventory().apply { copyFrom(p.inventory) }
        if (copy.add(id, amount, explosives) > 0) return false
        p.diamonds -= price
        p.inventory.add(id, amount, explosives)
        return true
    }

    const val SUPPLY_AD_CAP = 6

    fun supplyAdsRemaining(p: Progress, day: String): Int = SUPPLY_AD_CAP - (if (p.supplyAdsDay == day) p.supplyAdsToday else 0)

    /** Caixa de suprimentos grátis por anúncio no Arsenal; o que não couber se perde. */
    fun claimSupplyCrate(p: Progress, day: String, random: Random = Random): List<Pair<Int, Int>> {
        if (p.supplyAdsDay != day) {
            p.supplyAdsDay = day
            p.supplyAdsToday = 0
        }
        if (p.supplyAdsToday >= SUPPLY_AD_CAP) return emptyList()
        p.supplyAdsToday += 1
        val pool = listOf(Items.MEDKIT, Items.GRENADE, Items.MOLOTOV, Items.SMOKE, Items.ADRENALINE, Items.ARMOR, Items.FIRE_AMMO, Items.BANDAGE, Items.ANTIDOTE)
        val got = mutableListOf(Items.GRENADE to 2, Items.MEDKIT to 1)
        repeat(2) { got.add(pool[random.nextInt(pool.size)] to 1) }
        val explosives = p.upgrade("explosives")
        return got.map { (id, n) -> id to (n - p.inventory.add(id, n, explosives)) }.filter { it.second > 0 }
    }

    // ------------------------------------------------------------------ sobrevivência

    /** Ranking local: as 10 partidas que sobreviveram mais tempo. */
    fun survivalRanking(p: Progress): List<SurvivalRecord> =
        p.survivalRecords.sortedWith(compareByDescending<SurvivalRecord> { it.seconds }.thenByDescending { it.score }).take(10)

    fun applySurvivalResult(p: Progress, name: String, day: String, r: SurvivalResult): SurvivalSummary {
        val seconds = r.seconds.toInt()
        p.stats.kills += r.kills
        p.stats.headshots += r.headshots
        p.stats.playSeconds += seconds.toLong()
        p.stats.bestCombo = maxOf(p.stats.bestCombo, r.bestCombo)
        p.stats.distance += r.distance.toLong()
        p.stats.survivalRuns += 1
        bumpDaily(p, "kill", r.kills)
        bumpDaily(p, "headshot", r.headshots)
        bumpDaily(p, "play", seconds)
        bumpDaily(p, "explore", r.distance.toInt())
        if (r.bestCombo >= 5) bumpDaily(p, "combo", 1)
        val newRecord = seconds > p.stats.bestSurvivalSeconds
        p.stats.bestSurvivalSeconds = maxOf(p.stats.bestSurvivalSeconds, seconds)
        p.stats.bestWave = maxOf(p.stats.bestWave, r.wave)
        val record = SurvivalRecord(sanitizeName(name), seconds, r.wave, r.kills, r.score, day)
        p.survivalRecords.add(record)
        val ranking = survivalRanking(p)
        p.survivalRecords.retainAll(ranking.toSet())
        val rank = ranking.indexOf(record) + 1
        // Diamantes: 8 por onda vencida + 1 a cada 2 abates (+ bônus de recorde).
        val reward = (r.wave - 1).coerceAtLeast(0) * 8 + r.kills / 2 + if (newRecord && seconds >= 60) 30 else 0
        p.diamonds += reward
        return SurvivalSummary(reward, rank, newRecord)
    }

    fun sanitizeName(name: String): String = name.replace(Regex("[|;,\\n\\r]"), " ").trim().take(14).ifEmpty { "Jogador" }
}

/** Melhorias compráveis com diamantes. O limite de nível sobe a cada nova zona. */
data class UpgradeDef(val key: String, val title: String, val desc: String, val color: Int)

object Upgrades {
    val all = listOf(
        UpgradeDef("vitality", "VITALIDADE", "Mais vida máxima a cada nível.", 0xFFFF6B6B.toInt()),
        UpgradeDef("power", "PODER DE FOGO", "Mais dano em todas as armas.", 0xFFFFA53D.toInt()),
        UpgradeDef("reload", "RECARGA RÁPIDA", "Recarrega mais depressa.", 0xFFFFC83D.toInt()),
        UpgradeDef("armor", "BLINDAGEM", "−2,5% de dano recebido por nível (até 60%).", 0xFF9DB0D8.toInt()),
        UpgradeDef("regen", "REGENERAÇÃO", "Recupera vida sozinho fora de combate.", 0xFF8CFF6B.toInt()),
        UpgradeDef("agility", "AGILIDADE", "+2,5% de velocidade por nível (até +40%).", 0xFF4CE8FF.toInt()),
        UpgradeDef("headshot", "MIRA MORTAL", "+10% de dano em tiros na cabeça.", 0xFFFFD86B.toInt()),
        UpgradeDef("magazine", "PENTE ESTENDIDO", "+6% de balas em cada pente.", 0xFFB8C4D8.toInt()),
        UpgradeDef("luck", "SORTE", "+8% de chance de itens dos zumbis.", 0xFFA877FF.toInt()),
        UpgradeDef("explosives", "EXPLOSIVOS", "+8% de dano de explosões; mais granadas por espaço.", 0xFFB6FF5C.toInt()),
    )

    fun get(key: String): UpgradeDef? = all.firstOrNull { it.key == key }

    fun effectText(key: String, level: Int): String = when (key) {
        "vitality" -> "+${minOf(level, 5) * 20} de vida +${maxOf(0, level - 5) * 6}%"
        "power" -> "+${(Upgrades.power(level) * 100 - 100).toInt()}% de dano"
        "reload" -> "+${(Upgrades.reload(level) * 100 - 100).toInt()}% de velocidade"
        "armor" -> "−${(armor(level) * 100).toInt()}% de dano"
        "regen" -> "%.1f%% da vida/s".format(regen(level) * 100)
        "agility" -> "+${(agility(level) * 100 - 100).toInt()}% de velocidade"
        "headshot" -> "+${level * 10}% na cabeça"
        "magazine" -> "+${level * 6}% de munição"
        "luck" -> "+${level * 8}% de itens"
        else -> "+${level * 8}% de explosão"
    }

    fun power(level: Int) = 1f + 0.15f * minOf(level, 5) + 0.08f * maxOf(0, level - 5)
    fun reload(level: Int) = (1f + 0.12f * minOf(level, 5) + 0.06f * maxOf(0, level - 5)).coerceAtMost(3f)
    fun armor(level: Int) = (0.025f * level).coerceAtMost(0.6f)
    fun regen(level: Int) = (0.0012f * level).coerceAtMost(0.03f)
    fun agility(level: Int) = (1f + 0.025f * level).coerceAtMost(1.4f)
    fun headshot(level: Int) = 1f + 0.1f * level
    fun magazine(level: Int) = 1f + 0.06f * level
    fun luck(level: Int) = 1f + 0.08f * level
}
