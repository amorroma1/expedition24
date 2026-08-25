// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import org.junit.Assert.assertEquals
import org.junit.Test

class DaySummariesTest {

    private fun day(
        epochDay: Long,
        steps: Int = 8_000,
        resting: Int = 58,
        sleep: Int = 430,
        wake: Int = 1,
        share: Int = 5,
        onset: Int = 23 * 60,
    ) = DaySummary(epochDay, steps, resting, sleep, wake, share, onset)

    @Test
    fun `summaries round-trip and damaged entries are dropped`() {
        val days = arrayOf(day(20_750L, steps = 4_100), day(20_751L, steps = 12_400))
        val packed = DaySummaries.pack(days)
        val back = DaySummaries.parse(packed)
        assertEquals(2, back.size)
        assertEquals(4_100, back[0].steps)
        assertEquals(12_400, back[1].steps)
        assertEquals(23 * 60, back[1].sleepOnsetMinutes)

        assertEquals(0, DaySummaries.parse(null).size)
        assertEquals(0, DaySummaries.parse("").size)
        assertEquals(1, DaySummaries.parse(packed.split(';')[0] + ";junk:1:2").size)
    }

    @Test
    fun `a fortnight is kept, the oldest day falls off, and a day is never doubled`() {
        var days = emptyArray<DaySummary>()
        for (i in 0 until DaySummaries.KEEP_DAYS + 4) {
            days = DaySummaries.appended(days, day(20_700L + i))
        }
        assertEquals(DaySummaries.KEEP_DAYS, days.size)
        assertEquals(20_704L, days.first().epochDay)
        assertEquals(20_717L, days.last().epochDay)

        // Re-closing the same day replaces it rather than stacking a second copy.
        days = DaySummaries.appended(days, day(20_717L, steps = 999))
        assertEquals(DaySummaries.KEEP_DAYS, days.size)
        assertEquals(999, days.last().steps)
    }

    @Test
    fun `fewer than three days is a coincidence, not a baseline`() {
        val two = arrayOf(day(1L), day(2L))
        assertEquals(DaySummaries.NO_BASELINE, DaySummaries.baselineSteps(two))
        assertEquals(DaySummaries.NO_BASELINE, DaySummaries.baselineSleepMinutes(two))
        assertEquals(DaySummaries.NO_BASELINE, DaySummaries.baselineRestingBpm(two))
    }

    @Test
    fun `baselines are medians, so one holiday hike does not raise the bar`() {
        val days = arrayOf(
            day(1L, steps = 6_000), day(2L, steps = 6_400), day(3L, steps = 5_800),
            day(4L, steps = 6_200), day(5L, steps = 31_000),
        )
        assertEquals(6_200, DaySummaries.baselineSteps(days))

        // Days without a resting pulse on file are left out rather than counted as zero.
        val mixed = arrayOf(
            day(1L, resting = DayBins.NO_BPM), day(2L, resting = 55),
            day(3L, resting = 57), day(4L, resting = 59),
        )
        assertEquals(57, DaySummaries.baselineRestingBpm(mixed))
    }

    @Test
    fun `a run of high-pulse days counts back from today`() {
        val threshold = 20
        val calm = arrayOf(day(1L, share = 4), day(2L, share = 6), day(3L, share = 2))
        assertEquals(0, DaySummaries.consecutiveHighHrDays(calm, todaySharePct = 5, threshold))
        assertEquals(1, DaySummaries.consecutiveHighHrDays(calm, todaySharePct = 44, threshold))

        val two = arrayOf(day(1L, share = 4), day(2L, share = 31))
        assertEquals(2, DaySummaries.consecutiveHighHrDays(two, todaySharePct = 27, threshold))

        val three = arrayOf(day(1L, share = 25), day(2L, share = 31))
        assertEquals(3, DaySummaries.consecutiveHighHrDays(three, todaySharePct = 27, threshold))
    }
}
