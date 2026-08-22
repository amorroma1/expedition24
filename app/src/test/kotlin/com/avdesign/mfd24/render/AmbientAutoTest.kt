// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import com.avdesign.mfd24.astro.SolarTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AUTO half-density rule: thinned after dark, never during an active watch, and full when
 * there is nothing trustworthy to decide by. The failure directions differ — thinning by day is
 * an unreadable dial, thinning on a night shift dims the one face being worked from — so each is
 * pinned separately.
 */
class AmbientAutoTest {

    private val sunrise = 6 * 3_600_000L
    private val sunset = 20 * 3_600_000L
    private val noon = 12 * 3_600_000L
    private val night = 23 * 3_600_000L

    private fun auto(dutyActive: Boolean, kind: Int, now: Long, valid: Boolean = true): Boolean =
        AmbientAuto.halfDensity(
            AmbientAuto.MODE_AUTO, dutyActive, valid, kind, sunrise, sunset, now,
        )

    @Test
    fun `solid and halved are unconditional`() {
        // FULL never thins and HALF always does, whatever the sun or the duty are up to: the
        // explicit settings are promises, and AUTO is the only mode with an opinion of its own.
        assertFalse(
            AmbientAuto.halfDensity(
                AmbientAuto.MODE_FULL, false, true, SolarTime.NORMAL, sunrise, sunset, night,
            )
        )
        assertTrue(
            AmbientAuto.halfDensity(
                AmbientAuto.MODE_HALF, true, true, SolarTime.NORMAL, sunrise, sunset, noon,
            )
        )
    }

    @Test
    fun `auto thins after dark and not by day`() {
        assertTrue(auto(dutyActive = false, kind = SolarTime.NORMAL, now = night))
        // Before dawn is the same night as after dusk.
        assertTrue(auto(dutyActive = false, kind = SolarTime.NORMAL, now = sunrise - 1))
        assertFalse(auto(dutyActive = false, kind = SolarTime.NORMAL, now = noon))
    }

    @Test
    fun `an active watch keeps the full face all night`() {
        // A night shift is exactly when the dial is worked from in the dark.
        assertFalse(auto(dutyActive = true, kind = SolarTime.NORMAL, now = night))
        // Off duty again, the saving resumes.
        assertTrue(auto(dutyActive = false, kind = SolarTime.NORMAL, now = night))
    }

    @Test
    fun `polar day and night resolve like the daylight band does`() {
        assertFalse(auto(dutyActive = false, kind = SolarTime.POLAR_DAY, now = noon))
        assertTrue(auto(dutyActive = false, kind = SolarTime.POLAR_NIGHT, now = noon))
    }

    @Test
    fun `without a position auto falls back to the readable direction`() {
        // No position means no sunset to consult; guessing dark would dim a dial we cannot
        // justify dimming, so AUTO behaves as FULL until the daylight window exists.
        assertFalse(auto(dutyActive = false, kind = SolarTime.NORMAL, now = night, valid = false))
    }
}
