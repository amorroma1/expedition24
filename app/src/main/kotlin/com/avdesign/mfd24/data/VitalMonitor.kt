// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.avdesign.mfd24.health.DayAnalysis
import com.avdesign.mfd24.health.DayBins
import com.avdesign.mfd24.health.DayLogCodec
import com.avdesign.mfd24.health.DaySummaries
import com.avdesign.mfd24.health.DaySummary
import com.avdesign.mfd24.health.SleepModel
import com.avdesign.mfd24.health.SleepResult
import java.util.TimeZone

/**
 * The handle on the day recorder: process-wide state the renderer reads, the store behind it, and
 * the two commands that drive the service.
 *
 * Commands go by intent rather than through a reference to the running service — a static pointer
 * to a `Service` is a leak waiting to be forgotten about, and this one's deadlines are measured in
 * quarter-hours. The state is restored here, in the constructor, rather than left to the service:
 * the trail is what the wearer looks at first after a reboot, and it must be on the dial from the
 * first frame whether or not a recorder has managed to start yet.
 */
class VitalMonitor private constructor(context: Context) {

    private val appContext = context.applicationContext

    val state = VitalState()

    val store = VitalStore(appContext)

    private val hr = ByteArray(DayBins.BIN_COUNT)
    private val steps = ShortArray(DayBins.BIN_COUNT)
    private val flags = ByteArray(DayBins.BIN_COUNT)

    // The rolling day handed to the renderer: today's bins with yesterday's ahead of the hand.
    private val rollingHr = ByteArray(DayBins.BIN_COUNT)
    private val rollingSteps = ShortArray(DayBins.BIN_COUNT)
    private val rollingFlags = ByteArray(DayBins.BIN_COUNT)

    private val yesterdayHr = ByteArray(DayBins.BIN_COUNT)
    private val yesterdaySteps = ShortArray(DayBins.BIN_COUNT)
    private val yesterdayFlags = ByteArray(DayBins.BIN_COUNT)

    // The pair — yesterday then today — the night is read from, and the sleep it produces.
    private val pairHr = ByteArray(DayBins.BIN_COUNT * 2)
    private val pairSteps = ShortArray(DayBins.BIN_COUNT * 2)
    private val pairFlags = ByteArray(DayBins.BIN_COUNT * 2)
    private val sleep = SleepResult()

    init {
        republish(System.currentTimeMillis())
    }

    /**
     * Rebuilds the rolling day from what is on file and publishes it.
     *
     * Called from the constructor, after every tick the recorder writes, and whenever the face
     * becomes visible — the last of those because a day whose bins were written while the screen
     * slept has a hand standing in a different bin by the time anybody looks.
     */
    @Synchronized
    fun republish(nowMillis: Long) {
        val offset = TimeZone.getDefault().getOffset(nowMillis)
        val today = DayBins.localEpochDay(nowMillis, offset)
        val currentBin = DayBins.binIndex(nowMillis, offset)

        val todayDay = DayLogCodec.unpack(store.today(), hr, steps, flags)
        // A stored day that is not today's is history, not the day under way: read it as
        // yesterday if it is yesterday, and otherwise let it go rather than draw it as now.
        val todayValid = todayDay == today
        if (!todayValid) {
            java.util.Arrays.fill(hr, 0)
            java.util.Arrays.fill(steps, 0)
            java.util.Arrays.fill(flags, 0)
        }

        val storedYesterday = DayLogCodec.unpack(
            store.yesterday(), yesterdayHr, yesterdaySteps, yesterdayFlags,
        )
        val yesterdayValid = storedYesterday == today - 1L
        if (!yesterdayValid) {
            java.util.Arrays.fill(yesterdayHr, 0)
            java.util.Arrays.fill(yesterdaySteps, 0)
            java.util.Arrays.fill(yesterdayFlags, 0)
        }

        // Bins up to and including the hand are today's; the rest of the circle is yesterday's,
        // which is what keeps last night on the dial through the whole morning.
        for (i in 0 until DayBins.BIN_COUNT) {
            val fromToday = i <= currentBin
            rollingHr[i] = if (fromToday) hr[i] else yesterdayHr[i]
            rollingSteps[i] = if (fromToday) steps[i] else yesterdaySteps[i]
            rollingFlags[i] = if (fromToday) flags[i] else yesterdayFlags[i]
        }

        state.publishBins(
            DayBins.dayStartMillis(nowMillis, offset), currentBin,
            rollingHr, rollingSteps, rollingFlags,
        )

        var stepsToday = 0
        var sampled = false
        for (i in 0..currentBin) {
            if (flags[i].toInt() and DayBins.FLAG_SAMPLED != 0) sampled = true
            stepsToday += steps[i].toInt()
        }

        // The night, read across the pair so one that crossed midnight is a single run, and the
        // resting rate the rings' depths are judged against.
        System.arraycopy(yesterdayHr, 0, pairHr, 0, DayBins.BIN_COUNT)
        System.arraycopy(yesterdaySteps, 0, pairSteps, 0, DayBins.BIN_COUNT)
        System.arraycopy(yesterdayFlags, 0, pairFlags, 0, DayBins.BIN_COUNT)
        System.arraycopy(hr, 0, pairHr, DayBins.BIN_COUNT, DayBins.BIN_COUNT)
        System.arraycopy(steps, 0, pairSteps, DayBins.BIN_COUNT, DayBins.BIN_COUNT)
        System.arraycopy(flags, 0, pairFlags, DayBins.BIN_COUNT, DayBins.BIN_COUNT)
        val pairCount = DayBins.BIN_COUNT * 2
        val resting = SleepModel.restingBpm(pairHr, pairFlags, pairCount)
        SleepModel.infer(
            pairHr, pairSteps, pairFlags, pairCount, resting, sleep,
            allowOffBody = store.sleepOffBody(),
        )

        // The score is only offered once the day has enough of itself to be scored: before the
        // first tick there is nothing, and a hundred out of a hundred for an empty day would be
        // the most dishonest thing this face could say.
        val score = if (sampled) {
            DayAnalysis.score(
                DaySummary(
                    epochDay = today,
                    steps = stepsToday,
                    restingBpm = resting,
                    sleepMinutes = sleep.nightMinutes,
                    wakeEpisodes = sleep.wakeEpisodes,
                    hrHighSharePct = 0,
                    sleepOnsetMinutes = sleep.onsetMinutes,
                ),
                DaySummaries.baselineSteps(store.summaryDays()),
            )
        } else {
            -1
        }

        // No tick has landed today: the rows say nothing rather than inventing a zero, the same
        // rule the step slot keeps on the day it is switched on.
        state.publishFigures(
            stepsToday = if (sampled) stepsToday else SensorSlots.NO_READING,
            restingBpm = resting,
            sleepMinutes = if (sleep.nightMinutes > 0) sleep.nightMinutes else -1,
            dayScore = score,
        )
    }

    /** Starts or re-configures the recorder; idempotent, and safe to call from a frame. */
    fun start(intervalMillis: Long) {
        store.saveConfig(recording = true, intervalMillis = intervalMillis)
        send(
            Intent(appContext, VitalRecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INTERVAL_MILLIS, intervalMillis)
        )
    }

    fun stop() {
        store.saveConfig(recording = false, intervalMillis = store.intervalMillis(0L))
        send(Intent(appContext, VitalRecorderService::class.java).setAction(ACTION_STOP))
    }

    private fun send(intent: Intent) {
        // Same guard as vigilance: from API 31 a background uid may be refused outright, and a
        // watch face must not die because a recorder could not be told to start.
        runCatching { appContext.startForegroundService(intent) }
            .onFailure { Log.w(TAG, "Could not reach the day recorder", it) }
    }

    companion object {
        private const val TAG = "VitalMonitor"

        const val ACTION_START = "com.avdesign.mfd24.action.VITAL_START"
        const val ACTION_STOP = "com.avdesign.mfd24.action.VITAL_STOP"
        const val EXTRA_INTERVAL_MILLIS = "interval"

        @Volatile
        private var instance: VitalMonitor? = null

        fun get(context: Context): VitalMonitor =
            instance ?: synchronized(this) {
                instance ?: VitalMonitor(context).also { instance = it }
            }
    }
}
