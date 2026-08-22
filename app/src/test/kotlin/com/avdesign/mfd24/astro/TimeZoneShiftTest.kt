// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import com.avdesign.mfd24.data.WatchShiftState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * A watch crossing time zones — a pilot six hours into an eight-hour duty when the aircraft lands
 * two zones east.
 *
 * The shift is stored as two absolute instants, so nothing about it depends on the offset; only the
 * mapping onto the dial does. These tests pin both halves of that: the interval must not move, and
 * the angles must all move together.
 */
class TimeZoneShiftTest {

    private val hour = 3_600_000L
    private val start = Instant.parse("2026-08-18T08:00:00Z").toEpochMilli()
    private val duration = 8 * hour
    private val end = start + duration
    private val now = start + 6 * hour

    private fun handAngle(instant: Long, offsetMillis: Long): Float =
        AstroTime.hourHandAngle(AstroTime.localHoursOfDay(instant, offsetMillis))

    @Test
    fun `time remaining is the same in any zone`() {
        val state = WatchShiftState().apply {
            hasShift = true
            startMillis = start
            endMillis = end
        }
        // The offset never enters the calculation, so this is the property in its strongest form.
        assertEquals(2 * hour, state.remainingMillis(now))
        assertEquals(WatchShiftState.DUTY_ACTIVE, state.dutyState(now))
    }

    @Test
    fun `crossing two zones east slides hand and arc by the same thirty degrees`() {
        val west = 0L
        val east = 2 * hour

        val handWest = handAngle(now, west)
        val handEast = handAngle(now, east)
        val arcStartWest = handAngle(start, west)
        val arcStartEast = handAngle(start, east)
        val arcEndWest = handAngle(end, west)
        val arcEndEast = handAngle(end, east)

        // Two hours of a 24 hour dial is thirty degrees.
        assertEquals(30f, wrap(handEast - handWest), 1e-3f)
        assertEquals(30f, wrap(arcStartEast - arcStartWest), 1e-3f)
        assertEquals(30f, wrap(arcEndEast - arcEndWest), 1e-3f)
    }

    @Test
    fun `the gap between the hand and the end of the arc survives the crossing`() {
        val remainingDegrees = 2.0 / 24.0 * 360.0
        for (offset in longArrayOf(0L, 2 * hour, -5 * hour, 5 * hour + 1_800_000L)) {
            assertEquals(
                "offset $offset",
                remainingDegrees.toFloat(),
                wrap(handAngle(end, offset) - handAngle(now, offset)),
                1e-3f,
            )
        }
    }

    @Test
    fun `a half hour zone moves the hand by a half hour, not a whole one`() {
        val delhi = 5 * hour + 1_800_000L
        assertEquals(
            (5.5 / 24.0 * 360.0).toFloat(),
            wrap(handAngle(now, delhi) - handAngle(now, 0L)),
            1e-3f,
        )
    }

    @Test
    fun `the daylight band slides with everything else`() {
        val sunrise = Instant.parse("2026-08-18T03:00:00Z").toEpochMilli()
        val sunset = Instant.parse("2026-08-18T18:00:00Z").toEpochMilli()
        val east = 2 * hour

        assertEquals(30f, wrap(handAngle(sunrise, east) - handAngle(sunrise, 0L)), 1e-3f)
        // The band's own width is a duration, so it is untouched by the offset.
        assertEquals(
            wrap(handAngle(sunset, 0L) - handAngle(sunrise, 0L)),
            wrap(handAngle(sunset, east) - handAngle(sunrise, east)),
            1e-3f,
        )
    }

    /** Normalises a difference of dial angles into `[0, 360)`. */
    private fun wrap(degrees: Float): Float {
        var d = degrees % 360f
        if (d < 0f) d += 360f
        return d
    }
}
