// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The vigilance monitor is only as good as its ability to tell a deliberate arm movement from the
 * environment it is worn in. These drive the filter with synthetic signals standing in for the
 * things that actually shake a watch on duty.
 */
class MotionFilterTest {

    private val gravity = 9.81f

    /** The rate the service actually asks for, so these run against the shipped configuration. */
    private val sampleHz = 1_000_000f / VigilanceService.SENSOR_PERIOD_MICROS
    private val dt = 1f / sampleHz

    /** Everything [`engine and rotor vibration is ignored`] and its neighbours have to reject. */
    private val vibrationHz = floatArrayOf(8f, 12f, 14f, 20f)

    /** Runs a sine of the given frequency and amplitude on top of gravity for [seconds]. */
    private fun feedSine(
        filter: MotionFilter,
        frequencyHz: Float,
        amplitude: Float,
        seconds: Float,
    ): Boolean {
        var triggered = false
        val samples = (seconds * sampleHz).toInt()
        for (i in 0 until samples) {
            val t = i * dt
            val magnitude = gravity + amplitude * sin(2.0 * Math.PI * frequencyHz * t).toFloat()
            if (filter.accept(magnitude, dt)) triggered = true
        }
        return triggered
    }

    @Test
    fun `a still wrist reports nothing`() {
        val filter = MotionFilter()
        var triggered = false
        repeat(500) { if (filter.accept(gravity, dt)) triggered = true }
        assertFalse(triggered)
    }

    @Test
    fun `a deliberate arm movement is detected`() {
        // Roughly one and a half swings a second, a couple of m per second squared: a wrist turned
        // to look at the watch.
        assertTrue(feedSine(MotionFilter(), frequencyHz = 1.5f, amplitude = 3f, seconds = 3f))
    }

    @Test
    fun `the whole pass band responds`() {
        for (frequency in floatArrayOf(0.7f, 1.0f, 2.0f, 2.5f)) {
            assertTrue(
                "$frequency Hz should count as movement",
                feedSine(MotionFilter(), frequency, amplitude = 3f, seconds = 4f),
            )
        }
    }

    @Test
    fun `a hull rolling in a swell is ignored`() {
        // Ten second period, violent by feel — five m per second squared — and entirely below the
        // band. A ship's roll must never answer for the operator.
        assertFalse(feedSine(MotionFilter(), frequencyHz = 0.1f, amplitude = 5f, seconds = 40f))
    }

    @Test
    fun `slow aircraft pitching is ignored`() {
        assertFalse(feedSine(MotionFilter(), frequencyHz = 0.2f, amplitude = 4f, seconds = 30f))
    }

    /**
     * The sample rate has to resolve the noise, not just the signal.
     *
     * Nyquist for a 3 Hz band says 7 Hz would do, and the accelerometer will happily run at 13, so
     * lowering the rate looks like free battery. It is not. Sampling at 13 Hz folds 12 and 14 Hz —
     * two of the frequencies the tests below are required to reject — onto 1 Hz, dead centre of the
     * arm-movement band, where nothing downstream can tell them from a wrist. The rejection would
     * then rest entirely on the sensor's own anti-alias filter, which is not visible from here and
     * cannot be tested.
     *
     * So the rule is Nyquist for the *noise*: every frequency in [vibrationHz] must still be above
     * the pass band after folding. This is the test that stops the saving being taken again.
     */
    @Test
    fun `the sample rate resolves the noise it has to reject`() {
        for (frequency in vibrationHz) {
            val image = abs(frequency - Math.round(frequency / sampleHz) * sampleHz)
            assertTrue(
                "at ${sampleHz.toInt()} Hz, $frequency Hz folds to $image Hz, inside the pass band",
                image > MotionFilter.HIGH_CUTOFF_HZ,
            )
        }
    }

    @Test
    fun `engine and rotor vibration is ignored`() {
        for (frequency in vibrationHz) {
            assertFalse(
                "$frequency Hz should be filtered out",
                feedSine(MotionFilter(), frequency, amplitude = 4f, seconds = 5f),
            )
        }
    }

    @Test
    fun `vibration riding on a roll is still ignored`() {
        // The realistic case: a boat rolling with the engine running, and nobody moving.
        val filter = MotionFilter()
        var triggered = false
        val samples = (30f * sampleHz).toInt()
        for (i in 0 until samples) {
            val t = i * dt
            val magnitude = gravity +
                5f * sin(2.0 * Math.PI * 0.12 * t).toFloat() +
                3f * sin(2.0 * Math.PI * 14.0 * t).toFloat()
            if (filter.accept(magnitude, dt)) triggered = true
        }
        assertFalse(triggered)
    }

    @Test
    fun `a movement is still found under that noise`() {
        val filter = MotionFilter()
        var triggered = false
        val samples = (6f * sampleHz).toInt()
        for (i in 0 until samples) {
            val t = i * dt
            val magnitude = gravity +
                5f * sin(2.0 * Math.PI * 0.12 * t).toFloat() +
                3f * sin(2.0 * Math.PI * 14.0 * t).toFloat() +
                3f * sin(2.0 * Math.PI * 1.5 * t).toFloat()
            if (filter.accept(magnitude, dt)) triggered = true
        }
        assertTrue(triggered)
    }

    @Test
    fun `a single knock does not answer for the operator`() {
        val filter = MotionFilter()
        repeat(100) { filter.accept(gravity, dt) }
        // One isolated spike, then quiet again.
        assertFalse(filter.accept(gravity + 40f, dt))
    }

    @Test
    fun `a gap in the samples resets rather than firing`() {
        val filter = MotionFilter()
        repeat(100) { filter.accept(gravity, dt) }
        // The sensor was suspended for two seconds; the first sample back must not look like motion.
        assertFalse(filter.accept(gravity + 20f, 2f))
    }
}
