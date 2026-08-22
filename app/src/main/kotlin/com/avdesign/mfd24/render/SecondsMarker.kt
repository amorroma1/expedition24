// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.avdesign.mfd24.style.Palette

/**
 * Seconds indicator: a triangular cursor that steps from one minute tick to the next, apex pointing
 * at the centre of the dial.
 *
 * There is no second hand and no seconds ring — only the current second is marked, so the dial stays
 * quiet and the one moving element is unmistakable.
 *
 * The cursor is keyed to the dial's own minute ticks, which is why the fine ring carries 60
 * divisions: it lands dead centre on a tick every second instead of drifting across the markings.
 * Its base is a little wider than the tick pitch, so it reads as a cursor over the scale rather than
 * as one more tick.
 *
 * The path is built pointing straight up and the canvas is rotated around the hub, so stepping costs
 * a rotate and a path fill — no geometry is rebuilt per frame.
 */
class SecondsMarker {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val triangle = Path()

    private var builtLayout = -1

    /** @param fraction position within the current minute, in `[0, 1)` */
    fun draw(canvas: Canvas, g: Geometry, palette: Palette, fraction: Float, layoutGeneration: Int) {
        if (builtLayout != layoutGeneration) {
            build(g)
            builtLayout = layoutGeneration
        }

        val index = (fraction * Geometry.MINUTE_TICKS).toInt()
            .coerceIn(0, Geometry.MINUTE_TICKS - 1)

        paint.color = palette.second
        canvas.save()
        canvas.rotate(index * Geometry.SECONDS_SEGMENT_DEGREES, g.cx, g.cy)
        canvas.drawPath(triangle, paint)
        canvas.restore()
    }

    private companion object {
        /**
         * How far the cursor reaches towards the centre, in device pixels rather than as a fraction
         * of the radius: it is a hairline detail, and scaling it with the dial would make it either
         * invisible or blocky on a screen far from 454 px.
         */
        const val HEIGHT_PX = 8f
    }

    /** Built at the 12 o'clock position: base outwards, apex towards the hub. */
    private fun build(g: Geometry) {
        val baseY = g.cy - g.secondsMarkerBaseRadius
        val half = g.secondsMarkerHalfBase
        triangle.reset()
        triangle.moveTo(g.cx - half, baseY)
        triangle.lineTo(g.cx + half, baseY)
        triangle.lineTo(g.cx, baseY + HEIGHT_PX)
        triangle.close()
    }
}
