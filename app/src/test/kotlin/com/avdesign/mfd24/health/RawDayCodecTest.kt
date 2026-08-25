// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw export, and the sizes the KDoc claims for it.
 *
 * A size in a comment is a guess; a size in a test is a measurement that fails when the format
 * drifts. The numbers here are what decide whether a day fits in one QR code — capacity 858 bytes
 * at version 20 level L, which is the encoder this project ships — so they are worth a red build.
 */
class RawDayCodecTest {

    /** A day that looks like a real one: quiet night, a walk, an afternoon, a still evening. */
    private fun realisticDay(epochDay: Int, seed: Int = 0): RawDayCodec.Day {
        val hr = ByteArray(DayBins.BIN_COUNT)
        val steps = ShortArray(DayBins.BIN_COUNT)
        val flags = ByteArray(DayBins.BIN_COUNT)
        val watched = (DayBins.FLAG_SAMPLED or DayBins.FLAG_ON_BODY).toByte()
        for (i in 0 until DayBins.BIN_COUNT) {
            val hour = i * DayBins.BIN_MINUTES / 60
            flags[i] = watched
            when {
                hour < 7 -> {
                    hr[i] = (58 + (i + seed) % 5).toByte()
                }
                hour < 9 -> {
                    hr[i] = (88 + (i + seed) % 9).toByte()
                    steps[i] = (600 + (i * 37 + seed) % 300).toShort()
                    flags[i] = (watched.toInt() or DayBins.FLAG_MOVING).toByte()
                }
                hour < 18 -> {
                    hr[i] = (72 + (i + seed) % 7).toByte()
                    steps[i] = ((i * 53 + seed) % 220).toShort()
                    if (steps[i] >= DayBins.MOVING_MIN_STEPS) {
                        flags[i] = (watched.toInt() or DayBins.FLAG_MOVING).toByte()
                    }
                }
                else -> hr[i] = (69 + (i + seed) % 4).toByte()
            }
        }
        return RawDayCodec.Day(epochDay, hr, steps, flags)
    }

    @Test
    fun `a day survives the round trip byte for byte`() {
        val day = realisticDay(20323)
        val back = RawDayCodec.unpack(RawDayCodec.pack(listOf(day)))
        assertEquals(1, back.size)
        assertEquals(20323, back[0].epochDay)
        assertArrayEquals(day.hr, back[0].hr)
        assertArrayEquals(day.flags, back[0].flags)
        assertArrayEquals(day.steps, back[0].steps)
    }

    @Test
    fun `several days come back in the order they went in, each naming itself`() {
        val days = listOf(realisticDay(20320, 1), realisticDay(20321, 2), realisticDay(20322, 3))
        val back = RawDayCodec.unpack(RawDayCodec.pack(days))
        assertEquals(3, back.size)
        assertEquals(listOf(20320, 20321, 20322), back.map { it.epochDay })
        assertArrayEquals(days[1].hr, back[1].hr)
    }

    /**
     * The claim the export is built on: one day fits one code, comfortably.
     *
     * 388 raw bytes a day, a fifth of that deflated, a third again in Base64 — against a QR
     * capacity of 858. If a change to the grid ever pushes a day past that, the export silently
     * becomes tones-only, and this is where that gets noticed.
     */
    @Test
    fun `a day fits in one QR code with room to spare`() {
        val packed = RawDayCodec.pack(listOf(realisticDay(20323)))
        val packet = RawDayCodec.packet("SIERRA-07", "1a2b3c4d", listOf(realisticDay(20323)))
        assertEquals(388, RawDayCodec.DAY_BYTES)
        assertTrue("deflated day was ${packed.size} bytes", packed.size < 200)
        assertTrue("packet was ${packet.length} chars", packet.length < 400)
    }

    /**
     * A week does not, and that is why the export screen sends two days rather than everything.
     *
     * About 1.2 kB against the encoder's 858: a week would need several codes or a minute of
     * tones, and the day somebody is arguing about is nearly always last night. Measured here so
     * that if the grid ever shrinks enough to change the answer, this test says so.
     */
    @Test
    fun `a week does not fit one QR code, which is why two days is the offer`() {
        val week = (0 until 7).map { realisticDay(20320 + it, it) }
        val packet = RawDayCodec.packet("SIERRA-07", "1a2b3c4d", week)
        assertTrue("week packet was ${packet.length} chars", packet.length > 858)
        assertTrue("week packet was ${packet.length} chars", packet.length < 1500)

        val pair = (0 until 2).map { realisticDay(20320 + it, it) }
        assertTrue(RawDayCodec.packet("SIERRA-07", "1a2b3c4d", pair).length <= 858)
    }

    @Test
    fun `a packet names its days in the clear`() {
        val packet = RawDayCodec.packet("SIERRA-07", "1a2b3c4d", listOf(realisticDay(20323)))
        val lines = packet.split('\n')
        assertEquals(RawDayCodec.HEADER, lines[0])
        assertEquals("SIERRA-07 1a2b3c4d", lines[1])
        assertEquals("DAYS 20323", lines[2])
        assertEquals(lines[3], RawDayCodec.bodyOf(packet))
    }

    @Test
    fun `rubbish is refused rather than half-read`() {
        assertEquals(0, RawDayCodec.unpack(ByteArray(0)).size)
        assertEquals(0, RawDayCodec.unpack(byteArrayOf(1, 2, 3, 4, 5)).size)
        // Valid compression, wrong length: a truncated grid is not a day and must not become one.
        val short = RawDayCodec.pack(listOf(realisticDay(20323)))
        val truncated = short.copyOf(short.size - 3)
        assertEquals(0, RawDayCodec.unpack(truncated).size)
        assertNull(RawDayCodec.bodyOf("hello\nthere\nyou\nare"))
    }

    @Test
    fun `base64 is the base64 everyone else has`() {
        val bytes = ByteArray(256) { it.toByte() }
        val text = RawDayCodec.encodeBase64(bytes)
        assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), text)
        assertArrayEquals(bytes, RawDayCodec.decodeBase64(text))
        // The unpadded tails, both of them.
        assertEquals("AQ==", RawDayCodec.encodeBase64(byteArrayOf(1)))
        assertEquals("AQI=", RawDayCodec.encodeBase64(byteArrayOf(1, 2)))
    }
}
