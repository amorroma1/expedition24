// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayLogCodecTest {

    private val hr = ByteArray(DayBins.BIN_COUNT)
    private val steps = ShortArray(DayBins.BIN_COUNT)
    private val flags = ByteArray(DayBins.BIN_COUNT)

    private fun blank() {
        java.util.Arrays.fill(hr, 0)
        java.util.Arrays.fill(steps, 0)
        java.util.Arrays.fill(flags, 0)
    }

    private fun bin(i: Int, bpm: Int, st: Int, f: Int) {
        hr[i] = bpm.toByte()
        steps[i] = st.toShort()
        flags[i] = f.toByte()
    }

    @Test
    fun `a day round-trips through its packed form`() {
        blank()
        val watched = DayBins.FLAG_SAMPLED or DayBins.FLAG_ON_BODY
        bin(0, 54, 0, watched or DayBins.FLAG_SLEEP)
        bin(33, 88, 1_240, watched or DayBins.FLAG_MOVING)
        bin(64, 0, 0, DayBins.FLAG_SAMPLED or DayBins.FLAG_CHARGING)
        // The top of the unsigned byte: a pulse of 240 must survive the round trip, not wrap.
        bin(95, 240, 12, watched or DayBins.FLAG_MOVING)
        val packed = DayLogCodec.pack(20_759L, hr, steps, flags)

        blank()
        assertEquals(20_759L, DayLogCodec.unpack(packed, hr, steps, flags))
        assertEquals(54, hr[0].toInt() and 0xFF)
        assertEquals(240, hr[95].toInt() and 0xFF)
        assertEquals(1_240, steps[33].toInt())
        assertEquals(watched or DayBins.FLAG_SLEEP, flags[0].toInt() and 0xFF)
        assertEquals(DayBins.FLAG_SAMPLED or DayBins.FLAG_CHARGING, flags[64].toInt() and 0xFF)
        assertEquals(0, flags[1].toInt())
    }

    @Test
    fun `an untouched day packs to commas and reads back empty`() {
        blank()
        val packed = DayLogCodec.pack(20_760L, hr, steps, flags)
        assertTrue("untouched day was ${packed.length} chars", packed.length < 200)
        blank()
        assertEquals(20_760L, DayLogCodec.unpack(packed, hr, steps, flags))
        for (i in 0 until DayBins.BIN_COUNT) assertEquals(0, flags[i].toInt())
    }

    /**
     * A malformed bin is an absent bin and the day survives — the incident log's rule, that for
     * a record the direction which hurts is loss.
     */
    @Test
    fun `a damaged bin is dropped and its neighbours are kept`() {
        blank()
        bin(10, 60, 100, DayBins.FLAG_SAMPLED)
        bin(11, 61, 101, DayBins.FLAG_SAMPLED)
        bin(12, 62, 102, DayBins.FLAG_SAMPLED)
        val entries = DayLogCodec.pack(20_761L, hr, steps, flags).split(',').toMutableList()
        entries[11] = "1:junk:101"

        blank()
        assertEquals(20_761L, DayLogCodec.unpack(entries.joinToString(","), hr, steps, flags))
        assertEquals(60, hr[10].toInt() and 0xFF)
        assertEquals(0, flags[11].toInt())          // the damaged one, absent rather than guessed
        assertEquals(62, hr[12].toInt() and 0xFF)
    }

    /**
     * A bad header or a wrong bin count drops the whole day — the relay cache's rule: once the
     * indices cannot be trusted, a partial answer is a confident lie about which hour was which.
     */
    @Test
    fun `an untrustworthy header or length refuses the day whole`() {
        blank()
        bin(5, 70, 50, DayBins.FLAG_SAMPLED)
        val good = DayLogCodec.pack(20_762L, hr, steps, flags)

        blank()
        assertEquals(DayLogCodec.NO_DAY, DayLogCodec.unpack(null, hr, steps, flags))
        assertEquals(DayLogCodec.NO_DAY, DayLogCodec.unpack("", hr, steps, flags))
        assertEquals(DayLogCodec.NO_DAY, DayLogCodec.unpack("nonsense", hr, steps, flags))
        // A version this build does not know cannot be read into these arrays.
        assertEquals(DayLogCodec.NO_DAY, DayLogCodec.unpack("9$" + "|20762|", hr, steps, flags))
        assertEquals(DayLogCodec.NO_DAY, DayLogCodec.unpack(good.replace("|20762|", "|x|"), hr, steps, flags))
        // Bins lost from the string shift every index after them: refuse, do not re-align.
        val short = good.split(',').dropLast(3).joinToString(",")
        assertEquals(DayLogCodec.NO_DAY, DayLogCodec.unpack(short, hr, steps, flags))
        // And a refused string leaves an empty day, not the previous one half-overwritten.
        for (i in 0 until DayBins.BIN_COUNT) assertEquals(0, flags[i].toInt())
    }
}
