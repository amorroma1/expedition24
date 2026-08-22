// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import com.avdesign.mfd24.style.Palette
import com.avdesign.mfd24.text.TextBuf

/**
 * The static half of the watch face, rasterised once and blitted as textured quads.
 *
 * It comes in **two** layers rather than one because the daylight band has to sit between them:
 * under the ticks and numerals, over the background. Baking the band into a single bitmap meant
 * rebuilding a 454 x 454 raster whenever it moved, which is fine once a day and impossible at 60
 * frames a second while it animates across a change of position. Two cached bitmaps cost one extra
 * blit and buy an arc that can move freely.
 *
 * Neither layer depends on the time, the position or the shift — only on the surface size and the
 * user style — so both rebuild about as often as the user changes their mind.
 */
class DialLayer {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val minuteTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    private val hourTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    private val cardinalTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }

    private val numeralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val metrics = Paint.FontMetrics()

    private var background: Bitmap? = null
    private var scale: Bitmap? = null
    private var lastStyleGeneration = -1
    private var lastMidnightAs24 = false

    /**
     * Brings both bitmaps up to date. Call once per frame, before [background] and [scale].
     *
     * Rebuilding from inside the two accessors instead let them disagree about the cache key: the
     * first was given no `midnightAs24` to check, so a change to that setting was only noticed by
     * the second — which then recycled the background bitmap the first had already handed to the
     * canvas. On a hardware canvas that bitmap is still referenced until the frame is posted.
     */
    fun prepare(geometry: Geometry, palette: Palette, styleGeneration: Int, midnightAs24: Boolean) {
        rebuildIfStale(geometry, palette, styleGeneration, midnightAs24)
    }

    /** Opaque: background wash, horizon glow and the two bezel rings. Blit this first. */
    fun background(): Bitmap = background!!

    /** Transparent: the 24-hour and 60-minute scales. Blit this over the daylight band. */
    fun scale(): Bitmap = scale!!

    fun recycle() {
        background?.recycle()
        scale?.recycle()
        background = null
        scale = null
        lastStyleGeneration = -1
    }

    private fun rebuildIfStale(
        g: Geometry,
        palette: Palette,
        styleGeneration: Int,
        midnightAs24: Boolean,
    ) {
        val cachedBackground = background
        val cachedScale = scale
        if (cachedBackground != null && cachedScale != null &&
            !cachedBackground.isRecycled && !cachedScale.isRecycled &&
            cachedBackground.width == g.width && cachedBackground.height == g.height &&
            styleGeneration == lastStyleGeneration && midnightAs24 == lastMidnightAs24
        ) {
            return
        }
        cachedBackground?.recycle()
        cachedScale?.recycle()
        lastStyleGeneration = styleGeneration
        lastMidnightAs24 = midnightAs24

        val fresh = Bitmap.createBitmap(g.width, g.height, Bitmap.Config.ARGB_8888)
        drawBackground(Canvas(fresh), g, palette)
        background = fresh

        val freshScale = Bitmap.createBitmap(g.width, g.height, Bitmap.Config.ARGB_8888)
        freshScale.eraseColor(Color.TRANSPARENT)
        drawScale(Canvas(freshScale), g, palette, midnightAs24)
        scale = freshScale
    }

    private fun drawBackground(canvas: Canvas, g: Geometry, palette: Palette) {
        canvas.drawColor(palette.background)

        // A faint horizon wash keeps the dial from reading as a flat black rectangle on OLED.
        glowPaint.shader = RadialGradient(
            g.cx, g.cy, g.r,
            palette.horizon, palette.background,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(g.cx, g.cy, g.r, glowPaint)
        glowPaint.shader = null

        outerRingPaint.color = palette.lumeFaint
        outerRingPaint.strokeWidth = g.ringWidth
        canvas.drawOval(g.outerRing, outerRingPaint)

        // Bed of the duty arc, which doubles as the dial's inner decorative ring.
        innerRingPaint.color = Palette.withAlpha(palette.lume, 0x2A)
        innerRingPaint.strokeWidth = g.ringWidth * 0.7f
        canvas.drawOval(g.dutyArcTrack, innerRingPaint)
    }

    /**
     * Draws the 24-hour and 60-minute scales directly, bypassing the cache.
     *
     * Always-on needs them in a different palette from interactive, and caching a second pair of
     * 454 x 454 bitmaps to hold it would cost more memory than the drawing costs time: ambient
     * renders once a minute, so 84 lines and 24 short text runs are free. Same code as the cached
     * path, so the two cannot drift apart.
     */
    fun drawScaleDirect(canvas: Canvas, g: Geometry, palette: Palette, midnightAs24: Boolean) =
        drawScale(canvas, g, palette, midnightAs24)

    private fun drawScale(canvas: Canvas, g: Geometry, palette: Palette, midnightAs24: Boolean) {
        // Graduated minute ring: hairlines for the minutes, heavier marks every five so the eye can
        // land on a five without counting round from the top.
        minuteTickPaint.color = palette.lumeFaint
        minuteTickPaint.strokeWidth = g.minuteTickWidth
        canvas.drawLines(g.minuteTicks, minuteTickPaint)

        minuteTickPaint.color = palette.lumeDim
        minuteTickPaint.strokeWidth = g.minuteFiveTickWidth
        canvas.drawLines(g.minuteFiveTicks, minuteTickPaint)

        hourTickPaint.color = palette.lumeDim
        hourTickPaint.strokeWidth = g.hourTickWidth
        canvas.drawLines(g.hourTicks, hourTickPaint)

        cardinalTickPaint.color = palette.lume
        cardinalTickPaint.strokeWidth = g.cardinalTickWidth
        canvas.drawLines(g.cardinalTicks, cardinalTickPaint)

        numeralPaint.textSize = g.hourLabelSize
        numeralPaint.getFontMetrics(metrics)
        val numeralOffset = -(metrics.ascent + metrics.descent) / 2f
        for (h in 0 until Geometry.HOUR_TICKS) {
            numeralPaint.color = if (h % 6 == 0) palette.lume else palette.lumeDim
            // Midnight reads as either the start or the end of the day, depending on taste.
            val label = if (h == 0 && midnightAs24) TextBuf.LIT_HOUR_24 else g.hourLabels[h]
            canvas.drawText(
                label, 0, 2,
                g.hourLabelX[h], g.hourLabelY[h] + numeralOffset,
                numeralPaint,
            )
        }
    }
}
