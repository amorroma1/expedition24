// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distance gate on the site search, held to the two ways it has actually failed.
 *
 * Both bugs lived in *when the origin was written*: written inside the check, a slow drift reset
 * the yardstick every refresh and never crossed the threshold; skipped by an early return, the
 * origin stayed unset for ever and the gate waved every refresh through.
 */
class ResolveOriginTest {

    /** About 400 m of latitude — under the 500 m gate on its own, over it when it accumulates. */
    private val stepDegrees = 0.0036

    private val gateMeters = 500.0

    @Test
    fun `with no search on record everything counts as moved`() {
        val origin = ResolveOrigin()
        assertTrue(origin.movedBeyond(50.0, 30.0, gateMeters))
        // And it stays true: checking is not recording.
        assertTrue(origin.movedBeyond(50.0, 30.0, gateMeters))
    }

    @Test
    fun `a small move from the last search stays inside the gate`() {
        val origin = ResolveOrigin()
        origin.record(50.0, 30.0)
        assertFalse(origin.movedBeyond(50.0 + stepDegrees, 30.0, gateMeters))
    }

    @Test
    fun `checking does not move the baseline, so a slow drift still crosses the gate`() {
        // The creep bug: two sub-threshold steps in the same direction. An origin updated by the
        // check itself would compare each step to the previous one and never fire; measured from
        // where the search actually ran, 800 m is 800 m.
        val origin = ResolveOrigin()
        origin.record(50.0, 30.0)
        assertFalse(origin.movedBeyond(50.0 + stepDegrees, 30.0, gateMeters))
        assertTrue(origin.movedBeyond(50.0 + 2 * stepDegrees, 30.0, gateMeters))
    }

    @Test
    fun `recording a new search moves the baseline with it`() {
        val origin = ResolveOrigin()
        origin.record(50.0, 30.0)
        origin.record(51.0, 30.0)
        assertFalse(origin.movedBeyond(51.0 + stepDegrees, 30.0, gateMeters))
        assertTrue(origin.movedBeyond(50.0, 30.0, gateMeters))
    }
}
