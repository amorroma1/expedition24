// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The day exactly as it was recorded — every quarter-hour's flags, pulse and steps — packed small
 * enough to leave the watch through a camera or a speaker.
 *
 * The report and the graphs are the wearer's answer; this is the *engineer's*. When a night comes
 * back as an hour and a half and nobody can say why, the argument is about which bins carried
 * which flags, and no summary can settle it. So the raw grid leaves the watch unchanged: nothing
 * inferred, nothing rounded, no sleep run written in — the sleep model is re-run on the far side
 * against the same numbers, which is what makes a fix testable off the wrist.
 *
 * **Fixed layout, then deflate.** Each bin is four bytes: flags, pulse, steps big-endian. That is
 * 4 + 96 x 4 = 388 bytes a day before compression and about a fifth of that after, because the
 * grid is mostly repetition — long runs of identical flags, pulses that walk in ones, steps that
 * are zero all night. A bespoke delta-and-run encoding was written first and thrown away: it beat
 * deflate by a few dozen bytes a day and needed its own decoder on the far side, where deflate is
 * `zlib.decompress` in every language a receiver could be written in.
 *
 * The whole thing is Android-free so [RawDayCodecTest] can measure the sizes this KDoc claims
 * rather than restating them.
 */
object RawDayCodec {

    /** Bytes per bin: flags, pulse, then steps as a big-endian pair. */
    const val BIN_BYTES = 4

    /** Bytes per day before compression: the epoch day, then the grid. */
    const val DAY_BYTES = 4 + DayBins.BIN_COUNT * BIN_BYTES

    /** The line the receiver keys on; the version moves if the frame layout ever does. */
    const val HEADER = "MFD24 VITAL RAW 1"

    /** One day's grid, as it sits in storage. */
    class Day(
        val epochDay: Int,
        val hr: ByteArray,
        val steps: ShortArray,
        val flags: ByteArray,
    )

    /**
     * Packs [days] into the frame the packet carries: the fixed grid, deflated.
     *
     * Days are written in the order given and the epoch day travels with each one, so a receiver
     * never has to infer which day it is holding from the order it arrived in.
     */
    fun pack(days: List<Day>): ByteArray {
        val raw = ByteArray(days.size * DAY_BYTES)
        var p = 0
        for (day in days) {
            raw[p++] = (day.epochDay ushr 24).toByte()
            raw[p++] = (day.epochDay ushr 16).toByte()
            raw[p++] = (day.epochDay ushr 8).toByte()
            raw[p++] = day.epochDay.toByte()
            for (i in 0 until DayBins.BIN_COUNT) {
                raw[p++] = day.flags[i]
                raw[p++] = day.hr[i]
                val st = day.steps[i].toInt() and 0xFFFF
                raw[p++] = (st ushr 8).toByte()
                raw[p++] = st.toByte()
            }
        }
        return deflate(raw)
    }

    /** Reads back what [pack] wrote, or an empty list if the bytes are not a whole number of days. */
    fun unpack(packed: ByteArray): List<Day> {
        val raw = inflate(packed) ?: return emptyList()
        if (raw.isEmpty() || raw.size % DAY_BYTES != 0) return emptyList()
        val out = ArrayList<Day>(raw.size / DAY_BYTES)
        var p = 0
        while (p < raw.size) {
            val epochDay = ((raw[p].toInt() and 0xFF) shl 24) or
                ((raw[p + 1].toInt() and 0xFF) shl 16) or
                ((raw[p + 2].toInt() and 0xFF) shl 8) or
                (raw[p + 3].toInt() and 0xFF)
            p += 4
            val hr = ByteArray(DayBins.BIN_COUNT)
            val steps = ShortArray(DayBins.BIN_COUNT)
            val flags = ByteArray(DayBins.BIN_COUNT)
            for (i in 0 until DayBins.BIN_COUNT) {
                flags[i] = raw[p]
                hr[i] = raw[p + 1]
                steps[i] = (((raw[p + 2].toInt() and 0xFF) shl 8) or
                    (raw[p + 3].toInt() and 0xFF)).toShort()
                p += 4
            }
            out.add(Day(epochDay, hr, steps, flags))
        }
        return out
    }

    /**
     * The packet as text: three header lines and one line of Base64.
     *
     * Text because both channels this watch has are text channels — a QR code and 1200-baud AFSK
     * — and because a wearer who pastes the line into a chat window has still exported their day.
     * The header names the callsign and the days inside it, so a packet found on its own can be
     * placed without decoding it.
     */
    fun packet(callsign: String, shortId: String, days: List<Day>): String {
        val body = encodeBase64(pack(days))
        val sb = StringBuilder(body.length + 64)
        sb.append(HEADER).append('\n')
        sb.append(callsign).append(' ').append(shortId).append('\n')
        sb.append("DAYS")
        for (day in days) sb.append(' ').append(day.epochDay)
        sb.append('\n').append(body)
        return sb.toString()
    }

    /** The Base64 line out of a packet, or null if this is not one. */
    fun bodyOf(packet: String): String? {
        val lines = packet.split('\n')
        if (lines.size < 4 || lines[0] != HEADER) return null
        return lines[3].takeIf { it.isNotEmpty() }
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(raw)
            deflater.finish()
            // Worst case for deflate is the input plus its own overhead; the grid never
            // approaches it, but a buffer that can be overrun is a buffer that will be.
            val buf = ByteArray(raw.size + 64)
            val n = deflater.deflate(buf)
            return buf.copyOf(n)
        } finally {
            deflater.end()
        }
    }

    private fun inflate(packed: ByteArray): ByteArray? {
        if (packed.isEmpty()) return null
        val inflater = Inflater()
        try {
            inflater.setInput(packed)
            val buf = ByteArray(MAX_INFLATED)
            val n = runCatching { inflater.inflate(buf) }.getOrElse { return null }
            if (n <= 0 || !inflater.finished()) return null
            return buf.copyOf(n)
        } finally {
            inflater.end()
        }
    }

    /** Base64, written out rather than borrowed: `android.util.Base64` is not on the JVM path. */
    internal fun encodeBase64(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val v = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[v ushr 18]).append(ALPHABET[(v ushr 12) and 63])
            sb.append(ALPHABET[(v ushr 6) and 63]).append(ALPHABET[v and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val v = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[v ushr 18]).append(ALPHABET[(v ushr 12) and 63]).append("==")
            }
            2 -> {
                val v = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[v ushr 18]).append(ALPHABET[(v ushr 12) and 63])
                sb.append(ALPHABET[(v ushr 6) and 63]).append('=')
            }
        }
        return sb.toString()
    }

    internal fun decodeBase64(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in text) {
            if (c == '=' || c == '\n' || c == '\r') continue
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** Two weeks of grids inflated at once is 5.4 kB; the ceiling is far above anything sent. */
    private const val MAX_INFLATED = 64 * 1024

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}
