// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor under the duty scheduler.
 *
 * `WatchShiftController.schedule()` treats a start in the past as "begin immediately", with the
 * start chime — which is right for an alarm that fires late and wrong for a stepper the user is
 * still holding. The editor therefore clamps everything it books to `earliestBookableStart`, and
 * this pins what that floor is: never in the past, always on a five-minute boundary, and equal to
 * "now" exactly when now already sits on one.
 */
class BookableStartTest {

    private val step = WatchShiftController.STEP_MILLIS

    @Test
    fun `an instant on a step boundary is bookable as it stands`() {
        val now = 42L * step
        assertEquals(now, WatchShiftController.earliestBookableStart(now))
    }

    @Test
    fun `an instant off the boundary rounds up, never down`() {
        val base = 42L * step
        assertEquals(base + step, WatchShiftController.earliestBookableStart(base + 1L))
        assertEquals(base + step, WatchShiftController.earliestBookableStart(base + step - 1L))
    }

    @Test
    fun `the floor is never in the past and never a full step away`() {
        var now = 1_787_321_679_123L // an arbitrary real instant, deliberately off-boundary
        repeat(1_000) {
            val floor = WatchShiftController.earliestBookableStart(now)
            assertTrue("floor $floor behind now $now", floor >= now)
            assertTrue("floor $floor more than a step past now $now", floor - now < step)
            assertEquals("floor $floor off the five-minute grid", 0L, floor % step)
            now += 61_237L // an awkward stride so the sweep crosses boundaries at odd offsets
        }
    }
}
