// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import android.util.Log
import com.avdesign.mfd24.astro.DteWindows
import com.avdesign.mfd24.astro.EarthSky
import com.avdesign.mfd24.astro.MarsSolarDay
import com.avdesign.mfd24.astro.MarsSolarTime
import com.avdesign.mfd24.astro.Rovers

/**
 * Owns everything the Mars face knows about its sky: the rover's daylight, the direct-to-Earth
 * windows, and — once ephemerides have been fetched — the relay windows. The Mars counterpart of
 * [TelemetryRepository]'s weather half, and deliberately a separate object: the Earth pipeline
 * is location → site → network, this one is arithmetic first and network only for the orbiters,
 * and forcing the two into one class would gate each on the other's failures.
 *
 * The offline half ([refreshLocal]) is pure mathematics over the selected rover's constants, so
 * it can run on any thread at any provocation — process start, screen-on, a rover change — and
 * costs well under a millisecond. Daylight is published through [TelemetryState.setDaylight],
 * the exact contract the renderer and AmbientAuto already read; on this flavor nothing else
 * writes it (see the world gate in [TelemetryRepository.refreshDaylight]).
 *
 * The relay half is a cache in front of [HorizonsClient]. Validity is per instant, not per
 * fetch: a failed fetch with future coverage still on file is not a failure, and a clean cache
 * that has aged past now is one — the union is published `relayValid` only while **every
 * enabled** satellite's table reaches past now, because a union quietly missing one orbiter
 * reads as "no passes", which is the wrong kind of wrong.
 */
class MarsCommRepository private constructor(context: Context) {

    val state = MarsCommState()

    private val appContext = context.applicationContext
    private val telemetry = TelemetryRepository.get(context).state
    private val store = MarsCommStore(context)

    private val scratchDay = MarsSolarDay()
    private val scratchDte = DteWindows()
    private val scratchTable = HorizonsTable()
    private val satStart = Array(SATELLITE_COMMANDS.size) { LongArray(WINDOWS_PER_SATELLITE) }
    private val satEnd = Array(SATELLITE_COMMANDS.size) { LongArray(WINDOWS_PER_SATELLITE) }
    private val satCounts = IntArray(SATELLITE_COMMANDS.size)
    private val unionStart = LongArray(MarsCommState.MAX_RELAY_WINDOWS)
    private val unionEnd = LongArray(MarsCommState.MAX_RELAY_WINDOWS)

    @Volatile
    private var rover: Int = Rovers.PERSEVERANCE

    @Volatile
    private var relayMask: Int = ALL_RELAYS

    /** The rover in force; the style is the single writer, through the renderer's callback. */
    fun setRover(index: Int) {
        if (rover == index) return
        rover = index
        // A different site is a different sky: recompute now rather than at the next screen-on,
        // because the editor's live preview is exactly when the change must be seen to land.
        // The relay cache is keyed per rover, so whatever was fetched for this one republishes —
        // and where nothing was, the expedited fetch answers in seconds rather than leaving
        // NO EPHEMERIS standing until the six-hourly schedule happens by.
        refreshLocal(System.currentTimeMillis())
        MarsEphemerisWorker.fetchNow(appContext)
    }

    /** Which orbiters count toward the relay union, a bitmask in RELAY_SETTINGS order. */
    fun setRelayMask(mask: Int) {
        if (relayMask == mask) return
        relayMask = mask
        publishRelay(System.currentTimeMillis())
        // A newly enabled satellite may have no cache; the worker no-ops when everything is
        // fresh, so asking is cheap and not asking is a line stuck on yesterday's choice.
        MarsEphemerisWorker.fetchNow(appContext)
    }

    fun rover(): Int = rover

    fun relayMask(): Int = relayMask

    /**
     * Recomputes everything that needs no network: the sol's daylight for the Nadir band and
     * the sun mark, the direct-to-Earth windows for the inner comm line, and the relay union
     * from whatever the cache holds. Synchronised because the scratch objects are shared;
     * contention is a screen-on racing a rover change, both rare, both cheap.
     */
    @Synchronized
    fun refreshLocal(nowMillis: Long) {
        val lat = Rovers.LAT[rover]
        val lon = Rovers.LON_EAST[rover]
        MarsSolarTime.computeDay(nowMillis, lat, lon, scratchDay)
        telemetry.setDaylight(scratchDay.kind, scratchDay.sunriseMillis, scratchDay.sunsetMillis)
        state.setTwilight(scratchDay.twilightStartMillis, scratchDay.twilightEndMillis)
        EarthSky.computeWindows(nowMillis, lat, lon, EarthSky.MIN_ELEVATION_DEG, scratchDte)
        state.setDte(scratchDte)
        publishRelay(nowMillis)
    }

    /**
     * Fetches fresh tables for every enabled satellite whose cache runs out within
     * [MIN_COVERAGE_MILLIS], then republishes the union. Blocking network; call from a worker.
     * Returns false when any wanted fetch failed, so the worker can ask WorkManager to retry.
     */
    fun refreshRelay(nowMillis: Long): Boolean {
        var allFetched = true
        val mask = relayMask
        val site = rover
        for (sat in SATELLITE_COMMANDS.indices) {
            if (mask and (1 shl sat) == 0) continue
            if (store.coverageUntil(site, sat) - nowMillis >= MIN_COVERAGE_MILLIS) continue
            val stop = nowMillis + FETCH_SPAN_MILLIS
            when (HorizonsClient.fetch(
                SATELLITE_COMMANDS[sat], Rovers.LON_EAST[site], Rovers.LAT[site],
                nowMillis, stop, scratchTable,
            )) {
                HorizonsClient.OK -> {
                    val count = RelayWindows.extract(
                        scratchTable, EarthSky.MIN_ELEVATION_DEG, satStart[sat], satEnd[sat],
                    )
                    store.saveWindows(
                        site, sat, RelayWindows.pack(satStart[sat], satEnd[sat], count),
                        scratchTable.timesMillis[scratchTable.count - 1],
                    )
                    Log.i(
                        TAG,
                        "relay ${SATELLITE_COMMANDS[sat]}: $count windows to ${stop - nowMillis}ms",
                    )
                }

                // JPL has no trajectory for these dates — met live with MAVEN, whose published
                // ephemeris ended 2026-03-01. The spacecraft contributes honest emptiness for a
                // day rather than holding the whole line in NO EPHEMERIS: the notice is for
                // "cannot compute", and three orbiters' windows are computed.
                HorizonsClient.NO_COVERAGE -> {
                    store.saveWindows(site, sat, "", nowMillis + NO_DATA_RECHECK_MILLIS)
                    Log.i(TAG, "relay ${SATELLITE_COMMANDS[sat]}: no trajectory published")
                }

                else -> allFetched = false
            }
        }
        synchronized(this) { publishRelay(nowMillis) }
        return allFetched
    }

    /**
     * Rebuilds the published union from the cache. Callers hold the monitor (or are the
     * constructor); the arrays are the shared scratch.
     */
    private fun publishRelay(nowMillis: Long) {
        val mask = relayMask
        val site = rover
        var valid = true
        var enabled = 0
        for (sat in SATELLITE_COMMANDS.indices) {
            if (mask and (1 shl sat) == 0) {
                satCounts[sat] = 0
                continue
            }
            enabled++
            satCounts[sat] = RelayWindows.unpack(store.windows(site, sat), satStart[sat], satEnd[sat])
            if (store.coverageUntil(site, sat) <= nowMillis) valid = false
        }
        if (enabled == 0) {
            // Nothing enabled is an operator's choice, not a failure: no line and no notice.
            state.setRelay(unionStart, unionEnd, 0, valid = true)
            return
        }
        val count = RelayWindows.union(satStart, satEnd, satCounts, unionStart, unionEnd)
        state.setRelay(unionStart, unionEnd, if (valid) count else 0, valid)
    }

    companion object {
        private const val TAG = "MarsCommRepository"

        /** All four orbiters enabled — the schema's own default. */
        const val ALL_RELAYS: Int = 0xF

        /** Horizons ids, in [com.avdesign.mfd24.style.StyleSchema.RELAY_SETTINGS] order. */
        val SATELLITE_COMMANDS = arrayOf("-74", "-53", "-202", "-143")

        /** Refetch when the cache's future coverage falls below this. */
        const val MIN_COVERAGE_MILLIS: Long = 6 * 3_600_000L

        /** How far ahead each fetch reaches: past the next sol, so a missed run costs slack. */
        const val FETCH_SPAN_MILLIS: Long = 30 * 3_600_000L

        /** How long a "no trajectory published" answer stands before asking again. */
        const val NO_DATA_RECHECK_MILLIS: Long = 24 * 3_600_000L

        /** Ceiling per satellite per fetch span; a ~2 h orbit above 10 deg is far fewer. */
        private const val WINDOWS_PER_SATELLITE = 32

        @Volatile
        private var instance: MarsCommRepository? = null

        fun get(context: Context): MarsCommRepository =
            instance ?: synchronized(this) {
                instance ?: MarsCommRepository(context.applicationContext).also {
                    it.refreshLocal(System.currentTimeMillis())
                    instance = it
                }
            }
    }
}
