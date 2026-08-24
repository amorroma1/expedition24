// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Direct-to-Earth visibility windows for one sol at one Mars site, in absolute epoch millis. */
class DteWindows {

    @JvmField
    val startMillis = LongArray(MAX)

    @JvmField
    val endMillis = LongArray(MAX)

    @JvmField
    var count: Int = 0

    /** One of [EarthSky.NORMAL], [EarthSky.ALWAYS_UP], [EarthSky.ALWAYS_DOWN]. */
    @JvmField
    var kind: Int = EarthSky.NORMAL

    companion object {
        /**
         * Earth's diurnal track gives one rise and one set per sol; four slots cover the
         * scan-boundary split and leave room for the pathological rather than trusting it
         * cannot happen.
         */
        const val MAX = 4
    }
}

/**
 * Where Earth stands in a Mars site's sky, computed offline — the inner comm line must not
 * depend on a download the outer one needs, or one network failure would blank both.
 *
 * Heliocentric positions come from the Standish approximate Keplerian elements (JPL, valid
 * 1800–2050) for the Earth–Moon barycentre and Mars; the barycentre-vs-Earth error is under
 * 5000 km, invisible from the next planet over. The Mars–Earth vector is rotated into Mars's
 * equatorial frame from the IAU pole, and Earth's local hour angle is anchored **through the
 * sun**: `H_earth = H_sun + (α_sun − α_earth)`, with `H_sun` read off [MarsSolarTime.ltstHours].
 * Anchoring through the sun cancels any zero-point error in the frame — the same pipeline
 * places both bodies, so the difference is all that matters — and it guarantees the windows
 * agree with the clock the dial runs, because they are computed *from* it. Window edges are
 * good to a couple of real minutes, a fraction of one dial tick.
 */
object EarthSky {

    const val NORMAL: Int = 0

    /** Earth never sets below the threshold this sol: the whole dial is a window. */
    const val ALWAYS_UP: Int = 1

    /** Earth never rises above it: no window at all. */
    const val ALWAYS_DOWN: Int = 2

    /**
     * The elevation a link is worth listing at, shared by the direct-to-Earth and the relay
     * windows so the two lines never disagree about what "visible" means.
     */
    const val MIN_ELEVATION_DEG = 10.0

    /**
     * Solar conjunction: below this Sun-Earth separation, seen from Mars, the missions stand
     * their commanding down — the signal grazes the corona and arrives as noise. Two degrees is
     * the moratorium the relay programme itself uses.
     */
    const val CONJUNCTION_DEG = 2.0

    private const val AU_METERS = 1.495978707e11
    private const val LIGHT_SPEED = 299_792_458.0

    private const val DEG = Math.PI / 180.0
    private const val J2000_JD = 2451545.0
    private const val DAYS_PER_CENTURY = 36_525.0

    /** Obliquity of Earth's ecliptic at J2000, for the ecliptic-to-equatorial rotation. */
    private const val OBLIQUITY_DEG = 23.43928

    /** Mars's north pole, J2000 equatorial (IAU WG on cartographic coordinates). */
    private const val POLE_RA_DEG = 317.68143
    private const val POLE_DEC_DEG = 52.88650

    // Standish elements: value at J2000 and rate per Julian century, in AU and degrees.
    // Order: a, e, i, L, longitude of perihelion, longitude of ascending node.
    private val EARTH_ELEMENTS = doubleArrayOf(
        1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0,
    )
    private val EARTH_RATES = doubleArrayOf(
        0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0,
    )
    private val MARS_ELEMENTS = doubleArrayOf(
        1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891,
    )
    private val MARS_RATES = doubleArrayOf(
        0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343,
    )

    /** How finely the sol is sampled before bisection: 5 Mars minutes finds every real pass. */
    private const val SCAN_STEPS = 288

    /**
     * Elevation of Earth above the horizon at ([latDeg], [lonEastDeg]), in degrees.
     * Allocation-free after JIT (all vector work is in scalars), though nothing calls it per
     * frame — windows are computed on refresh and read as instants.
     */
    fun earthAltitudeDeg(epochMillis: Long, latDeg: Double, lonEastDeg: Double): Double =
        bodyAltitudeDeg(epochMillis, latDeg, lonEastDeg, earth = true)

    /**
     * The same pipeline pointed at the sun. Exists for the tests: at LTST noon this must peak
     * on the meridian, which checks the frame, the anchor and the longitude convention in one
     * assertion — a wrong sign here would move both bodies together and hide from any test
     * that only looked at Earth.
     */
    fun sunAltitudeDeg(epochMillis: Long, latDeg: Double, lonEastDeg: Double): Double =
        bodyAltitudeDeg(epochMillis, latDeg, lonEastDeg, earth = false)

    /**
     * One-way light time Mars ↔ Earth, in seconds — the operator's ever-present constant, and
     * the one number on this face that says how far from home the instrument is. Scalar work
     * only; cheap enough for the frame loop, like the moon model on the Earth face.
     */
    fun oneWayLightSeconds(epochMillis: Long): Double {
        val centuries = centuriesTt(epochMillis)
        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        heliocentric(MARS_ELEMENTS, MARS_RATES, centuries) { x, y, z -> mx = x; my = y; mz = z }
        var dx = 0.0
        var dy = 0.0
        var dz = 0.0
        heliocentric(EARTH_ELEMENTS, EARTH_RATES, centuries) { x, y, z ->
            dx = x - mx; dy = y - my; dz = z - mz
        }
        return sqrt(dx * dx + dy * dy + dz * dz) * AU_METERS / LIGHT_SPEED
    }

    /**
     * The Sun–Earth separation seen from Mars, in degrees. Under [CONJUNCTION_DEG] the
     * direct-to-Earth link is inside the corona and honestly unusable, whatever the geometry
     * of the horizon says.
     */
    fun sunEarthAngleDeg(epochMillis: Long): Double {
        val centuries = centuriesTt(epochMillis)
        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        heliocentric(MARS_ELEMENTS, MARS_RATES, centuries) { x, y, z -> mx = x; my = y; mz = z }
        var ex = 0.0
        var ey = 0.0
        var ez = 0.0
        heliocentric(EARTH_ELEMENTS, EARTH_RATES, centuries) { x, y, z ->
            ex = x - mx; ey = y - my; ez = z - mz
        }
        val sunNorm = sqrt(mx * mx + my * my + mz * mz)
        val earthNorm = sqrt(ex * ex + ey * ey + ez * ez)
        val dot = (-mx * ex - my * ey - mz * ez) / (sunNorm * earthNorm)
        return Math.toDegrees(kotlin.math.acos(dot.coerceIn(-1.0, 1.0)))
    }

    private fun centuriesTt(epochMillis: Long): Double =
        (AstroTime.julianDayUtc(epochMillis + MarsSolarTime.TT_MINUS_UTC_MILLIS) - J2000_JD) /
            DAYS_PER_CENTURY

    /**
     * Fills [out] with the intervals, inside one sol centred on [nowMillis], during which Earth
     * stands above [minElevationDeg] at the site. The scan boundary sits half a sol from now:
     * its two ends land on the same dial angle, so a window split across it re-joins seamlessly
     * where it is drawn.
     */
    fun computeWindows(
        nowMillis: Long,
        latDeg: Double,
        lonEastDeg: Double,
        minElevationDeg: Double,
        out: DteWindows,
    ) {
        val halfSol = Math.round(AstroTime.SOL_IN_MILLIS / 2.0)
        val from = nowMillis - halfSol
        val stepMillis = AstroTime.SOL_IN_MILLIS / SCAN_STEPS

        out.count = 0
        var above = earthAltitudeDeg(from, latDeg, lonEastDeg) > minElevationDeg
        var anyAbove = above
        var anyBelow = !above
        var openStart = if (above) from else 0L

        var i = 1
        while (i <= SCAN_STEPS) {
            val t = from + Math.round(stepMillis * i)
            val nowAbove = earthAltitudeDeg(t, latDeg, lonEastDeg) > minElevationDeg
            if (nowAbove) anyAbove = true else anyBelow = true
            if (nowAbove != above) {
                val crossing = refineCrossing(
                    from + Math.round(stepMillis * (i - 1)), t,
                    latDeg, lonEastDeg, minElevationDeg, rising = nowAbove,
                )
                if (nowAbove) {
                    openStart = crossing
                } else if (out.count < DteWindows.MAX) {
                    out.startMillis[out.count] = openStart
                    out.endMillis[out.count] = crossing
                    out.count++
                }
                above = nowAbove
            }
            i++
        }
        if (above && out.count < DteWindows.MAX) {
            out.startMillis[out.count] = openStart
            out.endMillis[out.count] = nowMillis + halfSol
            out.count++
        }

        out.kind = when {
            !anyBelow -> ALWAYS_UP
            !anyAbove -> ALWAYS_DOWN
            else -> NORMAL
        }
    }

    /** Bisects a threshold crossing found between two samples down to a few seconds. */
    private fun refineCrossing(
        loMillis: Long,
        hiMillis: Long,
        latDeg: Double,
        lonEastDeg: Double,
        minElevationDeg: Double,
        rising: Boolean,
    ): Long {
        var lo = loMillis
        var hi = hiMillis
        while (hi - lo > 5_000L) {
            val mid = (lo + hi) / 2
            val above = earthAltitudeDeg(mid, latDeg, lonEastDeg) > minElevationDeg
            if (above == rising) hi = mid else lo = mid
        }
        return (lo + hi) / 2
    }

    private fun bodyAltitudeDeg(
        epochMillis: Long,
        latDeg: Double,
        lonEastDeg: Double,
        earth: Boolean,
    ): Double {
        val centuries =
            (AstroTime.julianDayUtc(epochMillis + MarsSolarTime.TT_MINUS_UTC_MILLIS) - J2000_JD) /
                DAYS_PER_CENTURY

        // Heliocentric ecliptic positions; the target vector is Mars -> body.
        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        heliocentric(MARS_ELEMENTS, MARS_RATES, centuries) { x, y, z -> mx = x; my = y; mz = z }
        var vx = -mx
        var vy = -my
        var vz = -mz
        if (earth) {
            heliocentric(EARTH_ELEMENTS, EARTH_RATES, centuries) { x, y, z ->
                vx = x - mx; vy = y - my; vz = z - mz
            }
        }

        // Ecliptic -> Earth-equatorial, where the Mars pole is published.
        val cosE = cos(OBLIQUITY_DEG * DEG)
        val sinE = sin(OBLIQUITY_DEG * DEG)
        val ex = vx
        val ey = vy * cosE - vz * sinE
        val ez = vy * sinE + vz * cosE

        // Mars-equatorial basis from the pole: x toward the ascending node of Mars's equator
        // on Earth's, y completing the right-handed set.
        val np = cos(POLE_DEC_DEG * DEG)
        val nx = np * cos(POLE_RA_DEG * DEG)
        val ny = np * sin(POLE_RA_DEG * DEG)
        val nz = sin(POLE_DEC_DEG * DEG)
        val nodeNorm = sqrt(nx * nx + ny * ny)
        val bx = -ny / nodeNorm
        val by = nx / nodeNorm
        // b_z = 0 by construction; y_m = n x b.
        val cx = ny * 0.0 - nz * by
        val cy = nz * bx - nx * 0.0
        val cz = nx * by - ny * bx

        val norm = sqrt(ex * ex + ey * ey + ez * ez)
        val ux = ex / norm
        val uy = ey / norm
        val uz = ez / norm

        val declination = asin(ux * nx + uy * ny + uz * nz)
        val rightAscension = atan2(
            ux * cx + uy * cy + uz * cz,
            ux * bx + uy * by + uz * 0.0,
        )

        // The sun through the identical pipeline: the anchor that cancels the frame zero point.
        val sn = sqrt(mx * mx + my * my + mz * mz)
        val sxE = -mx
        val syE = (-my) * cosE - (-mz) * sinE
        val szE = (-my) * sinE + (-mz) * cosE
        val sux = sxE / sn
        val suy = syE / sn
        val suz = szE / sn
        val sunRa = atan2(
            sux * cx + suy * cy + suz * cz,
            sux * bx + suy * by + suz * 0.0,
        )

        val sunHourAngleDeg = (MarsSolarTime.ltstHours(epochMillis, lonEastDeg) - 12.0) * 15.0
        val hourAngle = sunHourAngleDeg * DEG + (sunRa - rightAscension)

        val latitude = latDeg * DEG
        return asin(
            sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(hourAngle),
        ) / DEG
    }

    /** Solves the two-body position for one Standish row; feeds x, y, z in AU to [take]. */
    private inline fun heliocentric(
        elements: DoubleArray,
        rates: DoubleArray,
        centuries: Double,
        take: (Double, Double, Double) -> Unit,
    ) {
        val a = elements[0] + rates[0] * centuries
        val e = elements[1] + rates[1] * centuries
        val inclination = (elements[2] + rates[2] * centuries) * DEG
        val meanLongitude = elements[3] + rates[3] * centuries
        val periLongitude = elements[4] + rates[4] * centuries
        val nodeLongitude = elements[5] + rates[5] * centuries

        var meanAnomaly = (meanLongitude - periLongitude).mod(360.0)
        if (meanAnomaly > 180.0) meanAnomaly -= 360.0
        val argPeri = (periLongitude - nodeLongitude) * DEG
        val node = nodeLongitude * DEG

        // Kepler by Newton; converges in a handful of steps at these eccentricities.
        val m = meanAnomaly * DEG
        var eccentric = m + e * sin(m)
        var iterations = 0
        while (iterations < 8) {
            val delta = (eccentric - e * sin(eccentric) - m) / (1.0 - e * cos(eccentric))
            eccentric -= delta
            if (Math.abs(delta) < 1e-9) break
            iterations++
        }

        val xOrb = a * (cos(eccentric) - e)
        val yOrb = a * sqrt(1.0 - e * e) * sin(eccentric)

        val cosW = cos(argPeri)
        val sinW = sin(argPeri)
        val cosO = cos(node)
        val sinO = sin(node)
        val cosI = cos(inclination)
        val sinI = sin(inclination)

        take(
            (cosW * cosO - sinW * sinO * cosI) * xOrb + (-sinW * cosO - cosW * sinO * cosI) * yOrb,
            (cosW * sinO + sinW * cosO * cosI) * xOrb + (-sinW * sinO + cosW * cosO * cosI) * yOrb,
            sinW * sinI * xOrb + cosW * sinI * yOrb,
        )
    }
}
