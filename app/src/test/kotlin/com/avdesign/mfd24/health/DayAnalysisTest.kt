// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four synthetic days and the advice they earn. Every threshold in [DayAnalysis] is a judgement
 * rather than a measurement, so what is pinned here is the *shape* of the judgement: which day
 * gets told what, and that nothing is said before there is enough of this wearer's own history
 * to say it against.
 */
class DayAnalysisTest {

    private val out = arrayOfNulls<Recommendation>(DayAnalysis.MAX_RECOMMENDATIONS)

    private fun day(
        steps: Int = 8_000,
        sleep: Int = 450,
        wake: Int = 1,
        share: Int = 5,
        onset: Int = 22 * 60 + 30,
        resting: Int = 56,
        epochDay: Long = 20_700L,
    ) = DaySummary(epochDay, steps, resting, sleep, wake, share, onset)

    private fun history(vararg shares: Int): Array<DaySummary> =
        Array(shares.size) { day(share = shares[it], epochDay = 20_690L + it) }

    @Test
    fun `a good day scores high and is told nothing`() {
        val good = day(steps = 11_000, sleep = 460, wake = 1, share = 4)
        assertTrue(DayAnalysis.score(good, baselineSteps = 9_000) >= 90)
        assertEquals(0, DayAnalysis.recommend(good, history(3, 4, 5), 9_000, out))
    }

    @Test
    fun `a sedentary day is asked for a walk, against the wearer's own norm`() {
        // Four thousand steps is under the floor for anybody.
        assertEquals(1, DayAnalysis.recommend(day(steps = 4_000), history(3, 4, 5), 9_000, out))
        assertEquals(Recommendation.MORE_STEPS, out[0])

        // Seven thousand is over the floor but well under this wearer's twelve — still worth
        // saying, because the standard is their own week and not a slogan.
        assertEquals(1, DayAnalysis.recommend(day(steps = 7_000), history(3, 4, 5), 12_000, out))
        assertEquals(Recommendation.MORE_STEPS, out[0])

        // And a wearer whose norm is small is not nagged for meeting the floor.
        assertEquals(0, DayAnalysis.recommend(day(steps = 6_500), history(3, 4, 5), 6_000, out))
    }

    @Test
    fun `a short night that began late is answered by bedtime, not by a nap`() {
        val late = day(sleep = 330, onset = 25 * 60 % (24 * 60))   // 01:00
        assertEquals(1, DayAnalysis.recommend(late, history(3, 4, 5), 6_000, out))
        assertEquals(Recommendation.SLEEP_EARLIER, out[0])

        val earlyButShort = day(sleep = 330, onset = 22 * 60)
        assertEquals(1, DayAnalysis.recommend(earlyButShort, history(3, 4, 5), 6_000, out))
        assertEquals(Recommendation.SLEEP_MORE, out[0])
    }

    @Test
    fun `a broken night counts as a poor one even when it was long enough`() {
        val fragmented = day(sleep = 470, wake = 4)
        assertEquals(1, DayAnalysis.recommend(fragmented, history(3, 4, 5), 6_000, out))
        assertEquals(Recommendation.SLEEP_MORE, out[0])
        // And it costs points: five for each wake past the two everybody has.
        assertTrue(
            DayAnalysis.score(fragmented, 6_000) < DayAnalysis.score(day(sleep = 470, wake = 1), 6_000)
        )
    }

    @Test
    fun `a caffeine day is mentioned, and a run of them is mentioned differently`() {
        // One busy day among calm ones: worth saying, gently.
        assertEquals(
            Recommendation.HIGH_HR,
            run { DayAnalysis.recommend(day(share = 31), history(4, 6, 3), 6_000, out); out[0] },
        )

        // The third in a row is the day the wording changes — that is what "persistent" means,
        // and it is the strongest thing this instrument will ever say.
        assertEquals(
            Recommendation.HIGH_HR_PERSISTENT,
            run { DayAnalysis.recommend(day(share = 27), history(4, 25, 30), 6_000, out); out[0] },
        )

        // Two in a row is not yet a pattern.
        assertEquals(
            Recommendation.HIGH_HR,
            run { DayAnalysis.recommend(day(share = 27), history(4, 6, 30), 6_000, out); out[0] },
        )
    }

    @Test
    fun `nothing is said about a pulse before there is a week to say it against`() {
        // Two days of history: the wearer's sitting norm is not known, so the busiest day says
        // nothing rather than judging one heart by another's.
        assertEquals(0, DayAnalysis.recommend(day(share = 44), history(30, 30), 6_000, out))
    }

    @Test
    fun `a bad day earns three things to change, and never more`() {
        val bad = day(steps = 2_500, sleep = 300, wake = 4, share = 35, onset = 1 * 60)
        val n = DayAnalysis.recommend(bad, history(25, 28, 31), 9_000, out)
        assertEquals(DayAnalysis.MAX_RECOMMENDATIONS, n)
        assertEquals(Recommendation.SLEEP_EARLIER, out[0])
        assertEquals(Recommendation.HIGH_HR_PERSISTENT, out[1])
        assertEquals(Recommendation.MORE_STEPS, out[2])
        assertTrue("a day like that should not score well", DayAnalysis.score(bad, 9_000) < 45)
    }

    @Test
    fun `the score stays inside its bounds and never falls for doing better`() {
        val worst = day(steps = 0, sleep = 0, wake = 9, share = 100)
        val best = day(steps = 30_000, sleep = 520, wake = 0, share = 0)
        assertTrue(DayAnalysis.score(worst, 9_000) >= 0)
        assertTrue(DayAnalysis.score(best, 9_000) <= 100)

        var previous = -1
        for (steps in intArrayOf(0, 2_000, 4_000, 6_000, 8_000, 12_000)) {
            val s = DayAnalysis.score(day(steps = steps), 9_000)
            assertTrue("more steps scored worse at $steps", s >= previous)
            previous = s
        }
        previous = -1
        for (sleep in intArrayOf(0, 120, 300, 420, 480)) {
            val s = DayAnalysis.score(day(sleep = sleep), 9_000)
            assertTrue("more sleep scored worse at $sleep", s >= previous)
            previous = s
        }
    }
}
