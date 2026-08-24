// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pinned against a JPL Horizons observer table captured 2026-08-23: Earth's azimuth/elevation
 * from a site at Jezero (COMMAND='399', CENTER='coord@499', COORD_TYPE='GEODETIC',
 * SITE_COORD='-77.4508,18.6320,0' — Horizons wants Mars east longitude negative, and the
 * planetographic latitude for our planetocentric 18.4447). The half-degree tolerance carries
 * the planetographic-vs-planetocentric vertical and the barycentre approximation; observed
 * agreement is ~0.15 degrees, under a minute of window edge.
 */
class EarthSkyTest {

    private val lat = Rovers.LAT[Rovers.PERSEVERANCE]
    private val lon = Rovers.LON_EAST[Rovers.PERSEVERANCE]

    @Test
    fun `earth altitude from jezero matches the captured horizons table`() {
        val golden = mapOf(
            "2026-08-23T00:00:00Z" to -39.131322,
            "2026-08-23T06:00:00Z" to -41.383649,
            "2026-08-23T10:00:00Z" to 12.205060,
            "2026-08-23T12:00:00Z" to 39.816349,
            "2026-08-23T15:00:00Z" to 76.210730,
            "2026-08-23T18:00:00Z" to 51.365416,
            "2026-08-23T21:00:00Z" to 10.141850,
        )
        for ((iso, elevation) in golden) {
            val t = Instant.parse(iso).toEpochMilli()
            assertEquals(iso, elevation, EarthSky.earthAltitudeDeg(t, lat, lon), 0.5)
        }
    }

    /**
     * The sun must cross the meridian when the clock says noon: at LTST 12 its altitude is the
     * sol's maximum, 90 minus the latitude-declination gap. One assertion checks the frame
     * rotation, the hour-angle anchor and the longitude sign together — a wrong sign anywhere
     * moves this by hours, not fractions.
     */
    @Test
    fun `sun peaks on the meridian at local true noon`() {
        // Find LTST noon nearest 2026-08-23 13:30Z (the captured table's transit).
        var t = Instant.parse("2026-08-23T12:00:00Z").toEpochMilli()
        while (Math.abs(MarsSolarTime.ltstHours(t, lon) - 12.0) > 1.0 / 60.0) t += 30_000L
        val noonAltitude = EarthSky.sunAltitudeDeg(t, lat, lon)
        // Either side of noon the sun must be lower.
        val hour = Math.round(MarsSolarTime.MARS_HOUR_MILLIS)
        assertTrue(noonAltitude > EarthSky.sunAltitudeDeg(t - 3 * hour, lat, lon))
        assertTrue(noonAltitude > EarthSky.sunAltitudeDeg(t + 3 * hour, lat, lon))
        // And the geometry must close: altitude = 90 - |lat - declination| at the meridian.
        // Solar declination from MarsSolarTime is the independent route to the same number.
        val expected = 90.0 - Math.abs(lat - MarsSolarTime.solarDeclinationDeg(t))
        assertEquals(expected, noonAltitude, 0.5)
    }

    /**
     * From the captured table Earth stands above 10 degrees from about 09:50 to about 21:01 UTC
     * on 2026-08-23. One window, found and refined to within the model's few real minutes.
     */
    @Test
    fun `one direct-to-earth window on the captured sol`() {
        val now = Instant.parse("2026-08-23T15:00:00Z").toEpochMilli()
        val out = DteWindows()
        EarthSky.computeWindows(now, lat, lon, EarthSky.MIN_ELEVATION_DEG, out)
        assertEquals(EarthSky.NORMAL, out.kind)
        assertEquals(1, out.count)
        val start = Instant.parse("2026-08-23T09:50:00Z").toEpochMilli()
        val end = Instant.parse("2026-08-23T21:01:00Z").toEpochMilli()
        assertEquals(start.toDouble(), out.startMillis[0].toDouble(), 300_000.0)
        assertEquals(end.toDouble(), out.endMillis[0].toDouble(), 300_000.0)
    }

    /** Horizons' own light-time for the same instant: 15.7576 minutes on 2026-08-23 12:00Z. */
    @Test
    fun `one way light time matches horizons`() {
        val t = Instant.parse("2026-08-23T12:00:00Z").toEpochMilli()
        assertEquals(15.75759 * 60.0, EarthSky.oneWayLightSeconds(t), 5.0)
    }

    /**
     * Solar conjunction: Earth passes behind the sun, seen from Mars, about thirteen months
     * after each opposition — early 2026 for the January 2025 one. The scan asserts the pass
     * actually happens in that window and that an ordinary date is nowhere near it, which pins
     * the angle's scale and sign without trusting a memorised date to the day.
     */
    @Test
    fun `earth passes behind the sun in the winter of 2025 to 2026`() {
        var t = Instant.parse("2025-11-01T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()
        var minimum = Double.MAX_VALUE
        while (t < end) {
            val angle = EarthSky.sunEarthAngleDeg(t)
            if (angle < minimum) minimum = angle
            t += 86_400_000L
        }
        assertTrue("conjunction minimum was $minimum degrees", minimum < 2.0)
        assertTrue(
            "2026-08-23 should be far from conjunction",
            EarthSky.sunEarthAngleDeg(
                Instant.parse("2026-08-23T12:00:00Z").toEpochMilli()
            ) > 20.0,
        )
    }

    /** A window under way at the scan boundary is split there, not lost. */
    @Test
    fun `a window crossing the scan boundary is split into two pieces`() {
        // Centre the scan mid-window: the boundary (half a sol away) lands inside the next
        // sol's window, so the pieces at the two ends must both appear.
        val now = Instant.parse("2026-08-23T15:00:00Z").toEpochMilli() +
            Math.round(AstroTime.SOL_IN_MILLIS / 2.0)
        val out = DteWindows()
        EarthSky.computeWindows(now, lat, lon, EarthSky.MIN_ELEVATION_DEG, out)
        assertEquals(EarthSky.NORMAL, out.kind)
        assertEquals(2, out.count)
        // The pieces abut the scan edges exactly.
        val halfSol = Math.round(AstroTime.SOL_IN_MILLIS / 2.0)
        assertEquals((now - halfSol).toDouble(), out.startMillis[0].toDouble(), 1.0)
        assertEquals((now + halfSol).toDouble(), out.endMillis[1].toDouble(), 1.0)
    }

    /**
     * On the captured date Earth's areocentric declination is about +5.5 degrees, so from high
     * northern latitudes it circles above a zero-degree horizon and from high southern ones it
     * never breaks it.
     */
    @Test
    fun `polar sites clamp to always up and always down`() {
        val now = Instant.parse("2026-08-23T15:00:00Z").toEpochMilli()
        val out = DteWindows()
        EarthSky.computeWindows(now, 89.9, lon, 0.0, out)
        assertEquals(EarthSky.ALWAYS_UP, out.kind)
        assertEquals(1, out.count)
        EarthSky.computeWindows(now, -89.9, lon, 0.0, out)
        assertEquals(EarthSky.ALWAYS_DOWN, out.kind)
        assertEquals(0, out.count)
    }
}
