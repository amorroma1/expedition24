// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.geo

import android.content.Context
import com.avdesign.mfd24.data.TelemetryState
import kotlin.math.roundToInt

/**
 * Turns a position fix into the "nearest site" line of the telemetry window.
 *
 * The database is opened lazily on first use and then kept — it is a few hundred kilobytes of heap
 * and reloading it per query would be wasteful. Everything here runs on a background dispatcher;
 * results reach the renderer through [TelemetryState].
 */
class PoiResolver(private val context: Context, private val state: TelemetryState) {

    private var database: PoiDatabase? = null
    private var databaseTried = false
    private val hit = PoiHit()

    /** Nothing further than this counts as "you are here". */
    var radiusMeters: Double = DEFAULT_RADIUS_METERS

    /**
     * Looks up the nearest site, publishes it to [TelemetryState], and returns it so the caller can
     * persist it. The returned [PoiHit] is reused between calls — copy anything you need to keep.
     */
    @Synchronized
    fun resolve(lat: Double, lon: Double): PoiHit? {
        val db = database()
        if (db == null) {
            state.clearSite()
            return null
        }
        if (!db.queryNearest(lat, lon, radiusMeters, hit)) {
            state.clearSite()
            return null
        }
        val distanceHm = (hit.distanceMeters / 100.0).roundToInt()
        state.setSite(hit.code, hit.codeLength, hit.type, hit.flags, distanceHm)
        return hit
    }

    fun clear() = state.clearSite()

    private fun database(): PoiDatabase? {
        if (!databaseTried) {
            databaseTried = true
            database = PoiDatabase.open(context)
        }
        return database
    }

    companion object {
        const val DEFAULT_RADIUS_METERS: Double = 5_000.0
    }
}
