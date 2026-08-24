// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.astro

/**
 * The two rovers this face keeps time for. Their positions are compile-time constants rather
 * than telemetry: a rover moves metres per sol, and the smallest thing the dial resolves — a
 * minute of local time — is a quarter degree of longitude, thousands of times that. Indexed by
 * plain `Int` for the same reason [PlanetMode] is: the render path reads these per frame.
 *
 * Coordinates are east-positive planetocentric, the convention [MarsSolarTime] takes. The
 * [SOL0_LSD] values are the floor of [MarsSolarTime.localSolDate] at each landing instant — the
 * local sol the mission calls sol 0 — and they are pinned by `RoversTest` against published
 * mission sol/date pairs rather than trusted from arithmetic done once.
 */
object Rovers {

    const val PERSEVERANCE = 0
    const val CURIOSITY = 1

    const val ID_PERSEVERANCE = "perseverance"
    const val ID_CURIOSITY = "curiosity"

    /** Planetocentric latitude, degrees: Jezero crater; Gale crater. */
    val LAT = doubleArrayOf(18.4447, -4.5895)

    /** East longitude, degrees. */
    val LON_EAST = doubleArrayOf(77.4508, 137.4417)

    /** floor(localSolDate) of each landing sol: 2021-02-18 20:55 UTC; 2012-08-06 05:17:57 UTC. */
    val SOL0_LSD = longArrayOf(52304, 49269)

    /** Unknown ids fall to Perseverance, the schema default — option ids flow one way. */
    fun fromOptionId(id: String): Int =
        if (id == ID_CURIOSITY) CURIOSITY else PERSEVERANCE

    /**
     * The mission sol under way at [epochMillis]: the landing sol is sol 0, and the count
     * increments at the site's local mean midnight, which is how both missions number them.
     */
    fun missionSol(epochMillis: Long, rover: Int): Long =
        Math.floor(MarsSolarTime.localSolDate(epochMillis, LON_EAST[rover])).toLong() -
            SOL0_LSD[rover]
}
