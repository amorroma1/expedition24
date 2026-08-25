// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import com.avdesign.mfd24.health.DayBins
import android.content.Context
import android.content.SharedPreferences
import com.avdesign.mfd24.health.DaySummaries

/**
 * Where the day log lives between ticks: device-protected preferences, like the telemetry cache
 * and the incident record.
 *
 * Device-protected because the face is direct-boot aware and draws the day's trail from its very
 * first frame after a reboot — a watch that restarts at four in the morning must come back
 * showing the night it just recorded, not an empty dial waiting for an unlock. The recorder's own
 * configuration lives here for the same reason the vigilance service keeps its own: a service
 * restarted by the platform is handed a null intent and has to remember what it was doing.
 */
class VitalStore(context: Context) {

    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** The day under way, packed by [com.avdesign.mfd24.health.DayLogCodec]. */
    fun today(): String? = prefs.getString(KEY_TODAY, null)

    fun saveToday(packed: String) {
        prefs.edit().putString(KEY_TODAY, packed).apply()
    }

    /** Yesterday, kept so a night that crossed midnight is one run and not two halves. */
    fun yesterday(): String? = prefs.getString(KEY_YESTERDAY, null)

    /** Closes a day: today becomes yesterday and the new day starts empty. */
    fun rollOver(closingPacked: String, openingPacked: String) {
        prefs.edit()
            .putString(KEY_YESTERDAY, closingPacked)
            .putString(KEY_TODAY, openingPacked)
            .apply()
    }

    fun summaries(): String? = prefs.getString(KEY_SUMMARIES, null)

    fun saveSummaries(packed: String) {
        prefs.edit().putString(KEY_SUMMARIES, packed).apply()
    }

    fun summaryDays(): Array<com.avdesign.mfd24.health.DaySummary> =
        DaySummaries.parse(summaries())

    /**
     * The step counter's last reading. Negative when there is none — which is the first tick
     * after an install and, deliberately, the same answer as after a reboot, since a counter
     * that restarted cannot be differenced against what it read before.
     */
    fun counterLast(): Long = prefs.getLong(KEY_COUNTER, -1L)

    fun saveCounterLast(value: Long) {
        prefs.edit().putLong(KEY_COUNTER, value).apply()
    }

    /** Recorder configuration, persisted for the restart the platform hands a null intent. */
    fun saveConfig(recording: Boolean, intervalMillis: Long) {
        prefs.edit()
            .putBoolean(KEY_RECORDING, recording)
            .putLong(KEY_INTERVAL, intervalMillis)
            .apply()
    }

    fun recording(fallback: Boolean): Boolean = prefs.getBoolean(KEY_RECORDING, fallback)

    /**
     * Two things the *style* decides that screens outside the face need.
     *
     * The report, the graphs and the raw export are plain activities with no editor session and
     * no watch-face style of their own, and a graph drawn midnight-up beside a dial drawn
     * noon-up is a graph the reader has to mentally flip. So the renderer writes the pair here
     * whenever the style changes, and the screens read it. Cheap: a style change is a rare
     * event, and nothing in the drawing path touches this.
     */
    fun saveDialStyle(midnightUp: Boolean, midnightAs24: Boolean, sleepOffBody: Boolean) {
        prefs.edit()
            .putBoolean(KEY_MIDNIGHT_UP, midnightUp)
            .putBoolean(KEY_MIDNIGHT_24, midnightAs24)
            .putBoolean(KEY_SLEEP_OFFBODY, sleepOffBody)
            .apply()
    }

    fun midnightUp(): Boolean = prefs.getBoolean(KEY_MIDNIGHT_UP, false)

    fun midnightAs24(): Boolean = prefs.getBoolean(KEY_MIDNIGHT_24, false)

    /**
     * A declared sleep session: "I am going to bed now", and later "I am up".
     *
     * The model can read a night off a wrist, but it cannot read intent, and the two cases it
     * cannot settle from data are exactly the ones a wearer can settle with one tap. Still and
     * quiet on a sofa at nine in the evening is a film; still and quiet in bed at nine is an
     * early night, and the flags are identical. A watch on the bedside table says nothing at
     * all unless somebody says it is a night.
     *
     * Kept as two instants rather than a boolean so a session survives a reboot and can be read
     * back into bins afterwards. Zero for start means no session; zero for end means one that is
     * still running.
     */
    fun sleepSessionStart(): Long = prefs.getLong(KEY_SESSION_START, 0L)

    fun sleepSessionEnd(): Long = prefs.getLong(KEY_SESSION_END, 0L)

    /** Whether a session is running *now*, with the runaway cap applied. */
    fun sleepSessionRunning(nowMillis: Long): Boolean {
        val start = sleepSessionStart()
        if (start <= 0L) return false
        if (sleepSessionEnd() > 0L) return false
        return nowMillis - start < MAX_SESSION_MILLIS
    }

    fun startSleepSession(nowMillis: Long) {
        prefs.edit().putLong(KEY_SESSION_START, nowMillis).putLong(KEY_SESSION_END, 0L).apply()
    }

    fun endSleepSession(nowMillis: Long) {
        if (sleepSessionStart() <= 0L) return
        prefs.edit().putLong(KEY_SESSION_END, nowMillis).apply()
    }

    /**
     * The session's end for reading purposes: what was recorded, or the cap past a start nobody
     * closed. A wearer who forgets to end one loses the tail of a morning, not the whole day.
     */
    fun sleepSessionEndOrCap(): Long {
        val start = sleepSessionStart()
        if (start <= 0L) return 0L
        val end = sleepSessionEnd()
        return if (end > 0L) end else start + MAX_SESSION_MILLIS
    }

    /**
     * The session as bin indices into the yesterday+today pair, for [SleepModel]: the start in
     * [out]`[0]`, the exclusive end in `[1]`, −1 for either where there is nothing to say.
     *
     * One place, because three screens and the face all ask it and a session read differently by
     * any of them would show a different night in the report than on the dial.
     */
    fun sleepSessionBins(todayEpochDay: Long, utcOffsetMillis: Int, out: IntArray) {
        out[0] = -1
        out[1] = -1
        val start = sleepSessionStart()
        if (start <= 0L) return
        out[0] = DayBins.pairBin(start, todayEpochDay, utcOffsetMillis)
        if (out[0] < 0) return
        val end = sleepSessionEndOrCap()
        out[1] = if (end > 0L) DayBins.pairBin(end, todayEpochDay, utcOffsetMillis) else -1
    }

    /** Whether sleep may be inferred from a watch that was off the wrist. Off unless asked for. */
    fun sleepOffBody(): Boolean = prefs.getBoolean(KEY_SLEEP_OFFBODY, false)

    fun intervalMillis(fallback: Long): Long = prefs.getLong(KEY_INTERVAL, fallback)

    private companion object {
        const val NAME = "mfd24_vital"

        const val KEY_TODAY = "today"
        const val KEY_YESTERDAY = "yesterday"
        const val KEY_SUMMARIES = "summaries"
        const val KEY_COUNTER = "counter_last"
        const val KEY_RECORDING = "recording"
        const val KEY_INTERVAL = "interval"
        const val KEY_MIDNIGHT_UP = "midnight_up"
        const val KEY_MIDNIGHT_24 = "midnight_24"
        const val KEY_SLEEP_OFFBODY = "sleep_offbody"
        const val KEY_SESSION_START = "sleep_session_start"
        const val KEY_SESSION_END = "sleep_session_end"

        /** Sixteen hours: past this a session was forgotten, not slept. */
        const val MAX_SESSION_MILLIS: Long = 16 * 3_600_000L
    }
}
