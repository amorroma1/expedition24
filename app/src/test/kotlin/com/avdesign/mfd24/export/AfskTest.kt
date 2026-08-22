// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import com.avdesign.mfd24.data.IncidentRecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * The modulator is checked the only way a modulator can be: by demodulating it.
 *
 * The receiver here is a bit-windowed Goertzel detector — mark energy against space energy over
 * each 40-sample bit — which is a fair stand-in for what `minimodem` does with the real audio.
 * If this cannot read the signal from a mathematically clean array, no microphone will read it
 * from a watch speaker across a desk.
 */
class AfskTest {

    private fun goertzel(pcm: ShortArray, from: Int, freq: Double): Double {
        val k = 2.0 * cos(2.0 * PI * freq / Afsk.SAMPLE_RATE)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until from + Afsk.SAMPLES_PER_BIT) {
            val s0 = pcm[i] + k * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - k * s1 * s2
    }

    /** True when the bit window starting at [from] carries mark rather than space. */
    private fun isMark(pcm: ShortArray, from: Int): Boolean =
        goertzel(pcm, from, Afsk.MARK_HZ) > goertzel(pcm, from, Afsk.SPACE_HZ)

    private fun demodulate(pcm: ShortArray): String {
        val bits = pcm.size / Afsk.SAMPLES_PER_BIT
        var bit = 0
        // Hunt for the first start bit; everything before it is carrier.
        while (bit < bits && isMark(pcm, bit * Afsk.SAMPLES_PER_BIT)) bit++
        val out = StringBuilder()
        while (bit + 10 <= bits) {
            if (isMark(pcm, bit * Afsk.SAMPLES_PER_BIT)) {
                // Idle again: the trailer. Done.
                break
            }
            var value = 0
            for (i in 0 until 8) {
                if (isMark(pcm, (bit + 1 + i) * Afsk.SAMPLES_PER_BIT)) value = value or (1 shl i)
            }
            assertTrue("stop bit must be mark", isMark(pcm, (bit + 9) * Afsk.SAMPLES_PER_BIT))
            out.append(value.toChar())
            bit += 10
        }
        return out.toString()
    }

    @Test
    fun `the signal decodes back to its own text`() {
        val text = LogPacket.build(
            arrayOf(IncidentRecord(1_787_230_037_000L), IncidentRecord(1_787_240_000_000L)), "RAVEN-42", "10396EB9",
        )
        assertEquals(text, demodulate(Afsk.modulate(text)))
    }

    @Test
    fun `a bit is a whole number of samples`() {
        // 48 kHz over 1200 baud. Anything fractional and the bit edges walk off the sample grid,
        // which is a slow decode failure no single window shows.
        assertEquals(0, Afsk.SAMPLE_RATE % Afsk.BAUD)
    }

    @Test
    fun `phase is continuous across the frequency switch`() {
        // A phase jump at a mark-space boundary splatters energy across the band. Adjacent
        // samples across every bit edge must differ by no more than one tone step allows.
        val pcm = Afsk.modulate("U")
        val maxStep = 2.0 * PI * Afsk.SPACE_HZ / Afsk.SAMPLE_RATE * Short.MAX_VALUE
        for (edge in 1 until pcm.size / Afsk.SAMPLES_PER_BIT) {
            val i = edge * Afsk.SAMPLES_PER_BIT
            val jump = abs(pcm[i] - pcm[i - 1]).toDouble()
            assertTrue("sample jump $jump at bit edge $edge", jump <= maxStep * 1.1)
        }
    }
}
