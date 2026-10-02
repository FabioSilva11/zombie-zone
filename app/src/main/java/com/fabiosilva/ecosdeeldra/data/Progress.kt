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
}

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
    var cinematic = false // iluminação cinematográfica (sombras, bloom, raios de sol)
    var hudLayout = ""    // posições dos controles montadas pelo jogador (vazio = padrão)
    var hudOpacity = 0.85f
}

/** Todo o progresso salvo do jogador. A lógica fica em [Rules] para ser testável sem Android. */
class Progress {
    var diamonds = 0
    var selectedSkin = 0
    val ownedSkins: MutableSet<Int> = sortedSetOf(0)
    val stages: MutableMap<Int, StageRecord> = mutableMapOf()
    var vitality = 0
    var power = 0
    var reload = 0
    val stats = Stats()
    var lastLoginDay = ""
    var calendarClaims = 0
    var lastCalendarClaim = ""
    var dailyDay = ""
    val daily: MutableList<DailyMission> = mutableListOf()
    var dailyBonusClaimed = false
    var adsDay = ""
    var adsToday = 0
    var chestReadyAt = 0L
    var tutorialSeen = false
    var missionsSinceInterstitial = 0
    var lastAdAt = 0L
    val settings = Settings()

    fun record(stage: Int): StageRecord = stages.getOrPut(stage) { StageRecord() }
    fun isStageCompleted(stage: Int): Boolean = stages[stage]?.completed == true
    fun totalStars(): Int = stages.values.sumOf { it.stars }
    fun completedStages(): Int = stages.values.count { it.completed }

    /** As fases são infinitas: a próxima libera assim que a anterior é vencida (ou pulada). */
    fun highestUnlockedStage(): Int {
        var n = 1
        while (isStageCompleted(n)) n++
        return n
    }
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
    const val CHEST_INTERVAL_MS = 4L * 60 * 60 * 1000
    const val DAILY_BONUS = 80
    val UPGRADE_COSTS = intArrayOf(60, 110, 170, 250, 350)
    val CALENDAR = intArrayOf(30, 40, 50, 60, 80, 100, 150)

    val DAILY_POOL = listOf(
        DailyDef("kill", "Elimine 30 zumbis", 30, 40),
        DailyDef("headshot", "Acerte 10 tiros na cabeça", 10, 45),
        DailyDef("win", "Vença 2 fases", 2, 50),
        DailyDef("crystal", "Colete 3 cristais nas fases", 3, 40),
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

    fun calendarReward(index: Int): Int = CALENDAR[index % CALENDAR.size]

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
        return record?.completed != true && fails >= SKIP_AFTER_FAILS && stage == p.highestUnlockedStage()
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

    fun shouldShowInterstitial(p: Progress, now: Long): Boolean =
        p.stats.wins + p.stats.losses >= 3 &&
            p.missionsSinceInterstitial >= INTERSTITIAL_EVERY &&
            now - p.lastAdAt >= INTERSTITIAL_GAP_MS

    /** Libera skins cujo objetivo foi cumprido; retorna as recém-liberadas. */
    fun refreshSkinUnlocks(p: Progress): List<SkinDef> {
        val unlocked = Skins.all.filter { it.index !in p.ownedSkins && Skins.objectiveMet(it, p) }
        unlocked.forEach { p.ownedSkins.add(it.index) }
        return unlocked
    }

    fun buySkin(p: Progress, index: Int): Boolean {
        val skin = Skins.get(index)
        if (index in p.ownedSkins || skin.price <= 0 || p.diamonds < skin.price) return false
        p.diamonds -= skin.price
        p.ownedSkins.add(index)
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

    fun upgradeLevel(p: Progress, kind: String): Int = when (kind) {
        "vitality" -> p.vitality
        "power" -> p.power
        else -> p.reload
    }

    fun upgradeCost(level: Int): Int? = UPGRADE_COSTS.getOrNull(level)

    fun buyUpgrade(p: Progress, kind: String): Boolean {
        val level = upgradeLevel(p, kind)
        val cost = upgradeCost(level) ?: return false
        if (p.diamonds < cost) return false
        p.diamonds -= cost
        when (kind) {
            "vitality" -> p.vitality += 1
            "power" -> p.power += 1
            else -> p.reload += 1
        }
        return true
    }
}
