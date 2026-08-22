// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.avdesign.mfd24.WatchShiftReceiver

/**
 * The watch (shift), as the render loop sees it: three volatile primitives, no locking, no
 * allocation.
 *
 * A shift outlives its own end, but only for an hour. Once served it keeps its start and end so the
 * arc can stay on the dial in grey and the readout can say what was worked — and then
 * [dutyState] hands back [DUTY_OFF] and the dial is clear for the next one. The instants stay in
 * storage regardless: they are absolute, so the answer is re-derived rather than remembered.
 */
class WatchShiftState {

    /** True while there is a shift worth drawing — scheduled, running or already served. */
    @Volatile
    var hasShift: Boolean = false
        internal set

    /** When the shift begins, in Unix millis. Equal to the moment of the tap for an immediate start. */
    @Volatile
    var startMillis: Long = 0L
        internal set

    /** When it is due to end, in Unix millis. */
    @Volatile
    var endMillis: Long = 0L
        internal set

    /**
     * Which of the four states the watch is in.
     *
     * A served watch is shown for [SERVED_VISIBLE_MILLIS] and then stops being shown at all — the
     * grey arc and the `DUTY` row go together, because they are the same claim. A shift that ended
     * an hour ago is not information any more: it says nothing about what to do next, and it
     * occupies the one row and the one arc that the *next* watch needs. Handing back
     * [DUTY_OFF] rather than clearing the stored shift is deliberate — the instants stay on file,
     * absolute as everything else here, so a reboot or a time-zone change re-derives the same
     * answer instead of depending on when a process happened to be running.
     */
    fun dutyState(nowMillis: Long): Int = when {
        !hasShift -> DUTY_OFF
        nowMillis < startMillis -> DUTY_PENDING
        nowMillis < endMillis -> DUTY_ACTIVE
        nowMillis < endMillis + SERVED_VISIBLE_MILLIS -> DUTY_SERVED
        else -> DUTY_OFF
    }

    /** Milliseconds left of the shift; zero once it is over. */
    fun remainingMillis(nowMillis: Long): Long {
        if (!hasShift) return 0L
        val remaining = endMillis - nowMillis
        return if (remaining > 0L) remaining else 0L
    }

    /** Milliseconds until a scheduled shift begins; zero once it has. */
    fun untilStartMillis(nowMillis: Long): Long {
        if (!hasShift) return 0L
        val until = startMillis - nowMillis
        return if (until > 0L) until else 0L
    }

    /**
     * Whether a booked shift is close enough for its arc to say something true.
     *
     * The dial is one revolution of the day, so an arc carries no date. A shift booked for the day
     * after tomorrow lands within a degree or two of the same shift booked for tonight, and reads
     * as imminent — the arc is not merely uninformative that far out, it is wrong. Until it can
     * mean something the readout carries it alone: `DUTY IN 48:00` says exactly what an arc cannot.
     *
     * The rule the lead-in has to satisfy is stronger than "not more than a day away", and it is
     * what [MfdRenderer][com.avdesign.mfd24.MfdRenderer] relies on: **the hour hand is never inside
     * a booked arc.** Work it out on the dial and the hand sits inside the span exactly while the
     * start is between one revolution and one revolution *minus the shift's own length* away — at
     * the far end of that window the hand stands precisely on the arc's end, which is where a
     * booked watch stops being distinguishable from one under way. So the arc waits until a turn
     * less the length, and one hour of dial further still for clearance: it appears with the hand
     * an hour *past* where the arc ends, and from there the hand only approaches the start from
     * outside.
     *
     * The consequence is that a long watch is announced later than a short one — nineteen hours
     * ahead for a four-hour watch, seven for a sixteen. That is the arithmetic being honest: a long
     * arc covers more of the dial, so there is less of the dial left to stand outside it.
     *
     * Everything is in turns rather than hours because the ambiguity is one of revolutions: on Mars
     * a revolution is a sol, and a watch a sol away is ambiguous in exactly the same way.
     *
     * @param dialPeriodMillis one revolution of whatever body the dial is showing
     */
    fun pendingArcVisible(nowMillis: Long, dialPeriodMillis: Double): Boolean {
        val until = startMillis - nowMillis
        // Running or served: not this function's business, and the caller does not ask. Answering
        // "yes" keeps it from ever being the reason a live arc disappears.
        if (until <= 0L) return true
        val leadIn = dialPeriodMillis - (endMillis - startMillis) -
            dialPeriodMillis * ARC_CLEARANCE_TURNS
        if (leadIn <= 0.0) return false
        return until <= leadIn
    }

    companion object {
        /**
         * Clearance between the hour hand and the end of a booked arc at the moment it appears,
         * as a fraction of a turn — one hour of dial. See [pendingArcVisible].
         */
        const val ARC_CLEARANCE_TURNS: Double = 1.0 / 24.0

        /** No shift set, or one that was cancelled. */
        const val DUTY_OFF: Int = 0

        /** Scheduled, not begun. */
        const val DUTY_PENDING: Int = 1

        /** Under way. */
        const val DUTY_ACTIVE: Int = 2

        /** Run to its end, and recent enough to still be worth showing. */
        const val DUTY_SERVED: Int = 3

        /**
         * How long a served watch stays on the dial: one hour past its end.
         *
         * Long enough to come off a watch, look at the thing and see how it went — which is the
         * only question a finished shift answers. Past that the grey arc is furniture: it cannot
         * be acted on, and it sits where the next watch's arc goes. An hour is also comfortably
         * more than the gap between back-to-back shifts is likely to be noticed across, so nobody
         * loses sight of a watch they have only just finished.
         */
        const val SERVED_VISIBLE_MILLIS: Long = 3_600_000L
    }
}

/**
 * Starts, schedules, ends and persists a watch, and owns the alarms that mark its boundaries.
 *
 * Process-wide, because two components need the same instance: the editor activity sets the shift up
 * and the watch face service renders it. Both live in this process, so a singleton keeps them in
 * step without a broadcast.
 *
 * The boundary alerts are [AlarmManager] alarms rather than something the renderer notices, because
 * the renderer is not running when it matters — in ambient it draws once a minute, and with the
 * screen off it may not draw at all.
 *
 * State lives in device-protected storage so a shift survives a reboot even before the user unlocks.
 */
class WatchShiftController private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val state = WatchShiftState()

    // --- Duty duration ---------------------------------------------------------------------

    /**
     * How long the next duty runs, as a preset plus the custom value last dialled in.
     *
     * This lives here rather than in the `UserStyleSchema` where it started. A duration is timer
     * configuration, not style: it belongs beside the shift it governs, in device-protected storage,
     * and it has no business being per-watch-face-instance. Keeping the custom value separate from
     * the selected preset is what lets CST come back to the figure you left it at instead of making
     * you dial it again every time you pass through 8h.
     */
    var durationPreset: String
        get() = prefs.getString(KEY_PRESET, PRESET_8H) ?: PRESET_8H
        set(value) = prefs.edit().putString(KEY_PRESET, value).apply()

    var customDurationMillis: Long
        get() = prefs.getLong(KEY_CUSTOM, DEFAULT_CUSTOM_MILLIS).coerceIn(MIN_MILLIS, MAX_MILLIS)
        set(value) = prefs.edit()
            .putLong(KEY_CUSTOM, value.coerceIn(MIN_MILLIS, MAX_MILLIS))
            .apply()

    /** The duration the primary action would actually use. */
    val selectedDurationMillis: Long
        get() = presetMillis(durationPreset) ?: customDurationMillis

    /** Boundaries already chimed, so a repeated alarm delivery stays silent. */
    private var announcedStart = 0L
    private var announcedEnd = 0L

    init {
        restore()
    }

    /** Begins a shift now. */
    fun start(durationMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
        if (durationMillis <= 0L) return
        apply(nowMillis, nowMillis + durationMillis)
        Alerts.signal(appContext)
        Log.d(TAG, "watch started for ${durationMillis / 60_000} min")
    }

    /**
     * Books a shift for later. Nothing sounds now; the start alarm does that when the time comes.
     * A start already in the past is treated as "begin immediately" — a backstop only: the
     * editor, the one caller, clamps to [earliestBookableStart] before calling, so this path is
     * reachable only by a clock jump between its clamp and this check.
     */
    fun schedule(
        startAtMillis: Long,
        durationMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (durationMillis <= 0L) return
        if (startAtMillis <= nowMillis) {
            start(durationMillis, nowMillis)
            return
        }
        apply(startAtMillis, startAtMillis + durationMillis)
        Log.d(TAG, "watch booked for $startAtMillis, ${durationMillis / 60_000} min")
    }

    /** Cancels the shift outright, arc and all. */
    fun cancel() {
        if (!state.hasShift) return
        clear()
        Alerts.signal(appContext)
        Log.d(TAG, "watch cancelled by the user")
    }

    /** Called from the alarm receiver when a booked shift comes due. */
    @Synchronized
    fun onStarted() {
        if (!state.hasShift) return
        if (announcedStart == state.startMillis) return
        announcedStart = state.startMillis
        prefs.edit().putLong(KEY_ANNOUNCED_START, announcedStart).apply()
        Alerts.signal(appContext)
        Log.d(TAG, "watch begins")
    }

    /**
     * Called from the alarm receiver when the shift runs out. The shift is kept — the arc turns
     * grey and the readout reads `OFF-DUTY` — so there is still something to look at afterwards.
     *
     * Guarded against repeats: a wall-clock jump makes the platform deliver a due `RTC_WAKEUP`
     * alarm more than once, and the user should hear one chime, not several.
     */
    @Synchronized
    fun onExpired() {
        if (!state.hasShift) return
        if (announcedEnd == state.endMillis) return
        announcedEnd = state.endMillis
        prefs.edit().putLong(KEY_ANNOUNCED_END, announcedEnd).apply()
        cancelAlarms()
        Alerts.signal(appContext)
        Log.d(TAG, "watch complete")
    }

    private fun apply(startMillis: Long, endMillis: Long) {
        cancelAlarms()
        state.startMillis = startMillis
        state.endMillis = endMillis
        state.hasShift = true
        // An immediate start chimes here, so record it as announced; a booked one has not chimed.
        announcedStart = if (startMillis <= System.currentTimeMillis()) startMillis else 0L
        announcedEnd = 0L
        prefs.edit()
            .putLong(KEY_START, startMillis)
            .putLong(KEY_END, endMillis)
            .putLong(KEY_ANNOUNCED_START, announcedStart)
            .putLong(KEY_ANNOUNCED_END, announcedEnd)
            .apply()
        scheduleAlarms(startMillis, endMillis, System.currentTimeMillis())
    }

    private fun clear() {
        state.hasShift = false
        state.startMillis = 0L
        state.endMillis = 0L
        announcedStart = 0L
        announcedEnd = 0L
        // Only the shift's own keys. A blanket clear() also took the duty duration with it, so
        // cancelling once reset the length you had chosen.
        prefs.edit()
            .remove(KEY_START)
            .remove(KEY_END)
            .remove(KEY_ANNOUNCED_START)
            .remove(KEY_ANNOUNCED_END)
            .apply()
        cancelAlarms()
    }

    private fun restore() {
        val start = prefs.getLong(KEY_START, 0L)
        val end = prefs.getLong(KEY_END, 0L)
        if (end <= 0L) return
        state.startMillis = start
        state.endMillis = end
        state.hasShift = true
        announcedStart = prefs.getLong(KEY_ANNOUNCED_START, 0L)
        announcedEnd = prefs.getLong(KEY_ANNOUNCED_END, 0L)

        val now = System.currentTimeMillis()
        if (end > now) {
            // Still to run, so put the boundary alarms back; the alert itself is not replayed.
            scheduleAlarms(start, end, now)
        }
    }

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            requestCode,
            Intent(appContext, WatchShiftReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun scheduleAlarms(startMillis: Long, endMillis: Long, nowMillis: Long) {
        if (startMillis > nowMillis) {
            setAlarm(startMillis, pendingIntent(WatchShiftReceiver.ACTION_STARTED, REQUEST_START))
        }
        if (endMillis > nowMillis) {
            setAlarm(endMillis, pendingIntent(WatchShiftReceiver.ACTION_EXPIRED, REQUEST_END))
        }
    }

    private fun setAlarm(triggerAtMillis: Long, pending: PendingIntent) {
        val manager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                // Without the exact-alarm permission a boundary alert can slip by a few minutes.
                // For a watch chime that is a degradation, not a failure, so take it rather than
                // pestering the user for a restricted permission.
                Log.i(TAG, "exact alarms unavailable; watch alerts may be late")
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        }.onFailure { Log.w(TAG, "Could not schedule a watch alarm", it) }
    }

    private fun cancelAlarms() {
        val manager = appContext.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            manager.cancel(pendingIntent(WatchShiftReceiver.ACTION_STARTED, REQUEST_START))
            manager.cancel(pendingIntent(WatchShiftReceiver.ACTION_EXPIRED, REQUEST_END))
        }
    }

    companion object {
        private const val TAG = "WatchShift"
        private const val PREFS_NAME = "mfd24_watch_shift"
        private const val KEY_START = "start"
        private const val KEY_END = "end"
        private const val KEY_ANNOUNCED_START = "announced_start"
        private const val KEY_ANNOUNCED_END = "announced_end"
        private const val REQUEST_START = 24
        private const val REQUEST_END = 25
        private const val KEY_PRESET = "duration_preset"
        private const val KEY_CUSTOM = "duration_custom"

        const val PRESET_4H: String = "4h"
        const val PRESET_8H: String = "8h"
        const val PRESET_12H: String = "12h"

        /** Not a fixed length: whatever [customDurationMillis] holds. */
        const val PRESET_CUSTOM: String = "cst"

        /** Fixed length behind a preset, or null for the custom one. */
        fun presetMillis(preset: String): Long? = when (preset) {
            PRESET_4H -> 4 * 3_600_000L
            PRESET_8H -> 8 * 3_600_000L
            PRESET_12H -> 12 * 3_600_000L
            else -> null
        }

        /** Steps of five minutes: a rota is never set to the odd minute. */
        const val STEP_MILLIS: Long = 5 * 60_000L

        /**
         * The nearest instant a shift can be booked for: now, rounded up to the next
         * [STEP_MILLIS] boundary. The editor's schedule steppers and its ARM TIMER clamp to
         * this — a booked start left in the past would fall through [schedule] into an
         * immediate start with an audible chime, which is never what stepping a time meant.
         */
        fun earliestBookableStart(nowMillis: Long): Long =
            (nowMillis + STEP_MILLIS - 1) / STEP_MILLIS * STEP_MILLIS

        const val MIN_MILLIS: Long = STEP_MILLIS

        /** A watch is a shift, not a day: sixteen hours is the outer limit of a defensible one. */
        const val MAX_MILLIS: Long = 16 * 3_600_000L

        const val DEFAULT_CUSTOM_MILLIS: Long = 6 * 3_600_000L

        @Volatile
        private var instance: WatchShiftController? = null

        fun get(context: Context): WatchShiftController =
            instance ?: synchronized(this) {
                instance ?: WatchShiftController(context).also { instance = it }
            }
    }
}
