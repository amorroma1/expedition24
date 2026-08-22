// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import com.avdesign.mfd24.astro.SolarTime

/**
 * Decides whether the always-on face is thinned to every other pixel.
 *
 * HALVED exists because half the lit subpixels is half the always-on power — and it is plainly too
 * dim in daylight, which is why it is not the default. AUTO takes the trade only when it pays:
 * after sunset the halved face is bright enough and the saving is real, so it thins by night and
 * stays solid by day.
 *
 * The exception is a running watch. A night shift is exactly when the dial is being *worked from*
 * in the dark, so AUTO backs off for the whole of an active duty and resumes when it is served —
 * an operator who wants the dim face on shift can still choose HALVED outright.
 *
 * Night is read from the daylight window the dial already computes for Nadir, so the two can never
 * disagree about when the sun went down. Without a position there is no sunset to consult, and the
 * fallback is the readable direction: full density, exactly what FULL would have drawn. Polar day
 * and night resolve the same way the band does — no sunset means no thinning, no sunrise means
 * thinning all day.
 *
 * Android-free so [AmbientAutoTest] can pin the rule rather than a wrist having to catch it.
 */
object AmbientAuto {

    /** Always-on density modes, resolved from the style's option id once per style change. */
    const val MODE_FULL: Int = 0
    const val MODE_HALF: Int = 1
    const val MODE_AUTO: Int = 2

    /**
     * @param dutyActive a watch is under way right now — not booked, not served
     * @param daylightValid whether the [daylightKind] and the two instants mean anything
     */
    fun halfDensity(
        mode: Int,
        dutyActive: Boolean,
        daylightValid: Boolean,
        daylightKind: Int,
        sunriseMillis: Long,
        sunsetMillis: Long,
        nowMillis: Long,
    ): Boolean = when (mode) {
        MODE_HALF -> true
        MODE_AUTO -> !dutyActive && isNight(daylightValid, daylightKind, sunriseMillis, sunsetMillis, nowMillis)
        else -> false
    }

    private fun isNight(
        valid: Boolean,
        kind: Int,
        sunriseMillis: Long,
        sunsetMillis: Long,
        nowMillis: Long,
    ): Boolean = when {
        !valid -> false
        kind == SolarTime.POLAR_DAY -> false
        kind == SolarTime.POLAR_NIGHT -> true
        else -> nowMillis < sunriseMillis || nowMillis > sunsetMillis
    }
}
