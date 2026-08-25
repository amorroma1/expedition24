// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import android.location.Location
import android.util.Log
import com.avdesign.mfd24.BuildConfig
import com.avdesign.mfd24.astro.SolarDay
import com.avdesign.mfd24.astro.SolarTime
import com.avdesign.mfd24.geo.PoiFormat
import com.avdesign.mfd24.geo.PoiResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RefreshOutcome {
    /** Everything that could be refreshed was. */
    OK,

    /** The user has not granted location access; nothing to retry. */
    NO_PERMISSION,

    /** Permission is granted but no fix arrived; worth trying again later. */
    NO_FIX,

    /** We had a fix but the weather call failed; worth retrying. */
    NETWORK_ERROR,
}

/**
 * Single owner of everything the telemetry window shows.
 *
 * Process-wide, because two very different callers need the same data: the watch face service (for
 * rendering, and for an opportunistic refresh when the screen comes on) and the periodic worker.
 * Keeping one instance means the worker's results are visible to the renderer immediately, without
 * a broadcast or a second read of the cache.
 */
class TelemetryRepository private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    val state = TelemetryState()

    private val store = TelemetryStore(appContext)
    private val location = LocationRepository(appContext)
    private val poi = PoiResolver(appContext, state)

    private val refreshLock = Mutex()

    private val solarDay = SolarDay()

    private val eventStart = LongArray(DayMarks.MAX_EVENTS)
    private val eventEnd = LongArray(DayMarks.MAX_EVENTS)

    /**
     * The position everything downstream actually uses: a device fix when there is one, otherwise
     * a hand-typed fallback, otherwise NaN. Derived rather than stored, because which of the two
     * sources wins changes the moment a permission is granted or a fix arrives.
     */
    @Volatile
    private var activeLatitude: Double = Double.NaN

    @Volatile
    private var activeLongitude: Double = Double.NaN

    /** Where the 5 km search last ran, so a move of any kind can retrigger it. */
    private val resolveOrigin = ResolveOrigin()

    init {
        // Populate from the device-protected cache so the very first frame is not blank.
        store.restoreInto(state)
        adoptBestPosition()
        refreshDaylight(System.currentTimeMillis())
    }

    /**
     * Chooses between the cached device fix and the hand-typed fallback and publishes the result.
     *
     * A device fix wins unless the user has explicitly asked for manual. Both are accurate
     * enough for the site lock now that the manual entry steps in hundredths of a degree, so the
     * choice is about which position is right, not about which features survive.
     */
    @Synchronized
    private fun adoptBestPosition() {
        // An explicit choice of manual outranks a perfectly good device fix: that is the whole
        // point of the switch.
        if (!(store.manualPositionSelected && store.hasManualPosition) &&
            location.hasPermission() && store.hasDevicePosition
        ) {
            activeLatitude = store.deviceLatitude
            activeLongitude = store.deviceLongitude
            state.positionLatDeg = activeLatitude
            state.positionLonDeg = activeLongitude
            state.positionSource = TelemetryState.POSITION_DEVICE
            return
        }
        if (store.hasManualPosition) {
            activeLatitude = store.manualLatitude
            activeLongitude = store.manualLongitude
            state.positionLatDeg = activeLatitude
            state.positionLonDeg = activeLongitude
            state.positionSource = TelemetryState.POSITION_MANUAL
            // Left to the refresh that follows: the site is no longer forfeit just because the
            // position was typed rather than measured.
            return
        }
        activeLatitude = Double.NaN
        activeLongitude = Double.NaN
        state.positionLatDeg = Double.NaN
        state.positionLonDeg = Double.NaN
        state.positionSource = TelemetryState.POSITION_NONE
        poi.clear()
        store.clearSite()
    }

    /**
     * Recomputes the daylight window from the position in force. Cheap, offline and independent of
     * the weather call, so it runs on every refresh and on start-up — the band would otherwise be
     * a day stale after midnight.
     */
    @Synchronized
    fun refreshDaylight(nowMillis: Long) {
        val lat = activeLatitude
        val lon = activeLongitude
        if (lat.isNaN() || lon.isNaN()) {
            state.clearDaylight()
            return
        }
        SolarTime.compute(nowMillis, lat, lon, solarDay)
        state.setDaylight(solarDay.kind, solarDay.sunriseMillis, solarDay.sunsetMillis)

    }

    /**
     * The next alarm and today's calendar, both cheap and neither on the network. Called from
     * the periodic refresh and whenever the face becomes visible: an alarm set a minute ago
     * should be on the dial the next time it is looked at, not half an hour later.
     */
    fun refreshMarks(nowMillis: Long) {
        state.nextAlarmMillis = DayMarks.nextAlarmMillis(appContext, nowMillis)
        if (!DayMarks.hasCalendarPermission(appContext)) {
            state.setEvents(eventStart, eventEnd, 0)
            return
        }
        // The window the dial can actually draw: from now round to the same hand position
        // tomorrow, which is the whole of what a 24-hour face has room to say.
        val count = DayMarks.calendarEvents(
            appContext, nowMillis, nowMillis + 24 * 3_600_000L, eventStart, eventEnd,
        )
        state.setEvents(eventStart, eventEnd, count)
    }

    fun hasLocationPermission(): Boolean = location.hasPermission()

    /** True once the platform has given us a real fix, i.e. this is not a cold start. */
    fun hasCachedPosition(): Boolean = store.hasDevicePosition

    /**
     * Called the moment the user grants location access.
     *
     * Worth an active fix rather than waiting: the periodic worker may be half an hour away, and
     * the editor — where the grant happens — is exactly where the user is looking for a position
     * to appear. Any manual fallback stays on file but stops being used as soon as this lands.
     */
    suspend fun onLocationGranted(): RefreshOutcome =
        refresh(allowActiveFix = true, nowMillis = System.currentTimeMillis())

    /**
     * Whether the weather row is wanted at all. Set from the user style; when off nothing is
     * fetched, so the radio stays asleep as well as the row staying empty.
     */
    @Volatile
    var weatherEnabled: Boolean = true

    // --- Manual position -------------------------------------------------------------------

    fun hasManualPosition(): Boolean = store.hasManualPosition

    /**
     * Whether the hand-typed position is being used in place of the device.
     *
     * Setting it re-resolves immediately: switching back to automatic asks for a fresh fix rather
     * than waiting on the half-hourly worker, which is the whole reason anyone flips it back.
     */
    var manualPositionSelected: Boolean
        get() = store.manualPositionSelected
        set(value) {
            if (store.manualPositionSelected == value) return
            store.manualPositionSelected = value
            adoptBestPosition()
            refreshDaylight(System.currentTimeMillis())
        }

    fun manualLatitude(): Double = store.manualLatitude

    fun manualLongitude(): Double = store.manualLongitude

    /**
     * The position in force, or NaN. The editor seeds its coordinate steppers from this: starting
     * at 0.00 means a long hold to reach anywhere real, and wherever you are now is a far better
     * guess than the Gulf of Guinea.
     */
    fun currentLatitude(): Double = activeLatitude

    fun currentLongitude(): Double = activeLongitude

    /**
     * Records a hand-typed position and applies it, unless a real fix is already in force — in
     * which case it is kept on file as a fallback and nothing on the dial changes.
     */
    suspend fun setManualPosition(latitude: Double, longitude: Double) {
        store.saveManualPosition(latitude, longitude)
        // Typing a position in and pressing apply *is* choosing it; anything else leaves the user
        // staring at a device fix wondering why their coordinates did nothing.
        store.manualPositionSelected = true
        adoptBestPosition()
        refreshDaylight(System.currentTimeMillis())
        // Site and weather both follow the position, and both want to be off the main thread: the
        // first POI query reads a 138 KB asset and the weather call is a network round trip.
        refresh(allowActiveFix = false, nowMillis = System.currentTimeMillis())
    }

    fun clearManualPosition() {
        val wasInForce = state.positionSource == TelemetryState.POSITION_MANUAL
        store.clearManualPosition()
        store.manualPositionSelected = false
        if (wasInForce) {
            adoptBestPosition()
            state.clearWeather()
            refreshDaylight(System.currentTimeMillis())
        }
    }

    /**
     * Brings position, site and weather up to date.
     *
     * @param allowActiveFix when false only a cached fix is used, so the screen coming on never
     *   wakes the GPS; the periodic worker passes true.
     */
    suspend fun refresh(allowActiveFix: Boolean, nowMillis: Long): RefreshOutcome =
        refreshLock.withLock {
            withContext(Dispatchers.IO) {
                val outcome = refreshLocked(allowActiveFix, nowMillis)
                Log.d(
                    TAG,
                    "refresh(activeFix=$allowActiveFix) -> $outcome " +
                        "[site=${state.siteValid} weather=${state.weatherValid}]",
                )
                outcome
            }
        }

    private suspend fun refreshLocked(allowActiveFix: Boolean, nowMillis: Long): RefreshOutcome {
        // With manual selected there is nothing to ask the platform for: the position is already
        // decided, and waking the GPS to then ignore the answer is the worst of both.
        val manualChosen = store.manualPositionSelected && store.hasManualPosition
        val fix = if (!manualChosen && location.hasPermission()) {
            obtainFix(allowActiveFix, nowMillis)
        } else {
            null
        }

        val lat: Double
        val lon: Double
        if (fix != null) {
            lat = fix.latitude
            lon = fix.longitude
            store.saveDevicePosition(lat, lon)
            setActivePosition(lat, lon, TelemetryState.POSITION_DEVICE)
            maybeResolveSite(lat, lon)
        } else if (!manualChosen && location.hasPermission() && store.hasDevicePosition) {
            // The platform said nothing, not "nowhere". A wallpaper and a worker are both
            // background callers, and on API 29+ every provider hands them null without
            // ACCESS_BACKGROUND_LOCATION — clearing the state on that answer threw away a
            // perfectly good cached fix every half hour and left the dial blank between visits
            // to the editor, which is the only foreground this app has. The cache wins over a
            // manual fallback for the same reason adoptBestPosition gives the device first
            // refusal: manual only rules when the user chose it.
            lat = store.deviceLatitude
            lon = store.deviceLongitude
            setActivePosition(lat, lon, TelemetryState.POSITION_DEVICE)
            maybeResolveSite(lat, lon)
        } else if (store.hasManualPosition) {
            lat = store.manualLatitude
            lon = store.manualLongitude
            setActivePosition(lat, lon, TelemetryState.POSITION_MANUAL)
            maybeResolveSite(lat, lon)
        } else {
            setActivePosition(Double.NaN, Double.NaN, TelemetryState.POSITION_NONE)
            poi.clear()
            state.clearWeather()
            state.clearDaylight()
            return if (location.hasPermission()) {
                RefreshOutcome.NO_FIX
            } else {
                RefreshOutcome.NO_PERMISSION
            }
        }

        refreshDaylight(nowMillis)

        if (!weatherEnabled) {
            state.clearWeather()
            return RefreshOutcome.OK
        }

        // Age *and* distance. A reading is of one place as well as one moment, and the watch can
        // leave the place a great deal faster than the reading goes off.
        val stale = WeatherCache.isStale(
            store.weatherObservedAt, store.weatherLatitude, store.weatherLongitude,
            nowMillis, lat, lon,
        )
        if (!stale && state.weatherValid) {
            return RefreshOutcome.OK
        }

        // Locked to an aerodrome, the row shows that field's own METAR — the observation its
        // tower reads out, not a model cell that happens to contain it. Any miss falls back to
        // Open-Meteo, and the miss is not a corner case: most sites never had a METAR, and whole
        // regions stop publishing in wartime.
        val sample = metarIcao()?.let { MetarClient.fetch(it, nowMillis) }
            ?: OpenMeteoClient.fetch(lat, lon, nowMillis)
        if (sample == null) {
            Log.i(TAG, "Weather refresh failed; keeping the cached observation")
            return RefreshOutcome.NETWORK_ERROR
        }
        state.setWeather(
            sample.temperatureDeciC,
            sample.pressureDeciHpa,
            sample.conditionIndex,
            sample.observedAt,
        )
        store.saveWeather(
            sample.temperatureDeciC,
            sample.pressureDeciHpa,
            sample.conditionIndex,
            sample.observedAt,
            lat,
            lon,
        )
        return RefreshOutcome.OK
    }

    private suspend fun obtainFix(allowActiveFix: Boolean, nowMillis: Long): Location? {
        val cached = location.lastKnown()
        if (cached != null && nowMillis - cached.time < FIX_TTL_MS) return cached
        if (!allowActiveFix) return cached
        return location.current() ?: cached
    }

    /**
     * Runs the site search when it could say something new: the position has left the last
     * search's 500 m, or there is no site on the dial to be stale.
     */
    /**
     * The wellness face has no site row and ships without the site index, so nothing here may
     * reach for it: the resolver would open an asset that is not in the APK, and the METAR a
     * locked aerodrome earns would be weather nobody prints.
     */
    private val siteLockAvailable: Boolean = BuildConfig.WORLD != "vital"

    private fun maybeResolveSite(lat: Double, lon: Double) {
        if (!siteLockAvailable) return
        if (resolveOrigin.movedBeyond(lat, lon, RESOLVE_DISTANCE_M) || !state.siteValid) {
            resolveSite(lat, lon)
        }
    }

    /** Finds the nearest site, publishes it to the state and mirrors it into the cache. */
    private fun resolveSite(lat: Double, lon: Double) {
        // Recorded beside the search, never in the distance check: see [ResolveOrigin] for the
        // two failure modes that placement is guarding against.
        resolveOrigin.record(lat, lon)
        val hit = poi.resolve(lat, lon)
        if (hit == null) {
            store.clearSite()
            return
        }
        // The same rounding the resolver published to the state, so the row does not shift by a
        // tenth of a kilometre when it is restored from the cache.
        store.saveSite(
            String(hit.code, 0, hit.codeLength),
            hit.type,
            hit.flags,
            state.siteDistanceHm,
        )
    }

    /**
     * The locked site's ICAO, or null when the site is not METAR-worthy: not an aerodrome, a
     * helipad, or one of the few fields whose code is a local identifier because no ICAO was
     * ever assigned. Four upper-case letters is the gate — the airports data carries ICAO only,
     * a decision made after a mixed column burned once (Hostomel was ICAO, Zhuliany was IATA).
     */
    private fun metarIcao(): String? {
        if (!siteLockAvailable) return null
        if (!state.siteValid || state.siteType != PoiFormat.TYPE_AIRPORT) return null
        if (state.siteFlags and PoiFormat.FLAG_HELIPAD != 0) return null
        val code = CharArray(8)
        val length = state.copySiteCode(code)
        if (length != 4) return null
        for (i in 0 until length) if (code[i] !in 'A'..'Z') return null
        return String(code, 0, length)
    }

    @Synchronized
    private fun setActivePosition(lat: Double, lon: Double, source: Int) {
        activeLatitude = lat
        activeLongitude = lon
        state.positionLatDeg = lat
        state.positionLonDeg = lon
        state.positionSource = source
    }

    companion object {
        private const val TAG = "TelemetryRepository"

        /** How stale a cached fix may be before we ask for a new one. */
        const val FIX_TTL_MS: Long = 15 * 60 * 1000L

        /** Re-running the 5 km search below this displacement cannot change the answer much. */
        const val RESOLVE_DISTANCE_M: Double = 500.0

        @Volatile
        private var instance: TelemetryRepository? = null

        fun get(context: Context): TelemetryRepository =
            instance ?: synchronized(this) {
                instance ?: TelemetryRepository(context).also { instance = it }
            }
    }
}
