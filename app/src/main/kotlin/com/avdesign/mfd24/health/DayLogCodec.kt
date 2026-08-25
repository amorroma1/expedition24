// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

/**
 * The day log as one preference string — the shape every record in this project takes, so a
 * `dumpsys`-and-read is enough to see what the watch believes about a day.
 *
 * `version|epochDay|bin,bin,bin,…` with 96 bins, each `flags[:bpm[:steps]]` and trailing zeros
 * dropped, so an untouched night costs one comma apiece. About 800 characters at its worst,
 * written once a quarter-hour.
 *
 * The failure policy sits deliberately between this project's two precedents, because a day log
 * is both kinds of thing at once. A malformed **bin** becomes an absent bin and the rest of the
 * day survives — the incident log's rule, that for a record the direction which hurts is loss.
 * A malformed **header**, or a bin count that is not 96, drops the whole day — the relay
 * cache's rule, that once the indices cannot be trusted, a partial answer is not a smaller
 * truth but a confident lie about which hour was which.
 *
 * Pure; the arrays are the caller's.
 */
object DayLogCodec {

    /** Bumped only if the grammar changes; an unknown version is refused whole. */
    const val VERSION = 1

    /** No day could be read out of the string. */
    const val NO_DAY = -1L

    fun pack(
        epochDay: Long,
        hr: ByteArray,
        steps: ShortArray,
        flags: ByteArray,
    ): String {
        val sb = StringBuilder(1024)
        sb.append(VERSION).append('|').append(epochDay).append('|')
        for (i in 0 until DayBins.BIN_COUNT) {
            if (i > 0) sb.append(',')
            val f = flags[i].toInt() and 0xFF
            val bpm = hr[i].toInt() and 0xFF
            val st = steps[i].toInt()
            if (f == 0 && bpm == DayBins.NO_BPM && st == 0) continue
            sb.append(f)
            if (bpm != DayBins.NO_BPM || st != 0) {
                sb.append(':').append(bpm)
                if (st != 0) sb.append(':').append(st)
            }
        }
        return sb.toString()
    }

    /**
     * Fills the caller's arrays from [packed] and returns the day it belongs to, or [NO_DAY]
     * when nothing trustworthy could be read. The arrays are cleared first, so a refused string
     * leaves an empty day rather than the previous one half-overwritten.
     */
    fun unpack(
        packed: String?,
        outHr: ByteArray,
        outSteps: ShortArray,
        outFlags: ByteArray,
    ): Long {
        java.util.Arrays.fill(outHr, 0)
        java.util.Arrays.fill(outSteps, 0)
        java.util.Arrays.fill(outFlags, 0)
        if (packed.isNullOrEmpty()) return NO_DAY

        val firstBar = packed.indexOf('|')
        if (firstBar <= 0) return NO_DAY
        val secondBar = packed.indexOf('|', firstBar + 1)
        if (secondBar < 0) return NO_DAY
        val version = packed.substring(0, firstBar).toIntOrNull() ?: return NO_DAY
        if (version != VERSION) return NO_DAY
        val epochDay = packed.substring(firstBar + 1, secondBar).toLongOrNull() ?: return NO_DAY

        val body = packed.substring(secondBar + 1)
        val entries = body.split(',')
        if (entries.size != DayBins.BIN_COUNT) return NO_DAY

        for (i in 0 until DayBins.BIN_COUNT) {
            val entry = entries[i]
            if (entry.isEmpty()) continue
            val parts = entry.split(':')
            if (parts.size > 3) continue
            val f = parts[0].toIntOrNull() ?: continue
            val bpm = if (parts.size > 1) parts[1].toIntOrNull() ?: continue else DayBins.NO_BPM
            val st = if (parts.size > 2) parts[2].toIntOrNull() ?: continue else 0
            if (f !in 0..0xFF || bpm !in 0..0xFF || st < 0 || st > Short.MAX_VALUE) continue
            outFlags[i] = f.toByte()
            outHr[i] = bpm.toByte()
            outSteps[i] = st.toShort()
        }
        return epochDay
    }
}
