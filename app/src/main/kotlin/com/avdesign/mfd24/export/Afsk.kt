// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import kotlin.math.PI
import kotlin.math.sin

/**
 * Bell 202 AFSK modulator: text out of the watch as four seconds of tones.
 *
 * The incident log has to leave a release build somehow, and every conventional road is blocked
 * on purpose: no companion app, no account, no network beyond one weather call, and
 * device-protected prefs that `run-as` cannot reach without a debuggable package. Sound is the
 * one channel every watch has and every phone can receive. Bell 202 rather than anything bespoke
 * because a bespoke protocol needs a bespoke decoder, and a 1979 standard is already decoded by
 * `minimodem --rx 1200` on anything with a microphone — the transmitter is all this file has to
 * be.
 *
 * Mark 1200 Hz, space 2200 Hz, 1200 baud, 8N1 — both tones sit mid-voiceband, which is the range
 * a coin-sized watch speaker actually reproduces. The sample rate is 48 kHz so a bit is exactly
 * [SAMPLES_PER_BIT] samples: bit edges land on sample boundaries and the test's demodulator can
 * window without drift. Phase is continuous across the frequency switches — a phase jump splatters
 * energy across the band and is the classic way a home-rolled FSK fails to decode.
 *
 * Pure arithmetic; [AfskTest] demodulates the output and reads the text back.
 */
object Afsk {

    const val SAMPLE_RATE: Int = 48_000
    const val BAUD: Int = 1_200
    const val SAMPLES_PER_BIT: Int = SAMPLE_RATE / BAUD

    const val MARK_HZ: Double = 1_200.0
    const val SPACE_HZ: Double = 2_200.0

    /**
     * Carrier ahead of the first start bit, in bits: a quarter second of idle mark. A serial line
     * at rest *is* mark, so the lead-in doubles as the receiver's chance to lock before anything
     * is at stake — and as the moment the wearer brings the microphone close.
     */
    const val PREAMBLE_BITS: Int = 300

    /** Idle tail, so the last stop bit is not also the last audible sample. */
    const val TRAILER_BITS: Int = 60

    /** Loud but not clipped: the speaker is small and the decode margin lives in clean edges. */
    private const val AMPLITUDE = 0.8 * Short.MAX_VALUE

    /** PCM for [text] as 8N1 frames, LSB first, preamble and trailer included. */
    fun modulate(text: String): ShortArray {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val bitCount = PREAMBLE_BITS + bytes.size * 10 + TRAILER_BITS
        val out = ShortArray(bitCount * SAMPLES_PER_BIT)

        var phase = 0.0
        var at = 0

        fun bit(mark: Boolean) {
            val step = 2.0 * PI * (if (mark) MARK_HZ else SPACE_HZ) / SAMPLE_RATE
            var i = 0
            while (i < SAMPLES_PER_BIT) {
                out[at++] = (AMPLITUDE * sin(phase)).toInt().toShort()
                phase += step
                i++
            }
            if (phase > 2.0 * PI) phase -= 2.0 * PI * (phase / (2.0 * PI)).toInt()
        }

        repeat(PREAMBLE_BITS) { bit(true) }
        for (b in bytes) {
            bit(false)                                   // start
            var v = b.toInt()
            repeat(8) {
                bit(v and 1 == 1)                        // data, LSB first
                v = v shr 1
            }
            bit(true)                                    // stop
        }
        repeat(TRAILER_BITS) { bit(true) }
        return out
    }
}
