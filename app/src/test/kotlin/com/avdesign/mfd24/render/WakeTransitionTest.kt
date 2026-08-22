// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake sweep is only visible for half a second, which makes it exactly the kind of thing that
 * is easier to pin here than to catch on a wrist.
 */
class WakeTransitionTest {

    private val t0 = 1_755_000_000_000L

    private fun settled(): WakeTransition = WakeTransition().apply {
        update(t0, ambient = true)
        update(t0, ambient = true)
    }

    @Test
    fun `the very first frame never sweeps, whichever mode it is in`() {
        val fresh = WakeTransition()
        fresh.update(t0, ambient = false)
        assertFalse("waking should not animate on the first frame ever drawn", fresh.active)
        assertEquals(1f, fresh.progress, 1e-6f)
    }

    @Test
    fun `leaving ambient starts a sweep and it finishes on time`() {
        val w = settled()
        w.update(t0, ambient = false)
        assertTrue(w.active)
        assertEquals(0f, w.progress, 1e-6f)

        w.update(t0 + WakeTransition.DURATION_MILLIS / 2, ambient = false)
        assertTrue(w.active)
        assertEquals(0.5f, w.progress, 1e-3f)

        w.update(t0 + WakeTransition.DURATION_MILLIS, ambient = false)
        assertFalse(w.active)
        assertEquals(1f, w.progress, 1e-6f)
    }

    @Test
    fun `entering ambient is not animated`() {
        val w = WakeTransition()
        w.update(t0, ambient = false)
        w.update(t0 + 1_000L, ambient = true)
        assertFalse(w.active)
    }

    @Test
    fun `the front only ever grows`() {
        val w = settled()
        w.update(t0, ambient = false)
        var previous = -1f
        var step = 0L
        while (step <= WakeTransition.DURATION_MILLIS) {
            w.update(t0 + step, ambient = false)
            assertTrue(
                "the front went backwards at ${step}ms: $previous -> ${w.brightnessRadius}",
                w.brightnessRadius >= previous - 1e-4f,
            )
            previous = w.brightnessRadius
            step += 10L
        }
    }

    @Test
    fun `the front clears the corners of the canvas by the end`() {
        val w = settled()
        w.update(t0, ambient = false)
        w.update(t0 + WakeTransition.DURATION_MILLIS, ambient = false)
        // The dial is inscribed in a square, so the far corner is at sqrt(2) radii.
        assertTrue("front stopped at ${w.brightnessRadius}", w.brightnessRadius >= 1.415f)
        assertEquals("the veil must be gone", 0f, w.veilAlpha, 1e-6f)
    }

    @Test
    fun `the front starts at the hub, not part way out`() {
        val w = settled()
        w.update(t0, ambient = false)
        assertEquals(0f, w.brightnessRadius, 1e-6f)
        assertEquals("the veil is at full strength at the instant of waking", 1f, w.veilAlpha, 1e-6f)
    }

    @Test
    fun `a backwards clock jump settles rather than freezing the sweep half drawn`() {
        val w = settled()
        w.update(t0, ambient = false)
        assertTrue(w.active)
        w.update(t0 - 60_000L, ambient = false)
        assertFalse("a negative elapsed time must resolve, not stick", w.active)
    }

    @Test
    fun `a second wake after settling sweeps again`() {
        val w = settled()
        w.update(t0, ambient = false)
        w.update(t0 + WakeTransition.DURATION_MILLIS, ambient = false)
        assertFalse(w.active)

        w.update(t0 + 10_000L, ambient = true)
        w.update(t0 + 20_000L, ambient = false)
        assertTrue("waking a second time should sweep too", w.active)
        assertEquals(0f, w.progress, 1e-6f)
    }
}
