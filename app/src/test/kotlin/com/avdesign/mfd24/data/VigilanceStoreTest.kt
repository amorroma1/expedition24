// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incident log's arithmetic, and the rule that decides when an incident stops holding the
 * monitor down.
 *
 * Both are here rather than eyeballed because neither can be seen on a wrist without spending a
 * whole watch producing them: the ceiling only shows itself after thirty-two incidents, and the
 * retirement rule only shows itself as the *absence* of a defect — a new watch that arms instead of
 * coming up already dead.
 */
class VigilanceStoreTest {

    private fun at(millis: Long) = IncidentRecord(millis)

    private val log = arrayOf(at(1_000L), at(2_000L), at(3_000L))

    @Test
    fun `an appended incident goes on the end, oldest first`() {
        assertArrayEquals(arrayOf(at(1_000L), at(2_000L), at(3_000L), at(4_000L)), IncidentLog.appended(log, at(4_000L)))
    }

    @Test
    fun `the first incident starts a log`() {
        assertArrayEquals(arrayOf(at(7L)), IncidentLog.appended(IncidentLog.EMPTY, at(7L)))
    }

    @Test
    fun `a full log drops its oldest entry rather than refusing the newest`() {
        var full = IncidentLog.EMPTY
        for (i in 1..IncidentLog.MAX_ENTRIES) full = IncidentLog.appended(full, at(i.toLong()))
        assertEquals(VigilanceStore.MAX_ENTRIES, full.size)
        assertEquals(1L, full.first().atMillis)

        val overflowed = IncidentLog.appended(full, at(99L))
        assertEquals(
            "the ceiling must hold",
            VigilanceStore.MAX_ENTRIES, overflowed.size
        )
        assertEquals(
            "the newest incident is the one that must survive",
            99L, overflowed.last().atMillis
        )
        assertEquals("the oldest is what goes", 2L, overflowed.first().atMillis)
    }

    @Test
    fun `entries age out after thirty days, oldest-first`() {
        val day = 24 * 3_600_000L
        val now = 100L * day
        val aged = arrayOf(at(now - 40 * day), at(now - 31 * day), at(now - 29 * day), at(now - day))
        assertArrayEquals(
            arrayOf(at(now - 29 * day), at(now - day)),
            IncidentLog.pruned(aged, now),
        )
        // Inside retention nothing moves — and the same array comes back, unallocated.
        val young = arrayOf(at(now - 2 * day), at(now - day))
        assertTrue(IncidentLog.pruned(young, now) === young)
    }

    @Test
    fun `a clock that jumps backwards prunes nothing`() {
        // For a record the failure direction is loss: a watch whose clock stepped back a year
        // must not read its whole log as ancient... which it would not anyway, but the inverse —
        // a *forward* jump ages entries out, and a backward one must simply keep everything.
        val entries = arrayOf(at(5_000L), at(6_000L))
        assertArrayEquals(entries, IncidentLog.pruned(entries, nowMillis = 1_000L))
    }

    @Test
    fun `a log survives a round trip through storage`() {
        assertArrayEquals(log, IncidentLog.parse(IncidentLog.pack(log)))
    }

    @Test
    fun `an empty log packs and unpacks to nothing`() {
        assertEquals("", IncidentLog.pack(IncidentLog.EMPTY))
        assertEquals(0, IncidentLog.parse("").size)
        assertEquals(0, IncidentLog.parse(null).size)
    }

    @Test
    fun `a corrupt entry costs one entry, not the log`() {
        // The alternative is a parse that throws on the service's start path, which would mean a
        // dead-man's switch that never arms because of a bad character in a preferences file.
        assertArrayEquals(
            arrayOf(at(1_000L), at(3_000L)),
            IncidentLog.parse("1000,,rubbish,-4,0,3000"),
        )
    }

    @Test
    fun `an incident from a previous watch is retired`() {
        assertTrue(IncidentLog.belongsToEarlierWatch(incidentMillis = 900L, shiftStartMillis = 1_000L))
    }

    @Test
    fun `an incident from the watch under way holds`() {
        // The defect this pins: with the incident held past the end of its own watch, the service
        // came back up on the next one, settled into the incident state instead of arming, and left
        // a new shift with no dead-man's switch at all and a full hub core to say so.
        assertFalse(IncidentLog.belongsToEarlierWatch(1_000L, 1_000L))
        assertFalse(IncidentLog.belongsToEarlierWatch(1_500L, 1_000L))
    }

    @Test
    fun `nothing is retired when there is no incident or no watch`() {
        assertFalse(IncidentLog.belongsToEarlierWatch(0L, 1_000L))
        assertFalse(IncidentLog.belongsToEarlierWatch(900L, 0L))
    }
}
