// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * The wellness face's rings: a quarter-hour of the day, stroked as one small arc, on whichever
 * of the three tracks it belongs to.
 *
 * The layer knows nothing about pulses or sleep — it takes a track, a set of angles and a set of
 * colours, and a colour of zero means the bin is not drawn at all. That last part is the whole
 * grammar of the face: a gap is a quarter-hour nobody watched, and it must be impossible to
 * confuse with one that was watched and found still, so bins are drawn edge to edge and only
 * absence leaves a hole.
 *
 * A second kind of hole is deliberate: the caller may name a short window that is not drawn at
 * all, the half-hour ahead of the hour hand. Without it the ring runs continuously past the
 * hand's tip into yesterday's quarter-hours, and there is nothing on a full circle to say which
 * arc is *now* — the day and the day before meet with no seam. The gap is the seam.
 *
 * The arrays are the caller's and the angles arrive precomputed — `render()` allocates nothing,
 * and the rings re-angle per frame so a zone glide or a flip of the dial carries them with
 * everything else on the hour scale.
 */
class ActivityTrailLayer {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    /**
     * Strokes [count] bins along [track].
     *
     * @param startAngles bin start angles, degrees clockwise from twelve o'clock
     * @param sweep one bin's width in degrees, the same for every bin
     * @param colors the colour each bin takes, alpha included; zero means "do not draw"
     * @param weights each bin's stroke as a fraction of [width] — the second channel, which is
     *   what lets the hues stay calm and still be read at a glance
     * @param skipFrom first bin of the clear window ahead of the hand, or a negative for none
     * @param skipCount how many bins that window holds, wrapping past the end of the day
     */
    fun draw(
        canvas: Canvas,
        track: RectF,
        startAngles: FloatArray,
        sweep: Float,
        colors: IntArray,
        weights: FloatArray,
        count: Int,
        width: Float,
        skipFrom: Int = -1,
        skipCount: Int = 0,
    ) {
        var i = 0
        while (i < count) {
            val color = colors[i]
            if (color != 0 && !inSkip(i, count, skipFrom, skipCount)) {
                paint.color = color
                paint.strokeWidth = width * weights[i]
                // Canvas measures arcs from three o'clock; the dial measures from twelve.
                canvas.drawArc(track, startAngles[i] - QUARTER_TURN, sweep, false, paint)
            }
            i++
        }
    }

    /**
     * Whether bin [i] falls in the clear window, which wraps: the half-hour ahead of the hand at
     * a quarter to midnight runs into the top of the day.
     */
    private fun inSkip(i: Int, count: Int, from: Int, length: Int): Boolean {
        if (from < 0 || length <= 0) return false
        var k = 0
        while (k < length) {
            if ((from + k) % count == i) return true
            k++
        }
        return false
    }

    private companion object {
        const val QUARTER_TURN = 90f
    }
}
