// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MarsSolarTimeTest {

    /**
     * The AM2000 chain and [AstroTime]'s UTC-based MSD are two routes to the same clock, and
     * they are a known, documented distance apart: [AstroTime] computes from JD_UTC with its
     * own epoch, which its test states costs about 0.0008 sol — around 69 Mars-seconds, with
     * AM2000 ahead. This pins that constant gap: if it ever grows or changes sign, one of the
     * two chains has been edited without the other.
     */
    @Test
    fun `mtc sits the documented 69 seconds ahead of the readout's own mars time`() {
        val instants = listOf(
            "2000-01-06T00:00:00Z", "2012-08-06T05:17:57Z",
            "2021-02-18T20:55:00Z", "2026-08-23T12:00:00Z",
        )
        for (iso in instants) {
            val t = Instant.parse(iso).toEpochMilli()
            var diff = MarsSolarTime.mtcHours(t) - AstroTime.marsTimeHours(t)
            if (diff > 12.0) diff -= 24.0
            if (diff < -12.0) diff += 24.0
            val seconds = diff * 3600.0
            assertTrue("MTC gap was $seconds Mars-seconds at $iso", seconds > 60.0 && seconds < 75.0)
        }
    }

    /** Mars year 36 began 2021-02-07: Ls crosses zero within that day's uncertainty. */
    @Test
    fun `ls is zero at the start of mars year 36`() {
        val t = Instant.parse("2021-02-07T12:00:00Z").toEpochMilli()
        val ls = MarsSolarTime.ls(t)
        val fromZero = Math.min(ls, 360.0 - ls)
        assertTrue("Ls was $ls", fromZero < 0.6)
    }

    /**
     * AM2000's own worked example: Mars Pathfinder landed 1997-07-04T16:56:55Z at 33.52 W and
     * the paper puts the local true solar time at about 02:56. The band here is ±3 minutes,
     * covering the last digit of the site longitude.
     */
    @Test
    fun `pathfinder landed at about 02_56 local true solar time`() {
        val t = Instant.parse("1997-07-04T16:56:55Z").toEpochMilli()
        val ltst = MarsSolarTime.ltstHours(t, 360.0 - 33.52)
        assertEquals(2.0 + 56.0 / 60.0, ltst, 3.0 / 60.0)
    }

    /** LMST is MTC shifted by longitude and nothing else — the sign convention, pinned. */
    @Test
    fun `lmst leads mtc by the east longitude`() {
        val t = Instant.parse("2026-08-23T12:00:00Z").toEpochMilli()
        val mtc = MarsSolarTime.mtcHours(t)
        assertEquals(
            (mtc + 77.4508 / 15.0).mod(24.0),
            MarsSolarTime.lmstHours(t, Rovers.LON_EAST[Rovers.PERSEVERANCE]),
            1e-9,
        )
        assertEquals(
            (mtc + 137.4417 / 15.0).mod(24.0),
            MarsSolarTime.lmstHours(t, Rovers.LON_EAST[Rovers.CURIOSITY]),
            1e-9,
        )
    }

    /** The sun mark is the mean clock plus the equation of time, exactly — nothing else. */
    @Test
    fun `ltst differs from lmst by the equation of time`() {
        val t = Instant.parse("2027-03-15T06:30:00Z").toEpochMilli()
        val lon = Rovers.LON_EAST[Rovers.CURIOSITY]
        val expected = (MarsSolarTime.lmstHours(t, lon) + MarsSolarTime.equationOfTimeHours(t)).mod(24.0)
        assertEquals(expected, MarsSolarTime.ltstHours(t, lon), 1e-9)
    }

    /**
     * The equation of time is where Mars's eccentricity shows: it must actually reach beyond
     * ±0.5 h across a Mars year — a chain that stays small has lost the equation of centre —
     * and it must never leave the published ±0.9 h envelope.
     */
    @Test
    fun `equation of time swings with the eccentricity and stays in its envelope`() {
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        val start = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val step = 5L * 86_400_000L
        for (i in 0 until 140) { // ~700 days, one Mars year
            val eot = MarsSolarTime.equationOfTimeHours(start + i * step)
            if (eot < min) min = eot
            if (eot > max) max = eot
            assertTrue("EOT out of envelope: $eot h", Math.abs(eot) < 0.9)
        }
        assertTrue("EOT max was $max h", max > 0.5)
        assertTrue("EOT min was $min h", min < -0.7)
    }

    @Test
    fun `computeDay brackets the nearest local noon at both rover sites`() {
        val out = MarsSolarDay()
        val instants = listOf(
            "2026-08-23T00:00:00Z", "2026-08-23T09:17:00Z", "2026-11-01T22:45:00Z",
            "2027-02-14T04:00:00Z", "2027-06-30T16:20:00Z",
        )
        for (rover in intArrayOf(Rovers.PERSEVERANCE, Rovers.CURIOSITY)) {
            val lat = Rovers.LAT[rover]
            val lon = Rovers.LON_EAST[rover]
            for (iso in instants) {
                val t = Instant.parse(iso).toEpochMilli()
                MarsSolarTime.computeDay(t, lat, lon, out)
                assertEquals(SolarTime.NORMAL, out.kind)
                assertTrue("sunrise after sunset at $iso", out.sunriseMillis < out.sunsetMillis)

                // Both edges belong to the sol under way.
                val halfSol = AstroTime.SOL_IN_MILLIS.toLong() / 2 + 1
                assertTrue("sunrise too far from now at $iso", Math.abs(out.sunriseMillis - t) <= AstroTime.SOL_IN_MILLIS.toLong())
                assertTrue("sunset too far from now at $iso", Math.abs(out.sunsetMillis - t) <= AstroTime.SOL_IN_MILLIS.toLong())
                val noon = (out.sunriseMillis + out.sunsetMillis) / 2
                assertTrue("noon not nearest now at $iso", Math.abs(noon - t) <= halfSol)

                // Day length at these tropical latitudes: 10 to 14 Mars hours.
                val dayHours = (out.sunsetMillis - out.sunriseMillis) / MarsSolarTime.MARS_HOUR_MILLIS
                assertTrue("day length $dayHours h at $iso", dayHours > 10.0 && dayHours < 14.0)

                // The midpoint of a true-solar day is true noon: LTST 12, within a minute or two
                // of equation-of-time drift across the sol.
                assertEquals(12.0, MarsSolarTime.ltstHours(noon, lon), 4.0 / 60.0)
            }
        }
    }

    /**
     * The twilight shoulder brackets the day: −6° comes before −0.21° in the morning and after
     * it in the evening, by twenty to fifty Mars minutes at these tropical latitudes.
     */
    @Test
    fun `twilight brackets the day by a plausible shoulder`() {
        val out = MarsSolarDay()
        for (rover in intArrayOf(Rovers.PERSEVERANCE, Rovers.CURIOSITY)) {
            val t = Instant.parse("2026-08-23T12:00:00Z").toEpochMilli()
            MarsSolarTime.computeDay(t, Rovers.LAT[rover], Rovers.LON_EAST[rover], out)
            assertTrue(out.twilightStartMillis < out.sunriseMillis)
            assertTrue(out.twilightEndMillis > out.sunsetMillis)
            val dawn = (out.sunriseMillis - out.twilightStartMillis) / 60_000.0
            val dusk = (out.twilightEndMillis - out.sunsetMillis) / 60_000.0
            assertTrue("dawn shoulder was $dawn min", dawn > 15.0 && dawn < 60.0)
            assertTrue("dusk shoulder was $dusk min", dusk > 15.0 && dusk < 60.0)
        }
    }

    /** Polar clamps mirror [SolarTime]: pick an instant with the sun well north, read both poles. */
    @Test
    fun `polar day and night clamp like the earth model`() {
        // Scan for a solar declination beyond +15 degrees (northern summer), then assert.
        var t = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val step = 10L * 86_400_000L
        while (MarsSolarTime.solarDeclinationDeg(t) < 15.0) t += step
        val out = MarsSolarDay()
        MarsSolarTime.computeDay(t, 85.0, 0.0, out)
        assertEquals(SolarTime.POLAR_DAY, out.kind)
        MarsSolarTime.computeDay(t, -85.0, 0.0, out)
        assertEquals(SolarTime.POLAR_NIGHT, out.kind)
    }

    /** A sol later, the mean clock reads the same to well under a Mars second. */
    @Test
    fun `lmst is periodic in the sol`() {
        val t = Instant.parse("2026-08-23T12:00:00Z").toEpochMilli()
        val solMillis = 88_775_244L
        assertEquals(
            MarsSolarTime.lmstHours(t, 77.4508),
            MarsSolarTime.lmstHours(t + solMillis, 77.4508),
            1e-4,
        )
    }
}
