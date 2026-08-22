// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** The moon as the dial needs it: where it stands over one observer, and how much of it is lit. */
class MoonState {

    /** Hour angle, degrees, positive west of the meridian; the dial position follows from this. */
    @JvmField
    var hourAngleDeg: Double = 0.0

    /** Geometric altitude, degrees; the mark exists only while this is positive. */
    @JvmField
    var altitudeDeg: Double = 0.0

    /** Illuminated fraction of the disc, 0 (new) to 1 (full). */
    @JvmField
    var illuminatedFraction: Double = 0.0
}

/**
 * The moon's place in the sky, from the truncated ELP series in Meeus — the ten largest
 * longitude terms and six of latitude, which lands within about a third of a degree. That is an
 * order of magnitude finer than a wrist can be aimed, and the compass is the point: the moon
 * mark sits at the moon's *hour angle*, the way the solar mark sits at the sun's, so turning the
 * watch until the mark points at the real moon orients the dial exactly as the sun does by day.
 *
 * Hour angle, deliberately, not clock time: the sky does not know what time zone it is over,
 * which is also why neither mark moves during a zone glide. Pure arithmetic on UTC milliseconds;
 * [MoonSkyTest] holds it against an independent ephemeris.
 */
object MoonSky {

    private const val DEG = Math.PI / 180.0

    /** Fills [out] for the observer at ([latitude], [longitude] east) at [epochMillis]. */
    fun compute(epochMillis: Long, latitude: Double, longitude: Double, out: MoonState) {
        val t = (AstroTime.julianDayUtc(epochMillis) - 2451545.0) / 36525.0

        // Fundamental arguments, degrees (Meeus ch. 47).
        val d = 297.8501921 + 445267.1114034 * t
        val m = 357.5291092 + 35999.0502909 * t
        val mp = 134.9633964 + 477198.8675055 * t
        val f = 93.2720950 + 483202.0175233 * t
        val lp = 218.3164477 + 481267.88123421 * t

        val lon = lp +
            6.288774 * sin(mp * DEG) +
            1.274027 * sin((2 * d - mp) * DEG) +
            0.658314 * sin(2 * d * DEG) +
            0.213618 * sin(2 * mp * DEG) -
            0.185116 * sin(m * DEG) -
            0.114332 * sin(2 * f * DEG) +
            0.058793 * sin((2 * d - 2 * mp) * DEG) +
            0.057066 * sin((2 * d - m - mp) * DEG) +
            0.053322 * sin((2 * d + mp) * DEG) +
            0.045758 * sin((2 * d - m) * DEG)

        val lat = 5.128122 * sin(f * DEG) +
            0.280602 * sin((mp + f) * DEG) +
            0.277693 * sin((mp - f) * DEG) +
            0.173237 * sin((2 * d - f) * DEG) +
            0.055413 * sin((2 * d + f - mp) * DEG) +
            0.046271 * sin((2 * d - f - mp) * DEG)

        // Ecliptic to equatorial.
        val eps = (23.4392911 - 0.0130042 * t) * DEG
        val lonR = lon * DEG
        val latR = lat * DEG
        val sinLon = sin(lonR)
        val ra = atan2(sinLon * cos(eps) - Math.tan(latR) * sin(eps), cos(lonR))
        val dec = asin(sin(latR) * cos(eps) + cos(latR) * sin(eps) * sinLon)

        // Hour angle from Greenwich sidereal time; east longitude adds.
        val days = AstroTime.julianDayUtc(epochMillis) - 2451545.0
        val gmst = 280.46061837 + 360.98564736629 * days
        var ha = (gmst + longitude - Math.toDegrees(ra)).mod(360.0)
        if (ha > 180.0) ha -= 360.0
        out.hourAngleDeg = ha

        val phi = latitude * DEG
        out.altitudeDeg = Math.toDegrees(
            asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(ha * DEG))
        )

        // Illumination from elongation; the distance factor shifts it under a percent.
        val sunLon = sunEclipticLongitudeDeg(t)
        val cosPsi = cos(latR) * cos((lon - sunLon) * DEG)
        out.illuminatedFraction = ((1.0 - cosPsi) / 2.0).coerceIn(0.0, 1.0)
    }

    /** The sun's ecliptic longitude, degrees — the same low-precision series SolarTime uses. */
    private fun sunEclipticLongitudeDeg(t: Double): Double {
        val m = 357.5291092 + 35999.0502909 * t
        val c = 1.9148 * sin(m * DEG) + 0.02 * sin(2 * m * DEG) + 0.0003 * sin(3 * m * DEG)
        return (280.4664567 + 36000.76982779 * t + c).mod(360.0)
    }
}
