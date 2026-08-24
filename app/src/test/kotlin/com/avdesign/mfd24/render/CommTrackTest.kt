// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import com.avdesign.mfd24.style.StyleSchema
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The comm lines' collision arithmetic, held against the shipped constants: the two tracks were
 * placed by hand into the only clear radial ground the dial has, and a casual nudge to any of
 * the five numbers involved would put a line through the ticks, the duty arc or the minute ring
 * on a wrist rather than in a diff.
 */
class CommTrackTest {

    @Test
    fun `the inner line kisses the hour ticks and clears the duty arc`() {
        val outerEdge = Geometry.COMM_INNER_RADIUS + Geometry.COMM_STROKE / 2f
        assertTrue(
            "inner line crosses into the hour ticks",
            outerEdge <= Geometry.TICK_HOUR_INNER + 1e-4f,
        )
        val innerEdge = Geometry.COMM_INNER_RADIUS - Geometry.COMM_STROKE / 2f
        val dutyOuterEdge = Geometry.DUTY_ARC_RADIUS + StyleSchema.DUTY_ARC_WIDTH_FRACTION / 2f
        assertTrue(
            "inner line touches the duty arc",
            innerEdge > dutyOuterEdge,
        )
    }

    /**
     * The Mars duty ring moved off the tick belt — three data rings and an arc in one belt read
     * as clutter — into the gap between the hour hand's tip and the numerals. Both of its new
     * neighbours are pinned: the numerals above, the readout's widest corner below.
     */
    @Test
    fun `the mars duty ring sits between the readout and the numerals`() {
        val half = StyleSchema.DUTY_ARC_WIDTH_FRACTION / 2f
        val outerEdge = Geometry.DUTY_ARC_RADIUS_MARS + half
        val innerEdge = Geometry.DUTY_ARC_RADIUS_MARS - half
        assertTrue("mars duty band reaches the numerals", outerEdge < Geometry.NUMERAL_INNER_EDGE)
        // The readout's widest reach is the third row: sixteen characters behind the
        // reference-frame symbol — the same worst case the width budget is drawn against.
        val rowHalf = (Geometry.SYMBOL_SIZE + Geometry.GLYPH_GAP +
            16 * Geometry.MONO_ADVANCE * Geometry.TEXT_SIZE) / 2f
        val worstCorner = sqrt(
            (rowHalf * rowHalf +
                Geometry.LINE_BASELINES[2] * Geometry.LINE_BASELINES[2]).toDouble()
        ).toFloat()
        assertTrue("mars duty band reaches the readout", innerEdge > worstCorner + 0.01f)
    }

    @Test
    fun `the outer line stays between the cardinal tips and the minute ring`() {
        val innerEdge = Geometry.COMM_OUTER_RADIUS - Geometry.COMM_STROKE / 2f
        val outerEdge = Geometry.COMM_OUTER_RADIUS + Geometry.COMM_STROKE / 2f
        assertTrue("outer line touches the cardinal ticks", innerEdge >= Geometry.TICK_CARDINAL_OUTER)
        assertTrue("outer line touches the minute ring", outerEdge <= Geometry.TICK_MINUTE_INNER)
    }
}
