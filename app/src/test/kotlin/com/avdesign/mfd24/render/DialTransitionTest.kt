// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import com.avdesign.mfd24.astro.AstroTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The dial re-setting itself after a change of zone or position.
 *
 * The tests that matter here are the ones about how far things travel. Easing a single UTC offset
 * carries the whole dial in step, which is the point — but the minute hand is the fractional part
 * of that same number, so an eight-hour change spun it eight full turns. These pin the travel
 * distances so that cannot come back.
 */
class DialTransitionTest {

    private val hour = 3_600_000L
    private val t0 = 1_000_000_000_000L

    private fun settled(offsetMillis: Long): DialTransition = DialTransition().apply {
        update(t0, offsetMillis, 0f, 0f)
    }

    /** Total distance the minute offset travels, sampled finely enough to catch a whole turn. */
    private fun minuteTravel(transition: DialTransition, target: Long): Long {
        var previous = transition.minuteOffsetMillis
        var travel = 0L
        for (step in 1..200) {
            transition.update(t0 + step * 25L, target, 0f, 0f)
            travel += abs(transition.minuteOffsetMillis - previous)
            previous = transition.minuteOffsetMillis
        }
        return travel
    }

    private fun hourTravel(transition: DialTransition, target: Long): Long {
        var previous = transition.hourOffsetMillis
        var travel = 0L
        for (step in 1..200) {
            transition.update(t0 + step * 25L, target, 0f, 0f)
            travel += abs(transition.hourOffsetMillis - previous)
            previous = transition.hourOffsetMillis
        }
        return travel
    }

    @Test
    fun `the first frame settles immediately, with nothing to animate`() {
        val transition = settled(3 * hour)
        assertEquals(3 * hour, transition.hourOffsetMillis)
        assertEquals(3 * hour, transition.minuteOffsetMillis)
        assertTrue(!transition.animating)
    }

    @Test
    fun `a whole hour zone change does not move the minute hand at all`() {
        val transition = settled(4 * hour)
        // Eight hours west: the hour scale swings, the minute hand must not budge.
        assertEquals(0L, minuteTravel(transition, -4 * hour))
    }

    @Test
    fun `an eight hour change moves the hour scale exactly eight hours, once`() {
        val transition = settled(4 * hour)
        val travel = hourTravel(transition, -4 * hour)
        // Monotonic, so total travel equals the net change. A spin would show up as a multiple.
        assertEquals(8 * hour, travel)
        assertEquals(-4 * hour, transition.hourOffsetMillis)
    }

    @Test
    fun `a half hour zone moves the minute hand half an hour, not a whole one`() {
        val transition = settled(5 * hour)
        // Delhi from Karachi: +5:00 to +5:30.
        val travel = minuteTravel(transition, 5 * hour + 1_800_000L)
        assertEquals(1_800_000L, travel)
    }

    @Test
    fun `crossing the date line does not spin the hour hand a full turn`() {
        // Apia to Pago Pago: +13:00 to -11:00. Twenty-four hours apart, same hour on the dial.
        val transition = settled(13 * hour)
        val travel = hourTravel(transition, -11 * hour)
        assertEquals("the dial should not move at all", 0L, travel)
        // It lands a day away from the real offset, which every angle is taken modulo.
        assertEquals(0L, Math.floorMod(transition.hourOffsetMillis - (-11 * hour), 86_400_000L))
    }

    @Test
    fun `a thirteen hour change goes the short way, eleven hours the other direction`() {
        val transition = settled(12 * hour)
        val travel = hourTravel(transition, -11 * hour)
        assertEquals(hour, travel)
    }

    @Test
    fun `the daylight band takes the short way round the dial`() {
        val transition = DialTransition().apply { update(t0, 0L, 350f, 100f) }
        var previous = transition.daylightStart
        var travel = 0f
        for (step in 1..200) {
            transition.update(t0 + step * 25L, 0L, 10f, 100f)
            var delta = transition.daylightStart - previous
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            travel += abs(delta)
            previous = transition.daylightStart
        }
        // 350 to 10 is twenty degrees forwards, not three hundred and forty backwards.
        assertEquals(20f, travel, 0.5f)
    }

    @Test
    fun `a band that appears grows in place instead of sliding in from nowhere`() {
        val transition = DialTransition().apply { update(t0, 0L, 0f, 0f) }
        transition.update(t0 + 1, 0L, 200f, 120f)
        // The start angle is already correct on the first frame; only the sweep opens up.
        assertEquals(200f, transition.daylightStart, 0.01f)
        assertTrue(transition.daylightSweep < 120f)
    }

    @Test
    fun `the glide finishes on target and stops`() {
        val transition = settled(0L)
        transition.update(t0 + 1, 3 * hour, 0f, 0f)
        assertTrue(transition.animating)
        transition.update(t0 + 10_000L, 3 * hour, 0f, 0f)
        assertTrue(!transition.animating)
        assertEquals(3 * hour, transition.hourOffsetMillis)
    }

    /**
     * The Mars case: a rover switch is a change of meridian, eased with the sol as the day and
     * a Mars hour as the hour. Jezero to Gale is 59.9909 degrees — four Mars hours less about a
     * second and a half — so the sol scale must take the whole change once, and the minute hand
     * only the remainder, not four full turns.
     */
    @Test
    fun `a rover switch glides the sol scale whole and the minute hand by seconds`() {
        val sol = Math.round(AstroTime.SOL_IN_MILLIS)
        val perseverance = Math.round(77.4508 / 360.0 * AstroTime.SOL_IN_MILLIS)
        val curiosity = Math.round(137.4417 / 360.0 * AstroTime.SOL_IN_MILLIS)
        val transition = DialTransition().apply { update(t0, perseverance, 0f, 0f, sol) }
        var hourPrevious = transition.hourOffsetMillis
        var minutePrevious = transition.minuteOffsetMillis
        var hourTravelled = 0L
        var minuteTravelled = 0L
        for (step in 1..200) {
            transition.update(t0 + step * 25L, curiosity, 0f, 0f, sol)
            hourTravelled += abs(transition.hourOffsetMillis - hourPrevious)
            minuteTravelled += abs(transition.minuteOffsetMillis - minutePrevious)
            hourPrevious = transition.hourOffsetMillis
            minutePrevious = transition.minuteOffsetMillis
        }
        assertEquals(curiosity - perseverance, hourTravelled)
        assertEquals(curiosity, transition.hourOffsetMillis)
        assertTrue("minute hand travelled $minuteTravelled ms", minuteTravelled < 5_000L)
    }
}
