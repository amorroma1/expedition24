// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.health

/**
 * The three rings the day is written on: what each quarter-hour is coloured, and how heavily it
 * is drawn.
 *
 * They read from the outside in and answer three different questions — **what the heart was
 * doing**, **how hard the body worked**, and **how the night went**. A quarter-hour marks the
 * pulse ring and exactly one of the other two, so the inner pair reads as a single band that
 * changes character at the moment a person lies down.
 *
 * ### Two channels, and why
 * Each mark carries its value twice: in the hue and in the **weight of the line**. Colour alone
 * has to shout to be read at a glance, and a dial that shouts all day is one a person stops
 * looking at; weight says the same thing quietly, and the two together let the palette stay calm
 * — a resting hour is a thin, dark thread, an hour of running is a thick bright one, and the eye
 * finds the second without being assaulted by the first. It also means the day still reads on a
 * dimmed always-on face, where hues collapse toward one another but thicknesses do not.
 *
 * ### On the colours
 * The hues are deliberately muted at the bottom of each scale and only fully saturated at the
 * top, so an ordinary day is quiet and an unusual one stands out. Pure phosphor green and pale
 * rose exist, but they are reached by a quarter-hour spent running, not by sitting at a desk.
 *
 * ### On blue
 * The dial's standing rule is that nothing carries blue: blue is the shortest-lived emitter in
 * the panel and always-on holds an image for hours. The rings keep that rule where it matters —
 * every colour a sleeping or resting wrist draws is blue-free — and only the coral and rose of a
 * working heart carry a trace, marks made while a person is awake and moving, which no night
 * will burn in.
 *
 * Pure; the render path reads colours and weights out of precomputed arrays.
 */
object VitalRings {

    // --- Pulse ---------------------------------------------------------------------------------

    /** Under this the heart is at its deepest — asleep, or very deeply at rest. */
    const val PULSE_DEEP_MAX = 45

    /** Resting and quiet: sitting, reading, driving. */
    const val PULSE_REST_MAX = 60

    /** Working: the ordinary range of a day on its feet. */
    const val PULSE_WORK_MAX = 90

    /** Deep maroon, blue-free: what a sleeping wrist draws all night. */
    const val PULSE_DEEP = 0xFF6E1008.toInt()

    /** A shade up and a little warmer, still blue-free. */
    const val PULSE_REST = 0xFFA82A14.toInt()

    /** Coral: the working range, and the first shade allowed a trace of blue. */
    const val PULSE_WORK = 0xFFCB5B38.toInt()

    /**
     * Rose: over ninety, and only ever drawn by a heart that is awake. Muted from where it
     * started — a full rose was the loudest thing on the dial and pulled the eye away from
     * whatever the wearer had actually picked the watch up to see. The weight of the stroke
     * carries the intensity now; the hue only has to name the zone.
     */
    const val PULSE_HIGH = 0xFFE38B74.toInt()

    // --- Activity ------------------------------------------------------------------------------

    /** Barely moving: a deep, quiet green that does not compete with the dial's own hue. */
    const val ACTIVITY_LOW = 0xFF14501C.toInt()

    /** Flat out: the phosphor green the palette keeps for the thing that has earned it. */
    const val ACTIVITY_HIGH = 0xFF00F03C.toInt()

    /** Steps in a quarter-hour that count as flat out; the ramp is capped there. */
    const val ACTIVITY_FULL_STEPS = 1200

    // --- Sleep ---------------------------------------------------------------------------------

    /** Sleep is amber, and its depth is carried by brightness and weight, never by hue. */
    const val SLEEP_AMBER = 0xFFFFB000.toInt()

    /** How far above the resting rate each depth reaches, in percent. */
    const val SLEEP_DEEP_PCT = 102
    const val SLEEP_LIGHT_PCT = 112

    const val DEPTH_DEEP = 0
    const val DEPTH_LIGHT = 1
    const val DEPTH_RESTLESS = 2

    private val SLEEP_ALPHA = intArrayOf(0xFF, 0xB4, 0x74)

    /** Weight, as a fraction of a ring's full width, for each depth of sleep. */
    private val SLEEP_WEIGHT = floatArrayOf(1.0f, 0.72f, 0.46f)

    /** Alpha for a bin that is drawn but carries no reading — present, unmeasured. */
    const val ALPHA_UNKNOWN = 0x4E

    /** The thinnest a mark is ever drawn: below this it stops reading as a line at all. */
    const val MIN_WEIGHT = 0.40f

    /**
     * The pulse ring's colour. Four zones rather than a continuous ramp: on nine pixels of arc
     * the eye reads a step and not a gradient, and four is as many steps as that can carry.
     */
    fun pulseColor(bpm: Int): Int = when {
        bpm <= DayBins.NO_BPM -> 0
        bpm < PULSE_DEEP_MAX -> PULSE_DEEP
        bpm < PULSE_REST_MAX -> PULSE_REST
        bpm < PULSE_WORK_MAX -> PULSE_WORK
        else -> PULSE_HIGH
    }

    /** And its weight: a quiet heart is a thread, a working one is a full stroke. */
    fun pulseWeight(bpm: Int): Float = when {
        bpm <= DayBins.NO_BPM -> MIN_WEIGHT
        bpm < PULSE_DEEP_MAX -> 0.46f
        bpm < PULSE_REST_MAX -> 0.60f
        bpm < PULSE_WORK_MAX -> 0.82f
        else -> 1.0f
    }

    /**
     * How hard a quarter-hour worked, from nothing to flat out.
     *
     * Steps *or* heart, whichever says more — and the "or" is the point. A step counter sees
     * walking and nothing else: an hour of digging, of carrying a child up a hill, of anything
     * done standing in one place, all read as a quarter-hour spent sitting. The pulse sees the
     * work but not the walking (a stroll barely lifts it). Taking the greater of the two makes
     * the ring say *the body worked* rather than *the feet moved*, which is the question anybody
     * looks at it to answer. Where there is no pulse the steps carry it alone, as before.
     */
    fun effort(steps: Int, bpm: Int, restingBpm: Int): Int {
        val fromSteps = steps.coerceIn(0, ACTIVITY_FULL_STEPS) * 255 / ACTIVITY_FULL_STEPS
        if (bpm <= DayBins.NO_BPM || restingBpm <= DayBins.NO_BPM) return fromSteps
        // Nothing counts until the pulse is clear of its own floor — a resting heart is not
        // effort — and it reaches the top at about twice resting, which is a hard hour for
        // anybody whose floor this is.
        val floor = restingBpm * EFFORT_FLOOR_PCT / 100
        val ceiling = restingBpm * EFFORT_CEILING_PCT / 100
        if (bpm <= floor || ceiling <= floor) return fromSteps
        val fromPulse = ((bpm - floor) * 255 / (ceiling - floor)).coerceIn(0, 255)
        return maxOf(fromSteps, fromPulse)
    }

    /** Where effort starts and tops out, as percentages of the wearer's own resting rate. */
    const val EFFORT_FLOOR_PCT = 125
    const val EFFORT_CEILING_PCT = 200

    /**
     * The activity ring's colour: a deep green for a quarter-hour that barely moved, phosphor
     * for one spent working. Interpolated in the channels, so the ramp reads as one colour
     * getting stronger rather than as two colours mixing.
     */
    fun activityColor(effort: Int): Int {
        val t = effort.coerceIn(0, 255)
        val r = lerp(ACTIVITY_LOW ushr 16 and 0xFF, ACTIVITY_HIGH ushr 16 and 0xFF, t)
        val g = lerp(ACTIVITY_LOW ushr 8 and 0xFF, ACTIVITY_HIGH ushr 8 and 0xFF, t)
        val b = lerp(ACTIVITY_LOW and 0xFF, ACTIVITY_HIGH and 0xFF, t)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Weight from the same figure, so the ring thickens as the day gets busier. */
    fun activityWeight(effort: Int): Float {
        val t = effort.coerceIn(0, 255).toFloat() / 255f
        return MIN_WEIGHT + (1.0f - MIN_WEIGHT) * t
    }

    /**
     * How deep a sleeping quarter-hour was, from the pulse against the wearer's own resting
     * rate. Not a clinical staging — a watch cannot see brain waves, and the README says so —
     * but the shape it draws is the real one: the heart runs slowest in the deepest sleep and
     * lifts toward morning, so a night reads as heavy bands early and lighter ones later.
     *
     * A bin with no reading is called light rather than deep: the stronger claim is the one that
     * needs evidence.
     */
    fun sleepDepth(bpm: Int, restingBpm: Int): Int {
        if (bpm <= DayBins.NO_BPM || restingBpm <= DayBins.NO_BPM) return DEPTH_LIGHT
        return when {
            bpm <= restingBpm * SLEEP_DEEP_PCT / 100 -> DEPTH_DEEP
            bpm <= restingBpm * SLEEP_LIGHT_PCT / 100 -> DEPTH_LIGHT
            else -> DEPTH_RESTLESS
        }
    }

    /** The sleep ring's colour: one amber, dimmed by how light the sleep was. */
    fun sleepColor(depth: Int): Int =
        (SLEEP_AMBER and 0x00FFFFFF) or (SLEEP_ALPHA[depth.coerceIn(0, 2)] shl 24)

    fun sleepWeight(depth: Int): Float = SLEEP_WEIGHT[depth.coerceIn(0, 2)]

    /** A reading that is present but unmeasured, in the ring's own hue at a whisper. */
    fun faint(color: Int): Int = (color and 0x00FFFFFF) or (ALPHA_UNKNOWN shl 24)

    private fun lerp(from: Int, to: Int, t: Int): Int = from + (to - from) * t / 255
}
