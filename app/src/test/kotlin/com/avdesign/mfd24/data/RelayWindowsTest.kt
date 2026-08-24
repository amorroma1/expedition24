// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayWindowsTest {

    private fun table(startMillis: Long, stepMillis: Long, vararg elevations: Double): HorizonsTable {
        val t = HorizonsTable()
        for (i in elevations.indices) {
            t.timesMillis[i] = startMillis + i * stepMillis
            t.elevationDeg[i] = elevations[i]
        }
        t.count = elevations.size
        return t
    }

    @Test
    fun `a pass becomes one window with interpolated edges`() {
        // Rises through 10 exactly halfway between samples 1 and 2, sets halfway between 3 and 4.
        val t = table(0L, 60_000L, -10.0, 0.0, 20.0, 20.0, 0.0, -10.0)
        val start = LongArray(4)
        val end = LongArray(4)
        assertEquals(1, RelayWindows.extract(t, 10.0, start, end))
        assertEquals(90_000L, start[0])
        assertEquals(210_000L, end[0])
    }

    @Test
    fun `never above is zero windows and always above is the whole table`() {
        val start = LongArray(4)
        val end = LongArray(4)
        assertEquals(
            0,
            RelayWindows.extract(table(0L, 60_000L, -5.0, -2.0, -8.0), 10.0, start, end),
        )
        assertEquals(
            1,
            RelayWindows.extract(table(0L, 60_000L, 15.0, 25.0, 15.0), 10.0, start, end),
        )
        assertEquals(0L, start[0])
        assertEquals(120_000L, end[0])
    }

    @Test
    fun `overlapping satellites merge into one honest arc`() {
        val starts = arrayOf(longArrayOf(0L, 500L), longArrayOf(100L), longArrayOf(900L))
        val ends = arrayOf(longArrayOf(200L, 600L), longArrayOf(300L), longArrayOf(950L))
        val counts = intArrayOf(2, 1, 1)
        val outStart = LongArray(8)
        val outEnd = LongArray(8)
        val n = RelayWindows.union(starts, ends, counts, outStart, outEnd)
        assertEquals(3, n)
        assertEquals(0L, outStart[0]); assertEquals(300L, outEnd[0])
        assertEquals(500L, outStart[1]); assertEquals(600L, outEnd[1])
        assertEquals(900L, outStart[2]); assertEquals(950L, outEnd[2])
    }

    @Test
    fun `the cache codec round-trips and refuses damage whole`() {
        val start = longArrayOf(1_000L, 2_000L, 3_000L)
        val end = longArrayOf(1_500L, 2_500L, 3_500L)
        val packed = RelayWindows.pack(start, end, 3)
        val outStart = LongArray(4)
        val outEnd = LongArray(4)
        assertEquals(3, RelayWindows.unpack(packed, outStart, outEnd))
        assertEquals(2_000L, outStart[1])
        assertEquals(3_500L, outEnd[2])
        assertEquals(0, RelayWindows.unpack("", outStart, outEnd))
        // Damage anywhere empties the answer: half a cache would draw half a sky.
        assertEquals(0, RelayWindows.unpack("1000:1500;garbage", outStart, outEnd))
        assertEquals(0, RelayWindows.unpack("2000:1500", outStart, outEnd))
    }
}
