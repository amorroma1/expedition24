// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun `haversine matches known distances`() {
        // Moscow Kremlin to Sheremetyevo, ~27.6 km.
        assertEquals(
            27_600.0,
            Geo.haversineMeters(55.7520, 37.6175, 55.9726, 37.4146),
            200.0,
        )
        // London to Paris, ~343 km.
        assertEquals(
            343_000.0,
            Geo.haversineMeters(51.5074, -0.1278, 48.8566, 2.3522),
            3_000.0,
        )
        // Antipodal-ish: half the circumference.
        assertEquals(
            Math.PI * Geo.EARTH_RADIUS_M,
            Geo.haversineMeters(0.0, 0.0, 0.0, 180.0),
            1.0,
        )
    }

    @Test
    fun `distance to self is zero`() {
        assertEquals(0.0, Geo.haversineMeters(45.0, 63.0, 45.0, 63.0), 1e-9)
    }

    @Test
    fun `the search box always covers the radius`() {
        // The box only pre-filters, so it must never be tighter than the circle it stands in for.
        for (lat in intArrayOf(0, 30, 45, 60, 75, 85)) {
            val latitude = lat.toDouble()
            val radius = 5_000.0

            val dLat = Geo.latDeltaDegrees(radius)
            assertTrue(
                "latitude edge at $lat",
                Geo.haversineMeters(latitude, 0.0, latitude + dLat, 0.0) >= radius,
            )

            val dLon = Geo.lonDeltaDegrees(latitude, radius)
            assertTrue(
                "longitude edge at $lat",
                Geo.haversineMeters(latitude, 0.0, latitude, dLon) >= radius,
            )
        }
    }

    @Test
    fun `longitude delta degenerates gracefully at the pole`() {
        assertEquals(180.0, Geo.lonDeltaDegrees(90.0, 5_000.0), 1e-9)
        assertEquals(180.0, Geo.lonDeltaDegrees(-90.0, 5_000.0), 1e-9)
    }
}
