package com.fabiosilva.ecosdeeldra

import com.fabiosilva.ecosdeeldra.data.Campaign
import com.fabiosilva.ecosdeeldra.data.MissionResult
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.Skins
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
        assertTrue(Rules.refreshSkinUnlocks(p).isEmpty())
        p.stats.kills = 40
        val unlocked = Rules.refreshSkinUnlocks(p)
        assertTrue(unlocked.any { it.name == "Brann" })
        assertTrue(2 in p.ownedSkins)
    }

    @Test
    fun adSkinsUnlockByWatchingAds() {
        val p = Progress()
        repeat(3) { Rules.registerAdWatched(p) }
        assertTrue(Rules.refreshSkinUnlocks(p).any { it.index == 5 })
    }

    @Test
    fun loginDaysCountOncePerDayAndUnlockExclusiveSkins() {
        val p = Progress()
        assertTrue(Rules.onAppOpen(p, "2026-10-02"))
        assertFalse(Rules.onAppOpen(p, "2026-10-02"))
        assertTrue(Rules.onAppOpen(p, "2026-10-03"))
        assertEquals(2, p.stats.loginDays)
        assertTrue(Rules.refreshSkinUnlocks(p).any { it.name == "Nyla" })
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
        assertTrue(Rules.buySkin(p, 1))
        assertEquals(50, p.diamonds)
        assertFalse(Rules.buySkin(p, 3)) // skin de login não se compra
        assertFalse(Rules.buyUpgrade(p, "power"))
        p.diamonds = 60
        assertTrue(Rules.buyUpgrade(p, "power"))
        assertEquals(1, p.power)
    }

    @Test
    fun difficultyScalesWithStageAndUpgrades() {
        val easy = Campaign.difficulty(1, 0, 0, 0)
        val hard = Campaign.difficulty(20, 5, 5, 5)
        assertTrue(hard.zombieCount > easy.zombieCount)
        assertTrue(hard.zombieHealth > easy.zombieHealth)
        assertEquals(1.75f, hard.power, 0.001f)
        assertEquals(18, Skins.all.size)
    }
}
