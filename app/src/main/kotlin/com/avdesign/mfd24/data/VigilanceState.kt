// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

/**
 * What the vigilance monitor is doing, as the render loop sees it: one volatile int and a deadline.
 */
class VigilanceState {

    @Volatile
    var status: Int = OFF
        internal set

    /** When the current phase runs out, in Unix millis; zero when nothing is pending. */
    @Volatile
    var deadlineMillis: Long = 0L
        internal set

    /**
     * How long the current phase is in total, in millis; zero when nothing is pending.
     *
     * Here so the centre-hub indicator can draw *elapsed over total* without knowing which phase it
     * is in or what the user set the interval to. A deadline on its own cannot say how far through
     * something is.
     */
    @Volatile
    var periodMillis: Long = 0L
        internal set

    /**
     * When the unanswered escalation happened, in Unix millis; zero when there is no incident on
     * file.
     *
     * Survives a restart, because the one thing that must not be lost is *when*. Held here as well
     * as in storage so the render loop can read it without touching a file.
     */
    @Volatile
    var incidentMillis: Long = 0L
        internal set

    /**
     * Until when the dial should read `TAP AGAIN` instead of `MAN DOWN`, in Unix millis.
     *
     * Set by the face's tap listener on the first tap of the clearing pair. The hint deliberately
     * outlives the 900 ms pairing window: at the resting one-frame-a-second rate a hint that
     * lasted only the window could pass between two frames unseen, and a tap while it is still
     * showing simply opens a fresh window, so acting on it always works. Discoverability was the
     * gap — the gesture was documented only in the README, and MAN DOWN is not the moment to go
     * looking for a manual.
     */
    @Volatile
    var clearHintUntilMillis: Long = 0L
        internal set

    /**
     * Every incident on file, oldest first, with whatever the pulse sensor could say about each.
     *
     * Published by replacing the array, never by writing into it, so a reader sees a finished log
     * through one volatile read and needs no lock. This is the editor's and the export's view.
     */
    @Volatile
    var incidents: Array<IncidentRecord> = IncidentLog.EMPTY
        internal set

    /**
     * The same log as bare instants, for the renderer.
     *
     * A second field rather than a mapping at the call site, because `render()` allocates nothing
     * — iterators and boxed fields included — and the marks on the duty arc are walked once a
     * frame. Built where the log is written, which is at most once per incident.
     */
    @Volatile
    var incidentTimes: LongArray = LongArray(0)
        internal set

    /** Replaces both views at once, so the renderer's array can never lag the editor's. */
    internal fun publishIncidents(log: Array<IncidentRecord>) {
        incidents = log
        incidentTimes = IncidentLog.times(log)
    }

    /**
     * The watch the log on file was recorded during, as its booked start and end.
     *
     * Held beside the log because the entries are instants and an instant on its own is detached
     * from the shift it happened in: "20:22Z" says nothing about whether that was ten minutes into
     * a night watch or the last hour of a sixteen. The editor prints it above the list and the
     * exported packet carries it as its own line.
     */
    @Volatile
    var logShiftStartMillis: Long = 0L
        internal set

    @Volatile
    var logShiftEndMillis: Long = 0L
        internal set

    internal fun publishLogShift(startMillis: Long, endMillis: Long) {
        logShiftStartMillis = startMillis
        logShiftEndMillis = endMillis
    }

    fun remainingMillis(nowMillis: Long): Long {
        val remaining = deadlineMillis - nowMillis
        return if (remaining > 0L) remaining else 0L
    }

    companion object {
        /** Not monitoring: switched off, or no watch under way. */
        const val OFF: Int = 0

        /** Monitoring, counting down to the next check. Nothing is asked of the operator. */
        const val ARMED: Int = 1

        /** Nudged. The operator has thirty seconds to move or touch the screen. */
        const val PROMPT: Int = 2

        /** Nobody answered. Sounding SOS until acknowledged. */
        const val ALARM: Int = 3

        /** Suspended because the watch is on charge — it is off the wrist, so there is nobody to watch. */
        const val CHARGING: Int = 4

        /**
         * The SOS ran its course with no answer.
         *
         * Signalling has stopped; what is left is the record. The dial holds the moment it happened
         * for whoever picks the watch up, and the battery goes to keeping that legible rather than
         * to beeping at an empty room.
         */
        const val INCIDENT: Int = 5

        /**
         * Suspended because the watch is not being worn.
         *
         * A watch on a table cannot fail to respond, and every nudge it is sent is a false one. The
         * trade this makes is stated where it is acted on, in
         * [VigilanceService][VigilanceService.setOnBody]: it is the one path by which the monitor
         * can stop watching a wrist that is still there.
         */
        const val OFF_BODY: Int = 6
    }
}
