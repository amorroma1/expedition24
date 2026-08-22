// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MortonTest {

    @Test
    fun `encode and decode round trip`() {
        val random = Random(7)
        repeat(20_000) {
            val x = random.nextInt(Morton.GRID)
            val y = random.nextInt(Morton.GRID)
            val key = Morton.encode(x, y)
            assertEquals(x, Morton.decodeX(key))
            assertEquals(y, Morton.decodeY(key))
        }
    }

    @Test
    fun `keys are monotonic in longitude for a fixed latitude row`() {
        // This is the property the row-wise range query relies on.
        val random = Random(11)
        repeat(200) {
            val y = random.nextInt(Morton.GRID)
            var previous = Morton.encode(0, y)
            for (x in 1 until 512) {
                val key = Morton.encode(x, y)
                assertTrue(Morton.compareKeys(previous, key) < 0)
                previous = key
            }
        }
    }

    @Test
    fun `quantisation covers the whole globe and clamps the extremes`() {
        assertEquals(0, Morton.quantizeLon(-180.0))
        assertEquals(Morton.GRID - 1, Morton.quantizeLon(180.0))
        assertEquals(0, Morton.quantizeLat(-90.0))
        assertEquals(Morton.GRID - 1, Morton.quantizeLat(90.0))
        assertEquals(Morton.GRID / 2, Morton.quantizeLon(0.0))
        assertEquals(Morton.GRID / 2, Morton.quantizeLat(0.0))
    }

    @Test
    fun `bucket is the top byte of the key`() {
        val random = Random(3)
        repeat(1000) {
            val key = random.nextInt()
            assertEquals(key ushr 24, Morton.bucketOf(key))
            assertTrue(Morton.bucketOf(key) in 0 until PoiFormat.BUCKET_COUNT)
        }
    }

    @Test
    fun `unsigned comparison orders keys with the sign bit set`() {
        assertTrue(Morton.compareKeys(0x7FFFFFFF, -1) < 0)
        assertTrue(Morton.compareKeys(-1, 0) > 0)
        assertEquals(0, Morton.compareKeys(-5, -5))
    }
}
