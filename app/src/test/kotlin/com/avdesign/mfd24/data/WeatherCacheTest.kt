// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache rule used to be a bare age check, which let the dial show the weather of a country the
 * wearer had left. These pin both halves of the replacement.
 */
class WeatherCacheTest {

    private val kyivLat = 50.45
    private val kyivLon = 30.52
    private val now = 1_755_000_000_000L

    private fun stale(
        observedAt: Long = now - 60_000L,
        obsLat: Double = kyivLat,
        obsLon: Double = kyivLon,
        lat: Double = kyivLat,
        lon: Double = kyivLon,
    ) = WeatherCache.isStale(observedAt, obsLat, obsLon, now, lat, lon)

    @Test
    fun `a recent observation of this place is reused`() {
        assertFalse(stale())
    }

    @Test
    fun `an observation older than the ttl is refetched`() {
        assertTrue(stale(observedAt = now - WeatherCache.TTL_MILLIS - 1))
        assertFalse(stale(observedAt = now - WeatherCache.TTL_MILLIS + 1))
    }

    @Test
    fun `having no observation at all is stale`() {
        assertTrue(stale(observedAt = 0L))
        assertTrue(stale(obsLat = Double.NaN, obsLon = Double.NaN))
    }

    @Test
    fun `moving across the world refetches however fresh the reading is`() {
        // Cape Canaveral, one minute after an observation taken in Kyiv.
        assertTrue(stale(lat = 28.56, lon = -80.58))
    }

    @Test
    fun `moving within a forecast cell does not refetch`() {
        // Roughly 5 km north: the same Open-Meteo cell, so the numbers would not change.
        assertFalse(stale(lat = kyivLat + 0.045))
    }

    @Test
    fun `the threshold is a distance not a coordinate delta`() {
        // A degree of longitude is a very different distance at the equator and near the pole;
        // the rule has to be in metres or it refetches far too eagerly up north.
        assertFalse(WeatherCache.isStale(now - 1000L, 78.0, 15.0, now, 78.0, 16.0))
        assertTrue(WeatherCache.isStale(now - 1000L, 0.0, 15.0, now, 0.0, 16.0))
    }

    @Test
    fun `a clock that jumped backwards is not treated as fresh`() {
        assertTrue(stale(observedAt = now + 60_000L))
    }
}
