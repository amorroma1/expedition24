// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the day's step count is measured from.
 *
 * `TYPE_STEP_COUNTER` counts from the last reboot, so a readout of steps *today* is a subtraction,
 * and the subtraction has two cases that are easy to get wrong and slow to catch on a wrist: a day
 * boundary, which shows up once a day, and a reboot part-way through a day, which without the rule
 * leaves the readout negative until midnight.
 */
class StepBaselineTest {

    private val day = 20_320L

    @Test
    fun `the first reading of a day is where the day starts from`() {
        // Nothing on file: whatever the counter says now is zero steps today.
        assertEquals(8_400L, SensorSlots.baselineFor(8_400L, 0L, -1L, day))
    }

    @Test
    fun `later the same day keeps counting from the same point`() {
        assertEquals(8_400L, SensorSlots.baselineFor(9_100L, 8_400L, day, day))
        assertEquals(700L, 9_100L - SensorSlots.baselineFor(9_100L, 8_400L, day, day))
    }

    @Test
    fun `a new day starts again from here`() {
        assertEquals(9_100L, SensorSlots.baselineFor(9_100L, 8_400L, day, day + 1))
        assertEquals(0L, 9_100L - SensorSlots.baselineFor(9_100L, 8_400L, day, day + 1))
    }

    @Test
    fun `a reboot mid-day restarts the tally rather than going negative`() {
        // The hardware counter is back at 120 while the baseline still says 8400. Subtracting would
        // give -8280 steps, and it would stay wrong until midnight.
        val baseline = SensorSlots.baselineFor(120L, 8_400L, day, day)
        assertEquals(120L, baseline)
        assertEquals(0L, 120L - baseline)
    }

    @Test
    fun `the first reading of a day subtracts from itself, which is why 0 is not published`() {
        // baselineFor returning the counter itself means "steps today" is exactly 0 by
        // construction. SensorSlots must therefore hold the row at dashes on that first sample:
        // a freshly installed face showed 0 to somebody who had already walked four thousand
        // steps, which was the whole reason the platform's own daily total is preferred now.
        val counter = 8_400L
        assertEquals(counter, SensorSlots.baselineFor(counter, 0L, -1L, day))
        assertEquals(0L, counter - SensorSlots.baselineFor(counter, 0L, -1L, day))
    }
}
