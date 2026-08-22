// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SolarTimeTest {

    private val day = SolarDay()

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun utcHours(millis: Long): Double =
        Math.floorMod(millis, 86_400_000L) / 3_600_000.0

    /** Length of the day in hours, or 24/0 inside the polar circles — the yardstick the model is
     * held to here; the dial itself never prints it, so it lives with the test. */
    private fun dayLengthHours(day: SolarDay): Double = when (day.kind) {
        SolarTime.POLAR_DAY -> 24.0
        SolarTime.POLAR_NIGHT -> 0.0
        else -> kotlin.math.abs(day.sunsetMillis - day.sunriseMillis) / 3_600_000.0
    }

    @Test
    fun `the apparent solar dial hour is the sun's real hour angle`() {
        // PyEphem, Kyiv, 2026-08-21 18:00 Z: the sun's hour angle is 119.68 deg = 7.98 h past
        // transit. The transit-midpoint route must land on the same number — this is the whole
        // difference between a compass and a highlight under the hour hand.
        val now = at("2026-08-21T18:00:00Z")
        SolarTime.compute(now, 50.4, 30.45, day)
        val dialHours = AstroTime.apparentSolarDialHours(now, day.sunriseMillis, day.sunsetMillis)
        assertEquals(12.0 + 119.68 / 15.0, dialHours, 0.05)
    }

    @Test
    fun `equator gets a twelve hour day all year`() {
        // Twelve hours plus a few minutes, because sunrise and sunset are reckoned to the upper
        // limb through refraction rather than to the sun's centre.
        for (iso in listOf(
            "2026-03-20T12:00:00Z",
            "2026-06-21T12:00:00Z",
            "2026-09-22T12:00:00Z",
            "2026-12-21T12:00:00Z",
        )) {
            SolarTime.compute(at(iso), 0.0, 0.0, day)
            assertEquals(SolarTime.NORMAL, day.kind)
            assertEquals("day length on $iso", 12.1, dayLengthHours(day), 0.2)
        }
    }

    @Test
    fun `sunrise on the equinox at the prime meridian is close to six UTC`() {
        SolarTime.compute(at("2026-03-20T12:00:00Z"), 0.0, 0.0, day)
        assertEquals(6.0, utcHours(day.sunriseMillis), 0.3)
        assertEquals(18.0, utcHours(day.sunsetMillis), 0.3)
    }

    @Test
    fun `fifteen degrees east brings sunrise an hour earlier`() {
        SolarTime.compute(at("2026-03-20T12:00:00Z"), 0.0, 0.0, day)
        val atGreenwich = utcHours(day.sunriseMillis)
        SolarTime.compute(at("2026-03-20T12:00:00Z"), 0.0, 15.0, day)
        val atFifteenEast = utcHours(day.sunriseMillis)
        assertEquals(1.0, atGreenwich - atFifteenEast, 0.05)
    }

    @Test
    fun `northern summer days are longer than northern winter days`() {
        SolarTime.compute(at("2026-06-21T12:00:00Z"), 55.75, 37.62, day)
        val summer = dayLengthHours(day)
        SolarTime.compute(at("2026-12-21T12:00:00Z"), 55.75, 37.62, day)
        val winter = dayLengthHours(day)

        // Moscow runs about 17h34m at the summer solstice and 7h00m at the winter one.
        assertEquals(17.6, summer, 0.3)
        assertEquals(7.0, winter, 0.3)
        assertTrue(summer > winter)
    }

    @Test
    fun `the southern hemisphere has the seasons the other way round`() {
        SolarTime.compute(at("2026-06-21T12:00:00Z"), -33.87, 151.21, day)
        val june = dayLengthHours(day)
        SolarTime.compute(at("2026-12-21T12:00:00Z"), -33.87, 151.21, day)
        val december = dayLengthHours(day)
        assertTrue("Sydney: June $june should be shorter than December $december", june < december)
    }

    @Test
    fun `inside the arctic circle the sun stays up in June and down in December`() {
        SolarTime.compute(at("2026-06-21T12:00:00Z"), 78.2, 15.6, day)
        assertEquals(SolarTime.POLAR_DAY, day.kind)
        assertEquals(24.0, dayLengthHours(day), 0.0)

        SolarTime.compute(at("2026-12-21T12:00:00Z"), 78.2, 15.6, day)
        assertEquals(SolarTime.POLAR_NIGHT, day.kind)
        assertEquals(0.0, dayLengthHours(day), 0.0)
    }

    /**
     * Either side of the antimeridian the local solar day and the UTC day are nearly a full day
     * apart, which is exactly where an algorithm that keys off midnight UTC picks the wrong day.
     */
    @Test
    fun `the date line does not shift the day the events belong to`() {
        val noon = at("2026-08-18T00:00:00Z")
        for (longitude in doubleArrayOf(-179.9, -175.0, 175.0, 179.9)) {
            SolarTime.compute(noon, -13.8, longitude, day)
            assertEquals(SolarTime.NORMAL, day.kind)
            assertTrue(
                "lon $longitude: sunrise before sunset",
                day.sunriseMillis < day.sunsetMillis,
            )
            assertTrue(
                "lon $longitude: events within a day of the instant asked about",
                Math.abs(day.sunriseMillis - noon) < 86_400_000L,
            )
            // Tropical latitude, so the day is close to twelve hours wherever we stand.
            assertEquals("lon $longitude", 11.7, dayLengthHours(day), 0.6)
        }
    }

    @Test
    fun `two sides of the date line at the same longitude agree`() {
        // Apia and Pago Pago are 180 km apart with a day between their calendars; the sun does not
        // care, so their sunrise instants must be within minutes of each other.
        val now = at("2026-08-18T06:00:00Z")
        SolarTime.compute(now, -13.83, -171.76, day)
        val apiaSunrise = day.sunriseMillis
        SolarTime.compute(now, -14.28, -170.70, day)
        val pagoSunrise = day.sunriseMillis
        assertTrue(
            "sunrises ${Math.abs(apiaSunrise - pagoSunrise) / 60000} min apart",
            Math.abs(apiaSunrise - pagoSunrise) < 15 * 60_000L,
        )
    }

    @Test
    fun `sunrise always precedes sunset on the same day`() {
        for (latitude in intArrayOf(-60, -30, 0, 30, 60)) {
            for (dayOfYear in intArrayOf(1, 80, 172, 266, 355)) {
                val millis = at("2026-01-01T12:00:00Z") + (dayOfYear - 1) * 86_400_000L
                SolarTime.compute(millis, latitude.toDouble(), 0.0, day)
                if (day.kind != SolarTime.NORMAL) continue
                assertTrue(
                    "lat $latitude day $dayOfYear",
                    day.sunriseMillis < day.sunsetMillis,
                )
                // Both events belong to the day we asked about, not a neighbouring one.
                assertTrue(Math.abs(day.sunriseMillis - millis) < 86_400_000L)
                assertTrue(Math.abs(day.sunsetMillis - millis) < 86_400_000L)
            }
        }
    }
}
