// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

/** What one pass of [SleepModel] found. Caller-owned and reused; the model allocates nothing. */
class SleepResult {

    /** Runs of sleep, as bin indices into the pair that was analysed; end is exclusive. */
    @JvmField
    val startBin = IntArray(SleepModel.MAX_RUNS)

    @JvmField
    val endBin = IntArray(SleepModel.MAX_RUNS)

    @JvmField
    var runCount: Int = 0

    /** The main night, in minutes, and everything else the day held. */
    @JvmField
    var nightMinutes: Int = 0

    @JvmField
    var napMinutes: Int = 0

    /** Quiet spells inside the night that were bridged: how broken it was. */
    @JvmField
    var wakeEpisodes: Int = 0

    /** Bins bridged inside each run — time inside the night that was spent awake. */
    @JvmField
    val bridgedBins = IntArray(SleepModel.MAX_RUNS)

    /**
     * How many separate spells those bins formed, per run.
     *
     * Kept apart from [bridgedBins] because they answer different questions and were once the
     * same field: half an hour up in the night is thirty minutes to subtract but *one* waking,
     * and reporting two made a settled night read as a broken one.
     */
    @JvmField
    val wakeCounts = IntArray(SleepModel.MAX_RUNS)

    /** Minute of the day the night began, or −1 when no night was found. */
    @JvmField
    var onsetMinutes: Int = -1

    fun clear() {
        java.util.Arrays.fill(bridgedBins, 0)
        java.util.Arrays.fill(wakeCounts, 0)
        runCount = 0
        nightMinutes = 0
        napMinutes = 0
        wakeEpisodes = 0
        onsetMinutes = -1
    }
}

/**
 * Sleep, inferred from what a watch can actually see: a wrist that is worn, still, and running a
 * pulse near its own floor, for long enough that it is not a meeting.
 *
 * Three deliberate positions, each of which decides a case the other way round would get wrong.
 *
 * **The pulse only ever says "awake", never "not asleep enough".** A quiet quarter-hour with no
 * reading is still a candidate, because the platform thins the night's samples exactly when it
 * idles hardest — the hours this model exists to describe are the hours it is least likely to
 * have a pulse for. And a reading that is merely *higher than the floor* does not end a night
 * either: only one well clear of it does, because lying still with a working heart is being
 * awake and lying still with a slightly quick one is being asleep. Getting that backwards cost
 * a real night: see [AWAKE_MARGIN_PCT].
 *
 * **The floor is the wearer's own.** The threshold is a tenth above the day's resting rate,
 * which is the tenth percentile of the day's own samples: the athlete who rests at 45 and the
 * smoker who rests at 78 each get their own bar, and neither needs to be asked their age.
 *
 * **Absence of evidence is not stillness.** Bins nobody watched neither extend a run nor break
 * one beyond the bridging gap — the same rule the motion filter keeps, where a batch that never
 * arrived is missing evidence rather than evidence of absence.
 *
 * What it cannot do, and says so in the README: bins are quarter-hours, so the edges are ±15
 * minutes; naps shorter than half an hour are invisible; and a still evening with a book can
 * pass for sleep when no pulse contradicts it. This is a pattern in a day, not a sleep study.
 */
object SleepModel {

    /**
     * A turn in bed is not a walk: a bin this quiet still counts as asleep.
     *
     * Sixty, from a real night read off the watch — quarter-hours carrying 22, 41 and 43 steps
     * at two and six in the morning, each with a pulse in the sixties or low seventies. That is
     * an arm moving under a duvet and a wrist-worn counter believing it; a trip to the bathroom
     * is a hundred steps and more, and the pulse rises with it. At twenty, every one of those
     * quarter-hours ended the night.
     */
    const val QUIET_MAX_STEPS = 60

    /**
     * And how quiet a quarter-hour has to be to *begin* one.
     *
     * Sixty steps is what a sleeping body does under a duvet, but it is also what an evening on
     * a sofa does — and a still evening with a calm pulse would otherwise open a night at eight
     * o'clock and run it through to morning. Falling asleep is a stop; staying asleep is not, so
     * the run opens on the tighter threshold and continues on the looser one, which is also the
     * order the two pieces of evidence actually arrive in.
     */
    const val QUIET_START_STEPS = 20

    /**
     * How far above the resting rate a *still, worn* pulse has to run before the wearer is
     * taken to be awake.
     *
     * This began at ten per cent and was wrong on the first real night it met. The resting rate
     * is the tenth percentile of a whole day, so a day on its feet drags it upward — sixty-three
     * for a wearer whose true sleeping pulse was nearer the mid-fifties — and a ceiling ten per
     * cent above that lands at sixty-nine, under the seventy-odd of ordinary light sleep. Seven
     * and a half hours in bed came back as an hour and a half, in pieces.
     *
     * Forty-five per cent is where a still body is doing something rather than resting. It
     * keeps the case this rule exists for — sitting through a film at ninety-five against a
     * resting fifty-five is plainly awake — and stops the model arguing with a night it can
     * plainly see: the same wrist ran 87 and 89 for half an hour around one in the morning,
     * which is a dream and not a waking, and a ceiling a third above 63 called both of them up.
     */
    const val AWAKE_MARGIN_PCT = 45

    /**
     * By day the pulse has to be *near* the floor, not merely under the awake line.
     *
     * The loose night ceiling exists because the platform thins the night's samples and light
     * sleep runs high; neither is true of a Tuesday morning. Without this, a sedentary hour at a
     * desk — still, worn, sixty-eight against a resting fifty-two — came back as a nap, which is
     * the one thing a wellness face must not tell somebody about their working day. A real nap
     * drops to within a sixth of the floor, and by day there has to be a reading at all: no
     * pulse and no movement, in the middle of the afternoon, is a watch on a table.
     */
    const val NAP_MARGIN_PCT = 15

    /** Below this many readings, a tenth percentile is a dice roll and no pulse rule is applied. */
    const val MIN_HR_BINS = 8

    /** Two bins — half an hour. Shorter than this is a sofa, not a sleep. */
    const val MIN_RUN_BINS = 2

    /**
     * Restless quarter-hours inside a night are counted and bridged rather than ending it. Two,
     * not one: a trip to the bathroom and an alarm the platform served late are the same length,
     * and a night chopped in three by half an hour of either is not a reading of the night.
     */
    const val MAX_GAP_BINS = 2

    /**
     * How long a run made of nothing but off-the-wrist quarter-hours has to be before it counts.
     *
     * Two hours. Without a wrist there is no pulse and no step count worth the name, so the only
     * evidence left is that nothing happened — which is equally true of a watch on a bedside
     * table and a watch in a drawer. A long still spell inside the night hours is the weakest
     * claim this model makes, and the length is what keeps it from being a worthless one.
     */
    const val MIN_OFFBODY_RUN_BINS = 8

    /** The night is looked for between these minutes of the day, across the pair. */
    const val NIGHT_FROM_MINUTES = 20 * 60
    const val NIGHT_TO_MINUTES = 10 * 60

    /**
     * And the narrower window a night may *begin* in.
     *
     * The night window runs to ten in the morning so that a night which keeps going gets to keep
     * its loose pulse rule — but a run that *starts* at half past eight, after the wearer has
     * plainly been up and about, is a claim about a nap and has to meet the daytime bar. Without
     * the distinction a sedentary desk morning came back as an hour and a half of sleep.
     */
    const val SLEEP_START_TO_MINUTES = 6 * 60

    const val MAX_RUNS = 16

    /**
     * The day's resting pulse: the tenth percentile of every reading taken on the wrist. A
     * percentile rather than a minimum, because one bad sample would otherwise set the floor for
     * the whole day. Returns [DayBins.NO_BPM] when there is not enough to say.
     */
    fun restingBpm(hr: ByteArray, flags: ByteArray, count: Int): Int {
        var n = 0
        val values = IntArray(count)
        for (i in 0 until count) {
            val f = flags[i].toInt() and 0xFF
            if (f and DayBins.FLAG_SAMPLED == 0 || f and DayBins.FLAG_ON_BODY == 0) continue
            val bpm = hr[i].toInt() and 0xFF
            if (bpm <= DayBins.NO_BPM) continue
            values[n++] = bpm
        }
        if (n < MIN_HR_BINS) return DayBins.NO_BPM
        val kept = values.copyOf(n)
        kept.sort()
        return kept[n / 10]
    }

    /**
     * Finds the sleep in a pair of days — yesterday's 96 bins followed by today's — so a night
     * that crossed midnight is one run rather than two halves. [out] is filled in place.
     *
     * @param count how many of the pair's bins are real; the caller passes 192 for a full pair
     * @param restingBpm the pair's resting rate, or [DayBins.NO_BPM] to skip the pulse rule
     */
    @JvmOverloads
    fun infer(
        hr: ByteArray,
        steps: ShortArray,
        flags: ByteArray,
        count: Int,
        restingBpm: Int,
        out: SleepResult,
        allowOffBody: Boolean = false,
        sessionFrom: Int = -1,
        sessionTo: Int = -1,
    ) {
        out.clear()
        val ceiling = if (restingBpm > DayBins.NO_BPM) {
            restingBpm + restingBpm * AWAKE_MARGIN_PCT / 100
        } else {
            Int.MAX_VALUE
        }

        var runStart = -1
        var gap = 0
        var awakeGap = 0
        var bridged = 0
        var wakes = 0
        var wornInRun = 0
        var i = 0
        while (i < count) {
            val f = flags[i].toInt() and 0xFF
            val watched = f and DayBins.FLAG_SAMPLED != 0
            val worn = f and DayBins.FLAG_ON_BODY != 0
            val charging = f and DayBins.FLAG_CHARGING != 0
            // Inside a declared session the wearer has answered the question the data cannot:
            // this is a night. The window rules stop applying — an early night at nine and a
            // shift worker's morning are both nights when somebody says so — and a watch left on
            // the bedside table counts, which is the case the switch exists for.
            val declared = inSession(i, sessionFrom, sessionTo)
            // A declared night opens on the same threshold it continues on: the wearer has said
            // they are in bed, and the model has no business arguing with a turn of the wrist.
            val quietLimit = if (runStart >= 0 || declared) QUIET_MAX_STEPS else QUIET_START_STEPS
            val quiet = steps[i].toInt() <= quietLimit
            val bpm = hr[i].toInt() and 0xFF
            // A run already under way keeps the night's own loose pulse rule right through the
            // morning window; a new one inside that window has to be a night starting, not a
            // Tuesday continuing.
            val night = declared || inNightWindow(i) && (runStart >= 0 || inStartWindow(i))
            val calm = if (night) {
                bpm <= DayBins.NO_BPM || bpm < ceiling
            } else {
                bpm > DayBins.NO_BPM && restingBpm > DayBins.NO_BPM &&
                    bpm < restingBpm + restingBpm * NAP_MARGIN_PCT / 100
            }
            // Off the wrist there is no pulse and no meaningful step count: the only evidence is
            // that the watch did not move, and that is only worth anything during the night
            // hours and only for a long stretch. Charging is allowed here on purpose — a watch on
            // the bedside charger is exactly the case this switch exists for — where a *worn*
            // watch on charge still says nothing about a sleeping wrist.
            val offBodyStill = (allowOffBody || declared) && !worn && steps[i].toInt() == 0 && night
            val asleep = watched && quiet && (worn && !charging && calm || offBodyStill)
            if (asleep && worn && !charging) wornInRun++

            if (asleep) {
                if (runStart < 0) {
                    runStart = i
                } else if (gap > 0) {
                    // The spell inside the run is bridged. Only the quarter-hours somebody was
                    // actually seen to be awake in come off the time asleep and count as a
                    // waking — a bin the recorder never reached is missing evidence, and
                    // charging it to the wearer as fifteen minutes out of bed turned four
                    // dozed-through samples into four wakings on a real night.
                    if (awakeGap > 0) {
                        bridged += awakeGap
                        wakes++
                    }
                    gap = 0
                    awakeGap = 0
                }
            } else if (runStart >= 0) {
                // A bin nobody watched is missing evidence, not evidence of being awake: it may
                // be bridged like any other gap, but it never ends a run on its own.
                gap++
                if (watched) awakeGap++
                if (gap > MAX_GAP_BINS) {
                    // The run ended at the last asleep bin: i - gap + 1 is one past it, since
                    // the gap counts every bin from the first awake one up to and including i.
                    closeRun(out, runStart, i - gap + 1, bridged, wakes, wornInRun)
                    runStart = -1
                    gap = 0
                    awakeGap = 0
                    bridged = 0
                    wakes = 0
                    wornInRun = 0
                }
            }
            i++
        }
        if (runStart >= 0) closeRun(out, runStart, count - gap, bridged, wakes, wornInRun)

        summarise(out, count, sessionFrom, sessionTo)
    }

    private fun closeRun(
        out: SleepResult,
        start: Int,
        end: Int,
        bridged: Int,
        wakes: Int,
        wornBins: Int,
    ) {
        if (end - start < MIN_RUN_BINS) return
        // A run nobody was wearing the watch for is stillness and nothing else: it has to be long
        // to be worth reporting, or an hour on a desk becomes an hour of sleep.
        if (wornBins == 0 && end - start < MIN_OFFBODY_RUN_BINS) return
        if (out.runCount >= MAX_RUNS) return
        out.startBin[out.runCount] = start
        out.endBin[out.runCount] = end
        out.bridgedBins[out.runCount] = bridged
        out.wakeCounts[out.runCount] = wakes
        out.runCount++
    }

    /**
     * Sorts the runs into the night and everything else. The night is the longest run overlapping
     * the night window — longest rather than first, because a shift worker's night is still a
     * night, and the window is loose on purpose.
     */
    private fun summarise(out: SleepResult, count: Int, sessionFrom: Int, sessionTo: Int) {
        var nightRun = -1
        var nightLength = 0
        for (r in 0 until out.runCount) {
            val length = out.endBin[r] - out.startBin[r]
            // A declared session outranks the clock: if the wearer said it was a night, the run
            // inside it is the night, whatever hour it began at.
            val declared = sessionFrom >= 0 &&
                out.startBin[r] < (if (sessionTo < 0) count else sessionTo) &&
                out.endBin[r] > sessionFrom
            val counts = declared || overlapsNight(out.startBin[r], out.endBin[r], count)
            if (counts && length > nightLength) {
                nightLength = length
                nightRun = r
            }
        }
        for (r in 0 until out.runCount) {
            // Time asleep, not time in bed: the quarter-hours bridged inside a run were spent
            // awake, and the advice measures a night against seven hours of *sleep*.
            val bins = out.endBin[r] - out.startBin[r] - out.bridgedBins[r]
            val minutes = bins * DayBins.BIN_MINUTES
            if (r == nightRun) out.nightMinutes = minutes else out.napMinutes += minutes
        }
        if (nightRun >= 0) {
            out.onsetMinutes =
                (out.startBin[nightRun] % DayBins.BIN_COUNT) * DayBins.BIN_MINUTES
            out.wakeEpisodes = out.wakeCounts[nightRun]
        }
    }

    /** Whether a bin falls inside a declared session; an open end runs to the end of the pair. */
    private fun inSession(bin: Int, from: Int, to: Int): Boolean {
        if (from < 0) return false
        return bin >= from && (to < 0 || bin < to)
    }

    /** Whether a bin is inside the narrower window a night may begin in. */
    private fun inStartWindow(bin: Int): Boolean {
        val minuteOfDay = (bin % DayBins.BIN_COUNT) * DayBins.BIN_MINUTES
        return minuteOfDay >= NIGHT_FROM_MINUTES || minuteOfDay < SLEEP_START_TO_MINUTES
    }

    /** Whether a bin of the pair falls in the night window at all. */
    private fun inNightWindow(bin: Int): Boolean {
        val minuteOfDay = (bin % DayBins.BIN_COUNT) * DayBins.BIN_MINUTES
        return minuteOfDay >= NIGHT_FROM_MINUTES || minuteOfDay < NIGHT_TO_MINUTES
    }

    private fun overlapsNight(startBin: Int, endBin: Int, count: Int): Boolean {
        for (bin in startBin until endBin) {
            val minuteOfDay = (bin % DayBins.BIN_COUNT) * DayBins.BIN_MINUTES
            if (minuteOfDay >= NIGHT_FROM_MINUTES || minuteOfDay < NIGHT_TO_MINUTES) return true
        }
        return false
    }
}
