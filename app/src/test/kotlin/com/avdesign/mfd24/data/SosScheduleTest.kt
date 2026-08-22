// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long an unanswered watch keeps calling for help, and how much of the battery that spends.
 *
 * This is here because the alternative way of checking it is sitting next to a wrist for five
 * minutes with a stopwatch, and because the numbers are a promise rather than an implementation
 * detail: the escalation shouts for thirty seconds while somebody might be in the room, then once a
 * minute so a searcher can walk towards it, and then it stops — the record on the dial has to
 * outlast everyone's attention, and it cannot do that on a flat battery.
 */
class SosScheduleTest {

    /** About the length of one unit in the shipped signal: buzz, then two audible repetitions. */
    private val unit = 13_000L

    @Test
    fun `the opening burst covers thirty seconds`() {
        val first = SosSchedule.burst(0, unit)
        assertNotNull(first)
        assertEquals(0L, first!!.startOffsetMillis)
        // Rounded up, never down: a burst that stops at 26 s is not a thirty-second burst.
        assertTrue(first.units * unit >= SosSchedule.FIRST_BURST_MILLIS)
        assertTrue((first.units - 1) * unit < SosSchedule.FIRST_BURST_MILLIS)
    }

    @Test
    fun `later bursts are doubles, a minute apart`() {
        var previous = SosSchedule.burst(1, unit)!!
        assertEquals(SosSchedule.UNITS_PER_LATER_BURST, previous.units)
        for (index in 2 until SosSchedule.BURSTS) {
            val burst = SosSchedule.burst(index, unit)!!
            assertEquals(SosSchedule.UNITS_PER_LATER_BURST, burst.units)
            assertEquals(
                SosSchedule.BURST_GAP_MILLIS,
                burst.startOffsetMillis - previous.startOffsetMillis,
            )
            previous = burst
        }
    }

    @Test
    fun `the schedule ends, and it ends inside five minutes`() {
        assertNull(SosSchedule.burst(SosSchedule.BURSTS, unit))
        assertNull(SosSchedule.burst(-1, unit))
        // The old escalation sounded continuously for a full five minutes. This one is shorter in
        // wall time and spends a small fraction of it actually driving the vibrator and the speaker.
        assertTrue(SosSchedule.totalMillis(unit) < 5 * 60_000L)
    }

    @Test
    fun `six signalling events in total, counting the nudge`() {
        // The nudge is not in the schedule — it is the vibration that opens the answer window —
        // so five bursts here plus that one is the six the escalation is allowed to spend.
        assertEquals(5, SosSchedule.BURSTS)
    }

    @Test
    fun `the signal is quiet for most of the escalation`() {
        // What the change is actually for. Sum what the bursts occupy and compare it against the
        // wall time they span: most of the escalation must be silence, or nothing was saved.
        var sounding = 0L
        for (index in 0 until SosSchedule.BURSTS) {
            sounding += SosSchedule.burst(index, unit)!!.units * unit
        }
        assertTrue(sounding * 2 < SosSchedule.totalMillis(unit))
    }
}
