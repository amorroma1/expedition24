// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

/**
 * Eases the dial from one frame of reference to another.
 *
 * Cross a time zone and every hour-scale element — the hour hand, both ends of the duty arc, the
 * daylight band — has to move by the same amount at once. Snapping them looks like a glitch; the
 * dial glides to its new setting instead.
 *
 * ### Why there are two offsets
 * Everything is derived from a UTC offset, so the obvious move is to ease that one number and let
 * it carry the whole dial. It does — and it also drags the minute hand through a full revolution
 * per hour of change, because the minute hand is the *fractional* part of the same value. An
 * eight-hour flight span it eight times round, which is nobody's idea of elegant.
 *
 * So the offset is eased twice, at two different moduli. The hour scale takes the whole change,
 * the short way round a day. The minute hand takes only what is left over inside an hour, which is
 * zero for almost every zone on earth and half a turn for the half-hour ones. Seconds are never
 * affected: no zone offset has ever had a fractional minute in it.
 *
 * Holds only primitives and allocates nothing.
 */
class DialTransition {

    /** Offset for the 24-hour scale: hour hand, duty arc, daylight band. */
    var hourOffsetMillis: Long = 0L
        private set

    /** Offset for the minute hand, which only ever differs in half-hour and quarter-hour zones. */
    var minuteOffsetMillis: Long = 0L
        private set

    /** Daylight band start, degrees clockwise from 12 o'clock. */
    var daylightStart: Float = 0f
        private set

    var daylightSweep: Float = 0f
        private set

    val animating: Boolean
        get() = progress < 1f

    private var initialised = false
    private var startedAt = 0L
    private var progress = 1f

    /** The real target, kept only to notice when it changes. */
    private var offsetTarget = 0L

    private var hourFrom = 0L
    private var hourTo = 0L
    private var minuteFrom = 0L
    private var minuteTo = 0L
    private var startFrom = 0f
    private var startTo = 0f
    private var sweepFrom = 0f
    private var sweepTo = 0f

    /**
     * Feeds in what the dial should be showing and returns the eased values through the properties.
     * Call once per frame; a change of target starts a fresh glide from wherever the last one had
     * got to, so a second change mid-flight does not jump.
     */
    fun update(
        nowMillis: Long,
        targetOffsetMillis: Long,
        targetDaylightStart: Float,
        targetDaylightSweep: Float,
    ) {
        if (!initialised) {
            initialised = true
            hourOffsetMillis = targetOffsetMillis
            minuteOffsetMillis = targetOffsetMillis
            daylightStart = targetDaylightStart
            daylightSweep = targetDaylightSweep
            offsetTarget = targetOffsetMillis
            startTo = targetDaylightStart
            sweepTo = targetDaylightSweep
            return
        }

        val changed = targetOffsetMillis != offsetTarget ||
            targetDaylightStart != startTo ||
            targetDaylightSweep != sweepTo
        if (changed) {
            offsetTarget = targetOffsetMillis

            // The hour scale goes the short way round a day. Crossing the date line can move the
            // offset by a full 24 hours while leaving the dial reading the same hour — Apia to Pago
            // Pago is exactly that — and easing the raw difference would spin the hand a whole
            // gratuitous turn. The landing value differs from the real offset by a multiple of a
            // day, which every angle here is taken modulo anyway.
            hourFrom = hourOffsetMillis
            hourTo = hourFrom + shortestDelta(hourFrom, targetOffsetMillis, DAY_MILLIS)

            // The minute hand goes the short way round an hour: nothing at all for a whole-hour
            // zone, half a turn for a half-hour one.
            minuteFrom = minuteOffsetMillis
            minuteTo = minuteFrom + shortestDelta(minuteFrom, targetOffsetMillis, HOUR_MILLIS)

            sweepFrom = daylightSweep
            sweepTo = targetDaylightSweep
            // A band appearing or disappearing grows and shrinks in place; only a band that exists
            // at both ends is worth sliding round the dial.
            startFrom = if (sweepFrom <= 0f || sweepTo <= 0f) targetDaylightStart else daylightStart
            startTo = targetDaylightStart

            startedAt = nowMillis
            progress = 0f
        }

        if (progress >= 1f) return

        val elapsed = nowMillis - startedAt
        progress = if (elapsed >= DURATION_MILLIS) 1f else elapsed / DURATION_MILLIS.toFloat()
        val eased = smoothStep(progress).toDouble()

        hourOffsetMillis = hourFrom + Math.round((hourTo - hourFrom) * eased)
        minuteOffsetMillis = minuteFrom + Math.round((minuteTo - minuteFrom) * eased)
        daylightSweep = sweepFrom + (sweepTo - sweepFrom) * eased.toFloat()
        daylightStart =
            normalise(startFrom + shortestAngle(startFrom, startTo) * eased.toFloat())
    }

    /** Smoothstep: starts and ends at rest, so the movement reads as deliberate rather than flung. */
    private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    /** Signed distance from [from] to [to] taken modulo [period], in `(-period/2, +period/2]`. */
    private fun shortestDelta(from: Long, to: Long, period: Long): Long {
        var delta = (to - from) % period
        if (delta > period / 2) delta -= period
        if (delta <= -period / 2) delta += period
        return delta
    }

    /** Signed angular distance in `(-180, 180]`, so the band never takes the long way round. */
    private fun shortestAngle(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta <= -180f) delta += 360f
        return delta
    }

    private fun normalise(degrees: Float): Float {
        var d = degrees % 360f
        if (d < 0f) d += 360f
        return d
    }

    private companion object {
        /**
         * Slow enough to watch. This is a dial re-setting itself, not a chronograph showing off;
         * four seconds reads as deliberate where two read as a twitch.
         */
        const val DURATION_MILLIS = 4000L

        const val DAY_MILLIS = 86_400_000L
        const val HOUR_MILLIS = 3_600_000L
    }
}
