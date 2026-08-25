// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Where the wellness face's three rings sit, held against the shipped constants.
 *
 * They were placed into the one clear band this dial has — between the hour hand's own tip and
 * the readout, which on that face is drawn close to the hub precisely to leave the band free.
 * A casual nudge to any of the five numbers involved would put a ring through the numerals or
 * through a line of type on a wrist rather than in a diff, which is the failure the readout's
 * own budget test exists to prevent.
 */
class VitalTrackTest {

    private val half = Geometry.VITAL_RING_WIDTH / 2f

    @Test
    fun `the pulse ring runs under the hand's point and clear of the numerals`() {
        val outerEdge = Geometry.PULSE_RING_RADIUS + half
        assertTrue(
            "the pulse ring reaches past the hour hand's tip",
            outerEdge <= Geometry.HOUR_HAND_TIP,
        )
        assertTrue(
            "the pulse ring reaches the hour numerals",
            outerEdge < Geometry.NUMERAL_INNER_EDGE,
        )
    }

    @Test
    fun `the three rings clear each other`() {
        val pulseInner = Geometry.PULSE_RING_RADIUS - half
        val activityOuter = Geometry.ACTIVITY_RING_RADIUS + half
        val activityInner = Geometry.ACTIVITY_RING_RADIUS - half
        val sleepOuter = Geometry.SLEEP_RING_RADIUS + half
        assertTrue("pulse and activity touch", pulseInner > activityOuter + 0.008f)
        assertTrue("activity and sleep touch", activityInner > sleepOuter + 0.008f)
    }

    @Test
    fun `the innermost ring clears the readout the wellness face draws`() {
        // That face's row above the hub is the score alone — `SCORE 100`, nine characters at its
        // widest — on the baseline the duty row keeps elsewhere. Anything longer would have to be
        // drawn through the sleep ring, which is why last night's hours live on the ring and in
        // the report rather than up here beside it.
        val rowHalf = 9 * Geometry.MONO_ADVANCE * Geometry.TEXT_SIZE / 2f
        val rowCorner = sqrt(
            (rowHalf * rowHalf +
                Geometry.VITAL_LINE_BASELINES[0] * Geometry.VITAL_LINE_BASELINES[0]).toDouble()
        ).toFloat()

        // And its widest slot line, `QFE 1013`, reaching outward from the hub.
        val slotHalf = 8 * Geometry.MONO_ADVANCE * Geometry.SENSOR_TEXT_SIZE / 2f
        val slotOuter = Geometry.VITAL_SENSOR_OFFSET_X + slotHalf
        val slotCorner = sqrt(
            (slotOuter * slotOuter +
                Geometry.SENSOR_BASELINE * Geometry.SENSOR_BASELINE).toDouble()
        ).toFloat()

        val sleepInner = Geometry.SLEEP_RING_RADIUS - half
        assertTrue(
            "the sleep ring reaches the readout: ring ${"%.3f".format(sleepInner)} r " +
                "against a corner at ${"%.3f".format(rowCorner)} r",
            sleepInner > rowCorner + 0.008f,
        )
        assertTrue(
            "the sleep ring reaches a sensor slot: ring ${"%.3f".format(sleepInner)} r " +
                "against a corner at ${"%.3f".format(slotCorner)} r",
            sleepInner > slotCorner + 0.008f,
        )
    }

    @Test
    fun `the battery clears the innermost ring too`() {
        val batteryHalf = 8 * Geometry.MONO_ADVANCE * Geometry.BATTERY_TEXT_SIZE / 2f
        val batteryCorner = sqrt(
            (batteryHalf * batteryHalf +
                Geometry.VITAL_BATTERY_BASELINE * Geometry.VITAL_BATTERY_BASELINE).toDouble()
        ).toFloat()
        val sleepInner = Geometry.SLEEP_RING_RADIUS - half
        assertTrue("the battery row reaches the sleep ring", sleepInner > batteryCorner + 0.008f)
    }
}
