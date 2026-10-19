package com.fabiosilva.ecosdeeldra.tutorial

import com.fabiosilva.ecosdeeldra.data.Difficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialPolicyTest {
    @Test
    fun newPlayerGetsFullTutorial() {
        assertEquals(0, TutorialPolicy.maskToTeach(0, 0, 0))
    }

    @Test
    fun partialMaskContinues() {
        assertEquals(0b11111, TutorialPolicy.maskToTeach(1, 0b11111, 0))
    }

    @Test
    fun completedTutorialStopsTeaching() {
        assertNull(TutorialPolicy.maskToTeach(1, Lesson.ALL, 0))
        assertTrue(TutorialPolicy.isDone(1, Lesson.ALL))
        assertFalse(TutorialPolicy.isDone(0, Lesson.ALL))
        assertFalse(TutorialPolicy.isDone(1, 0b111))
    }

    @Test
    fun veteranNeverSeesTutorial() {
        assertNull(TutorialPolicy.maskToTeach(0, 0, 1))
        assertNull(TutorialPolicy.maskToTeach(1, 0b1, 5))
    }

    @Test
    fun olderVersionRestartsForNonVeterans() {
        assertEquals(0, TutorialPolicy.maskToTeach(0, 0b1111, 0))
    }

    @Test
    fun migrateKeepsSavedObject() {
        assertEquals(TutorialPolicy.Saved(1, 0b101), TutorialPolicy.migrate(true, 1, 0b101, 0))
        assertEquals(TutorialPolicy.Saved(1, Lesson.ALL), TutorialPolicy.migrate(true, 1, 0xFFFF, 0))
    }

    @Test
    fun migrateVeteranSkipsTutorial() {
        assertEquals(TutorialPolicy.Saved(TutorialPolicy.VERSION, Lesson.ALL), TutorialPolicy.migrate(false, 0, 0, 2))
    }

    @Test
    fun legacyTutorialSeenAloneDoesNotCountAsLearned() {
        assertEquals(TutorialPolicy.Saved(0, 0), TutorialPolicy.migrate(false, 0, 0, 0))
    }

    @Test
    fun tuneLowersPressureOnlyForTutorialRun() {
        val d = Difficulty(zombieCount = 10, zombieHealth = 1.2f, zombieDamage = 10f, zombieSpeed = 1.1f, playerHealth = 100f, magazine = 30, power = 1f, reloadSpeed = 1f)
        val t = TutorialPolicy.tune(d)
        assertEquals(4, t.zombieCount)
        assertEquals(7f, t.zombieDamage, 0.001f)
        assertEquals(125f, t.playerHealth, 0.001f)
        assertEquals(d.zombieHealth, t.zombieHealth, 0f)
        assertEquals(d.zombieSpeed, t.zombieSpeed, 0f)
        assertEquals(2, TutorialPolicy.tune(d.copy(zombieCount = 2)).zombieCount)
    }
}
