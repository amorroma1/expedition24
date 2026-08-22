// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AstroTimeTest {

    @Test
    fun `lunar epoch constant matches Armstrong's first step`() {
        assertEquals(
            Instant.parse("1969-07-21T02:56:15Z").toEpochMilli(),
            AstroTime.LUNAR_EPOCH_MILLIS,
        )
    }

    @Test
    fun `julian day of the unix epoch`() {
        assertEquals(2440587.5, AstroTime.julianDayUtc(0L), 1e-9)
    }

    /**
     * Published reference: MSD at 2000-01-06T00:00:00Z is 44795.9998. We compute from JD_UTC rather
     * than JD_TT, which costs about 0.0008 sol, hence the 1e-3 tolerance.
     */
    @Test
    fun `mars sol date matches the published reference`() {
        val t = Instant.parse("2000-01-06T00:00:00Z").toEpochMilli()
        assertEquals(44795.9998, AstroTime.marsSolDate(t), 1e-3)
    }

    /**
     * The brief states the sol two ways — as "24 h 39 min 35.244 s" and as the divisor
     * 1.027491252 — and they are not quite the same number: the divisor works out to
     * 88775.2442 s. The divisor is the authoritative one, so the length is checked to a
     * millisecond rather than exactly.
     */
    @Test
    fun `a sol is 24h 39m 35_244s long`() {
        val t = Instant.parse("2026-08-17T12:00:00Z").toEpochMilli()
        val solMillis = 88_775_244L
        assertEquals(1.0, AstroTime.marsSolDate(t + solMillis) - AstroTime.marsSolDate(t), 1e-8)
        assertEquals(88_775.244, AstroTime.SOL_IN_SECONDS, 1e-3)
    }

    @Test
    fun `mars time stays inside a sol and advances with the sol number`() {
        val t = Instant.parse("2026-08-17T12:00:00Z").toEpochMilli()
        val solMillis = 88_775_244L
        val mtc = AstroTime.marsTimeHours(t)
        assertTrue("MTC was $mtc", mtc >= 0.0 && mtc < 24.0)
        assertEquals(AstroTime.marsSol(t) + 1, AstroTime.marsSol(t + solMillis))
        assertEquals(mtc, AstroTime.marsTimeHours(t + solMillis), 1e-6)
    }

    @Test
    fun `lunar day zero starts at the A_A_ epoch`() {
        val epoch = AstroTime.LUNAR_EPOCH_MILLIS
        assertEquals(0L, AstroTime.lunarDay(epoch))
        assertEquals(0.0, AstroTime.lunarTimeHours(epoch), 1e-9)

        // One second before the epoch we are still in lunar day -1, at the very end of it.
        assertEquals(-1L, AstroTime.lunarDay(epoch - 1000L))
        assertTrue(AstroTime.lunarTimeHours(epoch - 1000L) > 23.9)
    }

    @Test
    fun `a lunar day is one synodic month`() {
        val epoch = AstroTime.LUNAR_EPOCH_MILLIS
        val month = Math.round(AstroTime.SYNODIC_MONTH_DAYS * 86_400_000.0)
        // Pin the rollover to within a millisecond either side rather than trusting the rounding.
        assertEquals(0L, AstroTime.lunarDay(epoch + month - 2))
        assertEquals(1L, AstroTime.lunarDay(epoch + month + 2))
        assertEquals(9L, AstroTime.lunarDay(epoch + month * 10 - 20))
        assertEquals(10L, AstroTime.lunarDay(epoch + month * 10 + 20))
    }

    @Test
    fun `lunar time stays inside a lunar day`() {
        var t = AstroTime.LUNAR_EPOCH_MILLIS
        repeat(50) {
            val ltc = AstroTime.lunarTimeHours(t)
            assertTrue("LTC was $ltc at $t", ltc >= 0.0 && ltc < 24.0)
            t += 37_000_000L
        }
    }

    @Test
    fun `utc second of day tracks midnight and survives the pre-epoch`() {
        assertEquals(0, AstroTime.utcSecondOfDay(Instant.parse("2026-08-17T00:00:00Z").toEpochMilli()))
        assertEquals(
            18 * 3600 + 42 * 60,
            AstroTime.utcSecondOfDay(Instant.parse("2026-08-17T18:42:00Z").toEpochMilli()),
        )
        // Before the epoch the naive division truncates towards zero and lands a day out.
        assertEquals(
            23 * 3600 + 59 * 60 + 59,
            AstroTime.utcSecondOfDay(Instant.parse("1969-12-31T23:59:59Z").toEpochMilli()),
        )
    }

    @Test
    fun `utc calendar date is packed as month and day`() {
        fun at(iso: String) = AstroTime.utcMonthDay(Instant.parse(iso).toEpochMilli())

        assertEquals(1_01, at("1970-01-01T00:00:00Z"))
        assertEquals(12_31, at("1969-12-31T23:59:59Z"))
        assertEquals(8_18, at("2026-08-18T21:24:00Z"))
        assertEquals(3_01, at("2024-03-01T00:00:00Z"))
        // Leap day, and the day either side of it.
        assertEquals(2_29, at("2024-02-29T12:00:00Z"))
        assertEquals(2_28, at("2023-02-28T12:00:00Z"))
        assertEquals(3_01, at("2023-03-01T00:00:00Z"))
        // A century that is not a leap year, and one that is.
        assertEquals(3_01, at("1900-03-01T00:00:00Z"))
        assertEquals(2_29, at("2000-02-29T00:00:00Z"))
        // Just before and just after midnight UTC.
        assertEquals(8_17, at("2026-08-17T23:59:59Z"))
        assertEquals(8_18, at("2026-08-18T00:00:00Z"))
    }

    @Test
    fun `every packed month indexes a name`() {
        for (month in 1..12) {
            val name = com.avdesign.mfd24.text.TextBuf.MONTHS[month - 1]
            assertEquals(3, name.size)
        }
    }

    // --- The 24-hour dial ------------------------------------------------------------------

    @Test
    fun `noon is up, midnight is down, morning is left, evening is right`() {
        assertEquals(0f, AstroTime.hourHandAngle(12.0), 1e-4f)
        assertEquals(90f, AstroTime.hourHandAngle(18.0), 1e-4f)
        assertEquals(180f, AstroTime.hourHandAngle(0.0), 1e-4f)
        assertEquals(270f, AstroTime.hourHandAngle(6.0), 1e-4f)
    }

    @Test
    fun `hour hand makes exactly one turn per day`() {
        assertEquals(AstroTime.hourHandAngle(0.0), AstroTime.hourHandAngle(24.0), 1e-4f)
        // 15 degrees per hour.
        assertEquals(15f, AstroTime.hourHandAngle(13.0) - AstroTime.hourHandAngle(12.0), 1e-4f)
    }

    @Test
    fun `minute hand turns once per hour and the seconds cursor once per minute`() {
        assertEquals(0f, AstroTime.minuteHandAngle(9.0), 1e-4f)
        assertEquals(180f, AstroTime.minuteHandAngle(9.5), 1e-4f)
        assertEquals(90f, AstroTime.minuteHandAngle(9.25), 1e-4f)

        // 9.25 h is 09:15:00, so the cursor sits on the top tick.
        assertEquals(0f, AstroTime.secondFraction(9.25), 1e-5f)
        // Thirty seconds later it is half way round; fifteen seconds in, a quarter.
        assertEquals(0.5f, AstroTime.secondFraction(9.25 + 30.0 / 3600.0), 1e-5f)
        assertEquals(0.25f, AstroTime.secondFraction(9.25 + 15.0 / 3600.0), 1e-5f)
    }
}
