// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Canvas
import android.graphics.Paint
import com.avdesign.mfd24.style.Palette

/**
 * Draws the two hands and the centre hub. Seconds live on the rim ([SecondsMarker]), not on a
 * needle.
 *
 * The hands are skeletons: a thin contour along the body, solid only at the point and the
 * counterweight. The readout is printed on the dial underneath them, and a filled hand sweeping
 * across it simply deletes a line of type for part of every hour. Hollowed out, the type reads
 * straight through the shaft while the solid tip still lands unambiguously on a mark.
 *
 * Every `Paint` is created here once; [drawHourMinute] only mutates primitive fields and calls into
 * the canvas.
 */
class HandsLayer {

    private val handFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hubRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * @param hourAngle degrees clockwise from 12 o'clock; one turn per 24 h of the selected world
     * @param minuteAngle degrees clockwise from 12 o'clock; one turn per hour
     */
    fun drawHourMinute(
        canvas: Canvas,
        g: Geometry,
        palette: Palette,
        hourAngle: Float,
        minuteAngle: Float,
    ) {
        handFill.color = palette.lume
        handOutline.color = palette.lume
        handOutline.strokeWidth = g.r * Geometry.HAND_OUTLINE_WIDTH

        canvas.save()
        canvas.rotate(hourAngle, g.cx, g.cy)
        canvas.drawPath(g.hourHand, handOutline)
        canvas.drawPath(g.hourHandSolid, handFill)
        canvas.restore()

        canvas.save()
        canvas.rotate(minuteAngle, g.cx, g.cy)
        canvas.drawPath(g.minuteHand, handOutline)
        canvas.drawPath(g.minuteHandSolid, handFill)
        canvas.restore()
    }

    /**
     * Centre hub, drawn last so the hand pivots stay clean, with the vigilance core inside it.
     *
     * ### The core
     * [coreFraction] fills the hub from nothing to full as the vigilance interval runs down, so the
     * one thing the dial could never say before — that a dead-man's check is armed, and how much of
     * it is left — is readable at a glance without a row of type or a countdown.
     *
     * It is the accent hue, like the ring around it and the seconds cursor on the rim, rather than
     * a red of its own: the palette has three hues and always-on is the same hues dimmed, so a
     * hardcoded red would neither dim with the face nor be visible at all under the red palette.
     *
     * **The core never leaves the hub.** A first attempt marked the alarm with a ring outside it,
     * which read as the indicator carrying on growing after the interval had already run out —
     * there is no such thing as more than out of time, and an indicator that keeps moving past its
     * own limit is telling you something untrue. [alarming] thickens the hub's own ring instead, at
     * the same radius.
     *
     * Nothing here animates. A strobe would be invisible in always-on, which redraws once a minute,
     * and in interactive it would pin the frame rate at 16 ms for the length of an alarm — burning
     * battery in the name of a feature whose whole point is to save it.
     */
    /**
     * @param suspended vigilance is switched on but not watching — on charge, or off the wrist.
     *   The ring goes dim to say so, because an empty core cannot: an empty core is also what a
     *   monitor that has just been answered looks like. Only ever true when vigilance was asked
     *   for, so a face that does not use it keeps the accent hub it always had.
     *
     * The core fills only while an answer is owed. That is the whole of what it says, and the
     * reason an incident leaves it empty even though an incident is the worst of the states: the
     * words above the hub carry that one, and a full core here would say "answer now" for hours.
     */
    fun drawHub(
        canvas: Canvas,
        g: Geometry,
        palette: Palette,
        coreFraction: Float,
        alarming: Boolean,
        suspended: Boolean,
    ) {
        hubPaint.color = palette.background
        canvas.drawCircle(g.cx, g.cy, g.hubRadius, hubPaint)

        if (coreFraction > 0f) {
            hubPaint.color = palette.second
            canvas.drawCircle(g.cx, g.cy, g.hubRadius * coreFraction.coerceAtMost(1f), hubPaint)
        }

        // Same accent as the seconds rim, tying the centre of the dial to its edge. Last, so a full
        // core reads as a filled hub rather than as a hub that has burst its outline.
        hubRingPaint.color = if (suspended) palette.lumeDim else palette.second
        hubRingPaint.strokeWidth = g.r * if (alarming) ALARM_RING_WIDTH else HUB_RING_WIDTH
        canvas.drawCircle(g.cx, g.cy, g.hubRadius, hubRingPaint)
    }

    private companion object {
        const val HUB_RING_WIDTH = 0.010f

        /** The alarm reads as a heavier outline, not as a bigger circle. */
        const val ALARM_RING_WIDTH = 0.022f
    }
}
