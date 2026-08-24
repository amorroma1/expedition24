// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

/**
 * From elevation tables to windows: pure arithmetic, deliberately Android-free so the threshold
 * crossings, the union and the cache codec are all pinned by JVM tests — the alternative is
 * reading arcs off a wrist against a spacecraft timetable.
 */
object RelayWindows {

    /**
     * Extracts the intervals of [table] where elevation exceeds [minElevationDeg], edges by
     * linear interpolation between the samples either side of the crossing. A pass already under
     * way at the table's first row starts there; one still under way at the last row ends there
     * — a table is a slice of sky, and pretending to know more than it holds is how a window
     * gets invented. Returns the window count.
     */
    fun extract(
        table: HorizonsTable,
        minElevationDeg: Double,
        outStart: LongArray,
        outEnd: LongArray,
    ): Int {
        var count = 0
        if (table.count == 0) return 0
        var above = table.elevationDeg[0] > minElevationDeg
        var openStart = if (above) table.timesMillis[0] else 0L
        var i = 1
        while (i < table.count) {
            val nowAbove = table.elevationDeg[i] > minElevationDeg
            if (nowAbove != above) {
                val crossing = interpolate(table, i, minElevationDeg)
                if (nowAbove) {
                    openStart = crossing
                } else if (count < outStart.size) {
                    outStart[count] = openStart
                    outEnd[count] = crossing
                    count++
                }
                above = nowAbove
            }
            i++
        }
        if (above && count < outStart.size) {
            outStart[count] = openStart
            outEnd[count] = table.timesMillis[table.count - 1]
            count++
        }
        return count
    }

    /**
     * Merges several satellites' window lists into one sorted, overlap-free union — the outer
     * line answers "is anything overhead", so two orbiters sharing a horizon become one arc.
     * Returns the union's count.
     */
    fun union(
        starts: Array<LongArray>,
        ends: Array<LongArray>,
        counts: IntArray,
        outStart: LongArray,
        outEnd: LongArray,
    ): Int {
        // Collect, then insertion-sort by start: the lists are tiny (a dozen windows a sol).
        var total = 0
        for (s in counts.indices) total += counts[s]
        if (total == 0) return 0
        val allStart = LongArray(total)
        val allEnd = LongArray(total)
        var n = 0
        for (s in counts.indices) {
            for (i in 0 until counts[s]) {
                allStart[n] = starts[s][i]
                allEnd[n] = ends[s][i]
                n++
            }
        }
        for (i in 1 until n) {
            val ks = allStart[i]
            val ke = allEnd[i]
            var j = i - 1
            while (j >= 0 && allStart[j] > ks) {
                allStart[j + 1] = allStart[j]
                allEnd[j + 1] = allEnd[j]
                j--
            }
            allStart[j + 1] = ks
            allEnd[j + 1] = ke
        }
        var count = 0
        var curStart = allStart[0]
        var curEnd = allEnd[0]
        for (i in 1 until n) {
            if (allStart[i] <= curEnd) {
                if (allEnd[i] > curEnd) curEnd = allEnd[i]
            } else {
                if (count < outStart.size) {
                    outStart[count] = curStart
                    outEnd[count] = curEnd
                    count++
                }
                curStart = allStart[i]
                curEnd = allEnd[i]
            }
        }
        if (count < outStart.size) {
            outStart[count] = curStart
            outEnd[count] = curEnd
            count++
        }
        return count
    }

    /** Packs windows for the preference cache: `start:end;start:end`, empty for none. */
    fun pack(startMillis: LongArray, endMillis: LongArray, count: Int): String {
        val sb = StringBuilder()
        for (i in 0 until count) {
            if (i > 0) sb.append(';')
            sb.append(startMillis[i]).append(':').append(endMillis[i])
        }
        return sb.toString()
    }

    /**
     * Unpacks [packed] into the caller's arrays; returns the count. A malformed entry drops the
     * whole string — a cache is a convenience, and half a cache is a lie.
     */
    fun unpack(packed: String, outStart: LongArray, outEnd: LongArray): Int {
        if (packed.isEmpty()) return 0
        var count = 0
        for (entry in packed.split(';')) {
            val sep = entry.indexOf(':')
            if (sep <= 0 || count >= outStart.size) return 0
            val start = entry.substring(0, sep).toLongOrNull() ?: return 0
            val end = entry.substring(sep + 1).toLongOrNull() ?: return 0
            if (end < start) return 0
            outStart[count] = start
            outEnd[count] = end
            count++
        }
        return count
    }

    private fun interpolate(table: HorizonsTable, i: Int, threshold: Double): Long {
        val e0 = table.elevationDeg[i - 1]
        val e1 = table.elevationDeg[i]
        val t0 = table.timesMillis[i - 1]
        val t1 = table.timesMillis[i]
        val fraction = (threshold - e0) / (e1 - e0)
        return t0 + Math.round((t1 - t0) * fraction)
    }
}
