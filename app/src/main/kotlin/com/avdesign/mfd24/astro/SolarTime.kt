// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import kotlin.math.asin
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Sunrise and sunset for one day at one place. */
class SolarDay {

    @JvmField
    var sunriseMillis: Long = 0L

    @JvmField
    var sunsetMillis: Long = 0L

    /** One of [SolarTime.NORMAL], [SolarTime.POLAR_DAY], [SolarTime.POLAR_NIGHT]. */
    @JvmField
    var kind: Int = SolarTime.NORMAL
}

/**
 * Sunrise and sunset from the standard low-precision solar position model — the same one the NOAA
 * calculator publishes, good to well under a minute anywhere outside the polar circles, which is
 * more than a dial band needs.
 *
 * Pure arithmetic on UTC milliseconds, so it unit-tests on the JVM. It is evaluated when the
 * position or the date changes, never per frame.
 */
object SolarTime {

    const val NORMAL: Int = 0

    /** The sun never sets: the whole dial is daylight. */
    const val POLAR_DAY: Int = 1

    /** The sun never rises: no daylight band at all. */
    const val POLAR_NIGHT: Int = 2

    private const val DEG = Math.PI / 180.0

    /** Obliquity of the ecliptic. */
    private const val OBLIQUITY_DEG = 23.4397

    /**
     * Altitude of the sun's centre at the moment its upper limb touches the horizon: half a degree
     * of solar disc plus about a third of a degree of atmospheric refraction.
     */
    private const val HORIZON_DEG = -0.833

    /**
     * Civil twilight: the sun six degrees under, and the last of the light a person can read a
     * newspaper by. This is the edge that decides whether a walk home is in daylight.
     */
    const val CIVIL_DEG = -6.0

    /**
     * Astronomical twilight: eighteen degrees under, and the moment the sky is as dark as it
     * will get — the far edge of dawn, the hour a photographer or an observer plans around.
     */
    const val ASTRONOMICAL_DEG = -18.0

    private const val J2000 = 2451545.0
    private const val MILLIS_PER_DAY = 86_400_000.0

    /**
     * Fills [out] with the sunrise and sunset bracketing the solar day that contains [epochMillis]
     * at ([latitude], [longitude]), longitude positive east.
     */
    fun compute(epochMillis: Long, latitude: Double, longitude: Double, out: SolarDay) =
        compute(epochMillis, latitude, longitude, HORIZON_DEG, out)

    /**
     * The same day, for a sun at an arbitrary depth: [HORIZON_DEG] for sunrise and sunset,
     * [CIVIL_DEG] and [ASTRONOMICAL_DEG] for the two edges of twilight. One function rather than
     * three, so the dawn a face draws and the dawn it says out loud cannot come from different
     * arithmetic.
     */
    fun compute(
        epochMillis: Long,
        latitude: Double,
        longitude: Double,
        altitudeDeg: Double,
        out: SolarDay,
    ) {
        val julianDay = AstroTime.julianDayUtc(epochMillis)

        // Whole days since J2000, shifted so the day is centred on *local* solar noon rather than
        // on midnight UTC. Rounded to nearest, not floored: flooring picks the solar day whose noon
        // is at or before the instant asked about, which slips a whole day back when the call lands
        // near midnight UTC, and slips a day at any hour once the longitude is far enough east.
        val n = Math.round(julianDay - J2000 + 0.0008 + longitude / 360.0).toDouble()
        val meanSolarNoon = n - longitude / 360.0

        val meanAnomaly = (357.5291 + 0.98560028 * meanSolarNoon).mod(360.0)
        val centre = 1.9148 * sin(meanAnomaly * DEG) +
            0.0200 * sin(2.0 * meanAnomaly * DEG) +
            0.0003 * sin(3.0 * meanAnomaly * DEG)
        val eclipticLongitude = (meanAnomaly + centre + 180.0 + 102.9372).mod(360.0)

        val transit = J2000 + meanSolarNoon +
            0.0053 * sin(meanAnomaly * DEG) -
            0.0069 * sin(2.0 * eclipticLongitude * DEG)

        val declination = asin(sin(eclipticLongitude * DEG) * sin(OBLIQUITY_DEG * DEG))

        val cosHourAngle = (sin(altitudeDeg * DEG) - sin(latitude * DEG) * sin(declination)) /
            (cos(latitude * DEG) * cos(declination))

        if (cosHourAngle < -1.0) {
            out.kind = POLAR_DAY
            out.sunriseMillis = 0L
            out.sunsetMillis = 0L
            return
        }
        if (cosHourAngle > 1.0) {
            out.kind = POLAR_NIGHT
            out.sunriseMillis = 0L
            out.sunsetMillis = 0L
            return
        }

        val hourAngleDeg = Math.toDegrees(acos(cosHourAngle))
        out.kind = NORMAL
        out.sunriseMillis = julianDayToMillis(transit - hourAngleDeg / 360.0)
        out.sunsetMillis = julianDayToMillis(transit + hourAngleDeg / 360.0)
    }

    fun julianDayToMillis(julianDay: Double): Long =
        Math.round((julianDay - AstroTime.JD_UNIX_EPOCH) * MILLIS_PER_DAY)

    // Day length in hours lives in SolarTimeTest, which is the only caller: it is how the model is
    // checked against published figures, not something the dial ever prints.
}
