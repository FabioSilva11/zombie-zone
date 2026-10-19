package com.fabiosilva.ecosdeeldra.tutorial

import com.fabiosilva.ecosdeeldra.engine.Ev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialMachineTest {
    private fun obj(done: Int = 0, crystals: Int = 0, portal: Boolean = false) = Objective(true, done, 3, crystals, portal, false)

    private fun snap(
        dist: Float = 0f, yaw: Float = 0f, shots: Int = 0, o: Objective = obj(),
        near: Int = -1, hp: Float = -1f, nearestIdx: Int = 0, nearestDist: Float = 300f,
        valid: Boolean = true, dead: Boolean = false, victory: Boolean = false,
    ) = TutorialSnapshot(valid, dead, victory, yaw, dist, shots, o, near, hp, nearestIdx, nearestDist)

    private fun tick(s: TutorialState, snap: TutorialSnapshot, t: Long = 0L) = TutorialMachine.reduce(s, TutorialInput.Tick(snap, t))

    @Test
    fun startsOnMoveLesson() {
        val st = tick(TutorialMachine.start(0), snap())
        assertEquals(Lesson.MOVE, st.hint!!.lesson)
        assertEquals(1, st.hint!!.step)
        assertEquals(9, st.hint!!.total)
    }

    @Test
    fun moveCompletesAfterSixMeters() {
        var s = tick(TutorialMachine.start(0), snap(dist = 100f)).state
        s = tick(s, snap(dist = 105f), 10).state
        assertFalse(s.isDone(Lesson.MOVE))
        val st = tick(s, snap(dist = 106.1f), 20)
        assertTrue(st.state.isDone(Lesson.MOVE))
        assertTrue(st.hint!!.celebrate)
    }

    @Test
    fun lookAccumulatesAcrossAngleWrap() {
        var s = tick(TutorialMachine.start(Lesson.MOVE.bit), snap(yaw = 3.0f)).state
        s = tick(s, snap(yaw = -3.0f), 10).state
        assertEquals(0.283f, s.yawAccum, 0.01f)
        assertFalse(s.isDone(Lesson.LOOK))
        s = tick(s, snap(yaw = -2.0f), 20).state
        assertTrue(s.isDone(Lesson.LOOK))
    }

    @Test
    fun shootCompletesAfterThreeShots() {
        val mask = Lesson.MOVE.bit or Lesson.LOOK.bit
        var s = tick(TutorialMachine.start(mask), snap(shots = 10)).state
        s = tick(s, snap(shots = 12), 10).state
        assertFalse(s.isDone(Lesson.SHOOT))
        s = tick(s, snap(shots = 13), 20).state
        assertTrue(s.isDone(Lesson.SHOOT))
    }

    @Test
    fun mapLessonCompletesOnMapOpenedWithoutImplyingOthers() {
        val st = TutorialMachine.reduce(TutorialMachine.start(0), TutorialInput.MapOpened(0))
        assertTrue(st.state.isDone(Lesson.MAP))
        assertFalse(st.state.isDone(Lesson.MOVE))
        assertEquals(Lesson.MAP.bit, st.newlyDone)
    }

    @Test
    fun mapLessonSkippedSilentlyWhenNestWithin150m() {
        val mask = Lesson.MOVE.bit or Lesson.LOOK.bit or Lesson.SHOOT.bit
        val st = tick(TutorialMachine.start(mask), snap(nearestDist = 120f))
        assertTrue(st.state.isDone(Lesson.MAP))
        assertNull(st.state.celebrate)
        assertEquals(Lesson.MARKER, st.hint!!.lesson)
    }

    @Test
    fun mapShownWhenNestIsFar() {
        val mask = Lesson.MOVE.bit or Lesson.LOOK.bit or Lesson.SHOOT.bit
        assertEquals(Lesson.MAP, tick(TutorialMachine.start(mask), snap(nearestDist = 300f)).hint!!.lesson)
    }

    private val seq = Lesson.MOVE.bit or Lesson.LOOK.bit or Lesson.SHOOT.bit or Lesson.MAP.bit

    @Test
    fun markerCompletesWhenNestIsWithin45m() {
        var s = tick(TutorialMachine.start(seq), snap(nearestDist = 300f)).state
        assertFalse(s.isDone(Lesson.MARKER))
        s = tick(s, snap(near = 0, hp = 1f, nearestDist = 40f), 10).state
        assertTrue(s.isDone(Lesson.MARKER))
    }

    @Test
    fun markerProgressGrowsWhileApproaching() {
        var st = tick(TutorialMachine.start(seq), snap(nearestDist = 300f))
        assertEquals(Lesson.MARKER, st.hint!!.lesson)
        assertEquals(0f, st.hint!!.progress!!, 0.001f)
        st = tick(st.state, snap(nearestDist = 172.5f), 10)
        assertEquals(0.5f, st.hint!!.progress!!, 0.01f)
    }

    @Test
    fun coreLessonAppearsOnlyNearNestAndCompletesOnFirstDamage() {
        val mask = seq or Lesson.MARKER.bit
        assertNull(tick(TutorialMachine.start(mask), snap(nearestDist = 200f)).hint)
        var st = tick(TutorialMachine.start(mask), snap(near = 0, hp = 1f, nearestDist = 30f))
        assertEquals(Lesson.CORE, st.hint!!.lesson)
        assertEquals(0, st.hint!!.focusIndex)
        st = tick(st.state, snap(near = 0, hp = 0.9f, nearestDist = 30f), 10)
        assertTrue(st.state.isDone(Lesson.CORE))
        assertTrue(st.hint!!.celebrate)
    }

    @Test
    fun destroyCompletesOnNestDestroyedEventAndOnSnapshotFallback() {
        val mask = seq or Lesson.MARKER.bit or Lesson.CORE.bit
        val byEvent = TutorialMachine.reduce(TutorialMachine.start(mask), TutorialInput.Engine(Ev.NEST_DESTROYED, 0, 1, 3, 0))
        assertTrue(byEvent.state.isDone(Lesson.DESTROY))
        // evento perdido: o snapshot com ninho destruído fecha do mesmo jeito
        val bySnap = tick(TutorialMachine.start(mask), snap(o = obj(done = 1)))
        assertTrue(bySnap.state.isDone(Lesson.DESTROY))
    }

    @Test
    fun destroyProgressFollowsNestHealth() {
        val mask = seq or Lesson.MARKER.bit or Lesson.CORE.bit
        val st = tick(TutorialMachine.start(mask), snap(near = 0, hp = 0.25f, nearestDist = 20f))
        assertEquals(Lesson.DESTROY, st.hint!!.lesson)
        assertEquals(0.75f, st.hint!!.progress!!, 0.001f)
    }

    @Test
    fun crystalLessonShowsWhenCrystalIsPendingAndCompletesOnEvent() {
        val mask = seq or Lesson.MARKER.bit or Lesson.CORE.bit or Lesson.DESTROY.bit
        var st = tick(TutorialMachine.start(mask), snap(o = obj(done = 1)))
        assertEquals(Lesson.CRYSTAL, st.hint!!.lesson)
        val ev = TutorialMachine.reduce(st.state, TutorialInput.Engine(Ev.CRYSTAL, 0, 1, 3, 100))
        assertTrue(ev.state.isDone(Lesson.CRYSTAL))
        assertEquals(Lesson.CRYSTAL, ev.state.celebrate)
    }

    @Test
    fun portalLessonShowsWhenPortalOpensAndFinishesOnVictory() {
        val mask = Lesson.ALL and Lesson.PORTAL.bit.inv()
        val st = tick(TutorialMachine.start(mask), snap(o = obj(3, 3, portal = true)))
        assertEquals(Lesson.PORTAL, st.hint!!.lesson)
        val win = TutorialMachine.reduce(st.state, TutorialInput.Engine(Ev.VICTORY, 0, 0, 0, 10))
        assertTrue(win.finished)
        assertEquals(Lesson.ALL, win.state.doneMask)
    }

    @Test
    fun contextualLessonOverridesSequentialOne() {
        // Mapa pendente, mas perto do ninho: o núcleo vem primeiro.
        val mask = Lesson.MOVE.bit or Lesson.LOOK.bit or Lesson.SHOOT.bit
        val st = tick(TutorialMachine.start(mask), snap(near = 1, hp = 1f, nearestDist = 30f))
        assertEquals(Lesson.CORE, st.hint!!.lesson)
    }

    @Test
    fun completingALessonImpliesEarlierOnesExceptMap() {
        val st = tick(TutorialMachine.start(0), snap(near = 0, hp = 0.5f, nearestDist = 30f))
        assertTrue(st.state.isDone(Lesson.MOVE))
        assertTrue(st.state.isDone(Lesson.SHOOT))
        assertTrue(st.state.isDone(Lesson.MARKER))
        assertTrue(st.state.isDone(Lesson.CORE))
        // MAP fecha em silêncio só porque o ninho está a <= 150 m
        val far = TutorialMachine.reduce(TutorialMachine.start(0), TutorialInput.Engine(Ev.NEST_DESTROYED, 0, 0, 0, 0))
        assertFalse(far.state.isDone(Lesson.MAP))
    }

    @Test
    fun silentCompletionDoesNotCelebrate() {
        // Ativa é MOVE, mas o jogador já destruiu um ninho (fatos de outras lições chegam juntos).
        val st = tick(TutorialMachine.start(0), snap(dist = 0f, near = 0, hp = 0.5f, nearestDist = 30f))
        assertEquals(Lesson.CORE, st.state.celebrate)           // só a ativa (CORE) celebra
        assertTrue(st.state.isDone(Lesson.MOVE))
        assertTrue(st.hint!!.celebrate)
        assertEquals(Lesson.CORE, st.hint!!.lesson)
    }

    @Test
    fun celebrationIsShownBeforeNextHintAndExpires() {
        var s = tick(TutorialMachine.start(0), snap(dist = 0f), 0).state
        val done = tick(s, snap(dist = 7f), 1000)
        assertTrue(done.hint!!.celebrate)
        assertEquals("Muito bem!", done.hint!!.body)
        val during = tick(done.state, snap(dist = 7f), 1000 + TutorialMachine.CELEBRATE_MS - 1)
        assertTrue(during.hint!!.celebrate)
        val after = tick(during.state, snap(dist = 7f), 1000 + TutorialMachine.CELEBRATE_MS + 1)
        assertFalse(after.hint!!.celebrate)
        assertEquals(Lesson.LOOK, after.hint!!.lesson)
    }

    @Test
    fun noHintBetweenNestsAfterFirstCrystal() {
        val mask = seq or Lesson.MARKER.bit or Lesson.CORE.bit or Lesson.DESTROY.bit or Lesson.CRYSTAL.bit
        assertNull(tick(TutorialMachine.start(mask), snap(o = obj(1, 1), nearestDist = 300f)).hint)
    }

    @Test
    fun restoredMaskSkipsDoneLessons() {
        val st = tick(TutorialMachine.start(0b111), snap(nearestDist = 300f))
        assertEquals(Lesson.MAP, st.hint!!.lesson)
        assertEquals(4, st.hint!!.step)
    }

    @Test
    fun deadSnapshotShowsNoHintAndKeepsProgress() {
        val s = TutorialMachine.start(0b11)
        val st = tick(s, snap(dead = true))
        assertNull(st.hint)
        assertEquals(0b11, st.state.doneMask)
    }

    @Test
    fun invalidSnapshotNeverActivates() {
        val st = tick(TutorialMachine.start(0), snap(valid = false, o = Objective.INACTIVE))
        assertNull(st.hint)
        assertNull(st.state.base)
    }

    @Test
    fun skipFinishesAndMarksEverythingDone() {
        val st = TutorialMachine.reduce(TutorialMachine.start(0), TutorialInput.Skip(5))
        assertTrue(st.finished)
        assertEquals(Lesson.ALL, st.state.doneMask)
        assertNull(st.hint)
    }

    @Test
    fun finishedMachineIgnoresFurtherInput() {
        val fin = TutorialMachine.start(Lesson.ALL)
        assertTrue(fin.finished)
        val st = tick(fin, snap(o = obj(1, 0)))
        assertNull(st.hint)
        assertEquals(fin, st.state)
        assertFalse(st.finished)
    }

    @Test
    fun reduceIsDeterministicForSameInputs() {
        val input = TutorialInput.Tick(snap(dist = 3f, yaw = 0.4f, near = 0, hp = 0.8f, nearestDist = 30f), 77L)
        val a = TutorialMachine.reduce(TutorialMachine.start(0b1), input)
        val b = TutorialMachine.reduce(TutorialMachine.start(0b1), input)
        assertEquals(a, b)
        assertNotNull(a.hint)
    }

    @Test
    fun coachBodiesFitEightyCharacters() {
        for (l in Lesson.values()) assertTrue("${l.name} tem ${l.body.length}", l.body.length <= 80)
        assertEquals(0x1FF, Lesson.ALL)
    }
}
