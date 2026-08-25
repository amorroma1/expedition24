// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * One of the day's three records, drawn across a screen instead of around a dial.
 *
 * The rings on the face answer "what kind of day was that" in four steps of colour, which is all
 * a nine-pixel arc can honestly carry. This answers "and what happened at four in the
 * afternoon", which needs a horizontal axis and a screen to put it on. Same data, same colours,
 * different question — and keeping the two apart is what lets the dial stay quiet.
 *
 * Columns, never a joined line: the samples are quarter-hours and the platform sleeps through
 * some of them, so a line would draw a heartbeat, a walk or a sleep straight across every hour
 * nobody watched. A missing quarter-hour is a missing column, and the holes read as what they
 * are.
 */
class DayGraphView(
    context: Context,
    private val kind: Int,
    private val hr: ByteArray,
    private val steps: ShortArray,
    private val flags: ByteArray,
    private val restingBpm: Int,
) : View(context) {

    private val columnPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = AXIS
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS
        textSize = 17f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Six-hourly gridlines: the eye needs somewhere to stand to read an hour off a width.
        for (q in 1 until 4) {
            val x = w * q / 4f
            canvas.drawLine(x, 0f, x, h, axisPaint)
        }

        when (kind) {
            KIND_PULSE -> drawPulse(canvas, w, h)
            KIND_ACTIVITY -> drawActivity(canvas, w, h)
            else -> drawSleep(canvas, w, h)
        }
    }

    private fun drawPulse(canvas: Canvas, w: Float, h: Float) {
        var maxBpm = 0
        var minBpm = Int.MAX_VALUE
        for (b in hr) {
            val bpm = b.toInt() and 0xFF
            if (bpm <= DayBins.NO_BPM) continue
            if (bpm > maxBpm) maxBpm = bpm
            if (bpm < minBpm) minBpm = bpm
        }
        if (maxBpm == 0) return
        val floor = (if (restingBpm > DayBins.NO_BPM) minOf(minBpm, restingBpm) else minBpm) - 5
        val span = (maxBpm + 5 - floor).coerceAtLeast(1)

        // The resting rate as a line of its own: every column is read against it, and it is the
        // number the rings' own zones are built from.
        if (restingBpm > DayBins.NO_BPM) {
            val y = h - h * (restingBpm - floor) / span
            canvas.drawLine(0f, y, w, y, axisPaint)
            canvas.drawText(restingBpm.toString(), 2f, y - 4f, labelPaint)
        }

        val columnWidth = w / DayBins.BIN_COUNT
        for (i in 0 until DayBins.BIN_COUNT) {
            val bpm = hr[i].toInt() and 0xFF
            if (bpm <= DayBins.NO_BPM) continue
            val y = h - h * (bpm - floor) / span
            columnPaint.color = VitalRings.pulseColor(bpm)
            canvas.drawRect(columnWidth * i, y, columnWidth * i + columnWidth + 0.5f, h, columnPaint)
        }
    }

    private fun drawActivity(canvas: Canvas, w: Float, h: Float) {
        // Effort, not steps: the ring and the graph answer the same question, and it is whether
        // the body worked rather than whether the feet moved.
        var peak = 0
        for (i in 0 until DayBins.BIN_COUNT) {
            val v = VitalRings.effort(steps[i].toInt(), hr[i].toInt() and 0xFF, restingBpm)
            if (v > peak) peak = v
        }
        if (peak == 0) return
        // Scaled to the day's own busiest quarter-hour, so a quiet day fills the panel as
        // readably as a hard one — the same reason the pulse graph does not start at zero.
        val columnWidth = w / DayBins.BIN_COUNT
        for (i in 0 until DayBins.BIN_COUNT) {
            val f = flags[i].toInt() and 0xFF
            if (f and DayBins.FLAG_SAMPLED == 0) continue
            val v = VitalRings.effort(steps[i].toInt(), hr[i].toInt() and 0xFF, restingBpm)
            if (v <= 0) continue
            val top = h - h * v / peak
            columnPaint.color = VitalRings.activityColor(v)
            canvas.drawRect(columnWidth * i, top, columnWidth * i + columnWidth + 0.5f, h, columnPaint)
        }
    }

    private fun drawSleep(canvas: Canvas, w: Float, h: Float) {
        // Three bands rather than a height: sleep has depths, not amounts, and drawing it as a
        // quantity would invite a reader to add it up.
        val columnWidth = w / DayBins.BIN_COUNT
        for (i in 0 until DayBins.BIN_COUNT) {
            val f = flags[i].toInt() and 0xFF
            if (f and DayBins.FLAG_SLEEP == 0) continue
            val depth = VitalRings.sleepDepth(hr[i].toInt() and 0xFF, restingBpm)
            val top = h * depth / 3f
            columnPaint.color = VitalRings.sleepColor(depth)
            canvas.drawRect(
                columnWidth * i, top, columnWidth * i + columnWidth + 0.5f, h, columnPaint,
            )
        }
    }

    companion object {
        const val KIND_PULSE = 0
        const val KIND_ACTIVITY = 1
        const val KIND_SLEEP = 2

        private val AXIS = 0xFF4A463F.toInt()
    }
}
