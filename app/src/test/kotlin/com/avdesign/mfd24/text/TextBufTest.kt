// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TextBufTest {

    private fun TextBuf.render(): String = String(chars, 0, length)

    @Test
    fun `pad2 zero pads and clamps`() {
        assertEquals("00", TextBuf(8).clear().pad2(0).render())
        assertEquals("07", TextBuf(8).clear().pad2(7).render())
        assertEquals("42", TextBuf(8).clear().pad2(42).render())
        assertEquals("99", TextBuf(8).clear().pad2(1234).render())
        assertEquals("00", TextBuf(8).clear().pad2(-5).render())
    }

    @Test
    fun `uint matches the platform conversion`() {
        val values = longArrayOf(0, 1, 9, 10, 99, 100, 54_321, 1_234_567_890, Long.MAX_VALUE)
        for (v in values) {
            assertEquals(v.toString(), TextBuf(32).clear().uint(v).render())
        }
    }

    @Test
    fun `int keeps the sign`() {
        for (v in intArrayOf(0, 4, -4, 37, -37, 1013, -273)) {
            assertEquals(v.toString(), TextBuf(16).clear().int(v).render())
        }
    }

    @Test
    fun `tenths renders one decimal place`() {
        assertEquals("4.2", TextBuf(16).clear().tenths(42).render())
        assertEquals("-3.7", TextBuf(16).clear().tenths(-37).render())
        assertEquals("0.0", TextBuf(16).clear().tenths(0).render())
        assertEquals("0.5", TextBuf(16).clear().tenths(5).render())
        assertEquals("-0.5", TextBuf(16).clear().tenths(-5).render())
        assertEquals("123.4", TextBuf(16).clear().tenths(1234).render())
    }

    @Test
    fun `a full telemetry line assembles correctly`() {
        val buf = TextBuf(32)
        buf.clear()
            .lit(TextBuf.LIT_ZULU).pad2(18).lit(TextBuf.MONTHS[7]).space()
            .pad2(18).ch(':').pad2(42).ch(':').pad2(15)
        assertEquals("Z 18AUG 18:42:15", buf.render())

        buf.clear().lit(TextBuf.LIT_SOL).uint(54_321L)
        assertEquals("SOL 54321", buf.render())

        buf.clear().lit(TextBuf.LIT_LUNAR_DAY).uint(20_834L)
        assertEquals("LUNAR DAY 20834", buf.render())

        buf.clear().int(-4).lit(TextBuf.LIT_DEG_C).space().lit("OVC".toCharArray())
            .space().lit(TextBuf.LIT_QNH).uint(1013L)
        assertEquals("-4°C OVC Q1013", buf.render())
    }

    @Test
    fun `writing past the capacity truncates instead of overflowing`() {
        val buf = TextBuf(4)
        buf.clear().lit("ABCDEFGH".toCharArray())
        assertEquals("ABCD", buf.render())
        assertEquals(4, buf.length)
    }

    @Test
    fun `clear resets without reallocating`() {
        val buf = TextBuf(16)
        val backing = buf.chars
        buf.clear().uint(12345L)
        buf.clear().uint(7L)
        assertEquals("7", buf.render())
        assertSame(backing, buf.chars)
    }
}
