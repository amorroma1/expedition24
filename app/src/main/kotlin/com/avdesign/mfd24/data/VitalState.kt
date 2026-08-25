// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import com.avdesign.mfd24.health.DayBins

/**
 * The day as the renderer reads it: 96 bins of pulse, steps and flags, published behind a version
 * counter.
 *
 * The discipline is [TelemetryState]'s. Arrays cannot be published atomically, so the writer
 * copies under a lock and bumps a version; the render thread compares the version, copies once
 * when it moves, and works from its own arrays for every frame after — `render()` allocates
 * nothing and takes no lock.
 *
 * **The 96 bins are a rolling twenty-four hours**, not the calendar day: bins ahead of the
 * current one are yesterday's. That is what keeps last night's sleep on the dial all morning
 * instead of amputating it at midnight, and it is why the trail can be read as "the day behind
 * the hand" without anyone having to know where the date boundary fell.
 */
class VitalState {

    /** Bumped by every publication; the renderer re-copies only when it moves. */
    @Volatile
    var version: Int = 0
        private set

    /** Which bin the hand stands in — the old/new boundary of the rolling day. */
    @Volatile
    var currentBin: Int = 0
        private set

    /** Local midnight of the day the *current* bin belongs to; the trail's angles key off this. */
    @Volatile
    var dayStartMillis: Long = 0L
        private set

    /** Steps so far in the day under way, or [SensorSlots.NO_READING] before any tick. */
    @Volatile
    var stepsToday: Int = SensorSlots.NO_READING
        private set

    /** The day's resting pulse, or [DayBins.NO_BPM] before there are enough samples to say. */
    @Volatile
    var restingBpm: Int = DayBins.NO_BPM
        private set

    /** Last night's sleep in minutes, or −1 when no night has been inferred yet. */
    @Volatile
    var sleepMinutes: Int = -1
        private set

    /** The day's score, 0..100, or −1 before one can be computed. */
    @Volatile
    var dayScore: Int = -1
        private set

    private val hr = ByteArray(DayBins.BIN_COUNT)
    private val steps = ShortArray(DayBins.BIN_COUNT)
    private val flags = ByteArray(DayBins.BIN_COUNT)

    @Synchronized
    fun publishBins(
        dayStartMillis: Long,
        currentBin: Int,
        hr: ByteArray,
        steps: ShortArray,
        flags: ByteArray,
    ) {
        System.arraycopy(hr, 0, this.hr, 0, DayBins.BIN_COUNT)
        System.arraycopy(steps, 0, this.steps, 0, DayBins.BIN_COUNT)
        System.arraycopy(flags, 0, this.flags, 0, DayBins.BIN_COUNT)
        this.dayStartMillis = dayStartMillis
        this.currentBin = currentBin
        version++
    }

    /**
     * The figures the readout rows print. Scalars, so they need no version: a frame that catches
     * one of them a tick old prints a number that was true a quarter of an hour ago, which is
     * what every one of these numbers is anyway.
     */
    fun publishFigures(stepsToday: Int, restingBpm: Int, sleepMinutes: Int, dayScore: Int) {
        this.stepsToday = stepsToday
        this.restingBpm = restingBpm
        this.sleepMinutes = sleepMinutes
        this.dayScore = dayScore
    }

    /** Copies the bins into the caller's arrays; the renderer calls this only on a version change. */
    @Synchronized
    fun copyBins(outHr: ByteArray, outSteps: ShortArray, outFlags: ByteArray) {
        System.arraycopy(hr, 0, outHr, 0, DayBins.BIN_COUNT)
        System.arraycopy(steps, 0, outSteps, 0, DayBins.BIN_COUNT)
        System.arraycopy(flags, 0, outFlags, 0, DayBins.BIN_COUNT)
    }
}
