// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The handle on vigilance monitoring: process-wide state the renderer reads, and the three commands
 * that drive the service behind it.
 *
 * Commands go by intent rather than through a reference to the running service. A static pointer to
 * a `Service` is a leak waiting to be forgotten about, and the latency of an intent is irrelevant
 * against a thirty-second window.
 */
class VigilanceMonitor private constructor(context: Context) {

    private val appContext = context.applicationContext

    val state = VigilanceState()

    /**
     * The same records the service writes. Held here so a stale incident can be cleared with the
     * service down, which is the state a watch that expired under an unanswered escalation is in.
     */
    private val store = VigilanceStore(appContext)

    @Volatile
    private var running = false

    init {
        // Read here rather than left to the service, because the marks on the duty arc outlive the
        // monitoring that produced them: switch vigilance off part-way through a watch and the
        // service never starts, but what already happened during that watch still happened.
        state.publishIncidents(store.log())
        state.publishLogShift(store.logShiftStart(), store.logShiftEnd())
        state.incidentMillis = store.incidentAt()
        // And it is showing from the first frame, without waiting for a service to come up and say
        // so. An incident is cleared deliberately or by the next watch beginning; a reboot is
        // neither, and a face that came back up looking normal would have lost the one thing the
        // record exists to survive.
        if (state.incidentMillis != 0L) state.status = VigilanceState.INCIDENT
    }

    /**
     * Retires an incident belonging to a watch that has already ended.
     *
     * Here as well as in the service, because the service is not always there to do it: switch
     * vigilance off and a new watch would begin with `MAN DOWN` still on the dial and a full hub
     * core, with nothing running that could ever take it down. Starting a watch is a deliberate act
     * by somebody demonstrably conscious, which is what makes it enough to clear the record in
     * force. The log keeps the entry either way.
     */
    fun noteShiftStart(shiftStartMillis: Long, shiftEndMillis: Long = 0L) {
        // A new watch takes the log with it. The log is the record of *this* shift — what the
        // question "how did it go" is asked of at the end of one — and the shift it belongs to is
        // written beside it, so starting another is what closes the old record. Anything wanted
        // from it afterwards has to leave the watch first, which EXPORT LOG is for. This is an
        // instrument for people who stand watches rather than a certified recorder; if the job
        // ever calls for a kept journal, that is a different feature with different promises.
        if (shiftStartMillis > 0L && shiftStartMillis != store.logShiftStart()) {
            store.clearLog()
            store.noteLogShift(shiftStartMillis, shiftEndMillis)
            state.publishIncidents(IncidentLog.EMPTY)
            state.publishLogShift(shiftStartMillis, shiftEndMillis)
            Log.d(TAG, "new watch; incident log cleared")
        }
        if (!IncidentLog.belongsToEarlierWatch(state.incidentMillis, shiftStartMillis)) return
        state.incidentMillis = 0L
        if (state.status == VigilanceState.INCIDENT) state.status = VigilanceState.OFF
        store.clearIncidentAt()
    }

    /** Starts monitoring, or updates the settings of a run already under way. */
    fun start(
        intervalMillis: Long,
        vibeAmplitude: Int,
        toneVolume: Int,
        logHeartRate: Boolean,
        shiftStartMillis: Long,
    ) {
        running = true
        send(Intent(appContext, VigilanceService::class.java).apply {
            action = ACTION_START
            putExtra(VigilanceService.EXTRA_INTERVAL_MILLIS, intervalMillis)
            putExtra(VigilanceService.EXTRA_VIBE_AMPLITUDE, vibeAmplitude)
            putExtra(VigilanceService.EXTRA_TONE_VOLUME, toneVolume)
            putExtra(VigilanceService.EXTRA_LOG_HEART_RATE, logHeartRate)
            putExtra(VigilanceService.EXTRA_SHIFT_START_MILLIS, shiftStartMillis)
        })
    }

    /**
     * Stops monitoring — the watch is over, or the setting went off.
     *
     * An incident on file survives it. A shift that ran out while nobody was answering is precisely
     * the case the record exists for, and blanking the dial the moment the countdown expired would
     * throw it away at the hour it matters most. The status is left at
     * [VigilanceState.INCIDENT] for the face to keep showing.
     */
    fun stop() {
        if (!running) return
        running = false
        if (state.incidentMillis == 0L) state.status = VigilanceState.OFF
        state.deadlineMillis = 0L
        state.periodMillis = 0L
        runCatching { appContext.stopService(Intent(appContext, VigilanceService::class.java)) }
    }

    /** A sign of life — a tap on the watch face, or anything else that proves someone is there. */
    fun acknowledge() {
        if (!running) return
        if (state.status == VigilanceState.OFF) return
        send(Intent(appContext, VigilanceService::class.java).apply { action = ACTION_ACKNOWLEDGE })
    }

    /**
     * Clears a recorded incident and goes back to watching.
     *
     * Separate from [acknowledge] on purpose: the incident is the one piece of state a stray touch
     * must not be able to destroy, so the caller has to mean it.
     */
    fun clearIncident() {
        if (state.incidentMillis == 0L) return
        if (!running) {
            // The watch it happened on is over. Clearing the record is a prefs write and a status;
            // starting a foreground service to arm a dead-man's switch for a shift that is not
            // running would be neither asked for nor harmless.
            state.incidentMillis = 0L
            state.status = VigilanceState.OFF
            store.clearIncidentAt()
            return
        }
        send(Intent(appContext, VigilanceService::class.java).apply {
            action = ACTION_CLEAR_INCIDENT
        })
    }

    /**
     * Empties the incident log, from the editor.
     *
     * Separate from [clearIncident]: that ends the incident *in force* and is a two-tap gesture on
     * the face; this discards the history and is buried a section deep in the settings. Different
     * records, different lifetimes, different doors.
     */
    fun clearLog() {
        store.clearLog()
        state.publishIncidents(IncidentLog.EMPTY)
        state.publishLogShift(0L, 0L)
    }

    private fun send(intent: Intent) {
        runCatching { appContext.startForegroundService(intent) }
            .onFailure { Log.w(TAG, "Could not reach the vigilance service", it) }
    }

    companion object {
        private const val TAG = "Vigilance"

        const val ACTION_START = "com.avdesign.mfd24.action.VIGILANCE_START"
        const val ACTION_ACKNOWLEDGE = "com.avdesign.mfd24.action.VIGILANCE_ACK"
        const val ACTION_CLEAR_INCIDENT = "com.avdesign.mfd24.action.VIGILANCE_CLEAR"

        @Volatile
        private var instance: VigilanceMonitor? = null

        fun get(context: Context): VigilanceMonitor =
            instance ?: synchronized(this) {
                instance ?: VigilanceMonitor(context).also { instance = it }
            }
    }
}
