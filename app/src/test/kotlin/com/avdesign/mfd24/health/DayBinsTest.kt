// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DayBinsTest {

    private val kyiv = 3 * 3_600_000   // UTC+3, a whole-hour zone
    private val kathmandu = 5 * 3_600_000 + 45 * 60_000   // UTC+5:45, the awkward one

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `the day turns at local midnight, not at midnight utc`() {
        // 21:00Z is already tomorrow in Kyiv, and the bin index says so.
        val evening = at("2026-08-24T21:00:00Z")
        assertEquals(
            DayBins.localEpochDay(at("2026-08-25T00:30:00Z"), kyiv),
            DayBins.localEpochDay(evening, kyiv),
        )
        assertEquals(0, DayBins.binIndex(evening, kyiv))
        assertEquals(95, DayBins.binIndex(at("2026-08-24T20:59:00Z"), kyiv))
    }

    @Test
    fun `bins run 0 to 95 across a whole day`() {
        val start = DayBins.dayStartMillis(at("2026-08-24T12:00:00Z"), kyiv)
        for (i in 0 until DayBins.BIN_COUNT) {
            val inside = start + i * DayBins.BIN_MILLIS + 1000L
            assertEquals(i, DayBins.binIndex(inside, kyiv))
        }
        // One millisecond past the last bin is the next day's first.
        assertEquals(0, DayBins.binIndex(start + DayBins.DAY_MILLIS, kyiv))
    }

    @Test
    fun `dayStart is the midnight that opens the bin's own day`() {
        val t = at("2026-08-24T09:17:00Z")
        val start = DayBins.dayStartMillis(t, kathmandu)
        assertEquals(0, DayBins.binIndex(start, kathmandu))
        assertTrue(start <= t && t < start + DayBins.DAY_MILLIS)
    }

    /**
     * Daylight saving moves the offset, not the arithmetic: the day boundary follows the wearer's
     * clock. Spring forward leaves an hour of bins that no tick can land in (absent, honestly);
     * autumn's repeated hour is written twice and the later write wins. Totals survive both,
     * because a bin's steps are a difference of two counter readings, not a recount.
     */
    @Test
    fun `an offset change moves the boundary with the wearer's clock`() {
        // 21:30Z on the night Europe puts its clocks back: 00:30 in summer time, 23:30 in
        // winter time — the same instant, two different local days, and the log follows the
        // clock on the wrist rather than the one at Greenwich.
        val t = at("2026-10-24T21:30:00Z")
        val summer = DayBins.localEpochDay(t, 3 * 3_600_000)
        val winter = DayBins.localEpochDay(t, 2 * 3_600_000)
        assertEquals(summer, winter + 1)
        assertEquals(2, DayBins.binIndex(t, 3 * 3_600_000))    // 00:30 → the third quarter-hour
        assertEquals(94, DayBins.binIndex(t, 2 * 3_600_000))   // 23:30 → the second-to-last
    }

    @Test
    fun `step deltas survive a first reading and a reboot`() {
        // The first read is a baseline and nothing else. Found on the wrist: the recorder's very
        // first tick reported the whole counter since boot — twelve thousand steps painted onto
        // the quarter-hour it happened to start in.
        assertEquals(0L, DayBins.stepDelta(0L, -1L))
        assertEquals(0L, DayBins.stepDelta(12_266L, -1L))
        assertEquals(180L, DayBins.stepDelta(4_300L, 4_120L))    // ordinary case
        assertEquals(0L, DayBins.stepDelta(4_120L, 4_120L))      // nothing walked
        // The counter restarted below its own last value: those 37 steps were taken since the
        // reboot, so they are real and recent. What is lost is only what happened between the
        // last reading and the restart — lost, rather than guessed at.
        assertEquals(37L, DayBins.stepDelta(37L, 9_000L))
    }

    @Test
    fun `a still bin is watched, worn, off charge and not moving`() {
        val still = DayBins.FLAG_SAMPLED or DayBins.FLAG_ON_BODY
        assertTrue(DayBins.isStillAwake(still))
        assertFalse("an unwatched bin is not stillness", DayBins.isStillAwake(DayBins.FLAG_ON_BODY))
        assertFalse(DayBins.isStillAwake(still or DayBins.FLAG_MOVING))
        assertFalse(DayBins.isStillAwake(still or DayBins.FLAG_CHARGING))
        assertFalse(DayBins.isStillAwake(still or DayBins.FLAG_SLEEP))
    }
}
