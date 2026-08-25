// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.style

import android.graphics.Color
import com.avdesign.mfd24.astro.PlanetMode

/**
 * Resolved colours for one (palette, planet) combination.
 *
 * A [Palette] is rebuilt only when the user style changes, never per frame — the renderer reads the
 * plain `Int` fields directly.
 *
 * ### Three hues, none of them containing blue
 * Blue is by a wide margin the shortest-lived emitter in an OLED stack, and always-on holds the same
 * image for hours, so a face with blue in it is a face that ages its own panel. Rather than keep a
 * separate blue-free palette for always-on and cross-fade between the two on waking, the whole
 * palette is blue-free and always-on is simply **the same hue, dimmer**. Nothing changes colour when
 * the watch wakes; only the brightness comes up.
 *
 * That is why there is no white here. Cockpit White was mostly blue, which made it exactly the wrong
 * thing to leave on screen all night.
 */
class Palette {

    /** Primary marking colour. */
    var lume: Int = ALERT_AMBER
        private set

    /** Telemetry body text — readable, but a step below the primary markings. */
    var lumeSoft: Int = 0
        private set

    /** Minor ticks and secondary text. */
    var lumeDim: Int = 0
        private set

    /** Bezel rings and the minute-ring hairlines. */
    var lumeFaint: Int = 0
        private set

    /** Contrasting accent: the seconds cursor, the hub ring and the duty arc. */
    var second: Int = NVG_RED
        private set

    var background: Int = BG_EARTH
        private set

    /** Warm inner glow behind the dial, keyed to the celestial body. */
    var horizon: Int = 0
        private set

    /**
     * Colour of the watch (shift) arc.
     *
     * The accent rather than a setting of its own. A separate arc colour was three menu items that
     * changed nothing visible unless a shift happened to be running, which is most of the time not
     * the case, so it read as broken rather than as optional.
     */
    var dutyArc: Int = NVG_RED
        private set

    /** A finished watch keeps its arc, drained — the shift is served, not forgotten. */
    var dutyArcSpent: Int = SPENT_GREY
        private set

    /**
     * Colour of the Nadir band: the lume hue, held well back.
     *
     * It used to be a fixed teal, which sat outside the palette and clashed with every hue but the
     * one it was chosen against — and teal has blue in it, so always-on could not use it anyway.
     * Deriving it from the lume means the band shades the dial in the face's own colour: terracotta
     * under amber, moss under green, oxblood under red.
     */
    var daylightBand: Int = withAlpha(ALERT_AMBER, NADIR_ALPHA)
        private set

    /**
     * Colour of an incident mark on the duty arc.
     *
     * The one thing on this dial drawn in a hue the palette does not otherwise contain, and the
     * reason is that a mark sharing a hue with the band it crosses is not a mark. There are three
     * blue-free hues; a palette spends two of them, on the dial and on the accent; the mark takes
     * the third, so it can collide with neither. Green unless the dial is green, in which case red.
     *
     * Note that this is *not* the same as the opposite of the dial's own colour, which is the
     * obvious first guess. Under the amber palette the accent — and therefore the duty arc — is
     * already red, so a red mark would be exactly as invisible there as the accent-coloured mark
     * was everywhere. What has to be avoided is the arc's hue, not the dial's.
     *
     * Blue-free either way, so the panel is no worse off, and it is dimmed for always-on with
     * everything else rather than sitting outside the scheme.
     *
     * The earlier attempt used the lume and stood the mark proud of the band with a halo, on the
     * argument that the palette's own two hues are the strongest contrast available. They are — and
     * it still read as an untidy tick that caught the eye rather than as a reading, because a mark
     * that overshoots its band is furniture the dial does not otherwise have.
     */
    var incidentMark: Int = NVG_RED
        private set

    /**
     * The solar mark's disc: sun-coloured, whatever the palette wears.
     *
     * A fixed bright amber rather than a palette hue, because the mark stands for the actual sun
     * and amber is what the eye already calls it. Brighter than the lume's own amber by pushing
     * the green channel, never by mixing towards white — white carries blue, and blue is the one
     * thing no pixel on this face is allowed to hold overnight.
     */
    var sunMark: Int = SUN_AMBER
        private set

    /**
     * The lunar mark's lit side: neutral grey, because the moon is grey and painting it in a
     * palette hue would make it one more accent. The one deliberate exception to the blue-free
     * rule, shared with [SPENT_GREY]: a grey with the blue stripped turns yellow, and both of
     * these are small, rare and dimmed with everything else in always-on.
     */
    var moonMark: Int = MOON_GREY
        private set

    fun update(paletteId: String, planetMode: Int) {
        lume = colorForId(paletteId)
        lumeSoft = withAlpha(lume, 0xC8)
        lumeDim = withAlpha(lume, 0x8C)
        lumeFaint = withAlpha(lume, 0x47)
        // The accent must never share the lume's hue or it stops being an accent. Both candidates
        // are blue-free, so the choice costs nothing in panel life.
        second = if (lume == ALERT_AMBER) NVG_RED else ALERT_AMBER
        background = when (planetMode) {
            PlanetMode.MARS -> BG_MARS
            PlanetMode.MOON -> BG_MOON
            else -> BG_EARTH
        }
        horizon = when (planetMode) {
            PlanetMode.MARS -> 0xFF2E1109.toInt()
            PlanetMode.MOON -> 0xFF1D1F23.toInt()
            else -> 0xFF141720.toInt()
        }
        dutyArc = second
        dutyArcSpent = SPENT_GREY
        daylightBand = withAlpha(lume, NADIR_ALPHA)
        // The third hue: the one that is neither the dial's nor the arc's. There are exactly three
        // blue-free hues and the palette spends two, so the remaining one is determined, and the
        // arithmetic is short because the accent is always amber unless the lume is.
        incidentMark = if (lume == PHOSPHOR_GREEN) NVG_RED else PHOSPHOR_GREEN
        sunMark = SUN_AMBER
        moonMark = MOON_GREY
    }

    /**
     * Reconfigures this palette as the always-on variant of [source]: the same hues, scaled down to
     * [AMBIENT_LEVEL], on a black ground.
     *
     * Dimming by scaling the channels rather than by alpha keeps the hue exactly where it was, which
     * is the whole point — waking then has nothing to cross-fade, only a brightness to raise. Alpha
     * would also composite badly where antialiased strokes overlap.
     */
    fun updateAmbientFrom(source: Palette) {
        lume = dim(source.lume, AMBIENT_LEVEL)
        lumeSoft = dim(source.lumeSoft, AMBIENT_LEVEL)
        lumeDim = dim(source.lumeDim, AMBIENT_LEVEL)
        lumeFaint = dim(source.lumeFaint, AMBIENT_LEVEL)
        second = dim(source.second, AMBIENT_LEVEL)
        dutyArc = dim(source.dutyArc, AMBIENT_LEVEL)
        dutyArcSpent = dim(source.dutyArcSpent, AMBIENT_LEVEL)
        incidentMark = dim(source.incidentMark, AMBIENT_LEVEL)
        sunMark = dim(source.sunMark, AMBIENT_LEVEL)
        moonMark = dim(source.moonMark, AMBIENT_LEVEL)
        daylightBand = withAlpha(dim(source.lume, AMBIENT_LEVEL), NADIR_ALPHA)
        background = Color.BLACK
        horizon = Color.BLACK
    }

    companion object {
        const val ID_AMBER: String = "amber"
        const val ID_GREEN: String = "green"
        const val ID_RED: String = "red"

        // Pure by construction: zero blue in any of them.
        const val ALERT_AMBER: Int = 0xFFFFB000.toInt()
        const val PHOSPHOR_GREEN: Int = 0xFF00FF00.toInt()
        const val NVG_RED: Int = 0xFFFF0000.toInt()

        /** The sun's own disc: brighter than ALERT_AMBER, still zero blue. */
        const val SUN_AMBER: Int = 0xFFFFD000.toInt()

        /** Moonlight grey, a step brighter than [SPENT_GREY] so the phase reads at ten pixels. */
        const val MOON_GREY: Int = 0xFFC5CAD1.toInt()

        /**
         * How bright always-on is, as a fraction of interactive.
         *
         * Low enough to be restful in a dark room, high enough that the dial is still readable at a
         * glance without waking the watch — which is the only reason to have an always-on face.
         */
        const val AMBIENT_LEVEL: Float = 0.45f

        /** Neutral grey for a watch that has already been served. */
        const val SPENT_GREY: Int = 0xFFA8AEB6.toInt()

        /**
         * How strongly the Nadir band tints the dial. Low: it is a shaded sector of the hour scale,
         * not a ring of its own, and it sits under ticks and numerals that must stay the brightest
         * things on the dial.
         */
        const val NADIR_ALPHA: Int = 0x3A

        /** Tactical black backgrounds, one per celestial body. */
        const val BG_EARTH: Int = 0xFF0D0E11.toInt()
        const val BG_MARS: Int = 0xFF1A0A07.toInt()
        const val BG_MOON: Int = 0xFF121315.toInt()

        /**
         * Dims a colour the way always-on dims everything — by scaling the channels, never the
         * alpha — while leaving the alpha exactly where the caller put it. The trail needs both
         * at once: its alpha already carries how much walking a quarter-hour held, and dimming
         * that would turn a brisk hour into a still one at nightfall.
         */
        fun dimKeepingAlpha(color: Int, level: Float): Int {
            val alpha = (color ushr 24) and 0xFF
            return (dim(color, level) and 0x00FFFFFF) or (alpha shl 24)
        }

        fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        /** Scales a colour's channels towards black, leaving its hue and alpha alone. */
        fun dim(color: Int, factor: Float): Int = Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt(),
            (Color.green(color) * factor).toInt(),
            (Color.blue(color) * factor).toInt(),
        )

        fun colorForId(paletteId: String): Int = when (paletteId) {
            ID_GREEN -> PHOSPHOR_GREEN
            ID_RED -> NVG_RED
            else -> ALERT_AMBER
        }
    }
}
