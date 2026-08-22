// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import com.avdesign.mfd24.astro.AstroTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a booked watch's arc is allowed on the dial.
 *
 * Two defects are pinned here, and neither can be seen by looking. The first: a watch booked for
 * the day after tomorrow drew an arc within a degree or two of the same watch booked for tonight,
 * and nothing said which — it looked right, which is worse than looking wrong. The second is the
 * one a fixed lead-in did not fix, and the reason the rule involves the shift's own length: for a
 * long watch the hour hand ended up standing *inside* the booked arc for hours at a time, which is
 * exactly what a watch under way looks like.
 *
 * The guarantee is therefore stated as a property and measured rather than asserted about the
 * constant: `the hour hand is never inside a booked arc`.
 */
class PendingArcTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val dayMillis = day.toDouble()

    private fun booked(inMillis: Long, lengthHours: Int): WatchShiftState = WatchShiftState().apply {
        hasShift = true
        startMillis = NOW + inMillis
        endMillis = startMillis + lengthHours * hour
    }

    // --- the day-after-tomorrow defect -----------------------------------------------------

    @Test
    fun `a watch the day after tomorrow gets no arc`() {
        assertFalse(booked(2 * day, 8).pendingArcVisible(NOW, dayMillis))
    }

    @Test
    fun `a watch later today gets its arc`() {
        assertTrue(booked(4 * hour, 8).pendingArcVisible(NOW, dayMillis))
    }

    @Test
    fun `a running or served watch is never refused`() {
        assertTrue(booked(-2 * hour, 8).pendingArcVisible(NOW, dayMillis))
    }

    // --- the lead-in shortens as the watch lengthens ----------------------------------------

    @Test
    fun `the lead-in is a turn less the length, less an hour of clearance`() {
        // Nineteen hours ahead for a four-hour watch, seven for a sixteen. A long arc covers more
        // of the dial, so there is less of the dial left for the hand to stand outside it.
        for ((lengthHours, leadInHours) in mapOf(4 to 19, 8 to 15, 12 to 11, 16 to 7)) {
            val shift = booked(30 * hour, lengthHours)
            val appears = shift.startMillis - leadInHours * hour
            assertTrue(
                "a ${lengthHours}h watch should show its arc ${leadInHours}h out",
                shift.pendingArcVisible(appears, dayMillis),
            )
            assertFalse(
                "and not a minute earlier",
                shift.pendingArcVisible(appears - 60_000L, dayMillis),
            )
        }
    }

    // --- the property the whole rule exists for ----------------------------------------------

    @Test
    fun `the hour hand is never inside a booked arc`() {
        for (lengthHours in intArrayOf(4, 8, 12, 16)) {
            val shift = booked(3 * day, lengthHours)
            val sweep = lengthHours * 15f
            var checked = 0
            // Every ten minutes from three days out down to the moment it begins.
            var now = shift.startMillis - 3 * day
            while (now < shift.startMillis) {
                if (shift.pendingArcVisible(now, dayMillis)) {
                    val offset = handOffsetIntoArc(now, shift)
                    assertTrue(
                        "a ${lengthHours}h watch showed its arc with the hand ${offset}° into a " +
                            "${sweep}° span, ${(shift.startMillis - now) / 60_000}min before it began",
                        offset > sweep,
                    )
                    checked++
                }
                now += 10 * 60_000L
            }
            assertTrue("the arc has to be shown at some point", checked > 0)
        }
    }

    @Test
    fun `the arc appears one hour of dial past its own end`() {
        val shift = booked(3 * day, 16)
        val appears = shift.startMillis - 7 * hour
        assertTrue(shift.pendingArcVisible(appears, dayMillis))
        // 16 h is 240° of span; an hour of clearance puts the hand at 255°.
        assertEquals(255f, handOffsetIntoArc(appears, shift), 0.2f)
    }

    // --- and it is turns of the dial, not hours ----------------------------------------------

    @Test
    fun `a sol is a turn too`() {
        val sol = AstroTime.SOL_IN_MILLIS
        val shift = booked(3 * day, 8)
        // On Mars the dial is longer, so the same eight-hour watch earns a longer lead-in.
        val leadIn = sol - 8 * hour - sol / 24.0
        assertTrue(shift.pendingArcVisible(shift.startMillis - leadIn.toLong() + 60_000L, sol))
        assertFalse(shift.pendingArcVisible(shift.startMillis - leadIn.toLong() - 60_000L, sol))
    }

    /**
     * How far clockwise the hour hand stands from the arc's start, in degrees. Anything from zero
     * to the arc's sweep is *inside* it.
     */
    private fun handOffsetIntoArc(nowMillis: Long, shift: WatchShiftState): Float {
        val hand = AstroTime.hourHandAngle(AstroTime.localHoursOfDay(nowMillis, 0L), false)
        val start = AstroTime.hourHandAngle(AstroTime.localHoursOfDay(shift.startMillis, 0L), false)
        return ((hand - start) % 360f + 360f) % 360f
    }

    private companion object {
        /** 2026-08-19T00:00:00Z. */
        const val NOW = 1_787_097_600_000L
    }
}
