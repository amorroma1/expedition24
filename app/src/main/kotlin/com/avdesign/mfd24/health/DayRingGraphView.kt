// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.avdesign.mfd24.astro.AstroTime

/**
 * One of the day's three records, drawn the way the dial draws it: round, midnight at the top,
 * a quarter-hour every 3.75 degrees.
 *
 * The flat column panel this replaces answered *when* well enough and answered *how much* not at
 * all — every panel rescaled itself to its own busiest quarter-hour, so a quiet day and a hard one
 * drew the same picture and no column could be read against a number. Here the radius carries the
 * value on a **fixed** scale, and the scale is drawn: concentric circles at the zone boundaries
 * the rings on the face are built from, each labelled. A glance answers "how fast was my heart at
 * four in the morning, and what zone is that" with no legend to learn.
 *
 * Round rather than straight for the same reason the face is: the reader already knows where four
 * in the morning lives on this watch, so the axis needs nothing but its quarters. Marks stay
 * separate and are never joined — the platform sleeps through some quarter-hours, and a joined
 * line would draw a pulse through every hour nobody watched.
 */
class DayRingGraphView(
    context: Context,
    private val kind: Int,
    private val hr: ByteArray,
    private val steps: ShortArray,
    private val flags: ByteArray,
    private val restingBpm: Int,
    /** The dial's own orientation: noon at the top unless the wearer flipped it. */
    private val midnightUp: Boolean = false,
    /** And the dial's own name for midnight, so the two screens agree letter for letter. */
    private val midnightAs24: Boolean = false,
) : View(context) {

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = GUIDE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GUIDE_TEXT
        typeface = Typeface.MONOSPACE
        textSize = 15f
        textAlign = Paint.Align.CENTER
    }

    private val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HOUR_TEXT
        typeface = Typeface.MONOSPACE
        textSize = 16f
        textAlign = Paint.Align.CENTER
    }

    private val backingPaint = Paint().apply { color = 0xFF000000.toInt() }

    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val outer = minOf(w, h) / 2f - PAD
        if (outer <= MIN_RADIUS) return
        val inner = outer * INNER_FRACTION

        drawHours(canvas, cx, cy, outer)
        when (kind) {
            DayGraphView.KIND_PULSE -> drawPulse(canvas, cx, cy, inner, outer)
            DayGraphView.KIND_ACTIVITY -> drawActivity(canvas, cx, cy, inner, outer)
            else -> drawSleep(canvas, cx, cy, inner, outer)
        }
    }

    /**
     * The quarters of the day, placed and named exactly as the dial places and names them.
     *
     * Through [AstroTime.hourHandAngle], not a private copy of the arithmetic: the graph has to
     * follow the DIAL TOP setting, or a reader who keeps noon up finds their morning on the
     * wrong side of a screen they meant to compare with the face. Midnight takes whichever of
     * `00` and `24` the dial itself is showing, for the same reason.
     */
    private fun drawHours(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        for (hour in 0 until 24 step 6) {
            val a = Math.toRadians(AstroTime.hourHandAngle(hour.toDouble(), midnightUp) - 90.0)
            val r = outer + PAD * 0.62f
            val x = cx + (Math.cos(a) * r).toFloat()
            val y = cy + (Math.sin(a) * r).toFloat() + hourPaint.textSize * 0.36f
            val label = if (hour == 0 && midnightAs24) "24" else HOUR_LABELS[hour / 6]
            canvas.drawText(label, x, y, hourPaint)
        }
    }

    /**
     * Pulse on an absolute scale from [PULSE_FLOOR] to [PULSE_CEILING], with a circle at every
     * rate the ring on the face changes colour at. Absolute on purpose: the whole point of a
     * drawn scale is that 90 sits the same distance out on every day the wearer compares.
     */
    private fun drawPulse(canvas: Canvas, cx: Float, cy: Float, inner: Float, outer: Float) {
        // Labels alternate top and bottom, and the wearer's own floor goes out to the left.
        // Stacked on one radius they collide: fifteen beats is seventeen pixels here, a label
        // is eighteen tall, and a resting rate three beats off a guide buries it completely —
        // which is exactly what the first version drew.
        for ((n, bpm) in PULSE_GUIDES.withIndex()) {
            guidePaint.color = VitalRings.pulseColor(bpm) and 0x00FFFFFF or GUIDE_ALPHA
            val r = pulseRadius(bpm, inner, outer)
            canvas.drawCircle(cx, cy, r, guidePaint)
            if (n % 2 == 0) {
                label(canvas, cx, cy - r, bpm.toString())
            } else {
                label(canvas, cx, cy + r + labelPaint.textSize * 0.8f, bpm.toString())
            }
        }
        if (restingBpm > DayBins.NO_BPM) {
            guidePaint.color = RESTING_GUIDE
            val r = pulseRadius(restingBpm, inner, outer)
            canvas.drawCircle(cx, cy, r, guidePaint)
            label(canvas, cx - r, cy + labelPaint.textSize * 0.35f, "R" + restingBpm)
        }

        markPaint.strokeWidth = MARK_WEIGHT
        for (i in 0 until DayBins.BIN_COUNT) {
            val bpm = hr[i].toInt() and 0xFF
            if (bpm <= DayBins.NO_BPM) continue
            markPaint.color = VitalRings.pulseColor(bpm)
            arc(canvas, cx, cy, pulseRadius(bpm, inner, outer), i)
        }
    }

    private fun pulseRadius(bpm: Int, inner: Float, outer: Float): Float {
        val clamped = bpm.coerceIn(PULSE_FLOOR, PULSE_CEILING)
        return inner + (outer - inner) * (clamped - PULSE_FLOOR) /
            (PULSE_CEILING - PULSE_FLOOR).toFloat()
    }

    /**
     * Effort as a bar out from the inner circle, quartered by guides — the same effort the
     * activity ring on the face draws, so a long bar here and a bright arc there are one fact.
     */
    private fun drawActivity(canvas: Canvas, cx: Float, cy: Float, inner: Float, outer: Float) {
        canvas.drawCircle(cx, cy, inner, guidePaint)
        for (q in 1..4) {
            guidePaint.color = if (q == 4) GUIDE_BRIGHT else GUIDE
            canvas.drawCircle(cx, cy, inner + (outer - inner) * q / 4f, guidePaint)
        }
        // Inside its own circle, not on it: on it the label sits exactly where the hour numeral
        // outside the dial is, and the two overprint.
        label(canvas, cx, cy - outer + labelPaint.textSize * 1.1f, "MAX")

        markPaint.strokeWidth = BAR_WEIGHT
        for (i in 0 until DayBins.BIN_COUNT) {
            if (flags[i].toInt() and DayBins.FLAG_SAMPLED == 0) continue
            val effort = VitalRings.effort(steps[i].toInt(), hr[i].toInt() and 0xFF, restingBpm)
            if (effort <= 0) continue
            markPaint.color = VitalRings.activityColor(effort)
            // A bar rather than a point: activity is a quantity, and a quantity reads as
            // something filled from a baseline. Drawn as stacked arcs because a radial line
            // would taper the wrong way — the outer end of a wedge is wider than its root.
            val top = inner + (outer - inner) * effort / 255f
            var r = inner
            while (r < top) {
                arc(canvas, cx, cy, r, i)
                r += BAR_WEIGHT
            }
        }
    }

    /**
     * Sleep in three concentric bands, deep outermost. Bands rather than a height, for the same
     * reason the flat panel used them: sleep has depths and not amounts, and a reader given a
     * height will add it up.
     */
    private fun drawSleep(canvas: Canvas, cx: Float, cy: Float, inner: Float, outer: Float) {
        val band = (outer - inner) / 3f
        for (d in 0..3) {
            guidePaint.color = if (d == 0 || d == 3) GUIDE_BRIGHT else GUIDE
            canvas.drawCircle(cx, cy, inner + band * d, guidePaint)
        }
        label(canvas, cx, cy - outer + band * 0.5f, "DEEP")
        label(canvas, cx, cy - inner - band * 0.5f, "LIGHT")

        markPaint.strokeWidth = band * 0.82f
        for (i in 0 until DayBins.BIN_COUNT) {
            if (flags[i].toInt() and DayBins.FLAG_SLEEP == 0) continue
            val depth = VitalRings.sleepDepth(hr[i].toInt() and 0xFF, restingBpm)
            markPaint.color = VitalRings.sleepColor(depth)
            // Depth 0 is the lightest and sits innermost, so a night reads outward as it deepens.
            arc(canvas, cx, cy, inner + band * (depth + 0.5f), i)
        }
    }

    /** A guide's own number, on a scrap of background so the marks cannot swallow it. */
    private fun label(canvas: Canvas, x: Float, y: Float, text: String) {
        val w = labelPaint.measureText(text)
        canvas.drawRect(
            x - w / 2f - 2f, y - labelPaint.textSize * 0.8f, x + w / 2f + 2f, y + 3f, backingPaint,
        )
        canvas.drawText(text, x, y, labelPaint)
    }

    /** Bin [i]'s own 3.75 degrees at radius [r], on the dial's own axis. */
    private fun arc(canvas: Canvas, cx: Float, cy: Float, r: Float, i: Int) {
        box.set(cx - r, cy - r, cx + r, cy + r)
        val hours = i * DayBins.BIN_MINUTES / 60.0
        val start = AstroTime.hourHandAngle(hours, midnightUp) - 90f
        canvas.drawArc(box, start, BIN_DEGREES * 0.92f, false, markPaint)
    }

    private companion object {
        const val PAD = 20f
        const val MIN_RADIUS = 24f
        const val INNER_FRACTION = 0.34f
        const val BIN_DEGREES = 360f / DayBins.BIN_COUNT
        const val MARK_WEIGHT = 5f
        const val BAR_WEIGHT = 3f

        /** The fixed pulse window: under 40 is a sensor error, over 150 is not this face's job. */
        const val PULSE_FLOOR = 40
        const val PULSE_CEILING = 150
        val PULSE_GUIDES = intArrayOf(45, 60, 90, 120)

        val HOUR_LABELS = arrayOf("00", "06", "12", "18")

        val GUIDE = 0xFF3E3A34.toInt()
        val GUIDE_BRIGHT = 0xFF5A544B.toInt()
        /** The zone circles carry their own hue at this alpha: present, and never louder than
         *  the marks they are there to place. */
        val GUIDE_ALPHA = 0x9A000000.toInt()
        val GUIDE_TEXT = 0xFF8A857C.toInt()
        val HOUR_TEXT = 0xFFA8A49C.toInt()
        val RESTING_GUIDE = 0xFF8A6A50.toInt()
    }
}
