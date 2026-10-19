package com.fabiosilva.ecosdeeldra.tutorial

import com.fabiosilva.ecosdeeldra.data.Progress
import com.fabiosilva.ecosdeeldra.data.StageRecord
import com.fabiosilva.ecosdeeldra.data.save.DecodeOutcome
import com.fabiosilva.ecosdeeldra.data.save.SaveCodec
import com.fabiosilva.ecosdeeldra.save.SaveFixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Persistência do tutorial no pacote data/save (protocolo aditivo: o esquema continua em 5). */
class TutorialSaveTest {
    private fun decode(raw: String): Progress = (SaveCodec.decode(raw) as DecodeOutcome.Ok).progress

    @Test
    fun tutorialObjectRoundTrips() {
        val p = Progress().apply { tutorialVersion = TutorialPolicy.VERSION; tutorialMask = 0b10111 }
        val json = JSONObject(SaveCodec.encode(p))
        assertEquals(0b10111, json.getJSONObject("tutorial").getInt("mask"))
        assertEquals(TutorialPolicy.VERSION, json.getJSONObject("tutorial").getInt("version"))
        assertFalse(json.getBoolean("tutorialSeen"))
        val back = decode(json.toString())
        assertEquals(TutorialPolicy.VERSION, back.tutorialVersion)
        assertEquals(0b10111, back.tutorialMask)
        assertFalse(back.tutorialSeen)
    }

    @Test
    fun finishedTutorialSetsLegacyFlag() {
        val p = Progress().apply { tutorialVersion = TutorialPolicy.VERSION; tutorialMask = Lesson.ALL }
        val back = decode(SaveCodec.encode(p))
        assertTrue(back.tutorialSeen)
        assertTrue(JSONObject(SaveCodec.encode(p)).getBoolean("tutorialSeen"))
    }

    @Test
    fun schemaVersionIsNotBumpedByTheAdditiveField() {
        assertEquals(5, SaveCodec.SCHEMA_VERSION)
        assertEquals(5, JSONObject(SaveCodec.encode(Progress())).getInt("version"))
    }

    @Test
    fun legacySaveWithVictoriesIsAVeteran() {
        // V4_JSON tem fases vencidas e tutorialSeen = true, mas nenhum objeto "tutorial".
        val p = decode(SaveFixtures.V4_JSON)
        assertEquals(TutorialPolicy.VERSION, p.tutorialVersion)
        assertEquals(Lesson.ALL, p.tutorialMask)
        assertTrue(p.tutorialSeen)
        assertEquals(null, TutorialPolicy.maskToTeach(p.tutorialVersion, p.tutorialMask, p.completedStages()))
    }

    @Test
    fun legacyTutorialSeenWithoutVictoryStillTeaches() {
        val o = JSONObject(SaveFixtures.V4_JSON)
        o.put("stages", JSONObject())
        val p = decode(o.toString())
        assertEquals(0, p.tutorialVersion)
        assertEquals(0, p.tutorialMask)
        assertFalse(p.tutorialSeen)
        assertEquals(0, TutorialPolicy.maskToTeach(p.tutorialVersion, p.tutorialMask, p.completedStages()))
    }

    @Test
    fun partialTutorialSurvivesAsMask() {
        val p = Progress().apply {
            tutorialVersion = 0; tutorialMask = 0b11111
            stages[1] = StageRecord()   // fase não vencida
        }
        val back = decode(SaveCodec.encode(p))
        assertEquals(0b11111, back.tutorialMask)
        assertEquals(0b11111, TutorialPolicy.maskToTeach(1, back.tutorialMask, back.completedStages()))
    }

    @Test
    fun maskIsClampedToKnownLessons() {
        val o = JSONObject(SaveCodec.encode(Progress()))
        o.put("tutorial", JSONObject().put("version", 1).put("mask", 0x7FFF))
        assertEquals(Lesson.ALL, decode(o.toString()).tutorialMask)
    }
}
