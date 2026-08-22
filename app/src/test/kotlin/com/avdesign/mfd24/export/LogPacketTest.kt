// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import com.avdesign.mfd24.data.IncidentRecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/**
 * The over-the-air text: the receiving end is a human at a terminal, so the format is pinned
 * character for character — Zulu timestamps, a count, and a checksum that one line of shell can
 * verify.
 */
class LogPacketTest {

    @Test
    fun `a packet is header, identity, zulu lines, then a checkable END line`() {
        val packet = LogPacket.build(arrayOf(IncidentRecord(1_787_230_037_000L)), "RAVEN-42", "10396EB9")
        val lines = packet.split("\r\n")
        assertEquals("MFD24 INCIDENT LOG", lines[0])
        assertEquals("CALLSIGN RAVEN-42 ID 10396EB9", lines[1])
        assertEquals("2026-08-20T12:47:17Z", lines[2])
        assertTrue(lines[3].startsWith("END 1 CRC32 "))
        assertEquals("", lines[4])
    }

    @Test
    fun `the crc covers everything above the END line`() {
        val packet = LogPacket.build(
            arrayOf(IncidentRecord(1_787_230_037_000L), IncidentRecord(1_787_240_000_000L)), "RAVEN-42", "10396EB9",
        )
        val body = packet.substringBeforeLast("END ")
        val expected = CRC32().apply { update(body.toByteArray(Charsets.US_ASCII)) }.value
        val stated = packet.substringAfterLast("CRC32 ").trim().toLong(16)
        assertEquals(expected, stated)
    }

    @Test
    fun `an empty log still transmits a verifiable frame`() {
        // Sending "END 0" with a good checksum tells the debrief there is genuinely nothing on
        // file — silence would only say the transmission failed.
        val lines = LogPacket.build(emptyArray(), "RAVEN-42", "10396EB9").split("\r\n")
        assertEquals("MFD24 INCIDENT LOG", lines[0])
        assertEquals("CALLSIGN RAVEN-42 ID 10396EB9", lines[1])
        assertTrue(lines[2].startsWith("END 0 CRC32 "))
    }
}
