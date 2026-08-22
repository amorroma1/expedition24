// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent cache for the last known weather, position and site.
 *
 * It lives in **device-protected** storage on purpose. The watch face service is
 * `directBootAware`, so it can be asked to render before the user has unlocked the watch, and
 * credential-encrypted storage is simply not readable at that point. Keeping the cache here means
 * the telemetry window comes up populated straight after a reboot instead of showing `WX ---`
 * until first unlock.
 */
class TelemetryStore(context: Context) {

    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // --- Device fix ------------------------------------------------------------------------

    /**
     * The last position the platform actually gave us, and nothing else.
     *
     * A hand-typed position used to be written here too, which quietly cost the app a real fix: it
     * made [hasDevicePosition] true, so the cold-start branch stopped asking for one, and it gave
     * the site search's [ResolveOrigin] a fictional point to measure from. The two now live in separate
     * keys and only the device may write these.
     */
    val deviceLatitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_LAT, NO_FIX_BITS))

    val deviceLongitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_LON, NO_FIX_BITS))

    val hasDevicePosition: Boolean
        get() = !deviceLatitude.isNaN() && !deviceLongitude.isNaN()

    fun saveDevicePosition(lat: Double, lon: Double) {
        prefs.edit()
            .putLong(KEY_LAT, lat.toRawBits())
            .putLong(KEY_LON, lon.toRawBits())
            .apply()
    }

    // --- Manual position -------------------------------------------------------------------

    /**
     * A position typed in on the watch, for when location is switched off or refused. Kept apart
     * from the cached device fix so the two never get confused: only a real fix earns the site
     * lock.
     */
    /**
     * Whether the user has asked for the manual position to be used *instead of* the device.
     *
     * Separate from whether one is on file, because the two answer different questions: a manual
     * position can sit there as a fallback for when the platform gives us nothing, or it can be
     * the deliberate choice. Nothing here is derivable from the OS permission -- on API 30 an app
     * cannot hand its own location permission back, so "stop using my location" has to be the
     * app's own switch or it cannot exist at all.
     */
    var manualPositionSelected: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_SELECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MANUAL_SELECTED, value).apply()

    val hasManualPosition: Boolean
        get() = prefs.contains(KEY_MANUAL_LAT)

    val manualLatitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_MANUAL_LAT, NO_FIX_BITS))

    val manualLongitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_MANUAL_LON, NO_FIX_BITS))

    fun saveManualPosition(lat: Double, lon: Double) {
        prefs.edit()
            .putLong(KEY_MANUAL_LAT, lat.toRawBits())
            .putLong(KEY_MANUAL_LON, lon.toRawBits())
            .apply()
    }

    fun clearManualPosition() {
        prefs.edit().remove(KEY_MANUAL_LAT).remove(KEY_MANUAL_LON).apply()
    }

    val weatherObservedAt: Long
        get() = prefs.getLong(KEY_WX_AT, 0L)

    /** Where the cached observation was taken. NaN when it predates this being recorded. */
    val weatherLatitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_WX_LAT, NO_FIX_BITS))

    val weatherLongitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_WX_LON, NO_FIX_BITS))

    fun saveWeather(
        temperatureDeciC: Int,
        pressureDeciHpa: Int,
        conditionIndex: Int,
        observedAt: Long,
        latitude: Double,
        longitude: Double,
    ) {
        prefs.edit()
            .putInt(KEY_WX_TEMP, temperatureDeciC)
            .putInt(KEY_WX_PRESSURE, pressureDeciHpa)
            .putInt(KEY_WX_CONDITION, conditionIndex)
            .putLong(KEY_WX_AT, observedAt)
            .putLong(KEY_WX_LAT, latitude.toRawBits())
            .putLong(KEY_WX_LON, longitude.toRawBits())
            .apply()
    }

    fun saveSite(code: String, type: Int, flags: Int, distanceHm: Int) {
        prefs.edit()
            .putString(KEY_SITE_CODE, code)
            .putInt(KEY_SITE_TYPE, type)
            .putInt(KEY_SITE_FLAGS, flags)
            .putInt(KEY_SITE_DISTANCE, distanceHm)
            .apply()
    }

    fun clearSite() {
        prefs.edit().remove(KEY_SITE_CODE).apply()
    }

    /** Repopulates [state] from the cache so the first frame after a reboot is not blank. */
    fun restoreInto(state: TelemetryState) {
        val observedAt = weatherObservedAt
        if (observedAt > 0L) {
            state.setWeather(
                prefs.getInt(KEY_WX_TEMP, 0),
                prefs.getInt(KEY_WX_PRESSURE, 0),
                prefs.getInt(KEY_WX_CONDITION, WeatherCondition.UNKNOWN),
                observedAt,
            )
        }
        val code = prefs.getString(KEY_SITE_CODE, null)
        if (code != null && code.isNotEmpty()) {
            val chars = CharArray(code.length)
            code.toCharArray(chars, 0, 0, code.length)
            state.setSite(
                chars,
                chars.size,
                prefs.getInt(KEY_SITE_TYPE, 0),
                prefs.getInt(KEY_SITE_FLAGS, 0),
                prefs.getInt(KEY_SITE_DISTANCE, 0),
            )
        } else {
            state.clearSite()
        }
    }

    private companion object {
        const val NAME = "mfd24_telemetry"

        val NO_FIX_BITS: Long = Double.NaN.toRawBits()

        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_MANUAL_SELECTED = "manual_selected"
        const val KEY_MANUAL_LAT = "manual_lat"
        const val KEY_MANUAL_LON = "manual_lon"
        const val KEY_WX_TEMP = "wx_temp"
        const val KEY_WX_PRESSURE = "wx_pressure"
        const val KEY_WX_CONDITION = "wx_condition"
        const val KEY_WX_AT = "wx_at"
        const val KEY_WX_LAT = "wx_lat"
        const val KEY_WX_LON = "wx_lon"
        const val KEY_SITE_CODE = "site_code"
        const val KEY_SITE_TYPE = "site_type"
        const val KEY_SITE_FLAGS = "site_flags"
        const val KEY_SITE_DISTANCE = "site_distance"
    }
}
