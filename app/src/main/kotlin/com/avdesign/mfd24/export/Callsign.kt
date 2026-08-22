// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.export

import java.security.MessageDigest

/**
 * A tactical callsign for this watch, derived and never entered.
 *
 * An exported log with no origin is an anonymous note — inadmissible at any debrief with more
 * than one watch in the room. The fix is identity *at the exit*, not in the store: the incident
 * log stays pure instants, and the [LogPacket] header carries who they belong to. Derived from
 * the device identity deterministically because a watch face has no keyboard worth typing a name
 * on, and because a callsign that survives reinstalls without anyone maintaining it is the only
 * kind that will actually be there when the log matters.
 *
 * The derivation is SHA-256 over a salted device id, not `hashCode()`: the hash is even where
 * `String.hashCode` is not, and there is no `Int.MIN_VALUE` corner where `absoluteValue` comes
 * back negative. Twenty prefixes by ninety-nine numbers is 1 980 callsigns — namesakes are
 * possible in a large crew, the way they are on any net, which is why the packet also carries
 * [shortId]: eight hex characters of the same digest, unique for any crew this side of a
 * regiment.
 *
 * **The prefix list and the salt are part of every issued identity.** Reorder, rename or re-salt
 * and every watch in the field gets a new callsign — that is a re-issue, not a refactor.
 */
object Callsign {

    /** @param deviceId a stable device identity; `Settings.Secure.ANDROID_ID` on the watch. */
    fun of(deviceId: String): String {
        val h = hash32(deviceId)
        val prefix = PREFIXES[(h % PREFIXES.size.toLong()).toInt()]
        val number = (h / PREFIXES.size % 99L).toInt() + 1
        return "%s-%02d".format(prefix, number)
    }

    /** The disambiguator beside the callsign: the first four digest bytes, hex, uppercase. */
    fun shortId(deviceId: String): String =
        digest(deviceId).take(4).joinToString("") { "%02X".format(it) }

    private fun hash32(deviceId: String): Long {
        val d = digest(deviceId)
        return (d[0].toLong() and 0xFF shl 24) or (d[1].toLong() and 0xFF shl 16) or
            (d[2].toLong() and 0xFF shl 8) or (d[3].toLong() and 0xFF)
    }

    private fun digest(deviceId: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((SALT + deviceId).toByteArray(Charsets.US_ASCII))

    /** The salt keeps the raw ANDROID_ID out of the packet: the hash travels, the id does not. */
    private const val SALT = "MFD24"

    val PREFIXES = arrayOf(
        "ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOX", "GHOST",
        "HAWK", "INDIGO", "KILO", "LIMA", "NOMAD", "OMEGA", "RAVEN",
        "SIERRA", "TANGO", "VALKYRIE", "VIPER", "WRAITH", "ZULU",
    )
}
