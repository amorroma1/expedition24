// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * The comm-window arcs: medium-weight strokes hugging the hour-tick ring, direct-to-Earth on the
 * inner edge and relay passes on the outer, both in the palette's third hue — the one that is
 * neither the dial's nor the duty arc's, exactly the rule the incident marks live by, because
 * both say the same kind of thing: *this span of the day is different*.
 *
 * The two lines separate by track, not by colour or weight: a fourth blue-free hue does not
 * exist, and the radius is what the eye reads anyway — inside the ticks is the rover's own link,
 * outside is what flies over it. Arcs, like every span on this dial, so a window reads in the
 * same grammar as the duty and the daylight.
 *
 * Drawn in both draw modes — the windows are the point of this instrument, and an operator
 * glancing at a wrist in ambient is exactly who they are for — but not on the low-bit sparse
 * face, which keeps only the duty by its standing rule. Angles arrive precomputed: `render()`
 * allocates nothing, so the caller owns the FloatArray and this layer only strokes it.
 */
class CommWindowLayer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    /**
     * Strokes [count] windows from [arcs] — (startAngle CW from 12, sweep) pairs, degrees —
     * along [track] at [strokeWidth].
     */
    fun draw(
        canvas: Canvas,
        track: RectF,
        color: Int,
        strokeWidth: Float,
        arcs: FloatArray,
        count: Int,
    ) {
        if (count == 0) return
        paint.color = color
        paint.strokeWidth = strokeWidth
        var i = 0
        while (i < count) {
            // Canvas measures arcs from 3 o'clock; the dial measures from 12.
            canvas.drawArc(track, arcs[2 * i] - QUARTER_TURN, arcs[2 * i + 1], false, paint)
            i++
        }
    }

    private companion object {
        const val QUARTER_TURN = 90f
    }
}
