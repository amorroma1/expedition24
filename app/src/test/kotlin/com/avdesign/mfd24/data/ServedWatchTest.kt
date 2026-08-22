// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How long a finished watch stays on the dial.
 *
 * A served shift used to stay for ever — its grey arc and its readout row sat there until another
 * shift replaced them. That is furniture: it cannot be acted on, and it occupies the one arc and
 * the one row the *next* watch needs. It now retires an hour after it ends, and this pins the
 * boundary, which is otherwise a thing you would only notice by leaving a watch face alone for an
 * afternoon.
 *
 * The retirement is a function of the clock, not an event: nothing has to run at the hour mark, so
 * a process that was asleep across it comes back to the same answer.
 */
class ServedWatchTest {

    private val start = 1_787_300_000_000L
    private val end = start + 8 * 3_600_000L

    private val state = WatchShiftState().apply {
        hasShift = true
        startMillis = start
        endMillis = end
    }

    @Test
    fun `a running watch is active, and stays served for the hour after it ends`() {
        assertEquals(WatchShiftState.DUTY_ACTIVE, state.dutyState(end - 1L))
        assertEquals(WatchShiftState.DUTY_SERVED, state.dutyState(end))
        assertEquals(
            WatchShiftState.DUTY_SERVED,
            state.dutyState(end + WatchShiftState.SERVED_VISIBLE_MILLIS - 1L),
        )
    }

    @Test
    fun `an hour past its end the watch is gone from the dial`() {
        assertEquals(
            WatchShiftState.DUTY_OFF,
            state.dutyState(end + WatchShiftState.SERVED_VISIBLE_MILLIS),
        )
        assertEquals(WatchShiftState.DUTY_OFF, state.dutyState(end + 24 * 3_600_000L))
    }

    @Test
    fun `retirement is derived from the clock, not remembered`() {
        // The same state object, asked twice, answers by the instant it is given — which is what
        // makes a reboot, a doze, or a time-zone change land in the right place without anything
        // having had to run at the boundary.
        assertEquals(WatchShiftState.DUTY_SERVED, state.dutyState(end + 60_000L))
        assertEquals(WatchShiftState.DUTY_OFF, state.dutyState(end + 2 * 3_600_000L))
        assertEquals(WatchShiftState.DUTY_SERVED, state.dutyState(end + 60_000L))
    }

    @Test
    fun `a booked watch is still pending, and no shift is still off`() {
        assertEquals(WatchShiftState.DUTY_PENDING, state.dutyState(start - 1L))
        assertEquals(WatchShiftState.DUTY_OFF, WatchShiftState().dutyState(start))
    }
}
