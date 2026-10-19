package com.fabiosilva.ecosdeeldra.tutorial

import com.fabiosilva.ecosdeeldra.engine.Hud
import com.fabiosilva.ecosdeeldra.engine.Res
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialSnapshotTest {
    private fun campaignHud(): FloatArray = FloatArray(Hud.SIZE).also {
        it[Hud.ISLAND] = 1f; it[Hud.NEST_TOTAL] = 3f; it[Hud.NEST_NEAR] = -1f; it[Hud.NEST_HP] = -1f
    }

    private fun setObj(h: FloatArray, i: Int, x: Float, z: Float, state: Int) {
        val o = Hud.OBJ + i * Hud.OBJ_STRIDE
        h[o] = x; h[o + 1] = z; h[o + 2] = state.toFloat()
    }

    @Test
    fun readsDistanceYawShotsAndNearNest() {
        val h = campaignHud()
        h[Hud.DISTANCE] = 12.5f; h[Hud.CAM_YAW] = 1.25f; h[Hud.NEST_NEAR] = 1f; h[Hud.NEST_HP] = 0.5f
        val r = FloatArray(Res.SIZE); r[Res.SHOTS] = 7f
        val s = TutorialSnapshot.from(h, r)
        assertTrue(s.valid)
        assertEquals(12.5f, s.distanceWalked, 0f)
        assertEquals(1.25f, s.yaw, 0f)
        assertEquals(7, s.shots)
        assertEquals(1, s.nearNest)
        assertEquals(0.5f, s.nearNestHp, 0f)
    }

    @Test
    fun nearestNestIgnoresDestroyedAndPortalEntries() {
        val h = campaignHud()
        h[Hud.OBJ_COUNT] = 4f
        h[Hud.PLAYER_X] = 0f; h[Hud.PLAYER_Z] = 0f
        setObj(h, 0, 10f, 0f, 2)     // destruído, mais perto
        setObj(h, 1, 100f, 0f, 1)    // vivo
        setObj(h, 2, 50f, 0f, 1)     // vivo, o mais perto entre os vivos
        setObj(h, 3, 5f, 0f, 3)      // portal
        val s = TutorialSnapshot.from(h, FloatArray(Res.SIZE))
        assertEquals(2, s.nearestNestIndex)
        assertEquals(50f, s.nearestNestDist, 0.001f)
    }

    @Test
    fun noLiveNestMeansNoNearest() {
        val h = campaignHud()
        h[Hud.OBJ_COUNT] = 1f
        setObj(h, 0, 10f, 0f, 2)
        val s = TutorialSnapshot.from(h, FloatArray(Res.SIZE))
        assertEquals(-1, s.nearestNestIndex)
        assertEquals(Float.MAX_VALUE, s.nearestNestDist, 0f)
    }

    @Test
    fun deadAndVictoryFromState() {
        val h = campaignHud()
        val r = FloatArray(Res.SIZE)
        h[Hud.STATE] = 1f
        assertTrue(TutorialSnapshot.from(h, r).dead)
        assertFalse(TutorialSnapshot.from(h, r).victory)
        h[Hud.STATE] = 2f
        assertTrue(TutorialSnapshot.from(h, r).victory)
        assertFalse(TutorialSnapshot.from(h, r).dead)
    }

    @Test
    fun survivalSnapshotIsNotValid() {
        val h = campaignHud(); h[Hud.SURV] = 1f
        assertFalse(TutorialSnapshot.from(h, FloatArray(Res.SIZE)).valid)
    }
}
