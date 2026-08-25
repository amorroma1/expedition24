// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

/** What is kept of a day once its bins are no longer needed: the six figures the advice reads. */
class DaySummary(
    @JvmField val epochDay: Long,
    @JvmField val steps: Int,
    /** Resting pulse — the day's tenth percentile — or [DayBins.NO_BPM] with too few samples. */
    @JvmField val restingBpm: Int,
    @JvmField val sleepMinutes: Int,
    @JvmField val wakeEpisodes: Int,
    /** Share of still, worn, awake bins whose pulse ran above the sedentary baseline. */
    @JvmField val hrHighSharePct: Int,
    /** Minute of the day the night's sleep began; −1 when no night was found. */
    @JvmField val sleepOnsetMinutes: Int,
)

/**
 * A fortnight of days, packed into one preference string, and the baselines drawn from them.
 *
 * Fourteen days for the same reason the incident log keeps thirty: long enough to know what
 * *this* wearer's ordinary week looks like — which is the only standard the advice is allowed to
 * judge against, since resting pulse alone spans forty beats between two healthy people — and
 * short enough that the watch never becomes a dossier. Nothing here is a medical record; it is
 * the memory needed to say "less than you usually walk" instead of "less than a stranger walks".
 *
 * Pure, and allocation is fine: this runs once at a day boundary and once per report, never in
 * a frame.
 */
object DaySummaries {

    const val KEEP_DAYS = 14

    /**
     * Fewer than three days is not a baseline, it is a coincidence. Below this every baseline
     * answers [NO_BASELINE] and the advice that depends on one stays silent rather than
     * guessing at a stranger's norm.
     */
    const val MIN_BASELINE_DAYS = 3

    const val NO_BASELINE = -1

    fun pack(days: Array<DaySummary>): String {
        val sb = StringBuilder(256)
        for (d in days) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(d.epochDay).append(':')
                .append(d.steps).append(':')
                .append(d.restingBpm).append(':')
                .append(d.sleepMinutes).append(':')
                .append(d.wakeEpisodes).append(':')
                .append(d.hrHighSharePct).append(':')
                .append(d.sleepOnsetMinutes)
        }
        return sb.toString()
    }

    /** Malformed entries are dropped and the rest kept — for a record, loss is the direction that hurts. */
    fun parse(packed: String?): Array<DaySummary> {
        if (packed.isNullOrEmpty()) return emptyArray()
        val out = ArrayList<DaySummary>(KEEP_DAYS)
        for (entry in packed.split(';')) {
            if (entry.isEmpty()) continue
            val p = entry.split(':')
            if (p.size != 7) continue
            val day = p[0].toLongOrNull() ?: continue
            val steps = p[1].toIntOrNull() ?: continue
            val resting = p[2].toIntOrNull() ?: continue
            val sleep = p[3].toIntOrNull() ?: continue
            val wake = p[4].toIntOrNull() ?: continue
            val share = p[5].toIntOrNull() ?: continue
            val onset = p[6].toIntOrNull() ?: continue
            out.add(DaySummary(day, steps, resting, sleep, wake, share, onset))
        }
        return out.toTypedArray()
    }

    /** Appends [day], replacing any entry for the same date, and keeps the newest [KEEP_DAYS]. */
    fun appended(previous: Array<DaySummary>, day: DaySummary): Array<DaySummary> {
        val kept = previous.filter { it.epochDay != day.epochDay }.toMutableList()
        kept.add(day)
        kept.sortBy { it.epochDay }
        while (kept.size > KEEP_DAYS) kept.removeAt(0)
        return kept.toTypedArray()
    }

    /** The wearer's ordinary day, as a median — one holiday hike must not raise the bar for a week. */
    fun baselineSteps(days: Array<DaySummary>): Int = medianOf(days) { it.steps }

    fun baselineRestingBpm(days: Array<DaySummary>): Int =
        medianOf(days.filter { it.restingBpm > DayBins.NO_BPM }.toTypedArray()) { it.restingBpm }

    fun baselineSleepMinutes(days: Array<DaySummary>): Int = medianOf(days) { it.sleepMinutes }

    /**
     * How many days in a row — counting back from [todaySharePct] — have run a high sedentary
     * pulse. What turns a bad Tuesday into something worth saying out loud.
     */
    fun consecutiveHighHrDays(days: Array<DaySummary>, todaySharePct: Int, thresholdPct: Int): Int {
        if (todaySharePct < thresholdPct) return 0
        var run = 1
        for (i in days.indices.reversed()) {
            if (days[i].hrHighSharePct < thresholdPct) break
            run++
        }
        return run
    }

    private inline fun medianOf(days: Array<DaySummary>, value: (DaySummary) -> Int): Int {
        if (days.size < MIN_BASELINE_DAYS) return NO_BASELINE
        val values = IntArray(days.size) { value(days[it]) }
        values.sort()
        return values[values.size / 2]
    }
}
