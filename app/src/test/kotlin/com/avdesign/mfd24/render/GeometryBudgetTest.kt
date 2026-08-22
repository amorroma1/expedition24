// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The readout width budget, as arithmetic instead of prose.
 *
 * [Geometry] lays every row out on one figure — a 0.60 em monospace advance — and the rule that a
 * horizontal line at offset y with half-width w reaches sqrt(w² + y²), which must stay inside the
 * hour numerals' inner edge. That arithmetic used to live only in comments, which is exactly where
 * a lengthened row or a nudged baseline goes unnoticed until it touches a numeral on a wrist.
 * This test re-derives every row's worst corner from the shipped constants, so changing either
 * side of the bargain fails the build instead.
 */
class GeometryBudgetTest {

    /** Corner radius of a centred row of [chars] characters at [size], baseline offset [y]. */
    private fun corner(chars: Int, size: Float, y: Float): Float {
        val half = chars * Geometry.MONO_ADVANCE * size / 2f
        return sqrt((half * half + y * y).toDouble()).toFloat()
    }

    private fun assertInside(name: String, reach: Float) {
        assertTrue(
            "$name reaches ${"%.3f".format(reach)} r, past the numerals' " +
                "${Geometry.NUMERAL_INNER_EDGE} r",
            reach < Geometry.NUMERAL_INNER_EDGE,
        )
    }

    @Test
    fun `every readout row clears the hour numerals at its worst width`() {
        // `DUTY: 03:42 REM` — 15 characters; the incident form `21:14Z +01:09` is shorter.
        assertInside(
            "duty row",
            corner(15, Geometry.TEXT_SIZE, Geometry.LINE_BASELINES[0]),
        )
        // `Z 18AUG 18:42:15` — 16 characters.
        assertInside(
            "zulu row",
            corner(16, Geometry.TEXT_SIZE, Geometry.LINE_BASELINES[1]),
        )
        // The weather row carries the reference-frame symbol ahead of its 16 characters, which is
        // what makes it the widest thing on the dial.
        val weatherHalf = (Geometry.SYMBOL_SIZE + Geometry.GLYPH_GAP +
            16 * Geometry.MONO_ADVANCE * Geometry.TEXT_SIZE) / 2f
        assertInside(
            "weather row",
            sqrt(
                (weatherHalf * weatherHalf +
                    Geometry.LINE_BASELINES[2] * Geometry.LINE_BASELINES[2]).toDouble()
            ).toFloat(),
        )
        // Site row: a text-size pictogram, the gap, then `XXXXXX 99.9KM` — 13 characters.
        val siteHalf = (Geometry.TEXT_SIZE + Geometry.GLYPH_GAP +
            13 * Geometry.MONO_ADVANCE * Geometry.TEXT_SIZE) / 2f
        assertInside(
            "site row",
            sqrt(
                (siteHalf * siteHalf +
                    Geometry.LINE_BASELINES[3] * Geometry.LINE_BASELINES[3]).toDouble()
            ).toFloat(),
        )
    }

    @Test
    fun `the outlying rows hold their own bargains`() {
        // `BAT 100%` — eight characters at full charge, one more than the 84% the comment quotes.
        assertInside(
            "battery row",
            corner(8, Geometry.BATTERY_TEXT_SIZE, Geometry.BATTERY_BASELINE),
        )
        // `ACKNOWLEDGE` is the longest status word; `TAP AGAIN` and `MAN DOWN` are shorter.
        assertInside(
            "status line",
            corner(11, Geometry.STATUS_TEXT_SIZE, Geometry.STATUS_BASELINE),
        )
        // A sensor slot's outer edge: `QFE 1013` is the widest line, centred on the slot offset.
        val slotHalf = 8 * Geometry.MONO_ADVANCE * Geometry.SENSOR_TEXT_SIZE / 2f
        val outer = Geometry.SENSOR_OFFSET_X + slotHalf
        assertInside(
            "sensor slot",
            sqrt(
                (outer * outer + Geometry.SENSOR_BASELINE * Geometry.SENSOR_BASELINE).toDouble()
            ).toFloat(),
        )
    }
}
