// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The moon model against an independent ephemeris (PyEphem, refraction off, geocentric-grade),
 * at three instants chosen to cross the cases: moon low in a Kyiv evening, high fraction near
 * full, and below the horizon in the southern hemisphere. A degree and a half of slack is an
 * order of magnitude tighter than a wrist can be aimed and comfortably wider than the truncated
 * series' real error.
 */
class MoonSkyTest {

    private val state = MoonState()

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `kyiv evening moon`() {
        MoonSky.compute(at("2026-08-21T18:00:00Z"), 50.4, 30.45, state)
        assertEquals(9.70, state.altitudeDeg, 1.5)
        assertEquals(15.77, state.hourAngleDeg, 1.5)
        assertEquals(0.656, state.illuminatedFraction, 0.03)
    }

    @Test
    fun `near-full moon well past the meridian`() {
        MoonSky.compute(at("2026-03-01T03:00:00Z"), 50.4, 30.45, state)
        assertEquals(7.93, state.altitudeDeg, 1.5)
        assertEquals(101.01, state.hourAngleDeg, 1.5)
        assertEquals(0.935, state.illuminatedFraction, 0.03)
    }

    @Test
    fun `below the horizon in sydney, and the mark must know it`() {
        MoonSky.compute(at("2026-11-15T22:00:00Z"), -33.9, 151.2, state)
        assertTrue("altitude ${state.altitudeDeg}", state.altitudeDeg < -15.0)
        assertEquals(-133.42, state.hourAngleDeg, 1.5)
        assertEquals(0.353, state.illuminatedFraction, 0.03)
    }

    @Test
    fun `the dial position is the hour angle, exactly as the sun's`() {
        // 12 h plus the hour angle in hours: on the meridian the moon reads noon-up, the same
        // convention the solar mark uses, which is what makes the two marks one compass.
        MoonSky.compute(at("2026-08-21T18:00:00Z"), 50.4, 30.45, state)
        val dialHours = 12.0 + state.hourAngleDeg / 15.0
        assertEquals(13.05, dialHours, 0.15)
    }
}
