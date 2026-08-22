// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * What vigilance has to remember when the process does not: the incident in force, and the log of
 * every incident the watch has ever recorded.
 *
 * Two records rather than one, because they have different lifetimes. The incident *in force* is
 * what the dial shows and what blocks the monitor from quietly going back to counting; it is
 * cleared deliberately, or by the start of a new watch. The *log* is the record — it is what puts
 * the incident marks on the duty arc, and what is still there tomorrow when the question is how
 * many times it happened rather than whether it did. It is bounded twice, by [MAX_ENTRIES] and by
 * [RETENTION_MILLIS] — see the retention constant for why a personal aid keeps a month, not an
 * archive — and CLEAR LOG in the editor empties it on the spot.
 *
 * The log covers **one watch**: starting a new one empties it, and the watch's own start and end
 * are stored beside it so the record says which shift it belongs to rather than floating free.
 * This is a watch for people who stand watches, not a certified recorder — the question it answers
 * is "how did this shift go", asked at the end of it. Anything wanted from an earlier shift has to
 * leave the watch before the next one begins, which `EXPORT LOG` is for.
 *
 * Device-protected, like the shift state beside it. An incident has to survive a reboot and be
 * readable before the watch is unlocked, which is exactly the moment somebody else is holding it.
 *
 * The log is a comma-separated run of epoch millis, not a serialised object graph: a record that
 * cannot be read out of a prefs dump is a record nobody will read, and there is no second party to
 * reconcile it with, so an identifier per entry would carry no information. The instants are
 * absolute like every other instant in this project, which is what lets a mark on the arc follow a
 * time-zone change with the shift instead of sliding off it.
 */
class VigilanceStore(context: Context) {

    /**
     * Device-protected, and resolved once. `createDeviceProtectedStorageContext` is not free and
     * this is read on the service's start path.
     */
    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** When the operator stopped answering, or zero when there is no incident in force. */
    fun incidentAt(): Long = runCatching { prefs.getLong(KEY_INCIDENT_AT, 0L) }.getOrDefault(0L)

    fun setIncidentAt(millis: Long) {
        runCatching { prefs.edit().putLong(KEY_INCIDENT_AT, millis).apply() }
            .onFailure { Log.w(TAG, "Could not record the incident", it) }
    }

    fun clearIncidentAt() {
        runCatching { prefs.edit().remove(KEY_INCIDENT_AT).apply() }
    }

    /** Every incident still inside retention, oldest first. Empty when there have been none. */
    fun log(nowMillis: Long = System.currentTimeMillis()): Array<IncidentRecord> =
        IncidentLog.pruned(
            IncidentLog.parse(runCatching { prefs.getString(KEY_LOG, null) }.getOrNull()),
            nowMillis,
        )

    /**
     * Empties the log at once — the wearer's own door, ahead of the thirty-day ageing.
     *
     * It is in the editor rather than on the face for the same reason clearing an incident takes
     * two taps: a record of somebody failing to answer should not be removable by a sleeve.
     */
    fun clearLog() {
        runCatching {
            prefs.edit()
                .remove(KEY_LOG)
                .remove(KEY_LOG_SHIFT_START)
                .remove(KEY_LOG_SHIFT_END)
                .apply()
        }
    }

    // --- Which watch the log belongs to -------------------------------------------------------

    /**
     * The watch the log on file was recorded during, or zero when there is none.
     *
     * Stored rather than inferred, and this is the whole reason it exists: the log is emptied when
     * a *new* watch begins, and "new" has to survive a process restart. Without a written-down
     * answer the first frame after a reboot would compare the running watch against nothing,
     * decide it was new, and wipe the incidents of the watch under way — the one case where the
     * record matters most.
     */
    fun logShiftStart(): Long = runCatching { prefs.getLong(KEY_LOG_SHIFT_START, 0L) }.getOrDefault(0L)

    fun logShiftEnd(): Long = runCatching { prefs.getLong(KEY_LOG_SHIFT_END, 0L) }.getOrDefault(0L)

    /** Records which watch the log now belongs to, and how long it was booked for. */
    fun noteLogShift(startMillis: Long, endMillis: Long) {
        runCatching {
            prefs.edit()
                .putLong(KEY_LOG_SHIFT_START, startMillis)
                .putLong(KEY_LOG_SHIFT_END, endMillis)
                .apply()
        }
    }

    /**
     * Appends an incident and returns the log it produced.
     *
     * Returning the new array rather than mutating one is what makes this safe to publish straight
     * into [VigilanceState.incidents]: the render thread only ever sees a finished array through a
     * volatile reference, so there is no half-written log to draw and nothing to lock.
     */
    fun append(record: IncidentRecord): Array<IncidentRecord> {
        val next = IncidentLog.appended(log(record.atMillis), record)
        runCatching { prefs.edit().putString(KEY_LOG, IncidentLog.pack(next)).apply() }
            .onFailure { Log.w(TAG, "Could not extend the incident log", it) }
        return next
    }

    // --- Monitor configuration ---------------------------------------------------------------

    /**
     * The interval and nudge strength the service was last given.
     *
     * Persisted because a START_STICKY restart redelivers a *null* intent: the process the fields
     * lived in is gone, and without this the service came back watching at the ten-minute default
     * whatever the user had chosen — until the next frame of the face happened to re-report it.
     */
    fun saveConfig(
        intervalMillis: Long,
        vibeAmplitude: Int,
        toneVolume: Int,
        logHeartRate: Boolean,
    ) {
        runCatching {
            prefs.edit()
                .putLong(KEY_INTERVAL, intervalMillis)
                .putInt(KEY_AMPLITUDE, vibeAmplitude)
                .putInt(KEY_TONE_VOLUME, toneVolume)
                .putBoolean(KEY_LOG_HR, logHeartRate)
                .apply()
        }
    }

    fun intervalMillis(fallback: Long): Long =
        runCatching { prefs.getLong(KEY_INTERVAL, fallback) }.getOrDefault(fallback)

    fun vibeAmplitude(fallback: Int): Int =
        runCatching { prefs.getInt(KEY_AMPLITUDE, fallback) }.getOrDefault(fallback)

    fun toneVolume(fallback: Int): Int =
        runCatching { prefs.getInt(KEY_TONE_VOLUME, fallback) }.getOrDefault(fallback)

    fun logHeartRate(fallback: Boolean): Boolean =
        runCatching { prefs.getBoolean(KEY_LOG_HR, fallback) }.getOrDefault(fallback)

    companion object {
        private const val TAG = "Vigilance"
        private const val PREFS_NAME = "mfd24_vigilance"
        private const val KEY_INCIDENT_AT = "incident_at"
        private const val KEY_LOG = "incident_log"
        private const val KEY_LOG_SHIFT_START = "log_shift_start"
        private const val KEY_LOG_SHIFT_END = "log_shift_end"
        private const val KEY_INTERVAL = "interval_millis"
        private const val KEY_AMPLITUDE = "vibe_amplitude"
        private const val KEY_TONE_VOLUME = "sos_volume"
        private const val KEY_LOG_HR = "log_heart_rate"

        /**
         * Kept here as the one name the rest of the code reaches for. The arithmetic itself moved
         * to [IncidentLog], where it is pure and tested; these are the doors that were already in
         * use, and renaming them would have touched five files to say nothing new.
         */
        val EMPTY: Array<IncidentRecord> = IncidentLog.EMPTY

        const val MAX_ENTRIES: Int = IncidentLog.MAX_ENTRIES
        const val RETENTION_MILLIS: Long = IncidentLog.RETENTION_MILLIS

        fun belongsToEarlierWatch(incidentMillis: Long, shiftStartMillis: Long): Boolean =
            IncidentLog.belongsToEarlierWatch(incidentMillis, shiftStartMillis)
    }
}
