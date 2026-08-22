// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import com.avdesign.mfd24.data.IncidentRecord
import java.time.Instant
import java.util.zip.CRC32

/**
 * The incident log as the text that goes over the air.
 *
 * Plain lines, not a binary frame: whoever decodes this is reading a terminal, and a record that
 * needs a second tool to interpret is a record nobody will read. The second line says *whose*
 * instants these are — [Callsign] plus its short id — because an unattributed log is an anonymous
 * note the moment two watches share a debrief. Identity lives here, at the exit, and not in the
 * store: the log itself stays pure instants. The `WATCH` line names the shift those instants
 * belong to — the log covers one watch and is emptied by the next, so without it the times would
 * be readings with no scale beside them. Then one incident per line in ISO-8601 Zulu — the
 * same clock the dial prints and the editor lists, so the transmission cannot disagree with
 * either. The `END` line carries the count and a CRC32 of everything above it, because an
 * acoustic channel drops characters and a debrief needs to know whether it got them all; both are
 * checkable by eye and by one line of shell.
 *
 * CRLF line ends, because the receiving side is a serial-terminal habit and has been since before
 * the modulation this rides on.
 */
object LogPacket {

    fun build(
        log: Array<IncidentRecord>,
        callsign: String,
        shortId: String,
        shiftStartMillis: Long = 0L,
        shiftEndMillis: Long = 0L,
    ): String {
        val body = StringBuilder(HEADER)
        body.append("CALLSIGN ").append(callsign).append(" ID ").append(shortId).append(CRLF)
        // Which watch these instants belong to. Without it every line is a bare moment: "20:22Z"
        // cannot say whether that was ten minutes into a night watch or the last hour of a
        // sixteen-hour one, and that is most of what a debrief wants to know.
        if (shiftStartMillis > 0L && shiftEndMillis > shiftStartMillis) {
            body.append("WATCH ").append(zulu(shiftStartMillis))
                .append(" TO ").append(zulu(shiftEndMillis))
                .append(" DUR ").append(duration(shiftEndMillis - shiftStartMillis))
                .append(CRLF)
        }
        for (record in log) {
            body.append(zulu(record.atMillis))
            // Columns, not prose, and only when there is a reading: a line that says HR says it in
            // the same place every time, so a debrief can cut the field out with one shell command.
            if (record.hasBpm) body.append(" HR ").append(pad(record.bpm))
            if (record.hasBaseline) {
                body.append(" BASE ").append(pad(record.baselineBpm))
                    .append(" AT ").append(zulu(record.baselineAtMillis))
            }
            body.append(CRLF)
        }
        val crc = CRC32().apply { update(body.toString().toByteArray(Charsets.US_ASCII)) }
        return body
            .append("END ").append(log.size).append(" CRC32 ")
            .append("%08X".format(crc.value)).append(CRLF)
            .toString()
    }

    /** Whole seconds: the log's precision is the dial's, and millis would be false exactness. */
    private fun zulu(millis: Long): String =
        Instant.ofEpochMilli(millis / 1000L * 1000L).toString()

    /** `08:00` — the booked length of the watch, in hours and minutes. */
    private fun duration(millis: Long): String {
        val minutes = millis / 60_000L
        return "%02d:%02d".format(minutes / 60L, minutes % 60L)
    }

    /** Three digits, so the columns line up under each other on a fixed-width terminal. */
    private fun pad(bpm: Int): String = if (bpm < 100) "0$bpm" else bpm.toString()

    const val HEADER: String = "MFD24 INCIDENT LOG\r\n"
    private const val CRLF = "\r\n"
}
