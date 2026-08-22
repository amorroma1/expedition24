// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pulse fields on an incident: what survives a round trip, what a missing reading looks like,
 * and what happens to a log written before the watch knew about heart rates.
 *
 * The last one is the reason this file exists. The log lives in device-protected preferences and is
 * never migrated — it is read back by the same parser that wrote it, one release later. A parser
 * that could not read a 2.2.0 log would silently empty the record on update, which is the one
 * failure this data has no defence against.
 */
class IncidentPulseTest {

    @Test
    fun `a record with both readings survives packing`() {
        val log = arrayOf(IncidentRecord(1_787_326_665_000L, 41, 58, 1_787_325_300_000L))
        val back = IncidentLog.parse(IncidentLog.pack(log))
        assertArrayEquals(log, back)
    }

    @Test
    fun `a log written before pulses were recorded still reads`() {
        // Exactly what 2.2.0 and earlier wrote: bare instants, comma separated.
        val back = IncidentLog.parse("1787326665000,1787330000000")
        assertEquals(2, back.size)
        assertEquals(1_787_326_665_000L, back[0].atMillis)
        assertFalse(back[0].hasBpm)
        assertFalse(back[0].hasBaseline)
    }

    @Test
    fun `a record with no readings packs as compactly as it used to`() {
        // A watch with the setting off must not pay for a feature it is not using, in a string that
        // is bounded and lives in preferences.
        assertEquals("1787326665000", IncidentLog.pack(arrayOf(IncidentRecord(1_787_326_665_000L))))
    }

    @Test
    fun `an incident pulse without a baseline keeps the pulse`() {
        // The sensor locked on during the answer window but never during the preceding activity —
        // the reading is still worth having, and it must not drag the baseline fields in with it.
        val log = arrayOf(IncidentRecord(1_787_326_665_000L, bpm = 41))
        val back = IncidentLog.parse(IncidentLog.pack(log))
        assertEquals(41, back[0].bpm)
        assertFalse(back[0].hasBaseline)
    }

    @Test
    fun `zero is not a pulse`() {
        // A wrist optical sensor reports 0 before it locks on. Treating that as a heart rate of
        // zero would put "HR 0" beside an incident, which reads as a finding rather than as the
        // absence of one.
        assertFalse(IncidentLog.plausibleBpm(0))
        assertFalse(IncidentLog.plausibleBpm(-1))
        assertFalse(IncidentLog.plausibleBpm(300))
        assertTrue(IncidentLog.plausibleBpm(41))
        assertTrue(IncidentLog.plausibleBpm(180))

        val back = IncidentLog.parse("1787326665000:0:0:0")
        assertFalse(back[0].hasBpm)
        assertFalse(back[0].hasBaseline)
    }

    @Test
    fun `the renderer's view is just the instants`() {
        val log = arrayOf(
            IncidentRecord(1_000L, 41, 58, 900L),
            IncidentRecord(2_000L),
        )
        assertArrayEquals(longArrayOf(1_000L, 2_000L), IncidentLog.times(log))
    }

    private fun assertArrayEquals(expected: Array<IncidentRecord>, actual: Array<IncidentRecord>) =
        org.junit.Assert.assertArrayEquals(expected as Array<*>, actual as Array<*>)

    private fun assertArrayEquals(expected: LongArray, actual: LongArray) =
        org.junit.Assert.assertArrayEquals(expected, actual)
}
