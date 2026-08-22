// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.text

/**
 * A fixed-capacity character buffer for the render loop.
 *
 * Every telemetry line is assembled into one of these and handed to
 * `Canvas.drawText(char[], int, int, float, float, Paint)`. Nothing here allocates: no `String`,
 * no `StringBuilder`, no boxing, no `String.format`. Digits are written out by hand.
 *
 * Not thread safe — each buffer belongs to exactly one renderer.
 */
class TextBuf(capacity: Int) {

    @JvmField
    val chars: CharArray = CharArray(capacity)

    @JvmField
    var length: Int = 0

    fun clear(): TextBuf {
        length = 0
        return this
    }

    fun ch(c: Char): TextBuf {
        if (length < chars.size) chars[length++] = c
        return this
    }

    fun space(): TextBuf = ch(' ')

    /** Appends every character of [src] — use with the `LIT_*` constants below. */
    fun lit(src: CharArray): TextBuf = lit(src, 0, src.size)

    fun lit(src: CharArray, from: Int, count: Int): TextBuf {
        var i = 0
        while (i < count && length < chars.size) {
            chars[length++] = src[from + i]
            i++
        }
        return this
    }

    /** Zero-padded two digits, e.g. `07`. Values outside 0..99 are clamped. */
    fun pad2(value: Int): TextBuf {
        val v = if (value < 0) 0 else if (value > 99) 99 else value
        ch(DIGITS[v / 10])
        ch(DIGITS[v % 10])
        return this
    }

    /** Unsigned decimal, no padding. */
    fun uint(value: Long): TextBuf {
        if (value <= 0L) return ch('0')
        var v = value
        var digits = 0
        while (v > 0L) {
            SCRATCH[digits++] = DIGITS[(v % 10L).toInt()]
            v /= 10L
        }
        while (digits > 0) ch(SCRATCH[--digits])
        return this
    }

    fun uint(value: Int): TextBuf = uint(value.toLong())

    /** Signed decimal with an explicit `-` for negatives. */
    fun int(value: Int): TextBuf {
        if (value < 0) {
            ch('-')
            return uint(-value.toLong())
        }
        return uint(value.toLong())
    }

    /**
     * Signed decimal with one fractional digit, from a value already scaled by ten.
     * `tenths = -37` renders as `-3.7`.
     */
    fun tenths(tenths: Int): TextBuf {
        var v = tenths
        if (v < 0) {
            ch('-')
            v = -v
        }
        uint((v / 10).toLong())
        ch('.')
        ch(DIGITS[v % 10])
        return this
    }

    companion object {
        private val DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

        /**
         * Digit reversal scratch. Shared across buffers, which is safe because rendering is
         * single-threaded and [uint] never yields mid-call.
         */
        private val SCRATCH = CharArray(20)

        /** The zone suffix on its own: `Z` is what a date-time group actually carries. */
        val LIT_ZULU: CharArray = "Z ".toCharArray()

        /** Three-letter months for the ICAO date-time group, indexed from zero. */
        val MONTHS: Array<CharArray> = arrayOf(
            "JAN".toCharArray(), "FEB".toCharArray(), "MAR".toCharArray(),
            "APR".toCharArray(), "MAY".toCharArray(), "JUN".toCharArray(),
            "JUL".toCharArray(), "AUG".toCharArray(), "SEP".toCharArray(),
            "OCT".toCharArray(), "NOV".toCharArray(), "DEC".toCharArray(),
        )
        val LIT_SOL: CharArray = "SOL ".toCharArray()
        val LIT_MTC: CharArray = "MTC ".toCharArray()
        val LIT_LTC: CharArray = "LTC ".toCharArray()
        val LIT_LUNAR_DAY: CharArray = "LUNAR DAY ".toCharArray()
        val LIT_QNH: CharArray = "Q".toCharArray()
        val LIT_KM: CharArray = "KM".toCharArray()

        /**
         * A fix but nothing inside the radius. There is no `NO FIX` counterpart: without a device
         * fix [TelemetryLayer][com.avdesign.mfd24.render.TelemetryLayer] draws no site row at
         * all, and the reference-frame symbol stands alone on an empty weather row.
         */
        val LIT_NO_SITE: CharArray = "NO SITE".toCharArray()

        /** Alternative label for the midnight hour mark. */
        val LIT_HOUR_24: CharArray = "24".toCharArray()

        /** Duty readout: `DUTY: 2:09 REM`, `DUTY IN 4:30`, or `OFF-DUTY`. */
        val LIT_DUTY: CharArray = "DUTY: ".toCharArray()
        val LIT_REM: CharArray = " REM".toCharArray()
        val LIT_DUTY_IN: CharArray = "DUTY IN ".toCharArray()
        val LIT_OFF_DUTY: CharArray = "OFF-DUTY".toCharArray()

        /** Vigilance monitor status, shown only when it is doing something. */
        val LIT_VIGIL_PROMPT: CharArray = "ACKNOWLEDGE".toCharArray()
        val LIT_VIGIL_ALARM: CharArray = "SOS ACTIVE".toCharArray()

        /** The escalation went unanswered. The one line somebody else is meant to read. */
        val LIT_MAN_DOWN: CharArray = "MAN DOWN".toCharArray()

        /**
         * Shown in place of [LIT_MAN_DOWN] for a moment after the first tap of the clearing pair,
         * because the two-tap gesture was otherwise documented only in the README. Kept to nine
         * characters: the status line sits at 0.470 r, where a longer phrase would reach the
         * numerals.
         */
        val LIT_TAP_AGAIN: CharArray = "TAP AGAIN".toCharArray()

        /**
         * The watch is not being worn, so nothing is being watched.
         *
         * Said out loud, unlike the charging suspension, because this one is not self-evident: a
         * loose strap reads as off-body while the watch is still on the wrist, and a monitor that
         * has quietly stopped must not look like one that is armed.
         */
        val LIT_OFF_WRIST: CharArray = "OFF WRIST".toCharArray()

        /** Trailing Z on a bare time, where [LIT_ZULU] is the leading one on the ZULU row. */
        val LIT_ZULU_SUFFIX: CharArray = "Z".toCharArray()

        /** Battery row: `BAT 84%`. */
        val LIT_BATTERY: CharArray = "BAT ".toCharArray()

        /**
         * Labels and the no-reading placeholder for the two sensor slots.
         *
         * Dashes rather than a zero or a blank: a blank slot looks like a slot that was never
         * switched on, and a zero looks like a reading. An optical pulse takes several seconds to
         * lock, so this is what the slot shows every time the screen comes on.
         */
        val LIT_SENSOR_NONE: CharArray = "--".toCharArray()

        /**
         * Station pressure leads with its name because it has no pictogram: `QFE` is an aviation
         * term with no picture, and a barometer drawn at this size would say less than three
         * letters do. Pulse and steps carry a heart and a walking figure instead, on the same line
         * and at the same size as their digits.
         */
        val LIT_SENSOR_QFE: CharArray = "QFE ".toCharArray()
        val LIT_THOUSANDS: CharArray = "K".toCharArray()

        val LIT_MMHG: CharArray = "mm".toCharArray()
        val LIT_DEG_C: CharArray = "°C".toCharArray()
        val LIT_DEG_F: CharArray = "°F".toCharArray()
    }
}
