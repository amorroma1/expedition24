// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Pins [Rovers.SOL0_LSD] against the missions' own published sol counts, which is the only
 * authority that matters: both missions call the landing sol "sol 0" and step the count at
 * local mean midnight, and a constant computed once and trusted would drift silently if the
 * sol-date arithmetic ever moved under it.
 */
class RoversTest {

    @Test
    fun `option ids round trip and unknown ids fall to the default`() {
        assertEquals(Rovers.PERSEVERANCE, Rovers.fromOptionId(Rovers.ID_PERSEVERANCE))
        assertEquals(Rovers.CURIOSITY, Rovers.fromOptionId(Rovers.ID_CURIOSITY))
        assertEquals(Rovers.PERSEVERANCE, Rovers.fromOptionId("phobos"))
    }

    @Test
    fun `landing instants are sol zero`() {
        assertEquals(
            0L,
            Rovers.missionSol(
                Instant.parse("2021-02-18T20:55:00Z").toEpochMilli(), Rovers.PERSEVERANCE,
            ),
        )
        assertEquals(
            0L,
            Rovers.missionSol(
                Instant.parse("2012-08-06T05:17:57Z").toEpochMilli(), Rovers.CURIOSITY,
            ),
        )
    }

    /** JPL marked Curiosity's sol 3000 on 2021-01-12; the sol began late that day UTC. */
    @Test
    fun `curiosity reached sol 3000 in january 2021`() {
        assertEquals(
            3000L,
            Rovers.missionSol(Instant.parse("2021-01-13T06:00:00Z").toEpochMilli(), Rovers.CURIOSITY),
        )
        assertEquals(
            2999L,
            Rovers.missionSol(Instant.parse("2021-01-12T12:00:00Z").toEpochMilli(), Rovers.CURIOSITY),
        )
    }

    /**
     * NASA marked Perseverance's thousandth sol in mid-December 2023; by this arithmetic sol
     * 1000 runs through 2023-12-13 UTC, having begun late on the 12th — the same
     * begins-late-in-the-UTC-day shape the Curiosity anchor above shows exactly.
     */
    @Test
    fun `perseverance reached sol 1000 in december 2023`() {
        assertEquals(
            1000L,
            Rovers.missionSol(Instant.parse("2023-12-13T12:00:00Z").toEpochMilli(), Rovers.PERSEVERANCE),
        )
        assertEquals(
            999L,
            Rovers.missionSol(Instant.parse("2023-12-12T12:00:00Z").toEpochMilli(), Rovers.PERSEVERANCE),
        )
    }
}
