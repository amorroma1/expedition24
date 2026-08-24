// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.avdesign.mfd24.astro.AstroTime
import com.avdesign.mfd24.astro.PlanetMode
import com.avdesign.mfd24.astro.Rovers
import com.avdesign.mfd24.data.TelemetryState
import com.avdesign.mfd24.data.VigilanceState
import com.avdesign.mfd24.data.WatchShiftState
import com.avdesign.mfd24.data.WeatherCondition
import com.avdesign.mfd24.style.Palette
import com.avdesign.mfd24.text.TextBuf
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The readout printed on the dial.
 *
 * Bare type — no window, no fill, no frame — set in one size and one weight, split either side of
 * the hub. It is drawn under the hands, the way dial text always is, and each glyph is laid down
 * twice, first as a background-coloured outline and then as fill, so it separates cleanly from the
 * ticks and the duty arc.
 *
 * ```
 *                  EARTH                  MARS               MOON
 *   above  row 1   DUTY: 03:42 REM        DUTY: 03:42 REM    OFF-DUTY
 *          row 2   Z 18AUG 18:42:15       Z 18AUG 18:42      Z 18AUG 18:42
 *   below  row 3   ♁ -4°C OVC Q1013       ♂ SOL 54321        ☾ LUNAR DAY 20834
 *          row 4   ✈ SVO 4.2KM            MTC 18:42          LTC 18:42
 * ```
 * The site pictogram on row 4 is chosen by the site type *and* its flags — see [SiteGlyph].
 * The reference frame is an astronomical symbol rather than a word: it carries the same meaning in
 * a fraction of the width, and it is drawn as a path because font coverage for ♁ ♂ ☾ on Wear OS is
 * no more reliable than it is for emoji. Earth-referenced ZULU is present in every mode, as the
 * brief requires.
 */
class TelemetryLayer {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    /** Background-coloured outline drawn under every glyph. */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    /** Both pictograms — the reference-frame symbol and the site glyph — go through this pair. */
    private val symbolHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Refreshed once per frame; used to sit the pictograms on the type's optical middle. */
    private val metrics = Paint.FontMetrics()

    /**
     * Bold monospace, built once.
     *
     * `Typeface.create` allocates, so the low-battery weight cannot be produced in the drawing
     * path. Two faces are made here and the paint is pointed at one or the other, which costs a
     * field write.
     */
    private val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val zulu = TextBuf(24)
    private val battery = TextBuf(12)
    private val sensor = TextBuf(12)

    /** One cached pictogram per slot, keyed on the reading and the layout. */
    private val sensorGlyphPath = arrayOf(Path(), Path())
    private val sensorGlyphKey = intArrayOf(-1, -1)
    private val sensorGlyphLayout = intArrayOf(-1, -1)
    private val sensorGlyphX = floatArrayOf(Float.NaN, Float.NaN)
    private val sensorGlyphBox = RectF()
    private val duty = TextBuf(24)
    private val line1 = TextBuf(40)
    private val line2 = TextBuf(40)

    /**
     * The tail of the Mars third row — the light time or the conjunction flag — kept apart from
     * [line1] because an Earth pictogram sits between them: a bare `14M22S` read as nothing in
     * particular, and `OWLT` does not fit the row, so the glyph is the label.
     */
    private val line1Tail = TextBuf(8)

    /** Built with its left edge at the dial centre; drawing translates it into place. */
    private val symbolPath = Path()
    private var symbolMode = -1
    private var symbolLayout = -1

    /** The ground-station dish labelling the light-time figure; rebuilt with the layout. */
    private val dishTailPath = Path()
    private var dishTailLayout = -1

    private val glyphPath = Path()

    /** Type and flags packed together — both pick the pictogram, so both are the cache key. */
    private var glyphKey = -1
    private var glyphLayout = -1

    /** Local copy of the site code, refreshed only when the resolver publishes a new one. */
    private val siteCode = CharArray(8)
    private var siteCodeLength = 0
    private var siteVersionSeen = -1

    fun draw(
        canvas: Canvas,
        g: Geometry,
        palette: Palette,
        state: TelemetryState,
        planetMode: Int,
        epochMillis: Long,
        fahrenheit: Boolean,
        mmHg: Boolean,
        dutyState: Int,
        dutyMillis: Long,
        vigilanceStatus: Int,
        showClearHint: Boolean,
        incidentMillis: Long,
        incidentElapsedMillis: Long,
        sensorLeft: Int,
        sensorRight: Int,
        layoutGeneration: Int,
        frameSymbol: Boolean,
        roverIndex: Int,
        relayValid: Boolean,
        owltSeconds: Int,
        conjunction: Boolean,
    ) {
        buildLines(
            state, planetMode, epochMillis, fahrenheit, mmHg, roverIndex, relayValid,
            owltSeconds, conjunction,
        )
        if (vigilanceStatus == VigilanceState.INCIDENT) {
            buildIncident(incidentMillis, incidentElapsedMillis)
        } else {
            buildDuty(dutyState, dutyMillis)
        }

        textPaint.textSize = g.telemetryTextSize
        textPaint.getFontMetrics(metrics)
        haloPaint.textSize = g.telemetryTextSize
        haloPaint.strokeWidth = g.r * HALO_WIDTH
        haloPaint.color = palette.background
        symbolHaloPaint.strokeWidth = g.r * HALO_WIDTH
        symbolHaloPaint.color = palette.background

        textPaint.textAlign = Paint.Align.CENTER
        haloPaint.textAlign = Paint.Align.CENTER

        drawVigilanceStatus(canvas, g, palette, vigilanceStatus, showClearHint)
        // Before the readout rather than after, because the rows below have early returns and the
        // battery does not depend on any of them.
        drawBattery(canvas, g, palette, state.batteryPercent)
        drawSensorSlots(canvas, g, palette, state, sensorLeft, sensorRight, layoutGeneration)

        // Duty leads: it is the row that changes meaning, and ZULU sits below it where the dial is
        // wider — which is what lets the date group share the line. An incident takes the row
        // outright: how long a shift still had to run stopped being the useful thing on this dial
        // the moment nobody answered.
        if (vigilanceStatus == VigilanceState.INCIDENT) {
            textPaint.color = palette.second
            drawHaloed(canvas, duty, g.cx, g.telemetryLineY[0])
        } else {
            textPaint.color = when (dutyState) {
                WatchShiftState.DUTY_ACTIVE, WatchShiftState.DUTY_PENDING -> palette.dutyArc
                WatchShiftState.DUTY_SERVED -> palette.dutyArcSpent
                else -> palette.lumeDim
            }
            drawHaloed(canvas, duty, g.cx, g.telemetryLineY[0])
        }

        textPaint.color = palette.lume
        drawHaloed(canvas, zulu, g.cx, g.telemetryLineY[1])

        // First data row. On the multi-world faces the reference-frame symbol leads it, so the
        // mode costs no line of its own; the Earth face drops the glyph — one world says
        // nothing — and the row is plain text, absent entirely when there is nothing to report.
        textPaint.color = palette.lumeSoft
        if (frameSymbol) {
            if (symbolMode != planetMode || symbolLayout != layoutGeneration) {
                buildSymbol(g, planetMode)
                symbolMode = planetMode
                symbolLayout = layoutGeneration
            }
            if (line1Tail.length > 0) {
                if (dishTailLayout != layoutGeneration) {
                    Glyphs.buildDishSymbol(g.symbolBox, dishTailPath)
                    dishTailLayout = layoutGeneration
                }
                drawTwinGlyphedLine(
                    canvas, g, palette.lume, symbolPath, line1,
                    dishTailPath, line1Tail, g.telemetryLineY[2],
                )
            } else {
                drawGlyphedLine(
                    canvas, g, palette.lume, symbolPath,
                    g.symbolBox.width(), g.symbolBox.height(), line1, g.telemetryLineY[2],
                )
            }
        } else if (line1.length > 0) {
            drawHaloed(canvas, line1, g.cx, g.telemetryLineY[2])
        }

        // Second data row: the site pictogram leads it when there is a site to name. With no
        // position at all there is no row — not even a placeholder, which would be noise.
        if (planetMode == PlanetMode.EARTH && !state.hasPosition) {
            return
        }

        if (planetMode == PlanetMode.EARTH && state.siteValid) {
            val key = state.siteType shl 8 or (state.siteFlags and 0xFF)
            if (glyphKey != key || glyphLayout != layoutGeneration) {
                buildGlyph(g, state.siteType, state.siteFlags)
                glyphKey = key
                glyphLayout = layoutGeneration
            }
            // The accent colour marks a military site. It is the same accent the seconds cursor
            // and the hub ring use, and it is never the lume hue, so it separates at any size --
            // which is more than the silhouette alone can promise in a 22 px box.
            val glyphColor = if (SiteGlyph.isMilitary(state.siteFlags)) {
                palette.second
            } else {
                palette.lume
            }
            drawGlyphedLine(
                canvas, g, glyphColor, glyphPath,
                g.glyphBox.width(), g.glyphBox.height(), line2, g.telemetryLineY[3],
            )
        } else {
            drawHaloed(canvas, line2, g.cx, g.telemetryLineY[3])
        }
    }

    /**
     * Draws two pictogram-and-text pairs as one centred group: the reference-frame symbol with
     * the sol, then the ground-station dish with the light time — the glyph standing where a
     * label would, because `OWLT` does not fit the row and a bare duration reads as nothing in
     * particular. Same translate discipline as [drawGlyphedLine]; both paths share the symbol
     * box, so the two glyphs sit at one size.
     */
    private fun drawTwinGlyphedLine(
        canvas: Canvas,
        g: Geometry,
        glyphColor: Int,
        leadPath: Path,
        leadText: TextBuf,
        tailPath: Path,
        tailText: TextBuf,
        baselineY: Float,
    ) {
        val glyphWidth = g.symbolBox.width()
        val glyphHeight = g.symbolBox.height()
        val leadWidth = textPaint.measureText(leadText.chars, 0, leadText.length)
        val tailWidth = textPaint.measureText(tailText.chars, 0, tailText.length)
        // The dish sits almost against its figure — it is that figure's label, and a label a
        // gap away read as one more item in the row; the full gap stays where the two pairs
        // separate. It also rides a couple of pixels high: its visual mass is the bowl at the
        // bottom of the box, and on the shared baseline it hung below the digits' centre.
        val tailGap = g.glyphGap * DISH_GAP_FRACTION
        val tailLift = g.r * DISH_LIFT_FRACTION
        val groupWidth = glyphWidth + g.glyphGap + leadWidth +
            g.glyphGap + glyphWidth + tailGap + tailWidth
        val left = g.cx - groupWidth / 2f
        val dy = (metrics.ascent + metrics.descent) / 2f + glyphHeight / 2f

        glyphPaint.color = glyphColor
        canvas.save()
        canvas.translate(left - g.cx, dy)
        canvas.drawPath(leadPath, symbolHaloPaint)
        canvas.drawPath(leadPath, glyphPaint)
        canvas.restore()

        val tailGlyphLeft = left + glyphWidth + g.glyphGap + leadWidth + g.glyphGap
        canvas.save()
        canvas.translate(tailGlyphLeft - g.cx, dy - tailLift)
        canvas.drawPath(tailPath, symbolHaloPaint)
        canvas.drawPath(tailPath, glyphPaint)
        canvas.restore()

        textPaint.textAlign = Paint.Align.LEFT
        haloPaint.textAlign = Paint.Align.LEFT
        drawHaloed(canvas, leadText, left + glyphWidth + g.glyphGap, baselineY)
        drawHaloed(canvas, tailText, tailGlyphLeft + glyphWidth + tailGap, baselineY)
        textPaint.textAlign = Paint.Align.CENTER
        haloPaint.textAlign = Paint.Align.CENTER
    }



    /**
     * Draws a pictogram and its text as one group, centred on the dial. The path is built with its
     * left edge at the centre and its foot on the baseline, so positioning is a translate rather
     * than a rebuild.
     *
     * The vertical shift matters: a pictogram sitting on the baseline hangs low against digits,
     * which have no descender. Both are centred on the type's optical middle instead, which the
     * font metrics give as `baseline + (ascent + descent) / 2`.
     */
    private fun drawGlyphedLine(
        canvas: Canvas,
        g: Geometry,
        glyphColor: Int,
        path: Path,
        glyphWidth: Float,
        glyphHeight: Float,
        buf: TextBuf,
        baselineY: Float,
    ) {
        val textWidth = textPaint.measureText(buf.chars, 0, buf.length)
        // An empty buffer means the pictogram is the whole row, so it centres on its own rather
        // than being nudged aside by a gap that leads to nothing.
        val groupWidth =
            if (buf.length == 0) glyphWidth else glyphWidth + g.glyphGap + textWidth
        val left = g.cx - groupWidth / 2f
        val dy = (metrics.ascent + metrics.descent) / 2f + glyphHeight / 2f

        canvas.save()
        canvas.translate(left - g.cx, dy)
        canvas.drawPath(path, symbolHaloPaint)
        glyphPaint.color = glyphColor
        canvas.drawPath(path, glyphPaint)
        canvas.restore()

        if (buf.length == 0) return
        textPaint.textAlign = Paint.Align.LEFT
        haloPaint.textAlign = Paint.Align.LEFT
        drawHaloed(canvas, buf, left + glyphWidth + g.glyphGap, baselineY)
        textPaint.textAlign = Paint.Align.CENTER
        haloPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * The two optional readouts either side of the hub.
     *
     * Drawn from the same [TextBuf] twice over rather than from two, because nothing is kept
     * between frames here and one buffer costs one allocation at construction instead of two.
     *
     * The label is dim and the value is not, so a glance lands on the number. Both are haloed like
     * every other piece of type on this dial: the hands sweep straight through this ground, and a
     * halo is what keeps a digit legible with a hand crossing it.
     */
    private fun drawSensorSlots(
        canvas: Canvas,
        g: Geometry,
        palette: Palette,
        state: TelemetryState,
        leftKind: Int,
        rightKind: Int,
        layoutGeneration: Int,
    ) {
        if (leftKind == SLOT_OFF && rightKind == SLOT_OFF) return
        if (leftKind != SLOT_OFF) {
            drawSensorSlot(
                canvas, g, palette, state, leftKind, g.cx - g.sensorOffsetX, 0, layoutGeneration
            )
        }
        if (rightKind != SLOT_OFF) {
            drawSensorSlot(
                canvas, g, palette, state, rightKind, g.cx + g.sensorOffsetX, 1, layoutGeneration
            )
        }
        // Put the shared paints back: every other row assumes the readout size.
        textPaint.textSize = g.telemetryTextSize
        haloPaint.textSize = g.telemetryTextSize
    }

    /**
     * One slot: the pictogram and the value on one line, at one size.
     *
     * The pair is centred on the slot as a unit, which means placing each half against the other's
     * width rather than aligning either to the slot's own centre. Widths come from the monospace
     * advance the rest of the dial is laid out on, not from measuring: the figure is deterministic,
     * and the layout arithmetic is already written down in [Geometry] in those terms.
     */
    private fun drawSensorSlot(
        canvas: Canvas,
        g: Geometry,
        palette: Palette,
        state: TelemetryState,
        kind: Int,
        x: Float,
        slot: Int,
        layoutGeneration: Int,
    ) {
        buildSensorValue(state, kind)
        textPaint.textSize = g.sensorTextSize
        haloPaint.textSize = g.sensorTextSize
        textPaint.color = palette.lumeSoft

        // Station pressure has no pictogram, so it leads with its own name and the line is centred
        // on the slot as it stands.
        if (kind == SLOT_PRESSURE) {
            drawHaloed(canvas, sensor, x, g.sensorLineY)
            return
        }

        val textWidth = sensor.length * g.sensorTextSize * Geometry.MONO_ADVANCE
        val shift = (g.sensorGlyphSize + g.sensorGlyphGap) * 0.5f
        drawSensorGlyph(
            canvas, g, palette, kind, x - (textWidth + g.sensorGlyphGap) * 0.5f,
            slot, layoutGeneration,
        )
        drawHaloed(canvas, sensor, x + shift, g.sensorLineY)
    }

    /**
     * The heart or the walking figure, cached per slot.
     *
     * Rebuilt when the reading, the layout, or the *position* changes, and the position moves with
     * the value: a pulse going from 99 to 100 widens the line and shifts the glyph. Rebuilding
     * scales a path into place, which is worth not repeating for a shape that has not moved.
     */
    private fun drawSensorGlyph(
        canvas: Canvas,
        g: Geometry,
        palette: Palette,
        kind: Int,
        centreX: Float,
        slot: Int,
        layoutGeneration: Int,
    ) {
        val path = sensorGlyphPath[slot]
        if (sensorGlyphKey[slot] != kind ||
            sensorGlyphLayout[slot] != layoutGeneration ||
            sensorGlyphX[slot] != centreX
        ) {
            val half = g.sensorGlyphSize * 0.5f
            // Centred on the type's own optical middle rather than sitting on the baseline, so the
            // glyph and the digits read as one line and not as a picture with a caption.
            val middle = g.sensorLineY - g.sensorTextSize * CAP_MIDDLE
            sensorGlyphBox.set(centreX - half, middle - half, centreX + half, middle + half)
            if (kind == SLOT_HEART_RATE) {
                Glyphs.buildHeart(sensorGlyphBox, path)
            } else {
                Glyphs.buildPedestrian(sensorGlyphBox, path)
            }
            sensorGlyphKey[slot] = kind
            sensorGlyphLayout[slot] = layoutGeneration
            sensorGlyphX[slot] = centreX
        }
        glyphPaint.color = palette.lumeSoft
        canvas.drawPath(path, glyphPaint)
    }

    /**
     * Four characters at the outside, which is the width budget the position pays for.
     *
     * Steps go to thousands past 9999 rather than being truncated or allowed to run to five
     * figures: `12K` is the honest thing to say when the exact count no longer fits and no longer
     * matters. Station pressure drops its fraction for the same reason `1013` is what an altimeter
     * setting looks like.
     */
    private fun buildSensorValue(state: TelemetryState, kind: Int) {
        sensor.clear()
        val value = when (kind) {
            SLOT_HEART_RATE -> state.heartRate
            SLOT_STEPS -> state.stepsToday
            else -> state.localPressureTenths
        }
        if (value < 0) {
            if (kind == SLOT_PRESSURE) sensor.lit(TextBuf.LIT_SENSOR_QFE)
            sensor.lit(TextBuf.LIT_SENSOR_NONE)
            return
        }
        when (kind) {
            SLOT_STEPS ->
                if (value >= STEPS_IN_THOUSANDS) {
                    sensor.uint(value / 1000).lit(TextBuf.LIT_THOUSANDS)
                } else {
                    sensor.uint(value)
                }

            SLOT_PRESSURE -> sensor.lit(TextBuf.LIT_SENSOR_QFE).uint((value + 5) / 10)

            else -> sensor.uint(value)
        }
    }

    /**
     * `BAT 84%`, below the readout block.
     *
     * One colour in every state — [Palette.lumeDim], the same step back the minor ticks take. A
     * charge figure that turned red would compete with the accent the seconds cursor, the duty arc
     * and the vigilance core already share, and none of those can afford a rival at the bottom of
     * the dial. Below [LOW_BATTERY_PERCENT] the weight goes up instead: it reads at a glance and it
     * borrows nothing.
     */
    private fun drawBattery(canvas: Canvas, g: Geometry, palette: Palette, percent: Int) {
        if (percent < 0) return
        battery.clear().lit(TextBuf.LIT_BATTERY).uint(percent).ch('%')

        val low = percent < LOW_BATTERY_PERCENT
        textPaint.typeface = if (low) monoBold else Typeface.MONOSPACE
        haloPaint.typeface = textPaint.typeface
        textPaint.textSize = g.batteryTextSize
        haloPaint.textSize = g.batteryTextSize
        textPaint.color = palette.lumeDim
        drawHaloed(canvas, battery, g.cx, g.batteryLineY)

        // Put the shared paints back: every other row assumes the regular face at the readout size.
        textPaint.typeface = Typeface.MONOSPACE
        haloPaint.typeface = Typeface.MONOSPACE
        textPaint.textSize = g.telemetryTextSize
        haloPaint.textSize = g.telemetryTextSize
    }

    /**
     * The vigilance line: only what the operator has to *act* on.
     *
     * Armed and counting says nothing here — the hub core carries it, and a status permanently on
     * the dial stops being read. Suspended-on-charge says nothing either, since the hub is empty
     * and the watch is visibly on a charger; a line of type to repeat both was clutter that took a
     * row from the readout and told nobody anything they could not see.
     *
     * Off the wrist is the exception, and the reason is that it is *not* self-evident. The detector
     * can report off-body while the watch is worn — a loose strap, a sleeve — and that is the one
     * way this monitor can stop watching a wrist that is still there. It says so rather than
     * looking armed.
     */
    private fun drawVigilanceStatus(
        canvas: Canvas,
        g: Geometry,
        palette: Palette,
        status: Int,
        showClearHint: Boolean,
    ) {
        val label = when (status) {
            VigilanceState.PROMPT -> TextBuf.LIT_VIGIL_PROMPT
            VigilanceState.ALARM -> TextBuf.LIT_VIGIL_ALARM
            // The first tap of the clearing pair answers with the second half of the gesture,
            // right where the eyes already are. MAN DOWN itself is not lost: the duty row still
            // carries the incident's time, and the word comes back the moment the hint expires.
            VigilanceState.INCIDENT ->
                if (showClearHint) TextBuf.LIT_TAP_AGAIN else TextBuf.LIT_MAN_DOWN
            VigilanceState.OFF_BODY -> TextBuf.LIT_OFF_WRIST
            else -> return
        }
        textPaint.textSize = g.statusTextSize
        haloPaint.textSize = g.statusTextSize
        // Off the wrist is information, not an alert; the accent is reserved for the three states
        // that are asking something of somebody.
        textPaint.color =
            if (status == VigilanceState.OFF_BODY) palette.lumeDim else palette.second
        canvas.drawText(label, 0, label.size, g.cx, g.statusLineY, haloPaint)
        canvas.drawText(label, 0, label.size, g.cx, g.statusLineY, textPaint)

        textPaint.textSize = g.telemetryTextSize
        haloPaint.textSize = g.telemetryTextSize
    }

    /** Outline first, then fill — [textPaint]'s colour must already be set. */
    private fun drawHaloed(canvas: Canvas, buf: TextBuf, x: Float, y: Float) {
        canvas.drawText(buf.chars, 0, buf.length, x, y, haloPaint)
        canvas.drawText(buf.chars, 0, buf.length, x, y, textPaint)
    }

    /**
     * `21:14Z +02:13` — when the operator stopped answering, and how long ago that was.
     *
     * Zulu because it is the one clock that means the same thing to whoever reads it next, and it
     * is what the row below already shows. The elapsed figure rounds *down*: an incident four
     * minutes old must not read as five.
     */
    private fun buildIncident(incidentMillis: Long, elapsedMillis: Long) {
        duty.clear()
        val minuteOfDay = (incidentMillis / 60_000L).mod(1440L).toInt()
        duty.pad2(minuteOfDay / 60).ch(':').pad2(minuteOfDay % 60).lit(TextBuf.LIT_ZULU_SUFFIX)
        var minutes = (elapsedMillis / 60_000L).toInt()
        if (minutes < 0) minutes = 0
        if (minutes > MAX_DISPLAY_MINUTES) minutes = MAX_DISPLAY_MINUTES
        duty.ch(' ').ch('+').pad2(minutes / 60).ch(':').pad2(minutes % 60)
    }

    /**
     * `DUTY: 3:42 REM` while a shift runs, `DUTY IN 4:30` before a booked one begins, and
     * `OFF-DUTY` when none is set or the last one has been served.
     */
    private fun buildDuty(dutyState: Int, dutyMillis: Long) {
        duty.clear()
        when (dutyState) {
            WatchShiftState.DUTY_ACTIVE -> {
                appendDuration(duty.lit(TextBuf.LIT_DUTY), dutyMillis).lit(TextBuf.LIT_REM)
            }

            WatchShiftState.DUTY_PENDING -> {
                appendDuration(duty.lit(TextBuf.LIT_DUTY_IN), dutyMillis)
            }

            else -> duty.lit(TextBuf.LIT_OFF_DUTY)
        }
    }

    /**
     * Hours and minutes, rounded up so a live countdown never shows 0:00 while time remains, and
     * capped at 99:59 so no amount of nonsense upstream can push the line past its width budget.
     */
    private fun appendDuration(buf: TextBuf, millis: Long): TextBuf {
        var totalMinutes = ((millis + 59_999L) / 60_000L).toInt()
        if (totalMinutes > MAX_DISPLAY_MINUTES) totalMinutes = MAX_DISPLAY_MINUTES
        // Always two digits either side, like ZULU: a countdown that changes width as it passes
        // ten hours makes the whole row twitch.
        return buf.pad2(totalMinutes / 60).ch(':').pad2(totalMinutes % 60)
    }

    private fun buildLines(
        state: TelemetryState,
        planetMode: Int,
        epochMillis: Long,
        fahrenheit: Boolean,
        mmHg: Boolean,
        roverIndex: Int,
        relayValid: Boolean,
        owltSeconds: Int,
        conjunction: Boolean,
    ) {
        val daySeconds = AstroTime.utcSecondOfDay(epochMillis)

        // ICAO date-time group order: the day and month lead, then the time, all in UTC.
        val monthDay = AstroTime.utcMonthDay(epochMillis)
        zulu.clear()
            .lit(TextBuf.LIT_ZULU)
            .pad2(monthDay % 100)
            .lit(TextBuf.MONTHS[monthDay / 100 - 1])
            .space()
            .pad2(daySeconds / 3600)
            .ch(':')
            .pad2((daySeconds / 60) % 60)
        if (planetMode == PlanetMode.EARTH) {
            zulu.ch(':').pad2(daySeconds % 60)
        }

        line1.clear()
        line1Tail.clear()
        line2.clear()

        when (planetMode) {
            PlanetMode.MARS -> {
                // The mission sol, not the global MSD: operators plan in the number their rover
                // counts. When the relay line has nothing to stand on, this row says so — it is
                // the slot the weather notice would occupy, and the sol returns on recovery,
                // being pure arithmetic. There is no MTC row: the hour hand *is* the rover's
                // clock, and a second Mars time under the dial would restate it.
                if (relayValid) {
                    line1.lit(TextBuf.LIT_SOL).uint(Rovers.missionSol(epochMillis, roverIndex))
                    // After the sol, behind an Earth pictogram: the one-way light time — the
                    // operator's ever-present constant — or the conjunction flag when the sun
                    // stands between, because a delay for a link that cannot pass is noise and
                    // the flag is the news.
                    if (conjunction) {
                        line1Tail.lit(TextBuf.LIT_CONJ)
                    } else if (owltSeconds >= 0) {
                        // mm:ss, the way light time is actually spoken; the dish is what keeps
                        // it from reading as a time of day.
                        line1Tail.pad2(owltSeconds / 60).ch(':').pad2(owltSeconds % 60)
                    }
                } else {
                    line1.lit(TextBuf.LIT_NO_EPHEMERIS)
                }
                line2.lit(TextBuf.ROVER_NAMES[roverIndex])
            }

            PlanetMode.MOON -> {
                line1.lit(TextBuf.LIT_LUNAR_DAY).uint(AstroTime.lunarDay(epochMillis))
                appendClock(line2.lit(TextBuf.LIT_LTC), AstroTime.lunarTimeHours(epochMillis))
            }

            else -> {
                appendWeather(state, fahrenheit, mmHg)
                appendSite(state)
            }
        }
    }

    private fun appendClock(buf: TextBuf, hours: Double) {
        val h = floor(hours).toInt()
        val m = ((hours - h) * 60.0).toInt()
        buf.pad2(h).ch(':').pad2(m)
    }

    /** Leaves the row empty when there is nothing to report; the symbol then stands on its own. */
    private fun appendWeather(state: TelemetryState, fahrenheit: Boolean, mmHg: Boolean) {
        if (!state.weatherValid) return

        val deciC = state.temperatureDeciC
        if (fahrenheit) {
            val deciF = deciC * 9 / 5 + 320
            line1.int(roundDeci(deciF)).lit(TextBuf.LIT_DEG_F)
        } else {
            line1.int(roundDeci(deciC)).lit(TextBuf.LIT_DEG_C)
        }

        line1.space().lit(WeatherCondition.token(state.conditionIndex)).space()

        val deciHpa = state.pressureDeciHpa
        if (mmHg) {
            line1.uint((deciHpa * 0.0750062f).roundToInt()).lit(TextBuf.LIT_MMHG)
        } else {
            line1.lit(TextBuf.LIT_QNH).uint(roundDeci(deciHpa))
        }
    }

    private fun appendSite(state: TelemetryState) {
        if (!state.siteValid) {
            // A fix but nothing within the radius is worth saying; no fix at all is not, because
            // the row is not drawn in that case.
            line2.lit(TextBuf.LIT_NO_SITE)
            return
        }
        val version = state.siteVersion
        if (version != siteVersionSeen) {
            siteCodeLength = state.copySiteCode(siteCode)
            siteVersionSeen = version
        }
        line2.lit(siteCode, 0, siteCodeLength).space()
            .tenths(state.siteDistanceHm).lit(TextBuf.LIT_KM)
    }

    /** Rounds a tenths-scaled value to whole units, away from zero. */
    private fun roundDeci(deci: Int): Int =
        if (deci >= 0) (deci + 5) / 10 else -((-deci + 5) / 10)

    private fun buildSymbol(g: Geometry, planetMode: Int) {
        when (planetMode) {
            PlanetMode.MARS -> Glyphs.buildMarsSymbol(g.symbolBox, symbolPath)
            PlanetMode.MOON -> Glyphs.buildMoonSymbol(g.symbolBox, symbolPath)
            else -> Glyphs.buildEarthSymbol(g.symbolBox, symbolPath)
        }
    }

    /** Rasterises whichever silhouette [SiteGlyph] chose for this site. */
    private fun buildGlyph(g: Geometry, type: Int, flags: Int) {
        when (SiteGlyph.forSite(type, flags)) {
            SiteGlyph.ROCKET -> Glyphs.buildRocket(g.glyphBox, glyphPath)
            SiteGlyph.WARSHIP -> Glyphs.buildWarship(g.glyphBox, glyphPath)
            SiteGlyph.MERCHANT_SHIP -> Glyphs.buildMerchantShip(g.glyphBox, glyphPath)
            SiteGlyph.HELICOPTER -> Glyphs.buildHelicopter(g.glyphBox, glyphPath)
            SiteGlyph.HELICOPTER_MILITARY -> Glyphs.buildHelicopterMilitary(g.glyphBox, glyphPath)
            SiteGlyph.FIGHTER -> Glyphs.buildFighter(g.glyphBox, glyphPath)
            else -> Glyphs.buildAircraft(g.glyphBox, glyphPath)
        }
    }

    private companion object {
        /** The dish-to-figure gap, as a fraction of the ordinary glyph gap: it is a label. */
        const val DISH_GAP_FRACTION = 0.3f

        /** How far the dish rides above the shared baseline, as a fraction of the radius. */
        const val DISH_LIFT_FRACTION = 0.010f

        /** Halo stroke, as a fraction of the dial radius. */
        const val HALO_WIDTH = 0.014f

        /** 99:59 — a shift is at most a day, so anything longer is a bug, not a reading. */
        const val MAX_DISPLAY_MINUTES = 99 * 60 + 59

        /**
         * Sensor slot kinds, matching the ordinals of
         * [SensorSlots.Kind][com.avdesign.mfd24.data.SensorSlots.Kind]. Held as plain ints because
         * `render()` reads them once a frame and an enum lookup there is a needless indirection.
         */
        const val SLOT_OFF = 0
        const val SLOT_HEART_RATE = 1
        const val SLOT_STEPS = 2
        const val SLOT_PRESSURE = 3

        /** Past this the exact step count stops fitting, and stops mattering. */
        const val STEPS_IN_THOUSANDS = 10_000

        /** Half the cap height, as a fraction of the type size: where a line's optical middle is. */
        const val CAP_MIDDLE = 0.35f

        /** Below this the battery row goes bold. A quarter is where a watch stops being day's kit. */
        const val LOW_BATTERY_PERCENT = 25
    }
}
