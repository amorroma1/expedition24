// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

/**
 * The mutable slice of the world the render loop reads: last known weather and the nearest
 * infrastructure site.
 *
 * Producers (the weather worker, the POI resolver) run on background threads; the single consumer
 * is the render thread. Fields are primitives behind `@Volatile`, so reading them costs nothing and
 * allocates nothing. The site code is a character array, which cannot be published atomically, so
 * it is versioned: the renderer copies it into its own buffer only when [siteVersion] changes.
 */
class TelemetryState {

    // --- Weather ---------------------------------------------------------------------------

    @Volatile
    var weatherValid: Boolean = false
        private set

    /** Air temperature in tenths of a degree Celsius. */
    @Volatile
    var temperatureDeciC: Int = 0
        private set

    /** QNH in tenths of a hectopascal. */
    @Volatile
    var pressureDeciHpa: Int = 0
        private set

    /** Index into [WeatherCondition.TOKENS]. */
    @Volatile
    var conditionIndex: Int = WeatherCondition.UNKNOWN
        private set

    @Volatile
    var weatherObservedAt: Long = 0L
        private set

    fun setWeather(temperatureDeciC: Int, pressureDeciHpa: Int, conditionIndex: Int, observedAt: Long) {
        this.temperatureDeciC = temperatureDeciC
        this.pressureDeciHpa = pressureDeciHpa
        this.conditionIndex = conditionIndex
        this.weatherObservedAt = observedAt
        this.weatherValid = true
    }

    fun clearWeather() {
        weatherValid = false
        conditionIndex = WeatherCondition.UNKNOWN
    }

    // --- Daylight --------------------------------------------------------------------------

    /** One of `SolarTime.NORMAL` / `POLAR_DAY` / `POLAR_NIGHT`; only meaningful when valid. */
    @Volatile
    var daylightKind: Int = 0
        private set

    @Volatile
    var daylightValid: Boolean = false
        private set

    @Volatile
    var sunriseMillis: Long = 0L
        private set

    @Volatile
    var sunsetMillis: Long = 0L
        private set

    fun setDaylight(kind: Int, sunriseMillis: Long, sunsetMillis: Long) {
        this.daylightKind = kind
        this.sunriseMillis = sunriseMillis
        this.sunsetMillis = sunsetMillis
        this.daylightValid = true
    }

    fun clearDaylight() {
        daylightValid = false
    }

    // --- Nearest site ----------------------------------------------------------------------

    /**
     * Where the position on file came from: [POSITION_NONE], [POSITION_DEVICE] or
     * [POSITION_MANUAL].
     *
     * Both now earn the site lock. That was not always true: the manual entry used to step in
     * tenths of a degree, and 11 km of quantisation cannot support a 5 km radius. It steps in
     * hundredths now — about 1.1 km of latitude, less of longitude away from the equator — which
     * fits inside the radius with room to spare.
     */
    /**
     * Battery charge, 0..100, or -1 before the first `ACTION_BATTERY_CHANGED` arrives.
     *
     * Pushed in by a receiver rather than read on demand: the level changes about a hundred times
     * over a discharge, and asking for it once a frame — or once a minute — would be a poll where
     * the platform is already willing to tell us.
     */
    @Volatile
    var batteryPercent: Int = -1

    /**
     * The two optional sensor readouts either side of the hub, in the units they are drawn in:
     * beats per minute, steps since the most recent local midnight, and station pressure in tenths of a
     * hectopascal.
     *
     * [SensorSlots.NO_READING] until something arrives, which for the optical heart rate is several
     * seconds after the screen comes on. Kept apart from the weather row's own pressure on purpose:
     * that is sea-level pressure for the nearest station and this is the air here, and averaging
     * two different quantities into one field would make both wrong.
     */
    @Volatile
    var heartRate: Int = SensorSlots.NO_READING

    @Volatile
    var stepsToday: Int = SensorSlots.NO_READING

    @Volatile
    var localPressureTenths: Int = SensorSlots.NO_READING

    /**
     * The position in force, degrees, NaN when there is none. Mirrored here because the moon
     * mark needs an observer: the sun's dial place falls out of the daylight instants, but the
     * moon's hour angle and altitude need latitude and longitude of their own.
     */
    @Volatile
    var positionLatDeg: Double = Double.NaN

    @Volatile
    var positionLonDeg: Double = Double.NaN

    @Volatile
    var positionSource: Int = POSITION_NONE

    val hasPosition: Boolean
        get() = positionSource != POSITION_NONE

    @Volatile
    var siteValid: Boolean = false
        private set

    /** One of `PoiFormat.TYPE_*`. */
    @Volatile
    var siteType: Int = 0
        private set

    /** Bitfield of `PoiFormat.FLAG_*`; with [siteType] it picks the pictogram. */
    @Volatile
    var siteFlags: Int = 0
        private set

    /** Distance to the site in tenths of a kilometre. */
    @Volatile
    var siteDistanceHm: Int = 0
        private set

    @Volatile
    var siteVersion: Int = 0
        private set

    private val siteCode = CharArray(8)

    @Volatile
    private var siteCodeLength: Int = 0

    fun setSite(code: CharArray, codeLength: Int, type: Int, flags: Int, distanceHm: Int) {
        val n = if (codeLength > siteCode.size) siteCode.size else codeLength
        System.arraycopy(code, 0, siteCode, 0, n)
        siteCodeLength = n
        siteType = type
        siteFlags = flags
        siteDistanceHm = distanceHm
        siteValid = true
        siteVersion++
    }

    fun clearSite() {
        siteValid = false
        siteCodeLength = 0
        siteVersion++
    }

    companion object {
        const val POSITION_NONE: Int = 0

        /** A real fix from the platform: good enough for the 5 km site lock. */
        const val POSITION_DEVICE: Int = 1

        /** Typed in by hand, to hundredths of a degree. */
        const val POSITION_MANUAL: Int = 2
    }

    /** Copies the current code into [dst] and returns its length. Allocation-free. */
    fun copySiteCode(dst: CharArray): Int {
        val n = siteCodeLength
        val count = if (n > dst.size) dst.size else n
        System.arraycopy(siteCode, 0, dst, 0, count)
        return count
    }
}
