// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Sunrise and sunset for one local sol at one place on Mars, in absolute epoch millis. */
class MarsSolarDay {

    @JvmField
    var sunriseMillis: Long = 0L

    @JvmField
    var sunsetMillis: Long = 0L

    /**
     * When the sun crosses −6° on the way up — usable light before the disc itself. Mars dust
     * scatters twilight long and bright, and an operator planning around first light wants the
     * shoulder, not just the edge. Equal to [sunriseMillis] when the sol has no such crossing.
     */
    @JvmField
    var twilightStartMillis: Long = 0L

    /** The −6° crossing on the way down; equal to [sunsetMillis] when there is none. */
    @JvmField
    var twilightEndMillis: Long = 0L

    /** One of [SolarTime.NORMAL], [SolarTime.POLAR_DAY], [SolarTime.POLAR_NIGHT]. */
    @JvmField
    var kind: Int = SolarTime.NORMAL
}

/**
 * Mars solar time after Allison & McEwen (2000), the algorithm NASA's Mars24 clock runs — mean
 * anomaly, fictitious mean sun, the seven-term planetary perturbation series, equation of centre,
 * areocentric solar longitude Ls and the equation of time. [SolarTime] cannot be reused here:
 * its orbital elements are Earth's, written as literals, and Mars's eccentricity (0.0934, five
 * times Earth's) swings the equation of time through ±50 minutes — a sun mark drawn without it
 * would be visibly, misleadingly wrong for most of the Mars year.
 *
 * Everything is TT-based internally (TT = UTC + 69.184 s, exact while the leap-second count
 * stays at 37), because the published chain and its worked examples are; [AstroTime]'s UTC-based
 * MSD stays untouched for the readout that already uses it. Longitudes are **east-positive
 * planetocentric** throughout, the convention the rover coordinates are published in; the AM2000
 * paper writes west, and the sign lives in exactly one place ([lmstHours]) so the two can never
 * be mixed. Pure arithmetic on epoch millis, so it unit-tests on the JVM.
 */
object MarsSolarTime {

    /** One Mars hour — a twenty-fourth of a sol — in Earth milliseconds. */
    const val MARS_HOUR_MILLIS: Double = AstroTime.SOL_IN_MILLIS / 24.0

    /** TT − UTC: 37 leap seconds plus the fixed 32.184 s TAI offset. */
    const val TT_MINUS_UTC_MILLIS: Long = 69_184L

    private const val DEG = Math.PI / 180.0
    private const val J2000_JD = 2451545.0

    /** AM2000's own sol divisor; one digit longer than [AstroTime.SOL_IN_EARTH_DAYS]. */
    private const val SOL_DIVISOR = 1.0274912517

    /** MSD epoch and the small clock correction, straight from the paper's eq. 22. */
    private const val MSD_EPOCH_JD_TT = 2451549.5
    private const val MSD_OFFSET = 44_796.0 - 0.0009626

    /** sin of Mars's obliquity, 25.1919 degrees. */
    private const val SIN_OBLIQUITY = 0.42565

    /**
     * Altitude of the sun's centre at rise and set: half the solar disc as seen from Mars
     * (~0.21 degrees) and no refraction term — at 6 mbar the bend is ~0.006 degrees, three
     * orders below Earth's, and pretending otherwise would be false precision.
     */
    private const val HORIZON_DEG = -0.21

    /** The twilight shoulder: sun centre at −6°, the civil convention carried to Mars. */
    private const val TWILIGHT_HORIZON_DEG = -6.0

    /** The seven-term planetary perturbation series (AM2000 table 5): amplitude in degrees. */
    private val PBS_A = doubleArrayOf(0.0071, 0.0057, 0.0039, 0.0037, 0.0021, 0.0020, 0.0018)

    /** Periods in Julian years. */
    private val PBS_TAU = doubleArrayOf(2.2353, 2.7543, 1.0961, 15.8095, 2.4386, 32.8493, 13.1064)

    /** Phases in degrees. */
    private val PBS_PHI = doubleArrayOf(49.409, 168.173, 191.837, 21.736, 15.704, 95.528, 49.095)

    private fun jdTt(epochMillis: Long): Double =
        AstroTime.julianDayUtc(epochMillis + TT_MINUS_UTC_MILLIS)

    /** Mars Sol Date on the TT clock — the AM2000 form, ~0.7 s ahead of [AstroTime.marsSolDate]. */
    fun marsSolDateTt(epochMillis: Long): Double =
        (jdTt(epochMillis) - MSD_EPOCH_JD_TT) / SOL_DIVISOR + MSD_OFFSET

    /** Mars mean anomaly in degrees, normalised to [0, 360). */
    fun meanAnomalyDeg(epochMillis: Long): Double =
        (19.3871 + 0.52402073 * (jdTt(epochMillis) - J2000_JD)).mod(360.0)

    private fun pbsDeg(dtJ2000: Double): Double {
        var sum = 0.0
        for (i in PBS_A.indices) {
            sum += PBS_A[i] * cos(((360.0 / 365.25) * dtJ2000 / PBS_TAU[i] + PBS_PHI[i]) * DEG)
        }
        return sum
    }

    private fun equationOfCentreDeg(dtJ2000: Double): Double {
        val m = (19.3871 + 0.52402073 * dtJ2000) * DEG
        return (10.691 + 3.0e-7 * dtJ2000) * sin(m) +
            0.623 * sin(2.0 * m) +
            0.050 * sin(3.0 * m) +
            0.005 * sin(4.0 * m) +
            0.0005 * sin(5.0 * m) +
            pbsDeg(dtJ2000)
    }

    /** Areocentric solar longitude in degrees: 0 northern spring equinox, 90 northern summer. */
    fun ls(epochMillis: Long): Double {
        val dt = jdTt(epochMillis) - J2000_JD
        return (270.3871 + 0.524038496 * dt + equationOfCentreDeg(dt)).mod(360.0)
    }

    /**
     * Equation of time in Mars hours: LTST − LMST. Positive when the true sun runs ahead of the
     * mean one; swings roughly −0.86 h to +0.68 h across the Mars year.
     */
    fun equationOfTimeHours(epochMillis: Long): Double {
        val dt = jdTt(epochMillis) - J2000_JD
        val centre = equationOfCentreDeg(dt)
        val lsRad = (270.3871 + 0.524038496 * dt + centre).mod(360.0) * DEG
        val eotDeg = 2.861 * sin(2.0 * lsRad) -
            0.071 * sin(4.0 * lsRad) +
            0.002 * sin(6.0 * lsRad) -
            centre
        return eotDeg / 15.0
    }

    /** Coordinated Mars Time — mean solar time at the airy meridian — in [0, 24). */
    fun mtcHours(epochMillis: Long): Double = 24.0 * marsSolDateTt(epochMillis).mod(1.0)

    /**
     * Local Mean Solar Time at [lonEastDeg], in [0, 24). This is the render path — the hour hand,
     * the duty arc and the comm windows all map instants through it — so it stays allocation-free.
     */
    fun lmstHours(epochMillis: Long, lonEastDeg: Double): Double =
        (mtcHours(epochMillis) + lonEastDeg / 15.0).mod(24.0)

    /** Local True Solar Time: where the sun actually is, and therefore where the mark is drawn. */
    fun ltstHours(epochMillis: Long, lonEastDeg: Double): Double =
        (lmstHours(epochMillis, lonEastDeg) + equationOfTimeHours(epochMillis)).mod(24.0)

    /** Declination of the sun in degrees; the 0.25 sin Ls term is AM2000's areographic touch-up. */
    fun solarDeclinationDeg(epochMillis: Long): Double {
        val lsRad = ls(epochMillis) * DEG
        return asin(SIN_OBLIQUITY * sin(lsRad)) / DEG + 0.25 * sin(lsRad)
    }

    /**
     * The local sol count at [lonEastDeg]: MSD shifted by longitude so its floor increments at
     * the site's own mean midnight. Mission sols subtract a landing value from this.
     */
    fun localSolDate(epochMillis: Long, lonEastDeg: Double): Double =
        marsSolDateTt(epochMillis) + lonEastDeg / 360.0

    /**
     * Fills [out] with the sunrise and sunset of the local sol whose noon lies nearest
     * [epochMillis] at ([latDeg], [lonEastDeg]) — the same round-to-noon anchoring
     * [SolarTime.compute] uses, so sunrise is always before sunset and the pair brackets the
     * noon under way, which is the shape [com.avdesign.mfd24.render.AmbientAuto] and the
     * daylight band both assume.
     */
    fun computeDay(epochMillis: Long, latDeg: Double, lonEastDeg: Double, out: MarsSolarDay) {
        val declination = solarDeclinationDeg(epochMillis) * DEG
        val latitude = latDeg * DEG

        val cosHourAngle = (sin(HORIZON_DEG * DEG) - sin(latitude) * sin(declination)) /
            (cos(latitude) * cos(declination))

        if (cosHourAngle < -1.0) {
            out.kind = SolarTime.POLAR_DAY
            out.sunriseMillis = 0L
            out.sunsetMillis = 0L
            out.twilightStartMillis = 0L
            out.twilightEndMillis = 0L
            return
        }
        if (cosHourAngle > 1.0) {
            out.kind = SolarTime.POLAR_NIGHT
            out.sunriseMillis = 0L
            out.sunsetMillis = 0L
            out.twilightStartMillis = 0L
            out.twilightEndMillis = 0L
            return
        }

        // Half the daylight arc, in Mars hours either side of true noon; the equation of time
        // carries both edges from true-sun hours back to the mean clock the dial runs on. One
        // EOT for both edges: it drifts under half a minute per sol, far inside the horizon
        // constant's own uncertainty.
        val halfDayHours = Math.toDegrees(acos(cosHourAngle)) / 15.0
        val eotHours = equationOfTimeHours(epochMillis)
        val sunriseLmst = 12.0 - halfDayHours - eotHours
        val sunsetLmst = 12.0 + halfDayHours - eotHours

        // Anchor on the local sol whose mean noon is nearest now, then walk out to the edges.
        var toNoonHours = 12.0 - lmstHours(epochMillis, lonEastDeg)
        if (toNoonHours > 12.0) toNoonHours -= 24.0
        if (toNoonHours < -12.0) toNoonHours += 24.0
        val noonMillis = epochMillis + Math.round(toNoonHours * MARS_HOUR_MILLIS)

        out.kind = SolarTime.NORMAL
        out.sunriseMillis = noonMillis + Math.round((sunriseLmst - 12.0) * MARS_HOUR_MILLIS)
        out.sunsetMillis = noonMillis + Math.round((sunsetLmst - 12.0) * MARS_HOUR_MILLIS)

        // The twilight shoulder, by the same arithmetic six degrees lower. A sol that never
        // crosses −6° (high latitudes near solstice) collapses the shoulder onto the day's own
        // edges rather than inventing one.
        val cosTwilight = (sin(TWILIGHT_HORIZON_DEG * DEG) - sin(latitude) * sin(declination)) /
            (cos(latitude) * cos(declination))
        if (cosTwilight < -1.0 || cosTwilight > 1.0) {
            out.twilightStartMillis = out.sunriseMillis
            out.twilightEndMillis = out.sunsetMillis
            return
        }
        val twilightHalfHours = Math.toDegrees(acos(cosTwilight)) / 15.0
        out.twilightStartMillis =
            noonMillis + Math.round((-twilightHalfHours - eotHours) * MARS_HOUR_MILLIS)
        out.twilightEndMillis =
            noonMillis + Math.round((twilightHalfHours - eotHours) * MARS_HOUR_MILLIS)
    }
}
