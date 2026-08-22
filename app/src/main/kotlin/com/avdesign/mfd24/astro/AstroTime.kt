// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import kotlin.math.floor

/**
 * Time bases for the three planet modes. Everything here is a pure function of UTC milliseconds so
 * it can be unit-tested on the JVM and called from the render loop without allocating.
 */
object AstroTime {

    const val MILLIS_PER_DAY: Double = 86_400_000.0

    /** Julian Date of the Unix epoch, 1970-01-01T00:00:00Z. */
    const val JD_UNIX_EPOCH: Double = 2440587.5

    // --- Mars ------------------------------------------------------------------------------

    /** Epoch offset of the Mars Sol Date, per the spec. */
    const val MSD_EPOCH_JD: Double = 2405522.0028779

    /** Length of a sol in Earth days: 24 h 39 m 35.244 s. */
    const val SOL_IN_EARTH_DAYS: Double = 1.027491252

    const val SOL_IN_SECONDS: Double = SOL_IN_EARTH_DAYS * 86_400.0

    const val SOL_IN_MILLIS: Double = SOL_IN_EARTH_DAYS * MILLIS_PER_DAY

    // --- Moon ------------------------------------------------------------------------------

    /**
     * Epoch of the A.A. (After Apollo) count: Neil Armstrong's first step,
     * 1969-07-21T02:56:15Z, as Unix milliseconds.
     */
    const val LUNAR_EPOCH_MILLIS: Long = -14_159_025_000L

    /** Synodic month — one lunar day, in Earth days. */
    const val SYNODIC_MONTH_DAYS: Double = 29.530589

    const val LUNAR_DAY_IN_MILLIS: Double = SYNODIC_MONTH_DAYS * MILLIS_PER_DAY

    // --- Common ----------------------------------------------------------------------------

    /**
     * Julian Date from Unix milliseconds.
     *
     * Note: the canonical Mars Sol Date is defined on Terrestrial Time (UTC + ~69 s). The spec
     * calls for JD_UTC, which is what this returns; the resulting MTC runs ~0.7 s behind the
     * canonical value, which is below the resolution of anything on the dial.
     */
    fun julianDayUtc(epochMillis: Long): Double = epochMillis / MILLIS_PER_DAY + JD_UNIX_EPOCH

    /** Mars Sol Date — fractional sols since the 1873 epoch. */
    fun marsSolDate(epochMillis: Long): Double =
        (julianDayUtc(epochMillis) - MSD_EPOCH_JD) / SOL_IN_EARTH_DAYS

    /** Whole sol number, i.e. the `SOL` readout. */
    fun marsSol(epochMillis: Long): Long = floor(marsSolDate(epochMillis)).toLong()

    /** Coordinated Mars Time as hours in `[0, 24)`. */
    fun marsTimeHours(epochMillis: Long): Double {
        val msd = marsSolDate(epochMillis)
        return (msd - floor(msd)) * 24.0
    }

    /** Fractional lunar days since the A.A. epoch. */
    fun lunarDayNumber(epochMillis: Long): Double =
        (epochMillis - LUNAR_EPOCH_MILLIS) / MILLIS_PER_DAY / SYNODIC_MONTH_DAYS

    /** Whole lunar day count, i.e. the `LUNAR DAY` readout. */
    fun lunarDay(epochMillis: Long): Long = floor(lunarDayNumber(epochMillis)).toLong()

    /** Lunar Coordinated Time as hours in `[0, 24)` — a lunar day divided into 24 lunar hours. */
    fun lunarTimeHours(epochMillis: Long): Double {
        val day = lunarDayNumber(epochMillis)
        return (day - floor(day)) * 24.0
    }

    /**
     * Seconds since UTC midnight, in `[0, 86400)`.
     *
     * The readouts split this into hours, minutes and seconds by hand rather than going through a
     * `ZonedDateTime`: it is called every frame by both the interactive and the ambient layer, and
     * neither may allocate.
     */
    fun utcSecondOfDay(epochMillis: Long): Int =
        Math.floorMod(Math.floorDiv(epochMillis, 1000L), 86_400L).toInt()

    /**
     * Hours since local midnight, in `[0, 24)`, for an arbitrary instant and UTC offset.
     *
     * Everything with a position on the dial goes through this: the hour hand, the ends of the
     * duty arc, and the edges of the daylight band. They are all stored as absolute instants and
     * mapped to an angle with whatever offset is current, which is why crossing a time zone slides
     * them all by the same amount and never changes the interval between them.
     */
    fun localHoursOfDay(epochMillis: Long, utcOffsetMillis: Long): Double =
        Math.floorMod(epochMillis + utcOffsetMillis, 86_400_000L) / 3_600_000.0

    /**
     * UTC calendar month and day, packed as `month * 100 + day` with month in `1..12`.
     *
     * Packed into an `Int` rather than returned as a pair because the render loop calls it every
     * frame and must not allocate. The conversion is Howard Hinnant's `civil_from_days`: pure
     * integer arithmetic, no branches on leap years, no `Calendar`.
     */
    fun utcMonthDay(epochMillis: Long): Int {
        // Shift the epoch to 0000-03-01 so leap days land at the end of the cycle.
        val z = Math.floorDiv(epochMillis, 86_400_000L) + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val dayOfEra = z - era * 146_097L
        val yearOfEra =
            (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthPrime = (5L * dayOfYear + 2L) / 153L
        val day = (dayOfYear - (153L * monthPrime + 2L) / 5L + 1L).toInt()
        val month = (monthPrime + if (monthPrime < 10L) 3L else -9L).toInt()
        return month * 100 + day
    }

    /**
     * Angle of the 24-hour hour hand, in degrees clockwise from 12 o'clock.
     *
     * By default the dial has noon up and midnight down, so a plain `hours/24*360` is rotated by a
     * half turn: 12:00 -> 0 deg (up), 18:00 -> 90 deg (right), 00:00 -> 180 deg (down),
     * 06:00 -> 270 deg (left). With [midnightUp] the whole 24-hour axis flips and midnight takes
     * the top instead.
     */
    @JvmOverloads
    fun hourHandAngle(hoursOfDay: Double, midnightUp: Boolean = false): Float {
        var deg = hoursOfDay / 24.0 * 360.0 + if (midnightUp) 0.0 else 180.0
        if (deg >= 360.0) deg -= 360.0
        return deg.toFloat()
    }

    /** Minute hand: one turn per hour of whichever world is selected. */
    fun minuteHandAngle(hoursOfDay: Double): Float {
        val frac = hoursOfDay - floor(hoursOfDay)
        return (frac * 360.0).toFloat()
    }

    /**
     * Position within the current minute of whichever world is selected, in `[0, 1)`. This is what
     * the seconds cursor steps on; there is no second hand.
     */
    fun secondFraction(hoursOfDay: Double): Float {
        val minutes = (hoursOfDay - floor(hoursOfDay)) * 60.0
        return (minutes - floor(minutes)).toFloat()
    }

    /**
     * Apparent solar time as dial hours, from the daylight window's own instants: transit is the
     * midpoint of sunrise and sunset, and the sun stands at `12 h + hour angle`.
     *
     * This is what makes the solar mark a real compass rather than a highlight under the hour
     * hand. The first version placed the mark at its fraction of the daylight band — which is
     * algebraically the *clock* hour, i.e. exactly where the hour hand already is, so it added
     * nothing to the old point-the-hand-at-the-sun trick. Hour angle differs from the clock by
     * the equation of time plus the zone-versus-longitude offset, which is the whole accuracy
     * the mark claims — and it is zone-free, because the sky does not know what time zone it is
     * under. That is also why neither sky mark moves during a zone glide.
     */
    fun apparentSolarDialHours(nowMillis: Long, sunriseMillis: Long, sunsetMillis: Long): Double {
        val transit = (sunriseMillis + sunsetMillis) / 2L
        return 12.0 + (nowMillis - transit) / 3_600_000.0
    }

    // There is no secondHandAngle: seconds are a cursor stepping on the fraction above, and the
    // helper that turned it into degrees outlived the hand it was written for.
}
