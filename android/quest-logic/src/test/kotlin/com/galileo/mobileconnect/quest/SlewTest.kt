package com.galileo.mobileconnect.quest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlewTrackerTest {
    private fun started(): SlewTracker = SlewTracker().apply { begin("M31", 10.0, 41.0, 100.0 to 41.0, 0L) }

    @Test
    fun `separation is the angle on the sky`() {
        assertEquals(90.0, separationDegrees(0.0, 0.0, 90.0, 0.0), 1e-6)
        assertEquals(10.0, separationDegrees(0.0, 80.0, 180.0, 90.0), 1e-6) // across the pole, not 180 degrees of RA
        assertEquals(0.0, separationDegrees(33.0, 12.0, 33.0, 12.0), 1e-6)
    }

    @Test
    fun `progress follows the mount from the start to the target`() {
        val t = started()
        val s0 = assertNotNull(t.update(100.0 to 41.0, true, 1_000L))
        assertEquals(0f, s0.progress, 0.01f)
        val half = assertNotNull(t.update(55.0 to 41.0, true, 3_000L))
        assertTrue(half.progress in 0.4f..0.7f, "was ${half.progress}")
        assertTrue(half.remainingDegrees < s0.remainingDegrees)
        assertFalse(half.arrived)
    }

    @Test
    fun `it arrives when the mount stops slewing, lingers, then clears`() {
        val t = started()
        t.update(100.0 to 41.0, true, 1_000L)
        val done = assertNotNull(t.update(10.0 to 41.0, false, 5_000L))
        assertTrue(done.arrived)
        assertEquals(1f, done.progress)
        assertNotNull(t.update(10.0 to 41.0, false, 7_000L))
        assertNull(t.update(10.0 to 41.0, false, 5_000L + SlewTracker.LINGER_MILLIS + 1))
        assertFalse(t.isActive)
    }

    @Test
    fun `a slew that never starts does not leave the indicator up`() {
        val t = started()
        // before the Master reports slewing, "not slewing" is only the state before the move, not its end
        assertFalse(assertNotNull(t.update(100.0 to 41.0, false, 1_000L)).arrived)
        assertNull(t.update(100.0 to 41.0, false, SlewTracker.NEVER_STARTED_MILLIS + 1))
    }

    @Test
    fun `a mount already on target is shown as arrived`() {
        val t = SlewTracker().apply { begin("M31", 10.0, 41.0, 10.0 to 41.0, 0L) }
        assertTrue(assertNotNull(t.update(10.0 to 41.0, false, 3_000L)).arrived)
    }

    @Test
    fun `nothing is shown without a slew`() {
        assertNull(SlewTracker().update(1.0 to 1.0, true, 0L))
        val t = started()
        t.cancel()
        assertNull(t.update(1.0 to 1.0, true, 0L))
    }
}
