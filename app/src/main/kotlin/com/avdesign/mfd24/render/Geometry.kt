// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.render

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every coordinate the renderer needs, expressed in pixels for the current surface.
 *
 * [rebuild] is the only place in the drawing path that allocates, and it runs only when the surface
 * bounds actually change. Everything the per-frame code touches is a preallocated array or path.
 */
class Geometry(
    /**
     * Where the duty arc rides, as a fraction of the radius — a per-world choice made at
     * construction. Earth keeps [DUTY_ARC_RADIUS], just inside the hour ticks; Mars moves the
     * arc to [DUTY_ARC_RADIUS_MARS], between the hour hand's tip and the numerals, because the
     * tick ring there already carries the two comm lines and the Nadir band, and three data
     * rings plus the arc in one belt read as clutter.
     */
    private val dutyArcRadiusFraction: Float = DUTY_ARC_RADIUS,
) {

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    var cx: Float = 0f
        private set
    var cy: Float = 0f
        private set

    /** Radius of the dial, i.e. half the shorter side. */
    var r: Float = 0f
        private set

    /** True when the 24-hour axis is flipped so midnight sits at the top. */
    var midnightUp: Boolean = false
        private set

    // --- 24-hour scale ---------------------------------------------------------------------

    /** 24 hour ticks as x0,y0,x1,y1 quads for a single `Canvas.drawLines` call. */
    val hourTicks: FloatArray = FloatArray(HOUR_TICKS * 4)

    /** The four cardinal ticks (00/06/12/18) are drawn separately, heavier. */
    val cardinalTicks: FloatArray = FloatArray(4 * 4)

    /**
     * The fine ring: 60 divisions, one per minute of the minute hand's revolution and one per
     * second of the seconds cursor's. Split in two so the ring can be graduated — every fifth
     * division is drawn heavier, which is what lets the eye land on a five without counting.
     */
    val minuteTicks: FloatArray = FloatArray(MINUTE_MINOR_TICKS * 4)
    val minuteFiveTicks: FloatArray = FloatArray(MINUTE_MAJOR_TICKS * 4)

    /** `"00".."23"`, preformatted so the render loop never converts a number to text. */
    val hourLabels: Array<CharArray> = Array(HOUR_TICKS) { CharArray(2) }
    val hourLabelX: FloatArray = FloatArray(HOUR_TICKS)
    val hourLabelY: FloatArray = FloatArray(HOUR_TICKS)

    // The dial carries no NOON / MORN / EVE / MIDNIGHT wording: the heavy cardinal ticks and the
    // numerals already say it, and four more words only crowded the face.

    // --- Seconds marker --------------------------------------------------------------------

    /** Radius of the marker's outer edge, just clear of the tick tips. */
    var secondsMarkerBaseRadius: Float = 0f
        private set

    /**
     * Half the marker's base, a little wider than the gap between two minute ticks so it reads as a
     * cursor sitting over the scale rather than as one more tick.
     */
    var secondsMarkerHalfBase: Float = 0f
        private set

    // --- Watch (shift) arc -----------------------------------------------------------------

    /** Oval the duty arc rides on; it doubles as the dial's inner decorative ring. */
    val dutyArcTrack: RectF = RectF()

    // --- Daylight band ---------------------------------------------------------------------

    /**
     * Oval the daylight band is stroked along, filling the whole hour-tick band so the lit hours
     * read as a shaded sector of the 24-hour scale rather than as one more ring.
     */
    val daylightTrack: RectF = RectF()
    var daylightWidth: Float = 0f
        private set

    // --- Comm windows (Mars) ------------------------------------------------------------------

    /** Inner comm line: the rover's direct-to-Earth windows, just inside the hour ticks. */
    val commInnerTrack: RectF = RectF()

    /** Outer comm line: relay passes, in the clear band between cardinal tips and minute ring. */
    val commOuterTrack: RectF = RectF()

    var commStrokeWidth: Float = 0f
        private set

    // --- Hands -----------------------------------------------------------------------------

    /**
     * Hand silhouettes, built pointing straight up from the hub; the renderer rotates the canvas
     * around it. These are *stroked*, not filled — the readout sits under the hands, and a solid
     * hand crossing it takes a line of type with it.
     */
    val hourHand: Path = Path()
    val minuteHand: Path = Path()

    /**
     * The parts of each hand that stay solid: the point, so it lands unambiguously on a mark, and
     * the counterweight, so the hand still reads as a hand rather than as a wire.
     */
    val hourHandSolid: Path = Path()
    val minuteHandSolid: Path = Path()
    val hubRadius: Float
        get() = r * HUB_RADIUS

    /** Centre-line of the duty arc, where its incident marks have to cross it. */
    val dutyArcRadius: Float
        get() = r * dutyArcRadiusFraction

    /** Hollow outlines used in ambient mode, where filled hands would burn the panel. */
    val hourHandOutline: Path = Path()
    val minuteHandOutline: Path = Path()

    // --- Telemetry readout -----------------------------------------------------------------

    /**
     * Baselines of the four readout lines — ZULU and duty above the hub, the two data rows below —
     * all set in one size and one weight, because with no window and no frame the type itself has
     * to carry the structure.
     */
    val telemetryLineY: FloatArray = FloatArray(4)
    var telemetryTextSize: Float = 0f
        private set

    /** Baseline and size of the battery row, below the readout block. */
    var batteryLineY: Float = 0f
        private set
    var batteryTextSize: Float = 0f
        private set

    /** Status line above the readout, drawn only when the vigilance monitor has something to say. */
    var statusLineY: Float = 0f

    /** The two optional sensor readouts either side of the hub: one line each, one size. */
    var sensorLineY: Float = 0f
    var sensorOffsetX: Float = 0f
    var sensorTextSize: Float = 0f
    var sensorGlyphSize: Float = 0f
    var sensorGlyphGap: Float = 0f
        private set
    var statusTextSize: Float = 0f
        private set

    /**
     * Reference-frame symbol box, sitting on the first data line the way the site pictogram sits on
     * the second. Anchored with its left edge at [cx]; the renderer translates it into place.
     */
    val symbolBox: RectF = RectF()

    /** Site glyph box, likewise anchored at [cx]. */
    val glyphBox: RectF = RectF()

    var glyphGap: Float = 0f
        private set

    /** Boxes the spoken accessibility labels point at: the readout block, and the status line. */
    val a11yReadoutBounds: Rect = Rect()
    val a11yStatusBounds: Rect = Rect()

    // --- Rings -----------------------------------------------------------------------------

    val outerRing: RectF = RectF()

    var hourTickWidth: Float = 0f
        private set
    var cardinalTickWidth: Float = 0f
        private set
    var minuteTickWidth: Float = 0f
        private set
    var minuteFiveTickWidth: Float = 0f
        private set
    var hourLabelSize: Float = 0f
        private set
    var ringWidth: Float = 0f
        private set

    fun matches(bounds: Rect, midnightUp: Boolean): Boolean =
        bounds.width() == width && bounds.height() == height && midnightUp == this.midnightUp

    fun rebuild(bounds: Rect, midnightUp: Boolean) {
        this.midnightUp = midnightUp
        width = bounds.width()
        height = bounds.height()
        cx = bounds.exactCenterX()
        cy = bounds.exactCenterY()
        r = (if (width < height) width else height) / 2f

        hourTickWidth = r * 0.020f
        cardinalTickWidth = r * 0.032f
        minuteTickWidth = r * 0.008f
        minuteFiveTickWidth = r * 0.021f
        hourLabelSize = r * 0.095f
        ringWidth = r * 0.008f

        setRadius(outerRing, RING_RADIUS)
        setRadius(dutyArcTrack, dutyArcRadiusFraction)
        setRadius(daylightTrack, (TICK_HOUR_INNER + TICK_CARDINAL_OUTER) / 2f)
        daylightWidth = r * (TICK_CARDINAL_OUTER - TICK_HOUR_INNER)
        setRadius(commInnerTrack, COMM_INNER_RADIUS)
        setRadius(commOuterTrack, COMM_OUTER_RADIUS)
        commStrokeWidth = r * COMM_STROKE
        secondsMarkerBaseRadius = r * SECONDS_MARKER_BASE
        // Pitch between two minute ticks, measured along the arc the cursor's base sits on.
        val pitch = 2.0 * Math.PI * secondsMarkerBaseRadius / MINUTE_TICKS
        secondsMarkerHalfBase = (pitch * SECONDS_MARKER_PITCH_RATIO / 2.0).toFloat()

        buildScale()
        buildHands()
        buildTelemetry()
    }

    private fun setRadius(oval: RectF, fraction: Float) {
        val radius = r * fraction
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    private fun buildScale() {
        var cardinalIndex = 0
        for (h in 0 until HOUR_TICKS) {
            val angle = hourAngleRad(h.toDouble(), midnightUp)
            val sinA = sin(angle).toFloat()
            val cosA = cos(angle).toFloat()

            // Hour ticks all spring from the inner circle and grow outwards; the cardinals simply
            // reach further. They stop well short of the minute ring, because 24 hours and 60
            // minutes do not divide into each other and interleaving the two scales in one band
            // just produces noise.
            val isCardinal = h % 6 == 0
            val outer = if (isCardinal) TICK_CARDINAL_OUTER else TICK_HOUR_OUTER
            val x0 = cx + sinA * r * outer
            val y0 = cy - cosA * r * outer
            val x1 = cx + sinA * r * TICK_HOUR_INNER
            val y1 = cy - cosA * r * TICK_HOUR_INNER

            val base = h * 4
            hourTicks[base] = x0
            hourTicks[base + 1] = y0
            hourTicks[base + 2] = x1
            hourTicks[base + 3] = y1

            if (isCardinal) {
                val cb = cardinalIndex * 4
                cardinalTicks[cb] = x0
                cardinalTicks[cb + 1] = y0
                cardinalTicks[cb + 2] = x1
                cardinalTicks[cb + 3] = y1
                cardinalIndex++
            }

            hourLabels[h][0] = ('0' + h / 10)
            hourLabels[h][1] = ('0' + h % 10)
            hourLabelX[h] = cx + sinA * r * LABEL_RADIUS
            // Baseline is nudged in buildScale's caller-independent way: the renderer adds the
            // font's vertical centring offset, which depends on the Paint, not on the geometry.
            hourLabelY[h] = cy - cosA * r * LABEL_RADIUS
        }

        // The fine ring is 60 divisions rather than the 96 quarter-hours the 24 h scale would
        // suggest, because the seconds marker has to land dead centre on a tick and 96/60 is not a
        // whole number. Sixty is also the honest scale for the minute hand, which turns once an
        // hour. The two rings coincide every two hours; elsewhere an hour tick simply falls between
        // two minute ticks.
        var minor = 0
        var major = 0
        for (i in 0 until MINUTE_TICKS) {
            val angle = i * 2.0 * Math.PI / MINUTE_TICKS
            val sinA = sin(angle).toFloat()
            val cosA = cos(angle).toFloat()
            val x0 = cx + sinA * r * TICK_MINUTE_OUTER
            val y0 = cy - cosA * r * TICK_MINUTE_OUTER
            val x1 = cx + sinA * r * TICK_MINUTE_INNER
            val y1 = cy - cosA * r * TICK_MINUTE_INNER

            if (i % MINUTE_GRADUATION == 0) {
                val base = major++ * 4
                minuteFiveTicks[base] = x0
                minuteFiveTicks[base + 1] = y0
                minuteFiveTicks[base + 2] = x1
                minuteFiveTicks[base + 3] = y1
            } else {
                val base = minor++ * 4
                minuteTicks[base] = x0
                minuteTicks[base + 1] = y0
                minuteTicks[base + 2] = x1
                minuteTicks[base + 3] = y1
            }
        }

    }

    private fun buildHands() {
        // Hour hand — a slim lance with convex flanks and a rounded tail. No crossguard: against a
        // 24 h scale it read as a stray tick mark rather than as part of the hand. It stops just
        // short of the hour numerals, whose inner edge is around 0.638 r, so it points at them
        // without overlapping them.
        val hLen = r * 0.620f
        val hWide = r * 0.032f
        val tail = r * 0.115f
        hourHand.reset()
        hourHand.moveTo(cx, cy - hLen)
        hourHand.cubicTo(
            cx + hWide, cy - hLen * 0.58f,
            cx + hWide, cy - hLen * 0.16f,
            cx + hWide * 0.62f, cy + tail,
        )
        hourHand.quadTo(cx, cy + tail + r * 0.032f, cx - hWide * 0.62f, cy + tail)
        hourHand.cubicTo(
            cx - hWide, cy - hLen * 0.16f,
            cx - hWide, cy - hLen * 0.58f,
            cx, cy - hLen,
        )
        hourHand.close()

        // Minute hand — precision needle, tapering from base to tip, reaching almost to the inner
        // edge of the minute ring at 0.930 r so it is read against its own scale.
        val mLen = r * 0.905f
        val mBase = r * 0.020f
        val mTip = r * 0.005f
        val mTail = r * 0.130f
        minuteHand.reset()
        minuteHand.moveTo(cx - mTip, cy - mLen)
        minuteHand.lineTo(cx + mTip, cy - mLen)
        minuteHand.lineTo(cx + mBase, cy - r * 0.060f)
        minuteHand.lineTo(cx + mBase * 0.8f, cy + mTail)
        minuteHand.lineTo(cx - mBase * 0.8f, cy + mTail)
        minuteHand.lineTo(cx - mBase, cy - r * 0.060f)
        minuteHand.close()

        // Solid caps, cut out of the silhouettes themselves rather than drawn as separate shapes.
        // A hand-authored cap would have to re-derive the same curves and would drift out of
        // register with the outline the moment either is tweaked.
        buildSolid(hourHand, hLen, SOLID_TIP_HOUR, hourHandSolid)
        buildSolid(minuteHand, mLen, SOLID_TIP_MINUTE, minuteHandSolid)

        // Ambient variants are the same silhouettes; the renderer strokes rather than fills them,
        // so a shared Path would be enough — separate copies keep the intent obvious and let the
        // ambient shapes be simplified independently later.
        hourHandOutline.set(hourHand)
        minuteHandOutline.set(minuteHand)
    }

    /** Intersects [silhouette] with a band at the point and another below the hub. */
    private fun buildSolid(silhouette: Path, length: Float, tipDepth: Float, out: Path) {
        val tip = Path().apply {
            addRect(
                cx - r, cy - length - r * 0.02f,
                cx + r, cy - length + r * tipDepth,
                Path.Direction.CW,
            )
        }
        // The counterweight starts at the edge of the hub, not above it: taken from the centre it
        // swallows the widest part of the shaft and the two hands pool into a blob where they
        // cross. Tucked just under the hub it still reads as attached.
        val tail = Path().apply {
            addRect(cx - r, cy + hubRadius * 0.5f, cx + r, cy + r, Path.Direction.CW)
        }
        tip.op(tail, Path.Op.UNION)
        out.set(silhouette)
        out.op(tip, Path.Op.INTERSECT)
    }

    private fun buildTelemetry() {
        telemetryTextSize = r * TEXT_SIZE
        for (i in LINE_BASELINES.indices) {
            telemetryLineY[i] = cy + r * LINE_BASELINES[i]
        }

        batteryLineY = cy + r * BATTERY_BASELINE
        batteryTextSize = r * BATTERY_TEXT_SIZE
        statusTextSize = r * STATUS_TEXT_SIZE
        statusLineY = cy + r * STATUS_BASELINE

        sensorLineY = cy + r * SENSOR_BASELINE
        sensorOffsetX = r * SENSOR_OFFSET_X
        sensorTextSize = r * SENSOR_TEXT_SIZE
        sensorGlyphSize = r * SENSOR_TEXT_SIZE * SENSOR_GLYPH_RATIO
        sensorGlyphGap = r * SENSOR_GLYPH_GAP

        val symbolSize = r * SYMBOL_SIZE
        symbolBox.set(
            cx, telemetryLineY[2] - symbolSize,
            cx + symbolSize, telemetryLineY[2],
        )

        val glyphSize = r * TEXT_SIZE
        glyphBox.set(
            cx, telemetryLineY[3] - glyphSize,
            cx + glyphSize, telemetryLineY[3],
        )
        glyphGap = r * GLYPH_GAP

        // Loose boxes for the two spoken labels, not drawing geometry: TalkBack highlights them
        // and reads the text, so "roughly where the words are" is the whole requirement.
        a11yReadoutBounds.set(
            (cx - r * 0.45f).toInt(), (cy - r * 0.45f).toInt(),
            (cx + r * 0.45f).toInt(), (cy + r * 0.45f).toInt(),
        )
        a11yStatusBounds.set(
            (cx - r * 0.45f).toInt(), (statusLineY - statusTextSize * 1.2f).toInt(),
            (cx + r * 0.45f).toInt(), (statusLineY + statusTextSize * 0.4f).toInt(),
        )
    }

    companion object {
        const val HOUR_TICKS = 24

        /** One division per minute of the minute hand and per second of the seconds cursor. */
        const val MINUTE_TICKS = 60
        const val SECONDS_SEGMENT_DEGREES = 360f / MINUTE_TICKS

        /** Every fifth division is drawn heavier. */
        const val MINUTE_GRADUATION = 5
        const val MINUTE_MAJOR_TICKS = MINUTE_TICKS / MINUTE_GRADUATION
        const val MINUTE_MINOR_TICKS = MINUTE_TICKS - MINUTE_MAJOR_TICKS

        /** Outer edge of the seconds marker, just clear of the tick tips. */
        private const val SECONDS_MARKER_BASE = 0.976f

        /** Marker base as a multiple of the minute-tick pitch — a little wider, as asked. */
        private const val SECONDS_MARKER_PITCH_RATIO = 1.15

        private const val RING_RADIUS = 0.985f

        /** Outer band: the 60-division minute and seconds scale. */
        const val TICK_MINUTE_INNER = 0.930f
        private const val TICK_MINUTE_OUTER = 0.970f

        /**
         * Inner band: the 24-hour scale, on its own ring. The two scales do not share divisors, so
         * they get their own tracks rather than fighting for the same one; the hour ticks stop
         * short of the minute ring so the gap between them reads as deliberate.
         */
        const val TICK_HOUR_INNER = 0.785f
        private const val TICK_HOUR_OUTER = 0.870f
        const val TICK_CARDINAL_OUTER = 0.905f

        private const val LABEL_RADIUS = 0.680f

        /** The duty arc rides on the inner circle the hour ticks spring from. */
        const val DUTY_ARC_RADIUS = 0.760f

        /**
         * The Mars face's duty arc: between the hour hand's tip (0.620 r) and the hour numerals
         * (inner edge 0.632 r), so the hand points onto its own rail. The clearances are held by
         * a test: the band's outer edge (0.605 + 0.028/2 = 0.619 r) stays under the numerals,
         * and its inner edge (0.591 r) clears the readout's worst corner (~0.57 r).
         */
        const val DUTY_ARC_RADIUS_MARS = 0.605f

        /**
         * The comm lines hug the hour-tick ring, one on each edge, separating by radius because
         * a fourth blue-free hue does not exist. The collision arithmetic, held by
         * CommTrackTest: the inner band spans 0.776–0.785 r — its outer edge lands exactly on
         * [TICK_HOUR_INNER], the circle the ticks spring from, and its inner edge clears the
         * duty arc's outer edge (0.760 + 0.028/2 = 0.774 r); the outer band spans
         * 0.9105–0.9195 r, clear of both the cardinal tips ([TICK_CARDINAL_OUTER]) and the
         * minute ring ([TICK_MINUTE_INNER]).
         */
        const val COMM_INNER_RADIUS = 0.7805f
        const val COMM_OUTER_RADIUS = 0.915f
        const val COMM_STROKE = 0.009f

        /**
         * The readout is bare type on the dial — no window, no frame — split either side of the
         * hub: ZULU above, the reference frame and the situational data below. Offsets are signed,
         * measured from the centre.
         *
         * ### Why these numbers
         * The type is sized from the longest line the readout can produce. Two tie at 16
         * characters: the zulu row with its date-time group, `Z 18AUG 18:42:15`, and the Earth
         * weather row `-40°C TSGR Q1013`, which also carries the reference-frame symbol in front of
         * it and is therefore the wider of the two. `DUTY: 03:42 REM` is 15, and the site row is
         * shorter still even with a six-character code and a pictogram.
         *
         * Monospace advance is 0.60 em, so a 16-character line is 9.6 × [TEXT_SIZE] wide. A
         * horizontal line at vertical offset y with half-width w reaches radius sqrt(w² + y²) at
         * its corners, and that has to stay inside the hour numerals, whose inner edge sits at
         * about 0.632 r. With the outer rows spread out the four corners land within a whisker of
         * each other at about 0.57 r, leaving roughly 0.065 r of clearance, or 15 px on a 454 px
         * dial.
         */
        internal const val TEXT_SIZE = 0.096f

        /**
         * Monospace advance, the figure every width above is written in. Owned here rather than in
         * [TelemetryLayer][com.avdesign.mfd24.render.TelemetryLayer] because this file is where the
         * budget arithmetic lives; the layer draws with it, `GeometryBudgetTest` audits with it.
         */
        internal const val MONO_ADVANCE = 0.60f

        /** Space between a pictogram and the line it leads, as a fraction of the radius. */
        internal const val GLYPH_GAP = 0.028f

        /**
         * Inner edge of the hour numerals, the boundary every readout corner must stay inside.
         * Named so `GeometryBudgetTest` can check the shipped rows against it instead of trusting
         * the prose above.
         */
        internal const val NUMERAL_INNER_EDGE = 0.632f

        /**
         * Duty then ZULU above the hub; the two data rows below it.
         *
         * The inner pair sits close in. Pushed further out the block reads as two separate
         * captions with a bare stripe between them, rather than as one instrument panel with the
         * hands running through it — and the stripe is the widest part of the dial, so it is the
         * emptiness you notice first.
         *
         * The outer pair is spread away from them to use the height the dial actually has. It can
         * go about as far as 0.42 r before the corners of the longest row start crowding the hour
         * numerals; these sit inside that with room to spare.
         */
        internal val LINE_BASELINES = floatArrayOf(-0.360f, -0.160f, 0.200f, 0.395f)

        /**
         * The battery row, below the site row and outside the block the four lines form.
         *
         * 0.42 r is the limit for a *sixteen*-character line; `BAT 84%` is seven, which is what buys
         * the extra distance. At [BATTERY_TEXT_SIZE] its half-width is 7 × 0.60 × 0.086 / 2 =
         * 0.180 r, so the corner reaches sqrt(0.180² + 0.520²) = 0.550 r — inside the numerals'
         * 0.632 r by more than the existing worst corner manages. Lengthen this row and that stops
         * being true.
         */
        internal const val BATTERY_BASELINE = 0.520f

        /** A step under the readout: it is a background figure, not one of the four data rows. */
        internal const val BATTERY_TEXT_SIZE = 0.086f

        /** The reference-frame symbol leads the first data row, a little taller than the type. */
        internal const val SYMBOL_SIZE = 0.104f

        /**
         * The two optional readouts sit on the hub's own line, outboard of every other row.
         *
         * ### Why here
         * This is the only clear ground left. The readout rows reach 0.42 r either side of centre
         * at their widest, and they sit at -0.160 and +0.200; outside 0.42 r on the centre line
         * there is nothing at all between the hub and the hour numerals. Nothing had to move.
         *
         * ### The arithmetic
         * The offset is set by the widest line, not by the shortest. `QFE 1013` is eight characters
         * at 0.60 em of [SENSOR_TEXT_SIZE], which is 0.413 r wide and 0.206 r either side of its
         * centre; at 0.364 r that puts the outer edge at 0.570 r, the same reach as the readout's
         * own worst corner, and the inner edge at 0.158 r, well outside the hub. A pictogram slot
         * takes about half the width and simply sits further in, which keeps the two symmetrical.
         *
         * Lengthen a slot past eight characters and the arithmetic stops holding, which is why
         * steps go to `12K` rather than running to five figures.
         */
        internal const val SENSOR_OFFSET_X = 0.364f
        internal const val SENSOR_BASELINE = 0.030f

        /**
         * The same size as the battery row, and the only size in the slot.
         *
         * A pictogram over a value in two sizes was the first attempt and it was wrong: three type
         * sizes stacked in one corner of a dial makes the eye re-focus for every reading, which is
         * the opposite of what an instrument face is for. One line, one size, and the pictogram
         * sized to the digits beside it.
         */
        internal const val SENSOR_TEXT_SIZE = 0.086f

        /** Glyph height against the type it sits with, and the space between the two. */
        private const val SENSOR_GLYPH_RATIO = 0.90f
        private const val SENSOR_GLYPH_GAP = 0.022f

        /**
         * The vigilance status sits above the readout, smaller, and only appears when the monitor
         * is doing something worth saying. Set in the smaller size so a thirteen-character word
         * still clears the numerals this far out.
         */
        internal const val STATUS_TEXT_SIZE = 0.078f
        internal const val STATUS_BASELINE = -0.470f

        /**
         * Centre hub, as a fraction of the dial radius — about 9 dp across on a 454 px watch.
         *
         * It was 0.030, which is 6.8 dp and too small to read a fraction inside. Enlarging it is
         * what makes the vigilance core legible; going further, to the 30-odd dp a separate
         * indicator would want, would put the hub into the weather row 38 px below the centre.
         */
        private const val HUB_RADIUS = 0.040f

        /** How far down from each point the hand stays filled. */
        private const val SOLID_TIP_HOUR = 0.155f
        private const val SOLID_TIP_MINUTE = 0.215f

        /** Contour weight of the skeletonised body: heavy enough to read, light enough to see past. */
        const val HAND_OUTLINE_WIDTH = 0.009f

        /**
         * Screen angle for an hour on the 24-hour dial, in radians clockwise from 12 o'clock.
         * Noon is up and midnight down, or the other way round when [midnightUp] flips the axis.
         */
        fun hourAngleRad(hours: Double, midnightUp: Boolean): Double =
            (hours / 24.0 + if (midnightUp) 0.0 else 0.5) * 2.0 * Math.PI
    }
}
