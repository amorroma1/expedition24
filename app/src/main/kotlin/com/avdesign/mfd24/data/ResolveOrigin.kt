// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import com.avdesign.mfd24.geo.Geo

/**
 * Where the 5 km site search last actually ran, device or manual.
 *
 * A class of its own because the two mistakes it guards against are both about *when the fields
 * are written*, and both have been made here. Recording the origin inside the "has it moved" check
 * lets the baseline creep: a wearer drifting 400 m between refreshes resets the origin every time
 * and never crosses the threshold, however far the drift adds up to. And an early return for the
 * unset case that skips the write leaves the origin unset for ever, so every refresh reads as a
 * move and the distance gate does nothing at all. The rule that prevents both is that only
 * [record] writes, and only the caller that actually ran the search calls it.
 *
 * Android-free so the rule is pinned by [ResolveOriginTest] rather than reasoned about.
 */
internal class ResolveOrigin {

    private var latitude: Double = Double.NaN
    private var longitude: Double = Double.NaN

    /** True when no search has run yet, or the given point is more than [meters] from the last one. */
    fun movedBeyond(lat: Double, lon: Double, meters: Double): Boolean {
        if (latitude.isNaN() || longitude.isNaN()) return true
        return Geo.haversineMeters(latitude, longitude, lat, lon) > meters
    }

    /** Call from beside the search itself, never from the check. */
    fun record(lat: Double, lon: Double) {
        latitude = lat
        longitude = lon
    }
}
