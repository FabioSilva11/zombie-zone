package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.MissionResult
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.Settings
import com.fabiosilva.ecosdeeldra.data.Cosmetics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RulesTest {
    private fun win(stage: Int, time: Float = 60f, health: Float = 100f, kills: Int = 10, headshots: Int = 0, crystals: Int = 0b111) =
        MissionResult(true, time, health, 100f, kills, headshots, crystals, bestCombo = 2)

    @Test
    fun firstVictoryAwardsDiamondsStarsAndUnlocksNextStage() {
        val p = Progress()
        val stage = Campaign.stage(1)
        val summary = Rules.applyResult(p, stage, win(1))
        assertEquals(3, summary.stars)
        assertEquals(stage.firstClearDiamonds + 3 * 15 + 3 * 10 + 10, summary.total)
        assertEquals(summary.total, p.diamonds)
        assertEquals(2, p.highestUnlockedStage())
        assertEquals(3, p.stats.crystals)
    }

    @Test
    fun replayDoesNotRepeatFirstClearOrCrystals() {
        val p = Progress()
        val stage = Campaign.stage(1)
        Rules.applyResult(p, stage, win(1))
        val before = p.diamonds
        val again = Rules.applyResult(p, stage, win(1))
        assertEquals(0, again.firstClear)
        assertEquals(0, again.crystalBonus)
        assertEquals(before + 10, p.diamonds)
    }

    @Test
    fun starsDependOnHealthAndTime() {
        val stage = Campaign.stage(3)
        val slowAndHurt = Rules.evaluateStars(stage, win(3, time = stage.targetSeconds + 5f, health = 20f))
        assertEquals(listOf(true, false, false), slowAndHurt)
    }

    @Test
    fun objectiveSkinsUnlockWhenGoalReached() {
        val p = Progress()
        p.stats.kills = 39
        assertTrue(Rules.refreshCosmeticUnlocks(p).isEmpty())
        p.stats.kills = 40
        val unlocked = Rules.refreshCosmeticUnlocks(p)
        assertTrue(unlocked.any { it.name == "Colete tático" })
        assertTrue(2 in p.ownedCosmetics)
    }

    @Test
    fun adSkinsUnlockByWatchingAds() {
        val p = Progress()
        repeat(3) { Rules.registerAdWatched(p) }
        assertTrue(Rules.refreshCosmeticUnlocks(p).any { it.index == 5 })
    }

    @Test
    fun loginDaysCountOncePerDayAndUnlockExclusiveSkins() {
        val p = Progress()
        assertTrue(Rules.onAppOpen(p, "2026-10-02"))
        assertFalse(Rules.onAppOpen(p, "2026-10-02"))
        assertTrue(Rules.onAppOpen(p, "2026-10-03"))
        assertEquals(2, p.stats.loginDays)
        assertTrue(Rules.refreshCosmeticUnlocks(p).any { it.name == "Boné" })
    }

    @Test
    fun calendarClaimsOncePerDayWithGrowingRewards() {
        val p = Progress()
        assertEquals(30, Rules.claimCalendar(p, "2026-10-02"))
        assertEquals(0, Rules.claimCalendar(p, "2026-10-02"))
        assertEquals(40, Rules.claimCalendar(p, "2026-10-03"))
        assertEquals(70, p.diamonds)
    }

    @Test
    fun dailyMissionsRefreshAndPayOnce() {
        val p = Progress()
        Rules.refreshDaily(p, "2026-10-02")
        assertEquals(3, p.daily.size)
        val mission = p.daily.first()
        val def = Rules.dailyDef(mission.id)
        Rules.bumpDaily(p, mission.id, def.goal + 50)
        assertEquals(def.reward, Rules.claimDaily(p, mission.id))
        assertEquals(0, Rules.claimDaily(p, mission.id))
        Rules.refreshDaily(p, "2026-10-03")
        assertTrue(p.daily.none { it.claimed })
    }

    @Test
    fun dailyBonusRequiresAllThree() {
        val p = Progress()
        Rules.refreshDaily(p, "2026-10-02")
        assertEquals(0, Rules.claimDailyBonus(p))
        p.daily.forEach { Rules.bumpDaily(p, it.id, 10_000); Rules.claimDaily(p, it.id) }
        assertEquals(Rules.DAILY_BONUS, Rules.claimDailyBonus(p))
        assertEquals(0, Rules.claimDailyBonus(p))
    }

    @Test
    fun chestOpensEveryFourHours() {
        val p = Progress()
        val now = 1_000_000L
        val got = Rules.openChest(p, now, Random(1))
        assertTrue(got in 20..60)
        assertEquals(0, Rules.openChest(p, now + 1000))
        assertTrue(Rules.openChest(p, now + Rules.CHEST_INTERVAL_MS) > 0)
    }

    @Test
    fun adDiamondsAreCappedPerDay() {
        val p = Progress()
        repeat(Rules.AD_DAILY_CAP) { assertEquals(Rules.AD_DIAMONDS, Rules.claimAdDiamonds(p, "2026-10-02")) }
        assertEquals(0, Rules.claimAdDiamonds(p, "2026-10-02"))
        assertEquals(Rules.AD_DIAMONDS, Rules.claimAdDiamonds(p, "2026-10-03"))
    }

    @Test
    fun buyingSkinsAndUpgradesSpendsDiamonds() {
        val p = Progress()
        p.diamonds = 200
        assertTrue(Rules.buyCosmetic(p, 1))
        assertEquals(50, p.diamonds)
        assertFalse(Rules.buyCosmetic(p, 3)) // visual de login não se compra
        assertFalse(Rules.buyUpgrade(p, "power"))
        p.diamonds = 60
        assertTrue(Rules.buyUpgrade(p, "power"))
        assertEquals(1, p.power)
    }

    @Test
    fun stagesAreInfiniteAndKeepUnlocking() {
        val p = Progress()
        for (d in 1..25) Rules.applyResult(p, Campaign.stage(1 shl (d - 1)), win(d))
        assertEquals(26, p.highestUnlockedStage())
        val far = Campaign.stage(137)
        assertEquals(137, far.number)
        assertTrue(far.title.isNotBlank())
        assertTrue(Campaign.difficulty(137, 0, 0, 0).zombieHealth > Campaign.difficulty(20, 0, 0, 0).zombieHealth)
        assertTrue(Campaign.difficulty(137, 0, 0, 0).power > Campaign.difficulty(20, 0, 0, 0).power)
    }

    @Test
    fun stuckStageCanBeSkippedAfterTwoDefeats() {
        val p = Progress()
        val stage = Campaign.stage(1)
        val lose = MissionResult(false, 30f, 0f, 100f, 2, 0, 0, 0)
        Rules.applyResult(p, stage, lose)
        assertFalse(Rules.canSkip(p, 1))
        assertTrue(Rules.canSkip(p, 1, pendingFails = 1))
        Rules.applyResult(p, stage, lose)
        assertTrue(Rules.skipStage(p, 1))
        assertEquals(2, p.highestUnlockedStage())
        assertEquals(0, p.totalStars())
    }

    @Test
    fun explorationPaysChestsAndDistance() {
        val p = Progress()
        val r = MissionResult(true, 60f, 100f, 100f, 0, 0, 0, 0, chests = 2, chestDiamonds = 25, distance = 1500f, nests = 3)
        val summary = Rules.applyResult(p, Campaign.stage(1), r)
        assertEquals(25, summary.chestBonus)
        assertEquals(15, summary.exploreBonus)
        assertEquals(2, p.stats.chests)
        assertEquals(3, p.stats.nests)
    }

    @Test
    fun interstitialRespectsFrequency() {
        val p = Progress()
        p.stats.wins = 3
        p.missionsSinceInterstitial = 1
        assertFalse(Rules.shouldShowInterstitial(p, 1_000_000L))
        p.missionsSinceInterstitial = 2
        p.lastAdAt = 1_000_000L - 10_000L
        assertFalse(Rules.shouldShowInterstitial(p, 1_000_000L))
        p.lastAdAt = 0L
        assertTrue(Rules.shouldShowInterstitial(p, 1_000_000L))
    }

    @Test
    fun difficultyIgnoresAnyAdState() {
        val clean = Progress()
        val noisy = Progress().apply {
            stats.adsWatched = 5_000; adsDay = "2026-10-18"; adsToday = 5; supplyAdsToday = 6
            lastAdAt = 123L; playTapsSinceAd = 4; missionsSinceInterstitial = 9
        }
        for (stage in listOf(1, 5, 20, 137)) {
            assertEquals(Campaign.difficulty(stage, clean), Campaign.difficulty(stage, noisy))
        }
        assertEquals(Campaign.survivalDifficulty(clean), Campaign.survivalDifficulty(noisy))
    }

    @Test
    fun difficultyBaselineIsPinned() {
        val d = Campaign.difficulty(5, 0, 0, 0)
        assertEquals(10, d.zombieCount)
        assertEquals(108f, d.zombieHealth, 0.001f)
        assertEquals(10.4f, d.zombieDamage, 0.001f)
        assertEquals(1.088f, d.zombieSpeed, 0.001f)
    }

    @Test
    fun progressCampaignAndSettingsCarryNoAdRevenueState() {
        val names = (Progress::class.java.declaredFields + Settings::class.java.declaredFields).map { it.name.lowercase() } +
            Campaign::class.java.declaredMethods.map { it.name.lowercase() } + Campaign::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(names.none { "revenue" in it || "ilrd" in it })
    }

    @Test
    fun magazineUpgradeReachesTheCampaignDifficulty() {
        val p = Progress().also { it.upgrades["magazine"] = 5 }
        assertTrue(Campaign.difficulty(3, p).magazine > Campaign.difficulty(3, Progress()).magazine)
    }

    @Test
    fun campaignPlayAdIsRequestedEveryFifthTap() {
        val progress = Progress()

        repeat(4) { assertFalse(Rules.registerCampaignPlayTap(progress)) }
        assertTrue(Rules.registerCampaignPlayTap(progress))
        assertEquals(0, progress.playTapsSinceAd)
        assertFalse(Rules.registerCampaignPlayTap(progress))
    }

    @Test
    fun difficultyScalesWithStageAndUpgrades() {
        val easy = Campaign.difficulty(1, 0, 0, 0)
        val hard = Campaign.difficulty(20, 5, 5, 5)
        assertTrue(hard.zombieCount > easy.zombieCount)
        assertTrue(hard.zombieHealth > easy.zombieHealth)
        // 5 níveis de poder (+75%) e o bônus da zona 4 (+12% de zona x 0,8).
        assertEquals(1.75f * (1f + 0.12f * 0.8f), hard.power, 0.001f)
        assertEquals(18, Cosmetics.all.size)
    }
}
