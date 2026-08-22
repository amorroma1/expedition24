// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

/**
 * One incident, and the arithmetic of the log that holds them.
 *
 * An incident used to be a single instant, and an instant answers only "when". The question a
 * debrief actually asks next is "and what state were they in" — which a wrist that has a pulse
 * sensor on it can answer, if it was told to. So a record carries, when heart-rate logging is on:
 *
 *  - **the incident pulse**, sampled during the thirty-second answer window, between the nudge and
 *    the SOS. That is the operator at the moment they stopped responding.
 *  - **the reference pulse**, the last reading taken while they were demonstrably moving, *and the
 *    instant it was taken*. A number with no baseline beside it says nothing: 48 bpm is an athlete
 *    asleep or a casualty, and only the pair tells them apart. The instant matters for the same
 *    reason — a baseline from four hours ago is a different claim from one from four minutes ago.
 *
 * Every field is optional and absent means absent, never zero: no permission, no setting, or a
 * sensor that never locked on all read as `NO_BPM`, and nothing downstream may present a missing
 * reading as a low one.
 *
 * The packed form stays a single preferences string that a person can read out of a dump — one
 * record per comma, fields separated by colons, oldest first:
 *
 * ```
 * 1787326665000:41:58:1787325300000,1787330000000
 * ```
 *
 * The second record there is the **old format**, a bare instant, and parsing keeps accepting it: a
 * log written by 2.2.0 or earlier must survive the update that taught the watch about pulses. A
 * record that cannot be parsed at all is skipped rather than thrown, because a log that throws on
 * the service's start path is a dead-man's switch that never arms.
 */
data class IncidentRecord(
    /** When the operator stopped answering, absolute epoch millis. */
    val atMillis: Long,
    /** Pulse during the unanswered answer window, or [NO_BPM]. */
    val bpm: Int = NO_BPM,
    /** Last pulse recorded while the operator was moving, or [NO_BPM]. */
    val baselineBpm: Int = NO_BPM,
    /** When that reference pulse was taken, or zero. */
    val baselineAtMillis: Long = 0L,
) {
    val hasBpm: Boolean get() = bpm != NO_BPM
    val hasBaseline: Boolean get() = baselineBpm != NO_BPM && baselineAtMillis > 0L

    companion object {
        /** No reading. Distinct from any plausible pulse, and never rendered as a number. */
        const val NO_BPM: Int = -1
    }
}

/**
 * The log's pure arithmetic: packing, parsing, the entry ceiling and the retention window.
 *
 * Separate from [VigilanceStore] because these are the parts with decisions in them — which end of
 * a full log is dropped, what a backwards clock jump does — and those are pinned by tests rather
 * than by reading a preferences file off a wrist.
 */
object IncidentLog {

    val EMPTY: Array<IncidentRecord> = emptyArray()

    /**
     * Ceiling on the log.
     *
     * It bounds the string in prefs and the array the renderer walks once a frame, and it is
     * comfortably more than a single watch can produce: at the shortest interval and the longest
     * shift, an operator who answered nothing at all would record about a dozen.
     */
    const val MAX_ENTRIES: Int = 32

    /**
     * How long an entry is kept, alongside the entry ceiling: thirty days.
     *
     * This is a personal aid, not a certified recorder, and the habit for uncertified event data is
     * to keep it for the review it exists for — a debrief, a rota cycle, days to weeks — not for
     * ever. A month covers the longest of those; past it an entry stops being evidence for anyone
     * and starts being a dossier on the wearer.
     */
    const val RETENTION_MILLIS: Long = 30L * 24 * 3_600_000L

    /**
     * The log with one more entry, oldest first and capped.
     *
     * The ceiling drops the *oldest* rather than refusing the newest: a watch that has already gone
     * badly must not stop recording exactly when the record starts to matter.
     */
    fun appended(previous: Array<IncidentRecord>, record: IncidentRecord): Array<IncidentRecord> {
        val keep = if (previous.size < MAX_ENTRIES) previous.size else MAX_ENTRIES - 1
        val next = arrayOfNulls<IncidentRecord>(keep + 1)
        previous.copyInto(next, 0, previous.size - keep, previous.size)
        next[keep] = record
        @Suppress("UNCHECKED_CAST")
        return next as Array<IncidentRecord>
    }

    /**
     * The log with everything older than [RETENTION_MILLIS] dropped.
     *
     * Applied on every read, so old entries age out without a scheduled job. Entries are
     * oldest-first, so retention is a prefix drop. A clock that jumps backwards prunes nothing: for
     * a record the failure direction is loss, so doubt keeps.
     */
    fun pruned(log: Array<IncidentRecord>, nowMillis: Long): Array<IncidentRecord> {
        val cutoff = nowMillis - RETENTION_MILLIS
        var first = 0
        while (first < log.size && log[first].atMillis < cutoff) first++
        return if (first == 0) log else log.copyOfRange(first, log.size)
    }

    fun pack(log: Array<IncidentRecord>): String {
        val out = StringBuilder(log.size * 24)
        for (i in log.indices) {
            if (i > 0) out.append(',')
            val record = log[i]
            out.append(record.atMillis)
            // Trailing fields are written only when there is something in them, so a log recorded
            // with heart rate off stays exactly as compact as it used to be.
            if (record.hasBpm || record.hasBaseline) {
                out.append(':').append(record.bpm)
                if (record.hasBaseline) {
                    out.append(':').append(record.baselineBpm)
                    out.append(':').append(record.baselineAtMillis)
                }
            }
        }
        return out.toString()
    }

    /** Unpacks a log, skipping anything unreadable rather than throwing. Accepts bare instants. */
    fun parse(packed: String?): Array<IncidentRecord> {
        if (packed.isNullOrEmpty()) return EMPTY
        val out = ArrayList<IncidentRecord>(8)
        for (entry in packed.split(',')) {
            if (entry.isEmpty()) continue
            val fields = entry.split(':')
            val at = fields[0].toLongOrNull() ?: continue
            if (at <= 0L) continue
            val bpm = fields.getOrNull(1)?.toIntOrNull() ?: IncidentRecord.NO_BPM
            val baseline = fields.getOrNull(2)?.toIntOrNull() ?: IncidentRecord.NO_BPM
            val baselineAt = fields.getOrNull(3)?.toLongOrNull() ?: 0L
            out.add(
                IncidentRecord(
                    atMillis = at,
                    bpm = if (bpm > 0) bpm else IncidentRecord.NO_BPM,
                    baselineBpm = if (baseline > 0) baseline else IncidentRecord.NO_BPM,
                    baselineAtMillis = if (baselineAt > 0L) baselineAt else 0L,
                )
            )
        }
        return if (out.isEmpty()) EMPTY else out.toTypedArray()
    }

    /**
     * Just the instants, for the renderer.
     *
     * `render()` allocates nothing, so the marks on the duty arc are walked as a `LongArray` built
     * here — once per write of the log, never per frame.
     */
    fun times(log: Array<IncidentRecord>): LongArray =
        LongArray(log.size) { log[it].atMillis }

    /**
     * Whether an incident belongs to a watch that has already ended.
     *
     * Zero for either argument means there is nothing to decide — no incident, or no watch — and
     * the answer is no. Anything on or after the start belongs to the watch under way and has to
     * keep holding the monitor down.
     */
    fun belongsToEarlierWatch(incidentMillis: Long, shiftStartMillis: Long): Boolean =
        incidentMillis > 0L && shiftStartMillis > 0L && incidentMillis < shiftStartMillis

    /** A plausible pulse from a wrist sensor. Zero means "not locked on", not "no heartbeat". */
    fun plausibleBpm(bpm: Int): Boolean = bpm in MIN_BPM..MAX_BPM

    /**
     * The range a wrist optical sensor's readings are believed in.
     *
     * The bounds are wide on purpose — this is a filter against the sensor's own nonsense (a zero
     * before it locks on, a spike from a moving strap), not a medical judgement about the wearer.
     */
    const val MIN_BPM: Int = 25
    const val MAX_BPM: Int = 240
}
