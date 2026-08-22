// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * A QR code on a round screen: white panel, black modules, one caption above and one below.
 *
 * Shared between the log export and the ABOUT screen's repository link — the drawing is
 * payload-agnostic, and two hand-kept copies of the quiet-zone geometry would diverge the first
 * time one was fixed. The bottom caption is a function so a caller whose status changes while
 * the code is up (the AFSK burst ending) redraws with `invalidate()` and nothing more.
 */
internal class QrPanelView(
    context: Context,
    payload: String,
    private val topCaption: String,
    /**
     * The line under the code, or null for none.
     *
     * Null is the usual answer. A round screen clips a long line at both ends, and a caption that
     * only says what the code obviously is — "scan this" — is noise the second time you see it.
     * The log export keeps one because there it carries live state: whether the tones are still
     * playing.
     */
    private val bottomCaption: (() -> String)? = null,
    private val onTap: (() -> Unit)? = null,
) : View(context) {

    private val matrix = QrCode.encode(payload)

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val modulePaint = Paint().apply { color = Color.BLACK }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = 0xFFFFB000.toInt()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handler = onTap ?: return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) handler()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        // The largest square a round screen can show whole, quiet zone included: the inscribed
        // square. Corners of anything bigger fall off the bezel, and the corners are where the
        // finder patterns live.
        val side = (minOf(width, height) / SQRT2).toInt().toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        canvas.drawRect(left, top, left + side, top + side, panelPaint)

        val n = matrix.size
        val module = side / (n + 2 * QUIET_MODULES)
        val originX = left + QUIET_MODULES * module
        val originY = top + QUIET_MODULES * module
        for (r in 0 until n) {
            val row = matrix[r]
            for (c in 0 until n) {
                if (row[c]) {
                    canvas.drawRect(
                        originX + c * module,
                        originY + r * module,
                        originX + (c + 1) * module,
                        originY + (r + 1) * module,
                        modulePaint,
                    )
                }
            }
        }

        captionPaint.textSize = height * 0.045f
        canvas.drawText(topCaption, width / 2f, top - height * 0.025f, captionPaint)
        bottomCaption?.let {
            canvas.drawText(it(), width / 2f, height - height * 0.045f, captionPaint)
        }
    }

    private companion object {
        const val SQRT2 = 1.41421356f

        /** The spec asks four; the white panel provides them on every side. */
        const val QUIET_MODULES = 4
    }
}
