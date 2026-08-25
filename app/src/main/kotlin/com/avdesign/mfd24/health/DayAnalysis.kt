// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

/** What the day suggests doing differently tomorrow. Codes, so the wording lives in the UI. */
enum class Recommendation {
    /** Walk more: under the floor, or well under what this wearer usually manages. */
    MORE_STEPS,

    /** The pulse ran high while sitting still: caffeine, stress, or something starting. */
    HIGH_HR,

    /** And it has done for days now — the point at which a person deserves a real opinion. */
    HIGH_HR_PERSISTENT,

    /** Not enough sleep, and the night began early enough that the answer is simply more. */
    SLEEP_MORE,

    /** Not enough sleep because it started late: the fix is bedtime, not a nap. */
    SLEEP_EARLIER,
}

/**
 * What one day comes to, and the two or three things worth changing tomorrow.
 *
 * The whole of this file is a set of thresholds and a weighting, and every one of them is a
 * judgement rather than a measurement — so each is named, held to one place, and argued for in
 * a comment. The point is not to score a person. It is to say the smallest true thing that
 * tomorrow could act on: *you walked less than you usually do*, *your heart was busy while you
 * sat*, *that was a short night and it started late*. An instrument that says more than it can
 * know is one a wearer stops believing, and this one is already reading a life through a wrist.
 *
 * Nothing here is a diagnosis and the wording never pretends otherwise; the strongest thing it
 * will ever say is that a pattern has held for days and a doctor might want to hear about it.
 */
object DayAnalysis {

    /**
     * The floor, deliberately under the ten thousand of popular arithmetic: that number was a
     * pedometer's advertising slogan, and a target nobody meets is a target that gets switched
     * off. Six thousand is a walkable day for most people and a nudge rather than a rebuke.
     */
    const val STEPS_FLOOR = 6_000

    /** Or four fifths of what this wearer usually walks: a fifth under your own norm is real. */
    const val STEPS_BASELINE_PCT = 80

    /**
     * How far above the sedentary baseline a still quarter-hour's pulse has to run before it
     * counts as busy. Fifteen beats is beyond posture and digestion; it is caffeine, stress,
     * a fever, or a night of poor sleep being paid for.
     */
    const val SEDENTARY_EXCESS_BPM = 15

    /**
     * And how much of the sitting day has to be like that before it is a pattern. One coffee
     * lifts an hour — about four bins, six per cent of a sedentary day — so a fifth of it is
     * something else.
     */
    const val HIGH_HR_SHARE_PCT = 20

    /** Three days running: past a bad Tuesday, and the point at which a doctor is worth a mention. */
    const val PERSISTENT_DAYS = 3

    /** Seven hours: the low edge of adult guidance, not the ideal. */
    const val SLEEP_TARGET_MINUTES = 420

    /** A night broken this often is not a short night, it is a poor one. */
    const val FRAGMENTED_WAKE_EPISODES = 3

    /** Past this, a short night is a late night, and the answer is bedtime rather than a nap. */
    const val LATE_ONSET_MINUTES = 23 * 60

    /** At most three things to change: a list longer than that is a list nobody reads. */
    const val MAX_RECOMMENDATIONS = 3

    // The three pillars, weighted toward the two a wearer can act on tomorrow.
    private const val STEPS_POINTS = 40
    private const val SLEEP_POINTS = 40
    private const val PULSE_POINTS = 20

    /** Wake episodes past this cost points; one or two is a normal night for most people. */
    private const val FREE_WAKE_EPISODES = 2
    private const val WAKE_PENALTY = 5

    /** Sedentary-high share this small is free: everybody has a coffee. */
    private const val FREE_HIGH_HR_PCT = 10

    /**
     * The day out of a hundred.
     *
     * Steps and sleep carry forty each because they are what tomorrow can be different about;
     * the pulse carries twenty because it is mostly a consequence of the other two and of things
     * the watch cannot see. The target a day is measured against is the wearer's own, wherever
     * there is enough history to know it.
     */
    fun score(day: DaySummary, baselineSteps: Int): Int {
        val target = stepTarget(baselineSteps)
        val steps = STEPS_POINTS * minOf(day.steps, target) / target

        var sleep = SLEEP_POINTS * minOf(day.sleepMinutes, SLEEP_TARGET_MINUTES) /
            SLEEP_TARGET_MINUTES
        val excessWakes = day.wakeEpisodes - FREE_WAKE_EPISODES
        if (excessWakes > 0) sleep -= excessWakes * WAKE_PENALTY
        if (sleep < 0) sleep = 0

        var pulse = PULSE_POINTS - (day.hrHighSharePct - FREE_HIGH_HR_PCT).coerceIn(0, PULSE_POINTS)
        if (pulse < 0) pulse = 0

        return (steps + sleep + pulse).coerceIn(0, 100)
    }

    /**
     * Up to [MAX_RECOMMENDATIONS] codes, in the order tomorrow can act on them: sleep first
     * because it sets the shape of a day, then the pulse, then the steps. Returns how many were
     * written into [out].
     */
    fun recommend(
        day: DaySummary,
        history: Array<DaySummary>,
        baselineSteps: Int,
        out: Array<Recommendation?>,
    ): Int {
        var n = 0

        if (day.sleepMinutes in 0 until SLEEP_TARGET_MINUTES ||
            day.wakeEpisodes >= FRAGMENTED_WAKE_EPISODES
        ) {
            // A short night that began late is a bedtime problem; one that began in good time
            // and still came up short is answered by more sleep, tonight or in the afternoon.
            val late = day.sleepOnsetMinutes >= LATE_ONSET_MINUTES ||
                (day.sleepOnsetMinutes in 0 until 4 * 60)
            out[n++] = if (late) Recommendation.SLEEP_EARLIER else Recommendation.SLEEP_MORE
        }

        // The pulse rule needs a baseline of this wearer's own sitting days; below that it says
        // nothing rather than judging one person by another's heart.
        if (day.hrHighSharePct >= HIGH_HR_SHARE_PCT && history.size >= DaySummaries.MIN_BASELINE_DAYS) {
            val run = DaySummaries.consecutiveHighHrDays(history, day.hrHighSharePct, HIGH_HR_SHARE_PCT)
            out[n++] = if (run >= PERSISTENT_DAYS) {
                Recommendation.HIGH_HR_PERSISTENT
            } else {
                Recommendation.HIGH_HR
            }
        }

        if (n < MAX_RECOMMENDATIONS && day.steps < stepTarget(baselineSteps)) {
            out[n++] = Recommendation.MORE_STEPS
        }

        for (i in n until out.size) out[i] = null
        return n
    }

    /** What today is measured against: the floor, or four fifths of this wearer's own norm. */
    fun stepTarget(baselineSteps: Int): Int =
        if (baselineSteps <= DaySummaries.NO_BASELINE) {
            STEPS_FLOOR
        } else {
            maxOf(STEPS_FLOOR, baselineSteps * STEPS_BASELINE_PCT / 100)
        }
}
