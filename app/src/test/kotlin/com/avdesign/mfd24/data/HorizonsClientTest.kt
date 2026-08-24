// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * The Horizons parse, held to a real payload: the block below is what the API actually returned
 * for MRO from a Jezero site on 2026-08-23 (COMMAND='-74', CENTER='coord@499',
 * SITE_COORD='-77.4508,18.6320,0' — west-positive longitude, planetographic latitude), trimmed
 * to the final rows of the table. The rise through 10 degrees between 03:54 and 03:56 is a real
 * MRO pass; the trailing `e` marker on the last row is the API's own elevation flag and the
 * parser must read past it.
 */
class HorizonsClientTest {

    private val mroTail = """API VERSION: 1.2
API SOURCE: NASA/JPL Horizons API

*******************************************************************************
Target body name: Mars Reconnaissance Orbiter (spacecraft) (-74) {source: MRO_MERGED}
Center body name: Mars (499)                      {source: MRO_MERGED}
*******************************************************************************
 Date__(UT)__HR:MN, , , Azimuth_(a-app), Elevation_(a-app),
**************************************************
${'$'}${'$'}SOE
 2026-Aug-23 03:38, , ,   2.778460,   -28.774513,
 2026-Aug-23 03:40, , ,   2.545683,   -25.121839,
 2026-Aug-23 03:42, , ,   2.148493,   -21.370503,
 2026-Aug-23 03:44, , ,   1.538156,   -17.481309,
 2026-Aug-23 03:46, , ,   0.633305,   -13.391500,
 2026-Aug-23 03:48, , , 359.290390,    -8.994130,
 2026-Aug-23 03:50, , , 357.234443,    -4.092071,
 2026-Aug-23 03:52, ,r, 353.872553,     1.715704,
 2026-Aug-23 03:54, , , 347.683457,     9.343773,
 2026-Aug-23 03:56, , , 333.637949,    20.945964,
 2026-Aug-23 03:58, , , 292.889864,    35.770137,
 2026-Aug-23 04:00, ,e, 236.291238,    27.079635,
${'$'}${'$'}EOE
**************************************************
"""

    @Test
    fun `a real table parses row for row`() {
        val table = HorizonsTable()
        assertEquals(HorizonsClient.OK, HorizonsClient.parse(mroTail, table))
        assertEquals(12, table.count)
        assertEquals(Instant.parse("2026-08-23T03:38:00Z").toEpochMilli(), table.timesMillis[0])
        assertEquals(Instant.parse("2026-08-23T04:00:00Z").toEpochMilli(), table.timesMillis[11])
        assertEquals(35.770137, table.elevationDeg[10], 1e-9)
        assertEquals(-28.774513, table.elevationDeg[0], 1e-9)
    }

    @Test
    fun `the real pass rises through the threshold where the samples say`() {
        val table = HorizonsTable()
        assertEquals(HorizonsClient.OK, HorizonsClient.parse(mroTail, table))
        val start = LongArray(8)
        val end = LongArray(8)
        val count = RelayWindows.extract(table, 10.0, start, end)
        assertEquals(1, count)
        // Linear interpolation between 03:54 (9.34) and 03:56 (20.95) puts 10 degrees at
        // about 03:54:07; the pass is still under way when the table ends, so the window
        // closes at the last sample rather than pretending to know the set.
        assertEquals(
            Instant.parse("2026-08-23T03:54:07Z").toEpochMilli().toDouble(),
            start[0].toDouble(),
            2_000.0,
        )
        assertEquals(Instant.parse("2026-08-23T04:00:00Z").toEpochMilli(), end[0])
    }

    @Test
    fun `truncated, empty and disordered payloads are refused whole`() {
        val table = HorizonsTable()
        // A body with no block and no explanation could be anything: a failure, retried soon.
        assertEquals(
            HorizonsClient.FAILED,
            HorizonsClient.parse("API VERSION: 1.2\nsystem temporarily unavailable", table),
        )
        // A block that opens and never closes.
        assertEquals(
            HorizonsClient.FAILED,
            HorizonsClient.parse("$\$SOE\n 2026-Aug-23 03:38, , , 1.0, 2.0,\n", table),
        )
        // An empty block.
        assertEquals(HorizonsClient.FAILED, HorizonsClient.parse("$\$SOE\n$\$EOE", table))
        // A garbled row poisons the whole table, not just itself.
        assertEquals(
            HorizonsClient.FAILED,
            HorizonsClient.parse(
                "$\$SOE\n 2026-Aug-23 03:38, , , 1.0, junk,\n$\$EOE", table,
            ),
        )
        // Time running backwards is a table nothing downstream may trust.
        assertEquals(
            HorizonsClient.FAILED,
            HorizonsClient.parse(
                "$\$SOE\n 2026-Aug-23 03:40, , , 1.0, 2.0,\n 2026-Aug-23 03:38, , , 1.0, 3.0,\n$\$EOE",
                table,
            ),
        )
    }

    /** The API's real answer for MAVEN on 2026-08-23: its published trajectory simply ends. */
    @Test
    fun `no published trajectory is its own answer, not a failure`() {
        val table = HorizonsTable()
        assertEquals(
            HorizonsClient.NO_COVERAGE,
            HorizonsClient.parse(
                "API VERSION: 1.2\nAPI SOURCE: NASA/JPL Horizons API\n\n\n" +
                    "No ephemeris for target \"MAVEN (spacecraft)\" after A.D. 2026-MAR-01 00:58:50.8146 UT",
                table,
            ),
        )
    }
}
