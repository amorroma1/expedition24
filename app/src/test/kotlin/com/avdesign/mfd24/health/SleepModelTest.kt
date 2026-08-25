// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic nights, because the alternative is wearing the watch and arguing with it in the
 * morning. The pair under test is yesterday's 96 bins followed by today's, which is how a night
 * that crosses midnight stays one run.
 */
class SleepModelTest {

    private val count = DayBins.BIN_COUNT * 2
    private val hr = ByteArray(count)
    private val steps = ShortArray(count)
    private val flags = ByteArray(count)
    private val out = SleepResult()

    private val watched = DayBins.FLAG_SAMPLED or DayBins.FLAG_ON_BODY

    private fun blank() {
        java.util.Arrays.fill(hr, 0)
        java.util.Arrays.fill(steps, 0)
        java.util.Arrays.fill(flags, 0)
    }

    /** Bin index in the pair: day 0 is yesterday, day 1 today. */
    private fun bin(day: Int, hour: Int, minute: Int = 0) =
        day * DayBins.BIN_COUNT + (hour * 60 + minute) / DayBins.BIN_MINUTES

    private fun fill(from: Int, to: Int, bpm: Int, st: Int, f: Int) {
        for (i in from until to) {
            hr[i] = bpm.toByte()
            steps[i] = st.toShort()
            flags[i] = f.toByte()
        }
    }

    /** An ordinary awake day either side of the night, so runs have something to end against. */
    private fun awake(from: Int, to: Int, bpm: Int = 72, st: Int = 300) =
        fill(from, to, bpm, st, watched or DayBins.FLAG_MOVING)

    @Test
    fun `a clean night across midnight is one run, not two halves`() {
        blank()
        awake(bin(0, 8), bin(0, 23))
        fill(bin(0, 23), bin(1, 7), 52, 0, watched)          // 23:00 → 07:00
        awake(bin(1, 7), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)

        assertEquals(1, out.runCount)
        assertEquals(8 * 60, out.nightMinutes)
        assertEquals(0, out.napMinutes)
        assertEquals(0, out.wakeEpisodes)
        assertEquals(23 * 60, out.onsetMinutes)
    }

    @Test
    fun `a broken night stays one night and counts what broke it`() {
        blank()
        awake(bin(0, 8), bin(0, 23))
        fill(bin(0, 23), bin(1, 7), 52, 0, watched)
        // Two trips out of bed, each a single quarter-hour: bridged, and counted.
        awake(bin(1, 2), bin(1, 2, 15), bpm = 88, st = 120)
        awake(bin(1, 4), bin(1, 4, 15), bpm = 90, st = 140)
        awake(bin(1, 7), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)

        assertEquals(1, out.runCount)
        assertEquals(2, out.wakeEpisodes)
        // The night still spans 23:00 to 07:00, less the two quarter-hours spent up.
        assertEquals(8 * 60 - 2 * DayBins.BIN_MINUTES, out.nightMinutes)
    }

    @Test
    fun `an afternoon nap is a nap, and does not become the night`() {
        blank()
        awake(bin(0, 8), bin(0, 23))
        fill(bin(0, 23), bin(1, 7), 52, 0, watched)
        awake(bin(1, 7), bin(1, 14))
        fill(bin(1, 14), bin(1, 15), 58, 0, watched)          // an hour after lunch
        awake(bin(1, 15), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)

        assertEquals(2, out.runCount)
        assertEquals(8 * 60, out.nightMinutes)
        assertEquals(60, out.napMinutes)
    }

    @Test
    fun `a watch left on a table sleeps for nobody`() {
        blank()
        awake(bin(0, 8), bin(0, 23))
        // Perfectly still, perfectly quiet — and off the wrist, so it says nothing about a person.
        fill(bin(0, 23), bin(1, 7), 0, 0, DayBins.FLAG_SAMPLED)
        awake(bin(1, 7), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)

        assertEquals(0, out.runCount)
        assertEquals(0, out.nightMinutes)
        assertEquals(-1, out.onsetMinutes)
    }

    @Test
    fun `a night the platform gave no pulse for is still a night`() {
        blank()
        awake(bin(0, 8), bin(0, 23))
        // Doze thinned every sample away: quiet and worn has to carry it, or the model would
        // lose exactly the hours it exists to describe.
        fill(bin(0, 23), bin(1, 7), DayBins.NO_BPM, 0, watched)
        awake(bin(1, 7), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)

        assertEquals(1, out.runCount)
        assertEquals(8 * 60, out.nightMinutes)
    }

    @Test
    fun `a still evening with a racing pulse is not sleep`() {
        blank()
        awake(bin(0, 8), bin(0, 20))
        // Sitting through a film with the heart at 95 against a resting 55: still, worn, and
        // plainly awake. The pulse is allowed to say so when it is there.
        fill(bin(0, 20), bin(0, 23), 95, 0, watched)
        fill(bin(0, 23), bin(1, 7), 52, 0, watched)
        awake(bin(1, 7), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)

        assertEquals(1, out.runCount)
        assertEquals(23 * 60, out.onsetMinutes)
        assertEquals(8 * 60, out.nightMinutes)
    }

    /**
     * The night this model got wrong on a wrist, kept as a test.
     *
     * A wearer whose day drags the resting rate to 63 and whose light sleep sits in the low
     * seventies: seven and a half hours in bed, and the first version of the rule reported an
     * hour and a half in pieces, because a pulse a tenth above the floor was treated as being
     * awake. The night has to come back whole.
     */
    @Test
    fun `a real night with a light-sleep pulse comes back whole`() {
        blank()
        awake(bin(0, 9), bin(0, 24), bpm = 84, st = 400)
        // Midnight to half past seven, the pulse drifting where a sleeping one does.
        fill(bin(1, 0), bin(1, 2), 66, 0, watched)
        fill(bin(1, 2), bin(1, 4), 61, 0, watched)
        fill(bin(1, 4), bin(1, 6), 72, 0, watched)
        fill(bin(1, 6), bin(1, 7, 30), 74, 0, watched)
        awake(bin(1, 7, 30), bin(1, 9), bpm = 88, st = 300)
        SleepModel.infer(hr, steps, flags, count, restingBpm = 63, out)

        assertEquals(1, out.runCount)
        assertEquals(7 * 60 + 30, out.nightMinutes)
    }

    /** And a quarter-hour up in the night is bridged rather than ending it. */
    @Test
    fun `a bathroom trip does not end the night`() {
        blank()
        awake(bin(0, 9), bin(0, 24), bpm = 84, st = 400)
        fill(bin(1, 0), bin(1, 7, 30), 68, 0, watched)
        awake(bin(1, 3), bin(1, 3, 30), bpm = 92, st = 90)
        awake(bin(1, 7, 30), bin(1, 9), bpm = 88, st = 300)
        SleepModel.infer(hr, steps, flags, count, restingBpm = 63, out)

        assertEquals(1, out.runCount)
        // One episode, not two: half an hour up is a single interruption, however many
        // quarter-hours it spans. The count is of wakings, and the minutes are counted apart.
        assertEquals(1, out.wakeEpisodes)
        assertEquals(7 * 60, out.nightMinutes)
    }

    /**
     * The night of 25 August 2026, off the wrist and through `EXPORT RAW`, bin for bin.
     *
     * The wearer was in bed from midnight to half past seven. The model read five and a half
     * hours in seven pieces, and the raw grid says why: three quarter-hours carrying 22, 41 and
     * 43 steps — an arm under a duvet — half an hour at 87 and 89 against a resting 63, and four
     * quarter-hours the recorder never reached, each of which was charged to the wearer as
     * fifteen minutes out of bed. All three rules moved; this is the night they have to keep.
     */
    @Test
    fun `the night of 25 August, as the watch actually recorded it`() {
        blank()
        awake(bin(0, 9), bin(0, 24), bpm = 84, st = 400)
        // 00:00 on charge, then in bed. Pairs are (bpm, steps) per quarter-hour from the export.
        val night = arrayOf(
            intArrayOf(106, 16), intArrayOf(74, 41), intArrayOf(78, 0), intArrayOf(81, 0),
            intArrayOf(87, 0), intArrayOf(89, 15), intArrayOf(81, 0), intArrayOf(-1, 0),
            intArrayOf(72, 43), intArrayOf(77, 0), intArrayOf(76, 0), intArrayOf(80, 0),
            intArrayOf(74, 0), intArrayOf(72, 0), intArrayOf(-1, 0), intArrayOf(73, 0),
            intArrayOf(70, 0), intArrayOf(74, 0), intArrayOf(72, 0), intArrayOf(66, 0),
            intArrayOf(68, 0), intArrayOf(-1, 0), intArrayOf(68, 0), intArrayOf(63, 22),
            intArrayOf(64, 0), intArrayOf(67, 0), intArrayOf(63, 0), intArrayOf(69, 0),
            intArrayOf(-1, 0), intArrayOf(63, 0), intArrayOf(62, 0),
        )
        // Midnight itself: on the charger, which is never sleep.
        flags[bin(1, 0)] = (DayBins.FLAG_SAMPLED or DayBins.FLAG_CHARGING).toByte()
        for ((n, pair) in night.withIndex()) {
            val i = bin(1, 0) + 1 + n
            if (pair[0] < 0) continue                        // a quarter-hour nobody watched
            hr[i] = pair[0].toByte()
            steps[i] = pair[1].toShort()
            flags[i] = (watched or if (pair[1] >= DayBins.MOVING_MIN_STEPS) {
                DayBins.FLAG_MOVING
            } else {
                0
            }).toByte()
        }
        // 08:00, up and walking.
        awake(bin(1, 8), bin(1, 10), bpm = 92, st = 403)
        SleepModel.infer(hr, steps, flags, count, restingBpm = 63, out)

        assertEquals(1, out.runCount)
        // Seven and a quarter hours against seven and a half in bed. The window sits three
        // quarter-hours later than the one the wearer would name — midnight on the charger, then
        // 106 bpm, then 41 steps, none of which is a body that has gone to sleep yet — and that
        // is the honest reading: the model reports the sleep it can see, not the moment the
        // light went out.
        assertEquals(7 * 60 + 15, out.nightMinutes)
        assertEquals(0, out.wakeEpisodes)
        assertEquals(45, out.onsetMinutes)
    }

    /**
     * The seeded evening that found this rule: a sofa at sixty steps a quarter-hour with a calm
     * pulse used to open a night at eight in the evening and run it to morning — twelve hours of
     * "sleep" in a report. A night now has to *start* on a genuinely still quarter-hour.
     */
    /**
     * The morning that found the daytime rule: sedentary at a desk, sixty-eight against a
     * resting fifty-two, twenty steps a quarter-hour. Under the night's own ceiling it was
     * "asleep" for an hour and a half, drawn on the sleep dial in the deep band, on a Tuesday.
     */
    @Test
    fun `a sedentary morning at a desk is not a nap`() {
        blank()
        fill(bin(0, 0), bin(0, 7), 52, 0, watched)
        awake(bin(0, 7), bin(0, 8, 30), bpm = 96, st = 900)
        // Nine to half past twelve at a desk: still, worn, and awake.
        fill(bin(0, 8, 30), bin(0, 12, 30), 68, 20, watched)
        awake(bin(0, 12, 30), bin(0, 23))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 52, out)

        assertEquals(0, out.napMinutes)
    }

    @Test
    fun `a quiet evening on a sofa does not open a night`() {
        blank()
        awake(bin(0, 8), bin(0, 19), bpm = 92, st = 800)
        // Television: still enough to look asleep on every test but the step count.
        fill(bin(0, 19), bin(0, 23), 66, 60, watched)
        fill(bin(0, 23), bin(1, 7), 54, 0, watched)
        awake(bin(1, 7), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 52, out)

        assertEquals(1, out.runCount)
        assertEquals(23 * 60, out.onsetMinutes)
        assertEquals(8 * 60, out.nightMinutes)
    }

    @Test
    fun `a half-hour is the shortest thing that counts as sleep`() {
        blank()
        awake(bin(0, 8), bin(0, 23))
        fill(bin(0, 23), bin(1, 7), 52, 0, watched)
        awake(bin(1, 7), bin(1, 13))
        fill(bin(1, 13), bin(1, 13, 15), 60, 0, watched)      // fifteen minutes on the sofa
        awake(bin(1, 13, 15), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)

        assertEquals("a quarter-hour is a sofa, not a sleep", 1, out.runCount)
        assertEquals(0, out.napMinutes)
    }

    /**
     * The switch the wearer who charges overnight asked for: a still watch off the wrist, in the
     * night hours, read as a night. Everything that makes the worn reading trustworthy is gone —
     * no pulse, no phases, no telling a bedside table from a drawer — which is why it is off by
     * default and says so in the editor.
     */
    @Test
    fun `off the wrist and still, a night is offered only when asked for`() {
        blank()
        awake(bin(0, 8), bin(0, 23))
        // Taken off at eleven, on the bedside charger, motionless until seven.
        fill(bin(0, 23), bin(1, 7), 0, 0, DayBins.FLAG_SAMPLED or DayBins.FLAG_CHARGING)
        awake(bin(1, 7), bin(1, 20))

        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)
        assertEquals("off by default, and silent", 0, out.runCount)

        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out, allowOffBody = true)
        assertEquals(1, out.runCount)
        assertEquals(8 * 60, out.nightMinutes)
    }

    @Test
    fun `a watch on a desk by day is not a nap, however still it lies`() {
        blank()
        awake(bin(0, 8), bin(0, 12))
        // Four hours face down on a desk, over lunch. Outside the night window, so it says
        // nothing at all — the one rule keeping a working day from reading as sleep.
        fill(bin(0, 12), bin(0, 16), 0, 0, DayBins.FLAG_SAMPLED)
        awake(bin(0, 16), bin(0, 23))
        fill(bin(0, 23), bin(1, 7), 52, 0, watched)
        awake(bin(1, 7), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out, allowOffBody = true)

        assertEquals(1, out.runCount)
        assertEquals(0, out.napMinutes)
        assertEquals(8 * 60, out.nightMinutes)
    }

    @Test
    fun `an hour on the nightstand is too little to call a night`() {
        blank()
        awake(bin(0, 8), bin(0, 23))
        // An hour off the wrist before bed, then the watch goes back on. Under the two-hour
        // floor, so the stillness alone earns nothing.
        fill(bin(0, 23), bin(1, 0), 0, 0, DayBins.FLAG_SAMPLED or DayBins.FLAG_CHARGING)
        awake(bin(1, 0), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out, allowOffBody = true)

        assertEquals(0, out.runCount)
    }

    /**
     * The declared night: one tap when the wearer goes to bed, another when they get up.
     *
     * It exists for the two cases the flags cannot settle. An evening on the sofa and an early
     * night look identical from a wrist — still, worn, a pulse in the sixties — and the model is
     * right to refuse the first, which means it must also refuse the second. And a watch on the
     * bedside table is stillness with no wearer attached. A tap answers both.
     */
    @Test
    fun `a declared night counts from nine in the evening`() {
        blank()
        awake(bin(0, 9), bin(0, 21), bpm = 88, st = 500)
        // Nine in the evening to five in the morning: outside the window a night may begin in,
        // and quiet enough to be a film if nobody says otherwise.
        fill(bin(0, 21), bin(1, 5), 62, 12, watched)
        awake(bin(1, 5), bin(1, 9), bpm = 84, st = 300)

        SleepModel.infer(hr, steps, flags, count, restingBpm = 58, out)
        assertEquals("undeclared, an evening at nine is an evening", 21 * 60, out.onsetMinutes)

        SleepModel.infer(
            hr, steps, flags, count, restingBpm = 58, out,
            sessionFrom = bin(0, 21), sessionTo = bin(1, 5),
        )
        assertEquals(1, out.runCount)
        assertEquals(21 * 60, out.onsetMinutes)
        assertEquals(8 * 60, out.nightMinutes)
    }

    @Test
    fun `a declared night reads a watch left on the bedside table`() {
        blank()
        awake(bin(0, 9), bin(0, 23), bpm = 84, st = 400)
        // Taken off at eleven and put down: sampled, not worn, not moving. Without a declaration
        // this needs the off-wrist switch; with one it counts on its own.
        fill(bin(0, 23), bin(1, 7), 0, 0, DayBins.FLAG_SAMPLED)
        awake(bin(1, 7), bin(1, 20))

        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)
        assertEquals("off the wrist and undeclared says nothing", 0, out.runCount)

        SleepModel.infer(
            hr, steps, flags, count, restingBpm = 55, out,
            sessionFrom = bin(0, 23), sessionTo = bin(1, 7),
        )
        assertEquals(1, out.runCount)
        assertEquals(8 * 60, out.nightMinutes)
    }

    @Test
    fun `a declared night does not swallow the day around it`() {
        blank()
        // The declaration covers 23:00 to 07:00; the sedentary morning after it is outside, and
        // has to be judged by the ordinary daytime rules — which refuse it.
        awake(bin(0, 9), bin(0, 23), bpm = 84, st = 400)
        fill(bin(0, 23), bin(1, 7), 54, 0, watched)
        fill(bin(1, 8, 30), bin(1, 12), 68, 20, watched)
        awake(bin(1, 12), bin(1, 20))
        SleepModel.infer(
            hr, steps, flags, count, restingBpm = 52, out,
            sessionFrom = bin(0, 23), sessionTo = bin(1, 7),
        )

        assertEquals(8 * 60, out.nightMinutes)
        assertEquals(0, out.napMinutes)
    }

    @Test
    fun `the resting rate is the day's own tenth percentile`() {
        blank()
        // Eight readings: 50 52 54 56 58 60 62 64 — the tenth percentile is the lowest of them.
        for (i in 0 until 8) {
            hr[i] = (50 + i * 2).toByte()
            flags[i] = watched.toByte()
        }
        assertEquals(50, SleepModel.restingBpm(hr, flags, count))

        // Seven is not enough to call anything a floor.
        blank()
        for (i in 0 until 7) {
            hr[i] = (50 + i).toByte()
            flags[i] = watched.toByte()
        }
        assertEquals(DayBins.NO_BPM, SleepModel.restingBpm(hr, flags, count))

        // Readings taken off the wrist are not the wearer's pulse and do not set their floor.
        blank()
        for (i in 0 until 12) {
            hr[i] = 40.toByte()
            flags[i] = DayBins.FLAG_SAMPLED.toByte()
        }
        assertEquals(DayBins.NO_BPM, SleepModel.restingBpm(hr, flags, count))
    }

    @Test
    fun `unwatched quarter-hours neither invent sleep nor break it`() {
        blank()
        awake(bin(0, 8), bin(0, 23))
        fill(bin(0, 23), bin(1, 7), 52, 0, watched)
        // One bin the recorder never reached, in the middle of the night.
        fill(bin(1, 3), bin(1, 3, 15), 0, 0, 0)
        awake(bin(1, 7), bin(1, 20))
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)
        assertEquals(1, out.runCount)
        assertTrue(out.nightMinutes >= 7 * 60)

        // And a day of nothing but absent bins claims no sleep at all.
        blank()
        SleepModel.infer(hr, steps, flags, count, restingBpm = 55, out)
        assertEquals(0, out.runCount)
    }
}
