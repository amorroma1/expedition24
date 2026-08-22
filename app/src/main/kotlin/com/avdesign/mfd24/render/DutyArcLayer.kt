// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Canvas
import android.graphics.Paint
import com.avdesign.mfd24.style.Palette
import kotlin.math.cos
import kotlin.math.sin

/**
 * The duty arc: a band on the dial's inner ring spanning the hours you are on duty.
 *
 * Two arcs are drawn. The dim one is the whole watch, from where the hour hand stood when it began
 * to where it will stand when it ends, so the shape of the shift is visible at a glance. The bright
 * one runs from the hour hand's current position to that same end point — the time still to serve.
 *
 * Angles come in as degrees clockwise from 12 o'clock, which is how the rest of the renderer talks;
 * `Canvas.drawArc` measures from 3 o'clock, hence the quarter-turn offset.
 */
class DutyArcLayer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    /** Separate from [paint] so a notch never inherits an arc's width, or the other way about. */
    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    /**
     * @param spanColor colour of the whole shift, already at its intended alpha
     * @param remainingColor colour of the part still to serve; ignored when [remainingSweep] is 0
     * @param uncovered vigilance was asked for and is not watching — on charge, or off the wrist.
     *   The time still to serve is drawn as a thin line inside the shift's own band instead of
     *   filling it, so the arc says the watch is running *and* that nothing is covering it.
     *
     *   The arc carries this rather than only the hub, which was the first attempt. The hub is eight
     *   pixels of dial in the middle, under the hands, and the state it was reporting is a safety
     *   one: the arc is the largest thing on the face and the only part of it that can be read
     *   without looking. Glanceability wins over keeping the shift and the monitor in separate
     *   drawings. It is gated on vigilance having been switched on, so a face that does not use it
     *   never sees a thin arc.
     */
    fun draw(
        canvas: Canvas,
        g: Geometry,
        spanColor: Int,
        remainingColor: Int,
        strokeWidth: Float,
        spanStartAngle: Float,
        spanSweep: Float,
        remainingStartAngle: Float,
        remainingSweep: Float,
        uncovered: Boolean,
    ) {
        paint.strokeWidth = strokeWidth

        paint.color = spanColor
        canvas.drawArc(g.dutyArcTrack, spanStartAngle - QUARTER_TURN, spanSweep, false, paint)

        if (remainingSweep > 0f) {
            paint.color = remainingColor
            paint.strokeWidth = if (uncovered) strokeWidth * UNCOVERED_WIDTH else strokeWidth
            canvas.drawArc(
                g.dutyArcTrack, remainingStartAngle - QUARTER_TURN, remainingSweep, false, paint
            )
            paint.strokeWidth = strokeWidth
        }
    }

    /**
     * Incident marks: one radial notch across the arc for every time during this watch that the
     * operator stopped answering.
     *
     * This is the row's whole reason for existing. One missed check is an event, and the dial
     * already spells that one out; three notches by the fourth hour is a picture of somebody
     * failing, and there is nowhere else on this face that a *count* would fit. They are drawn on
     * the served part of the arc, which is dim, so a mark at full strength separates cleanly.
     *
     * **Flush with the band, in the opposite hue.** Two earlier attempts both went the same way and
     * both were wrong. The accent made the mark a slightly brighter piece of the arc, because the
     * accent is what the arc is drawn in. The lume, standing proud of the band with a halo, was
     * legible but untidy: a tick that overshoots its band is furniture this dial does not otherwise
     * have, and it caught the eye instead of being read.
     *
     * So the mark fills the band's own cross-section exactly — no overshoot, no halo, the same
     * thickness as the arc — and separates by hue alone, in [Palette.incidentMark]. That is the one
     * colour here outside the palette's two, and the reason it has to be is that neither of the two
     * is available: one is the arc and the other is the dial.
     *
     * @param angles degrees clockwise from 12 o'clock, the convention the rest of the renderer uses
     * @param count how many of [angles] are in use; the array is preallocated and longer
     */
    fun drawIncidents(
        canvas: Canvas,
        g: Geometry,
        color: Int,
        arcWidth: Float,
        angles: FloatArray,
        count: Int,
    ) {
        if (count <= 0) return
        notchPaint.color = color
        notchPaint.strokeWidth = g.r * NOTCH_STROKE
        val half = arcWidth * 0.5f
        val inner = g.dutyArcRadius - half
        val outer = g.dutyArcRadius + half
        var i = 0
        while (i < count) {
            // Canvas angles run from 3 o'clock, hence the same quarter-turn the arcs take.
            val radians = ((angles[i] - QUARTER_TURN) * DEGREES_TO_RADIANS)
            val dx = cos(radians).toFloat()
            val dy = sin(radians).toFloat()
            canvas.drawLine(
                g.cx + dx * inner, g.cy + dy * inner,
                g.cx + dx * outer, g.cy + dy * outer,
                notchPaint,
            )
            i++
        }
    }

    /** Alpha the not-yet-served part of a running shift is drawn at. */
    fun spanColorFor(color: Int): Int = Palette.withAlpha(color, ELAPSED_ALPHA)

    /** Ambient variant: outline weight only, so the arc costs almost no lit pixels. */
    fun drawAmbient(
        canvas: Canvas,
        g: Geometry,
        color: Int,
        remainingStartAngle: Float,
        remainingSweep: Float,
        antiAlias: Boolean,
    ) {
        if (remainingSweep <= 0f) return
        paint.isAntiAlias = antiAlias
        paint.color = color
        paint.strokeWidth = g.r * AMBIENT_STROKE
        canvas.drawArc(
            g.dutyArcTrack, remainingStartAngle - QUARTER_TURN, remainingSweep, false, paint
        )
        paint.isAntiAlias = true
    }

    private companion object {
        const val QUARTER_TURN = 90f
        const val ELAPSED_ALPHA = 0x4D
        const val AMBIENT_STROKE = 0.008f
        const val DEGREES_TO_RADIANS = Math.PI / 180.0

        /**
         * Width of the time still to serve when nothing is watching, against the band's own.
         *
         * Thin enough to read as a line inside the band rather than a slightly narrower band — at
         * 0.028 r the difference between 28 and 24 would not be seen at arm's length, and the whole
         * point is that it is seen without looking.
         */
        const val UNCOVERED_WIDTH = 0.40f

        /**
         * Notch width and how far it stands proud of the band, both as a fraction of the dial
         * radius. Against the arc's own 0.028 r this puts a mark of about five pixels across and
         * fifteen long on a 454 px watch — judged at that size, not on a magnified mock-up.
         */
        /**
         * Tangential width of a mark. Its radial extent is the arc's own thickness, so the mark is
         * a block of the band rather than a tick across it.
         */
        const val NOTCH_STROKE = 0.020f
    }
}
