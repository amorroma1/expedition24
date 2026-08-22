// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import com.avdesign.mfd24.astro.AstroTime
import com.avdesign.mfd24.astro.PlanetMode
import com.avdesign.mfd24.style.Palette
import com.avdesign.mfd24.text.TextBuf

/**
 * Always-on rendering, tuned for an OLED panel that will sit on this image for hours.
 *
 * Three defences against burn-in, and which face gets which matters:
 *  - **Low fill** (sparse face). Pure black ground, hollow hands, four cardinal ticks and one line
 *    of text. The lit area works out around 2 % of the dial, comfortably inside the 10 % budget.
 *  - **Drift** (sparse face only). The frame walks a four-position cycle, one step per minute, so
 *    no pixel is driven continuously. The *full* ambient face does not drift at all — see the note
 *    at the bottom of this file — and leans on the blue-free palette and [applyHalfDensity], whose
 *    alternating phase is its own duty-cycle halving.
 *  - **Low-bit safety.** With `hasLowBitAmbient` the paints drop antialiasing and the colour is
 *    quantised, because dithered edges cannot be represented and end up as noise.
 */
class AmbientLayer {

    private val handPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
    }
    private val tickPaint = Paint().apply { strokeCap = Paint.Cap.BUTT }
    private val hubPaint = Paint().apply { style = Paint.Style.STROKE }
    private val textPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    private val zulu = TextBuf(16)

    /**
     * A checkerboard that removes every other pixel, punched over the finished frame.
     *
     * Two things at once: half the lit subpixels is half the always-on power, and half the duty
     * cycle on each of them. The second only holds because the **phase alternates every minute**
     * — a fixed checkerboard lights exactly the same subpixels forever and would simply burn a
     * checkerboard version of the dial into the panel instead of a solid one.
     *
     * This is what replaced the moving frame. A one-pixel phase flip is invisible where a sliding
     * dial is not, and it protects the panel the same way.
     */
    private val halfDensityTile = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
        setPixel(0, 0, Color.BLACK)
        setPixel(1, 1, Color.BLACK)
        setPixel(1, 0, Color.TRANSPARENT)
        setPixel(0, 1, Color.TRANSPARENT)
    }

    private val halfDensityShader =
        BitmapShader(halfDensityTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

    private val halfDensityMatrix = Matrix()

    private val halfDensityPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
        shader = halfDensityShader
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private var veilShader: RadialGradient? = null
    private var veilLayout = -1
    private val veilMatrix = Matrix()
    private val veilPaint = Paint()

    /**
     * Darkens whatever the brightness front has not reached yet.
     *
     * One gradient is built per layout and then *scaled* by a local matrix, because a
     * `RadialGradient` cannot change its radius after construction and building one per frame would
     * allocate sixty times a second.
     *
     * @param radius how far full brightness has reached, in dial radii
     * @param alpha overall strength, which reaches zero exactly as the front finishes, so the veil
     *   cannot leave a residue behind on the settled face
     */
    fun applyWakeVeil(canvas: Canvas, g: Geometry, radius: Float, alpha: Float, layout: Int) {
        if (alpha <= 0f) return
        if (veilShader == null || veilLayout != layout) {
            veilShader = RadialGradient(
                g.cx, g.cy, g.r,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0f, VEIL_CLEAR_STOP, 1f),
                Shader.TileMode.CLAMP,
            )
            veilLayout = layout
        }
        // Never scale to zero: a degenerate matrix leaves the shader undefined.
        val scale = if (radius < 0.02f) 0.02f else radius
        veilMatrix.setScale(scale, scale, g.cx, g.cy)
        veilShader!!.setLocalMatrix(veilMatrix)
        veilPaint.shader = veilShader
        veilPaint.alpha = (alpha * VEIL_MAX_ALPHA).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, g.width.toFloat(), g.height.toFloat(), veilPaint)
    }

    /** Thins the frame already on [canvas] to every other pixel. Call last. */
    fun applyHalfDensity(canvas: Canvas, g: Geometry, epochMillis: Long) {
        val phase = Math.floorMod(Math.floorDiv(epochMillis, 60_000L), 2L).toFloat()
        halfDensityMatrix.setTranslate(phase, 0f)
        halfDensityShader.setLocalMatrix(halfDensityMatrix)
        canvas.drawRect(0f, 0f, g.width.toFloat(), g.height.toFloat(), halfDensityPaint)
    }

    /**
     * Burn-in drift for the sparse low-bit face. Read back through fields rather than returned,
     * because the drawing path does not allocate.
     */
    @JvmField
    var driftX: Float = 0f

    @JvmField
    var driftY: Float = 0f

    /**
     * Walks the sparse low-bit frame around a closed four-position cycle, one step per minute, so
     * no pixel is driven continuously. Only [draw] calls this: the full ambient face is protected
     * by the blue-free palette and the half-density checkerboard instead, because any drift wide
     * enough to matter under 24 numerals and four rows of type is a drift you can watch happening.
     */
    fun updateDrift(epochMillis: Long, radius: Float) {
        val step = Math.floorMod(Math.floorDiv(epochMillis, 60_000L), DRIFT_X.size.toLong()).toInt()
        val amplitude = radius * DRIFT_AMPLITUDE
        driftX = DRIFT_X[step] * amplitude
        driftY = DRIFT_Y[step] * amplitude
    }

    fun draw(
        canvas: Canvas,
        g: Geometry,
        palette: Palette,
        planetMode: Int,
        epochMillis: Long,
        hourAngle: Float,
        minuteAngle: Float,
        lowBitAmbient: Boolean,
        burnInProtection: Boolean,
        dutyArcLayer: DutyArcLayer,
        watchRemainingStartAngle: Float,
        dutyRemainingSweep: Float,
    ) {
        canvas.drawColor(Color.BLACK)

        val antiAlias = !lowBitAmbient
        handPaint.isAntiAlias = antiAlias
        tickPaint.isAntiAlias = antiAlias
        hubPaint.isAntiAlias = antiAlias
        textPaint.isAntiAlias = antiAlias

        // On a low-bit panel intermediate alphas cannot be shown, so use the pure hue.
        val ink = if (lowBitAmbient) opaque(palette.lume) else palette.lume
        handPaint.color = ink
        tickPaint.color = ink
        hubPaint.color = ink
        textPaint.color = ink

        canvas.save()
        if (burnInProtection) {
            updateDrift(epochMillis, g.r)
            canvas.translate(driftX, driftY)
        }

        tickPaint.strokeWidth = g.r * 0.012f
        canvas.drawLines(g.cardinalTicks, tickPaint)

        // A running watch is the one thing worth spending extra ambient pixels on: it is the
        // reason you glanced at the wrist. Hairline weight keeps the cost around one percent.
        dutyArcLayer.drawAmbient(
            canvas, g,
            if (lowBitAmbient) opaque(palette.dutyArc) else palette.dutyArc,
            watchRemainingStartAngle, dutyRemainingSweep, antiAlias,
        )

        handPaint.strokeWidth = g.r * 0.011f
        canvas.save()
        canvas.rotate(hourAngle, g.cx, g.cy)
        canvas.drawPath(g.hourHandOutline, handPaint)
        canvas.restore()

        canvas.save()
        canvas.rotate(minuteAngle, g.cx, g.cy)
        canvas.drawPath(g.minuteHandOutline, handPaint)
        canvas.restore()

        hubPaint.strokeWidth = g.r * 0.010f
        canvas.drawCircle(g.cx, g.cy, g.hubRadius, hubPaint)

        // ZULU stays visible in every mode, including on Mars and the Moon.
        val daySeconds = AstroTime.utcSecondOfDay(epochMillis)
        zulu.clear()
            .lit(TextBuf.LIT_ZULU)
            .pad2(daySeconds / 3600)
            .ch(':')
            .pad2((daySeconds / 60) % 60)
        textPaint.textSize = g.r * 0.072f
        canvas.drawText(
            zulu.chars, 0, zulu.length,
            g.cx, g.cy + g.r * 0.470f, textPaint,
        )

        // A single dot marks a non-Earth time base without adding a second text line.
        if (planetMode != PlanetMode.EARTH) {
            canvas.drawCircle(g.cx, g.cy + g.r * 0.560f, g.r * 0.014f, hubPaint)
        }

        canvas.restore()
    }

    private fun opaque(color: Int): Int =
        Color.rgb(Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        /** Where the veil stops being fully clear, as a fraction of its scaled radius. */
        const val VEIL_CLEAR_STOP = 0.7f

        /**
         * How dark the veil gets at its darkest. Not fully opaque: the point is to hold the outer
         * face down at roughly its ambient brightness for a moment, not to black it out.
         */
        const val VEIL_MAX_ALPHA = 210

        /** Four-position drift cycle for the sparse face, one step per minute. */
        val DRIFT_X = floatArrayOf(-1f, 1f, 1f, -1f)
        val DRIFT_Y = floatArrayOf(-1f, -1f, 1f, 1f)
        const val DRIFT_AMPLITUDE = 0.014f

    }

    // The full face does not drift at all. Moving a dial that carries 24 numerals and four rows of
    // type is visible however it is ordered -- walking a ring made it slide, and even alternating
    // about the centre is a twitch you can catch. Dropping blue and thinning to every other pixel
    // with an alternating phase protect the panel without anything appearing to move.
}
