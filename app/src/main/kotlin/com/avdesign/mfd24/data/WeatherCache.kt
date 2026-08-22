// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import com.avdesign.mfd24.geo.Geo

/**
 * When a cached weather observation stops being worth reusing.
 *
 * It used to be a bare age check, which is only half the question: an observation is a reading of
 * one place at one time, and the watch can leave that place a great deal faster than the reading
 * goes stale. Setting a position on the far side of the world kept the old temperature and QNH on
 * the dial for up to half an hour — the site row and the daylight band moved, and the weather sat
 * there reading the country the wearer had left.
 *
 * Android-free so the rule can be unit-tested rather than reasoned about.
 */
object WeatherCache {

    /** How stale an observation may be before Open-Meteo is called again. */
    const val TTL_MILLIS: Long = 30 * 60 * 1000L

    /**
     * How far the wearer may move before the observation is refetched regardless of its age.
     *
     * Open-Meteo's grid is around 11 km, so anything inside that is the same forecast cell and a
     * new call would return the same numbers. Twenty-five kilometres is a couple of cells: far
     * enough not to poll the service while somebody drives across a city, close enough that a
     * flight, a long drive or a hand-typed jump always refetches.
     */
    const val DISTANCE_METERS: Double = 25_000.0

    /**
     * @param observedAt when the cached observation was taken, or 0 if there is none
     * @param observedLatitude where it was taken; NaN if unrecorded, which counts as stale
     * @param nowMillis the current time
     * @param latitude where the wearer is now
     */
    fun isStale(
        observedAt: Long,
        observedLatitude: Double,
        observedLongitude: Double,
        nowMillis: Long,
        latitude: Double,
        longitude: Double,
    ): Boolean {
        if (observedAt <= 0L) return true
        // A clock that has gone backwards is not evidence of freshness.
        if (nowMillis - observedAt !in 0 until TTL_MILLIS) return true
        if (observedLatitude.isNaN() || observedLongitude.isNaN()) return true
        val moved = Geo.haversineMeters(observedLatitude, observedLongitude, latitude, longitude)
        return moved > DISTANCE_METERS
    }
}
