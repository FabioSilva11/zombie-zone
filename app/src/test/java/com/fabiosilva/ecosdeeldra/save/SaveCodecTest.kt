package com.fabiosilva.ecosdeeldra.save

import com.fabiosilva.ecosdeeldra.data.ContractId
import com.fabiosilva.ecosdeeldra.data.DailyMission
import com.fabiosilva.ecosdeeldra.data.ExpeditionKind
import com.fabiosilva.ecosdeeldra.data.ExpeditionOutcome
import com.fabiosilva.ecosdeeldra.data.ExpeditionPlan
import com.fabiosilva.ecosdeeldra.data.ExpeditionRecord
import com.fabiosilva.ecosdeeldra.data.Hero
import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.Rules
import com.fabiosilva.ecosdeeldra.data.StageRecord
import com.fabiosilva.ecosdeeldra.data.SurvivalRecord
import com.fabiosilva.ecosdeeldra.data.save.DecodeOutcome
import com.fabiosilva.ecosdeeldra.data.save.SaveCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveCodecTest {
    private fun ok(raw: String): DecodeOutcome.Ok {
        val d = SaveCodec.decode(raw)
        assertTrue("esperava Ok mas veio $d", d is DecodeOutcome.Ok)
        return d as DecodeOutcome.Ok
    }

    private fun assertInvalid(raw: String) {
        val d = SaveCodec.decode(raw)
        assertTrue("esperava Invalid mas veio $d", d is DecodeOutcome.Invalid)
    }

    private fun fullProgress(): Progress = Progress().apply {
        diamonds = 777
        ownedCosmetics.add(2)
        heroes.add(Hero("Bia", 4242))
        selectedHero = 1
        stages[1] = StageRecord(true, 3, 99.5f, 7, 2, false)
        stages[2] = StageRecord(true, 1, 200f, 3, 0, true)
        stages[10] = StageRecord(false, 0, 0f, 0, 5, false)
        upgrades["vitality"] = 4; upgrades["power"] = 3; upgrades["reload"] = 2; upgrades["magazine"] = 5
        survivalRecords.add(SurvivalRecord("Ana", 120, 4, 50, 800, "2026-10-01"))
        supplyAdsDay = "2026-10-18"; supplyAdsToday = 3
        stats.kills = 9; stats.headshots = 3; stats.wins = 2; stats.crystals = 4; stats.playSeconds = 3600L
        stats.adsWatched = 5; stats.loginDays = 6; stats.bestCombo = 7; stats.pvpKills = 1; stats.pvpWins = 1
        stats.pvpMatches = 2; stats.chests = 3; stats.nests = 4; stats.distance = 1234L; stats.losses = 2
        stats.survivalRuns = 2; stats.bestWave = 6; stats.bestSurvivalSeconds = 300
        lastLoginDay = "2026-10-18"; calendarClaims = 2; lastCalendarClaim = "2026-10-18"; dailyDay = "2026-10-18"
        daily.add(DailyMission("kill", 12, true))
        dailyBonusClaimed = true; adsDay = "2026-10-18"; adsToday = 2; chestReadyAt = 1_760_000_000_000L
        tutorialVersion = 1; tutorialMask = 0b10101; playTapsSinceAd = 2; missionsSinceInterstitial = 3; lastAdAt = 1_760_000_000_001L
        settings.sensitivity = 1.5f; settings.musicVolume = 0.3f; settings.sfxVolume = 0.8f; settings.aimAssist = false
        settings.autoFire = false; settings.invertY = true; settings.vibration = false; settings.quality = 2
        settings.reminders = false; settings.showFps = true; settings.leftHanded = true; settings.playerName = "Ze"
        settings.cinematic = true; settings.hudLayout = "a=1,2"; settings.hudOpacity = 0.6f
        saveExtras["futuro"] = "[{\"a\":[1,2]}]"
        expeditions.add(ExpeditionRecord("ZZ-80W0-Y4G0-100RS", ExpeditionKind.CAMPAIGN, 7, ContractId.SILENT_RECOVERY, ExpeditionOutcome.VICTORY, 212, 19, 6, 38, 118, "2026-10-18"))
        expeditions.remember(ExpeditionPlan(ExpeditionKind.CAMPAIGN, 7, 5, ContractId.RED_NIGHT))
        expeditions.grantModule("nucleo_instavel")
    }

    @Test
    fun fullProgressRoundTrips() {
        val a = SaveCodec.encode(fullProgress(), "x")
        val decoded = ok(a)
        val b = SaveCodec.encode(decoded.progress, "x")
        assertEquals(SaveFixtures.canon(JSONObject(a)), SaveFixtures.canon(JSONObject(b)))
        assertEquals(SaveCodec.SCHEMA_VERSION, JSONObject(a).getInt("version"))
        assertEquals(777, decoded.progress.diamonds)
        assertEquals("Bia", decoded.progress.currentHero.name)
        assertTrue(JSONObject(b).has("futuro"))
        // O histórico de expedições (campo "expeditions") sobrevive ao ida-e-volta.
        assertEquals(1, decoded.progress.expeditions.records.size)
        assertEquals(ContractId.RED_NIGHT, decoded.progress.expeditions.lastCampaign)
        assertEquals(1, decoded.progress.expeditions.modules["nucleo_instavel"])
        assertEquals(1, decoded.progress.expeditions.winsOf(ContractId.SILENT_RECOVERY))
    }

    @Test
    fun legacyV4FixtureDecodesWithEveryValue() {
        val d = ok(SaveFixtures.V4_JSON)
        val p = d.progress
        assertEquals(4, d.schemaFound)
        assertEquals(1234, p.diamonds)
        assertEquals(1, p.selectedHero)
        assertEquals("Bia", p.currentHero.name)
        assertTrue(p.stages[2]!!.skipped)
        assertEquals(4, p.upgrade("magazine"))
        assertFalse(p.upgrades.containsKey("inexistente"))
        assertEquals(6, p.stats.adsWatched)
        assertEquals(3, p.playTapsSinceAd)
        assertEquals(2, p.settings.quality)
        assertTrue(p.settings.leftHanded)
        assertEquals(SaveFixtures.STARTER_INVENTORY, p.inventory.encode())
    }

    @Test
    fun adRevenueFieldsAreIgnoredAndNeverWrittenAgain() {
        val p = ok(SaveFixtures.V4_JSON).progress
        assertTrue(Progress::class.java.declaredFields.none { "revenue" in it.name.lowercase() })
        val out = SaveCodec.encode(p)
        assertFalse(out.contains("adRevenue"))
        assertTrue(p.saveExtras.isEmpty())
    }

    @Test
    fun unknownTopLevelKeysSurviveARoundTrip() {
        val raw = SaveFixtures.v4With { it.put("futuro", JSONObject("""{"a":[1,2]}""")); it.put("texto", "123") }
        val out = JSONObject(SaveCodec.encode(ok(raw).progress))
        assertEquals("""{"a":[1,2]}""", SaveFixtures.canon(out.getJSONObject("futuro")))
        assertEquals("123", out.get("texto"))
        assertTrue(out.get("texto") is String)
    }

    @Test
    fun knownKeysCoverEveryEncodedKey() {
        val o = JSONObject(SaveCodec.encode(Progress(), "x"))
        val missing = o.keys().asSequence().filter { it !in SaveCodec.KNOWN_KEYS }.toList()
        assertTrue("chaves fora de KNOWN_KEYS: $missing", missing.isEmpty())
    }

    @Test
    fun missingFieldsGetDefaults() {
        val p = ok("""{"version":4}""").progress
        // Progress() ainda não tem as chaves de melhoria; o decode as materializa com 0. Comparamos já normalizados.
        val baseline = ok(SaveCodec.encode(Progress())).progress
        assertEquals(SaveFixtures.canon(JSONObject(SaveCodec.encode(baseline))), SaveFixtures.canon(JSONObject(SaveCodec.encode(p))))
    }

    @Test
    fun emptyObjectOrForeignJsonIsInvalid() {
        for (raw in listOf("{}", "[]", "null", "", "{\"foo\":1}")) assertInvalid(raw)
    }

    @Test
    fun truncatedJsonIsInvalid() = assertInvalid(SaveFixtures.V4_JSON.dropLast(40))

    @Test
    fun bomAndWhitespaceAreTolerated() {
        ok("﻿  " + SaveFixtures.V4_JSON + "\n")
    }

    @Test
    fun wrongContainerTypeIsInvalidNotReset() {
        assertInvalid(SaveFixtures.v4With { it.put("stages", JSONArray()) })
        assertInvalid(SaveFixtures.v4With { it.put("heroes", JSONObject()) })
        assertInvalid(SaveFixtures.v4With { it.put("stats", "x") })
        assertInvalid(SaveFixtures.v4With { it.put("inventory", JSONObject()) })
    }

    @Test
    fun wrongScalarInCriticalFieldIsInvalid() {
        assertInvalid(SaveFixtures.v4With { it.put("diamonds", "muitos") })
        assertInvalid(SaveFixtures.v4With { it.put("stages", JSONObject().put("1", JSONObject().put("stars", "x"))) })
    }

    @Test
    fun droppedLightEntriesProduceWarnings() {
        val a = ok(SaveFixtures.v4With { it.put("survival", JSONArray("""[1,{"name":"A"}]""")) })
        assertTrue(a.warnings.contains("ranking de sobrevivência"))
        val b = ok(SaveFixtures.v4With { it.put("daily", JSONArray("[null]")) })
        assertTrue(b.warnings.contains("missões diárias"))
        assertEquals("", b.progress.dailyDay)
    }

    @Test
    fun nonFiniteFloatsNeverBreakEncode() {
        val p = Progress()
        p.stages[1] = StageRecord(true, 1, Float.NaN, 0, 0, false)
        p.settings.sensitivity = Float.POSITIVE_INFINITY
        val d = ok(SaveCodec.encode(p))
        assertEquals(0f, d.progress.stages[1]!!.bestTime, 0f)
        assertEquals(1.0f, d.progress.settings.sensitivity, 0.001f)
    }

    @Test
    fun newerSchemaIsReadLeniently() {
        val d = ok(SaveFixtures.v4With { it.put("version", 99) })
        assertTrue(d.isNewerThanApp)
        assertEquals(99, d.schemaFound)
    }

    @Test
    fun negativeCountersAreClamped() {
        val p = ok(SaveFixtures.v4With { it.put("diamonds", -5); it.put("stats", JSONObject().put("kills", -1)) }).progress
        assertEquals(0, p.diamonds)
        assertEquals(0, p.stats.kills)
    }

    @Test
    fun campaignPlayTapCounterIsClamped() {
        val p = ok(SaveFixtures.v4With { it.put("playTapsSinceAd", 99) }).progress
        assertEquals(Rules.CAMPAIGN_PLAY_AD_EVERY - 1, p.playTapsSinceAd)
    }
}
