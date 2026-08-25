// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.avdesign.mfd24.style.Palette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Nadir band: the hours between sunrise and sunset at the wearer's position, shaded across the
 * hour-tick band.
 *
 * Drawn per frame between the dial's background and its scale, so it can slide and grow under the
 * ticks while a change of position or time zone eases into place.
 */
class DaylightLayer {

    private val alarmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sunRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * @param startAngle leading edge, degrees clockwise from 12 o'clock
     * @param sweepAngle extent in degrees; zero draws nothing
     */
    fun draw(canvas: Canvas, g: Geometry, color: Int, startAngle: Float, sweepAngle: Float) {
        if (sweepAngle <= 0f) return
        paint.color = color
        paint.strokeWidth = g.daylightWidth
        canvas.drawArc(g.daylightTrack, startAngle - QUARTER_TURN, sweepAngle, false, paint)
    }

    /**
     * Calendar spans: short arcs on the band's outer edge, saying that an hour is spoken for and
     * nothing else. Overlapping events are drawn over one another on purpose — three meetings at
     * ten are still just "ten is busy" to a dial, and a wearer who needs to know which opens the
     * calendar.
     */
    fun drawEvents(
        canvas: Canvas,
        g: Geometry,
        color: Int,
        startAngles: FloatArray,
        sweeps: FloatArray,
        count: Int,
    ) {
        if (count == 0) return
        paint.color = color
        paint.strokeWidth = g.eventWidth
        var i = 0
        while (i < count) {
            canvas.drawArc(g.eventTrack, startAngles[i] - QUARTER_TURN, sweeps[i], false, paint)
            i++
        }
    }

    /**
     * The next alarm, as a notch across the band — the incident mark's own shape, because it is
     * the same kind of statement: a single instant that has to be told apart from a span.
     */
    fun drawAlarm(canvas: Canvas, g: Geometry, color: Int, angle: Float) {
        if (angle.isNaN()) return
        alarmPaint.color = color
        alarmPaint.strokeWidth = g.alarmStrokeWidth
        val radians = Math.toRadians(angle.toDouble() - QUARTER_TURN)
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        canvas.drawLine(
            g.cx + dx * g.alarmInnerRadius, g.cy + dy * g.alarmInnerRadius,
            g.cx + dx * g.alarmOuterRadius, g.cy + dy * g.alarmOuterRadius,
            alarmPaint,
        )
    }

    /**
     * The sun, as a disc riding its own band.
     *
     * This is the solar compass: the mark sits at apparent solar time, so turning the watch until
     * it points at the real sun orients the dial — noon towards the equator. The disc is the lume
     * at full strength ringed in the background colour, which is the same recipe the readout's
     * halo uses: it has to separate from a band already drawn in its own hue.
     *
     * @param angle degrees clockwise from 12 o'clock, already eased with the band it rides
     */
    fun drawSun(canvas: Canvas, g: Geometry, disc: Int, rim: Int, angle: Float) {
        val radians = Math.toRadians(angle.toDouble() - QUARTER_TURN)
        val bandRadius = g.daylightTrack.width() / 2f
        val x = g.cx + cos(radians).toFloat() * bandRadius
        val y = g.cy + sin(radians).toFloat() * bandRadius
        val r = g.daylightWidth * SUN_RADIUS
        sunPaint.color = disc
        sunRimPaint.color = rim
        sunRimPaint.strokeWidth = r * 0.45f
        canvas.drawCircle(x, y, r, sunPaint)
        canvas.drawCircle(x, y, r, sunRimPaint)
    }

    private val moonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val moonOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val moonRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val moonLit = Path()
    private val moonCircle = RectF()
    private val moonTerminator = RectF()

    /**
     * The moon on the sky ring: an outlined disc whose grey fill is the honest phase.
     *
     * The lit side faces the sun's own mark, because that is where the light actually comes
     * from — a crescent pointing away from the sun would be an ornament, and this dial does not
     * wear ornaments. The shape is the classical two-arc construction: half the disc's edge,
     * closed by the terminator, an ellipse whose semi-axis is `|2f - 1| * r` — a straight line at
     * the quarters, the full circle at full.
     *
     * @param moonAngle dial degrees clockwise from 12 o'clock, from the moon's hour angle
     * @param litFraction illuminated fraction of the disc, 0..1
     * @param sunAngle the solar mark's dial degrees, toward which the lit side turns
     */
    fun drawMoon(
        canvas: Canvas,
        g: Geometry,
        fill: Int,
        rim: Int,
        moonAngle: Float,
        litFraction: Float,
        sunAngle: Float,
    ) {
        val bandRadius = g.daylightTrack.width() / 2f
        val moonRad = Math.toRadians(moonAngle.toDouble() - QUARTER_TURN)
        val x = g.cx + cos(moonRad).toFloat() * bandRadius
        val y = g.cy + sin(moonRad).toFloat() * bandRadius
        val r = g.daylightWidth * SUN_RADIUS

        val sunRad = Math.toRadians(sunAngle.toDouble() - QUARTER_TURN)
        val sx = g.cx + cos(sunRad).toFloat() * bandRadius
        val sy = g.cy + sin(sunRad).toFloat() * bandRadius
        val limbDeg = Math.toDegrees(atan2((sy - y).toDouble(), (sx - x).toDouble())).toFloat()

        moonRimPaint.color = rim
        moonRimPaint.strokeWidth = r * 0.45f
        moonOutlinePaint.color = fill
        moonOutlinePaint.strokeWidth = r * 0.22f
        moonFillPaint.color = fill

        canvas.drawCircle(x, y, r, moonRimPaint)

        canvas.save()
        // Local frame with the lit limb along +x; build the phase there and let the canvas turn.
        canvas.rotate(limbDeg, x, y)
        moonCircle.set(x - r, y - r, x + r, y + r)
        val f = litFraction.coerceIn(0f, 1f)
        if (f >= 0.99f) {
            canvas.drawCircle(x, y, r, moonFillPaint)
        } else if (f > 0.01f) {
            val a = abs(2f * f - 1f) * r
            moonTerminator.set(x - a, y - r, x + a, y + r)
            moonLit.reset()
            // The sunward half of the edge, top to bottom...
            moonLit.arcTo(moonCircle, -90f, 180f, true)
            // ...closed by the terminator: bulging sunward before half, away from it after.
            moonLit.arcTo(moonTerminator, 90f, if (f >= 0.5f) 180f else -180f, false)
            moonLit.close()
            canvas.drawPath(moonLit, moonFillPaint)
        }
        canvas.restore()

        canvas.drawCircle(x, y, r, moonOutlinePaint)
    }

    private companion object {
        const val QUARTER_TURN = 90f

        /** Disc radius against the band's width: inside it, clearly of it. */
        const val SUN_RADIUS = 0.42f
    }
}
