// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

/**
 * When the SOS sounds, and for how long — as arithmetic rather than as a loop that runs until the
 * battery gives out.
 *
 * The earlier escalation sounded one continuous SOS every five seconds for five minutes: 56 cycles,
 * every one of them holding the processor awake, driving the vibrator at full amplitude and building
 * a fresh tone generator. On a watch that is already the emergency's only radio, that is the wrong
 * thing to spend the charge on — and it was measurably the largest single draw the face could
 * produce.
 *
 * The shape now is a **burst pattern**, which is how a distress signal has always actually been
 * sent: shout, then listen. One long burst while somebody is most likely to be within earshot, then
 * single bursts a minute apart, which a searcher can walk towards. Between bursts nothing runs.
 *
 *  - The **nudge** is burst zero and is not here: it is the vibration in `VigilanceService.prompt`,
 *    followed by the thirty-second answer window.
 *  - **Burst one** runs for [FIRST_BURST_MILLIS] — long enough to be found by someone in the room.
 *  - **Bursts two to five** are one unit each, [BURST_GAP_MILLIS] apart.
 *
 * Six signalling events in total counting the nudge, spanning about four and a half minutes, and
 * then the watch goes quiet and holds the incident — the record is what has to outlast everyone's
 * attention, and it needs the charge more than a sixth minute of beeping does.
 *
 * Pure and tested: the timing is a promise about how long an unconscious operator's watch keeps
 * calling for help, and that is not a thing to verify by sitting next to a wrist for five minutes.
 */
object SosSchedule {

    /** One burst: when it starts, relative to the escalation, and how many SOS units it repeats. */
    data class Burst(val startOffsetMillis: Long, val units: Int)

    /**
     * The burst at [index] (zero-based, first burst is 0), or null past the end of the schedule.
     *
     * The first burst's unit count is derived from [FIRST_BURST_MILLIS] rather than written down, so
     * changing the unit's own length cannot silently turn "thirty seconds of SOS" into ten.
     */
    fun burst(index: Int, unitMillis: Long): Burst? {
        if (index < 0 || index >= BURSTS) return null
        if (index == 0) {
            val units = ((FIRST_BURST_MILLIS + unitMillis - 1) / unitMillis).toInt().coerceAtLeast(1)
            return Burst(0L, units)
        }
        return Burst(FIRST_BURST_MILLIS + index * BURST_GAP_MILLIS, UNITS_PER_LATER_BURST)
    }

    /** How long the whole schedule lasts, for the doc and for the test that pins it. */
    fun totalMillis(unitMillis: Long): Long {
        val last = burst(BURSTS - 1, unitMillis) ?: return 0L
        return last.startOffsetMillis + last.units * unitMillis
    }

    /** Bursts after the nudge. Five here plus the nudge is the six events the escalation spends. */
    const val BURSTS: Int = 5

    /** The opening burst: thirty seconds of continuous calling. */
    const val FIRST_BURST_MILLIS: Long = 30_000L

    /** A minute of silence between later bursts — long enough to listen, short enough to follow. */
    const val BURST_GAP_MILLIS: Long = 60_000L

    /**
     * Later bursts are doubled rather than single.
     *
     * A lone SOS a minute apart is easy to mistake for a notification from any of the other things
     * on a wrist. Two in a row, with the letter gap between them, is unmistakably the pattern.
     */
    const val UNITS_PER_LATER_BURST: Int = 2
}
