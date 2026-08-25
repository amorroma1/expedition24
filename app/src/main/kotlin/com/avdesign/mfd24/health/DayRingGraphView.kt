// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
    /**
     * The hour of the local day the hands are drawn at, or a negative to leave them off.
     *
     * Two schematic hands, at the same angles the face puts them: without them a reader has to
     * count round from `00` to find where they are on a ring of ninety-six marks, and the whole
     * point of drawing this round was that they already know that shape.
     */
    private val nowHours: Double = -1.0,
    /** The bin the hand stands in, so the line can be broken where today meets yesterday. */
    private val nowBin: Int = -1,
) : View(context) {

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

    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = HAND
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HAND }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val linePath = Path()
    /** The ends of the radial scale, from the day itself; see [setPulseScale]. */
    private var loBpm = PULSE_FLOOR
    private var hiBpm = PULSE_CEILING

    /** One record, ready to draw: a radius per quarter-hour and its colour, zero for absent. */
    private val traceRadius = FloatArray(DayBins.BIN_COUNT)
    private val traceColor = IntArray(DayBins.BIN_COUNT)

    private val here = FloatArray(2)
    private val other = FloatArray(2)


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
        drawHands(canvas, cx, cy, outer)
    }

    /**
     * The hands, schematically: over the data and deliberately dim, the way a hand on the face
     * crosses the rings without becoming the thing you read. Same mapping as the dial, so a mark
     * under the hour hand here is a mark under the hour hand there.
     */
    private fun drawHands(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        if (nowHours < 0.0) return
        handPaint.strokeWidth = MINUTE_WEIGHT
        hand(canvas, cx, cy, AstroTime.minuteHandAngle(nowHours), outer * MINUTE_LENGTH)
        handPaint.strokeWidth = HOUR_WEIGHT
        hand(canvas, cx, cy, AstroTime.hourHandAngle(nowHours, midnightUp), outer * HOUR_LENGTH)
        canvas.drawCircle(cx, cy, HUB_RADIUS, hubPaint)
    }

    /** One hand, from the hub outward, at a dial angle measured clockwise from twelve. */
    private fun hand(canvas: Canvas, cx: Float, cy: Float, degrees: Float, length: Float) {
        val a = Math.toRadians(degrees - 90.0)
        canvas.drawLine(
            cx, cy,
            cx + (Math.cos(a) * length).toFloat(),
            cy + (Math.sin(a) * length).toFloat(),
            handPaint,
        )
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
        setPulseScale()
        // Labels alternate top and bottom, and the wearer's own floor goes out to the left.
        // Stacked on one radius they collide: fifteen beats is seventeen pixels here, a label
        // is eighteen tall, and a resting rate three beats off a guide buries it completely —
        // which is exactly what the first version drew.
        for ((n, bpm) in PULSE_GUIDES.withIndex()) {
            // A guide outside the day's own range is not drawn: on a quiet day the 120 circle is
            // an empty promise taking up two thirds of the panel, and the scale it implies is
            // what flattens the line into the middle.
            if (bpm < loBpm || bpm > hiBpm) continue
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

        clearTrace()
        for (i in 0 until DayBins.BIN_COUNT) {
            val bpm = hr[i].toInt() and 0xFF
            if (bpm <= DayBins.NO_BPM) continue
            traceRadius[i] = pulseRadius(bpm, inner, outer)
            traceColor[i] = VitalRings.pulseColor(bpm)
        }
        drawTrace(canvas, cx, cy)
    }

    /**
     * The one way this screen draws a record: a smoothed line through the quarter-hours.
     *
     * Marks were honest and hard to read: the eye has to join them itself, and at this size it
     * joins them wrongly — a rise reads as scatter. A line does the joining, so the shape of the
     * night, the climb of a walk and the plateau of an afternoon arrive whole.
     *
     * The rule the marks were protecting survives, and it is the one thing this must not break:
     * **a line is only drawn between two quarter-hours that were both measured.** A gap in the
     * record breaks the line rather than being bridged, so the platform's thin hours still read
     * as absence and never as an invented heartbeat. The seam between today and yesterday breaks
     * it too — the quarter-hours either side of the hand are a day apart.
     *
     * Each piece is drawn separately, from the midpoint before a sample through the sample to the
     * midpoint after it, in that sample's own zone colour: the smoothing is per-point, so the
     * line changes hue where the pulse changes zone rather than carrying one colour throughout.
     */
    private fun drawTrace(canvas: Canvas, cx: Float, cy: Float) {
        linePaint.strokeWidth = LINE_WEIGHT
        for (i in 0 until DayBins.BIN_COUNT) {
            if (traceColor[i] == 0) continue
            val prev = (i + DayBins.BIN_COUNT - 1) % DayBins.BIN_COUNT
            val next = (i + 1) % DayBins.BIN_COUNT
            point(cx, cy, i, traceRadius[i], here)
            linePaint.color = traceColor[i]

            val hasPrev = traceColor[prev] != 0 && joined(prev, i)
            val hasNext = traceColor[next] != 0 && joined(i, next)
            if (!hasPrev && !hasNext) {
                // One measured quarter-hour with nothing either side of it: a dot, because a
                // line of zero length would draw nothing at all and the reading did happen.
                canvas.drawPoint(here[0], here[1], linePaint)
                continue
            }
            // One quadratic per sample, from the midpoint before it to the midpoint after, with
            // the sample itself as the control point. That is what makes the line smooth: the
            // curve passes near each reading rather than cornering on it, and consecutive pieces
            // meet tangentially at the midpoints, so ninety-six of them read as one stroke.
            linePath.reset()
            if (hasPrev) {
                point(cx, cy, prev, traceRadius[prev], other)
                linePath.moveTo((here[0] + other[0]) * 0.5f, (here[1] + other[1]) * 0.5f)
                if (hasNext) {
                    point(cx, cy, next, traceRadius[next], other)
                    linePath.quadTo(
                        here[0], here[1],
                        (here[0] + other[0]) * 0.5f, (here[1] + other[1]) * 0.5f,
                    )
                } else {
                    linePath.lineTo(here[0], here[1])
                }
            } else {
                point(cx, cy, next, traceRadius[next], other)
                linePath.moveTo(here[0], here[1])
                linePath.lineTo((here[0] + other[0]) * 0.5f, (here[1] + other[1]) * 0.5f)
            }
            canvas.drawPath(linePath, linePaint)
        }
    }

    /**
     * Whether two adjacent quarter-hours may be joined: they must be adjacent in time as well as
     * in the array, which fails at the seam where the rolling day meets the one before it.
     */
    private fun joined(a: Int, b: Int): Boolean {
        if (nowBin < 0) return true
        return a != nowBin && b != (nowBin + 1) % DayBins.BIN_COUNT
    }

    private fun clearTrace() {
        java.util.Arrays.fill(traceColor, 0)
    }

    /** The centre of bin [i] at radius [r], in pixels. */
    private fun point(cx: Float, cy: Float, i: Int, r: Float, out: FloatArray) {
        val hours = (i + 0.5) * DayBins.BIN_MINUTES / 60.0
        val a = Math.toRadians(AstroTime.hourHandAngle(hours, midnightUp) - 90.0)
        out[0] = cx + (Math.cos(a) * r).toFloat()
        out[1] = cy + (Math.sin(a) * r).toFloat()
    }

    /**
     * The radial scale: the day's own range, on a logarithm.
     *
     * Fixed 40–150 was drawn first and had both faults at once. A day that never left the
     * sixties used a fifth of the panel and read as a flat circle; a run that touched 170 was
     * *clamped* — the peak flattened against the rim and the graph lied about the one moment
     * anybody would have looked for. So the ends come from the day, padded a little, and the
     * mapping is logarithmic because a pulse is a ratio: fifty to sixty is the same distance as
     * a hundred to a hundred and twenty, which is what a heart rate actually means. A peak
     * cannot leave the circles because the circles are drawn around it.
     */
    private fun setPulseScale() {
        var lo = Int.MAX_VALUE
        var hi = 0
        for (i in 0 until DayBins.BIN_COUNT) {
            val bpm = hr[i].toInt() and 0xFF
            if (bpm <= DayBins.NO_BPM) continue
            if (bpm < lo) lo = bpm
            if (bpm > hi) hi = bpm
        }
        if (restingBpm > DayBins.NO_BPM && restingBpm < lo) lo = restingBpm
        if (hi == 0) {
            loBpm = PULSE_FLOOR
            hiBpm = PULSE_CEILING
            return
        }
        // A little air either side, and never a span so narrow that sensor noise looks like a
        // day: five beats of quiet would otherwise fill the panel with its own rounding.
        loBpm = (lo - PULSE_PAD).coerceAtLeast(PULSE_MIN)
        hiBpm = (hi + PULSE_PAD).coerceAtMost(PULSE_MAX)
        if (hiBpm - loBpm < PULSE_MIN_SPAN) hiBpm = loBpm + PULSE_MIN_SPAN
    }

    private fun pulseRadius(bpm: Int, inner: Float, outer: Float): Float {
        val clamped = bpm.coerceIn(loBpm, hiBpm)
        val lo = Math.log(loBpm.toDouble())
        val hi = Math.log(hiBpm.toDouble())
        val v = Math.log(clamped.toDouble())
        return inner + (outer - inner) * ((v - lo) / (hi - lo)).toFloat()
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

        // A line, like the other two. Bars were drawn first and read as a different instrument
        // on the same screen — three panels, three grammars, and the eye spends its first second
        // working out which is which instead of reading the day. A quarter-hour that was watched
        // and found still is a *measurement of zero*, so it sits on the inner circle rather than
        // leaving a hole; a quarter-hour nobody watched is the hole.
        clearTrace()
        for (i in 0 until DayBins.BIN_COUNT) {
            if (flags[i].toInt() and DayBins.FLAG_SAMPLED == 0) continue
            val effort = VitalRings.effort(steps[i].toInt(), hr[i].toInt() and 0xFF, restingBpm)
            traceRadius[i] = inner + (outer - inner) * effort / 255f
            traceColor[i] = VitalRings.activityColor(effort)
        }
        drawTrace(canvas, cx, cy)
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

        // The night as a line through its own depths, so the shape of it — deep early, lighter
        // toward morning — arrives as a shape. It exists only where there was sleep: the line
        // stops at the edges of the night rather than running along the floor all day.
        clearTrace()
        for (i in 0 until DayBins.BIN_COUNT) {
            if (flags[i].toInt() and DayBins.FLAG_SLEEP == 0) continue
            val depth = VitalRings.sleepDepth(hr[i].toInt() and 0xFF, restingBpm)
            traceRadius[i] = inner + band * (depth + 0.5f)
            traceColor[i] = VitalRings.sleepColor(depth)
        }
        drawTrace(canvas, cx, cy)
    }

    /** A guide's own number, on a scrap of background so the marks cannot swallow it. */
    private fun label(canvas: Canvas, x: Float, y: Float, text: String) {
        val w = labelPaint.measureText(text)
        canvas.drawRect(
            x - w / 2f - 2f, y - labelPaint.textSize * 0.8f, x + w / 2f + 2f, y + 3f, backingPaint,
        )
        canvas.drawText(text, x, y, labelPaint)
    }

    private companion object {
        const val PAD = 20f
        const val MIN_RADIUS = 24f
        const val INNER_FRACTION = 0.34f
        const val BIN_DEGREES = 360f / DayBins.BIN_COUNT
        const val LINE_WEIGHT = 4f

        /** Where the scale sits before a day has said anything. */
        const val PULSE_FLOOR = 40
        const val PULSE_CEILING = 150

        /** Air either side of the day's own range, and the limits of a believable one. */
        const val PULSE_PAD = 4
        const val PULSE_MIN = 35
        const val PULSE_MAX = 210
        const val PULSE_MIN_SPAN = 25
        val PULSE_GUIDES = intArrayOf(45, 60, 90, 120)

        val HOUR_LABELS = arrayOf("00", "06", "12", "18")

        /** The hands: long enough to point, quiet enough not to be read instead of the data. */
        const val HOUR_LENGTH = 0.44f
        const val MINUTE_LENGTH = 0.60f
        const val HOUR_WEIGHT = 3.5f
        const val MINUTE_WEIGHT = 1.6f
        const val HUB_RADIUS = 3.5f
        val HAND = 0xFFBDB6AA.toInt()

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
