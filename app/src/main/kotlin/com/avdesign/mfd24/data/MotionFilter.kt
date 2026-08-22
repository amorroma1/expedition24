// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import kotlin.math.abs
import kotlin.math.exp

/**
 * Decides whether the wrist actually moved.
 *
 * The problem is not detecting motion; it is detecting motion on a flight deck or a bridge, where
 * the accelerometer never rests. Two kinds of noise have to go:
 *
 *  - **Below 0.5 Hz** — gravity itself, the slow pitch and roll of an aircraft or a hull, a turn, a
 *    change of posture. Sustained and large, and none of it means the operator is awake.
 *  - **Above 3 Hz** — engine and rotor vibration, road buzz, propeller beat. Constant, and equally
 *    meaningless.
 *
 * A deliberate arm movement lives in between, so the signal is band-passed to 0.5–3 Hz before
 * anything is decided: two high-pass poles at the bottom of the band feeding two low-pass poles at
 * the top.
 *
 * Two poles a side, not one. A single pole rolls off at 6 dB per octave, which sounds like plenty
 * until you work out what it leaves: a ten-second roll of five m/s² still puts about a quarter of
 * itself into the band, and add engine buzz on top and the pair of them trip the threshold with
 * nobody awake. Twelve dB per octave drops the same roll to a few percent.
 *
 * Fed the raw magnitude of acceleration, so gravity arrives as a constant and the high-pass eats
 * it. Six floats of state and no allocation, cheap enough for every sample.
 */
class MotionFilter {

    private var lastInput = Float.NaN
    private var highPass1 = 0f
    private var highPass2 = 0f
    private var lowPass1 = 0f
    private var lowPass2 = 0f
    private var consecutive = 0

    /** Latest band-passed value, in m/s². Exposed for diagnostics and tests. */
    var band: Float = 0f
        private set

    fun reset() {
        lastInput = Float.NaN
        highPass1 = 0f
        highPass2 = 0f
        lowPass1 = 0f
        lowPass2 = 0f
        band = 0f
        consecutive = 0
    }

    /**
     * Feeds one sample.
     *
     * @param magnitude length of the acceleration vector, m/s², gravity included
     * @param deltaSeconds time since the previous sample
     * @return true when this sample completes a run long enough to call it real movement
     */
    fun accept(magnitude: Float, deltaSeconds: Float): Boolean {
        if (deltaSeconds <= 0f || deltaSeconds > MAX_GAP_SECONDS) {
            // A gap that long means the sensor was suspended; the filter state is meaningless.
            reset()
            lastInput = magnitude
            return false
        }
        if (lastInput.isNaN()) {
            lastInput = magnitude
            return false
        }

        // Two high-pass poles: strip gravity, posture and the slow heave of a vehicle.
        val hpAlpha = highPassAlpha(deltaSeconds, LOW_CUTOFF_HZ)
        val previousHighPass1 = highPass1
        highPass1 = hpAlpha * (highPass1 + magnitude - lastInput)
        highPass2 = hpAlpha * (highPass2 + highPass1 - previousHighPass1)
        lastInput = magnitude

        // Two low-pass poles: strip engine, rotor and road.
        val lpAlpha = lowPassAlpha(deltaSeconds, HIGH_CUTOFF_HZ)
        lowPass1 += (highPass2 - lowPass1) * lpAlpha
        lowPass2 += (lowPass1 - lowPass2) * lpAlpha
        band = lowPass2

        if (abs(band) >= THRESHOLD_MSS) {
            consecutive++
            if (consecutive >= REQUIRED_SAMPLES) {
                consecutive = 0
                return true
            }
        } else {
            consecutive = 0
        }
        return false
    }

    /** One-pole low-pass coefficient for a cutoff of [cutoffHz] at this sample spacing. */
    private fun lowPassAlpha(deltaSeconds: Float, cutoffHz: Float): Float =
        1f - exp(-2.0 * Math.PI * cutoffHz * deltaSeconds).toFloat()

    /** One-pole high-pass coefficient: the RC form, `rc / (rc + dt)`. */
    private fun highPassAlpha(deltaSeconds: Float, cutoffHz: Float): Float {
        val rc = 1.0 / (2.0 * Math.PI * cutoffHz)
        return (rc / (rc + deltaSeconds)).toFloat()
    }

    companion object {
        const val LOW_CUTOFF_HZ = 0.5f
        const val HIGH_CUTOFF_HZ = 3.0f

        /**
         * How much band-limited acceleration counts as a deliberate movement. A wrist turned on
         * purpose swings several m/s² through this band; a hull rolling in a swell contributes
         * almost nothing to it, however violent it feels.
         */
        const val THRESHOLD_MSS = 1.2f

        /** Two in a row, so a single spike from a knock does not answer for the operator. */
        const val REQUIRED_SAMPLES = 2

        /** Longer than this between samples and the filter has no usable history. */
        const val MAX_GAP_SECONDS = 1.0f
    }
}
