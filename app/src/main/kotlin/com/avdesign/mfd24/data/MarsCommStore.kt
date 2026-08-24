// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import android.content.Context
import android.content.SharedPreferences

/**
 * The relay-window cache, in device-protected storage for the same reason the telemetry cache
 * is: the face is direct-boot aware, and windows fetched yesterday are exactly what a rebooted,
 * still-locked watch should draw. Keyed per rover *and* per satellite — the windows are the
 * geometry of one site's sky, so a rover switch must not read the other site's passes, and each
 * satellite refreshes on its own clock.
 */
class MarsCommStore(context: Context) {

    private val prefs: SharedPreferences = context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun saveWindows(rover: Int, satellite: Int, packed: String, coverageUntilMillis: Long) {
        prefs.edit()
            .putString(windowsKey(rover, satellite), packed)
            .putLong(coverageKey(rover, satellite), coverageUntilMillis)
            .apply()
    }

    fun windows(rover: Int, satellite: Int): String =
        prefs.getString(windowsKey(rover, satellite), "") ?: ""

    /** Until when the cached table for this rover and satellite says anything; 0 when never fetched. */
    fun coverageUntil(rover: Int, satellite: Int): Long =
        prefs.getLong(coverageKey(rover, satellite), 0L)

    private fun windowsKey(rover: Int, satellite: Int) = "w_${rover}_$satellite"

    private fun coverageKey(rover: Int, satellite: Int) = "cov_${rover}_$satellite"

    private companion object {
        const val NAME = "mfd24_mars_comm"
    }
}
