// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The day's pulse as a graph — the detail the rings deliberately do not carry.
 *
 * The rings answer "what kind of day was that" at a glance and in four steps of colour; this
 * answers "and what exactly happened at four in the afternoon", which is a question worth a
 * screen rather than a ring. It is the same split the dial keeps everywhere: the estimate on
 * the face, the figures behind a tap.
 *
 * Drawn as columns rather than as a joined line, because the samples are quarter-hours and not a
 * continuous trace: a line would draw a stroke straight through the hours the platform slept
 * past, inventing a heartbeat for every gap. A missing quarter-hour is simply a missing column,
 * and the eye reads the holes as what they are.
 */
class PulseGraphView(
    context: Context,
    private val hr: ByteArray,
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
        textSize = 18f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // The scale runs from a little under the day's own resting rate to a little over its
        // busiest quarter-hour, so a quiet day fills the panel as readably as a hard one.
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
        val ceiling = maxBpm + 5
        val span = (ceiling - floor).coerceAtLeast(1)

        // Six-hourly gridlines: the eye needs somewhere to stand to read an hour off the width.
        for (q in 1 until 4) {
            val x = w * q / 4f
            canvas.drawLine(x, 0f, x, h, axisPaint)
        }

        // The resting rate as a line of its own — every column is read against it, and it is the
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
            val x = columnWidth * i
            val y = h - h * (bpm - floor) / span
            columnPaint.color = VitalRings.pulseColor(bpm)
            canvas.drawRect(x, y, x + columnWidth + 0.5f, h, columnPaint)
        }
    }

    private companion object {
        val AXIS = 0xFF4A463F.toInt()
    }
}
