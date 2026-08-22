// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 R. Kravcov

package com.avdesign.mfd24.geo

/**
 * On-disk layout of `poi_v1.bin`, shared verbatim between the generator (`:tools:poi`) and the
 * watch face reader so the two can never disagree about the format.
 *
 * ```
 * header (1036 B, little-endian)
 *   magic    u32   "EXP1"
 *   version  u16
 *   reserved u16
 *   count    u32   number of records
 *   buckets  u32[256]  record index of the first record whose (morton ushr 24) >= bucket
 *
 * record (20 B), sorted ascending by unsigned morton key
 *   morton   u32
 *   lat      f32   degrees
 *   lon      f32   degrees
 *   type     u8    TYPE_*
 *   flags    u8    bitfield of FLAG_*
 *   code     u8[6] ASCII identifier, NUL-padded. Aerodromes carry ICAO, never IATA — the METAR
 *            fetch keys on the code, and a mixed column burned once: Hostomel was ICAO while
 *            Zhuliany was IATA, and nothing could tell which was which. Ports carry UN-LOCODE 5;
 *            the few fields with no ICAO assigned keep a local code, which the four-letter gate
 *            in the METAR path simply declines.
 * ```
 */
object PoiFormat {

    /** ASCII "EXP1" read as a little-endian u32. */
    const val MAGIC: Int = 0x31505845
    const val VERSION: Int = 1

    const val BUCKET_COUNT: Int = 256
    const val HEADER_BYTES: Int = 4 + 2 + 2 + 4 + BUCKET_COUNT * 4

    const val RECORD_BYTES: Int = 20
    const val CODE_BYTES: Int = 6

    const val OFFSET_MORTON: Int = 0
    const val OFFSET_LAT: Int = 4
    const val OFFSET_LON: Int = 8
    const val OFFSET_TYPE: Int = 12
    const val OFFSET_FLAGS: Int = 13
    const val OFFSET_CODE: Int = 14

    const val TYPE_AIRPORT: Int = 0
    const val TYPE_PORT: Int = 1
    const val TYPE_SPACEPORT: Int = 2

    /**
     * Military airfield or naval base. Chooses the pictogram — a fast jet rather than an airliner,
     * a warship rather than a merchantman — but does **not** affect [priorityOf]: which site is the
     * more interesting readout is a question about the kind of place, not about who owns it. The
     * helipad flag is the exception, because "rotorcraft only" really does say how significant a
     * place is.
     */
    const val FLAG_MILITARY: Int = 1

    /** Rotorcraft-only aerodrome. Beats [FLAG_MILITARY] when choosing a pictogram. */
    const val FLAG_HELIPAD: Int = 2

    const val ASSET_NAME: String = "poi_v1.bin"

    /**
     * Tie-break order when several sites fall inside the search radius: a spaceport beats an
     * airfield beats a port beats a helipad, because that is the more interesting readout on a
     * tactical dial. Distance only decides between sites of equal rank.
     *
     * A helipad ranks *below* a port rather than with the airfields it shares a type with. It is
     * a landing spot, not a facility, and it was masking things that matter: standing on the naval
     * base at Toulon reported a naval air station's helipad four kilometres away, because any
     * aerodrome outranked any port at any distance.
     */
    fun priorityOf(type: Int, flags: Int): Int = when {
        type == TYPE_SPACEPORT -> 3
        type == TYPE_AIRPORT && flags and FLAG_HELIPAD == 0 -> 2
        type == TYPE_PORT -> 1
        else -> 0
    }
}
