// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

/**
 * Eases the face out of always-on instead of snapping it.
 *
 * Leaving ambient changes two things at once: a single dim hue becomes the full palette, and the
 * light output roughly triples. Done in one frame that is a slap in a dark room, which is exactly
 * where a watch gets looked at most.
 *
 * So the light arrives as a front sweeping out from the hub over half a second: long enough to read
 * as deliberate, short enough that nobody waits for it.
 *
 * Only the light. Always-on wears the same hues as interactive, merely dimmer, so there is nothing
 * to cross-fade — an earlier version swept colour and brightness as two separate fronts, which was
 * machinery in service of a difference that no longer exists.
 *
 * ### Only on the way out
 * Going *into* ambient is not animated. The screen is about to be left alone for hours, dimming is
 * what the eye wants anyway, and ambient draws once a minute — there would be nothing to animate
 * with.
 *
 * Holds only primitives and allocates nothing.
 */
class WakeTransition {

    /** `0` at the instant of waking, `1` once the face has fully arrived. */
    var progress: Float = 1f
        private set

    /** True while the sweep is still running, so the renderer knows to ask for fast frames. */
    val active: Boolean
        get() = progress < 1f

    private var initialised = false
    private var wasAmbient = false
    private var startedAt = 0L

    /** Call once per frame, before reading the fronts. */
    fun update(nowMillis: Long, ambient: Boolean) {
        if (!initialised) {
            // Whatever mode the first frame is in, it is where we came in: nothing to sweep.
            initialised = true
            wasAmbient = ambient
            progress = 1f
            return
        }

        if (ambient) {
            wasAmbient = true
            progress = 1f
            return
        }

        if (wasAmbient) {
            wasAmbient = false
            startedAt = nowMillis
            progress = 0f
        }

        if (progress >= 1f) return

        // A clock that jumped backwards would otherwise leave the sweep stuck part-drawn.
        val elapsed = nowMillis - startedAt
        progress = when {
            elapsed < 0L -> 1f
            elapsed >= DURATION_MILLIS -> 1f
            else -> elapsed / DURATION_MILLIS.toFloat()
        }
    }

    /**
     * Radius already at full brightness, as a fraction of the dial radius.
     *
     * Runs past 1 because the dial is inscribed in a square: the corners sit at 1.41 r, and a front
     * that stopped at 1 would leave four dim wedges behind after it had finished.
     */
    val brightnessRadius: Float
        get() = OVERSHOOT * smoothStep(progress)

    /** Overall strength of the veil, so it is guaranteed to be gone at the end. */
    val veilAlpha: Float
        get() = 1f - smoothStep(progress)

    /** Starts and ends at rest, so the front never appears to be flung. */
    private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    companion object {
        /**
         * Half a second. Long enough to read as a deliberate reveal rather than a stutter, short
         * enough that raising a wrist never feels like waiting for something.
         */
        const val DURATION_MILLIS = 500L


        /** Far enough to clear the corners of a square canvas around a round dial. */
        const val OVERSHOOT = 1.45f
    }
}
