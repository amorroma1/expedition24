// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical-earth distance helpers, in metres. */
object Geo {

    const val EARTH_RADIUS_M: Double = 6_371_008.8

    /** Metres per degree of great-circle arc on the sphere the distances are measured on. */
    const val METERS_PER_DEGREE: Double = Math.PI * EARTH_RADIUS_M / 180.0

    /**
     * Deliberate slack on the search box. The box only pre-filters candidates — the exact
     * haversine test decides — so erring outwards costs a handful of extra comparisons, whereas
     * erring inwards silently drops a site that is genuinely in range.
     */
    const val BOX_MARGIN: Double = 1.002

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val a = sinLat * sinLat + cos(rLat1) * cos(rLat2) * sinLon * sinLon
        return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Half-width in degrees of longitude that covers [meters] at [latitude]. Clamped to 180 so a
     * search near a pole degenerates to "the whole parallel" instead of overflowing.
     */
    fun lonDeltaDegrees(latitude: Double, meters: Double): Double {
        val scale = cos(Math.toRadians(latitude))
        if (scale <= 1e-6) return 180.0
        val delta = meters * BOX_MARGIN / (METERS_PER_DEGREE * scale)
        return if (delta > 180.0) 180.0 else delta
    }

    fun latDeltaDegrees(meters: Double): Double = meters * BOX_MARGIN / METERS_PER_DEGREE
}
