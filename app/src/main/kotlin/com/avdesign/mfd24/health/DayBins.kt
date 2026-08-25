// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

/**
 * The day, cut into 96 quarter-hours — the grid the recorder writes and the trail draws.
 *
 * Fifteen minutes rather than five, for three reasons that all point the same way. The pulse
 * arrives at most once per sampling interval (5, 10 or 15 minutes), so five-minute bins would be
 * mostly holes by construction — resolution the sampler cannot fill. The platform serves an
 * app roughly one while-idle alarm every nine minutes in deep Doze, and the vigilance monitor
 * already draws on that budget, so a quarter-hour is the finest cadence that can be *kept*
 * rather than hoped for: a stretched alarm lands in the same bin or the next, which is a late
 * write, never a lost one. And the totals do not depend on the cadence at all, because the step
 * counter is cumulative and every bin's steps are a difference of two readings.
 *
 * Pure arithmetic on epoch millis, so it unit-tests on the JVM. The local-day convention is the
 * one [com.avdesign.mfd24.data.SensorSlots] already counts steps by: a day is
 * `(now + utcOffset) / DAY_MILLIS`, which puts the boundary at local midnight where a person
 * would put it.
 */
object DayBins {

    const val BIN_MINUTES = 15
    const val BIN_COUNT = 96
    const val BIN_MILLIS: Long = BIN_MINUTES * 60_000L
    const val DAY_MILLIS: Long = 24 * 3_600_000L

    /** No pulse for this bin. Zero rather than −1 so the packed form stays one unsigned byte. */
    const val NO_BPM = 0

    /**
     * A tick reached this bin. The distinction the whole face rests on: an unflagged bin means
     * *nobody was looking*, which is not the same claim as "nothing happened here", and the
     * trail draws the two differently — a gap against a hairline.
     */
    const val FLAG_SAMPLED = 0x01

    /** The watch was on a wrist. Off-body bins carry no pulse and never count as sleep. */
    const val FLAG_ON_BODY = 0x02

    /** The watch was on a charger — off a wrist by definition, however the detector reads. */
    const val FLAG_CHARGING = 0x04

    /** Steps happened here, by [MOVING_MIN_STEPS]. */
    const val FLAG_MOVING = 0x08

    /** Written back at day close by [SleepModel]; today's sleep is inferred on demand. */
    const val FLAG_SLEEP = 0x10

    /**
     * The fidget floor. Ten steps in a quarter of an hour is a strap shifting, a kettle, a
     * reach across a desk — counting that as movement would erase the still hours the face
     * exists to show.
     */
    const val MOVING_MIN_STEPS = 10

    /** The local day containing [nowMillis]; the boundary is local midnight. */
    fun localEpochDay(nowMillis: Long, utcOffsetMillis: Int): Long =
        Math.floorDiv(nowMillis + utcOffsetMillis, DAY_MILLIS)

    /** Which of the 96 bins [nowMillis] falls in, `0..95`. */
    fun binIndex(nowMillis: Long, utcOffsetMillis: Int): Int =
        (Math.floorMod(nowMillis + utcOffsetMillis, DAY_MILLIS) / BIN_MILLIS).toInt()

    /** Local midnight that opens the day containing [nowMillis], as an absolute instant. */
    fun dayStartMillis(nowMillis: Long, utcOffsetMillis: Int): Long =
        localEpochDay(nowMillis, utcOffsetMillis) * DAY_MILLIS - utcOffsetMillis

    /**
     * Steps since the previous reading of a counter that counts from boot.
     *
     * Two cases decide this, and they resolve differently — which is the whole point, and was
     * settled by the first tick this recorder ever ran on a watch.
     *
     * With **no previous reading** the answer is zero: the counter holds every step since the
     * device booted, and the first tick has no way to know which quarter-hour any of them
     * belonged to. Dropping the whole figure into the bin the recorder happened to start in
     * would have painted twelve thousand steps onto one quarter of an hour — seen on the wrist,
     * a bead at full brightness for a walk that never happened there. The reading becomes the
     * baseline and contributes nothing, the same silence the step slot keeps on the day it is
     * switched on rather than publishing a zero it invented.
     *
     * With a **counter below its own last value** the hardware restarted while the day did not,
     * and there the counter *is* the answer: those steps were taken since the reboot, so they
     * are real and recent. What is lost is only what happened between the last reading and the
     * restart, and that is lost rather than guessed at.
     */
    fun stepDelta(counter: Long, lastCounter: Long): Long = when {
        lastCounter < 0L -> 0L
        counter < lastCounter -> if (counter < 0L) 0L else counter
        else -> counter - lastCounter
    }

    /** A bin that was watched, worn, off charge and still — what the trail draws as a hairline. */
    fun isStillAwake(flags: Int): Boolean =
        flags and FLAG_SAMPLED != 0 &&
            flags and FLAG_ON_BODY != 0 &&
            flags and FLAG_CHARGING == 0 &&
            flags and FLAG_MOVING == 0 &&
            flags and FLAG_SLEEP == 0
}
